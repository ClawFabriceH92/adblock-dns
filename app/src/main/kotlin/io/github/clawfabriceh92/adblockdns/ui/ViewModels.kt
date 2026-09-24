package io.github.clawfabriceh92.adblockdns.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.clawfabriceh92.adblockdns.AppContainer
import io.github.clawfabriceh92.adblockdns.apps.AppResolver
import io.github.clawfabriceh92.adblockdns.appContainer
import io.github.clawfabriceh92.adblockdns.data.BlocklistCatalog
import io.github.clawfabriceh92.adblockdns.data.BlocklistDefinition
import io.github.clawfabriceh92.adblockdns.data.EngineStatus
import io.github.clawfabriceh92.adblockdns.data.JournalRepository
import io.github.clawfabriceh92.adblockdns.data.Settings
import io.github.clawfabriceh92.adblockdns.data.UpdateOutcome
import io.github.clawfabriceh92.adblockdns.data.UpstreamChoice
import io.github.clawfabriceh92.adblockdns.data.db.AppCount
import io.github.clawfabriceh92.adblockdns.data.db.AppDatabase
import io.github.clawfabriceh92.adblockdns.data.db.AppRef
import io.github.clawfabriceh92.adblockdns.data.db.BlockedEventEntity
import io.github.clawfabriceh92.adblockdns.data.db.BucketCount
import io.github.clawfabriceh92.adblockdns.data.db.CategoryCount
import io.github.clawfabriceh92.adblockdns.data.db.ListStateEntity
import io.github.clawfabriceh92.adblockdns.data.db.RuleEntity
import io.github.clawfabriceh92.adblockdns.vpn.NetworkMonitor
import io.github.clawfabriceh92.adblockdns.vpn.ProtectionStatus
import java.io.File
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Fabrique commune : chaque ViewModel reçoit le conteneur de dépendances de l'application. */
val AppViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
    initializer { HomeViewModel(container()) }
    initializer { JournalViewModel(container()) }
    initializer { StatsViewModel(container()) }
    initializer { ListsViewModel(container()) }
    initializer { RulesViewModel(container()) }
    initializer { SettingsViewModel(container()) }
    initializer { ExcludedAppsViewModel(container()) }
}

private fun CreationExtras.container(): AppContainer =
    checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]).appContainer

private fun <T> Flow<T>.stateIn(model: ViewModel, initial: T): StateFlow<T> =
    stateIn(model.viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

// ---------------------------------------------------------------- Accueil

data class HomeUiState(
    val status: ProtectionStatus = ProtectionStatus.Stopped,
    val blockedToday: Int = 0,
    val appsToday: Int = 0,
    val engine: EngineStatus = EngineStatus(),
    val mainList: ListStateEntity? = null,
    val startOnBoot: Boolean = true,
    /** Nom du serveur quand le DNS privé d'Android est en mode strict. */
    val strictPrivateDns: String? = null,
    val updatingLists: Boolean = false,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val counters = combine(container.journal.blockedToday(), container.journal.appsToday()) { blocked, apps -> blocked to apps }

    val state: StateFlow<HomeUiState> = combine(
        container.protectionStatus,
        counters,
        container.filterEngine.status,
        combine(container.blocklists.states, container.blocklists.updating) { states, updating ->
            states.firstOrNull { it.id == BlocklistCatalog.HAGEZI_PRO.id } to updating.isNotEmpty()
        },
        combine(container.settings.settings, NetworkMonitor.strictPrivateDnsFlow(container.context)) { s, dns -> s.startOnBoot to dns },
    ) { status, (blocked, apps), engine, (mainList, updating), (boot, privateDns) ->
        HomeUiState(status, blocked, apps, engine, mainList, boot, privateDns, updating)
    }.stateIn(this, HomeUiState())

    fun setStartOnBoot(value: Boolean) {
        viewModelScope.launch { container.settings.setStartOnBoot(value) }
    }
}

// ---------------------------------------------------------------- Journal

/** Résultat de « Autoriser », gardé pour pouvoir l'annuler. */
class AllowResult(val domain: String, val pattern: String?, val removed: List<BlockedEventEntity>)

class JournalViewModel(private val container: AppContainer) : ViewModel() {
    private val search = MutableStateFlow("")
    private val app = MutableStateFlow<String?>(null)
    val selectedApp: StateFlow<String?> = app.asStateFlow()

    val events: StateFlow<List<BlockedEventEntity>?> = combine(search.debounce(250), app) { q, a -> q to a }
        .flatMapLatest { (q, a) -> container.journal.recent(q, a) }
        .stateIn(this, null)

    val apps: StateFlow<List<AppRef>> = container.journal.apps().stateIn(this, emptyList())

    val whitelist: StateFlow<List<String>> = container.rules.all
        .map { rules -> rules.filter { it.type == RuleEntity.ALLOW }.map { it.pattern } }
        .stateIn(this, emptyList())

    fun onSearch(text: String) {
        search.value = text
    }

    fun onAppSelected(packageName: String?) {
        app.value = packageName
    }

    /** Ajoute le domaine à la liste blanche et retire ses entrées du journal. */
    suspend fun allow(domain: String): AllowResult {
        val pattern = container.rules.allow(domain)
        val removed = container.journal.takeDomain(domain)
        return AllowResult(domain, pattern, removed)
    }

    fun undo(result: AllowResult) {
        viewModelScope.launch {
            result.pattern?.let { container.rules.remove(it, RuleEntity.ALLOW) }
            container.journal.restore(result.removed)
        }
    }
}

// ---------------------------------------------------------------- Statistiques

enum class StatsPeriod(val label: String, val buckets: Int, val bucketMs: Long) {
    DAY("24 h", 24, JournalRepository.HOUR_MS),
    WEEK("7 j", 7, JournalRepository.DAY_MS),
    MONTH("30 j", 30, JournalRepository.DAY_MS),
}

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.DAY,
    val series: List<Int> = emptyList(),
    val firstLabel: String = "",
    val lastLabel: String = "",
    val total: Int = 0,
    val topApps: List<AppCount> = emptyList(),
    val categories: List<CategoryCount> = emptyList(),
)

