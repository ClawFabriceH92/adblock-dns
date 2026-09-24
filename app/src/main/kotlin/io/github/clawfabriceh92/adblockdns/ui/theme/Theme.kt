package io.github.clawfabriceh92.adblockdns.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Couleurs propres à l'application (reprises de la maquette), en plus du schéma Material 3. */
@Immutable
data class AdBlockColors(
    val ok: Color,
    val ko: Color,
    val warn: Color,
    val ads: Color,
    val tracking: Color,
    val malware: Color,
    val custom: Color,
    val muted: Color,
    val line: Color,
    val panel2: Color,
)

private val LightExtra = AdBlockColors(
    ok = Color(0xFF129D5B),
    ko = Color(0xFFDC2626),
    warn = Color(0xFFD97706),
    ads = Color(0xFF1A73E8),
    tracking = Color(0xFF8B5CF6),
    malware = Color(0xFFDC2626),
    custom = Color(0xFF0F766E),
    muted = Color(0xFF5B6472),
    line = Color(0xFFDFE3E9),
    panel2 = Color(0xFFF6F7F9),
)

private val DarkExtra = AdBlockColors(
    ok = Color(0xFF34D399),
    ko = Color(0xFFF87171),
    warn = Color(0xFFFBBF24),
    ads = Color(0xFF6AA8FF),
    tracking = Color(0xFFA78BFA),
    malware = Color(0xFFF87171),
    custom = Color(0xFF2DD4BF),
    muted = Color(0xFF8D97A8),
    line = Color(0xFF282F3B),
    panel2 = Color(0xFF1D222B),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    secondary = Color(0xFF129D5B),
    onSecondary = Color.White,
    background = Color(0xFFECEEF2),
    onBackground = Color(0xFF12161D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF12161D),
    surfaceVariant = Color(0xFFF6F7F9),
    onSurfaceVariant = Color(0xFF5B6472),
    outline = Color(0xFFDFE3E9),
    error = Color(0xFFDC2626),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6AA8FF),
    onPrimary = Color(0xFF04203F),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF04281A),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFE9EDF3),
    surface = Color(0xFF161A21),
    onSurface = Color(0xFFE9EDF3),
    surfaceVariant = Color(0xFF1D222B),
    onSurfaceVariant = Color(0xFF8D97A8),
    outline = Color(0xFF282F3B),
    error = Color(0xFFF87171),
)

val LocalAdBlockColors = staticCompositionLocalOf { DarkExtra }

/** Thème clair et sombre, qui suit le réglage du téléphone. */
@Composable
fun AdBlockTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAdBlockColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}

object AdBlockTheme {
    val colors: AdBlockColors
        @Composable get() = LocalAdBlockColors.current
}
