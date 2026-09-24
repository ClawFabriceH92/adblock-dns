package io.github.clawfabriceh92.adblockdns.core.filter

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays

/**
 * Ensemble de domaines bloqués stocké sous forme d'empreintes 64 bits triées : recherche
 * par dichotomie (~21 comparaisons pour 2 millions d'entrées), 8 octets par domaine, aucune
 * allocation pendant la recherche.
 *
 * Un domaine est bloqué si lui-même ou l'un de ses parents figure dans l'ensemble :
 * `doubleclick.net` bloque `ads.g.doubleclick.net`. Le dernier label seul (TLD) n'est jamais
 * testé.
 */
class HashedDomainSet private constructor(private val sortedHashes: LongArray) {

    val size: Int get() = sortedHashes.size

    fun containsHash(hash: Long): Boolean = Arrays.binarySearch(sortedHashes, hash) >= 0

    /** Vrai si [domain] (minuscules, sans point final) est exactement dans l'ensemble. */
    fun containsExact(domain: String): Boolean = containsHash(DomainHash.hash(domain))

    /**
     * Renvoie la partie de [domain] trouvée dans l'ensemble (le domaine lui-même ou le parent le
     * plus long), ou null.
     */
    fun findMatchingSuffix(domain: String): String? {
        var start = 0
        while (true) {
            val dot = domain.indexOf('.', start)
            if (dot < 0) return null
            if (containsHash(DomainHash.hash(domain, start))) {
                return if (start == 0) domain else domain.substring(start)
            }
            start = dot + 1
        }
    }

    /** Écrit l'ensemble au format binaire relu par [readFrom] (en-tête puis empreintes big-endian). */
    fun writeTo(output: OutputStream) {
        val out = DataOutputStream(output.buffered(1 shl 16))
        out.writeInt(MAGIC)
        out.writeInt(DomainHash.VERSION)
        out.writeInt(sortedHashes.size)
        val block = ByteArray(BLOCK_ENTRIES * 8)
        var i = 0
        while (i < sortedHashes.size) {
            val n = minOf(BLOCK_ENTRIES, sortedHashes.size - i)
            for (k in 0 until n) {
                val h = sortedHashes[i + k]
                val o = k * 8
                for (b in 0 until 8) block[o + b] = (h ushr (56 - 8 * b)).toByte()
            }
            out.write(block, 0, n * 8)
            i += n
        }
        out.flush()
    }

    companion object {
        /** « ADBH » en ASCII. */
        private const val MAGIC = 0x41444248
        private const val MAX_ENTRIES = 50_000_000
        private const val BLOCK_ENTRIES = 8192

        val EMPTY = HashedDomainSet(LongArray(0))

        /** Construit l'ensemble à partir des [count] premières empreintes de [hashes] (non triées). */
        fun fromHashes(hashes: LongArray, count: Int = hashes.size): HashedDomainSet {
            require(count in 0..hashes.size)
            val sorted = hashes.copyOf(count)
            sorted.sort()
            var unique = 0
            for (i in sorted.indices) {
                if (i == 0 || sorted[i] != sorted[unique - 1]) sorted[unique++] = sorted[i]
            }
            return HashedDomainSet(if (unique == sorted.size) sorted else sorted.copyOf(unique))
        }

        fun fromDomains(domains: Iterable<String>): HashedDomainSet {
            val list = domains.map { DomainHash.hash(it) }
            return fromHashes(list.toLongArray())
        }

        /**
         * Relit un ensemble écrit par [writeTo]. Lève [IOException] si le fichier est tronqué,
         * corrompu ou produit par une autre version de [DomainHash].
         */
        fun readFrom(input: InputStream): HashedDomainSet {
            val data = DataInputStream(input.buffered(1 shl 16))
            try {
                if (data.readInt() != MAGIC) throw IOException("Fichier de liste compilée invalide")
                val version = data.readInt()
                if (version != DomainHash.VERSION) throw IOException("Version d'empreinte $version non prise en charge")
                val count = data.readInt()
                if (count < 0 || count > MAX_ENTRIES) throw IOException("Nombre d'entrées invalide : $count")
                val hashes = LongArray(count)
                val block = ByteArray(BLOCK_ENTRIES * 8)
                var i = 0
                while (i < count) {
                    val n = minOf(BLOCK_ENTRIES, count - i)
                    data.readFully(block, 0, n * 8)
                    for (k in 0 until n) {
                        val o = k * 8
                        var h = 0L
                        for (b in 0 until 8) h = (h shl 8) or (block[o + b].toLong() and 0xFF)
                        hashes[i + k] = h
                    }
                    i += n
                }
                // La recherche par dichotomie exige un tableau trié et sans doublon.
                for (k in 1 until count) {
                    if (hashes[k] <= hashes[k - 1]) throw IOException("Liste compilée non triée")
                }
                return HashedDomainSet(hashes)
            } catch (e: EOFException) {
                throw IOException("Liste compilée tronquée", e)
            }
        }
    }
}
