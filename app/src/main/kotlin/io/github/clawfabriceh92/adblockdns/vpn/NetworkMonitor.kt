package io.github.clawfabriceh92.adblockdns.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Réseau physique utilisé pour sortir (Wi-Fi ou mobile) et sa configuration DNS. */
data class UnderlyingNetwork(
    val network: Network? = null,
    val dnsServers: List<InetAddress> = emptyList(),
    val privateDnsActive: Boolean = false,
    /** Non nul quand le DNS privé d'Android est en mode strict (nom d'hôte imposé). */
    val privateDnsServerName: String? = null,
)

/**
 * Suit le réseau par défaut de l'application. Celle-ci s'exclut de son propre tunnel : son
 * réseau par défaut est donc le réseau physique, dont on relit les serveurs DNS à chaque
 * changement (passage Wi-Fi ↔ mobile).
 */
class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val _current = MutableStateFlow(UnderlyingNetwork())
    val current: StateFlow<UnderlyingNetwork> = _current.asStateFlow()

    @Volatile private var registered = false
    @Volatile private var lastIsVpn = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            lastIsVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            // Garde-fou : ne jamais prendre un VPN (le nôtre) comme résolveur amont.
            if (lastIsVpn) return
            _current.value = UnderlyingNetwork(
                network = network,
                dnsServers = linkProperties.dnsServers,
                privateDnsActive = linkProperties.isPrivateDnsActive,
                privateDnsServerName = linkProperties.privateDnsServerName,
            )
        }

        override fun onLost(network: Network) {
            if (_current.value.network == network) _current.value = UnderlyingNetwork()
        }
    }

    fun start() {
        if (registered) return
        connectivity.registerDefaultNetworkCallback(callback)
        registered = true
    }

    fun stop() {
        if (!registered) return
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: IllegalArgumentException) {
            // déjà désinscrit
        }
        registered = false
    }

    companion object {
        /**
         * Nom du serveur quand le DNS privé d'Android est en mode strict, sinon null. Dans ce mode,
         * les applications peuvent envoyer leurs requêtes chiffrées directement à ce serveur, sans
         * passer par le tunnel : le filtrage ne les voit alors pas (à vérifier sur l'appareil).
         */
        fun strictPrivateDnsFlow(context: Context): Flow<String?> = callbackFlow {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    trySend(linkProperties.privateDnsServerName)
                }

                override fun onLost(network: Network) {
                    trySend(null)
                }
            }
            connectivity.registerDefaultNetworkCallback(callback)
            awaitClose { connectivity.unregisterNetworkCallback(callback) }
        }.distinctUntilChanged()
    }
}
