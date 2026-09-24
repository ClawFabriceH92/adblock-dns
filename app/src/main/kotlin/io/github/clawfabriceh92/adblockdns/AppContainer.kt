package io.github.clawfabriceh92.adblockdns

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.clawfabriceh92.adblockdns.apps.AppResolver
import io.github.clawfabriceh92.adblockdns.core.lists.ListDownloader
import io.github.clawfabriceh92.adblockdns.data.BlocklistRepository
import io.github.clawfabriceh92.adblockdns.data.FilterEngineHolder
import io.github.clawfabriceh92.adblockdns.data.JournalRepository
import io.github.clawfabriceh92.adblockdns.data.RulesRepository
import io.github.clawfabriceh92.adblockdns.data.SettingsRepository
import io.github.clawfabriceh92.adblockdns.data.db.AppDatabase
import io.github.clawfabriceh92.adblockdns.data.settingsDataStore
import io.github.clawfabriceh92.adblockdns.vpn.ProtectionStatus
import io.github.clawfabriceh92.adblockdns.work.ListUpdateScheduler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Dépendances partagées de l'application (injection manuelle, sans framework) : une seule
 * instance par processus, créée par [AdBlockApplication].
 */
class AppContainer(private val application: Application) {

    val context: Context get() = application

    private val errorHandler = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "Erreur non gérée dans une tâche de fond", error)
    }

    /** Portée des tâches de fond qui vivent aussi longtemps que le processus. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errorHandler)

    val database: AppDatabase by lazy { AppDatabase.build(application) }

    val settings = SettingsRepository(application.settingsDataStore)

    val appResolver = AppResolver(application)

    val rules: RulesRepository by lazy { RulesRepository(database.ruleDao()) }

    val journal: JournalRepository by lazy { JournalRepository(database.blockedEventDao(), applicationScope) }

    val blocklists: BlocklistRepository by lazy {
        BlocklistRepository(
            context = application,
            dao = database.listStateDao(),
            downloader = ListDownloader(userAgent = "AdBlockDns/${BuildConfig.VERSION_NAME} (Android)"),
        )
    }

    val filterEngine: FilterEngineHolder by lazy { FilterEngineHolder(blocklists, rules, applicationScope) }

    /** État du tunnel, publié par le service VPN et observé par l'interface. */
    val protectionStatus = MutableStateFlow<ProtectionStatus>(ProtectionStatus.Stopped)

    fun start() {
        applicationScope.launch {
            blocklists.ensureInstalled()
            journal.purgeOlderThanRetention()
        }
        filterEngine.start()
        applicationScope.launch {
            settings.settings
                .map { it.autoUpdateLists }
                .distinctUntilChanged()
                .collect { enabled -> ListUpdateScheduler.apply(application, enabled) }
        }
    }

    private companion object {
        const val TAG = "AppContainer"
    }
}
