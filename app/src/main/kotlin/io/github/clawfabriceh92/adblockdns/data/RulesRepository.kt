package io.github.clawfabriceh92.adblockdns.data

import io.github.clawfabriceh92.adblockdns.core.filter.RuleSyntax
import io.github.clawfabriceh92.adblockdns.data.db.RuleDao
import io.github.clawfabriceh92.adblockdns.data.db.RuleEntity
import kotlinx.coroutines.flow.Flow

/** Listes blanche et noire manuelles. */
class RulesRepository(private val dao: RuleDao) {

    val all: Flow<List<RuleEntity>> = dao.all()

    /**
     * Ajoute une règle après normalisation (URL collée, `||domaine^`… acceptés).
     * Renvoie la forme enregistrée, ou null si la saisie n'est pas une règle valide.
     */
    suspend fun add(input: String, type: String): String? {
        val pattern = RuleSyntax.normalize(input) ?: return null
        dao.insert(RuleEntity(pattern = pattern, type = type, createdAt = System.currentTimeMillis()))
        return pattern
    }

    suspend fun allow(domain: String): String? = add(domain, RuleEntity.ALLOW)

    suspend fun remove(pattern: String, type: String) = dao.delete(pattern, type)
}
