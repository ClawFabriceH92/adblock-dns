package io.github.clawfabriceh92.adblockdns.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** État du tunnel, publié par le service et affiché par l'interface. */
sealed interface ProtectionStatus {
    data object Stopped : ProtectionStatus
    data object Starting : ProtectionStatus
    data class Running(val since: Long, val dnsServer: String, val upstream: String) : ProtectionStatus
    data class Failed(val reason: String) : ProtectionStatus
}

/** Démarrage et arrêt du service depuis l'interface, le démarrage du téléphone ou une notification. */
object VpnController {
    fun start(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_START),
        )
    }

    fun stop(context: Context) {
        context.startService(Intent(context, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP))
    }
}
