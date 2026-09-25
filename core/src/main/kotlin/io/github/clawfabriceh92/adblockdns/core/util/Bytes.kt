package io.github.clawfabriceh92.adblockdns.core.util

// Lecture et écriture big-endian (ordre réseau) dans des tableaux d'octets.

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

internal fun ByteArray.u16(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

internal fun ByteArray.u32(index: Int): Long = (u16(index).toLong() shl 16) or u16(index + 2).toLong()

internal fun ByteArray.put16(index: Int, value: Int) {
    this[index] = (value ushr 8).toByte()
    this[index + 1] = value.toByte()
}

internal fun ByteArray.put32(index: Int, value: Long) {
    put16(index, (value ushr 16).toInt())
    put16(index + 2, value.toInt())
}

/** Somme de contrôle Internet (RFC 1071) : somme en complément à un de mots de 16 bits. */
internal object InternetChecksum {

    /** Ajoute [length] octets de [data] à la somme partielle [initial] (sans repliement). */
    fun sum(data: ByteArray, offset: Int = 0, length: Int = data.size, initial: Long = 0): Long {
        var sum = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += data.u16(i)
            i += 2
        }
        if (i < end) sum += data.u8(i) shl 8
        return sum
    }

    /** Replie la somme sur 16 bits et renvoie son complément : la valeur à écrire dans l'en-tête. */
    fun finish(sum: Long): Int {
        var s = sum
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return s.toInt().inv() and 0xFFFF
    }
}
