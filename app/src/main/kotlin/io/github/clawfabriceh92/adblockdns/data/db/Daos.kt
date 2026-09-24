package io.github.clawfabriceh92.adblockdns.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class BlockedEventDao {
    @Insert
    abstract suspend fun insertAll(events: List<BlockedEventEntity>)

    @Query(
        """
        SELECT * FROM blocked_event
        WHERE (:search = '' OR domain LIKE '%' || :search || '%')
          AND (:appPackage IS NULL OR appPackage = :appPackage)
        ORDER BY timestamp DESC
        LIMIT :limit
        """,
    )
    abstract fun recent(search: String, appPackage: String?, limit: Int): Flow<List<BlockedEventEntity>>

    @Query("SELECT COUNT(*) FROM blocked_event WHERE timestamp >= :since")
    abstract fun countSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(DISTINCT appPackage) FROM blocked_event WHERE timestamp >= :since")
    abstract fun appCountSince(since: Long): Flow<Int>

    @Query(
        """
        SELECT (timestamp + :offsetMs) / :bucketMs AS bucket, COUNT(*) AS count
        FROM blocked_event WHERE timestamp >= :since
        GROUP BY bucket ORDER BY bucket
        """,
    )
    abstract fun histogram(since: Long, bucketMs: Long, offsetMs: Long): Flow<List<BucketCount>>

    @Query(
        """
        SELECT appPackage, MAX(appLabel) AS appLabel, COUNT(*) AS count
        FROM blocked_event WHERE timestamp >= :since
        GROUP BY appPackage ORDER BY count DESC LIMIT :limit
        """,
    )
    abstract fun topApps(since: Long, limit: Int): Flow<List<AppCount>>

    @Query("SELECT category, COUNT(*) AS count FROM blocked_event WHERE timestamp >= :since GROUP BY category")
    abstract fun categories(since: Long): Flow<List<CategoryCount>>

    @Query(
        """
        SELECT appPackage, MAX(appLabel) AS appLabel FROM blocked_event
        WHERE appPackage IS NOT NULL GROUP BY appPackage ORDER BY appLabel COLLATE NOCASE
        """,
    )
    abstract fun apps(): Flow<List<AppRef>>

    @Query("SELECT COUNT(*) FROM blocked_event")
    abstract fun count(): Flow<Int>

    @Query("SELECT * FROM blocked_event WHERE domain = :domain")
    abstract suspend fun byDomain(domain: String): List<BlockedEventEntity>

    @Query("DELETE FROM blocked_event WHERE domain = :domain")
    abstract suspend fun deleteDomain(domain: String)

    @Query("DELETE FROM blocked_event WHERE timestamp < :before")
    abstract suspend fun deleteBefore(before: Long)

    @Query("DELETE FROM blocked_event")
    abstract suspend fun deleteAll()

    /** Retire du journal toutes les entrées d'un domaine et les renvoie (pour « Annuler »). */
    @Transaction
    open suspend fun takeDomain(domain: String): List<BlockedEventEntity> {
        val events = byDomain(domain)
        deleteDomain(domain)
        return events
    }
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rule ORDER BY pattern")
    fun all(): Flow<List<RuleEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rule: RuleEntity): Long

    @Query("DELETE FROM rule WHERE pattern = :pattern AND type = :type")
    suspend fun delete(pattern: String, type: String)
}

@Dao
interface ListStateDao {
    @Query("SELECT * FROM blocklist")
    fun all(): Flow<List<ListStateEntity>>

    @Query("SELECT * FROM blocklist")
    suspend fun allNow(): List<ListStateEntity>

    @Query("SELECT * FROM blocklist WHERE id = :id")
    suspend fun get(id: String): ListStateEntity?

    @Upsert
    suspend fun upsert(state: ListStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(state: ListStateEntity)
}
