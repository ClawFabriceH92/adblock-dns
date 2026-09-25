package io.github.clawfabriceh92.adblockdns.core.lists

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BlocklistParserTest {

    private fun parse(text: String): Pair<List<String>, ParseResult> {
        val domains = ArrayList<String>()
        val result = BlocklistParser.parse(text.reader().buffered()) { domains += it }
        return domains to result
    }

    @Test
    fun `format Wildcard Domains de hagezi avec son en-tete`() {
        // Extrait de l'en-tête réel de wildcard/pro-onlydomains.txt (23/09/2026).
        val (domains, result) = parse(
            """
            # Title: HaGeZi's Multi PRO - extended protection (recommended)
            # Last modified: 23 Sep 2026 15:17 UTC
            # Version: 2026.0923.1517.16
            # Syntax: Domains (without subdomains)
            # Number of entries: 225658
            #
            cdn.007moms.com
            my.007moms.com
            """.trimIndent(),
        )
        assertEquals(listOf("cdn.007moms.com", "my.007moms.com"), domains)
        assertEquals(0, result.rejected)
        assertEquals("HaGeZi's Multi PRO - extended protection (recommended)", result.header.title)
        assertEquals("2026.0923.1517.16", result.header.version)
        assertEquals("23 Sep 2026 15:17 UTC", result.header.lastModified)
        assertEquals(225658, result.header.declaredEntries)
    }

    @Test
    fun `fichier hosts de StevenBlack sans les entrees locales`() {
        val (domains, result) = parse(
            """
            # Date: 23 September 2026 20:06:31 (UTC)
            # Number of unique domains: 76,510
            127.0.0.1 localhost
            127.0.0.1 localhost.localdomain
            127.0.0.1 local
            255.255.255.255 broadcasthost
            ::1 localhost
            fe80::1%lo0 localhost
            0.0.0.0 0.0.0.0
            0.0.0.0 ad-assets.futurecdn.net
            0.0.0.0 ck.getcookiestxt.com eu1.clevertap-prod.com # deux noms sur une ligne
            """.trimIndent(),
        )
        assertEquals(listOf("ad-assets.futurecdn.net", "ck.getcookiestxt.com", "eu1.clevertap-prod.com"), domains)
        assertEquals(7, result.rejected)
        assertEquals(76510, result.header.declaredEntries)
        assertEquals("23 September 2026 20:06:31 (UTC)", result.header.lastModified)
    }

    @Test
    fun `syntaxe adblock limitee au DNS`() {
        val (domains, result) = parse(
            """
            [Adblock Plus]
            ! Title: Test
            ||ads.example.com^
            ||tracker.example.org^${'$'}important
            ||ok.example.net^${'$'}third-party
            @@||allowed.example.com^
            example.com##.banner
            /ads/*
            """.trimIndent(),
        )
        assertEquals(listOf("ads.example.com", "tracker.example.org"), domains)
        assertEquals(4, result.rejected)
        assertEquals("Test", result.header.title)
    }

    @Test
    fun `jokers en tete et noms invalides`() {
        val (domains, _) = parse("*.ads.example.com\n.cdn.example.com\nINVALID_ÉTÉ.com\nsingle\n192.168.0.1\n")
        assertEquals(listOf("ads.example.com", "cdn.example.com"), domains)
    }

    @Test
    fun `compilation en ensemble d'empreintes`() {
        val list = BlocklistCompiler.compile("a.example\nb.example\na.example\n".byteInputStream())
        assertEquals(2, list.domains.size)
        assertEquals(3, list.parse.accepted)
        assertEquals("a.example", list.domains.findMatchingSuffix("x.a.example"))
    }

    @Test
    fun `garde-fou sur le nombre d'entrees`() {
        val text = (1..100).joinToString("\n") { "d$it.example" }
        assertFailsWith<IOException> { BlocklistCompiler.compile(text.byteInputStream(), maxEntries = 50) }
    }
}

class ListDownloaderTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val downloader = ListDownloader(userAgent = "test")
    private var lastIfNoneMatch: String? = null

    init {
        server.createContext("/list.txt") { exchange ->
            lastIfNoneMatch = exchange.requestHeaders.getFirst("If-None-Match")
            if (lastIfNoneMatch == "\"v1\"") {
                exchange.sendResponseHeaders(304, -1)
            } else {
                val body = "# Title: Liste de test\nads.example.com\ntracker.example.org\n".toByteArray()
                exchange.responseHeaders.add("ETag", "\"v1\"")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.createContext("/gzip.txt") { exchange ->
            val raw = ByteArrayOutputStream()
            GZIPOutputStream(raw).use { it.write("ads.example.com\n".toByteArray()) }
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, raw.size().toLong())
            exchange.responseBody.use { it.write(raw.toByteArray()) }
        }
        server.createContext("/portail.html") { exchange ->
            val body = "<html><body>Connectez-vous au Wi-Fi</body></html>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/absent") { exchange -> exchange.sendResponseHeaders(404, -1) }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `premier telechargement puis 304`() {
        val first = assertIs<DownloadResult.Updated>(downloader.download("$base/list.txt"))
        assertEquals(2, first.list.domains.size)
        assertEquals("\"v1\"", first.etag)
        assertEquals("Liste de test", first.list.parse.header.title)
        assertNull(lastIfNoneMatch)

        assertSame(DownloadResult.NotModified, downloader.download("$base/list.txt", etag = first.etag))
        assertEquals("\"v1\"", lastIfNoneMatch)
    }

    @Test
    fun `reponse gzip decodee`() {
        val result = assertIs<DownloadResult.Updated>(downloader.download("$base/gzip.txt"))
        assertNotNull(result.list.domains.findMatchingSuffix("ads.example.com"))
    }

    @Test
    fun `page de portail captif refusee`() {
        val error = assertFailsWith<IOException> { downloader.download("$base/portail.html") }
        assertTrue(error.message!!.contains("Aucun domaine"))
    }

    @Test
    fun `erreur HTTP remontee`() {
        assertFailsWith<IOException> { downloader.download("$base/absent") }
    }

    @Test
    fun `reponse trop volumineuse refusee`() {
        val small = ListDownloader(userAgent = "test", maxDownloadBytes = 10)
        assertFailsWith<IOException> { small.download("$base/list.txt") }
    }
}
