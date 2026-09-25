package io.github.clawfabriceh92.adblockdns.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.clawfabriceh92.adblockdns.R
import io.github.clawfabriceh92.adblockdns.ui.MainActivity
import io.github.clawfabriceh92.adblockdns.ui.formatCount
import io.github.clawfabriceh92.adblockdns.vpn.AdBlockVpnService

object Notifications {
    const val CHANNEL_PROTECTION = "protection"
    const val ID_PROTECTION = 1

    fun createChannels(context: Context) {
        val channel = NotificationChannel(CHANNEL_PROTECTION, "Protection active", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Notification permanente exigée par Android tant que le filtrage tourne. " +
                "Elle peut être réduite ou masquée dans les réglages de notification."
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun protection(context: Context, blockedToday: Int?): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (blockedToday == null) {
            "Filtrage DNS en service"
        } else {
            "${formatCount(blockedToday)} requête${if (blockedToday > 1) "s" else ""} bloquée${if (blockedToday > 1) "s" else ""} aujourd'hui"
        }
        return NotificationCompat.Builder(context, CHANNEL_PROTECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Protection active")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
