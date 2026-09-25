package io.github.clawfabriceh92.adblockdns.core.dns

import io.github.clawfabriceh92.adblockdns.core.TestDns
import io.github.clawfabriceh92.adblockdns.core.u16At
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DnsMessagesTest {

    @Test
    fun `question lue et mise en minuscules`() {
        val query = TestDns.query("Ads.Example.COM", type = DnsMessages.TYPE_AAAA, id = 0xBEEF)
        val q = assertNotNull(DnsMessages.parseQuestion(query))
        assertEquals(0xBEEF, q.id)
        assertEquals("ads.example.com", q.name)
        assertEquals(DnsMessages.TYPE_AAAA, q.type)
        assertEquals(1, q.questionClass)
        assertEquals(query.size, q.questionEnd)
    }

    @Test
    fun `question lue malgre un enregistrement EDNS`() {
        val query = TestDns.query("example.com", edns = true)
        val q = assertNotNull(DnsMessages.parseQuestion(query))
        assertEquals("example.com", q.name)
        assertTrue(q.questionEnd < query.size)
    }

    @Test
    fun `messages non standard refuses`() {
        val query = TestDns.query("example.com")
        assertNull(DnsMessages.parseQuestion(query.copyOf(11)), "trop court")
        assertNull(DnsMessages.parseQuestion(query.copyOf().also { it[2] = 0x81.toByte() }), "réponse")
        assertNull(DnsMessages.parseQuestion(query.copyOf().also { it[2] = 0x28 }), "opcode UPDATE")
        assertNull(DnsMessages.parseQuestion(query.copyOf().also { it[5] = 2 }), "deux questions")
        assertNull(DnsMessages.parseQuestion(query.copyOf(query.size - 2)), "question tronquée")
    }

    @Test
    fun `NXDOMAIN reprend l'identifiant et la question`() {
        val query = TestDns.query("tracker.example", id = 0x4242, edns = true)
        val q = DnsMessages.parseQuestion(query)!!
        val response = DnsMessages.nxdomain(query, q)

        assertEquals(0x4242, DnsMessages.id(response))
        assertTrue(DnsMessages.isResponse(response))
        assertFalse(DnsMessages.isTruncated(response))
        assertEquals(DnsMessages.RCODE_NXDOMAIN, DnsMessages.rcode(response))
        assertEquals(0x0100, response.u16At(2) and 0x0100, "RD recopié")
        assertEquals(0x0080, response.u16At(2) and 0x0080, "RA positionné")
        assertEquals(1, response.u16At(4))
        assertEquals(0, response.u16At(6))
        assertEquals(0, response.u16At(10), "EDNS retiré de la réponse")
        assertContentEquals(query.copyOfRange(12, q.questionEnd), response.copyOfRange(12, response.size))
    }

    @Test
    fun `SERVFAIL et reponse tronquee`() {
        val query = TestDns.query("example.com")
        val q = DnsMessages.parseQuestion(query)!!
        assertEquals(DnsMessages.RCODE_SERVFAIL, DnsMessages.rcode(DnsMessages.errorResponse(query, q, DnsMessages.RCODE_SERVFAIL)))
        val truncated = DnsMessages.truncatedResponse(query, q)
        assertTrue(DnsMessages.isTruncated(truncated))
        assertEquals(DnsMessages.RCODE_NOERROR, DnsMessages.rcode(truncated))
    }

    @Test
    fun `cibles CNAME extraites avec compression`() {
        val query = TestDns.query("metrics.site.fr")
        val response = TestDns.response(
            query,
            listOf(TestDns.cname("site.eulerian.net"), TestDns.a(203, 0, 113, 7)),
        )
        assertEquals(listOf("site.eulerian.net"), DnsMessages.cnameTargets(response))
    }

    @Test
    fun `aucune cible CNAME dans une reponse simple ou vide`() {
        val query = TestDns.query("example.com")
        assertEquals(emptyList(), DnsMessages.cnameTargets(TestDns.response(query, listOf(TestDns.a(1, 2, 3, 4)))))
        assertEquals(emptyList(), DnsMessages.cnameTargets(TestDns.response(query, emptyList())))
        assertEquals(emptyList(), DnsMessages.cnameTargets(ByteArray(3)))
    }

    @Test
    fun `reponse CNAME tronquee lue sans exception`() {
        val query = TestDns.query("a.example")
        val response = TestDns.response(query, listOf(TestDns.cname("b.example"), TestDns.cname("c.example")))
        for (cut in DnsMessages.HEADER_SIZE until response.size) {
            DnsMessages.cnameTargets(response.copyOf(cut)) // ne doit jamais lever d'exception
        }
    }

    @Test
    fun `pointeurs de compression en boucle refuses`() {
        val message = ByteArray(16)
        message[12] = 0xC0.toByte()
        message[13] = 14
        message[14] = 0xC0.toByte()
        message[15] = 12
        assertNull(DnsMessages.readName(message, 12))
    }

    @Test
    fun `nom trop long refuse`() {
        val longName = (1..5).joinToString(".") { "a".repeat(60) }
        assertNull(DnsMessages.parseQuestion(TestDns.query(longName)))
    }
}
