package app.solarma.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════
// SOLARMA DEFI PALETTE
// Serious, High-Trust, OLED Black
// ═══════════════════════════════════════════════════════════════

// Core Backgrounds
val DeepBlack = Color(0xFF000000)
val Graphite = Color(0xFF121212)
val GraphiteSurface = Color(0xFF1E1E1E)

// Primary - Solana Green (Go / Success / Active)
val SolanaGreen = Color(0xFF14F195)
val SolanaGreenDark = Color(0xFF0E8A55)

// Secondary - Solana Purple (Premium / Gradient)
val SolanaPurple = Color(0xFF9945FF)
val SolanaPurpleDark = Color(0xFF6B2FB3)

// Functional
val ErrorCrimson = Color(0xFFCF6679) // Material error-like but deeper
val WarningAmber = Color(0xFFFFAB40)

// Text
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFB0B0B0)
val TextMuted = Color(0xFF666666)

// Gradients
val GradientSolana = listOf(
    SolanaPurple,
    SolanaGreen
)

val GradientSolanaPrimary = listOf(
    Color(0xFF9945FF),
    Color(0xFF14F195)
)

val GradientDarkSurface = listOf(
    Color(0xFF1E1E1E),
    Color(0xFF121212)
)

// ═══════════════════════════════════════════════════════════════
// LEGACY ALIASES (Backward Compatibility)
// Map old names to new palette for existing components
// ═══════════════════════════════════════════════════════════════

// Background aliases
val NightSky = DeepBlack
val NightSkyLight = Graphite
val NightSkyCard = GraphiteSurface

// Color aliases
val SunriseOrange = SolanaGreen
val GoldenHour = SolanaPurple
val DawnPurple = SolanaPurple
val MorningGreen = SolanaGreen
val AlertRed = ErrorCrimson

// Gradient aliases
val GradientNight = GradientDarkSurface
val GradientSunrise = GradientSolanaPrimary
