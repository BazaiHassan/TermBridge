package io.termbridge.core.transport

import io.termbridge.core.crypto.KeyPair
import io.termbridge.core.proto.ErrorCodes
import io.termbridge.core.proto.Message
import io.termbridge.core.proto.MessageCodec
import io.termbridge.core.proto.Protocol
import io.termbridge.core.proto.ProtocolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One end-to-end encrypted connection to an agent (PROTOCOL.md §1.2, §4–5).
 *
 * Dialing: every address from the pairing QR is tried, each 250 ms after the previous one; the
 * first Noise handshake to complete wins and the others are dropped (PROTOCOL.md §8).
 *
 * Then: HELLO handshake, keepalive with RTT measurement, session multiplexing. Incoming frames
 * are dispatched on OkHttp's reader thread straight to session listeners; outgoing keystrokes are
 * encrypted and enqueued immediately, one frame each, never coalesced.
 */
class TermBridgeConnection internal constructor(
    val machineName: String,
    private val endpoints: List<Endpoint>,
    private val agentStatic: ByteArray,
    private val device: KeyPair,
    private val handshakePayload: ByteArray,
    private val clientName: String,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val openSocket: (Request, WebSocketListener) -> WebSocket,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _rttMillis = MutableStateFlow<Long?>(null)

    /** Round-trip time of the last PING, or null before the first PONG. */
    val rttMillis: StateFlow<Long?> = _rttMillis.asStateFlow()

    /** The address that won the race, once connected. */
    val endpoint: Endpoint? get() = channel?.endpoint

    private class PendingOpen(val listener: SessionListener, val result: CompletableDeferred<RemoteSession>)

    private val sessions = ConcurrentHashMap<Int, RemoteSession>()
    private val pendingOpens = ConcurrentLinkedQueue<PendingOpen>()
    private val attempts = CopyOnWriteArrayList<SecureChannel>()
    private val failures = CopyOnWriteArrayList<SecureChannel.Failure>()
    private val openLock = Any()
    private val winLock = Any()
    private val finished = AtomicBoolean(false)

    @Volatile private var channel: SecureChannel? = null

    @Volatile private var awaitingPong = false
    private var missedPongs = 0
    private var dialing: Job? = null
    private var keepalive: Job? = null
    private var handshakeTimeout: Job? = null

    internal fun start() {
        require(endpoints.isNotEmpty()) { "no address to connect to" }
        val direct = endpoints.filter { it.direct }
        val relayed = endpoints.filterNot { it.direct }
        dialing = scope.launch(dispatcher) {
            launch {
                direct.forEachIndexed { i, endpoint ->
                    if (i > 0) delay(ATTEMPT_STAGGER_MS)
                    if (channel != null || finished.get()) return@launch
                    dial(endpoint)
                }
            }
            if (relayed.isNotEmpty()) {
                launch {
                    // PROTOCOL.md §9.4: the relay joins after 750 ms, or at once when every
                    // direct path has already failed.
                    if (direct.isNotEmpty()) withTimeoutOrNull(RELAY_DELAY_MS) { directExhausted.await() }
                    if (channel != null || finished.get()) return@launch
                    relayed.forEach(::dial)
                }
            }
        }
        handshakeTimeout = scope.launch(dispatcher) {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (_state.value == ConnectionState.Connecting) fail(explainFailures(timedOut = true))
        }
    }

    private val directExhausted = CompletableDeferred<Unit>()

    private fun dial(endpoint: Endpoint) {
        SecureChannel(endpoint, agentStatic, device, handshakePayload, channelEvents).also {
            attempts += it
            it.start(openSocket)
        }
    }

    /**
     * Opens a shell of [cols]×[rows] on the agent. Output may reach [listener] before this
     * returns — it is registered the moment SESSION_OPENED arrives.
     */
    suspend fun openSession(cols: Int, rows: Int, listener: SessionListener, shell: String = "", cwd: String = ""): RemoteSession {
        val pending = PendingOpen(listener, CompletableDeferred())
        synchronized(openLock) { // replies are FIFO, so queue order must equal send order
            if (_state.value !is ConnectionState.Connected) throw IOException("Not connected")
            pendingOpens.add(pending)
            if (!send(Message.SessionOpen(shell, cwd, cols, rows))) {
                pendingOpens.remove(pending)
                throw IOException("Connection is closing")
            }
        }
        return pending.result.await()
    }

    /** Closes the connection; open sessions end with [SessionListener.onClosed]. */
    fun close() {
        channel?.close()
        attempts.forEach { if (it !== channel) it.cancel() }
        finish(ConnectionState.Closed("Disconnected"))
    }

    internal fun send(message: Message): Boolean =
        !finished.get() && channel?.send(MessageCodec.encode(message)) == true

    // ---- Channel events ------------------------------------------------------------------

    private val channelEvents = object : SecureChannel.Events {
        override fun onEstablished(channel: SecureChannel) = established(channel)
        override fun onFrame(channel: SecureChannel, frame: ByteArray) = received(channel, frame)
        override fun onFailed(channel: SecureChannel, failure: SecureChannel.Failure) = attemptFailed(channel, failure)
        override fun onClosed(channel: SecureChannel, reason: String, error: Boolean) = channelClosed(channel, reason, error)
    }

    private fun established(channel: SecureChannel) {
        synchronized(winLock) {
            if (this.channel != null || finished.get()) {
                channel.cancel()
                return
            }
            this.channel = channel
        }
        dialing?.cancel()
        attempts.forEach { if (it !== channel) it.cancel() }
        send(Message.Hello(client = clientName))
    }

    private fun received(channel: SecureChannel, frame: ByteArray) {
        if (channel !== this.channel) return
        val message = try {
            MessageCodec.decode(frame)
        } catch (e: ProtocolException) {
            fail("Malformed frame from the agent: ${e.message}")
            return
        }
        handle(message)
    }

    private fun attemptFailed(channel: SecureChannel, failure: SecureChannel.Failure) {
        failures += failure
        if (channel.endpoint.direct) directFailures.incrementAndGet()
        if (directFailures.get() >= endpoints.count { it.direct }) directExhausted.complete(Unit)
        if (this.channel == null && failures.size >= endpoints.size) fail(explainFailures(timedOut = false))
    }

    private val directFailures = java.util.concurrent.atomic.AtomicInteger()

    private fun channelClosed(channel: SecureChannel, reason: String, error: Boolean) {
        if (channel === this.channel) finish(if (error) ConnectionState.Failed(reason) else ConnectionState.Closed(reason))
    }

    // ---- Protocol ------------------------------------------------------------------------

    private fun handle(message: Message) {
        when (message) {
            is Message.Data -> sessions[message.session]?.listener?.onOutput(message.bytes, message.offset, message.length)
            is Message.HelloAck -> {
                handshakeTimeout?.cancel()
                _state.value = ConnectionState.Connected(
                    AgentInfo(message.agent, message.os, message.hostname, message.lanAddrs, message.wanAddrs),
                )
                startKeepalive()
            }
            is Message.SessionOpened -> pendingOpens.poll()?.let { open ->
                val session = RemoteSession(message.newSession, this, open.listener)
                sessions[message.newSession] = session
                open.result.complete(session)
            }
            is Message.SessionExit -> sessions.remove(message.session)?.listener?.onExit(message.exitCode)
            is Message.SessionClose -> sessions.remove(message.session)?.listener?.onClosed()
            is Message.Pong -> {
                awaitingPong = false
                _rttMillis.value = clock() - message.timestamp
            }
            is Message.Ping -> send(Message.Pong(message.timestamp))
            is Message.Error -> handleError(message)
            else -> Unit // unknown opcodes and app-bound-only frames are ignored (PROTOCOL.md §3)
        }
    }

    private fun handleError(error: Message.Error) {
        when {
            _state.value == ConnectionState.Connecting -> fail("The agent refused the connection: ${error.message}")
            error.session == Protocol.CONTROL_SESSION &&
                error.code in setOf(ErrorCodes.SESSION_OPEN_FAILED, ErrorCodes.TOO_MANY_SESSIONS, ErrorCodes.BAD_FRAME) ->
                pendingOpens.poll()?.result?.completeExceptionally(RemoteException(error.code, error.message))
        }
    }

    private fun startKeepalive() {
        keepalive = scope.launch(dispatcher) {
            while (isActive) {
                if (awaitingPong && ++missedPongs >= MAX_MISSED_PONGS) {
                    fail("$machineName stopped responding")
                    return@launch
                }
                if (!awaitingPong) missedPongs = 0
                awaitingPong = true
                send(Message.Ping(clock()))
                delay(KEEPALIVE_INTERVAL_MS)
            }
        }
    }

    /** One actionable sentence out of every attempt's failure. */
    private fun explainFailures(timedOut: Boolean): String {
        val pairing = handshakePayload.isNotEmpty()
        return when {
            failures.any { it.kind == SecureChannel.FailureKind.REJECTED } ->
                if (pairing) {
                    "$machineName didn't accept the pairing code. Codes work once and expire after 2 minutes; show a new QR code."
                } else {
                    "$machineName doesn't recognize this phone. It may have been revoked or reset; pair again."
                }
            failures.any { it.kind == SecureChannel.FailureKind.OFFLINE } ->
                "$machineName is offline: TermBridge isn't running there, or it can't reach the relay. Start it on the computer and try again."
            failures.any { it.kind == SecureChannel.FailureKind.PROTOCOL } -> failures.first { it.kind == SecureChannel.FailureKind.PROTOCOL }.detail
            else -> buildString {
                append(if (timedOut) "Timed out reaching " else "Can't reach ")
                append(machineName)
                append(" at ")
                append(endpoints.joinToString())
                append(". Make sure both are on the same Wi-Fi, the agent is running, and no VPN on the phone captures local traffic.")
                if (endpoints.none { !it.direct }) append(" To connect from other networks, set up a relay (termbridge relay <url>) and pair again.")
            }
        }
    }

    private fun fail(reason: String) {
        attempts.forEach { it.cancel() }
        finish(ConnectionState.Failed(reason))
    }

    private fun finish(terminal: ConnectionState) {
        if (!finished.compareAndSet(false, true)) return
        dialing?.cancel()
        keepalive?.cancel()
        handshakeTimeout?.cancel()
        _state.value = terminal
        sessions.values.forEach { it.listener.onClosed() }
        sessions.clear()
        while (true) {
            val open = pendingOpens.poll() ?: break
            open.result.completeExceptionally(IOException(terminal.toString()))
        }
    }

    private companion object {
        const val ATTEMPT_STAGGER_MS = 250L
        const val RELAY_DELAY_MS = 750L
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val KEEPALIVE_INTERVAL_MS = 15_000L
        const val MAX_MISSED_PONGS = 3
    }
}

/** A shell on the agent. All methods are non-blocking and safe from any thread. */
class RemoteSession internal constructor(
    val id: Int,
    private val connection: TermBridgeConnection,
    internal val listener: SessionListener,
) {
    /** Sends input; large pastes are split at the frame payload limit. */
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val n = minOf(end - pos, Protocol.MAX_PAYLOAD)
            connection.send(Message.Data(id, bytes, pos, n))
            pos += n
        }
    }

    fun resize(cols: Int, rows: Int) {
        connection.send(Message.Resize(id, cols, rows))
    }

    fun close() {
        connection.send(Message.SessionClose(id))
    }
}
