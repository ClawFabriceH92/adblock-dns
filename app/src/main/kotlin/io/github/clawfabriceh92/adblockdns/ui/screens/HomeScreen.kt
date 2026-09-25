package io.github.clawfabriceh92.adblockdns.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.clawfabriceh92.adblockdns.data.BlocklistCatalog
import io.github.clawfabriceh92.adblockdns.ui.AppViewModelFactory
import io.github.clawfabriceh92.adblockdns.ui.HomeViewModel
import io.github.clawfabriceh92.adblockdns.ui.components.MutedText
import io.github.clawfabriceh92.adblockdns.ui.components.NoticeCard
import io.github.clawfabriceh92.adblockdns.ui.components.RingCounter
import io.github.clawfabriceh92.adblockdns.ui.components.ScreenTitle
import io.github.clawfabriceh92.adblockdns.ui.components.SectionCard
import io.github.clawfabriceh92.adblockdns.ui.components.ToggleRow
import io.github.clawfabriceh92.adblockdns.ui.formatCount
import io.github.clawfabriceh92.adblockdns.ui.formatDate
import io.github.clawfabriceh92.adblockdns.ui.theme.AdBlockTheme
import io.github.clawfabriceh92.adblockdns.vpn.ProtectionStatus

@Composable
fun HomeScreen(
    onToggleProtection: (Boolean) -> Unit,
    onOpenLists: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelFactory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AdBlockTheme.colors
    val running = state.status is ProtectionStatus.Running
    val starting = state.status is ProtectionStatus.Starting

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ScreenTitle(
            when {
                running -> "Protection active"
                starting -> "Démarrage…"
                else -> "Protection arrêtée"
            },
        )

        (state.status as? ProtectionStatus.Failed)?.let { failed ->
            NoticeCard("Protection interrompue", failed.reason, colors.ko)
        }
        state.strictPrivateDns?.let { host ->
            NoticeCard(
                title = "DNS privé d'Android actif",
                text = "Le DNS privé est réglé sur « $host ». Dans ce mode, les applications peuvent envoyer " +
                    "leurs requêtes directement à ce serveur, sans passer par le filtre. Si les publicités ne " +
                    "sont pas bloquées, désactivez-le : Réglages Android › Réseau › DNS privé.",
                color = colors.warn,
            )
        }
        if (!state.engine.ready) {
            NoticeCard("Préparation", "Chargement de la liste embarquée…", colors.ads)
        } else if (state.engine.listEntries + state.engine.blockRules == 0) {
            NoticeCard(
                title = "Aucune règle de blocage",
                text = "Aucune liste n'est chargée : rien n'est bloqué. Ouvrez l'onglet Listes pour activer " +
                    "ou mettre à jour une liste.",
                color = colors.warn,
            )
        }

        SectionCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                RingCounter(
                    value = formatCount(state.blockedToday),
                    caption = "requêtes bloquées aujourd'hui",
                    fraction = if (running) 1f else 0f,
                    color = if (running) colors.ok else colors.ko,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                Text(
                    if (running) "Tunnel DNS local en service" else "Aucun filtrage en cours",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = if (running) MaterialTheme.colorScheme.onSurface else colors.ko,
                )
                MutedText(
                    if (running) {
                        "Trafic relayé normalement · seules les requêtes DNS sont filtrées"
                    } else {
                        "Les publicités et les traqueurs passent à nouveau"
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                Button(
                    onClick = { onToggleProtection(!running) },
                    enabled = !starting,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running) colors.ko else colors.ok,
                        contentColor = if (running) Color.White else Color(0xFF04281A),
                    ),
                ) {
                    Text(if (running) "Arrêter la protection" else "Activer la protection", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            StatTile(formatCount(state.appsToday), "applications concernées aujourd'hui", Modifier.weight(1f))
            StatTile(
                formatCount(state.engine.listEntries + state.engine.blockRules),
                "règles de blocage chargées",
                Modifier.weight(1f),
            )
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(BlocklistCatalog.HAGEZI_PRO.name, fontWeight = FontWeight.Medium)
                    val list = state.mainList
                    MutedText(
                        when {
                            state.updatingLists -> "Mise à jour en cours…"
                            list?.installedAt != null -> "Liste par défaut · installée le ${formatDate(list.installedAt)}"
                            else -> "Liste par défaut"
                        },
                    )
                }
                OutlinedButton(onClick = onOpenLists) { Text("Voir") }
            }
            Spacer(Modifier.height(6.dp))
            MutedText(
                if (running) {
                    "Bloque publicités, traqueurs et une partie des domaines malveillants"
                } else {
                    "Liste en pause : la protection est arrêtée"
                },
            )
        }

        SectionCard {
            ToggleRow(
                title = "Démarrage au boot",
                description = "Relancer la protection après un redémarrage du téléphone",
                checked = state.startOnBoot,
                onCheckedChange = viewModel::setStartOnBoot,
            )
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(AdBlockTheme.colors.panel2)
            .padding(12.dp),
    ) {
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        MutedText(label)
    }
}
