package io.github.clawfabriceh92.adblockdns.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Domaine résolu récemment (non bloqué), regroupé par application. */
data class RecentDomain(
    val domain: String,
    val appPackage: String?,
    val appLabel: String,
    val lastSeen: Long,
    val count: Int,
)

/**
 * Derniers domaines autorisés, en mémoire seulement : rien n'est écrit sur le téléphone et tout
 * est effacé à l'arrêt de la protection. Sert à repérer le domaine d'une publicité qui passe
 * entre les mailles des listes, pour le bloquer en un geste.
 */
class RecentQueries(private val capacity: Int = CAPACITY) {
    // Ordre d'accès : le domaine vu le plus récemment passe en fin de table.
    private val entries = LinkedHashMap<String, RecentDomain>(capacity, 0.75f, true)
    private val _version = MutableStateFlow(0L)

    /** Change à chaque modification ; le contenu se lit avec [snapshot]. */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun record(domain: String, appPackage: String?, appLabel: String, timestamp: Long) {
        // Requêtes inverses (adresse → nom) : sans intérêt pour repérer une publicité.
        if (domain.endsWith(".arpa")) return
        synchronized(entries) {
            val key = "$domain|${appPackage ?: appLabel}"
            val previous = entries[key]
            entries[key] = RecentDomain(domain, appPackage, appLabel, timestamp, (previous?.count ?: 0) + 1)
            if (entries.size > capacity) entries.remove(entries.keys.first())
        }
        _version.update { it + 1 }
    }

    /** Retire un domaine (toutes applications confondues), par exemple après l'avoir bloqué. */
    fun remove(domain: String) {
        synchronized(entries) { entries.values.removeAll { it.domain == domain } }
        _version.update { it + 1 }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
        _version.update { it + 1 }
    }

    /** Du plus récent au plus ancien. */
    fun snapshot(): List<RecentDomain> = synchronized(entries) { entries.values.toList() }.asReversed()

    companion object {
        const val CAPACITY = 300
    }
}
