package io.github.clawfabriceh92.adblockdns.vpn

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import io.github.clawfabriceh92.adblockdns.core.dns.DnsMessages
import io.github.clawfabriceh92.adblockdns.core.upstream.DnsUpstream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Transmet les requêtes autorisées au résolveur d'Android (DnsResolver.rawQuery, API 29) sur le
 * réseau physique. Avantages : le DNS privé (DNS-over-TLS) du système est respecté, ainsi que son
 * cache, ses délais et son repli en TCP. La documentation de LinkProperties demande de ne pas
 * envoyer de DNS en clair quand le DNS privé est actif : c'est ce que garantit ce passage.
 */
class SystemResolverUpstream(
    private val network: () -> Network?,
    private val timeoutMs: Long = 6_000,
) : DnsUpstream {
    private val resolver = DnsResolver.getInstance()
    private val direct = Executor { it.run() }

    override fun resolve(query: ByteArray): ByteArray {
        val done = CountDownLatch(1)
        val answerRef = AtomicReference<ByteArray?>()
        val failureRef = AtomicReference<Throwable?>()
        val cancel = CancellationSignal()
        resolver.rawQuery(
            network(),
            query,
            DnsResolver.FLAG_EMPTY,
            direct,
            cancel,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    answerRef.set(answer)
                    done.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    failureRef.set(error)
                    done.countDown()
                }
            },
        )
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            cancel.cancel()
            throw SocketTimeoutException("Résolveur du système : délai dépassé")
        }
        failureRef.get()?.let { throw IOException("Résolveur du système : ${it.message}", it) }
        val bytes = answerRef.get() ?: throw IOException("Résolveur du système : réponse vide")
        if (bytes.size < DnsMessages.HEADER_SIZE) throw IOException("Résolveur du système : réponse trop courte")
        // La réponse peut venir du cache du système : on lui redonne l'identifiant de la requête.
        return DnsMessages.withId(bytes, DnsMessages.id(query))
    }
}
