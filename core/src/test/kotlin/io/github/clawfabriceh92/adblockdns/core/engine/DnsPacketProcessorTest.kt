package io.github.clawfabriceh92.adblockdns.core.engine

import io.github.clawfabriceh92.adblockdns.core.TestDns
import io.github.clawfabriceh92.adblockdns.core.dns.DnsMessages
import io.github.clawfabriceh92.adblockdns.core.dns.DnsQuestion
import io.github.clawfabriceh92.adblockdns.core.filter.BlockSource
import io.github.clawfabriceh92.adblockdns.core.filter.FilterEngine
import io.github.clawfabriceh92.adblockdns.core.filter.HashedDomainSet
import io.github.clawfabriceh92.adblockdns.core.filter.ListCategory
import io.github.clawfabriceh92.adblockdns.core.filter.LoadedList
import io.github.clawfabriceh92.adblockdns.core.filter.RuleMatcher
import io.github.clawfabriceh92.adblockdns.core.packet.IpPackets
import io.github.clawfabriceh92.adblockdns.core.packet.ParsedPacket
import io.github.clawfabriceh92.adblockdns.core.packet.UdpPacket
import io.github.clawfabriceh92.adblockdns.core.upstream.DnsUpstream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DnsPacketProcessorTest {
    private val client = byteArrayOf(192.toByte(), 0, 2, 1)
    private val virtualDns = byteArrayOf(192.toByte(), 0, 2, 2)

    private val list = LoadedList(
        "hagezi-pro",
        ListCategory.ADS_TRACKING,
        HashedDomainSet.fromDomains(listOf("doubleclick.net", "eulerian.net")),
    )
    private var engine = FilterEngine(RuleMatcher.EMPTY, RuleMatcher.EMPTY, listOf(list))
    private val blocked = ArrayList<BlockedQuery>()
    private val forwarded = ArrayList<String>()
    private var upstreamCalls = 0
    private var upstreamAnswer: (ByteArray) -> ByteArray = { q -> TestDns.response(q, listOf(TestDns.a(203, 0, 113, 7))) }

    private val upstream = object : DnsUpstream {
        override fun resolve(query: ByteArray): ByteArray {
            upstreamCalls++
            return upstreamAnswer(query)
        }
    }

    private val processor = DnsPacketProcessor(
        engine = { engine },
        upstream = { upstream },
        listener = object : DnsPacketProcessor.Listener {
            override fun onBlocked(query: BlockedQuery, packet: UdpPacket) {
                blocked += query
            }

            override fun onForwarded(question: DnsQuestion, packet: UdpPacket) {
                forwarded += "${question.name}/${question.type}/${packet.sourcePort}"
            }
        },
        clock = { 1_000L },
    )

    private fun queryPacket(name: String, id: Int = 0x0A0A, port: Int = 53) =
        IpPackets.buildUdp(4, client, virtualDns, 41000, port, TestDns.query(name, id = id))

    private fun replyPayload(packet: ByteArray?): ByteArray {
        val udp = assertIs<ParsedPacket.Udp>(IpPackets.parse(assertNotNull(packet))).packet
        assertContentEquals(virtualDns, udp.sourceAddress)
        assertContentEquals(client, udp.destinationAddress)
        assertEquals(53, udp.sourcePort)
        assertEquals(41000, udp.destinationPort)
        return udp.payload
    }

    @Test
    fun `domaine bloque NXDOMAIN sans appel amont`() {
        val response = replyPayload(processor.process(queryPacket("securepubads.g.doubleclick.net", id = 0x1111)))
        assertEquals(0x1111, DnsMessages.id(response))
        assertEquals(DnsMessages.RCODE_NXDOMAIN, DnsMessages.rcode(response))
        assertEquals(0, upstreamCalls)
        val event = blocked.single()
        assertEquals("securepubads.g.doubleclick.net", event.domain)
        assertEquals("doubleclick.net", event.matched)
        assertEquals(BlockSource.FromList("hagezi-pro", ListCategory.ADS_TRACKING), event.source)
        assertEquals(1_000L, event.timestamp)
    }

    @Test
    fun `requete transmise signalee avec sa question et son paquet, pas les requetes bloquees`() {
        processor.process(queryPacket("example.com"))
        processor.process(queryPacket("securepubads.g.doubleclick.net"))
        assertEquals(listOf("example.com/1/41000"), forwarded)
    }

    @Test
    fun `domaine autorise relaye`() {
        val response = replyPayload(processor.process(queryPacket("example.com", id = 0x2222)))
        assertEquals(0x2222, DnsMessages.id(response))
        assertEquals(DnsMessages.RCODE_NOERROR, DnsMessages.rcode(response))
        assertEquals(1, upstreamCalls)
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun `CNAME vers un traqueur bloque`() {
        upstreamAnswer = { q -> TestDns.response(q, listOf(TestDns.cname("lemonde.eulerian.net"), TestDns.a(1, 2, 3, 4))) }
        val response = replyPayload(processor.process(queryPacket("metrics.lemonde.fr")))
        assertEquals(DnsMessages.RCODE_NXDOMAIN, DnsMessages.rcode(response))
        assertEquals("lemonde.eulerian.net", blocked.single().viaCname)
        assertEquals("metrics.lemonde.fr", blocked.single().domain)
    }

    @Test
    fun `CNAME ignore si le domaine demande est en liste blanche`() {
        engine = FilterEngine(RuleMatcher(listOf("lemonde.fr")), RuleMatcher.EMPTY, listOf(list))
        upstreamAnswer = { q -> TestDns.response(q, listOf(TestDns.cname("lemonde.eulerian.net"))) }
        val response = replyPayload(processor.process(queryPacket("metrics.lemonde.fr")))
        assertEquals(DnsMessages.RCODE_NOERROR, DnsMessages.rcode(response))
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun `inspection CNAME desactivable`() {
        val noCname = DnsPacketProcessor(
            engine = { engine },
            upstream = { upstream },
            listener = object : DnsPacketProcessor.Listener {
                override fun onBlocked(query: BlockedQuery, packet: UdpPacket) {
                    blocked += query
                }
            },
            cnameInspection = { false },
        )
        upstreamAnswer = { q -> TestDns.response(q, listOf(TestDns.cname("x.eulerian.net"))) }
        assertEquals(DnsMessages.RCODE_NOERROR, DnsMessages.rcode(replyPayload(noCname.process(queryPacket("m.site.fr")))))
    }

    @Test
    fun `panne amont SERVFAIL immediat`() {
        upstreamAnswer = { throw IOException("réseau coupé") }
        val response = replyPayload(processor.process(queryPacket("example.com", id = 0x3333)))
        assertEquals(0x3333, DnsMessages.id(response))
        assertEquals(DnsMessages.RCODE_SERVFAIL, DnsMessages.rcode(response))
    }

    @Test
    fun `message DNS non standard transmis tel quel`() {
        val update = TestDns.query("example.com").also { it[2] = 0x28 } // opcode UPDATE
        var forwarded: ByteArray? = null
        upstreamAnswer = { q -> forwarded = q; TestDns.response(q, emptyList()) }
        val packet = IpPackets.buildUdp(4, client, virtualDns, 41000, 53, update)
        assertNotNull(processor.process(packet))
        assertContentEquals(update, forwarded)
    }

    @Test
    fun `erreur interne du filtre fail-open`() {
        val failing = DnsPacketProcessor(
            engine = { throw IllegalStateException("liste en cours de rechargement") },
            upstream = { upstream },
            listener = object : DnsPacketProcessor.Listener {
                override fun onBlocked(query: BlockedQuery, packet: UdpPacket) = Unit
            },
        )
        val response = replyPayload(failing.process(queryPacket("doubleclick.net")))
        assertEquals(DnsMessages.RCODE_NOERROR, DnsMessages.rcode(response))
    }

    @Test
    fun `UDP hors port 53 ignore`() {
        assertNull(processor.process(queryPacket("example.com", port = 5353)))
    }

    @Test
    fun `DNS sur TCP refuse par un RST`() {
        val syn = ByteArray(40)
        syn[0] = 0x45; syn[3] = 40; syn[9] = 6
        client.copyInto(syn, 12); virtualDns.copyInto(syn, 16)
        syn[20] = 0x9C.toByte(); syn[21] = 0x40; syn[23] = 53; syn[32] = 0x50; syn[33] = IpPackets.TCP_SYN.toByte()
        val reply = assertIs<ParsedPacket.Tcp>(IpPackets.parse(assertNotNull(processor.process(syn)))).segment
        assertEquals(IpPackets.TCP_RST or IpPackets.TCP_ACK, reply.flags)
    }

    @Test
    fun `paquet illisible ignore`() {
        assertNull(processor.process(ByteArray(10)))
    }
}
