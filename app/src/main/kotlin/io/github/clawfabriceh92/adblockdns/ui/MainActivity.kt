package io.github.clawfabriceh92.adblockdns.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.ContextCompat
import io.github.clawfabriceh92.adblockdns.R
import io.github.clawfabriceh92.adblockdns.appContainer
import io.github.clawfabriceh92.adblockdns.ui.screens.ExcludedAppsScreen
import io.github.clawfabriceh92.adblockdns.ui.screens.HomeScreen
import io.github.clawfabriceh92.adblockdns.ui.screens.JournalScreen
import io.github.clawfabriceh92.adblockdns.ui.screens.ListsScreen
import io.github.clawfabriceh92.adblockdns.ui.screens.RulesScreen
import io.github.clawfabriceh92.adblockdns.ui.screens.SettingsScreen
import io.github.clawfabriceh92.adblockdns.ui.screens.StatsScreen
import io.github.clawfabriceh92.adblockdns.ui.theme.AdBlockTheme
import io.github.clawfabriceh92.adblockdns.vpn.ProtectionStatus
import io.github.clawfabriceh92.adblockdns.vpn.VpnController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AdBlockTheme {
                ProtectionLauncher { onToggle -> AdBlockApp(onToggle) }
            }
        }
    }
}

/**
 * Enchaîne les autorisations avant de démarrer : notification (Android 13+, facultative) puis
 * consentement VPN d'Android, demandé une seule fois.
 */
@Composable
private fun ProtectionLauncher(content: @Composable (onToggleProtection: (Boolean) -> Unit) -> Unit) {
    val context = LocalContext.current
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            VpnController.start(context)
        } else {
            context.appContainer.protectionStatus.value =
                ProtectionStatus.Failed("Autorisation VPN refusée : la protection ne peut pas démarrer.")
        }
    }
    val requestVpn = {
        val consentIntent = VpnService.prepare(context)
        if (consentIntent != null) vpnConsent.launch(consentIntent) else VpnController.start(context)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Refus sans conséquence : la protection fonctionne, seule la notification est masquée.
        requestVpn()
    }
    content { enable ->
        if (!enable) {
            VpnController.stop(context)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpn()
        }
    }
}

enum class Tab(val label: String, @param:DrawableRes val icon: Int) {
    ACCUEIL("Accueil", R.drawable.ic_nav_accueil),
    JOURNAL("Journal", R.drawable.ic_nav_journal),
    STATS("Stats", R.drawable.ic_nav_stats),
    LISTES("Listes", R.drawable.ic_nav_listes),
    REGLAGES("Réglages", R.drawable.ic_nav_reglages),
}

enum class SubScreen { REGLES, APPLICATIONS_EXCLUES }

@Composable
fun AdBlockApp(onToggleProtection: (Boolean) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.ACCUEIL) }
    var subScreen by rememberSaveable { mutableStateOf<SubScreen?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val showMessage: suspend (String) -> Unit = { snackbar.showSnackbar(it) }
    val showUndoable: suspend (String, String) -> Boolean = { message, action ->
        snackbar.showSnackbar(message, actionLabel = action, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed
    }

    BackHandler(enabled = subScreen != null || tab != Tab.ACCUEIL) {
        if (subScreen != null) subScreen = null else tab = Tab.ACCUEIL
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                for (item in Tab.entries) {
                    NavigationBarItem(
                        selected = subScreen == null && tab == item,
                        onClick = {
                            tab = item
                            subScreen = null
                        },
                        icon = { Icon(painterResource(item.icon), contentDescription = null) },
                        label = { Text(item.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AdBlockTheme.colors.ok,
                            selectedTextColor = AdBlockTheme.colors.ok,
                            indicatorColor = AdBlockTheme.colors.ok.copy(alpha = 0.15f),
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (subScreen) {
                SubScreen.REGLES -> RulesScreen(onBack = { subScreen = null })
                SubScreen.APPLICATIONS_EXCLUES -> ExcludedAppsScreen(onBack = { subScreen = null })
                null -> when (tab) {
                    Tab.ACCUEIL -> HomeScreen(onToggleProtection = onToggleProtection, onOpenLists = { tab = Tab.LISTES })
                    Tab.JOURNAL -> JournalScreen(onOpenRules = { subScreen = SubScreen.REGLES }, showUndoableMessage = showUndoable)
                    Tab.STATS -> StatsScreen()
                    Tab.LISTES -> ListsScreen(onOpenRules = { subScreen = SubScreen.REGLES }, showMessage = showMessage)
                    Tab.REGLAGES -> SettingsScreen(onOpenExcludedApps = { subScreen = SubScreen.APPLICATIONS_EXCLUES })
                }
            }
        }
    }
}
