package io.github.clawfabriceh92.adblockdns.core.packet

import io.github.clawfabriceh92.adblockdns.core.util.InternetChecksum
import io.github.clawfabriceh92.adblockdns.core.util.put16
import io.github.clawfabriceh92.adblockdns.core.util.put32
import io.github.clawfabriceh92.adblockdns.core.util.u16
import io.github.clawfabriceh92.adblockdns.core.util.u32
import io.github.clawfabriceh92.adblockdns.core.util.u8

/** Datagramme UDP extrait d'un paquet IPv4 ou IPv6 lu dans le tunnel. */
class UdpPacket(
    val ipVersion: Int,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
)

/** En-tête d'un segment TCP : suffisant pour y répondre par un RST. */
class TcpSegment(
    val ipVersion: Int,
    val sourceAddress: ByteArray,
    val destinationAddress: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val sequenceNumber: Long,
    val acknowledgmentNumber: Long,
    val flags: Int,
    val payloadLength: Int,
)

sealed interface ParsedPacket {
    class Udp(val packet: UdpPacket) : ParsedPacket
    class Tcp(val segment: TcpSegment) : ParsedPacket
    data object Unsupported : ParsedPacket
}

/** Lecture et construction de paquets IPv4/IPv6 transportant de l'UDP ou du TCP. */
object IpPackets {
    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_ACK = 0x10

    private const val IPV4_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val UDP_HEADER = 8
    private const val TCP_HEADER = 20
    private const val DEFAULT_TTL = 64

    /** Plus grande charge UDP transportable dans un paquet IPv4 (65535 - 20 - 8). */
    const val MAX_UDP_PAYLOAD = 65_507

    fun parse(buffer: ByteArray, length: Int = buffer.size): ParsedPacket {
        if (length < 1 || length > buffer.size) return ParsedPacket.Unsupported
        return when (buffer.u8(0) ushr 4) {
            4 -> parseIpv4(buffer, length)
            6 -> parseIpv6(buffer, length)
            else -> ParsedPacket.Unsupported
        }
    }

    private fun parseIpv4(b: ByteArray, length: Int): ParsedPacket {
        if (length < IPV4_HEADER) return ParsedPacket.Unsupported
        val headerLength = (b.u8(0) and 0x0F) * 4
        if (headerLength < IPV4_HEADER || headerLength > length) return ParsedPacket.Unsupported
        val totalLength = b.u16(2)
        if (totalLength < headerLength || totalLength > length) return ParsedPacket.Unsupported
        // Fragments ignorés : une requête DNS tient toujours dans un seul paquet.
        val fragment = b.u16(6)
        if (fragment and 0x2000 != 0 || fragment and 0x1FFF != 0) return ParsedPacket.Unsupported
        return parseTransport(
            ipVersion = 4,
            protocol = b.u8(9),
            b = b,
            offset = headerLength,
            end = totalLength,
            source = b.copyOfRange(12, 16),
            destination = b.copyOfRange(16, 20),
        )
    }

    private fun parseIpv6(b: ByteArray, length: Int): ParsedPacket {
        if (length < IPV6_HEADER) return ParsedPacket.Unsupported
        val end = IPV6_HEADER + b.u16(4)
        if (end > length) return ParsedPacket.Unsupported
        var next = b.u8(6)
        var offset = IPV6_HEADER
        // En-têtes d'extension sautés : hop-by-hop (0), routage (43), options de destination (60).
        while (next == 0 || next == 43 || next == 60) {
            if (offset + 8 > end) return ParsedPacket.Unsupported
            val extensionLength = (b.u8(offset + 1) + 1) * 8
            next = b.u8(offset)
            offset += extensionLength
            if (offset > end) return ParsedPacket.Unsupported
        }
        return parseTransport(
            ipVersion = 6,
            protocol = next,
            b = b,
            offset = offset,
            end = end,
            source = b.copyOfRange(8, 24),
            destination = b.copyOfRange(24, 40),
        )
    }

    private fun parseTransport(
        ipVersion: Int,
        protocol: Int,
        b: ByteArray,
        offset: Int,
        end: Int,
        source: ByteArray,
        destination: ByteArray,
    ): ParsedPacket = when (protocol) {
        PROTOCOL_UDP -> {
            val udpLength = if (offset + UDP_HEADER <= end) b.u16(offset + 4) else -1
            if (udpLength < UDP_HEADER || offset + udpLength > end) {
                ParsedPacket.Unsupported
            } else {
                ParsedPacket.Udp(
                    UdpPacket(
                        ipVersion = ipVersion,
                        sourceAddress = source,
                        destinationAddress = destination,
                        sourcePort = b.u16(offset),
                        destinationPort = b.u16(offset + 2),
                        payload = b.copyOfRange(offset + UDP_HEADER, offset + udpLength),
                    ),
                )
            }
        }
        PROTOCOL_TCP -> {
            val dataOffset = if (offset + TCP_HEADER <= end) (b.u8(offset + 12) ushr 4) * 4 else -1
            if (dataOffset < TCP_HEADER || offset + dataOffset > end) {
                ParsedPacket.Unsupported
            } else {
                ParsedPacket.Tcp(
                    TcpSegment(
                        ipVersion = ipVersion,
                        sourceAddress = source,
                        destinationAddress = destination,
                        sourcePort = b.u16(offset),
                        destinationPort = b.u16(offset + 2),
                        sequenceNumber = b.u32(offset + 4),
                        acknowledgmentNumber = b.u32(offset + 8),
                        flags = b.u8(offset + 13),
                        payloadLength = end - offset - dataOffset,
                    ),
                )
            }
        }
        else -> ParsedPacket.Unsupported
    }

