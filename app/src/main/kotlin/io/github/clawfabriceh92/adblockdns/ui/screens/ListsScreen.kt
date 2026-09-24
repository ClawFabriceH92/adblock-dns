package io.github.clawfabriceh92.adblockdns.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.clawfabriceh92.adblockdns.data.BlocklistCatalog
import io.github.clawfabriceh92.adblockdns.data.db.RuleEntity
import io.github.clawfabriceh92.adblockdns.ui.AppViewModelFactory
import io.github.clawfabriceh92.adblockdns.ui.ListItemUi
import io.github.clawfabriceh92.adblockdns.ui.ListsViewModel
import io.github.clawfabriceh92.adblockdns.ui.components.CardTitle
import io.github.clawfabriceh92.adblockdns.ui.components.MutedText
import io.github.clawfabriceh92.adblockdns.ui.components.ScreenTitle
import io.github.clawfabriceh92.adblockdns.ui.components.SectionCard
import io.github.clawfabriceh92.adblockdns.ui.components.Tag
import io.github.clawfabriceh92.adblockdns.ui.formatCount
import io.github.clawfabriceh92.adblockdns.ui.formatDateTime
import io.github.clawfabriceh92.adblockdns.ui.plural
import io.github.clawfabriceh92.adblockdns.ui.theme.AdBlockTheme

@Composable
fun ListsScreen(
    onOpenRules: () -> Unit,
    showMessage: suspend (String) -> Unit,
    viewModel: ListsViewModel = viewModel(factory = AppViewModelFactory),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val engine by viewModel.engine.collectAsStateWithLifecycle()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri)
    }
    LaunchedEffect(viewModel) { viewModel.messages.collect { showMessage(it) } }
    val anyUpdating = items.any { it.updating }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ScreenTitle("Listes de blocage")

        SectionCard {
            CardTitle("${formatCount(engine.listEntries)} domaines bloqués")
            MutedText("${plural(engine.activeLists, "liste active", "listes actives")} · chaque entrée bloque aussi ses sous-domaines")
            Spacer(Modifier.height(10.dp))
            if (anyUpdating) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 10.dp))
            Button(onClick = viewModel::updateAll, enabled = !anyUpdating, modifier = Modifier.fillMaxWidth()) {
                Text(if (anyUpdating) "Mise à jour en cours…" else "Mettre à jour les listes")
            }
            Spacer(Modifier.height(6.dp))
            MutedText("Téléchargement direct depuis GitHub (raw.githubusercontent.com), requête conditionnelle : une liste inchangée n'est pas retéléchargée.")
        }

        for (item in items) {
            ListCard(
                item = item,
                onToggle = { enabled -> viewModel.setEnabled(item, enabled) },
                onImport = { importer.launch(arrayOf("*/*")) },
                onRemove = viewModel::removeCustom,
            )
        }

        RulesSummary("Liste blanche", "Toujours autorisés, prioritaires sur toutes les listes", rules.filter { it.type == RuleEntity.ALLOW }, onOpenRules)
        RulesSummary("Liste noire manuelle", "Ajoutés à la main, en plus des listes publiques", rules.filter { it.type == RuleEntity.BLOCK }, onOpenRules)
    }
}

@Composable
private fun ListCard(item: ListItemUi, onToggle: (Boolean) -> Unit, onImport: () -> Unit, onRemove: () -> Unit) {
    val definition = item.definition
    val state = item.state
    val enabled = state?.enabled == true
    val installedAt = state?.installedAt
    val installed = installedAt != null
    val isCustom = definition.id == BlocklistCatalog.CUSTOM.id
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(definition.name, fontWeight = FontWeight.Medium)
                    if (enabled && installed) {
                        Spacer(Modifier.width(6.dp))
                        Tag("active", AdBlockTheme.colors.ok)
                    }
                }
                MutedText(if (isCustom && state?.displayName != null) "Fichier : ${state.displayName}" else definition.description)
            }
            if (!isCustom || installed) {
                Spacer(Modifier.width(8.dp))
                Switch(checked = enabled, onCheckedChange = onToggle, enabled = !item.updating)
            }
        }
        Spacer(Modifier.height(6.dp))
        when {
            item.updating -> LinearProgressIndicator(Modifier.fillMaxWidth())
            state != null && installedAt != null -> MutedText(
                buildString {
                    append(plural(state.entryCount, "domaine"))
                    state.sourceVersion?.let { append(" · version $it") }
                    append(" · installée le ${formatDateTime(installedAt)}")
                },
            )
            definition.url != null -> MutedText("Pas encore téléchargée : activez-la pour la récupérer.")
        }
        state?.lastError?.let {
            Text("Dernière erreur : $it", color = AdBlockTheme.colors.ko, style = MaterialTheme.typography.bodySmall)
        }
        if (isCustom) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImport) { Text(if (installed) "Remplacer le fichier" else "Importer un fichier") }
                if (installed) TextButton(onClick = onRemove) { Text("Retirer") }
            }
        }
        definition.homepage?.let { MutedText("Source : ${it.removePrefix("https://")} · licence ${definition.license}", modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun RulesSummary(title: String, description: String, rules: List<RuleEntity>, onOpenRules: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardTitle("$title · ${plural(rules.size, "règle")}")
                MutedText(description)
            }
            TextButton(onClick = onOpenRules) { Text("Gérer") }
        }
        if (rules.isNotEmpty()) {
            MutedText(rules.take(6).joinToString("  ·  ") { it.pattern } + if (rules.size > 6) "  …" else "", modifier = Modifier.padding(top = 6.dp))
        }
    }
}
