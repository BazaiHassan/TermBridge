package io.termbridge.core.proto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairingTest {
    private val agentId = "lA4j9t8H4NGr6QAKRnKFSiYHy97mMEF8MO5NlBB9c3I="

    @Test
    fun parsesAgentQr() {
        val qr = PairingQr.parse(
            """{"v":1,"agent_id":"$agentId","name":"amir-thinkpad","code":"AAECAwQF","lan":["192.168.100.9:7423"],"future":1}""",
        ).getOrThrow()
        assertEquals("amir-thinkpad", qr.name)
        assertEquals(listOf("192.168.100.9:7423"), qr.lan)
    }

    @Test
    fun rejectsForeignOrBrokenCodes() {
        val bad = listOf(
            "https://example.com",
            """{"v":2,"agent_id":"$agentId","code":"x","lan":["1.2.3.4:1"]}""",
            """{"v":1,"agent_id":"short","code":"x","lan":["1.2.3.4:1"]}""",
            """{"v":1,"agent_id":"$agentId","code":"x","lan":[]}""",
            """{"v":1,"agent_id":"$agentId","code":"x","lan":["1.2.3.4:99999"]}""",
            """{"v":1,"agent_id":"$agentId","code":"x","lan":["rm -rf /:22"]}""",
        )
        for (text in bad) assertTrue(PairingQr.parse(text).isFailure, text)
    }

    @Test
    fun handshakePayloadMatchesGoShape() {
        assertEquals("""{"pair":{"code":"AAECAwQF","name":"Pixel 8"}}""", HandshakePayload.pair("AAECAwQF", "Pixel 8").decodeToString())
        assertEquals(0, HandshakePayload.CONNECT.size)
    }
}
