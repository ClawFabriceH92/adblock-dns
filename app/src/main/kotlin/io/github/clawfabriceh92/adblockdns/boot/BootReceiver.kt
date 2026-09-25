package io.github.clawfabriceh92.adblockdns.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import io.github.clawfabriceh92.adblockdns.appContainer
import io.github.clawfabriceh92.adblockdns.vpn.VpnController
import kotlinx.coroutines.launch

/**
 * Relance la protection :
 * - au démarrage du téléphone, si l'utilisateur l'avait activée et a coché « Démarrage au boot » ;
 * - après une mise à jour de l'application (Android arrête alors le service), si elle tournait.
 * L'autorisation VPN, déjà accordée, n'est pas redemandée (VpnService.prepare renvoie null).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val container = context.appContainer
        container.applicationScope.launch {
            try {
                val settings = container.settings.current()
                val wanted = settings.protectionWanted && (action == Intent.ACTION_MY_PACKAGE_REPLACED || settings.startOnBoot)
                if (wanted && VpnService.prepare(context) == null) {
                    VpnController.start(context)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Relance de la protection impossible", e)
            } finally {
                pending.finish()
            }
        }
    }
}
