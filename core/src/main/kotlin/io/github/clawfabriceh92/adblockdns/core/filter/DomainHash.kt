package io.github.clawfabriceh92.adblockdns.core.filter

/**
 * Empreinte 64 bits d'un nom de domaine : FNV-1a 64 bits suivi du brassage final de
 * MurmurHash3 (fmix64) pour bien répartir les bits.
 *
 * Stocker des empreintes plutôt que des chaînes divise la mémoire par ~10 (8 octets par
 * domaine). Avec n domaines, la probabilité qu'un domaine absent ait la même empreinte qu'un
 * domaine de la liste est d'environ n / 2^64, soit ~1e-13 pour 2 millions de domaines ; la
 * liste blanche corrige de toute façon un éventuel faux positif.
 *
 * Toute modification de cette fonction doit incrémenter [VERSION] : les listes compilées
 * enregistrées avec une autre version sont alors reconstruites.
 */
object DomainHash {
    const val VERSION = 1

    private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L
    private val FMIX_1 = 0xff51afd7ed558ccdUL.toLong()
    private val FMIX_2 = 0xc4ceb9fe1a85ec53UL.toLong()

    /** Empreinte de `domain.substring(start)`, sans allouer de sous-chaîne. */
    fun hash(domain: String, start: Int = 0): Long {
        var h = FNV_OFFSET_BASIS
        for (i in start until domain.length) {
            var c = domain[i].code
            if (c in 'A'.code..'Z'.code) c += 'a'.code - 'A'.code
            h = (h xor c.toLong()) * FNV_PRIME
        }
        h = h xor (h ushr 33)
        h *= FMIX_1
        h = h xor (h ushr 33)
        h *= FMIX_2
        return h xor (h ushr 33)
    }
}
