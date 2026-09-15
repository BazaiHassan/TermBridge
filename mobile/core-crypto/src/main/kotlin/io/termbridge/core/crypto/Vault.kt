package io.termbridge.core.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals small secrets. Production: [KeystoreBox]; tests: an in-memory fake. */
interface SecretBox {
    fun seal(plaintext: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-256-GCM under a non-exportable Android Keystore key, StrongBox-backed when the phone has
 * one (docs/adr/0005 #4). Output: 12-byte IV ‖ ciphertext ‖ tag.
 */
class KeystoreBox(private val alias: String = "termbridge.vault.v1") : SecretBox {
    override fun seal(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray): ByteArray {
        require(sealed.size > IV_SIZE) { "sealed value too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_SIZE))
        }
        return cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE)
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return try {
            generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (e: ProviderException) { // StrongBoxUnavailableException is a ProviderException
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

/** Named secrets, sealed by a [SecretBox] and persisted in a DataStore. */
class Vault(private val store: DataStore<Preferences>, private val box: SecretBox) {
    fun observe(name: String): Flow<ByteArray?> = store.data.map { prefs -> prefs[key(name)]?.let(::unseal) }

    suspend fun read(name: String): ByteArray? = observe(name).first()

    suspend fun write(name: String, value: ByteArray) {
        val sealed = Base64.getEncoder().encodeToString(box.seal(value))
        store.edit { it[key(name)] = sealed }
    }

    suspend fun delete(name: String) {
        store.edit { it.remove(key(name)) }
    }

    private fun unseal(encoded: String): ByteArray = box.open(Base64.getDecoder().decode(encoded))

    private fun key(name: String) = stringPreferencesKey(name)

    companion object {
        fun create(context: Context, box: SecretBox = KeystoreBox()): Vault =
            Vault(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("vault") }, box)
    }
}
