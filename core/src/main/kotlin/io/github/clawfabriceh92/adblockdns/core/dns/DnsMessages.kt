package io.github.clawfabriceh92.adblockdns.core.dns

import io.github.clawfabriceh92.adblockdns.core.util.put16
import io.github.clawfabriceh92.adblockdns.core.util.u16
import io.github.clawfabriceh92.adblockdns.core.util.u8

/** Question unique d'une requête DNS standard (RFC 1035). */
class DnsQuestion(
    val id: Int,
    val flags: Int,
    /** Nom demandé, en minuscules, sans point final. */
    val name: String,
    val type: Int,
    val questionClass: Int,
    /** Position de fin de la section question dans le message. */
    val questionEnd: Int,
)

/** Lecture et construction des messages DNS nécessaires au filtrage. */
object DnsMessages {
    const val HEADER_SIZE = 12

    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_AAAA = 28
    const val TYPE_HTTPS = 65

    const val RCODE_NOERROR = 0
    const val RCODE_FORMERR = 1
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3

    private const val FLAG_QR = 0x8000
    private const val MASK_OPCODE = 0x7800
    private const val FLAG_TC = 0x0200
    private const val FLAG_RD = 0x0100
    private const val FLAG_RA = 0x0080
    private const val FLAG_CD = 0x0010
    private const val MAX_NAME_WIRE_LENGTH = 255
    private const val MAX_POINTER_JUMPS = 64

    fun id(message: ByteArray): Int = message.u16(0)

    fun isResponse(message: ByteArray): Boolean =
        message.size >= HEADER_SIZE && message.u16(2) and FLAG_QR != 0

    fun isTruncated(message: ByteArray): Boolean =
        message.size >= HEADER_SIZE && message.u16(2) and FLAG_TC != 0

    fun rcode(message: ByteArray): Int = message.u16(2) and 0x000F

    /** Copie de [message] avec l'identifiant [id]. */
    fun withId(message: ByteArray, id: Int): ByteArray = message.copyOf().also { it.put16(0, id) }

    /**
     * Lit la question d'une requête standard (QR=0, OPCODE=QUERY, une seule question).
     * Renvoie null pour tout autre message : l'appelant le transmet alors tel quel.
     */
    fun parseQuestion(message: ByteArray): DnsQuestion? {
        if (message.size < HEADER_SIZE) return null
        val flags = message.u16(2)
        if (flags and FLAG_QR != 0 || flags and MASK_OPCODE != 0) return null
        if (message.u16(4) != 1) return null
        val name = readName(message, HEADER_SIZE) ?: return null
        val offset = name.end
        if (offset + 4 > message.size) return null
        return DnsQuestion(
            id = message.u16(0),
            flags = flags,
            name = name.value,
            type = message.u16(offset),
            questionClass = message.u16(offset + 2),
            questionEnd = offset + 4,
        )
    }

    /** Réponse « domaine inexistant » (NXDOMAIN) à la requête [query]. */
    fun nxdomain(query: ByteArray, question: DnsQuestion): ByteArray =
        headerOnlyResponse(query, question, RCODE_NXDOMAIN, truncated = false)

    /** Réponse d'erreur (SERVFAIL, FORMERR…) : le client échoue vite au lieu d'attendre. */
    fun errorResponse(query: ByteArray, question: DnsQuestion, rcode: Int): ByteArray =
        headerOnlyResponse(query, question, rcode, truncated = false)

    /** Réponse vide marquée tronquée (TC) : invite le client à réessayer autrement. */
    fun truncatedResponse(query: ByteArray, question: DnsQuestion): ByteArray =
        headerOnlyResponse(query, question, RCODE_NOERROR, truncated = true)

    private fun headerOnlyResponse(query: ByteArray, question: DnsQuestion, rcode: Int, truncated: Boolean): ByteArray {
        val out = query.copyOf(question.questionEnd)
        var flags = FLAG_QR or (question.flags and (MASK_OPCODE or FLAG_RD or FLAG_CD)) or FLAG_RA or rcode
        if (truncated) flags = flags or FLAG_TC
        out.put16(2, flags)
        out.put16(4, 1)
        out.put16(6, 0)
        out.put16(8, 0)
        out.put16(10, 0)
        return out
    }

    /**
     * Cibles des enregistrements CNAME de la section réponse de [response]. Sert à détecter le
     * « CNAME cloaking » : un sous-domaine du site qui pointe en réalité vers un traqueur.
     */
    fun cnameTargets(response: ByteArray): List<String> {
        if (response.size < HEADER_SIZE) return emptyList()
        val questions = response.u16(4)
        val answers = response.u16(6)
        if (answers == 0) return emptyList()
        var offset = HEADER_SIZE
        repeat(questions) {
            val name = readName(response, offset) ?: return emptyList()
            offset = name.end + 4
            if (offset > response.size) return emptyList()
        }
        val targets = ArrayList<String>(2)
        repeat(answers) {
            val owner = readName(response, offset) ?: return targets
            offset = owner.end
            if (offset + 10 > response.size) return targets
            val type = response.u16(offset)
            val dataLength = response.u16(offset + 8)
            val dataStart = offset + 10
            if (dataStart + dataLength > response.size) return targets
            if (type == TYPE_CNAME) {
                readName(response, dataStart)?.let { targets.add(it.value) }
            }
            offset = dataStart + dataLength
        }
        return targets
    }

    /** Nom lu dans un message et position qui suit le nom à son emplacement d'origine. */
    class Name(val value: String, val end: Int)

    /**
     * Lit un nom de domaine (suite de labels, pointeurs de compression inclus). Renvoie null si
     * le nom est malformé, trop long ou si les pointeurs bouclent.
     */
    fun readName(message: ByteArray, start: Int): Name? {
        val sb = StringBuilder(64)
        var offset = start
        var end = -1
        var jumps = 0
        var wireLength = 0
        while (true) {
            if (offset >= message.size) return null
            val length = message.u8(offset)
            when (length and 0xC0) {
                0x00 -> {
                    if (length == 0) {
                        if (end < 0) end = offset + 1
                        return Name(sb.toString(), end)
                    }
                    wireLength += length + 1
                    if (wireLength > MAX_NAME_WIRE_LENGTH) return null
                    if (offset + 1 + length > message.size) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    for (i in offset + 1..offset + length) {
                        var c = message.u8(i)
                        if (c in 'A'.code..'Z'.code) c += 'a'.code - 'A'.code
                        sb.append(c.toChar())
                    }
                    offset += length + 1
                }
                0xC0 -> {
                    if (offset + 1 >= message.size) return null
                    if (++jumps > MAX_POINTER_JUMPS) return null
                    if (end < 0) end = offset + 2
                    offset = message.u16(offset) and 0x3FFF
                }
                else -> return null // types de labels étendus (RFC 6891) non utilisés en pratique
            }
        }
    }
}