    /** Réponse UDP au datagramme [request] : adresses et ports inversés. */
    fun udpReply(request: UdpPacket, payload: ByteArray): ByteArray = buildUdp(
        ipVersion = request.ipVersion,
        source = request.destinationAddress,
        destination = request.sourceAddress,
        sourcePort = request.destinationPort,
        destinationPort = request.sourcePort,
        payload = payload,
    )

    fun buildUdp(
        ipVersion: Int,
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        require(payload.size <= MAX_UDP_PAYLOAD) { "Charge UDP trop grande : ${payload.size} octets" }
        val udpLength = UDP_HEADER + payload.size
        val ipHeader = writeIpHeaderSize(ipVersion)
        val out = ByteArray(ipHeader + udpLength)
        writeIpHeader(out, ipVersion, PROTOCOL_UDP, source, destination, udpLength)
        out.put16(ipHeader, sourcePort)
        out.put16(ipHeader + 2, destinationPort)
        out.put16(ipHeader + 4, udpLength)
        payload.copyInto(out, ipHeader + UDP_HEADER)
        var checksum = transportChecksum(out, PROTOCOL_UDP, source, destination, ipHeader, udpLength)
        if (checksum == 0) checksum = 0xFFFF // 0 signifierait « pas de somme de contrôle » (RFC 768)
        out.put16(ipHeader + 6, checksum)
        return out
    }

    /**
     * Segment RST refusant [segment] (RFC 793, génération d'un reset) : le client échoue
     * immédiatement au lieu d'attendre l'expiration de son délai. Renvoie null si [segment]
     * est lui-même un RST (on ne répond jamais à un RST).
     */
    fun tcpReset(segment: TcpSegment): ByteArray? {
        if (segment.flags and TCP_RST != 0) return null
        val ipHeader = writeIpHeaderSize(segment.ipVersion)
        val out = ByteArray(ipHeader + TCP_HEADER)
        writeIpHeader(out, segment.ipVersion, PROTOCOL_TCP, segment.destinationAddress, segment.sourceAddress, TCP_HEADER)
        val sequence: Long
        val acknowledgment: Long
        val flags: Int
        if (segment.flags and TCP_ACK != 0) {
            sequence = segment.acknowledgmentNumber
            acknowledgment = 0
            flags = TCP_RST
        } else {
            var segmentLength = segment.payloadLength.toLong()
            if (segment.flags and TCP_SYN != 0) segmentLength++
            if (segment.flags and TCP_FIN != 0) segmentLength++
            sequence = 0
            acknowledgment = (segment.sequenceNumber + segmentLength) and 0xFFFF_FFFFL
            flags = TCP_RST or TCP_ACK
        }
        out.put16(ipHeader, segment.destinationPort)
        out.put16(ipHeader + 2, segment.sourcePort)
        out.put32(ipHeader + 4, sequence)
        out.put32(ipHeader + 8, acknowledgment)
        out[ipHeader + 12] = 0x50 // 5 mots de 32 bits, sans option
        out[ipHeader + 13] = flags.toByte()
        val checksum = transportChecksum(
            out, PROTOCOL_TCP, segment.destinationAddress, segment.sourceAddress, ipHeader, TCP_HEADER,
        )
        out.put16(ipHeader + 16, checksum)
        return out
    }

    private fun writeIpHeaderSize(ipVersion: Int): Int = when (ipVersion) {
        4 -> IPV4_HEADER
        6 -> IPV6_HEADER
        else -> throw IllegalArgumentException("Version IP inconnue : $ipVersion")
    }

    private fun writeIpHeader(
        out: ByteArray,
        ipVersion: Int,
        protocol: Int,
        source: ByteArray,
        destination: ByteArray,
        payloadLength: Int,
    ) {
        if (ipVersion == 4) {
            require(source.size == 4 && destination.size == 4) { "Adresse IPv4 invalide" }
            out[0] = 0x45
            out.put16(2, IPV4_HEADER + payloadLength)
            // Identification à 0 : autorisé pour un datagramme non fragmentable (RFC 6864).
            out.put16(6, 0x4000) // DF
            out[8] = DEFAULT_TTL.toByte()
            out[9] = protocol.toByte()
            source.copyInto(out, 12)
            destination.copyInto(out, 16)
            out.put16(10, InternetChecksum.finish(InternetChecksum.sum(out, 0, IPV4_HEADER)))
        } else {
            require(source.size == 16 && destination.size == 16) { "Adresse IPv6 invalide" }
            out[0] = 0x60
            out.put16(4, payloadLength)
            out[6] = protocol.toByte()
            out[7] = DEFAULT_TTL.toByte()
            source.copyInto(out, 8)
            destination.copyInto(out, 24)
        }
    }

    /**
     * Somme de contrôle UDP/TCP avec pseudo-en-tête. Pour IPv4 comme pour IPv6 (longueur
     * < 65536), la contribution du pseudo-en-tête vaut : adresses + protocole + longueur.
     */
    private fun transportChecksum(
        out: ByteArray,
        protocol: Int,
        source: ByteArray,
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        var sum = InternetChecksum.sum(source)
        sum = InternetChecksum.sum(destination, initial = sum)
        sum += protocol + length
        sum = InternetChecksum.sum(out, offset, length, sum)
        return InternetChecksum.finish(sum)
    }
}
