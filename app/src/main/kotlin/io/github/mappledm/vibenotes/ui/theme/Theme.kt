package io.github.mappledm.vibenotes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mappledm.vibenotes.data.Palette
import io.github.mappledm.vibenotes.data.ThemeMode

// "Лимон и кофе": cream paper, coffee ink, lemon highlights. The default palette.
private val LemonCoffeeLight = lightColorScheme(
    primary = Color(0xFF6B4A2F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBE38E),
    onPrimaryContainer = Color(0xFF3B2A12),
    secondary = Color(0xFF7A6448),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7E07A),
    onSecondaryContainer = Color(0xFF3B2A12),
    tertiary = Color(0xFF8C6D00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF1B0),
    onTertiaryContainer = Color(0xFF2E2400),
    background = Color(0xFFFBF6EC),
    onBackground = Color(0xFF2A211A),
    surface = Color(0xFFFBF6EC),
    onSurface = Color(0xFF2A211A),
    surfaceVariant = Color(0xFFF0E7D6),
    onSurfaceVariant = Color(0xFF6E6152),
    surfaceContainerLowest = Color(0xFFFFFDF7),
    surfaceContainerLow = Color(0xFFF6EEDF),
    surfaceContainer = Color(0xFFF1E8D6),
    surfaceContainerHigh = Color(0xFFEBE0CB),
    surfaceContainerHighest = Color(0xFFE4D8C0),
    outline = Color(0xFFCBBBA3),
    outlineVariant = Color(0xFFE5D9C5),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val LemonCoffeeDark = darkColorScheme(
    primary = Color(0xFFF5D96B),
    onPrimary = Color(0xFF3A2C00),
    primaryContainer = Color(0xFF5A4422),
    onPrimaryContainer = Color(0xFFFCE9B5),
    secondary = Color(0xFFD9C3A3),
    onSecondary = Color(0xFF3B2C1A),
    secondaryContainer = Color(0xFF4D3B20),
    onSecondaryContainer = Color(0xFFF7E3A6),
    tertiary = Color(0xFFE8C66A),
    onTertiary = Color(0xFF3B2F00),
    tertiaryContainer = Color(0xFF4F4100),
    onTertiaryContainer = Color(0xFFFFEFB0),
    background = Color(0xFF1C1612),
    onBackground = Color(0xFFEFE5D6),
    surface = Color(0xFF1C1612),
    onSurface = Color(0xFFEFE5D6),
    surfaceVariant = Color(0xFF332A22),
    onSurfaceVariant = Color(0xFFBBAE9C),
    surfaceContainerLowest = Color(0xFF15100D),
    surfaceContainerLow = Color(0xFF231C17),
    surfaceContainer = Color(0xFF29211B),
    surfaceContainerHigh = Color(0xFF312821),
    surfaceContainerHighest = Color(0xFF3A3027),
    outline = Color(0xFF5E5043),
    outlineVariant = Color(0xFF3B3128),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF5C1D19),
    onErrorContainer = Color(0xFFF9DEDC),
)

// "Графит": calm neutrals with a single indigo accent.
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F55C9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E7FB),
    onPrimaryContainer = Color(0xFF14204F),
    secondary = Color(0xFF5B5F6B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E9E6),
    onSecondaryContainer = Color(0xFF1B1C1F),
    tertiary = Color(0xFF2E7D5B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCEFE5),
    onTertiaryContainer = Color(0xFF0C2A1D),
    background = Color(0xFFFAFAF8),
    onBackground = Color(0xFF1B1C1E),
    surface = Color(0xFFFAFAF8),
    onSurface = Color(0xFF1B1C1E),
    surfaceVariant = Color(0xFFEFEFEC),
    onSurfaceVariant = Color(0xFF5E6067),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F5F2),
    surfaceContainer = Color(0xFFF0F0ED),
    surfaceContainerHigh = Color(0xFFEAEAE7),
    surfaceContainerHighest = Color(0xFFE4E4E1),
    outline = Color(0xFFC9C9C4),
    outlineVariant = Color(0xFFE2E2DE),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9AAAFF),
    onPrimary = Color(0xFF13205A),
    primaryContainer = Color(0xFF2B3A86),
    onPrimaryContainer = Color(0xFFE0E5FF),
    secondary = Color(0xFFC3C5CE),
    onSecondary = Color(0xFF2C2F36),
    secondaryContainer = Color(0xFF2E3036),
    onSecondaryContainer = Color(0xFFE3E4EA),
    tertiary = Color(0xFF7FD3A8),
    onTertiary = Color(0xFF00391F),
    tertiaryContainer = Color(0xFF1C3D2D),
    onTertiaryContainer = Color(0xFFCDEEDC),
    background = Color(0xFF131417),
    onBackground = Color(0xFFE5E5E8),
    surface = Color(0xFF131417),
    onSurface = Color(0xFFE5E5E8),
    surfaceVariant = Color(0xFF24262B),
    onSurfaceVariant = Color(0xFFA9ABB3),
    surfaceContainerLowest = Color(0xFF0E0F11),
    surfaceContainerLow = Color(0xFF18191C),
    surfaceContainer = Color(0xFF1C1D21),
    surfaceContainerHigh = Color(0xFF232429),
    surfaceContainerHighest = Color(0xFF2A2C31),
    outline = Color(0xFF45474E),
    outlineVariant = Color(0xFF30323A),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF5C1D19),
    onErrorContainer = Color(0xFFF9DEDC),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

private val base = Typography()

private val AppTypography = Typography(
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
)

/** Colors the theme does not cover: Markdown syntax in the editor, success states. */
@Immutable
data class ExtraColors(
    val syntax: Color,
    val codeBackground: Color,
    val success: Color,
    val warning: Color,
)

val LocalExtraColors = staticCompositionLocalOf {
    ExtraColors(Color.Gray, Color.LightGray, Color.Green, Color.Yellow)
}

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp)

@Composable
fun VibeTheme(mode: ThemeMode, palette: Palette = Palette.LEMON_COFFEE, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors: ColorScheme = when (palette) {
        Palette.LEMON_COFFEE -> if (dark) LemonCoffeeDark else LemonCoffeeLight
        Palette.GRAPHITE -> if (dark) DarkColors else LightColors
    }
    val extra = when {
        palette == Palette.LEMON_COFFEE && dark ->
            ExtraColors(syntax = Color(0xFF7D6F60), codeBackground = Color(0xFF2A221C), success = Color(0xFFB5D27A), warning = Color(0xFFF5D96B))
        palette == Palette.LEMON_COFFEE ->
            ExtraColors(syntax = Color(0xFFB2A38C), codeBackground = Color(0xFFF1E8D6), success = Color(0xFF5E7F2E), warning = Color(0xFF8C6D00))
        dark ->
            ExtraColors(syntax = Color(0xFF6E717B), codeBackground = Color(0xFF1E2024), success = Color(0xFF7FD3A8), warning = Color(0xFFE8C26A))
        else ->
            ExtraColors(syntax = Color(0xFFA3A5AD), codeBackground = Color(0xFFF0F0EC), success = Color(0xFF2E7D5B), warning = Color(0xFF9A6B00))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(colorScheme = colors, shapes = AppShapes, typography = AppTypography, content = content)
    }
}
