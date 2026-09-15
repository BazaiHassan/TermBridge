package io.termbridge.core.transport

import io.termbridge.core.crypto.KeyPair
import io.termbridge.core.crypto.NoiseIK
import io.termbridge.core.crypto.NoiseTransport
import io.termbridge.core.crypto.X25519
import io.termbridge.core.proto.ErrorCodes
import io.termbridge.core.proto.HandshakePayload
import io.termbridge.core.proto.Message
import io.termbridge.core.proto.MessageCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.EOFException
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TermBridgeConnectionTest {
    /** Plays the agent: a real Noise IK responder behind a fake WebSocket. */
    private class FakeAgent(private val request: Request, private val agent: KeyPair, private val trusts: (ByteArray) -> Boolean) : WebSocket {
        lateinit var listener: WebSocketListener
        private val responder = NoiseIK(initiator = false, localStatic = agent, remoteStatic = null)
        private var transport: NoiseTransport? = null
        val received = mutableListOf<Message>()
        var msg1Payload: ByteArray? = null
        var cancelled = false

        override fun request() = request
        override fun queueSize() = 0L
        override fun send(text: String) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {
            cancelled = true
        }

        override fun send(bytes: ByteString): Boolean {
            val t = transport
            if (t == null) { // msg1
                msg1Payload = responder.readMessage(bytes.toByteArray())
                if (!trusts(responder.remoteStatic!!)) {
                    listener.onFailure(this, EOFException("dropped"), null) // silent drop
                    return true
                }
                val msg2 = responder.writeMessage()
                transport = responder.split()
                listener.onMessage(this, msg2.toByteString())
            } else {
                synchronized(received) { received += MessageCodec.decode(t.decrypt(bytes.toByteArray())) }
            }
            return true
        }

        fun open() = listener.onOpen(
            this,
            Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(101)
                .message("Switching Protocols").header("Sec-WebSocket-Protocol", "termbridge.v1").build(),
        )

        fun deliver(m: Message) = listener.onMessage(this, transport!!.encrypt(MessageCodec.encode(m)).toByteString())
    }

    private class Harness(scope: TestScope, addresses: Int = 1, payload: ByteArray = HandshakePayload.CONNECT, trusted: Boolean = true) {
        val agent = X25519.generate()
        val phone = X25519.generate()
        val sockets = mutableListOf<FakeAgent>()
        val conn = TermBridgeConnection(
            machineName = "box",
            endpoints = (1..addresses).map { Endpoint("192.168.1.$it") },
            agentStatic = agent.public,
            device = phone,
            handshakePayload = payload,
            clientName = "test/1",
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            clock = { scope.testScheduler.currentTime },
        ) { request, l ->
            FakeAgent(request, agent) { trusted && it.contentEquals(phone.public) }.also {
                it.listener = l
                sockets += it
            }
        }.also { it.start() }

        val socket get() = sockets.single()

        fun connect() {
            socket.open()
            socket.deliver(Message.HelloAck(1, "termbridge-agent/0.1.0", "linux", "box"))
        }
    }

    private class Recorder : SessionListener {
        val output = StringBuilder()
        var exitCode: Int? = null
        var closed = false
        override fun onOutput(bytes: ByteArray, offset: Int, length: Int) {
            output.append(bytes.decodeToString(offset, offset + length))
        }
        override fun onExit(exitCode: Int) {
            this.exitCode = exitCode
        }
        override fun onClosed() {
            closed = true
        }
    }

    @Test
    fun noiseHandshakeThenHelloOverEncryptedChannel() = runTest {
        val h = Harness(this)
        runCurrent()
        h.socket.open()
        assertEquals(listOf<Message>(Message.Hello(client = "test/1")), h.socket.received)
        assertEquals(ConnectionState.Connecting, h.conn.state.value)
        h.socket.deliver(Message.HelloAck(1, "agent", "linux", "box"))
        assertEquals(ConnectionState.Connected(AgentInfo("agent", "linux", "box")), h.conn.state.value)
    }

    @Test
    fun pairingPayloadTravelsInsideMsg1() = runTest {
        val payload = HandshakePayload.pair("AAECAwQF", "Pixel 8")
        val h = Harness(this, payload = payload)
        runCurrent()
        h.socket.open()
        assertContentEquals(payload, h.socket.msg1Payload)
    }

    @Test
    fun unknownPhoneIsToldToPairAgain() = runTest {
        val h = Harness(this, trusted = false)
        runCurrent()
        h.socket.open()
        val state = assertIs<ConnectionState.Failed>(h.conn.state.value)
        assertTrue("pair again" in state.reason, state.reason)
    }

    @Test
    fun rejectedPairingExplainsTheCode() = runTest {
        val h = Harness(this, payload = HandshakePayload.pair("AAAAAAAA", "Pixel"), trusted = false)
        runCurrent()
        h.socket.open()
        val state = assertIs<ConnectionState.Failed>(h.conn.state.value)
        assertTrue("new QR code" in state.reason, state.reason)
    }

    @Test
    fun secondAddressWinsWhenFirstIsSilent() = runTest {
        val h = Harness(this, addresses = 2)
        runCurrent()
        assertEquals(1, h.sockets.size) // staggered: second attempt only after 250 ms
        advanceTimeBy(251)
        runCurrent()
        assertEquals(2, h.sockets.size)
        h.sockets[1].open()
        h.sockets[1].deliver(Message.HelloAck(1, "a", "linux", "box"))
        assertIs<ConnectionState.Connected>(h.conn.state.value)
        assertEquals("192.168.1.2", h.conn.endpoint?.host)
        assertTrue(h.sockets[0].cancelled)
    }

    @Test
    fun allAddressesUnreachableGivesNetworkAdvice() = runTest {
        val h = Harness(this, addresses = 2)
        runCurrent()
        advanceTimeBy(251)
        runCurrent()
        h.sockets.forEach { it.listener.onFailure(it, ConnectException("refused"), null) }
        val state = assertIs<ConnectionState.Failed>(h.conn.state.value)
        assertTrue("same Wi-Fi" in state.reason, state.reason)
    }

    @Test
    fun threeMissedPongsFailTheConnection() = runTest {
        val h = Harness(this)
        runCurrent()
        h.connect()
        advanceTimeBy(15_000L * 3 + 1)
        runCurrent()
        assertIs<ConnectionState.Failed>(h.conn.state.value)
    }

    @Test
    fun pongMeasuresRoundTrip() = runTest {
        val h = Harness(this)
        runCurrent()
        h.connect()
        runCurrent()
        val ping = h.socket.received.last() as Message.Ping
        advanceTimeBy(40)
        h.socket.deliver(Message.Pong(ping.timestamp))
        assertEquals(40L, h.conn.rttMillis.value)
    }

    @Test
    fun sessionRepliesAreMatchedInOrderAndOutputRouted() = runTest {
        val h = Harness(this)
        runCurrent()
        h.connect()
        val first = Recorder()
        val a = async { h.conn.openSession(80, 24, first) }
        val b = async { h.conn.openSession(80, 24, Recorder()) }
        runCurrent()
        h.socket.deliver(Message.SessionOpened(3))
        h.socket.deliver(Message.SessionOpened(4))
        h.socket.deliver(Message.Data(3, "hi".encodeToByteArray()))
        h.socket.deliver(Message.SessionExit(3, 7))
        assertEquals(3, a.await().id)
        assertEquals(4, b.await().id)
        assertEquals("hi", first.output.toString())
        assertEquals(7, first.exitCode)
    }

    @Test
    fun openErrorFailsThePendingOpen() = runTest {
        val h = Harness(this)
        runCurrent()
        h.connect()
        val result = async { runCatching { h.conn.openSession(80, 24, Recorder()) } }
        runCurrent()
        h.socket.deliver(Message.Error(0, ErrorCodes.TOO_MANY_SESSIONS, "limit is 8"))
        assertEquals(ErrorCodes.TOO_MANY_SESSIONS, assertIs<RemoteException>(result.await().exceptionOrNull()).code)
    }

    @Test
    fun pasteIsSplitAtPayloadLimit() = runTest {
        val h = Harness(this)
        runCurrent()
        h.connect()
        val session = async { h.conn.openSession(80, 24, Recorder()) }
        runCurrent()
        h.socket.deliver(Message.SessionOpened(1))
        session.await().write(ByteArray(100_000))
        assertEquals(listOf(65_517, 100_000 - 65_517), h.socket.received.filterIsInstance<Message.Data>().map { it.length })
    }

    @Test
    fun endpointParsingAndUrls() {
        assertEquals(Endpoint("192.168.1.20", 7423), Endpoint.parse("192.168.1.20:7423"))
        assertEquals("ws://[fd00::1]:7423/v1", Endpoint("fd00::1").url)
    }
}