/**
 * Fenêtre d'une période statistique en « cases » (heures ou jours) alignées sur l'heure locale.
 * Les numéros de case sont ceux calculés en SQL : (timestamp + décalage horaire) / durée.
 */
data class StatsWindow(val firstBucket: Long, val lastBucket: Long, val sinceMs: Long, val offsetMs: Long) {
    companion object {
        fun of(period: StatsPeriod, nowMs: Long, offsetMs: Long): StatsWindow {
            val last = (nowMs + offsetMs) / period.bucketMs
            val first = last - period.buckets + 1
            return StatsWindow(first, last, first * period.bucketMs - offsetMs, offsetMs)
        }
    }

    /** Série complète de la fenêtre : une valeur par case, 0 pour les cases sans blocage. */
    fun series(histogram: List<BucketCount>): List<Int> {
        val counts = histogram.associate { it.bucket to it.count }
        return (firstBucket..lastBucket).map { counts[it] ?: 0 }
    }

    /** Début de la case en heure UTC (ms), pour l'affichage. */
    fun bucketStartMs(bucket: Long, period: StatsPeriod): Long = bucket * period.bucketMs - offsetMs
}

class StatsViewModel(private val container: AppContainer) : ViewModel() {
    private val period = MutableStateFlow(StatsPeriod.DAY)

    val state: StateFlow<StatsUiState> = period.flatMapLatest { p ->
        val now = System.currentTimeMillis()
        val window = StatsWindow.of(p, now, TimeZone.getDefault().getOffset(now).toLong())
        combine(
            container.journal.histogram(window.sinceMs, p.bucketMs, window.offsetMs),
            container.journal.topApps(window.sinceMs, 5),
            container.journal.categories(window.sinceMs),
        ) { histogram, top, categories ->
            val series = window.series(histogram)
            StatsUiState(
                period = p,
                series = series,
                firstLabel = bucketLabel(p, window, window.firstBucket),
                lastLabel = bucketLabel(p, window, window.lastBucket),
                total = series.sum(),
                topApps = top,
                categories = categories.sortedByDescending { it.count },
            )
        }
    }.stateIn(this, StatsUiState())

    fun select(p: StatsPeriod) {
        period.value = p
    }

    private fun bucketLabel(p: StatsPeriod, window: StatsWindow, bucket: Long): String =
        if (p == StatsPeriod.DAY) "${bucket % 24} h" else formatDayLabel(window.bucketStartMs(bucket, p))
}

// ---------------------------------------------------------------- Listes

data class ListItemUi(val definition: BlocklistDefinition, val state: ListStateEntity?, val updating: Boolean)

class ListsViewModel(private val container: AppContainer) : ViewModel() {
    val items: StateFlow<List<ListItemUi>> = combine(container.blocklists.states, container.blocklists.updating) { states, updating ->
        BlocklistCatalog.ALL.map { d -> ListItemUi(d, states.firstOrNull { it.id == d.id }, d.id in updating) }
    }.stateIn(this, emptyList())

    val rules: StateFlow<List<RuleEntity>> = container.rules.all.stateIn(this, emptyList())

