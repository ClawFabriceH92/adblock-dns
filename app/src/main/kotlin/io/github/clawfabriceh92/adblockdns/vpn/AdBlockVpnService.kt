package io.github.clawfabriceh92.adblockdns.vpn

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.core.app.ServiceCompat
import io.github.clawfabriceh92.adblockdns.AppContainer
import io.github.clawfabriceh92.adblockdns.BuildConfig
import io.github.clawfabriceh92.adblockdns.R
import io.github.clawfabriceh92.adblockdns.appContainer
import io.github.clawfabriceh92.adblockdns.core.engine.BlockedQuery
import io.github.clawfabriceh92.adblockdns.core.engine.DnsPacketProcessor
import io.github.clawfabriceh92.adblockdns.core.filter.BlockSource
import io.github.clawfabriceh92.adblockdns.core.net.TunnelAddresses
import io.github.clawfabriceh92.adblockdns.core.packet.UdpPacket
import io.github.clawfabriceh92.adblockdns.core.upstream.DnsUpstream
import io.github.clawfabriceh92.adblockdns.core.upstream.DohUpstream
import io.github.clawfabriceh92.adblockdns.core.upstream.SocketProtector
import io.github.clawfabriceh92.adblockdns.core.upstream.UdpUpstream
import io.github.clawfabriceh92.adblockdns.data.Settings
import io.github.clawfabriceh92.adblockdns.data.UpstreamChoice
import io.github.clawfabriceh92.adblockdns.data.db.BlockedEventEntity
import io.github.clawfabriceh92.adblockdns.notification.Notifications
import io.github.clawfabriceh92.adblockdns.ui.MainActivity
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Service VPN local. Le tunnel ne route qu'une seule adresse, celle d'un serveur DNS virtuel :
 * les applications y envoient leurs requêtes DNS, tout le reste du trafic passe normalement par
 * le réseau, hors tunnel. L'application s'exclut elle-même du tunnel pour que ses propres
 * requêtes (résolution amont, téléchargement des listes) sortent directement.
 */
class AdBlockVpnService : VpnService() {

    private val container: AppContainer by lazy { appContainer }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private lateinit var networkMonitor: NetworkMonitor
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    @Volatile private var session: Session? = null
    private var watchers: List<Job> = emptyList()

    @Volatile private var settings = Settings()
    @Volatile private var upstream: DnsUpstream? = null

    private class Session(val worker: TunnelWorker, val addresses: TunnelAddresses, val excludedApps: Set<String>)

    override fun onCreate() {
        super.onCreate()
        networkMonitor = NetworkMonitor(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { stopProtection(userRequested = true) }
            return START_NOT_STICKY
        }
        // ACTION_START, VpnService.SERVICE_INTERFACE (VPN permanent d'Android) ou relance
        // automatique (intent null) : Android exige startForeground() dans les secondes qui suivent.
        startInForeground()
        scope.launch { startProtection() }
        return START_STICKY
    }

