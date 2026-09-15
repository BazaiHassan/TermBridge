package io.termbridge.core.crypto

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** This phone's long-term Noise static key, created on first use and kept sealed in the [Vault]. */
@Singleton
class DeviceIdentity @Inject constructor(private val vault: Vault) {
    private val mutex = Mutex()
    private var cached: KeyPair? = null

    suspend fun keyPair(): KeyPair = mutex.withLock {
        cached ?: (vault.read(KEY)?.let { KeyPair(it, X25519.publicKey(it)) } ?: X25519.generate().also { vault.write(KEY, it.private) })
            .also { cached = it }
    }

    private companion object {
        const val KEY = "device_static_x25519"
    }
}

/** A computer this phone has paired with. [agentId] is its identity; addresses may change over time. */
@Serializable
data class PairedMachine(
    val agentId: String,
    val name: String,
    val addresses: List<String>,
    val pairedAt: Long,
    val lastConnectedAt: Long = 0,
)

/** Paired machines, sealed in the [Vault] (architecture §7.6). */
@Singleton
class MachineStore @Inject constructor(private val vault: Vault) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    /** Most recently used first. */
    val machines: Flow<List<PairedMachine>> = vault.observe(KEY).map { decode(it).sortedByDescending { m -> maxOf(m.lastConnectedAt, m.pairedAt) } }

    suspend fun get(agentId: String): PairedMachine? = decode(vault.read(KEY)).firstOrNull { it.agentId == agentId }

    suspend fun upsert(machine: PairedMachine) = update { list -> list.filterNot { it.agentId == machine.agentId } + machine }

    /** Records a successful connection; [via] (the address that won) is tried first next time. */
    suspend fun markConnected(agentId: String, via: String? = null, at: Long = System.currentTimeMillis()) = update { list ->
        list.map { m ->
            if (m.agentId != agentId) return@map m
            val addresses = if (via != null && via in m.addresses) listOf(via) + (m.addresses - via) else m.addresses
            m.copy(lastConnectedAt = at, addresses = addresses)
        }
    }

    suspend fun remove(agentId: String) = update { list -> list.filterNot { it.agentId == agentId } }

    private suspend fun update(transform: (List<PairedMachine>) -> List<PairedMachine>) = mutex.withLock {
        vault.write(KEY, json.encodeToString(transform(decode(vault.read(KEY)))).encodeToByteArray())
    }

    private fun decode(raw: ByteArray?): List<PairedMachine> =
        raw?.let { runCatching { json.decodeFromString<List<PairedMachine>>(it.decodeToString()) }.getOrNull() }.orEmpty()

    private companion object {
        const val KEY = "paired_machines"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {
    @Provides
    @Singleton
    fun vault(@ApplicationContext context: Context): Vault = Vault.create(context)
}
