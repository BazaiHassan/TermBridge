package io.termbridge.core.transport

import io.termbridge.core.crypto.KeyPair
import io.termbridge.core.crypto.NoiseException
import io.termbridge.core.crypto.NoiseIK
import io.termbridge.core.crypto.NoiseTransport
import io.termbridge.core.proto.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One WebSocket to one address, secured with Noise IK (PROTOCOL.md §1.2). The first two messages
 * are the handshake; after that each message is one encrypted frame.
 */
internal class SecureChannel(
    val endpoint: Endpoint,
    agentStatic: ByteArray,
    device: KeyPair,
    private val payload: ByteArray,
    private val events: Events,
) {
    interface Events {
        fun onEstablished(channel: SecureChannel)

        /** A decrypted frame; called on OkHttp's reader thread. */
        fun onFrame(channel: SecureChannel, frame: ByteArray)

        /** The attempt ended before the handshake completed. */
        fun onFailed(channel: SecureChannel, failure: Failure)

        /** An established channel ended. */
        fun onClosed(channel: SecureChannel, reason: String, error: Boolean)
    }

    enum class FailureKind { UNREACHABLE, REJECTED, PROTOCOL }

    data class Failure(val kind: FailureKind, val detail: String)

    private val handshake = NoiseIK(initiator = true, localStatic = device, remoteStatic = agentStatic)
    private val sendLock = Any()
    private val done = AtomicBoolean(false)

    @Volatile private var transport: NoiseTransport? = null

    @Volatile private var sentMsg1 = false
    private lateinit var socket: WebSocket

    fun start(open: (Request, WebSocketListener) -> WebSocket) {
        val request = Request.Builder()
            .url(endpoint.url)
            .header("Sec-WebSocket-Protocol", Protocol.SUBPROTOCOL)
            .build()
        socket = open(request, Listener())
    }

    /** Encrypts and sends one frame. Encryption and enqueueing are atomic so nonces stay in order. */
    fun send(frame: ByteArray): Boolean = synchronized(sendLock) {
        val t = transport ?: return false
        !done.get() && socket.send(t.encrypt(frame).toByteString())
    }

    fun close() {
        if (::socket.isInitialized) socket.close(NORMAL_CLOSURE, "bye")
        done.set(true)
    }

    /** Abandons this attempt silently (another address won the race). */
    fun cancel() {
        done.set(true)
        if (::socket.isInitialized) socket.cancel()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (response.header("Sec-WebSocket-Protocol") != Protocol.SUBPROTOCOL) {
                failed(FailureKind.PROTOCOL, "$endpoint does not speak ${Protocol.SUBPROTOCOL}")
                webSocket.cancel()
                return
            }
            val msg1 = try {
                handshake.writeMessage(payload)
            } catch (e: NoiseException) {
                failed(FailureKind.PROTOCOL, "Invalid agent key: ${e.message}")
                webSocket.cancel()
                return
            }
            sentMsg1 = true
            webSocket.send(msg1.toByteString())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (done.get()) return
            val t = transport
            if (t == null) {
                try {
                    handshake.readMessage(bytes.toByteArray())
                    transport = handshake.split()
                } catch (e: NoiseException) {
                    // msg2 that does not decrypt: this is not the computer we paired with.
                    failed(FailureKind.REJECTED, "The computer at $endpoint could not prove its identity")
                    webSocket.cancel()
                    return
                }
                events.onEstablished(this@SecureChannel)
                return
            }
            val frame = try {
                t.decrypt(bytes.toByteArray())
            } catch (e: NoiseException) {
                webSocket.cancel()
                ended("Received corrupted data; the connection was closed for safety", error = true)
                return
            }
            events.onFrame(this@SecureChannel, frame)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            webSocket.cancel()
            if (transport == null) failed(FailureKind.PROTOCOL, "Unexpected text message") else ended("Unexpected text message", error = true)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (transport == null) {
                failed(FailureKind.REJECTED, reason.ifBlank { "Closed during handshake" })
            } else {
                ended(reason.ifBlank { "The computer closed the connection" }, error = false)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            when {
                transport != null -> ended(describe(t, response), error = true)
                // The agent drops unknown phones right after msg1, without a reply.
                sentMsg1 -> failed(FailureKind.REJECTED, "Handshake refused by $endpoint")
                else -> failed(if (response?.code == 403) FailureKind.PROTOCOL else FailureKind.UNREACHABLE, describe(t, response))
            }
        }
    }

    private fun failed(kind: FailureKind, detail: String) {
        if (done.compareAndSet(false, true)) events.onFailed(this, Failure(kind, detail))
    }

    private fun ended(reason: String, error: Boolean) {
        if (done.compareAndSet(false, true)) events.onClosed(this, reason, error)
    }

    private fun describe(t: Throwable, response: Response?): String = when {
        response?.code == 403 -> "$endpoint only accepts devices on its local network"
        t is ConnectException || t is NoRouteToHostException -> "Nothing answered at $endpoint"
        t is SocketTimeoutException -> "Timed out reaching $endpoint"
        t is UnknownHostException -> "Unknown host ${endpoint.host}"
        else -> t.message ?: t.javaClass.simpleName
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
