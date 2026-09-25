package io.github.clawfabriceh92.adblockdns.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentQueriesTest {
    @Test
    fun `requetes regroupees par domaine et application, la plus recente en tete`() {
        val recent = RecentQueries()
        recent.record("example.com", "com.reddit.frontpage", "Reddit", 1_000)
        recent.record("ads.example.net", "com.reddit.frontpage", "Reddit", 2_000)
        recent.record("example.com", "com.reddit.frontpage", "Reddit", 3_000)
        recent.record("example.com", null, "Système Android", 4_000)

        val list = recent.snapshot()
        assertEquals(listOf("example.com", "example.com", "ads.example.net"), list.map { it.domain })
        assertEquals("Système Android", list[0].appLabel)
        assertEquals(2, list[1].count)
        assertEquals(3_000L, list[1].lastSeen)
    }

    @Test
    fun `capacite bornee, les plus anciens sortent`() {
        val recent = RecentQueries(capacity = 3)
        for (i in 1..5) recent.record("d$i.example", "app", "App", i.toLong())
        assertEquals(listOf("d5.example", "d4.example", "d3.example"), recent.snapshot().map { it.domain })
    }

    @Test
    fun `retrait apres blocage, requetes inverses ignorees, effacement`() {
        val recent = RecentQueries()
        val before = recent.version.value
        recent.record("tracker.example", "a", "A", 1)
        recent.record("tracker.example", "b", "B", 2)
        recent.record("1.2.0.192.in-addr.arpa", "a", "A", 3)
        assertEquals(2, recent.snapshot().size)
        assertTrue(recent.version.value > before)

        recent.remove("tracker.example")
        assertTrue(recent.snapshot().isEmpty())

        recent.record("other.example", "a", "A", 4)
        recent.clear()
        assertTrue(recent.snapshot().isEmpty())
    }
}
