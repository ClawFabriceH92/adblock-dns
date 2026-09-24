package io.github.clawfabriceh92.adblockdns.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import io.github.clawfabriceh92.adblockdns.core.engine.DnsPacketProcessor
import java.io.FileDescriptor
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Boucle de lecture du tunnel. Aucune attente active : le thread dort dans poll(2) jusqu'à
 * l'arrivée d'un paquet ou d'un signal d'arrêt (tube dédié). Chaque requête est traitée par un
 * petit groupe de threads pour qu'une résolution lente n'en retarde pas d'autres.
 */
class TunnelWorker(
    private val tunnel: ParcelFileDescriptor,
    private val processor: DnsPacketProcessor,
    private val onFailure: (Throwable) -> Unit,
) {
    @Volatile private var running = true
    private val wakeup: Array<FileDescriptor> = Os.pipe()
    private val writeLock = Any()
    private val workers = ThreadPoolExecutor(
        WORKERS,
        WORKERS,
        30,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "dns-worker").apply { isDaemon = true } },
        // File pleine (résolveur amont bloqué) : on abandonne la plus ancienne, le client réessaiera.
        ThreadPoolExecutor.DiscardOldestPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }
    private val reader = Thread(::readLoop, "tunnel-reader")

    fun start() = reader.start()

    private fun readLoop() {
        val buffer = ByteArray(MAX_PACKET)
        val tunnelFd = tunnel.fileDescriptor
        val tunnelPoll = StructPollfd().apply {
            fd = tunnelFd
            events = OsConstants.POLLIN.toShort()
        }
        val wakeupPoll = StructPollfd().apply {
            fd = wakeup[0]
            events = OsConstants.POLLIN.toShort()
        }
        val polls = arrayOf(tunnelPoll, wakeupPoll)
        try {
            while (running) {
                tunnelPoll.revents = 0
                wakeupPoll.revents = 0
                try {
                    Os.poll(polls, -1)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw e
                }
                if (!running || wakeupPoll.revents.toInt() != 0) break
                val events = tunnelPoll.revents.toInt()
                if (events and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) {
                    throw IOException("L'interface du tunnel a été fermée")
                }
                if (events and OsConstants.POLLIN == 0) continue
                val length = try {
                    Os.read(tunnelFd, buffer, 0, buffer.size)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                    throw e
                }
                if (length <= 0) continue
                val packet = buffer.copyOf(length)
                workers.execute { handle(packet) }
            }
        } catch (t: Throwable) {
            if (running) {
                Log.e(TAG, "Arrêt inattendu de la lecture du tunnel", t)
                onFailure(t)
            }
        } finally {
            workers.shutdownNow()
        }
    }

    private fun handle(packet: ByteArray) {
        val reply = try {
            processor.process(packet, packet.size)
        } catch (t: Throwable) {
            Log.w(TAG, "Paquet ignoré après une erreur de traitement", t)
            null
        } ?: return
        if (!running) return
        try {
            synchronized(writeLock) { Os.write(tunnel.fileDescriptor, reply, 0, reply.size) }
        } catch (e: ErrnoException) {
            if (running) Log.w(TAG, "Réponse non écrite dans le tunnel : ${e.message}")
        } catch (e: IOException) {
            if (running) Log.w(TAG, "Réponse non écrite dans le tunnel : ${e.message}")
        }
    }

    /** Arrête la lecture, attend la fin du thread et ferme le tunnel. */
    fun stop() {
        running = false
        try {
            Os.write(wakeup[1], byteArrayOf(1), 0, 1)
        } catch (e: ErrnoException) {
            Log.w(TAG, "Signal d'arrêt non envoyé", e)
        } catch (e: IOException) {
            Log.w(TAG, "Signal d'arrêt non envoyé", e)
        }
        if (Thread.currentThread() != reader) reader.join(JOIN_TIMEOUT_MS)
        workers.shutdownNow()
        for (fd in wakeup) {
            try {
                Os.close(fd)
            } catch (e: ErrnoException) {
                // déjà fermé
            }
        }
        try {
            tunnel.close()
        } catch (e: IOException) {
            // déjà fermé
        }
    }

    private companion object {
        const val TAG = "Tunnel"
        const val MAX_PACKET = 32_767
        const val WORKERS = 16
        const val QUEUE_CAPACITY = 512
        const val JOIN_TIMEOUT_MS = 2_000L
    }
}
