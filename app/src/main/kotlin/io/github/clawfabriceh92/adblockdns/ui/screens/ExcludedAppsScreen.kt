package io.github.clawfabriceh92.adblockdns.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.clawfabriceh92.adblockdns.ui.AppViewModelFactory
import io.github.clawfabriceh92.adblockdns.ui.ExcludedAppsViewModel
import io.github.clawfabriceh92.adblockdns.ui.components.MutedText
import io.github.clawfabriceh92.adblockdns.ui.components.ScreenTitle
import io.github.clawfabriceh92.adblockdns.ui.components.ToggleRow

@Composable
fun ExcludedAppsScreen(onBack: () -> Unit, viewModel: ExcludedAppsViewModel = viewModel(factory = AppViewModelFactory)) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val excluded by viewModel.excluded.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
        item {
            Column {
                TextButton(onClick = onBack) { Text("‹ Retour") }
                ScreenTitle("Applications exclues")
                MutedText(
                    "Les applications cochées sortent du tunnel : leurs requêtes ne sont ni filtrées ni journalisées. " +
                        "Le changement s'applique tout de suite, sans couper la protection.",
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Rechercher une application…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ToggleRow("Afficher les applications système", null, showSystem, { showSystem = it })
            }
        }
        val list = apps
        if (list == null) {
            item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else {
            val visible = list.filter { app ->
                (showSystem || !app.isSystem || app.packageName in excluded) &&
                    (query.isBlank() || app.label.contains(query, ignoreCase = true) || app.packageName.contains(query, ignoreCase = true))
            }
            items(visible, key = { it.packageName }) { app ->
                val checked = app.packageName in excluded
                Row(
                    Modifier.fillMaxWidth().clickable { viewModel.setExcluded(app.packageName, !checked) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { viewModel.setExcluded(app.packageName, it) })
                    Column(Modifier.weight(1f)) {
                        Text(app.label)
                        MutedText(app.packageName)
                    }
                }
            }
        }
    }
}
