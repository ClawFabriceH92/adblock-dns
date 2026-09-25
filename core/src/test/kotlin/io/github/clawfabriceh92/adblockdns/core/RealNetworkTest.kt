package io.github.clawfabriceh92.adblockdns.core

import io.github.clawfabriceh92.adblockdns.core.dns.DnsMessages
import io.github.clawfabriceh92.adblockdns.core.lists.DownloadResult
import io.github.clawfabriceh92.adblockdns.core.lists.ListDownloader
import io.github.clawfabriceh92.adblockdns.core.upstream.DohUpstream
import org.junit.Assume.assumeTrue
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests contre les vrais services (listes publiques, résolveurs DoH). Désactivés par défaut :
 * ./gradlew :core:test -PnetworkTests=true
 */
class RealListsTest {
    private val downloader = ListDownloader(userAgent = "adblock-dns-tests")

    @BeforeTest
    fun onlyOnDemand() = assumeTrue("tests réseau non demandés", System.getProperty("networkTests") == "true")

    private fun checkList(url: String, minimum: Int) {
        val started = System.nanoTime()
        val result = assertIs<DownloadResult.Updated>(downloader.download(url))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val list = result.list
        println("$url : ${list.domains.size} domaines uniques, ${list.parse.accepted} acceptés, ${list.parse.rejected} rejetés, en-tête ${list.parse.header}, ${elapsedMs} ms")
        assertTrue(list.domains.size >= minimum, "au moins $minimum domaines")
        val declared = list.parse.header.declaredEntries
        if (declared != null) {
            // Le nombre annoncé dans l'en-tête doit correspondre à ce qui a été compris (±1 %).
            assertTrue(kotlin.math.abs(list.domains.size - declared) <= declared / 100, "annoncé $declared, lu ${list.domains.size}")
        }
        assertNotNull(result.etag, "ETag attendu pour les mises à jour conditionnelles")
        assertEquals(DownloadResult.NotModified, downloader.download(url, etag = result.etag))
    }

    @Test
    fun `hagezi Pro format Wildcard Domains`() =
        checkList("https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt", 100_000)

    @Test
    fun `hagezi TIF medium`() =
        checkList("https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/tif.medium-onlydomains.txt", 300_000)

    @Test
    fun `StevenBlack hosts unifie`() =
        checkList("https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", 30_000)
}

class RealDohTest {
    @BeforeTest
    fun onlyOnDemand() = assumeTrue("tests réseau non demandés", System.getProperty("networkTests") == "true")

    private fun check(endpoint: String) {
        val query = TestDns.query("example.com", id = 0x7777)
        val response = DohUpstream(endpoint).resolve(query)
        assertEquals(0x7777, DnsMessages.id(response))
        assertEquals(DnsMessages.RCODE_NOERROR, DnsMessages.rcode(response))
        assertTrue(response.u16At(6) > 0, "au moins une réponse")
    }

    @Test
    fun cloudflare() = check(DohUpstream.CLOUDFLARE)

    @Test
    fun quad9() = check(DohUpstream.QUAD9)
}
