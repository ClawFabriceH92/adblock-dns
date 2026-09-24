package io.github.clawfabriceh92.adblockdns.core.filter

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainsTest {
    @Test
    fun `noms valides et invalides`() {
        assertTrue(Domains.isValidHostname("ads.example.com"))
        assertTrue(Domains.isValidHostname("_dmarc.example.com"))
        assertTrue(Domains.isValidHostname("x-1.co"))
        assertFalse(Domains.isValidHostname("com"), "un seul label")
        assertTrue(Domains.isValidHostname("com", minLabels = 1))
        assertFalse(Domains.isValidHostname("0.0.0.0"), "adresse IPv4")
        assertFalse(Domains.isValidHostname("a..b"))
        assertFalse(Domains.isValidHostname("exa mple.com"))
        assertFalse(Domains.isValidHostname("ex/ample.com"))
        assertFalse(Domains.isValidHostname("a".repeat(64) + ".com"), "label de plus de 63 caractères")
        assertEquals("ads.example.com", Domains.normalize(" ADS.Example.com. "))
        assertNull(Domains.normalize("localhost"))
    }
}

class HashedDomainSetTest {
    private val set = HashedDomainSet.fromDomains(listOf("doubleclick.net", "ads.example.com", "ads.example.com"))

    @Test
    fun `doublons supprimes`() = assertEquals(2, set.size)

    @Test
    fun `un domaine bloque ses sous-domaines`() {
        assertEquals("doubleclick.net", set.findMatchingSuffix("doubleclick.net"))
        assertEquals("doubleclick.net", set.findMatchingSuffix("googleads.g.doubleclick.net"))
        assertEquals("ads.example.com", set.findMatchingSuffix("x.ads.example.com"))
    }

    @Test
    fun `parents et voisins non bloques`() {
        assertNull(set.findMatchingSuffix("example.com"))
        assertNull(set.findMatchingSuffix("notdoubleclick.net"))
        assertNull(set.findMatchingSuffix("cdn.example.com"))
        assertNull(set.findMatchingSuffix("net"))
        assertNull(set.findMatchingSuffix(""))
    }

    @Test
    fun `aller-retour au format binaire`() {
        val out = ByteArrayOutputStream()
        set.writeTo(out)
        val reread = HashedDomainSet.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertEquals(set.size, reread.size)
        assertTrue(reread.containsExact("doubleclick.net"))
        assertFalse(reread.containsExact("example.com"))
    }

    @Test
    fun `fichier corrompu ou tronque refuse`() {
        val out = ByteArrayOutputStream()
        HashedDomainSet.fromDomains((1..10_000).map { "d$it.example" }).writeTo(out)
        val bytes = out.toByteArray()
        assertFailsWith<IOException> { HashedDomainSet.readFrom(ByteArrayInputStream(bytes.copyOf(bytes.size - 3))) }
        val unsorted = bytes.copyOf().also { it[20] = (it[20] + 1).toByte(); it[12] = 0x7F }
        assertFailsWith<IOException> { HashedDomainSet.readFrom(ByteArrayInputStream(unsorted)) }
        assertFailsWith<IOException> { HashedDomainSet.readFrom(ByteArrayInputStream(ByteArray(12))) }
    }
}

class RuleSyntaxTest {
    @Test
    fun `saisies tolerantes ramenees au domaine`() {
        assertEquals("example.com", RuleSyntax.normalize("Example.com"))
        assertEquals("example.com", RuleSyntax.normalize("https://user@Example.com:8443/page?x=1#top"))
        assertEquals("ads.example.com", RuleSyntax.normalize("||ads.example.com^"))
        assertEquals("ads.example.com", RuleSyntax.normalize("0.0.0.0 ads.example.com"))
        assertEquals("example.com", RuleSyntax.normalize(".example.com."))
    }

    @Test
    fun `jokers acceptes s'ils gardent une partie fixe`() {
        assertEquals("*.example.com", RuleSyntax.normalize("*.example.com"))
        assertEquals("pub*.example.com", RuleSyntax.normalize("pub*.example.com"))
        assertNull(RuleSyntax.normalize("*"))
        assertNull(RuleSyntax.normalize("*.*"))
        assertNull(RuleSyntax.normalize("exa\$mple*.com"))
    }

