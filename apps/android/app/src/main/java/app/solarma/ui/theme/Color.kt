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
val GradientSolana =
    listOf(
        SolanaPurple,
        SolanaGreen,
    )

val GradientSolanaPrimary =
    listOf(
        Color(0xFF9945FF),
        Color(0xFF14F195),
    )

val GradientDarkSurface =
    listOf(
        Color(0xFF1E1E1E),
        Color(0xFF121212),
    )

// ═══════════════════════════════════════════════════════════════
// LEGACY ALIASES — Deprecated, will be removed in a future version
// ═══════════════════════════════════════════════════════════════

// Background aliases
@Deprecated("Use DeepBlack directly", ReplaceWith("DeepBlack"))
val NightSky = DeepBlack

@Deprecated("Use Graphite directly", ReplaceWith("Graphite"))
val NightSkyLight = Graphite

@Deprecated("Use GraphiteSurface directly", ReplaceWith("GraphiteSurface"))
val NightSkyCard = GraphiteSurface

// Color aliases — names are misleading (SunriseOrange is actually green)
@Deprecated("Misleading name. Use SolanaGreen", ReplaceWith("SolanaGreen"))
val SunriseOrange = SolanaGreen

@Deprecated("Misleading name. Use SolanaPurple", ReplaceWith("SolanaPurple"))
val GoldenHour = SolanaPurple

@Deprecated("Use SolanaPurple directly", ReplaceWith("SolanaPurple"))
val DawnPurple = SolanaPurple

@Deprecated("Use SolanaGreen directly", ReplaceWith("SolanaGreen"))
val MorningGreen = SolanaGreen

@Deprecated("Use ErrorCrimson directly", ReplaceWith("ErrorCrimson"))
val AlertRed = ErrorCrimson

// Gradient aliases
@Deprecated("Use GradientDarkSurface directly", ReplaceWith("GradientDarkSurface"))
val GradientNight = GradientDarkSurface

@Deprecated("Use GradientSolanaPrimary directly", ReplaceWith("GradientSolanaPrimary"))
val GradientSunrise = GradientSolanaPrimary
