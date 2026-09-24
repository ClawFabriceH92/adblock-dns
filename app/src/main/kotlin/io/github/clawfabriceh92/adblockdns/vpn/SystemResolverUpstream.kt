package io.github.clawfabriceh92.adblockdns.vpn

import android.content.Context
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.os.HandlerThread
import androidx.annotation.RequiresApi
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
 *
 * Sur API 37+, on utilise le constructeur DnsResolver(Context, Looper) (getInstance() y est
 * déprécié) avec un thread dédié à la surveillance des réponses, plutôt que le thread principal.
 */
class SystemResolverUpstream(
    context: Context,
    private val network: () -> Network?,
    private val timeoutMs: Long = 6_000,
) : DnsUpstream, AutoCloseable {
    private val repliesThread: HandlerThread? =
        if (Build.VERSION.SDK_INT >= 37) HandlerThread("resolveur-dns").apply { start() } else null
    private val resolver: DnsResolver =
        if (Build.VERSION.SDK_INT >= 37) modernResolver(context) else legacyResolver()
    private val direct = Executor { it.run() }

    @RequiresApi(37)
    private fun modernResolver(context: Context): DnsResolver =
        DnsResolver(context.applicationContext, checkNotNull(repliesThread).looper)

    @Suppress("DEPRECATION") // seul moyen avant l'API 37
    private fun legacyResolver(): DnsResolver = DnsResolver.getInstance()

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

    override fun close() {
        repliesThread?.quitSafely()
    }
}
