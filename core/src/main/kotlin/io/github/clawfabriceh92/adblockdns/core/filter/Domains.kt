package io.github.clawfabriceh92.adblockdns.core.filter

/** Règles de forme des noms de domaine acceptés dans les listes et les règles manuelles. */
object Domains {
    private const val MAX_LENGTH = 253
    private const val MAX_LABEL = 63

    /**
     * Vrai si [domain] (déjà en minuscules, sans point final) est un nom d'hôte utilisable :
     * au moins [minLabels] labels de 1 à 63 caractères [a-z0-9-_], 253 caractères au plus,
     * et un dernier label non entièrement numérique (ce qui écarte les adresses IPv4).
     */
    fun isValidHostname(domain: String, minLabels: Int = 2): Boolean {
        if (domain.isEmpty() || domain.length > MAX_LENGTH) return false
        var labels = 0
        var labelLength = 0
        var labelAllDigits = true
        for (c in domain) {
            if (c == '.') {
                if (labelLength == 0) return false
                labels++
                labelLength = 0
                labelAllDigits = true
                continue
            }
            val allowed = c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
            if (!allowed) return false
            if (c !in '0'..'9') labelAllDigits = false
            if (++labelLength > MAX_LABEL) return false
        }
        if (labelLength == 0) return false
        labels++
        return labels >= minLabels && !labelAllDigits
    }

    /** Minuscules ASCII et suppression du point final ; null si le résultat est invalide. */
    fun normalize(raw: String, minLabels: Int = 2): String? {
        val lower = raw.trim().lowercase().trimEnd('.')
        return lower.takeIf { isValidHostname(it, minLabels) }
    }
}
