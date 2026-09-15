package io.termbridge.core.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The pairing QR payload (PROTOCOL.md §8). */
@Serializable
data class PairingQr(
    val v: Int,
    @SerialName("agent_id") val agentId: String,
    val name: String = "",
    val code: String,
    val lan: List<String> = emptyList(),
    val relay: String? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val hostPort = Regex("""^[0-9A-Za-z.\-]+:\d{1,5}$""")

        /** Parses and validates scanned text; the message is shown to the user on failure. */
        fun parse(text: String): Result<PairingQr> = runCatching {
            val qr = try {
                json.decodeFromString<PairingQr>(text.trim())
            } catch (e: SerializationException) {
                throw IllegalArgumentException("This isn't a TermBridge pairing code", e)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("This isn't a TermBridge pairing code", e)
            }
            require(qr.v == Protocol.VERSION) { "This pairing code needs a newer TermBridge app (v${qr.v})" }
            require(qr.agentId.length in 40..48) { "Pairing code has an invalid agent ID" }
            require(qr.code.isNotBlank()) { "Pairing code has no one-time code" }
            require(qr.lan.isNotEmpty() || qr.relay != null) { "The computer reported no network address" }
            require(qr.lan.all { hostPort.matches(it) && it.substringAfterLast(':').toInt() in 1..65535 }) {
                "Pairing code has an invalid address"
            }
            qr.copy(name = qr.name.take(64).ifBlank { "Computer" })
        }
    }
}

/** The JSON inside Noise msg1 (PROTOCOL.md §1.2). */
object HandshakePayload {
    @Serializable
    private data class Pair(val code: String, val name: String)

    @Serializable
    private data class Payload(val pair: Pair)

    /** Empty payload: an ordinary connection from a paired phone. */
    val CONNECT = ByteArray(0)

    fun pair(code: String, deviceName: String): ByteArray =
        Json.encodeToString(Payload.serializer(), Payload(Pair(code, deviceName))).encodeToByteArray()
}
