package io.github.clawfabriceh92.adblockdns.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.clawfabriceh92.adblockdns.ui.AppViewModelFactory
import io.github.clawfabriceh92.adblockdns.ui.StatsPeriod
import io.github.clawfabriceh92.adblockdns.ui.StatsViewModel
import io.github.clawfabriceh92.adblockdns.ui.categoryTitle
import io.github.clawfabriceh92.adblockdns.ui.components.CardTitle
import io.github.clawfabriceh92.adblockdns.ui.components.HorizontalBar
import io.github.clawfabriceh92.adblockdns.ui.components.LegendDot
import io.github.clawfabriceh92.adblockdns.ui.components.LineChart
import io.github.clawfabriceh92.adblockdns.ui.components.MutedText
import io.github.clawfabriceh92.adblockdns.ui.components.ScreenTitle
import io.github.clawfabriceh92.adblockdns.ui.components.SectionCard
import io.github.clawfabriceh92.adblockdns.ui.components.SegmentedSelector
import io.github.clawfabriceh92.adblockdns.ui.components.categoryColor
import io.github.clawfabriceh92.adblockdns.ui.formatCount
import io.github.clawfabriceh92.adblockdns.ui.plural
import io.github.clawfabriceh92.adblockdns.ui.theme.AdBlockTheme
import kotlin.math.roundToInt

@Composable
fun StatsScreen(viewModel: StatsViewModel = viewModel(factory = AppViewModelFactory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ScreenTitle("Statistiques")
        SegmentedSelector(StatsPeriod.entries.map { it to it.label }, state.period, viewModel::select)

        SectionCard {
            CardTitle(if (state.period == StatsPeriod.DAY) "Blocages par heure · 24 h" else "Blocages par jour · ${state.period.label}")
            MutedText("${plural(state.total, "blocage")} sur la période")
            Spacer(Modifier.height(8.dp))
            LineChart(state.series)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                MutedText(state.firstLabel)
                Spacer(Modifier.weight(1f))
                MutedText(state.lastLabel)
            }
        }

        SectionCard {
            CardTitle("Applications les plus bloquées")
            Spacer(Modifier.height(6.dp))
            if (state.topApps.isEmpty()) MutedText("Pas encore de données sur cette période.")
            val max = state.topApps.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
            for (app in state.topApps) {
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(app.appLabel, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
                    Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        HorizontalBar(app.count.toFloat() / max, AdBlockTheme.colors.ads)
                    }
                    MutedText(formatCount(app.count))
                }
            }
        }

        SectionCard {
            CardTitle("Répartition par catégorie")
            MutedText("Selon la liste qui a bloqué : les listes hagezi ne séparent pas publicité et traçage.")
            val total = state.categories.sumOf { it.count }
            if (total == 0) {
                MutedText("Pas encore de données sur cette période.", modifier = Modifier.padding(top = 6.dp))
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp).height(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    for (category in state.categories.filter { it.count > 0 }) {
                        Box(
                            Modifier
                                .weight(category.count.toFloat())
                                .height(14.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(categoryColor(category.category)),
                        )
                    }
                }
                for (category in state.categories) {
                    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        LegendDot(categoryColor(category.category))
                        Spacer(Modifier.width(6.dp))
                        MutedText("${categoryTitle(category.category)} · ${(category.count * 100f / total).roundToInt()} % (${formatCount(category.count)})")
                    }
                }
            }
        }
    }
}
