package io.termbridge.core.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Finds agents on the local network with DNS-SD (PROTOCOL.md §9.5), so a computer whose IP
 * changed is still reached. Discovery runs only while someone collects a flow.
 */
@Singleton
class LanDiscovery @Inject constructor(@ApplicationContext context: Context) {
    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    /** `host:port` addresses advertised by [agentId] (standard base64), as they are found. */
    fun addressesOf(agentId: String): Flow<List<String>> =
        agents().map { it[agentId].orEmpty().sorted() }.distinctUntilChanged().filter { it.isNotEmpty() }

    /** Every advertising agent: agent ID (standard base64) → addresses. */
    fun agents(): Flow<Map<String, Set<String>>> = callbackFlow {
        val manager = nsd ?: run {
            close()
            return@callbackFlow
        }
        val found = ConcurrentHashMap<String, MutableSet<String>>()
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.trySend(info)
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit // keep the last known address
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        // NsdManager resolves one service at a time on older Android: do them in order.
        val resolver = launch {
            for (info in toResolve) {
                val (id, address) = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(manager, info) } ?: continue
                found.getOrPut(id) { ConcurrentHashMap.newKeySet() }.add(address)
                trySend(found.mapValues { it.value.toSet() })
            }
        }
        awaitClose {
            runCatching { manager.stopServiceDiscovery(listener) }
            resolver.cancel()
        }
    }

    private suspend fun resolve(manager: NsdManager, info: NsdServiceInfo): Pair<String, String>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) resolveModern(manager, info) else resolveLegacy(manager, info)

    @Suppress("DEPRECATION") // the only resolver below API 34
    private suspend fun resolveLegacy(manager: NsdManager, info: NsdServiceInfo): Pair<String, String>? =
        suspendCancellableCoroutine { cont ->
            manager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = cont.resume(null)
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) =
                        cont.resume(match(serviceInfo.attributes["id"], listOfNotNull(serviceInfo.host), serviceInfo.port))
                },
            )
        }

    private suspend fun resolveModern(manager: NsdManager, info: NsdServiceInfo): Pair<String, String>? =
        suspendCancellableCoroutine { cont ->
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    val result = match(serviceInfo.attributes["id"], serviceInfo.hostAddresses, serviceInfo.port)
                    runCatching { manager.unregisterServiceInfoCallback(this) }
                    if (cont.isActive) cont.resume(result)
                }
                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            manager.registerServiceInfoCallback(info, executor, callback)
            cont.invokeOnCancellation { runCatching { manager.unregisterServiceInfoCallback(callback) } }
        }

    companion object {
        /** NsdManager wants the type without `.local`. */
        const val SERVICE_TYPE = "_termbridge._tcp"
        private const val RESOLVE_TIMEOUT_MS = 3_000L

        /**
         * Turns a resolved record into (agent ID in standard base64, `host:port`), preferring
         * IPv4; null when the record is not a TermBridge agent.
         */
        internal fun match(rawId: ByteArray?, hosts: List<InetAddress>, port: Int): Pair<String, String>? {
            val id = agentIdFromTxt(rawId) ?: return null
            val host = hosts.firstOrNull { it is Inet4Address } ?: hosts.firstOrNull() ?: return null
            if (port !in 1..65535) return null
            val literal = host.hostAddress?.substringBefore('%') ?: return null
            return id to Endpoint.Direct(literal, port).label
        }

        internal fun agentIdFromTxt(raw: ByteArray?): String? {
            val text = raw?.decodeToString() ?: return null
            val bytes = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull() ?: return null
            return if (bytes.size == 32) Base64.getEncoder().encodeToString(bytes) else null
        }
    }
}
