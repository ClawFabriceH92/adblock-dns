package io.github.clawfabriceh92.adblockdns.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Requête bloquée, telle qu'affichée dans le journal. */
@Entity(
    tableName = "blocked_event",
    indices = [Index("timestamp"), Index("domain"), Index("appPackage")],
)
data class BlockedEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val domain: String,
    val queryType: Int,
    /** UID Android de l'application, ou -1 si inconnu. */
    val uid: Int,
    /** Paquet et nom de l'application au moment du blocage (conservés après désinstallation). */
    val appPackage: String?,
    val appLabel: String,
    /** Catégorie : nom d'une [io.github.clawfabriceh92.adblockdns.core.filter.ListCategory] ou RULE. */
    val category: String,
    /** Identifiant de la liste ou texte de la règle à l'origine du blocage. */
    val source: String,
    val matched: String,
    val viaCname: String?,
)

/** Règle manuelle : liste blanche (ALLOW) ou liste noire (BLOCK). */
@Entity(tableName = "rule", indices = [Index(value = ["pattern", "type"], unique = true)])
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val type: String,
    val createdAt: Long,
) {
    companion object {
        const val ALLOW = "ALLOW"
        const val BLOCK = "BLOCK"
    }
}

/** État local d'une liste de blocage du catalogue. */
@Entity(tableName = "blocklist")
data class ListStateEntity(
    @PrimaryKey val id: String,
    val enabled: Boolean,
    val entryCount: Int = 0,
    /** Date de compilation du fichier en service, ou null s'il n'y en a pas encore. */
    val installedAt: Long? = null,
    /** Version annoncée par la liste (en-tête « Version » ou date). */
    val sourceVersion: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastCheckAt: Long? = null,
    val lastError: String? = null,
    /** Nom du fichier importé (liste personnelle). */
    val displayName: String? = null,
)

/** Résultats d'agrégation pour les statistiques. */
data class BucketCount(val bucket: Long, val count: Int)

data class AppCount(val appPackage: String?, val appLabel: String, val count: Int)

data class CategoryCount(val category: String, val count: Int)

data class AppRef(val appPackage: String, val appLabel: String)
