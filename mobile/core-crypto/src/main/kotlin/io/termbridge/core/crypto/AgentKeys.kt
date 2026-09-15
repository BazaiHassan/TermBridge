package io.termbridge.core.crypto

import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.math.BigInteger
import java.util.Base64

/** Converts the agent ID from the QR code (an Ed25519 public key) to its Noise static key. */
object AgentKeys {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

    fun decodeAgentId(agentId: String): ByteArray {
        val key = try {
            Base64.getDecoder().decode(agentId)
        } catch (e: IllegalArgumentException) {
            throw NoiseException("agent ID is not base64", e)
        }
        if (key.size != Ed25519.PUBLIC_KEY_SIZE) throw NoiseException("agent ID must be ${Ed25519.PUBLIC_KEY_SIZE} bytes")
        return key
    }

    /** The same four-group fingerprint the computer prints (`termbridge status`), for eyeball checks. */
    fun fingerprint(agentId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(decodeAgentId(agentId))
        val hex = digest.take(8).joinToString("") { "%02x".format(it) }
        return hex.chunked(4).joinToString(" ")
    }

    /**
     * The Edwards→Montgomery map `u = (1 + y) / (1 − y) mod p` (libsodium
     * `crypto_sign_ed25519_pk_to_curve25519`). Invalid and low-order keys are rejected: they
     * would let an attacker predict every shared secret.
     */
    fun noiseStaticKey(agentId: String): ByteArray {
        val ed = decodeAgentId(agentId)
        if (!Ed25519.validatePublicKeyFull(ed, 0)) throw NoiseException("agent ID is not a valid Ed25519 key")
        val yBytes = ed.reversedArray() // little-endian → big-endian
        yBytes[0] = (yBytes[0].toInt() and 0x7F).toByte() // drop the x sign bit
        val y = BigInteger(1, yBytes)
        val u = BigInteger.ONE.add(y).multiply(BigInteger.ONE.subtract(y).mod(P).modInverse(P)).mod(P)
        val be = u.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
        return ByteArray(32).also { out -> be.reversed().forEachIndexed { i, b -> out[i] = b } }
    }
}
