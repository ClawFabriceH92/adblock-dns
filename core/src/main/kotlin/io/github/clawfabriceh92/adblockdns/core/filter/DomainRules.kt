package io.github.clawfabriceh92.adblockdns.core.filter

/**
 * Syntaxe des règles saisies à la main (listes blanche et noire) :
 * - `exemple.com` : le domaine et tous ses sous-domaines ;
 * - motif avec jokers `*` (n'importe quelle suite de caractères, points compris) :
 *   `*.exemple.com` = sous-domaines seulement, `pub*.exemple.com`, `*tracker*`…
 *
 * La saisie est tolérante : une URL collée, une ligne de fichier hosts ou une règle adblock
 * `||exemple.com^` sont ramenées au domaine.
 */
object RuleSyntax {

    /** Forme canonique de [input], ou null si la saisie n'est pas une règle valide. */
    fun normalize(input: String): String? {
        var s = input.trim().lowercase()
        if (s.isEmpty()) return null
        val scheme = s.indexOf("://")
        if (scheme >= 0) {
            s = s.substring(scheme + 3)
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('@')
                .substringBefore(':')
        }
        s = s.removePrefix("||").removeSuffix("^")
        if (s.any { it.isWhitespace() }) s = s.split(WHITESPACE).last()
        s = s.trimEnd('.').removePrefix(".")
        if (s.isEmpty() || s.length > 253) return null
        if ('*' in s) {
            val allowed = s.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' || it == '*' }
            // Un motif sans aucun caractère fixe (« * », « *.* ») bloquerait tout.
            val hasFixedPart = s.any { it != '*' && it != '.' }
            return s.takeIf { allowed && hasFixedPart }
        }
        return s.takeIf { Domains.isValidHostname(it) }
    }

    fun isPattern(rule: String): Boolean = '*' in rule

    fun toRegex(pattern: String): Regex =
        Regex(pattern.split('*').joinToString(".*") { if (it.isEmpty()) "" else Regex.escape(it) })

    private val WHITESPACE = Regex("\\s+")
}

/**
 * Ensemble de règles manuelles prêt pour la recherche. Les domaines simples sont indexés par
 * empreinte (aucune allocation par requête), les motifs sont testés un par un : les listes
 * manuelles ne contiennent que quelques dizaines de règles.
 */
class RuleMatcher(rules: Collection<String>) {
    private val domainRules = HashMap<Long, String>()
    private val patterns = ArrayList<Pair<String, Regex>>()

    init {
        for (rule in rules) {
            if (RuleSyntax.isPattern(rule)) {
                patterns += rule to RuleSyntax.toRegex(rule)
            } else {
                domainRules[DomainHash.hash(rule)] = rule
            }
        }
    }

    val isEmpty: Boolean get() = domainRules.isEmpty() && patterns.isEmpty()

    /** Première règle qui s'applique à [domain], ou null. */
    fun match(domain: String): String? {
        if (domainRules.isNotEmpty()) {
            var start = 0
            while (true) {
                val rule = domainRules[DomainHash.hash(domain, start)]
                if (rule != null && rule.length == domain.length - start &&
                    domain.regionMatches(start, rule, 0, rule.length)
                ) {
                    return rule
                }
                val dot = domain.indexOf('.', start)
                if (dot < 0) break
                start = dot + 1
            }
        }
        for ((rule, regex) in patterns) {
            if (regex.matches(domain)) return rule
        }
        return null
    }

    companion object {
        val EMPTY = RuleMatcher(emptyList())
    }
}
