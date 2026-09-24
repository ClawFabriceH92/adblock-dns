package io.github.clawfabriceh92.adblockdns

import android.app.Application
import android.content.Context
import io.github.clawfabriceh92.adblockdns.notification.Notifications

class AdBlockApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.start()
    }
}

/** Accès aux dépendances partagées depuis n'importe quel composant Android. */
val Context.appContainer: AppContainer
    get() = (applicationContext as AdBlockApplication).container
