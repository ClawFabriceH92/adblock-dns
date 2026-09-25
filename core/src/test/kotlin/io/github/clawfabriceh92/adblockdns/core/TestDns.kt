package io.github.clawfabriceh92.adblockdns.core

import java.io.ByteArrayOutputStream

/** Encodeur DNS minimal pour les tests, écrit indépendamment du code de production. */
object TestDns {

    fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        if (name.isNotEmpty()) {
            for (label in name.split('.')) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                out.write(bytes.size)
                out.write(bytes)
            }
        }
        out.write(0)
        return out.toByteArray()
    }

    /** Requête standard à une question (RD=1), avec un enregistrement OPT EDNS0 si [edns]. */
    fun query(name: String, type: Int = 1, id: Int = 0x1234, edns: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16(id)
        out.u16(0x0100)
        out.u16(1)
        out.u16(0)
        out.u16(0)
        out.u16(if (edns) 1 else 0)
        out.write(encodeName(name))
        out.u16(type)
        out.u16(1)
        if (edns) {
            out.write(0) // nom racine
            out.u16(41) // OPT
            out.u16(1232) // taille UDP annoncée
            out.u32(0)
            out.u16(0)
        }
        return out.toByteArray()
    }

    class Answer(val type: Int, val rdata: ByteArray, val ttl: Long = 300)

    fun a(vararg octets: Int) = Answer(1, ByteArray(4) { octets[it].toByte() })

    fun cname(target: String) = Answer(5, encodeName(target))

    /**
     * Réponse NOERROR à [query] : chaque réponse a pour propriétaire le nom de la question,
     * désigné par un pointeur de compression vers la section question (offset 12).
     */
    fun response(query: ByteArray, answers: List<Answer>, flags: Int = 0x8180): ByteArray {
        val questionEnd = questionEnd(query)
        val out = ByteArrayOutputStream()
        out.write(query, 0, 2)
        out.u16(flags)
        out.u16(1)
        out.u16(answers.size)
        out.u16(0)
        out.u16(0)
        out.write(query, 12, questionEnd - 12)
        for (answer in answers) {
            out.u16(0xC00C)
            out.u16(answer.type)
            out.u16(1)
            out.u32(answer.ttl)
            out.u16(answer.rdata.size)
            out.write(answer.rdata)
        }
        return out.toByteArray()
    }

    private fun questionEnd(query: ByteArray): Int {
        var i = 12
        while (query[i].toInt() != 0) i += (query[i].toInt() and 0xFF) + 1
        return i + 1 + 4
    }

    private fun ByteArrayOutputStream.u16(v: Int) {
        write(v ushr 8 and 0xFF)
        write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.u32(v: Long) {
        u16((v ushr 16).toInt() and 0xFFFF)
        u16(v.toInt() and 0xFFFF)
    }
}

fun hex(s: String): ByteArray {
    val clean = s.filter { !it.isWhitespace() }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun ByteArray.u16At(i: Int): Int = ((this[i].toInt() and 0xFF) shl 8) or (this[i + 1].toInt() and 0xFF)
