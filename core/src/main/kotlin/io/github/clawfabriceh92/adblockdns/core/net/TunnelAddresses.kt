package io.github.clawfabriceh92.adblockdns.core.net

import java.net.Inet4Address
import java.net.InetAddress

/** Adresses IPv4 de l'interface du tunnel et du serveur DNS virtuel (même /24). */
data class TunnelAddresses(val interfaceAddress: String, val dnsServer: String) {
    val prefixLength: Int get() = 24

    companion object {
        /**
         * Plages candidates, par ordre de préférence : d'abord les plages de documentation
         * (RFC 5737), jamais routées sur Internet, puis des plages privées peu courantes. Une plage
         * fixe peut entrer en conflit avec un réseau réel (le conteneur de test de ce projet utilise
         * lui-même 192.0.2.0/24) : on prend la première qui ne contient aucune adresse locale.
         */
        val CANDIDATE_PREFIXES = listOf("198.51.100", "203.0.113", "192.0.2", "10.111.222", "172.31.254", "192.168.254")

        fun choose(addressesInUse: Collection<InetAddress>): TunnelAddresses {
            val used = addressesInUse
                .filterIsInstance<Inet4Address>()
                .map { it.address.let { b -> "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}" } }
                .toSet()
            val prefix = CANDIDATE_PREFIXES.firstOrNull { it !in used } ?: CANDIDATE_PREFIXES.first()
            return TunnelAddresses("$prefix.1", "$prefix.2")
        }
    }
}
