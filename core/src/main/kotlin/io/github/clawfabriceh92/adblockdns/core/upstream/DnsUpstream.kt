package io.github.clawfabriceh92.adblockdns.core.upstream

import io.github.clawfabriceh92.adblockdns.core.dns.DnsMessages
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Résolveur auquel sont transmises les requêtes autorisées. */
interface DnsUpstream {
    /** Envoie la requête DNS brute [query] et renvoie la réponse brute (même identifiant). */
    @Throws(IOException::class)
    fun resolve(query: ByteArray): ByteArray
}

/**
 * Permet au service VPN de sortir ses propres sockets du tunnel (VpnService.protect). Sur la
 * JVM de test, rien à faire.
 */
fun interface SocketProtector {
    fun protect(socket: DatagramSocket)

    companion object {
        val NONE = SocketProtector { }
    }
}

/**
 * Résolveur DNS classique (UDP port 53) : les serveurs du réseau actif, relus à chaque requête
 * car ils changent en passant du Wi-Fi au réseau mobile. Essaie les serveurs dans l'ordre ; si la
 * réponse est tronquée (bit TC), refait la requête en TCP pour renvoyer la réponse complète.
 */
class UdpUpstream(
    private val servers: () -> List<InetAddress>,
    private val protector: SocketProtector = SocketProtector.NONE,
    private val timeoutMs: Int = 2_500,
    private val port: Int = 53,
    private val maxServersTried: Int = 3,
) : DnsUpstream {

    override fun resolve(query: ByteArray): ByteArray {
        val candidates = servers().take(maxServersTried)
        if (candidates.isEmpty()) throw IOException("Aucun serveur DNS amont connu")
        var lastError: IOException? = null
        for (server in candidates) {
            try {
                return resolveWith(server, query)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Échec de la résolution")
    }

    private fun resolveWith(server: InetAddress, query: ByteArray): ByteArray {
        DatagramSocket().use { socket ->
            protector.protect(socket)
            socket.connect(server, port)
            socket.soTimeout = timeoutMs
            socket.send(DatagramPacket(query, query.size))
            val buffer = ByteArray(65_535)
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (true) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val response = buffer.copyOf(packet.length)
                if (isAnswerTo(query, response)) {
                    return if (DnsMessages.isTruncated(response)) resolveTcp(server, query) ?: response else response
                }
                // Datagramme inattendu (ancienne réponse, usurpation) : on continue d'attendre.
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) throw SocketTimeoutException("Pas de réponse de $server")
                socket.soTimeout = remainingMs.toInt()
            }
        }
    }

    /** DNS sur TCP (RFC 1035 §4.2.2 : longueur sur 2 octets puis message). Null en cas d'échec. */
    private fun resolveTcp(server: InetAddress, query: ByteArray): ByteArray? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(server, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val out = DataOutputStream(socket.getOutputStream().buffered())
            out.writeShort(query.size)
            out.write(query)
            out.flush()
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val response = ByteArray(input.readUnsignedShort())
            input.readFully(response)
            response.takeIf { isAnswerTo(query, it) }
        }
    } catch (e: IOException) {
        null // on renvoie alors la réponse tronquée : le client décidera
    }

    private fun isAnswerTo(query: ByteArray, response: ByteArray): Boolean =
        response.size >= DnsMessages.HEADER_SIZE &&
            DnsMessages.isResponse(response) &&
            DnsMessages.id(response) == DnsMessages.id(query)
}

/**
 * DNS-over-HTTPS (RFC 8484, méthode POST, type application/dns-message) avec OkHttp, qui
 * négocie HTTP/2 : Quad9 a retiré le HTTP/1.1 de son service DoH le 15/12/2025 (billet
 * « DOH HTTP/1.1 Retirement », quad9.net). L'identifiant est mis à 0 dans la requête envoyée,
 * comme le recommande la RFC 8484 §4.1 pour faciliter la mise en cache, puis restauré.
 */
class DohUpstream(
    private val endpoint: String,
    private val client: OkHttpClient = sharedClient,
) : DnsUpstream {

    override fun resolve(query: ByteArray): ByteArray {
        if (query.size < DnsMessages.HEADER_SIZE) throw IOException("Requête DNS trop courte")
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", MEDIA_TYPE)
            .post(DnsMessages.withId(query, 0).toRequestBody(DNS_MESSAGE))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("DNS-over-HTTPS : HTTP ${response.code}")
            val body = response.body
            if (body.contentLength() > MAX_RESPONSE) throw IOException("DNS-over-HTTPS : réponse trop grande")
            val bytes = body.bytes()
            if (bytes.size > MAX_RESPONSE || bytes.size < DnsMessages.HEADER_SIZE || !DnsMessages.isResponse(bytes)) {
                throw IOException("DNS-over-HTTPS : réponse invalide")
            }
            return DnsMessages.withId(bytes, DnsMessages.id(query))
        }
    }

    companion object {
        const val MEDIA_TYPE = "application/dns-message"
        private val DNS_MESSAGE = MEDIA_TYPE.toMediaType()
        private const val MAX_RESPONSE = 65_535

        /** Documenté par Cloudflare : developers.cloudflare.com/1.1.1.1/encryption/dns-over-https/ */
        const val CLOUDFLARE = "https://cloudflare-dns.com/dns-query"

        /** Documenté par Quad9 : quad9.net (service DoH sur dns.quad9.net, HTTP/2 uniquement). */
        const val QUAD9 = "https://dns.quad9.net/dns-query"

        /** Client partagé : connexions HTTP/2 réutilisées d'une requête et d'un résolveur à l'autre. */
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    }
}
