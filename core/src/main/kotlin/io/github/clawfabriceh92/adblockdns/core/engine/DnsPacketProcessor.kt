package io.github.clawfabriceh92.adblockdns.core.engine

import io.github.clawfabriceh92.adblockdns.core.dns.DnsMessages
import io.github.clawfabriceh92.adblockdns.core.dns.DnsQuestion
import io.github.clawfabriceh92.adblockdns.core.filter.BlockSource
import io.github.clawfabriceh92.adblockdns.core.filter.FilterDecision
import io.github.clawfabriceh92.adblockdns.core.filter.FilterEngine
import io.github.clawfabriceh92.adblockdns.core.packet.IpPackets
import io.github.clawfabriceh92.adblockdns.core.packet.ParsedPacket
import io.github.clawfabriceh92.adblockdns.core.packet.UdpPacket
import io.github.clawfabriceh92.adblockdns.core.upstream.DnsUpstream
import java.io.IOException

/** Requête bloquée, transmise au journal. */
class BlockedQuery(
    val timestamp: Long,
    /** Nom demandé par l'application. */
    val domain: String,
    val queryType: Int,
    val source: BlockSource,
    /** Règle ou domaine de liste qui a déclenché le blocage. */
    val matched: String,
    /** Cible CNAME bloquée quand le nom demandé était lui-même autorisé (CNAME cloaking). */
    val viaCname: String? = null,
)

/**
 * Traite les paquets lus dans le tunnel : seules les requêtes DNS arrivent ici, car le tunnel
 * ne route que l'adresse du serveur DNS virtuel. Principe « fail-open » : en cas de doute
 * (message non standard, erreur interne), la requête est transmise telle quelle au résolveur
 * amont, jamais bloquée ni perdue.
 *
 * Sans état et utilisable depuis plusieurs threads ; les dépendances sont relues à chaque
 * paquet pour prendre en compte immédiatement un changement de règle ou de résolveur.
 */
class DnsPacketProcessor(
    private val engine: () -> FilterEngine,
    private val upstream: () -> DnsUpstream,
    private val listener: Listener,
    private val cnameInspection: () -> Boolean = { true },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Listener {
        /**
         * Appelé pour chaque blocage, avant l'envoi de la réponse : la socket de l'application
         * existe encore, ce qui permet d'identifier son propriétaire.
         */
        fun onBlocked(query: BlockedQuery, packet: UdpPacket)

        fun onForwarded(domain: String?) {}

        fun onUpstreamFailure(domain: String?, error: IOException) {}
    }

    /** Traite un paquet et renvoie le paquet à réinjecter dans le tunnel, ou null. */
    fun process(buffer: ByteArray, length: Int = buffer.size): ByteArray? =
        when (val parsed = IpPackets.parse(buffer, length)) {
            is ParsedPacket.Udp -> processUdp(parsed.packet)
            // DNS sur TCP et DNS-over-TLS (853) ne sont pas pris en charge : refus immédiat, pour
            // que le client repasse en UDP ou échoue vite au lieu d'attendre son délai.
            is ParsedPacket.Tcp -> IpPackets.tcpReset(parsed.segment)
            ParsedPacket.Unsupported -> null
        }

    private fun processUdp(packet: UdpPacket): ByteArray? {
        if (packet.destinationPort != DNS_PORT) return null
        val query = packet.payload
        val question = DnsMessages.parseQuestion(query) ?: return forward(packet, query, null)
        val decision = try {
            engine().decide(question.name)
        } catch (e: RuntimeException) {
            FilterDecision.Allowed // fail-open
        }
        if (decision is FilterDecision.Blocked) {
            return block(packet, query, question, BlockedQuery(clock(), question.name, question.type, decision.source, decision.matched))
        }
        return forward(packet, query, question, inspectCnames = decision == FilterDecision.Allowed)
    }

    private fun forward(
        packet: UdpPacket,
        query: ByteArray,
        question: DnsQuestion?,
        inspectCnames: Boolean = false,
    ): ByteArray? {
        val response = try {
            upstream().resolve(query)
        } catch (e: IOException) {
            listener.onUpstreamFailure(question?.name, e)
            // Échec rapide plutôt qu'un silence de plusieurs secondes côté application.
            return question?.let { IpPackets.udpReply(packet, DnsMessages.errorResponse(query, it, DnsMessages.RCODE_SERVFAIL)) }
        }
        if (question != null && inspectCnames && cnameInspection()) {
            val cloaked = findBlockedCname(response)
            if (cloaked != null) {
                val (target, decision) = cloaked
                val blocked = BlockedQuery(clock(), question.name, question.type, decision.source, decision.matched, viaCname = target)
                return block(packet, query, question, blocked)
            }
        }
        listener.onForwarded(question?.name)
        val payload = when {
            response.size <= IpPackets.MAX_UDP_PAYLOAD -> response
            question != null -> DnsMessages.truncatedResponse(query, question)
            else -> return null
        }
        return IpPackets.udpReply(packet, payload)
    }

    private fun block(packet: UdpPacket, query: ByteArray, question: DnsQuestion, blocked: BlockedQuery): ByteArray {
        listener.onBlocked(blocked, packet)
        return IpPackets.udpReply(packet, DnsMessages.nxdomain(query, question))
    }

    /**
     * Première cible CNAME bloquée de la réponse ; une cible en liste blanche lève l'alerte.
     * Toute erreur laisse passer la réponse (fail-open).
     */
    private fun findBlockedCname(response: ByteArray): Pair<String, FilterDecision.Blocked>? = try {
        val filter = engine()
        var found: Pair<String, FilterDecision.Blocked>? = null
        for (target in DnsMessages.cnameTargets(response)) {
            val decision = filter.decide(target)
            if (decision is FilterDecision.AllowedByRule) break
            if (decision is FilterDecision.Blocked) {
                found = target to decision
                break
            }
        }
        found
    } catch (e: RuntimeException) {
        null
    }

    companion object {
        const val DNS_PORT = 53
    }
}
