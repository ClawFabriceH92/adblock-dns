package io.github.clawfabriceh92.adblockdns.core.lists

import io.github.clawfabriceh92.adblockdns.core.filter.DomainHash
import io.github.clawfabriceh92.adblockdns.core.filter.HashedDomainSet
import java.io.IOException
import java.io.InputStream

class CompiledList(val domains: HashedDomainSet, val parse: ParseResult)

/** Transforme une liste texte en [HashedDomainSet] en une seule passe, sans garder les chaînes. */
object BlocklistCompiler {
    /** Garde-fou mémoire : 5 millions d'entrées = 40 Mo d'empreintes (hagezi TIF : ~2,2 millions). */
    const val MAX_ENTRIES = 5_000_000

    fun compile(input: InputStream, maxEntries: Int = MAX_ENTRIES): CompiledList {
        var hashes = LongArray(minOf(1 shl 16, maxEntries))
        var count = 0
        val result = BlocklistParser.parse(input.bufferedReader(Charsets.UTF_8)) { domain ->
            if (count == hashes.size) {
                if (count >= maxEntries) throw IOException("Liste trop volumineuse (plus de $maxEntries entrées)")
                hashes = hashes.copyOf(minOf(hashes.size * 2, maxEntries))
            }
            hashes[count++] = DomainHash.hash(domain)
        }
        return CompiledList(HashedDomainSet.fromHashes(hashes, count), result)
    }
}
