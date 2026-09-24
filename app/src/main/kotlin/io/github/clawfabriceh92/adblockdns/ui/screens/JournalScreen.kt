package io.github.clawfabriceh92.adblockdns.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.clawfabriceh92.adblockdns.data.db.AppRef
import io.github.clawfabriceh92.adblockdns.data.db.BlockedEventEntity
import io.github.clawfabriceh92.adblockdns.ui.AppViewModelFactory
import io.github.clawfabriceh92.adblockdns.ui.JournalViewModel
import io.github.clawfabriceh92.adblockdns.ui.components.AppBadge
import io.github.clawfabriceh92.adblockdns.ui.components.CategoryTag
import io.github.clawfabriceh92.adblockdns.ui.components.MutedText
import io.github.clawfabriceh92.adblockdns.ui.components.ScreenTitle
import io.github.clawfabriceh92.adblockdns.ui.components.SectionCard
import io.github.clawfabriceh92.adblockdns.ui.formatTime
import io.github.clawfabriceh92.adblockdns.ui.plural
import io.github.clawfabriceh92.adblockdns.ui.queryTypeLabel
import kotlinx.coroutines.launch

/**
 * Journal des requêtes bloquées. « Autoriser » ajoute le domaine à la liste blanche (effet
 * immédiat, sans redémarrage) et retire ses entrées du journal ; « Annuler » défait les deux.
 */
@Composable
fun JournalScreen(
    onOpenRules: () -> Unit,
    showUndoableMessage: suspend (message: String, action: String) -> Boolean,
    viewModel: JournalViewModel = viewModel(factory = AppViewModelFactory),
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val selectedApp by viewModel.selectedApp.collectAsStateWithLifecycle()
    val whitelist by viewModel.whitelist.collectAsStateWithLifecycle()
    var search by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
        item { ScreenTitle("Journal des blocages") }
        item {
            SectionCard(contentPadding = 12.dp) {
                OutlinedTextField(
                    value = search,
                    onValueChange = {
                        search = it
                        viewModel.onSearch(it)
                    },
                    placeholder = { Text("Rechercher un domaine…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(9.dp))
                AppFilter(apps, selectedApp, viewModel::onAppSelected)
            }
        }
        val list = events
        when {
            list == null -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            list.isEmpty() -> item {
                MutedText(
                    if (search.isBlank() && selectedApp == null) {
                        "Aucune requête bloquée pour l'instant. Activez la protection puis naviguez : les blocages apparaîtront ici."
                    } else {
                        "Aucune requête bloquée avec ce filtre."
                    },
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            }
            else -> items(list, key = { it.id }) { event ->
                Column {
                    JournalRow(
                        event = event,
                        onAllow = {
                            scope.launch {
                                val result = viewModel.allow(event.domain)
                                val undo = showUndoableMessage("${event.domain} autorisé · ajouté à la liste blanche", "Annuler")
                                if (undo) viewModel.undo(result)
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                MutedText(
                    "Liste blanche : ${if (whitelist.isEmpty()) "vide" else plural(whitelist.size, "domaine")}",
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenRules) { Text("Gérer les règles") }
            }
        }
    }
}

@Composable
private fun JournalRow(event: BlockedEventEntity, onAllow: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppBadge(event.appLabel, event.appPackage)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(event.domain, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MutedText(
                    "${formatTime(event.timestamp)} · ${event.appLabel} · ${queryTypeLabel(event.queryType)} ",
                    modifier = Modifier.weight(1f, fill = false),
                )
                CategoryTag(event.category)
            }
            event.viaCname?.let { MutedText("via l'alias $it") }
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onAllow, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
            Text("Autoriser", fontSize = 12.sp)
        }
    }
}

@Composable
private fun AppFilter(apps: List<AppRef>, selected: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = apps.firstOrNull { it.appPackage == selected }?.appLabel ?: "Toutes les applications"
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Toutes les applications") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            for (app in apps) {
                DropdownMenuItem(
                    text = { Text(app.appLabel) },
                    onClick = {
                        onSelect(app.appPackage)
                        expanded = false
                    },
                )
            }
        }
    }
}
