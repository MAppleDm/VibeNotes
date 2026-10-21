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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mappledm.vibenotes.data.Palette
import io.github.mappledm.vibenotes.data.ThemeMode

// "Лимон и кофе": Lemon Zest accents on Cappuccino / Espresso backgrounds.
// References: https://www.color-name.com/lemon-zest.color
// https://github.com/breakthebull/cappuccino#-two-roasts-one-experience
private val LemonZest = Color(0xFFF9D857)
private val Cappuccino = Color(0xFFF5F0E8)
private val Espresso = Color(0xFF2E1F16)

// Against the page background: body ≥ 15:1, secondary text ≥ 7:1,
// outlines and Markdown syntax ≥ 4:1. Light-theme links stay dark for readability.
private val LemonCoffeeLight = lightColorScheme(
    primary = Color(0xFF5A3B22),
    onPrimary = Color.White,
    primaryContainer = LemonZest,
    onPrimaryContainer = Color(0xFF2A1C0C),
    secondary = Color(0xFF6A5641),
    onSecondary = Color.White,
    secondaryContainer = LemonZest,
    onSecondaryContainer = Color(0xFF2A1C0C),
    tertiary = Color(0xFF6E5600),
    onTertiary = Color.White,
    tertiaryContainer = LemonZest,
    onTertiaryContainer = Color(0xFF241B00),
    background = Cappuccino,
    onBackground = Color(0xFF1C1510),
    surface = Cappuccino,
    onSurface = Color(0xFF1C1510),
    surfaceVariant = Color(0xFFF2EADC),
    onSurfaceVariant = Color(0xFF54473A),
    surfaceContainerLowest = Color(0xFFFFFCF6),
    surfaceContainerLow = Cappuccino,
    surfaceContainer = Color(0xFFF2EADC),
    surfaceContainerHigh = Color(0xFFEFE6D6),
    surfaceContainerHighest = Color(0xFFE9DECA),
    outline = Color(0xFF6A5641),
    outlineVariant = Color(0xFFE2D7C4),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val LemonCoffeeDark = darkColorScheme(
    primary = LemonZest,
    onPrimary = Color(0xFF2E2200),
    primaryContainer = LemonZest,
    onPrimaryContainer = Color(0xFF2A1C0C),
    secondary = Color(0xFFE3D0B5),
    onSecondary = Color(0xFF3A2C1A),
    secondaryContainer = LemonZest,
    onSecondaryContainer = Color(0xFF2A1C0C),
    tertiary = LemonZest,
    onTertiary = Color(0xFF3A2E00),
    tertiaryContainer = Color(0xFF4A3720),
    onTertiaryContainer = LemonZest,
    background = Espresso,
    onBackground = Color(0xFFFFFCF6),
    surface = Espresso,
    onSurface = Color(0xFFFFFCF6),
    surfaceVariant = Color(0xFF3A2F26),
    onSurfaceVariant = Color(0xFFE3D0B5),
    surfaceContainerLowest = Color(0xFF1A120D),
    surfaceContainerLow = Espresso,
    surfaceContainer = Color(0xFF372D25),
    surfaceContainerHigh = Color(0xFF3A2F26),
    surfaceContainerHighest = Color(0xFF4B3B26),
    outline = Color(0xFFA39079),
    outlineVariant = Color(0xFF3D332A),
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

/** [AppTypography] set in [family]; the system font keeps Material's own defaults. */
private fun typographyFor(family: FontFamily): Typography {
    if (family == FontFamily.Default) return AppTypography
    fun TextStyle.inFamily() = copy(fontFamily = family)
    return with(AppTypography) {
        Typography(
            displayLarge = displayLarge.inFamily(),
            displayMedium = displayMedium.inFamily(),
            displaySmall = displaySmall.inFamily(),
            headlineLarge = headlineLarge.inFamily(),
            headlineMedium = headlineMedium.inFamily(),
            headlineSmall = headlineSmall.inFamily(),
            titleLarge = titleLarge.inFamily(),
            titleMedium = titleMedium.inFamily(),
            titleSmall = titleSmall.inFamily(),
            bodyLarge = bodyLarge.inFamily(),
            bodyMedium = bodyMedium.inFamily(),
            bodySmall = bodySmall.inFamily(),
            labelLarge = labelLarge.inFamily(),
            labelMedium = labelMedium.inFamily(),
            labelSmall = labelSmall.inFamily(),
        )
    }
}

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
fun VibeTheme(
    mode: ThemeMode,
    palette: Palette = Palette.LEMON_COFFEE,
    fontFamily: FontFamily = GolosText,
    content: @Composable () -> Unit,
) {
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
            ExtraColors(syntax = Color(0xFFA39079), codeBackground = Color(0xFF3A2F26), success = Color(0xFFB9DA80), warning = LemonZest)
        palette == Palette.LEMON_COFFEE ->
            ExtraColors(syntax = Color(0xFF6A5641), codeBackground = Color(0xFFF3ECDF), success = Color(0xFF3F6B1F), warning = Color(0xFF7A5C00))
        dark ->
            ExtraColors(syntax = Color(0xFF6E717B), codeBackground = Color(0xFF1E2024), success = Color(0xFF7FD3A8), warning = Color(0xFFE8C26A))
        else ->
            ExtraColors(syntax = Color(0xFFA3A5AD), codeBackground = Color(0xFFF0F0EC), success = Color(0xFF2E7D5B), warning = Color(0xFF9A6B00))
    }
    val typography = remember(fontFamily) { typographyFor(fontFamily) }
    androidx.compose.runtime.CompositionLocalProvider(LocalExtraColors provides extra) {
        MaterialTheme(colorScheme = colors, shapes = AppShapes, typography = typography, content = content)
    }
}
