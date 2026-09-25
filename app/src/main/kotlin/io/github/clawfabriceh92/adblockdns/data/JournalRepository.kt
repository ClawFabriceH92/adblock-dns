package io.github.clawfabriceh92.adblockdns.data

import android.util.Log
import io.github.clawfabriceh92.adblockdns.data.db.AppCount
import io.github.clawfabriceh92.adblockdns.data.db.AppRef
import io.github.clawfabriceh92.adblockdns.data.db.BlockedEventDao
import io.github.clawfabriceh92.adblockdns.data.db.BlockedEventEntity
import io.github.clawfabriceh92.adblockdns.data.db.BucketCount
import io.github.clawfabriceh92.adblockdns.data.db.CategoryCount
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Journal des requêtes bloquées. Les événements arrivent du tunnel à un rythme élevé : ils sont
 * regroupés et écrits par lots (une transaction par seconde au plus), jamais un par un.
 */
class JournalRepository(private val dao: BlockedEventDao, scope: CoroutineScope) {

    private val pending = Channel<BlockedEventEntity>(capacity = 5_000, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch(Dispatchers.IO) { writeBatches() }
    }

    /** Appelé depuis les threads du tunnel : ne bloque jamais. */
    fun record(event: BlockedEventEntity) {
        pending.trySend(event)
    }

    private suspend fun writeBatches() {
        val batch = ArrayList<BlockedEventEntity>(128)
        while (true) {
            batch += pending.receive()
            withTimeoutOrNull(FLUSH_DELAY_MS) {
                while (batch.size < MAX_BATCH) batch += pending.receive()
            }
            try {
                dao.insertAll(batch.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Écriture du journal impossible (${batch.size} événements perdus)", e)
            }
            batch.clear()
        }
    }

    fun recent(search: String, appPackage: String?): Flow<List<BlockedEventEntity>> =
        dao.recent(search.trim().lowercase(), appPackage, JOURNAL_LIMIT)

    fun apps(): Flow<List<AppRef>> = dao.apps()

    fun totalCount(): Flow<Int> = dao.count()

    /** Nombre de blocages depuis minuit, recalculé au changement de jour. */
    fun blockedToday(): Flow<Int> = startOfDayFlow().flatMapLatest { dao.countSince(it) }

    /** Nombre d'applications concernées depuis minuit. */
    fun appsToday(): Flow<Int> = startOfDayFlow().flatMapLatest { dao.appCountSince(it) }

    fun histogram(since: Long, bucketMs: Long, offsetMs: Long): Flow<List<BucketCount>> = dao.histogram(since, bucketMs, offsetMs)

    fun topApps(since: Long, limit: Int): Flow<List<AppCount>> = dao.topApps(since, limit)

    fun categories(since: Long): Flow<List<CategoryCount>> = dao.categories(since)

    /** Retire les entrées d'un domaine (après « Autoriser ») et les renvoie pour pouvoir annuler. */
    suspend fun takeDomain(domain: String): List<BlockedEventEntity> = dao.takeDomain(domain)

    suspend fun restore(events: List<BlockedEventEntity>) {
        if (events.isNotEmpty()) dao.insertAll(events)
    }

    suspend fun purgeAll() = dao.deleteAll()

    suspend fun purgeOlderThanRetention() = dao.deleteBefore(System.currentTimeMillis() - RETENTION_DAYS * DAY_MS)

    companion object {
        /** Durée de conservation : couvre la vue statistique la plus longue (30 jours). */
        const val RETENTION_DAYS = 30L
        const val DAY_MS = 86_400_000L
        const val HOUR_MS = 3_600_000L
        private const val JOURNAL_LIMIT = 300
        private const val FLUSH_DELAY_MS = 1_000L
        private const val MAX_BATCH = 500
        private const val TAG = "Journal"

        fun startOfToday(now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().run {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }

        /** Émet le début du jour courant, puis à nouveau juste après chaque minuit. */
        fun startOfDayFlow(): Flow<Long> = flow {
            while (true) {
                val start = startOfToday()
                emit(start)
                val nextMidnight = start + DAY_MS
                delay((nextMidnight - System.currentTimeMillis()).coerceIn(1_000L, 60_000L))
            }
        }.distinctUntilChanged()
    }
}
