package io.github.clawfabriceh92.adblockdns.core.upstream

import com.sun.net.httpserver.HttpServer
import io.github.clawfabriceh92.adblockdns.core.TestDns
import io.github.clawfabriceh92.adblockdns.core.dns.DnsMessages
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Faux serveur DNS UDP + TCP sur 127.0.0.1, même port pour les deux protocoles. */
private class FakeDnsServer(private val answer: (ByteArray, tcp: Boolean) -> List<ByteArray>) : AutoCloseable {
    val tcp = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val udp = DatagramSocket(tcp.localPort, InetAddress.getByName("127.0.0.1"))
    val port get() = tcp.localPort
    @Volatile var tcpQueries = 0

    init {
        thread(isDaemon = true) {
            val buffer = ByteArray(4096)
            while (!udp.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udp.receive(packet)
                } catch (e: IOException) {
                    break
                }
                for (reply in answer(buffer.copyOf(packet.length), false)) {
                    udp.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                }
            }
        }
        thread(isDaemon = true) {
            while (!tcp.isClosed) {
                val client = try { tcp.accept() } catch (e: IOException) { break }
                client.use {
                    tcpQueries++
                    val input = DataInputStream(it.getInputStream())
                    val query = ByteArray(input.readUnsignedShort()).also { q -> input.readFully(q) }
                    val out = DataOutputStream(it.getOutputStream())
                    val reply = answer(query, true).first()
                    out.writeShort(reply.size)
                    out.write(reply)
                    out.flush()
                }
            }
        }
    }

    override fun close() {
        udp.close()
        tcp.close()
    }
}

class UdpUpstreamTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val servers = ArrayList<FakeDnsServer>()

    private fun server(answer: (ByteArray, Boolean) -> List<ByteArray>) = FakeDnsServer(answer).also { servers += it }

    @AfterTest
    fun close() = servers.forEach { it.close() }

    @Test
    fun `reponse relayee`() {
        val s = server { q, _ -> listOf(TestDns.response(q, listOf(TestDns.a(203, 0, 113, 7)))) }
        val query = TestDns.query("example.com", id = 777)
        val response = UdpUpstream({ listOf(loopback) }, port = s.port).resolve(query)
        assertEquals(777, DnsMessages.id(response))
        assertTrue(DnsMessages.isResponse(response))
    }

    @Test
    fun `datagramme a l'identifiant inattendu ignore`() {
        val s = server { q, _ ->
            val good = TestDns.response(q, listOf(TestDns.a(1, 2, 3, 4)))
            listOf(DnsMessages.withId(good, DnsMessages.id(q) xor 1), good)
        }
        val query = TestDns.query("example.com", id = 1000)
        assertEquals(1000, DnsMessages.id(UdpUpstream({ listOf(loopback) }, port = s.port).resolve(query)))
    }

    @Test
    fun `reponse tronquee refaite en TCP`() {
        val s = server { q, tcp ->
            if (tcp) listOf(TestDns.response(q, List(40) { TestDns.a(10, 0, 0, it) }))
            else listOf(TestDns.response(q, emptyList(), flags = 0x8380)) // TC=1
        }
        val response = UdpUpstream({ listOf(loopback) }, port = s.port).resolve(TestDns.query("big.example"))
        assertFalse(DnsMessages.isTruncated(response))
        assertEquals(40, (response[6].toInt() shl 8) or (response[7].toInt() and 0xFF))
        assertEquals(1, s.tcpQueries)
    }

    @Test
    fun `serveur suivant essaye si le premier ne repond pas`() {
        val s = server { q, _ -> listOf(TestDns.response(q, listOf(TestDns.a(1, 1, 1, 1)))) }
        // Rien n'écoute sur 127.0.0.2 : le système renvoie « port inaccessible » ou le délai expire.
        val upstream = UdpUpstream({ listOf(InetAddress.getByName("127.0.0.2"), loopback) }, port = s.port, timeoutMs = 500)
        assertTrue(DnsMessages.isResponse(upstream.resolve(TestDns.query("example.com"))))
    }

    @Test
    fun `aucun serveur connu`() {
        assertFailsWith<IOException> { UdpUpstream({ emptyList() }).resolve(TestDns.query("example.com")) }
    }
}

class DohUpstreamTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val url get() = "http://127.0.0.1:${server.address.port}/dns-query"
    @Volatile private var receivedId = -1
    @Volatile private var receivedType: String? = null

    init {
        server.createContext("/dns-query") { exchange ->
            receivedType = exchange.requestHeaders.getFirst("Content-Type")
            val query = exchange.requestBody.readBytes()
            receivedId = DnsMessages.id(query)
            val response = TestDns.response(query, listOf(TestDns.a(9, 9, 9, 9)))
            exchange.responseHeaders.add("Content-Type", DohUpstream.MEDIA_TYPE)
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.createContext("/panne") { exchange -> exchange.sendResponseHeaders(500, -1) }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `requete POST avec identifiant nul puis identifiant restaure`() {
        val query = TestDns.query("example.com", id = 0x5151)
        val response = DohUpstream(url).resolve(query)
        assertEquals(0, receivedId, "RFC 8484 §4.1 : identifiant 0")
        assertEquals(DohUpstream.MEDIA_TYPE, receivedType)
        assertEquals(0x5151, DnsMessages.id(response))
        assertContentEquals(query.copyOfRange(12, query.size), response.copyOfRange(12, query.size))
    }

    @Test
    fun `erreur HTTP remontee`() {
        assertFailsWith<IOException> {
            DohUpstream(url.replace("/dns-query", "/panne")).resolve(TestDns.query("example.com"))
        }
    }
}
