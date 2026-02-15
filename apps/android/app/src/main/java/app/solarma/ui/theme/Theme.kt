package app.solarma.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════
// SOLARMA DEFI THEME
// Forced Dark Mode for Serious Financial Vibes
// ═══════════════════════════════════════════════════════════════

private val DeFiColorScheme =
    darkColorScheme(
        primary = SolanaGreen,
        onPrimary = DeepBlack,
        primaryContainer = SolanaGreenDark,
        onPrimaryContainer = Color.White,
        secondary = SolanaPurple,
        onSecondary = Color.White,
        secondaryContainer = SolanaPurpleDark,
        onSecondaryContainer = Color.White,
        tertiary = GraphiteSurface,
        onTertiary = TextPrimary,
        tertiaryContainer = Graphite,
        onTertiaryContainer = TextPrimary,
        error = ErrorCrimson,
        onError = Color.White,
        errorContainer = ErrorCrimson,
        onErrorContainer = Color.White,
        background = DeepBlack,
        onBackground = TextPrimary,
        surface = Graphite,
        onSurface = TextPrimary,
        surfaceVariant = GraphiteSurface,
        onSurfaceVariant = TextSecondary,
        outline = Color(0x33FFFFFF),
        outlineVariant = Color(0x1AFFFFFF),
    )

@Composable
fun SolarmaTheme(
    // Force Dark Theme for DeFi look
    darkTheme: Boolean = true,
    // Disable dynamic colors to ensure brand consistency
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Always use DeFiColorScheme (Dark)
    val colorScheme = DeFiColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SolarmaTypography,
        shapes = SolarmaShapes,
        content = content,
    )
}
