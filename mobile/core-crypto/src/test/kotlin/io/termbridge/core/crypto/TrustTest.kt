package io.termbridge.core.crypto

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TrustTest {
    /** Reversible stand-in for the Keystore; proves values are stored sealed, not in clear. */
    private class XorBox : SecretBox {
        override fun seal(plaintext: ByteArray) = ByteArray(plaintext.size) { (plaintext[it].toInt() xor 0x5A).toByte() }
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    private fun TestScope.vault(dir: File): Vault =
        Vault(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(dir, "vault.preferences_pb") }, XorBox())

    @Test
    fun deviceKeyIsCreatedOnceAndPersists() = runTest(UnconfinedTestDispatcher()) {
        val dir = Files.createTempDirectory("vault").toFile()
        val v = vault(dir)
        val first = DeviceIdentity(v).keyPair()
        val again = DeviceIdentity(v).keyPair()
        assertContentEquals(first.private, again.private)
        assertContentEquals(X25519.publicKey(first.private), first.public)
        val onDisk = dir.listFiles()!!.single().readBytes()
        assertFalse(onDisk.toList().windowed(first.private.size).any { it.toByteArray().contentEquals(first.private) }, "private key stored in clear")
    }

    @Test
    fun machineStoreUpsertsMarksAndRemoves() = runTest(UnconfinedTestDispatcher()) {
        val store = MachineStore(vault(Files.createTempDirectory("vault").toFile()))
        store.upsert(PairedMachine("A", "laptop", listOf("192.168.1.2:7423"), pairedAt = 1))
        store.upsert(PairedMachine("B", "desktop", listOf("192.168.1.3:7423"), pairedAt = 2))
        store.markConnected("A", at = 10)
        assertEquals(listOf("A", "B"), store.machines.first().map { it.agentId })
        store.upsert(PairedMachine("B", "desktop-2", listOf("10.0.0.3:7423"), pairedAt = 3))
        assertEquals("desktop-2", store.get("B")?.name)
        store.remove("A")
        assertNull(store.get("A"))
    }
}
