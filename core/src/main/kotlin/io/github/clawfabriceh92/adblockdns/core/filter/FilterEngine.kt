package io.github.clawfabriceh92.adblockdns.core.filter

/**
 * Catégorie d'une liste de blocage, reprise dans le journal et les statistiques.
 *
 * Les listes hagezi Multi ne distinguent pas publicité et traçage domaine par domaine :
 * la catégorie est donc celle de la liste qui a bloqué, pas une analyse du domaine.
 */
enum class ListCategory {
    /** Publicité et traçage (hagezi Pro, StevenBlack…). */
    ADS_TRACKING,

    /** Domaines malveillants : hameçonnage, logiciels malveillants, arnaques (hagezi TIF). */
    MALWARE,

    /** Liste importée par l'utilisateur. */
    CUSTOM,
}

/** Liste compilée et chargée en mémoire. */
class LoadedList(val id: String, val category: ListCategory, val domains: HashedDomainSet)

sealed interface BlockSource {
    data class FromList(val listId: String, val category: ListCategory) : BlockSource
    data class FromRule(val rule: String) : BlockSource
    /** Règle intégrée (domaine « canari » de Firefox). */
    data object Builtin : BlockSource
}

sealed interface FilterDecision {
    data object Allowed : FilterDecision
    data class AllowedByRule(val rule: String) : FilterDecision
    data class Blocked(val source: BlockSource, val matched: String) : FilterDecision
}

/**
 * Décision de filtrage d'un nom de domaine. Ordre de priorité :
 * 1. liste blanche manuelle (toujours prioritaire, sous-domaines compris) ;
 * 2. domaine canari de Firefox ;
 * 3. liste noire manuelle ;
 * 4. listes de blocage, malveillants d'abord (pour l'attribution de catégorie).
 *
 * Instance immuable : le service en construit une nouvelle à chaque changement de règle ou de
 * liste et la remplace atomiquement, sans interrompre le tunnel.
 */
class FilterEngine(
    private val allowRules: RuleMatcher,
    private val blockRules: RuleMatcher,
    lists: List<LoadedList>,
) {
    private val lists: List<LoadedList> = lists.sortedBy { CATEGORY_PRIORITY.indexOf(it.category) }

    /** Nombre total de domaines chargés depuis les listes (doublons entre listes compris). */
    val listEntryCount: Int = lists.sumOf { it.domains.size }

    fun decide(domain: String): FilterDecision {
        allowRules.match(domain)?.let { return FilterDecision.AllowedByRule(it) }
        if (domain == FIREFOX_CANARY_DOMAIN) return FilterDecision.Blocked(BlockSource.Builtin, domain)
        blockRules.match(domain)?.let { return FilterDecision.Blocked(BlockSource.FromRule(it), it) }
        for (list in lists) {
            val matched = list.domains.findMatchingSuffix(domain) ?: continue
            return FilterDecision.Blocked(BlockSource.FromList(list.id, list.category), matched)
        }
        return FilterDecision.Allowed
    }

    companion object {
        /**
         * Répondre NXDOMAIN pour ce domaine indique à Firefox qu'un filtrage DNS local est en place :
         * il n'active alors pas son DNS-over-HTTPS par défaut, qui contournerait le bloqueur.
         * Source : code de Firefox, toolkit/components/doh/DoHHeuristics.sys.mjs, fonction
         * globalCanary() (NXDOMAIN sur « use-application-dns.net. » → "disable_doh"),
         * https://github.com/mozilla-firefox/firefox (vérifié le 24/09/2026).
         */
        const val FIREFOX_CANARY_DOMAIN = "use-application-dns.net"

        private val CATEGORY_PRIORITY = listOf(ListCategory.MALWARE, ListCategory.ADS_TRACKING, ListCategory.CUSTOM)

        val EMPTY = FilterEngine(RuleMatcher.EMPTY, RuleMatcher.EMPTY, emptyList())
    }
}
