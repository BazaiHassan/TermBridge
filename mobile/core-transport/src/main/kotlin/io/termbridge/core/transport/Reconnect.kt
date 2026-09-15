package io.termbridge.core.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** Wait before reconnect attempt [attempt] (1-based): 1 s, 2 s, 4 s … capped at 30 s. */
fun reconnectDelayMillis(attempt: Int): Long = (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)

/** The phone's default network, so a dropped link is retried the moment another one is up. */
@Singleton
class NetworkMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    /** Emits the current default network at once, then every switch (Wi-Fi ↔ mobile data, VPN); null while offline. */
    val defaultNetwork: Flow<Network?> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            close()
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(network)
            }

            override fun onLost(network: Network) {
                trySend(null)
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