    private fun startInForeground() {
        val notification = Notifications.protection(this, blockedToday = null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifications.ID_PROTECTION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifications.ID_PROTECTION, notification)
        }
    }

    private suspend fun startProtection() = mutex.withLock {
        if (session != null) return@withLock
        container.protectionStatus.value = ProtectionStatus.Starting
        if (prepare(this) != null) {
            fail("Autorisation VPN absente : ouvrez l'application et activez la protection.")
            return@withLock
        }
        settings = container.settings.current()
        // Premier lancement : on attend la liste embarquée (quelques secondes au plus) pour ne pas
        // annoncer « protection active » avec un filtre vide.
        container.filterEngine.awaitReady(ENGINE_WAIT_MS)
        container.appResolver.invalidate()
        container.journal.purgeOlderThanRetention()
        networkMonitor.start()
        upstream = createUpstream(settings.upstream)

        val addresses = TunnelAddresses.choose(localIpAddresses())
        val worker = try {
            launchTunnel(addresses, settings.excludedApps)
        } catch (e: Exception) {
            Log.e(TAG, "Création du tunnel impossible", e)
            null
        }
        if (worker == null) {
            fail("Android a refusé de créer le tunnel VPN.")
            return@withLock
        }
        session = Session(worker, addresses, settings.excludedApps)
        container.settings.setProtectionWanted(true)
        container.protectionStatus.value = ProtectionStatus.Running(
            since = System.currentTimeMillis(),
            dnsServer = addresses.dnsServer,
            upstream = settings.upstream.label,
        )
        watchers = listOf(watchSettings(), watchNotification())
    }

    /** Établit l'interface VPN et démarre sa boucle de lecture ; null si Android refuse. */
    private fun launchTunnel(addresses: TunnelAddresses, excludedApps: Set<String>): TunnelWorker? {
        val tunnel = buildInterface(addresses, excludedApps) ?: return null
        val processor = DnsPacketProcessor(
            engine = { container.filterEngine.engine },
            upstream = { upstream ?: throw IOException("Résolveur amont non prêt") },
            listener = journalListener,
            cnameInspection = { settings.cnameInspection },
        )
        return TunnelWorker(tunnel, processor) { error ->
            scope.launch { stopProtection(userRequested = false, failure = "Le tunnel s'est arrêté : ${error.message}") }
        }.also { it.start() }
    }

    private fun buildInterface(addresses: TunnelAddresses, excludedApps: Set<String>): ParcelFileDescriptor? {
        val configureIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(addresses.interfaceAddress, addresses.prefixLength)
            // Seul le serveur DNS virtuel est routé dans le tunnel.
            .addRoute(addresses.dnsServer, 32)
            .addDnsServer(addresses.dnsServer)
            .setMtu(MTU)
            // Par défaut un VPN est considéré comme « facturé à l'usage » (doc de setMetered) :
            // on hérite plutôt du réseau réel pour ne pas freiner les autres applications.
            .setMetered(false)
            .setConfigureIntent(configureIntent)
        builder.addDisallowedApplication(packageName)
        for (app in excludedApps) {
            try {
                builder.addDisallowedApplication(app)
            } catch (e: PackageManager.NameNotFoundException) {
                // application désinstallée depuis : ignorée
            }
        }
        return builder.establish()
    }

    /** Applique les réglages à chaud : résolveur amont, CNAME, applications exclues. */
    private fun watchSettings(): Job = scope.launch {
        container.settings.settings.distinctUntilChanged().collect { next ->
            val previous = settings
            settings = next
            if (next.upstream != previous.upstream) {
                upstream = createUpstream(next.upstream)
                (container.protectionStatus.value as? ProtectionStatus.Running)?.let {
                    container.protectionStatus.value = it.copy(upstream = next.upstream.label)
                }
            }
            if (next.excludedApps != session?.excludedApps) reconfigure(next.excludedApps)
        }
    }

    /** Recrée l'interface avec la nouvelle liste d'applications exclues, sans arrêter le service. */
    private suspend fun reconfigure(excludedApps: Set<String>) = mutex.withLock {
        val current = session ?: return@withLock
        val replacement = try {
            launchTunnel(current.addresses, excludedApps)
        } catch (e: Exception) {
            Log.e(TAG, "Reconfiguration du tunnel impossible", e)
            null
        } ?: return@withLock
        current.worker.stop()
        session = Session(replacement, current.addresses, excludedApps)
    }

    /** Compteur du jour dans la notification, rafraîchi au plus toutes les 15 secondes. */
    private fun watchNotification(): Job = scope.launch {
        val manager = getSystemService(NotificationManager::class.java)
        container.journal.blockedToday().distinctUntilChanged().conflate().collect { count ->
            manager.notify(Notifications.ID_PROTECTION, Notifications.protection(this@AdBlockVpnService, count))
            delay(NOTIFICATION_REFRESH_MS)
        }
    }

    private fun createUpstream(choice: UpstreamChoice): DnsUpstream = when (choice) {
        UpstreamChoice.SYSTEM -> SystemUpstreamWithFallback()
        UpstreamChoice.CLOUDFLARE -> DohUpstream(DohUpstream.CLOUDFLARE)
        UpstreamChoice.QUAD9 -> DohUpstream(DohUpstream.QUAD9)
    }

    /**
     * Résolveur du système ; en cas d'échec, envoi direct en UDP aux serveurs du réseau, sauf si le
     * DNS privé est actif (il ne faut alors jamais envoyer de DNS en clair).
     */
    @Volatile private var systemResolverCreated = false
    private val systemResolver by lazy {
        systemResolverCreated = true
        SystemResolverUpstream(this, network = { networkMonitor.current.value.network })
    }

    private inner class SystemUpstreamWithFallback : DnsUpstream {
        private val system = systemResolver
        private val direct = UdpUpstream(
            servers = { networkMonitor.current.value.dnsServers.filterNot { isOwnTunnelAddress(it) } },
            protector = SocketProtector { socket -> protect(socket) },
        )

        override fun resolve(query: ByteArray): ByteArray = try {
            system.resolve(query)
        } catch (e: IOException) {
            if (networkMonitor.current.value.privateDnsActive) throw e
            direct.resolve(query)
        }
    }

    private fun isOwnTunnelAddress(address: InetAddress): Boolean =
        session?.addresses?.let { address.hostAddress == it.dnsServer || address.hostAddress == it.interfaceAddress } ?: false

    private val journalListener = object : DnsPacketProcessor.Listener {
        override fun onBlocked(query: BlockedQuery, packet: UdpPacket) {
            // Le domaine canari de Firefox n'est pas une publicité : pas d'entrée au journal.
            if (query.source is BlockSource.Builtin) return
            val uid = ownerUid(packet)
            val app = container.appResolver.forUid(uid)
            // Trace réservée aux builds de débogage (vérifiée par le test sur émulateur).
            if (BuildConfig.DEBUG) Log.d(TAG, "Bloqué : ${query.domain} (${app.label}, uid $uid)")
            val (category, source) = when (val s = query.source) {
                is BlockSource.FromList -> s.category.name to s.listId
                is BlockSource.FromRule -> CATEGORY_RULE to s.rule
                BlockSource.Builtin -> "BUILTIN" to "builtin"
            }
            container.journal.record(
                BlockedEventEntity(
                    timestamp = query.timestamp,
                    domain = query.domain,
                    queryType = query.queryType,
                    uid = uid,
                    appPackage = app.packageName,
                    appLabel = app.label,
                    category = category,
                    source = source,
                    matched = query.matched,
                    viaCname = query.viaCname,
                ),
            )
        }

        override fun onUpstreamFailure(domain: String?, error: IOException) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Échec de résolution amont : ${error.message}")
        }
    }

    /**
     * Application à l'origine de la requête (API 29). La socket DNS existe encore à ce moment,
     * la réponse n'étant pas encore écrite.
     */
    private fun ownerUid(packet: UdpPacket): Int = try {
        connectivity.getConnectionOwnerUid(
            OsConstants.IPPROTO_UDP,
            InetSocketAddress(InetAddress.getByAddress(packet.sourceAddress), packet.sourcePort),
            InetSocketAddress(InetAddress.getByAddress(packet.destinationAddress), packet.destinationPort),
        )
    } catch (e: SecurityException) {
        Process.INVALID_UID
    } catch (e: IllegalArgumentException) {
        Process.INVALID_UID
    }

    private fun localIpAddresses(): List<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { it.inetAddresses.toList() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun fail(reason: String) {
        Log.w(TAG, reason)
        teardown()
        container.protectionStatus.value = ProtectionStatus.Failed(reason)
        finish()
    }

    private suspend fun stopProtection(userRequested: Boolean, failure: String? = null) = mutex.withLock {
        teardown()
        if (userRequested) container.settings.setProtectionWanted(false)
        container.protectionStatus.value = failure?.let { ProtectionStatus.Failed(it) } ?: ProtectionStatus.Stopped
        finish()
    }

    private fun teardown() {
        watchers.forEach { it.cancel() }
        watchers = emptyList()
        session?.worker?.stop()
        session = null
        networkMonitor.stop()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Autorisation retirée ou autre VPN démarré : on s'efface sans relance automatique. */
    override fun onRevoke() {
        scope.launch {
            stopProtection(
                userRequested = true,
                failure = "Protection coupée : une autre application VPN a pris la main ou l'autorisation a été retirée.",
            )
        }
    }

    override fun onDestroy() {
        teardown()
        if (systemResolverCreated) systemResolver.close()
        val status = container.protectionStatus.value
        if (status is ProtectionStatus.Running || status is ProtectionStatus.Starting) {
            container.protectionStatus.value = ProtectionStatus.Stopped
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "io.github.clawfabriceh92.adblockdns.action.START"
        const val ACTION_STOP = "io.github.clawfabriceh92.adblockdns.action.STOP"
        const val CATEGORY_RULE = "RULE"
        private const val TAG = "VpnService"
        private const val MTU = 1500
        private const val ENGINE_WAIT_MS = 10_000L
        private const val NOTIFICATION_REFRESH_MS = 15_000L
    }
}
