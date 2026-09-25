package io.github.clawfabriceh92.adblockdns.core.lists

import io.github.clawfabriceh92.adblockdns.core.filter.Domains
import java.io.BufferedReader

/** Métadonnées lues dans les commentaires d'en-tête d'une liste (quand elles existent). */
data class ListHeader(
    val title: String? = null,
    val version: String? = null,
    val lastModified: String? = null,
    val declaredEntries: Int? = null,
)

data class ParseResult(
    /** Domaines acceptés (doublons compris). */
    val accepted: Int,
    /** Lignes de règle ignorées : syntaxe non prise en charge, IP, nom invalide… */
    val rejected: Int,
    val header: ListHeader,
)

/**
 * Lit une liste de blocage ligne par ligne, quel que soit son format :
 * - domaines seuls (« Wildcard Domains » de hagezi) : `ads.exemple.com` ;
 * - joker en tête : `*.exemple.com` ;
 * - fichier hosts : `0.0.0.0 ads.exemple.com` (plusieurs noms possibles par ligne) ;
 * - adblock limité au DNS : `||ads.exemple.com^` (option `$important` tolérée).
 *
 * Toutes les entrées sont interprétées « domaine et sous-domaines ». Les commentaires (`#`,
 * `!`), les exceptions adblock (`@@`), les règles cosmétiques et les entrées de boucle locale
 * des fichiers hosts (localhost, broadcasthost…) sont ignorés.
 */
object BlocklistParser {
    private const val MAX_HEADER_LINES = 60
    private val IGNORED_HOSTS = setOf("localhost.localdomain", "ip6-localhost.localdomain")

    fun parse(reader: BufferedReader, onDomain: (String) -> Unit): ParseResult {
        var accepted = 0
        var rejected = 0
        val header = HeaderCollector()
        var lineNumber = 0
        while (true) {
            val raw = reader.readLine() ?: break
            lineNumber++
            val line = raw.trim()
            if (line.isEmpty()) continue
            val first = line[0]
            if (first == '#' || first == '!') {
                if (lineNumber <= MAX_HEADER_LINES) header.offer(line.substring(1).trim())
                continue
            }
            if (first == '[') continue // « [Adblock Plus] »
            val count = parseRuleLine(line, onDomain)
            if (count > 0) accepted += count else rejected++
        }
        return ParseResult(accepted, rejected, header.build())
    }

    /** Traite une ligne de règle et renvoie le nombre de domaines émis. */
    private fun parseRuleLine(line: String, onDomain: (String) -> Unit): Int {
        if (line.startsWith("@@")) return 0
        if (line.startsWith("||")) {
            val end = line.indexOf('^')
            if (end < 0) return 0
            val options = line.substring(end + 1)
            if (options.isNotEmpty() && options != "|" && options != "\$important") return 0
            return emit(line.substring(2, end), onDomain)
        }
        // « # » précédé d'un espace : commentaire de fin de ligne (hosts). Collé à un nom, c'est une
        // règle cosmétique adblock (« exemple.com##.pub ») sans rapport avec le DNS : ignorée.
        val hash = line.indexOf('#')
        if (hash > 0 && !line[hash - 1].isWhitespace()) return 0
        val content = (if (hash >= 0) line.substring(0, hash) else line).trim()
        if (content.isEmpty()) return 0
        val firstSpace = content.indexOfFirst { it == ' ' || it == '\t' }
        if (firstSpace < 0) {
            return emit(content.removePrefix("*.").removePrefix("."), onDomain)
        }
        val tokens = content.split(' ', '\t').filter { it.isNotEmpty() }
        if (!looksLikeIpAddress(tokens[0])) return 0
        var count = 0
        for (i in 1 until tokens.size) count += emit(tokens[i], onDomain)
        return count
    }

    private fun emit(candidate: String, onDomain: (String) -> Unit): Int {
        val domain = Domains.normalize(candidate) ?: return 0
        if (domain in IGNORED_HOSTS) return 0
        onDomain(domain)
        return 1
    }

    private fun looksLikeIpAddress(token: String): Boolean =
        ':' in token || (token.isNotEmpty() && token.all { it.isDigit() || it == '.' })

    /** Reconnaît les clés d'en-tête de hagezi (« Title: », « Version: »…) et de StevenBlack. */
    private class HeaderCollector {
        private var title: String? = null
        private var version: String? = null
        private var lastModified: String? = null
        private var entries: Int? = null

        fun offer(comment: String) {
            val colon = comment.indexOf(':')
            if (colon <= 0) return
            val key = comment.substring(0, colon).trim().lowercase()
            val value = comment.substring(colon + 1).trim().takeIf { it.isNotEmpty() } ?: return
            when (key) {
                "title" -> if (title == null) title = value
                "version" -> if (version == null) version = value
                "last modified", "last-modified", "date", "updated" -> if (lastModified == null) lastModified = value
                "number of entries", "number of unique domains", "entries" ->
                    if (entries == null) entries = value.filter { it.isDigit() }.toIntOrNull()
            }
        }

        fun build() = ListHeader(title, version, lastModified, entries)
    }
}
