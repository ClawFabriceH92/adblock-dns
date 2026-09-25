package io.github.clawfabriceh92.adblockdns.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.clawfabriceh92.adblockdns.core.filter.ListCategory
import io.github.clawfabriceh92.adblockdns.ui.categoryLabel
import io.github.clawfabriceh92.adblockdns.ui.theme.AdBlockTheme

/** Titre d'écran (équivalent du « h2 » de la maquette). */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 6.dp, bottom = 12.dp),
    )
}

/** Carte arrondie à bordure fine, fond « panneau ». */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, AdBlockTheme.colors.line),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = AdBlockTheme.colors.muted,
        modifier = modifier,
        textAlign = textAlign,
    )
}

/** Ligne titre + description + interrupteur, toute la ligne étant cliquable. */
@Composable
fun ToggleRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (description != null) MutedText(description)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Petite étiquette arrondie (« pub/traqueur », « active »…). */
@Composable
fun Tag(text: String, color: Color = AdBlockTheme.colors.muted, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
fun categoryColor(category: String): Color = when (category) {
    ListCategory.ADS_TRACKING.name -> AdBlockTheme.colors.ads
    ListCategory.MALWARE.name -> AdBlockTheme.colors.malware
    ListCategory.CUSTOM.name -> AdBlockTheme.colors.custom
    else -> AdBlockTheme.colors.tracking
}

@Composable
fun CategoryTag(category: String) = Tag(categoryLabel(category), categoryColor(category))

/** Pastille colorée avec l'initiale de l'application (couleur stable par application). */
@Composable
fun AppBadge(label: String, key: String?, size: Dp = 34.dp) {
    val palette = listOf(
        Color(0xFF4285F4), Color(0xFFE1306C), Color(0xFFFF0033), Color(0xFF475569),
        Color(0xFFEA4335), Color(0xFF0F766E), Color(0xFF0284C7), Color(0xFF7C3AED),
    )
    val color = palette[((key ?: label).hashCode() and Int.MAX_VALUE) % palette.size]
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** Anneau de l'écran d'accueil avec le compteur au centre. */
@Composable
fun RingCounter(
    value: String,
    caption: String,
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val animated = animateFloatAsState(targetValue = fraction, animationSpec = tween(1_100), label = "anneau")
    val track = AdBlockTheme.colors.line
    Box(modifier = modifier.size(196.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(196.dp)) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * animated.value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            MutedText(caption, textAlign = TextAlign.Center, modifier = Modifier.width(140.dp))
        }
    }
}

/** Courbe avec aire dégradée (statistiques). */
@Composable
fun LineChart(values: List<Int>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val description = "Courbe de ${values.size} points, maximum ${values.maxOrNull() ?: 0}"
    Canvas(modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = description }) {
        if (values.size < 2) return@Canvas
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1) * 1.15f
        val left = 4.dp.toPx()
        val right = size.width - 4.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 6.dp.toPx()
        fun x(i: Int) = left + i * (right - left) / (values.size - 1)
        fun y(v: Int) = bottom - (v / max) * (bottom - top)
        val line = Path().apply {
            moveTo(x(0), y(values[0]))
            for (i in 1 until values.size) lineTo(x(i), y(values[i]))
        }
        val area = Path().apply {
            addPath(line)
            lineTo(x(values.size - 1), bottom)
            lineTo(x(0), bottom)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent), startY = top, endY = bottom))
        drawPath(line, color, style = Stroke(width = 2.4.dp.toPx(), join = StrokeJoin.Round, cap = StrokeCap.Round))
    }
}

/** Barre horizontale proportionnelle (classement des applications). */
@Composable
fun HorizontalBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
            .height(9.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(color),
    )
}

/** Sélecteur à segments (« 24 h / 7 j / 30 j »), sans composant expérimental. */
@Composable
fun <T> SegmentedSelector(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((value, label) in options) {
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else AdBlockTheme.colors.panel2)
                    .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else AdBlockTheme.colors.line, RoundedCornerShape(10.dp))
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else AdBlockTheme.colors.muted,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/** Bandeau d'information ou d'alerte (DNS privé, erreur de démarrage…). */
@Composable
fun NoticeCard(title: String, text: String, color: Color, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = color)
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
            if (action != null) {
                Spacer(Modifier.height(6.dp))
                action()
            }
        }
    }
}

/** Point de couleur pour les légendes. */
@Composable
fun LegendDot(color: Color) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(color))
}
