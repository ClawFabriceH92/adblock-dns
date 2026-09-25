package io.github.clawfabriceh92.adblockdns.data

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.clawfabriceh92.adblockdns.core.filter.HashedDomainSet
import io.github.clawfabriceh92.adblockdns.core.filter.ListCategory
import io.github.clawfabriceh92.adblockdns.core.filter.LoadedList
import io.github.clawfabriceh92.adblockdns.core.lists.BlocklistCompiler
import io.github.clawfabriceh92.adblockdns.core.lists.CompiledList
import io.github.clawfabriceh92.adblockdns.core.lists.DownloadResult
import io.github.clawfabriceh92.adblockdns.core.lists.ListDownloader
import io.github.clawfabriceh92.adblockdns.data.db.ListStateDao
import io.github.clawfabriceh92.adblockdns.data.db.ListStateEntity
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Liste proposée par l'application. */
data class BlocklistDefinition(
    val id: String,
    val name: String,
    val description: String,
    /** Source en ligne ; null pour la liste importée depuis un fichier. */
    val url: String?,
    val category: ListCategory,
    val enabledByDefault: Boolean,
    /** Instantané embarqué dans l'APK (gzip), utilisé avant la première mise à jour. */
    val embeddedAsset: String? = null,
    val license: String,
    val homepage: String?,
)

object BlocklistCatalog {
    val HAGEZI_PRO = BlocklistDefinition(
        id = "hagezi-pro",
        name = "HaGeZi Multi PRO",
        description = "Publicité, traçage, télémétrie et une partie des arnaques. Liste par défaut, embarquée dans l'application.",
        url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt",
        category = ListCategory.ADS_TRACKING,
        enabledByDefault = true,
        // Pas de « .gz » : la compilation Android retire cette extension des assets (hérité d'aapt).
        embeddedAsset = "blocklists/hagezi-pro.txt.gzip",
        license = "GPL-3.0",
        homepage = "https://github.com/hagezi/dns-blocklists",
    )
    val HAGEZI_PRO_PLUS = BlocklistDefinition(
        id = "hagezi-pro-plus",
        name = "HaGeZi Multi PRO++",
        description = "Version plus stricte, à ajouter à la liste par défaut : bloque davantage de publicités et de traqueurs, " +
            "avec plus de risques de gêner un site ou une application (selon son auteur).",
        url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro.plus-onlydomains.txt",
        category = ListCategory.ADS_TRACKING,
        enabledByDefault = false,
        license = "GPL-3.0",
        homepage = "https://github.com/hagezi/dns-blocklists",
    )
    val HAGEZI_TIF = BlocklistDefinition(
        id = "hagezi-tif",
        name = "HaGeZi Threat Intelligence (medium)",
        description = "Domaines malveillants : hameçonnage, logiciels malveillants, arnaques. Téléchargement d'environ 16 Mo.",
        url = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/tif.medium-onlydomains.txt",
        category = ListCategory.MALWARE,
        enabledByDefault = false,
        license = "GPL-3.0",
        homepage = "https://github.com/hagezi/dns-blocklists",
    )
    val STEVENBLACK = BlocklistDefinition(
        id = "stevenblack",
        name = "StevenBlack (unifiée)",
        description = "Publicité et logiciels malveillants, au format hosts.",
        url = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
        category = ListCategory.ADS_TRACKING,
        enabledByDefault = false,
        license = "MIT",
        homepage = "https://github.com/StevenBlack/hosts",
    )
    val CUSTOM = BlocklistDefinition(
        id = "personnelle",
        name = "Ma liste personnelle",
        description = "Importée depuis un fichier du téléphone (domaines, hosts ou adblock).",
        url = null,
        category = ListCategory.CUSTOM,
        enabledByDefault = false,
        license = "",
        homepage = null,
    )

    val ALL = listOf(HAGEZI_PRO, HAGEZI_PRO_PLUS, HAGEZI_TIF, STEVENBLACK, CUSTOM)

    fun byId(id: String): BlocklistDefinition? = ALL.firstOrNull { it.id == id }
}

sealed interface UpdateOutcome {
    data object UpToDate : UpdateOutcome
    data class Updated(val entries: Int) : UpdateOutcome
    data class Failed(val message: String) : UpdateOutcome
}

/**
 * Listes de blocage : état en base, fichiers compilés dans le stockage interne
 * (`files/listes/<id>.bin`), liste embarquée, mises à jour et import.
 */