    val engine: StateFlow<EngineStatus> = container.filterEngine.status

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun setEnabled(item: ListItemUi, enabled: Boolean) {
        viewModelScope.launch {
            val outcome = container.blocklists.setEnabled(item.definition.id, enabled)
            if (outcome != null) _messages.emit(describe(item.definition.name, outcome))
        }
    }

    fun updateAll() {
        viewModelScope.launch {
            val outcomes = container.blocklists.updateAll()
            val failed = outcomes.values.filterIsInstance<UpdateOutcome.Failed>()
            val updated = outcomes.values.filterIsInstance<UpdateOutcome.Updated>()
            _messages.emit(
                when {
                    outcomes.isEmpty() -> "Aucune liste en ligne activée"
                    failed.isNotEmpty() -> "Échec de mise à jour : ${failed.first().message}"
                    updated.isEmpty() -> "Listes déjà à jour"
                    else -> "Listes à jour · ${formatCount(updated.sumOf { it.entries })} règles téléchargées"
                },
            )
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { displayName(uri) }
            _messages.emit(describe(name ?: "Fichier", container.blocklists.importCustom(uri, name)))
        }
    }

    private fun displayName(uri: Uri): String? = try {
        container.context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (e: SecurityException) {
        null
    }

    fun removeCustom() {
        viewModelScope.launch {
            container.blocklists.removeCustom()
            _messages.emit("Liste personnelle retirée")
        }
    }

    private fun describe(name: String, outcome: UpdateOutcome): String = when (outcome) {
        is UpdateOutcome.Updated -> "$name : ${plural(outcome.entries, "règle chargée", "règles chargées")}"
        UpdateOutcome.UpToDate -> "$name : déjà à jour"
        is UpdateOutcome.Failed -> "$name : ${outcome.message}"
    }
}

// ---------------------------------------------------------------- Règles

class RulesViewModel(private val container: AppContainer) : ViewModel() {
    val rules: StateFlow<List<RuleEntity>> = container.rules.all.stateIn(this, emptyList())

    /** Renvoie null si la règle est ajoutée, sinon le message d'erreur à afficher. */
    suspend fun add(input: String, type: String): String? =
        if (container.rules.add(input, type) != null) null else "Saisie non reconnue : indiquez un domaine (exemple.com) ou un motif (*.exemple.com)"

    fun remove(rule: RuleEntity) {
        viewModelScope.launch { container.rules.remove(rule.pattern, rule.type) }
    }
}

// ---------------------------------------------------------------- Réglages

data class JournalStorage(val entries: Int, val bytes: Long)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings> = container.settings.settings.stateIn(this, Settings())

    val storage: StateFlow<JournalStorage> = container.journal.totalCount()
        .map { count -> JournalStorage(count, databaseBytes()) }
        .flowOn(Dispatchers.IO)
        .stateIn(this, JournalStorage(0, 0))

    val excludedLabels: StateFlow<List<String>> = container.settings.settings
        .map { s -> s.excludedApps.map { container.appResolver.labelOf(it) }.sortedBy { it.lowercase() } }
        .flowOn(Dispatchers.IO)
        .stateIn(this, emptyList())

    fun setUpstream(choice: UpstreamChoice) = launchEdit { container.settings.setUpstream(choice) }
    fun setStartOnBoot(value: Boolean) = launchEdit { container.settings.setStartOnBoot(value) }
    fun setCnameInspection(value: Boolean) = launchEdit { container.settings.setCnameInspection(value) }
    fun setAutoUpdate(value: Boolean) = launchEdit { container.settings.setAutoUpdateLists(value) }
    fun purgeJournal() = launchEdit { container.journal.purgeAll() }

    private fun launchEdit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun databaseBytes(): Long {
        val main = container.context.getDatabasePath(AppDatabase.FILE_NAME)
        return listOf(main, File(main.path + "-wal"), File(main.path + "-shm")).sumOf { if (it.exists()) it.length() else 0L }
    }
}

class ExcludedAppsViewModel(private val container: AppContainer) : ViewModel() {
    /** null pendant le chargement de la liste des applications installées. */
    val apps: StateFlow<List<AppResolver.InstalledApp>?> = flow { emit(container.appResolver.installedApps()) }
        .flowOn(Dispatchers.IO)
        .stateIn(this, null)

    val excluded: StateFlow<Set<String>> = container.settings.settings.map { it.excludedApps }.stateIn(this, emptySet())

    fun setExcluded(packageName: String, value: Boolean) {
        viewModelScope.launch { container.settings.setAppExcluded(packageName, value) }
    }
}
