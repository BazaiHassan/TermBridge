package io.termbridge.core.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.Blake2sDigest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom
import org.bouncycastle.math.ec.rfc7748.X25519 as BcX25519

class NoiseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** An X25519 key pair. */
class KeyPair(val private: ByteArray, val public: ByteArray)

object X25519 {
    const val KEY_SIZE = 32

    fun generate(random: SecureRandom = SecureRandom()): KeyPair {
        val private = ByteArray(KEY_SIZE).also { BcX25519.generatePrivateKey(random, it) }
        return KeyPair(private, publicKey(private))
    }

    /** [private] is used as-is; X25519 clamps it internally (RFC 7748). */
    fun publicKey(private: ByteArray): ByteArray =
        ByteArray(KEY_SIZE).also { BcX25519.scalarMultBase(private, 0, it, 0) }

    fun dh(private: ByteArray, public: ByteArray): ByteArray {
        val out = ByteArray(KEY_SIZE)
        if (!BcX25519.calculateAgreement(private, 0, public, 0, out, 0)) {
            throw NoiseException("X25519 produced an all-zero shared secret (low-order key)")
        }
        return out
    }
}

/** A Noise CipherState for ChaChaPoly: 96-bit nonce = 4 zero bytes + 64-bit little-endian counter. */
class CipherState internal constructor(private var key: ByteArray? = null) {
    private var nonce = 0L

    val hasKey: Boolean get() = key != null

    internal fun initializeKey(k: ByteArray) {
        key = k
        nonce = 0
    }

    fun encrypt(ad: ByteArray, plaintext: ByteArray, offset: Int = 0, length: Int = plaintext.size - offset): ByteArray {
        val k = key ?: return plaintext.copyOfRange(offset, offset + length)
        val out = ByteArray(length + TAG_SIZE)
        val aead = aead(true, k, ad)
        val n = aead.processBytes(plaintext, offset, length, out, 0)
        aead.doFinal(out, n)
        nonce++
        return out
    }

    fun decrypt(ad: ByteArray, ciphertext: ByteArray, offset: Int = 0, length: Int = ciphertext.size - offset): ByteArray {
        val k = key ?: return ciphertext.copyOfRange(offset, offset + length)
        if (length < TAG_SIZE) throw NoiseException("ciphertext shorter than the tag")
        val out = ByteArray(length - TAG_SIZE)
        val aead = aead(false, k, ad)
        try {
            val n = aead.processBytes(ciphertext, offset, length, out, 0)
            aead.doFinal(out, n)
        } catch (e: InvalidCipherTextException) {
            throw NoiseException("decryption failed", e)
        }
        nonce++
        return out
    }

    private fun aead(encrypt: Boolean, k: ByteArray, ad: ByteArray): ChaCha20Poly1305 {
        val iv = ByteArray(12)
        for (i in 0 until 8) iv[4 + i] = (nonce ushr (8 * i)).toByte()
        return ChaCha20Poly1305().apply { init(encrypt, AEADParameters(KeyParameter(k), TAG_SIZE * 8, iv, ad)) }
    }

    companion object {
        const val TAG_SIZE = 16
    }
}

/** Noise SymmetricState with BLAKE2s (HASHLEN 32). */
private class SymmetricState(protocolName: String) {
    var h: ByteArray
    private var ck: ByteArray
    val cipher = CipherState()

    init {
        val name = protocolName.encodeToByteArray()
        h = if (name.size <= HASH_LEN) name.copyOf(HASH_LEN) else hash(name)
        ck = h.copyOf()
    }

    fun mixHash(data: ByteArray) {
        h = hash(h, data)
    }

    fun mixKey(ikm: ByteArray) {
        val (newCk, tempK) = hkdf2(ck, ikm)
        ck = newCk
        cipher.initializeKey(tempK)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray = cipher.encrypt(h, plaintext).also(::mixHash)

    fun decryptAndHash(ciphertext: ByteArray): ByteArray = cipher.decrypt(h, ciphertext).also { mixHash(ciphertext) }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = hkdf2(ck, ByteArray(0))
        return CipherState(k1) to CipherState(k2)
    }

    companion object {
        const val HASH_LEN = 32

        fun hash(vararg parts: ByteArray): ByteArray {
            val d = Blake2sDigest(256)
            for (p in parts) d.update(p, 0, p.size)
            return ByteArray(HASH_LEN).also { d.doFinal(it, 0) }
        }

        private fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
            val mac = HMac(Blake2sDigest(256)).apply { init(KeyParameter(key)) }
            for (p in parts) mac.update(p, 0, p.size)
            return ByteArray(HASH_LEN).also { mac.doFinal(it, 0) }
        }

        fun hkdf2(chainingKey: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
            val temp = hmac(chainingKey, ikm)
            val out1 = hmac(temp, byteArrayOf(1))
            val out2 = hmac(temp, out1, byteArrayOf(2))
            return out1 to out2
        }
    }
}