class BlocklistRepository(
    private val context: Context,
    private val dao: ListStateDao,
    private val downloader: ListDownloader,
) {
    private val directory = File(context.filesDir, "listes")
    private val fileMutex = Mutex()
    private val cache = HashMap<String, CachedSet>()

    private class CachedSet(val stamp: Long, val length: Long, val set: HashedDomainSet)

    val states: Flow<List<ListStateEntity>> = dao.all()

    private val _fileVersion = MutableStateFlow(0L)

    /** Change à chaque remplacement d'un fichier compilé : déclenche le rechargement du filtre. */
    val fileVersion: StateFlow<Long> = _fileVersion.asStateFlow()

    private val _installed = MutableStateFlow(false)

    /** Vrai une fois la liste embarquée prête au premier lancement. */
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    private val _updating = MutableStateFlow<Set<String>>(emptySet())

    /** Listes en cours de téléchargement. */
    val updating: StateFlow<Set<String>> = _updating.asStateFlow()

    private fun compiledFile(id: String) = File(directory, "$id.bin")

    fun hasCompiledFile(id: String): Boolean = compiledFile(id).exists()

    /** Crée l'état des listes du catalogue et compile la liste embarquée si nécessaire. */
    suspend fun ensureInstalled() = withContext(Dispatchers.IO) {
        try {
            for (definition in BlocklistCatalog.ALL) {
                dao.insertIfAbsent(ListStateEntity(id = definition.id, enabled = definition.enabledByDefault))
            }
            for (state in dao.allNow()) {
                val definition = BlocklistCatalog.byId(state.id) ?: continue
                if (state.enabled && definition.embeddedAsset != null && !isUsable(state.id)) {
                    try {
                        installFromAsset(definition, state)
                    } catch (e: IOException) {
                        Log.e(TAG, "Préparation de la liste embarquée impossible", e)
                        // Visible dans l'écran Listes ; l'accueil signale l'absence de règles.
                        dao.upsert(state.copy(lastError = "Liste embarquée illisible : ${describe(e)}"))
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Préparation des listes impossible", e)
        } finally {
            _installed.value = true
        }
    }

    private fun isUsable(id: String): Boolean {
        val file = compiledFile(id)
        if (!file.exists()) return false
        return file.inputStream().use { HashedDomainSet.peekEntryCount(it, file.length()) } != null
    }

    private suspend fun installFromAsset(definition: BlocklistDefinition, state: ListStateEntity) {
        val asset = definition.embeddedAsset ?: return
        val compiled = context.assets.open(asset).use { raw -> compileMaybeGzip(raw) }
        fileMutex.withLock { writeCompiled(definition.id, compiled.domains) }
        dao.upsert(
            state.copy(
                entryCount = compiled.domains.size,
                installedAt = System.currentTimeMillis(),
                sourceVersion = compiled.describeVersion(),
                // Pas d'ETag : la première mise à jour en ligne téléchargera la liste complète.
                etag = null,
                lastModified = null,
                lastError = null,
            ),
        )
    }

    /** Écriture atomique : fichier temporaire synchronisé sur disque puis renommé. */
    private fun writeCompiled(id: String, set: HashedDomainSet) {
        directory.mkdirs()
        val temporary = File(directory, "$id.bin.tmp")
        temporary.outputStream().use { out ->
            set.writeTo(out)
            out.fd.sync()
        }
        if (!temporary.renameTo(compiledFile(id))) {
            temporary.delete()
            throw IOException("Impossible d'enregistrer la liste « $id »")
        }
        _fileVersion.update { it + 1 }
    }

    /** Charge en mémoire les listes activées (réutilise celles dont le fichier n'a pas changé). */
    suspend fun loadEnabled(states: List<ListStateEntity>): List<LoadedList> = withContext(Dispatchers.IO) {
        val enabled = states.filter { it.enabled }
        val loaded = enabled.mapNotNull { state ->
            val definition = BlocklistCatalog.byId(state.id) ?: return@mapNotNull null
            val set = loadSet(state.id) ?: return@mapNotNull null
            LoadedList(definition.id, definition.category, set)
        }
        synchronized(cache) { cache.keys.retainAll(enabled.map { it.id }.toSet()) }
        loaded
    }

    private fun loadSet(id: String): HashedDomainSet? {
        val file = compiledFile(id)
        if (!file.exists()) return null
        val stamp = file.lastModified()
        val length = file.length()
        synchronized(cache) {
            cache[id]?.takeIf { it.stamp == stamp && it.length == length }?.let { return it.set }
        }
        return try {
            file.inputStream().use { HashedDomainSet.readFrom(it) }.also { set ->
                synchronized(cache) { cache[id] = CachedSet(stamp, length, set) }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Liste compilée illisible : $id", e)
            null
        }
    }

    /** Télécharge une liste si elle a changé (requête conditionnelle). */
    suspend fun update(id: String): UpdateOutcome = withContext(Dispatchers.IO) {
        val definition = BlocklistCatalog.byId(id) ?: return@withContext UpdateOutcome.Failed("Liste inconnue")
        val url = definition.url ?: return@withContext UpdateOutcome.Failed("Pas de source en ligne")
        _updating.update { it + id }
        val state = dao.get(id) ?: ListStateEntity(id = id, enabled = definition.enabledByDefault)
        try {
            val hasFile = compiledFile(id).exists()
            val result = downloader.download(
                url = url,
                etag = state.etag.takeIf { hasFile },
                lastModified = state.lastModified.takeIf { hasFile },
            )
            val now = System.currentTimeMillis()
            when (result) {
                DownloadResult.NotModified -> {
                    dao.upsert(state.copy(lastCheckAt = now, lastError = null))
                    UpdateOutcome.UpToDate
                }
                is DownloadResult.Updated -> {
                    fileMutex.withLock { writeCompiled(id, result.list.domains) }
                    dao.upsert(
                        state.copy(
                            entryCount = result.list.domains.size,
                            installedAt = now,
                            sourceVersion = result.list.describeVersion(),
                            etag = result.etag,
                            lastModified = result.lastModified,
                            lastCheckAt = now,
                            lastError = null,
                        ),
                    )
                    UpdateOutcome.Updated(result.list.domains.size)
                }
            }
        } catch (e: IOException) {
            val message = describe(e)
            dao.upsert(state.copy(lastCheckAt = System.currentTimeMillis(), lastError = message))
            UpdateOutcome.Failed(message)
        } finally {
            _updating.update { it - id }
        }
    }

    /** Met à jour toutes les listes activées qui ont une source en ligne. */
    suspend fun updateAll(): Map<String, UpdateOutcome> {
        val targets = dao.allNow().filter { it.enabled && BlocklistCatalog.byId(it.id)?.url != null }
        return targets.associate { it.id to update(it.id) }
    }

    /** Active ou désactive une liste ; une liste activée pour la première fois est téléchargée. */
    suspend fun setEnabled(id: String, enabled: Boolean): UpdateOutcome? {
        val definition = BlocklistCatalog.byId(id) ?: return null
        val state = dao.get(id) ?: ListStateEntity(id = id, enabled = enabled)
        dao.upsert(state.copy(enabled = enabled))
        if (!enabled || compiledFile(id).exists()) return null
        return when {
            definition.embeddedAsset != null -> withContext(Dispatchers.IO) {
                try {
                    installFromAsset(definition, state.copy(enabled = true))
                    null
                } catch (e: IOException) {
                    UpdateOutcome.Failed(describe(e))
                }
            }
            definition.url != null -> update(id)
            else -> null
        }
    }

    /** Importe un fichier choisi par l'utilisateur comme liste personnelle. */
    suspend fun importCustom(uri: Uri, displayName: String?): UpdateOutcome = withContext(Dispatchers.IO) {
        try {
            val compiled = context.contentResolver.openInputStream(uri)?.use { raw -> compileMaybeGzip(raw) }
                ?: return@withContext UpdateOutcome.Failed("Fichier illisible")
            if (compiled.domains.size == 0) return@withContext UpdateOutcome.Failed("Aucun domaine reconnu dans ce fichier")
            fileMutex.withLock { writeCompiled(BlocklistCatalog.CUSTOM.id, compiled.domains) }
            dao.upsert(
                ListStateEntity(
                    id = BlocklistCatalog.CUSTOM.id,
                    enabled = true,
                    entryCount = compiled.domains.size,
                    installedAt = System.currentTimeMillis(),
                    sourceVersion = compiled.describeVersion(),
                    displayName = displayName,
                ),
            )
            UpdateOutcome.Updated(compiled.domains.size)
        } catch (e: IOException) {
            UpdateOutcome.Failed(describe(e))
        } catch (e: SecurityException) {
            UpdateOutcome.Failed("Accès au fichier refusé")
        }
    }

    suspend fun removeCustom() = withContext(Dispatchers.IO) {
        fileMutex.withLock {
            compiledFile(BlocklistCatalog.CUSTOM.id).delete()
            _fileVersion.update { it + 1 }
        }
        dao.upsert(ListStateEntity(id = BlocklistCatalog.CUSTOM.id, enabled = false))
    }

    /** Reconnaît un fichier gzip à sa signature (1f 8b), quel que soit son nom. */
    private fun compileMaybeGzip(raw: InputStream): CompiledList {
        val input = BufferedInputStream(raw)
        input.mark(2)
        val gzip = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        return if (gzip) GZIPInputStream(input).use { BlocklistCompiler.compile(it) } else BlocklistCompiler.compile(input)
    }

    private fun CompiledList.describeVersion(): String? = parse.header.version ?: parse.header.lastModified

    private fun describe(e: IOException): String = when (e) {
        is UnknownHostException -> "Pas de connexion Internet (serveur introuvable)"
        is SocketTimeoutException -> "Délai dépassé : réseau trop lent ou indisponible"
        else -> e.message ?: e.javaClass.simpleName
    }

    private companion object {
        const val TAG = "Listes"
    }
}
