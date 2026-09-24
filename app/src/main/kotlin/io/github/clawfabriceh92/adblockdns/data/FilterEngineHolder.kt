package io.github.clawfabriceh92.adblockdns.data

import android.util.Log
import io.github.clawfabriceh92.adblockdns.core.filter.FilterEngine
import io.github.clawfabriceh92.adblockdns.core.filter.RuleMatcher
import io.github.clawfabriceh92.adblockdns.data.db.RuleEntity
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class EngineStatus(
    /** Faux tant que la liste embarquée n'est pas prête (premier lancement). */
    val ready: Boolean = false,
    val listEntries: Int = 0,
    val activeLists: Int = 0,
    val allowRules: Int = 0,
    val blockRules: Int = 0,
)

/**
 * Tient le [FilterEngine] courant. Il est reconstruit à chaque changement de liste ou de règle
 * puis remplacé atomiquement : le tunnel continue de tourner et la nouvelle règle s'applique à
 * la requête suivante (« Autoriser » répare un site sans redémarrage).
 */
class FilterEngineHolder(
    private val lists: BlocklistRepository,
    private val rules: RulesRepository,
    private val scope: CoroutineScope,
) {
    private val current = AtomicReference(FilterEngine.EMPTY)

    val engine: FilterEngine get() = current.get()

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    fun start() {
        scope.launch {
            combine(lists.states, lists.fileVersion, lists.installed, rules.all) { states, _, installed, allRules ->
                Triple(states, installed, allRules)
            }.collectLatest { (states, installed, allRules) ->
                try {
                    val loaded = lists.loadEnabled(states)
                    val allow = allRules.filter { it.type == RuleEntity.ALLOW }.map { it.pattern }
                    val block = allRules.filter { it.type == RuleEntity.BLOCK }.map { it.pattern }
                    val next = FilterEngine(RuleMatcher(allow), RuleMatcher(block), loaded)
                    current.set(next)
                    _status.value = EngineStatus(
                        ready = installed,
                        listEntries = next.listEntryCount,
                        activeLists = loaded.size,
                        allowRules = allow.size,
                        blockRules = block.size,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // On garde le filtre précédent : mieux vaut filtrer avec l'ancien que ne rien filtrer.
                    Log.e(TAG, "Reconstruction du filtre impossible", e)
                }
            }
        }
    }

    /** Attend que le filtre soit prêt, au plus [timeoutMs] millisecondes. */
    suspend fun awaitReady(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { status.filter { it.ready }.first() }
    }

    private companion object {
        const val TAG = "Filtre"
    }
}
