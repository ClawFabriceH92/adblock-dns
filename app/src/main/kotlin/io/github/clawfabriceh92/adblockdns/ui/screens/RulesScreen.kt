package io.github.clawfabriceh92.adblockdns.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.clawfabriceh92.adblockdns.data.db.RuleEntity
import io.github.clawfabriceh92.adblockdns.ui.AppViewModelFactory
import io.github.clawfabriceh92.adblockdns.ui.RulesViewModel
import io.github.clawfabriceh92.adblockdns.ui.components.CardTitle
import io.github.clawfabriceh92.adblockdns.ui.components.MutedText
import io.github.clawfabriceh92.adblockdns.ui.components.ScreenTitle
import io.github.clawfabriceh92.adblockdns.ui.components.SectionCard
import io.github.clawfabriceh92.adblockdns.ui.plural
import kotlinx.coroutines.launch

/** Listes blanche et noire manuelles : toute règle est visible, ajoutable et réversible en un tap. */
@Composable
fun RulesScreen(onBack: () -> Unit, viewModel: RulesViewModel = viewModel(factory = AppViewModelFactory)) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        TextButton(onClick = onBack) { Text("‹ Retour") }
        ScreenTitle("Règles manuelles")
        MutedText(
            "« exemple.com » couvre le domaine et tous ses sous-domaines. Jokers acceptés : " +
                "*.exemple.com (sous-domaines seulement), pub*.exemple.com. Une URL collée est ramenée à son domaine. " +
                "La liste blanche est toujours prioritaire.",
            modifier = Modifier.padding(bottom = 12.dp),
        )
        RuleSection(
            title = "Liste blanche",
            description = "Domaines toujours autorisés (réparer un site cassé)",
            type = RuleEntity.ALLOW,
            rules = rules.filter { it.type == RuleEntity.ALLOW },
            viewModel = viewModel,
        )
        RuleSection(
            title = "Liste noire",
            description = "Domaines toujours bloqués, en plus des listes",
            type = RuleEntity.BLOCK,
            rules = rules.filter { it.type == RuleEntity.BLOCK },
            viewModel = viewModel,
        )
    }
}

@Composable
private fun RuleSection(title: String, description: String, type: String, rules: List<RuleEntity>, viewModel: RulesViewModel) {
    var input by rememberSaveable(type) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val submit: () -> Unit = {
        val text = input
        if (text.isNotBlank()) {
            scope.launch {
                error = viewModel.add(text, type)
                if (error == null) input = ""
            }
        }
    }

    SectionCard {
        CardTitle("$title · ${plural(rules.size, "règle")}")
        MutedText(description)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    error = null
                },
                placeholder = { Text("exemple.com") },
                singleLine = true,
                isError = error != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = submit) { Text("Ajouter") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(6.dp))
        if (rules.isEmpty()) MutedText("Aucune règle.")
        for (rule in rules) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.pattern, style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = { viewModel.remove(rule) }) { Text("Retirer") }
            }
            HorizontalDivider()
        }
    }
}
