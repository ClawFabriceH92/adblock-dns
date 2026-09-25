package io.github.clawfabriceh92.adblockdns.core.packet

import io.github.clawfabriceh92.adblockdns.core.hex
import io.github.clawfabriceh92.adblockdns.core.u16At
import io.github.clawfabriceh92.adblockdns.core.util.InternetChecksum
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class IpPacketsTest {
    private val client4 = byteArrayOf(192.toByte(), 0, 2, 1)
    private val dns4 = byteArrayOf(192.toByte(), 0, 2, 2)
    private val client6 = hex("fd00 0000 0000 0000 0000 0000 0000 0001")
    private val dns6 = hex("fd00 0000 0000 0000 0000 0000 0000 0002")

    @Test
    fun `somme de controle de l'exemple numerique de la RFC 1071`() {
        // RFC 1071 §3 : somme repliée 0xddf2, donc somme de contrôle 0x220d.
        val data = hex("0001 f203 f4f5 f6f7")
        assertEquals(0x220d, InternetChecksum.finish(InternetChecksum.sum(data)))
    }

    @Test
    fun `somme de controle d'un en-tete IPv4 connu`() {
        // En-tête IPv4 (checksum à zéro) dont la somme de contrôle attendue est 0xb861.
        val header = hex("4500 0073 0000 4000 4011 0000 c0a8 0001 c0a8 00c7")
        assertEquals(0xb861, InternetChecksum.finish(InternetChecksum.sum(header)))
    }

    @Test
    fun `datagramme UDP IPv4 construit puis relu`() {
        val payload = "bonjour".toByteArray()
        val packet = IpPackets.buildUdp(4, client4, dns4, 40000, 53, payload)

        assertEquals(20 + 8 + payload.size, packet.size)
        assertEquals(0, InternetChecksum.finish(InternetChecksum.sum(packet, 0, 20)), "en-tête IPv4 valide")
        assertEquals(0, udpChecksumResidue(packet, 20, client4, dns4), "somme UDP valide")

        val udp = assertIs<ParsedPacket.Udp>(IpPackets.parse(packet)).packet
        assertEquals(4, udp.ipVersion)
        assertContentEquals(client4, udp.sourceAddress)
        assertContentEquals(dns4, udp.destinationAddress)
        assertEquals(40000, udp.sourcePort)
        assertEquals(53, udp.destinationPort)
        assertContentEquals(payload, udp.payload)
    }

    @Test
    fun `datagramme UDP IPv6 construit puis relu`() {
        val payload = ByteArray(301) { it.toByte() } // longueur impaire : octet de bourrage
        val packet = IpPackets.buildUdp(6, client6, dns6, 5353, 53, payload)

        assertEquals(40 + 8 + payload.size, packet.size)
        assertEquals(0, udpChecksumResidue(packet, 40, client6, dns6), "somme UDP valide")
        val udp = assertIs<ParsedPacket.Udp>(IpPackets.parse(packet)).packet
        assertEquals(6, udp.ipVersion)
        assertContentEquals(dns6, udp.destinationAddress)
        assertContentEquals(payload, udp.payload)
    }

    @Test
    fun `la reponse inverse adresses et ports`() {
        val request = assertIs<ParsedPacket.Udp>(IpPackets.parse(IpPackets.buildUdp(4, client4, dns4, 40000, 53, byteArrayOf(1)))).packet
        val reply = assertIs<ParsedPacket.Udp>(IpPackets.parse(IpPackets.udpReply(request, byteArrayOf(2, 3)))).packet
        assertContentEquals(dns4, reply.sourceAddress)
        assertContentEquals(client4, reply.destinationAddress)
        assertEquals(53, reply.sourcePort)
        assertEquals(40000, reply.destinationPort)
    }

    @Test
    fun `octets en trop apres le paquet IPv4 ignores`() {
        val packet = IpPackets.buildUdp(4, client4, dns4, 1, 53, byteArrayOf(9, 9))
        val buffer = packet.copyOf(packet.size + 50)
        val udp = assertIs<ParsedPacket.Udp>(IpPackets.parse(buffer, buffer.size)).packet
        assertContentEquals(byteArrayOf(9, 9), udp.payload)
    }

    @Test
    fun `fragments et paquets tronques refuses`() {
        val packet = IpPackets.buildUdp(4, client4, dns4, 1, 53, ByteArray(20))
        val moreFragments = packet.copyOf().also { it[6] = 0x20 }
        assertSame(ParsedPacket.Unsupported, IpPackets.parse(moreFragments))
        val offset = packet.copyOf().also { it[6] = 0; it[7] = 5 }
        assertSame(ParsedPacket.Unsupported, IpPackets.parse(offset))
        assertSame(ParsedPacket.Unsupported, IpPackets.parse(packet, 25))
        assertSame(ParsedPacket.Unsupported, IpPackets.parse(ByteArray(0), 0))
        assertSame(ParsedPacket.Unsupported, IpPackets.parse(byteArrayOf(0x75, 0, 0), 3))
    }

    @Test
    fun `en-tete d'extension IPv6 saute`() {
        val plain = IpPackets.buildUdp(6, client6, dns6, 1000, 53, byteArrayOf(7, 8, 9))
        // Insère un en-tête hop-by-hop de 8 octets entre l'en-tête IPv6 et l'UDP.
        val withExtension = ByteArray(plain.size + 8)
        plain.copyInto(withExtension, 0, 0, 40)
        withExtension[6] = 0 // next header = hop-by-hop
        withExtension[40] = 17 // puis UDP
        withExtension[41] = 0 // longueur (0 + 1) * 8
        plain.copyInto(withExtension, 48, 40)
        val payloadLength = plain.u16At(4) + 8
        withExtension[4] = (payloadLength ushr 8).toByte()
        withExtension[5] = payloadLength.toByte()
        val udp = assertIs<ParsedPacket.Udp>(IpPackets.parse(withExtension)).packet
        assertContentEquals(byteArrayOf(7, 8, 9), udp.payload)
    }

    @Test
    fun `RST en reponse a un SYN`() {
        val syn = tcpSegment(flags = IpPackets.TCP_SYN, seq = 1000, ack = 0)
        val segment = assertIs<ParsedPacket.Tcp>(IpPackets.parse(syn)).segment
        val rst = assertNotNull(IpPackets.tcpReset(segment))

        val reply = assertIs<ParsedPacket.Tcp>(IpPackets.parse(rst)).segment
        assertContentEquals(dns4, reply.sourceAddress)
        assertContentEquals(client4, reply.destinationAddress)
        assertEquals(53, reply.sourcePort)
        assertEquals(40001, reply.destinationPort)
        assertEquals(IpPackets.TCP_RST or IpPackets.TCP_ACK, reply.flags)
        assertEquals(0, reply.sequenceNumber)
        assertEquals(1001, reply.acknowledgmentNumber, "ACK = SEQ + 1 pour le SYN")
        assertEquals(0, tcpChecksumResidue(rst, 20, dns4, client4))
    }

    @Test
    fun `RST a un segment portant un ACK reprend son numero`() {
        val segment = assertIs<ParsedPacket.Tcp>(IpPackets.parse(tcpSegment(IpPackets.TCP_ACK, 5, 0xFFFF_FFF0L))).segment
        val reply = assertIs<ParsedPacket.Tcp>(IpPackets.parse(IpPackets.tcpReset(segment)!!)).segment
        assertEquals(IpPackets.TCP_RST, reply.flags)
        assertEquals(0xFFFF_FFF0L, reply.sequenceNumber)
    }

    @Test
    fun `jamais de RST en reponse a un RST`() {
        val segment = assertIs<ParsedPacket.Tcp>(IpPackets.parse(tcpSegment(IpPackets.TCP_RST, 1, 0))).segment
        assertNull(IpPackets.tcpReset(segment))
    }

    /** Segment TCP IPv4 minimal du client vers le serveur DNS virtuel (port 53). */
    private fun tcpSegment(flags: Int, seq: Long, ack: Long): ByteArray {
        val p = ByteArray(40)
        p[0] = 0x45
        p[3] = 40
        p[8] = 64
        p[9] = 6
        client4.copyInto(p, 12)
        dns4.copyInto(p, 16)
        p[20] = (40001 ushr 8).toByte(); p[21] = 40001.toByte()
        p[23] = 53
        for (i in 0 until 4) p[24 + i] = (seq ushr (24 - 8 * i)).toByte()
        for (i in 0 until 4) p[28 + i] = (ack ushr (24 - 8 * i)).toByte()
        p[32] = 0x50
        p[33] = flags.toByte()
        return p
    }

    /** Somme sur pseudo-en-tête + segment : 0 si la somme de contrôle écrite est correcte. */
    private fun udpChecksumResidue(packet: ByteArray, offset: Int, src: ByteArray, dst: ByteArray): Int =
        transportResidue(packet, offset, src, dst, 17)

    private fun tcpChecksumResidue(packet: ByteArray, offset: Int, src: ByteArray, dst: ByteArray): Int =
        transportResidue(packet, offset, src, dst, 6)

    private fun transportResidue(packet: ByteArray, offset: Int, src: ByteArray, dst: ByteArray, protocol: Int): Int {
        val length = packet.size - offset
        var sum = InternetChecksum.sum(src) + InternetChecksum.sum(dst) + protocol + length
        sum = InternetChecksum.sum(packet, offset, length, sum)
        return InternetChecksum.finish(sum)
    }
}
