package io.github.clawfabriceh92.adblockdns.ui

import io.github.clawfabriceh92.adblockdns.data.db.BucketCount
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsWindowTest {
    private val paris = 2 * 3_600_000L // heure d'été : UTC+2

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `24 h alignees sur l'heure locale`() {
        val window = StatsWindow.of(StatsPeriod.DAY, ms("2026-09-24T08:30:00Z"), paris)
        assertEquals(24, (window.lastBucket - window.firstBucket + 1).toInt())
        // 10 h 30 à Paris : la dernière case est 10 h, la première 11 h la veille.
        assertEquals(10L, window.lastBucket % 24)
        assertEquals(11L, window.firstBucket % 24)
        assertEquals(ms("2026-09-23T09:00:00Z"), window.sinceMs)
    }

    @Test
    fun `7 jours alignes sur minuit local`() {
        // 00 h 30 le 25 à Paris alors qu'il est encore le 24 en UTC.
        val window = StatsWindow.of(StatsPeriod.WEEK, ms("2026-09-24T22:30:00Z"), paris)
        assertEquals(ms("2026-09-18T22:00:00Z"), window.sinceMs, "minuit du 19 à Paris")
        assertEquals(ms("2026-09-24T22:00:00Z"), window.bucketStartMs(window.lastBucket, StatsPeriod.WEEK))
    }

    @Test
    fun `30 jours`() {
        val window = StatsWindow.of(StatsPeriod.MONTH, ms("2026-09-24T12:00:00Z"), 0)
        assertEquals(30, window.series(emptyList()).size)
        assertEquals(ms("2026-08-26T00:00:00Z"), window.sinceMs)
    }

    @Test
    fun `serie completee par des zeros et bornee a la fenetre`() {
        val window = StatsWindow.of(StatsPeriod.DAY, ms("2026-09-24T08:30:00Z"), paris)
        val series = window.series(
            listOf(
                BucketCount(window.firstBucket - 1, 99), // hors fenêtre : ignoré
                BucketCount(window.firstBucket, 3),
                BucketCount(window.firstBucket + 2, 5),
                BucketCount(window.lastBucket, 1),
            ),
        )
        assertEquals(24, series.size)
        assertEquals(listOf(3, 0, 5), series.take(3))
        assertEquals(1, series.last())
        assertEquals(9, series.sum())
    }

    private fun assertEquals(expected: Long, actual: Long, message: String) = assertEquals(message, expected, actual)
}