/** Keys for both directions once a handshake completes. */
class NoiseTransport internal constructor(val send: CipherState, val receive: CipherState) {
    fun encrypt(plaintext: ByteArray, offset: Int = 0, length: Int = plaintext.size - offset): ByteArray =
        send.encrypt(EMPTY, plaintext, offset, length)

    fun decrypt(ciphertext: ByteArray, offset: Int = 0, length: Int = ciphertext.size - offset): ByteArray =
        receive.decrypt(EMPTY, ciphertext, offset, length)
}

private val EMPTY = ByteArray(0)

/**
 * Noise_IK_25519_ChaChaPoly_BLAKE2s — only what TermBridge needs (PROTOCOL.md §1.2):
 * ```
 * <- s
 * ...
 * -> e, es, s, ss
 * <- e, ee, se
 * ```
 * [ephemeral] is injectable for test vectors only.
 */
class NoiseIK(
    private val initiator: Boolean,
    private val localStatic: KeyPair,
    remoteStatic: ByteArray?,
    prologue: ByteArray = PROLOGUE,
    private val ephemeral: () -> KeyPair = { X25519.generate() },
) {
    private val ss = SymmetricState(PROTOCOL_NAME)
    private var e: KeyPair? = null
    private var re: ByteArray? = null
    private var step = 0

    /** The peer's static key: known up front by the initiator, learned from msg1 by the responder. */
    var remoteStatic: ByteArray? = remoteStatic
        private set

    val isComplete: Boolean get() = step == 2

    init {
        if (initiator) requireNotNull(remoteStatic) { "the initiator must know the responder's static key" }
        ss.mixHash(prologue)
        // Pre-message "<- s": both sides hash the responder's static key.
        ss.mixHash(if (initiator) remoteStatic!! else localStatic.public)
    }

    /** msg1 for the initiator, msg2 for the responder. */
    fun writeMessage(payload: ByteArray = EMPTY): ByteArray {
        val rs = remoteStatic
        return when {
            initiator && step == 0 -> {
                val eph = ephemeral().also { e = it }
                ss.mixHash(eph.public)
                ss.mixKey(X25519.dh(eph.private, rs!!)) // es
                val encStatic = ss.encryptAndHash(localStatic.public) // s
                ss.mixKey(X25519.dh(localStatic.private, rs)) // ss
                step = 1
                eph.public + encStatic + ss.encryptAndHash(payload)
            }
            !initiator && step == 1 -> {
                val eph = ephemeral().also { e = it }
                ss.mixHash(eph.public)
                ss.mixKey(X25519.dh(eph.private, re!!)) // ee
                ss.mixKey(X25519.dh(eph.private, rs!!)) // se
                step = 2
                eph.public + ss.encryptAndHash(payload)
            }
            else -> throw NoiseException("writeMessage out of order")
        }
    }

    /** msg1 for the responder, msg2 for the initiator. Returns the decrypted payload. */
    fun readMessage(message: ByteArray): ByteArray {
        val k = X25519.KEY_SIZE
        return when {
            !initiator && step == 0 -> {
                if (message.size < k + k + CipherState.TAG_SIZE + CipherState.TAG_SIZE) throw NoiseException("msg1 too short")
                val remoteE = message.copyOfRange(0, k).also { re = it }
                ss.mixHash(remoteE)
                ss.mixKey(X25519.dh(localStatic.private, remoteE)) // es
                val rs = ss.decryptAndHash(message.copyOfRange(k, k + k + CipherState.TAG_SIZE)) // s
                remoteStatic = rs
                ss.mixKey(X25519.dh(localStatic.private, rs)) // ss
                step = 1
                ss.decryptAndHash(message.copyOfRange(k + k + CipherState.TAG_SIZE, message.size))
            }
            initiator && step == 1 -> {
                if (message.size < k + CipherState.TAG_SIZE) throw NoiseException("msg2 too short")
                val remoteE = message.copyOfRange(0, k).also { re = it }
                ss.mixHash(remoteE)
                ss.mixKey(X25519.dh(e!!.private, remoteE)) // ee
                ss.mixKey(X25519.dh(localStatic.private, remoteE)) // se
                step = 2
                ss.decryptAndHash(message.copyOfRange(k, message.size))
            }
            else -> throw NoiseException("readMessage out of order")
        }
    }

    /** Transport keys; the first CipherState of Split() is initiator→responder. */
    fun split(): NoiseTransport {
        check(isComplete) { "handshake not complete" }
        val (i2r, r2i) = ss.split()
        return if (initiator) NoiseTransport(send = i2r, receive = r2i) else NoiseTransport(send = r2i, receive = i2r)
    }

    companion object {
        const val PROTOCOL_NAME = "Noise_IK_25519_ChaChaPoly_BLAKE2s"
        val PROLOGUE = "TermBridge/1".encodeToByteArray()
    }
}
