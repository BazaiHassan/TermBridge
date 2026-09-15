package io.termbridge.core.transport

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanDiscoveryTest {
    private val agentId = "lA4j9t8H4NGr6QAKRnKFSiYHy97mMEF8MO5NlBB9c3I="
    private val txtId = "lA4j9t8H4NGr6QAKRnKFSiYHy97mMEF8MO5NlBB9c3I".encodeToByteArray() // base64url, as the agent sends it

    @Test
    fun txtIdMapsToPairedAgentId() {
        assertEquals(agentId, LanDiscovery.agentIdFromTxt(txtId))
        assertNull(LanDiscovery.agentIdFromTxt("short".encodeToByteArray()))
        assertNull(LanDiscovery.agentIdFromTxt(null))
    }

    @Test
    fun prefersIpv4Address() {
        val hosts = listOf(InetAddress.getByName("fe80::1"), InetAddress.getByName("192.168.100.9"))
        assertEquals(agentId to "192.168.100.9:7423", LanDiscovery.match(txtId, hosts, 7423))
    }

    @Test
    fun ignoresForeignServices() {
        assertNull(LanDiscovery.match(null, listOf(InetAddress.getByName("192.168.1.2")), 7423))
        assertNull(LanDiscovery.match(txtId, emptyList(), 7423))
    }
}
