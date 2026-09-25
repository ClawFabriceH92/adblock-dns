package io.github.clawfabriceh92.adblockdns.core.net

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class TunnelAddressesTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `premiere plage libre retenue`() {
        assertEquals(TunnelAddresses("198.51.100.1", "198.51.100.2"), TunnelAddresses.choose(listOf(ip("192.168.1.20"))))
    }

    @Test
    fun `plage deja utilisee par une interface evitee`() {
        val chosen = TunnelAddresses.choose(listOf(ip("198.51.100.7"), ip("203.0.113.9"), ip("::1")))
        assertEquals(TunnelAddresses("192.0.2.1", "192.0.2.2"), chosen)
    }

    @Test
    fun `repli sur la premiere plage si tout est occupe`() {
        val all = TunnelAddresses.CANDIDATE_PREFIXES.map { ip("$it.5") }
        assertEquals("198.51.100.2", TunnelAddresses.choose(all).dnsServer)
    }
}
