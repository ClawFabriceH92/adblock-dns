package io.github.clawfabriceh92.adblockdns.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.clawfabriceh92.adblockdns.BuildConfig
import io.github.clawfabriceh92.adblockdns.data.JournalRepository
import io.github.clawfabriceh92.adblockdns.data.UpstreamChoice
import io.github.clawfabriceh92.adblockdns.notification.Notifications
import io.github.clawfabriceh92.adblockdns.ui.AppViewModelFactory
import io.github.clawfabriceh92.adblockdns.ui.SettingsViewModel
import io.github.clawfabriceh92.adblockdns.ui.components.CardTitle
import io.github.clawfabriceh92.adblockdns.ui.components.MutedText
import io.github.clawfabriceh92.adblockdns.ui.components.ScreenTitle
import io.github.clawfabriceh92.adblockdns.ui.components.SectionCard
import io.github.clawfabriceh92.adblockdns.ui.components.Tag
import io.github.clawfabriceh92.adblockdns.ui.components.ToggleRow
import io.github.clawfabriceh92.adblockdns.ui.formatBytes
import io.github.clawfabriceh92.adblockdns.ui.plural
import android.provider.Settings as AndroidSettings

@Composable
fun SettingsScreen(
    onOpenExcludedApps: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelFactory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val excluded by viewModel.excludedLabels.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmPurge by remember { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ScreenTitle("Réglages")

        SectionCard {
            CardTitle("Résolveur DNS amont")
            MutedText("Serveur interrogé pour les domaines autorisés")
            Spacer(Modifier.height(6.dp))
            for (choice in UpstreamChoice.entries) {
                Row(
                    Modifier.fillMaxWidth().clickable { viewModel.setUpstream(choice) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.upstream == choice, onClick = { viewModel.setUpstream(choice) })
                    Column(Modifier.weight(1f)) {
                        Text(choice.label)
                        MutedText(choice.detail)
                    }
                }
            }
            MutedText(
                "Les résolveurs chiffrés sont des services tiers : ils voient les domaines autorisés que vous consultez.",
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        SectionCard {
            ToggleRow(
                "Démarrage au boot",
                "Relancer la protection après un redémarrage du téléphone",
                settings.startOnBoot,
                viewModel::setStartOnBoot,
            )
            HorizontalDivider()
            ToggleRow(
                "Anti-camouflage CNAME",
                "Bloque aussi un domaine dont l'alias (CNAME) mène à un traqueur connu",
                settings.cnameInspection,
                viewModel::setCnameInspection,
            )
            HorizontalDivider()
            ToggleRow(
                "Mise à jour automatique des listes",
                "Une fois par semaine, en Wi-Fi uniquement",
                settings.autoUpdateLists,
                viewModel::setAutoUpdate,
            )
        }

        SectionCard {
            CardTitle("Applications exclues du tunnel")
            MutedText("Pour les applications qui refusent de fonctionner derrière un VPN (banque, streaming, jeux). Leurs requêtes ne sont ni filtrées ni journalisées.")
            Spacer(Modifier.height(6.dp))
            if (excluded.isEmpty()) MutedText("Aucune application exclue.")
            for (label in excluded) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f))
                    Tag("exclue")
                }
            }
            OutlinedButton(onClick = onOpenExcludedApps, modifier = Modifier.padding(top = 8.dp)) { Text("Choisir les applications") }
        }

        SectionCard {
            CardTitle("VPN permanent d'Android")
            MutedText(
                "Plus fiable que le démarrage au boot : Android relance lui-même la protection. Dans les réglages VPN, " +
                    "touchez la roue à côté de « Bloqueur DNS » puis activez « VPN permanent ». N'activez pas " +
                    "« Bloquer les connexions sans VPN » : seul le DNS passe par ce tunnel, tout le reste serait bloqué.",
            )
            OutlinedButton(onClick = { context.openSafely(Intent(AndroidSettings.ACTION_VPN_SETTINGS)) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Ouvrir les réglages VPN")
            }
        }

        SectionCard {
            CardTitle("Notification permanente")
            MutedText("Android l'exige tant que le filtrage tourne. Vous pouvez la réduire ou la masquer dans ses réglages.")
            OutlinedButton(
                onClick = {
                    context.openSafely(
                        Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(AndroidSettings.EXTRA_CHANNEL_ID, Notifications.CHANNEL_PROTECTION),
                    )
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Réglages de la notification") }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    CardTitle("Historique du journal")
                    MutedText("${plural(storage.entries, "requête")} · ${formatBytes(storage.bytes)} · conservé ${JournalRepository.RETENTION_DAYS} jours")
                }
                OutlinedButton(onClick = { confirmPurge = true }) { Text("Purger") }
            }
        }

        SectionCard {
            CardTitle("Bloqueur DNS")
            MutedText("Version ${BuildConfig.VERSION_NAME} · aucun compte, aucune statistique d'usage : rien n'est envoyé en dehors du téléchargement des listes et des requêtes DNS autorisées.")
            MutedText("Listes : HaGeZi (GPL-3.0), StevenBlack (MIT).", modifier = Modifier.padding(top = 4.dp))
        }
    }

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            title = { Text("Purger le journal ?") },
            text = { Text("Toutes les entrées du journal et les statistiques seront effacées. Les règles et les listes sont conservées.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.purgeJournal()
                    confirmPurge = false
                }) { Text("Purger") }
            },
            dismissButton = { TextButton(onClick = { confirmPurge = false }) { Text("Annuler") } },
        )
    }
}

private fun Context.openSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Écran absent sur cette version d'Android : rien à faire.
    }
}
