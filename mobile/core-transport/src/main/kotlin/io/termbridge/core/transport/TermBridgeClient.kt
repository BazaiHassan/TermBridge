package io.termbridge.core.transport

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.termbridge.core.crypto.AgentKeys
import io.termbridge.core.crypto.DeviceIdentity
import io.termbridge.core.proto.HandshakePayload
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

/** What to connect to: a paired machine, or one being paired right now. */
class ConnectTarget(
    val name: String,
    val agentId: String,
    val endpoints: List<Endpoint>,
    /** [HandshakePayload.CONNECT] normally; [HandshakePayload.pair] to redeem a QR code. */
    val handshakePayload: ByteArray = HandshakePayload.CONNECT,
)

/** Opens [TermBridgeConnection]s authenticated with this phone's [DeviceIdentity]. */
@Singleton
class TermBridgeClient @Inject constructor(
    private val okHttp: OkHttpClient,
    private val identity: DeviceIdentity,
    @ClientName private val clientName: String,
) {
    /**
     * Starts connecting at once; observe [TermBridgeConnection.state]. Work is bound to [scope].
     * Throws [io.termbridge.core.crypto.NoiseException] for an invalid agent ID.
     */
    suspend fun connect(target: ConnectTarget, scope: CoroutineScope): TermBridgeConnection {
        val agentStatic = AgentKeys.noiseStaticKey(target.agentId)
        val device = identity.keyPair()
        return TermBridgeConnection(
            machineName = target.name,
            endpoints = target.endpoints,
            agentStatic = agentStatic,
            device = device,
            handshakePayload = target.handshakePayload,
            clientName = clientName,
            scope = scope,
        ) { request, listener -> okHttp.newWebSocket(request, listener) }.also { it.start() }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object TransportModule {
    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .socketFactory(NoDelaySocketFactory())
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // liveness is the protocol's PING, not a socket timeout
        .build()
}

/** Disables Nagle's algorithm: a keystroke must not wait for the previous one's ACK. */
internal class NoDelaySocketFactory(private val delegate: SocketFactory = getDefault()) : SocketFactory() {
    private fun Socket.noDelay() = apply { tcpNoDelay = true }

    override fun createSocket(): Socket = delegate.createSocket().noDelay()
    override fun createSocket(host: String, port: Int): Socket = delegate.createSocket(host, port).noDelay()
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).noDelay()
    override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(host, port).noDelay()
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).noDelay()
}
