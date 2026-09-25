package io.github.clawfabriceh92.adblockdns.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "reglages")

/** Résolveur auquel sont transmises les requêtes autorisées. */
enum class UpstreamChoice(val label: String, val detail: String) {
    SYSTEM("DNS du téléphone", "Résolveur du réseau actif, via le résolveur d'Android (respecte le DNS privé)"),
    CLOUDFLARE("Cloudflare (chiffré)", "DNS-over-HTTPS vers cloudflare-dns.com"),
    QUAD9("Quad9 (chiffré)", "DNS-over-HTTPS vers dns.quad9.net"),
}

data class Settings(
    /** Protection voulue par l'utilisateur : sert à la relancer au démarrage du téléphone. */
    val protectionWanted: Boolean = false,
    val startOnBoot: Boolean = true,
    val upstream: UpstreamChoice = UpstreamChoice.SYSTEM,
    val excludedApps: Set<String> = emptySet(),
    val cnameInspection: Boolean = true,
    /** Activée par défaut : un instantané de liste vieillit vite (hagezi annonce « Expires: 8 hours »). */
    val autoUpdateLists: Boolean = true,
)

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<Settings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { p ->
            Settings(
                protectionWanted = p[PROTECTION_WANTED] ?: false,
                startOnBoot = p[START_ON_BOOT] ?: true,
                upstream = p[UPSTREAM]?.let { name -> UpstreamChoice.entries.firstOrNull { it.name == name } }
                    ?: UpstreamChoice.SYSTEM,
                excludedApps = p[EXCLUDED_APPS] ?: emptySet(),
                cnameInspection = p[CNAME_INSPECTION] ?: true,
                autoUpdateLists = p[AUTO_UPDATE_LISTS] ?: true,
            )
        }

    suspend fun current(): Settings = settings.first()

    suspend fun setProtectionWanted(value: Boolean) = putBoolean(PROTECTION_WANTED, value)
    suspend fun setStartOnBoot(value: Boolean) = putBoolean(START_ON_BOOT, value)
    suspend fun setCnameInspection(value: Boolean) = putBoolean(CNAME_INSPECTION, value)
    suspend fun setAutoUpdateLists(value: Boolean) = putBoolean(AUTO_UPDATE_LISTS, value)

    suspend fun setUpstream(value: UpstreamChoice) {
        dataStore.edit { it[UPSTREAM] = value.name }
    }

    suspend fun setAppExcluded(packageName: String, excluded: Boolean) {
        dataStore.edit { p ->
            val current = p[EXCLUDED_APPS] ?: emptySet()
            p[EXCLUDED_APPS] = if (excluded) current + packageName else current - packageName
        }
    }

    private suspend fun putBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        dataStore.edit { it[key] = value }
    }

    private companion object {
        val PROTECTION_WANTED = booleanPreferencesKey("protection_voulue")
        val START_ON_BOOT = booleanPreferencesKey("demarrage_boot")
        val UPSTREAM = stringPreferencesKey("resolveur_amont")
        val EXCLUDED_APPS = stringSetPreferencesKey("applications_exclues")
        val CNAME_INSPECTION = booleanPreferencesKey("inspection_cname")
        val AUTO_UPDATE_LISTS = booleanPreferencesKey("maj_auto_listes")
    }
}
