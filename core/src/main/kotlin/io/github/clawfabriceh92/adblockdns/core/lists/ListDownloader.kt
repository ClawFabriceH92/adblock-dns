package io.github.clawfabriceh92.adblockdns.core.lists

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

sealed interface DownloadResult {
    /** Le serveur confirme que la liste n'a pas changé (HTTP 304). */
    data object NotModified : DownloadResult

    class Updated(val list: CompiledList, val etag: String?, val lastModified: String?) : DownloadResult
}

/**
 * Télécharge et compile une liste publique. Requête conditionnelle (ETag / Last-Modified) :
 * une liste inchangée ne coûte que quelques octets. Une réponse sans aucun domaine valide
 * (page d'erreur, portail captif…) est refusée pour ne jamais remplacer une bonne liste.
 */
class ListDownloader(
    private val userAgent: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
    private val maxDownloadBytes: Long = 100L * 1024 * 1024,
    private val maxDecompressedBytes: Long = 400L * 1024 * 1024,
) {
    fun download(url: String, etag: String? = null, lastModified: String? = null): DownloadResult {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            // Gzip demandé explicitement et décodé ici : même comportement sur JVM et Android.
            connection.setRequestProperty("Accept-Encoding", "gzip")
            if (etag != null) connection.setRequestProperty("If-None-Match", etag)
            if (lastModified != null) connection.setRequestProperty("If-Modified-Since", lastModified)
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> DownloadResult.NotModified
                HttpURLConnection.HTTP_OK -> {
                    var stream: InputStream = LimitedInputStream(connection.inputStream, maxDownloadBytes)
                    if ("gzip".equals(connection.contentEncoding, ignoreCase = true)) {
                        stream = LimitedInputStream(GZIPInputStream(stream), maxDecompressedBytes)
                    }
                    val compiled = stream.use { BlocklistCompiler.compile(it) }
                    if (compiled.domains.size == 0) {
                        throw IOException("Aucun domaine valide dans la réponse : liste refusée")
                    }
                    DownloadResult.Updated(
                        list = compiled,
                        etag = connection.getHeaderField("ETag"),
                        lastModified = connection.getHeaderField("Last-Modified"),
                    )
                }
                else -> throw IOException("Téléchargement refusé : HTTP $code")
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** Flux qui échoue au-delà de [limit] octets : protège contre une réponse démesurée. */
internal class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) check(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) check(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        check(skipped)
        return skipped
    }

    private fun check(n: Long) {
        count += n
        if (count > limit) throw IOException("Réponse trop volumineuse (plus de $limit octets)")
    }
}