    @Test
    fun `saisies invalides refusees`() {
        assertNull(RuleSyntax.normalize(""))
        assertNull(RuleSyntax.normalize("   "))
        assertNull(RuleSyntax.normalize("com"))
        assertNull(RuleSyntax.normalize("192.168.1.1"))
        assertNull(RuleSyntax.normalize("exemple .com"))
    }
}

class RuleMatcherTest {
    private val rules = RuleMatcher(listOf("example.com", "*.cdn.net", "pub*.site.fr", "*track*"))

    @Test
    fun `regle de domaine`() {
        assertEquals("example.com", rules.match("example.com"))
        assertEquals("example.com", rules.match("a.b.example.com"))
        assertNull(rules.match("notexample.com"))
        assertNull(rules.match("example.com.evil.org"))
    }

    @Test
    fun `motifs avec jokers`() {
        assertEquals("*.cdn.net", rules.match("img.cdn.net"))
        assertNull(rules.match("cdn.net"), "*.cdn.net ne couvre pas cdn.net lui-même")
        assertEquals("pub*.site.fr", rules.match("pub-video.site.fr"))
        assertNull(rules.match("www.site.fr"))
        assertEquals("*track*", rules.match("mytracker.io"))
    }

    @Test
    fun `ensemble vide`() {
        assertTrue(RuleMatcher.EMPTY.isEmpty)
        assertNull(RuleMatcher.EMPTY.match("example.com"))
    }
}

class FilterEngineTest {
    private val ads = LoadedList("ads", ListCategory.ADS_TRACKING, HashedDomainSet.fromDomains(listOf("doubleclick.net", "evil.example")))
    private val malware = LoadedList("tif", ListCategory.MALWARE, HashedDomainSet.fromDomains(listOf("evil.example")))

    private fun engine(allow: List<String> = emptyList(), block: List<String> = emptyList()) =
        FilterEngine(RuleMatcher(allow), RuleMatcher(block), listOf(ads, malware))

    @Test
    fun `domaine absent autorise`() {
        assertEquals(FilterDecision.Allowed, engine().decide("example.org"))
    }

    @Test
    fun `blocage par liste avec sa categorie`() {
        val d = assertIs<FilterDecision.Blocked>(engine().decide("ad.doubleclick.net"))
        assertEquals(BlockSource.FromList("ads", ListCategory.ADS_TRACKING), d.source)
        assertEquals("doubleclick.net", d.matched)
    }

    @Test
    fun `les listes de malveillants sont consultees en premier`() {
        val d = assertIs<FilterDecision.Blocked>(engine().decide("evil.example"))
        assertEquals(BlockSource.FromList("tif", ListCategory.MALWARE), d.source)
    }

    @Test
    fun `liste blanche prioritaire y compris pour un sous-domaine`() {
        val e = engine(allow = listOf("g.doubleclick.net"))
        assertEquals(FilterDecision.AllowedByRule("g.doubleclick.net"), e.decide("googleads.g.doubleclick.net"))
        assertIs<FilterDecision.Blocked>(e.decide("ad.doubleclick.net"))
    }

    @Test
    fun `liste blanche prioritaire sur la liste noire manuelle`() {
        val e = engine(allow = listOf("example.com"), block = listOf("ads.example.com"))
        assertEquals(FilterDecision.AllowedByRule("example.com"), e.decide("ads.example.com"))
    }

    @Test
    fun `liste noire manuelle`() {
        val d = assertIs<FilterDecision.Blocked>(engine(block = listOf("*.pub.fr")).decide("video.pub.fr"))
        assertEquals(BlockSource.FromRule("*.pub.fr"), d.source)
    }

    @Test
    fun `domaine canari de Firefox bloque sauf liste blanche`() {
        assertEquals(BlockSource.Builtin, assertIs<FilterDecision.Blocked>(engine().decide("use-application-dns.net")).source)
        assertIs<FilterDecision.AllowedByRule>(engine(allow = listOf("use-application-dns.net")).decide("use-application-dns.net"))
    }

    @Test
    fun `nombre d'entrees chargees`() = assertEquals(3, engine().listEntryCount)
}
