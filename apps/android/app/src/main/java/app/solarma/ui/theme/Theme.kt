package app.solarma.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════════
// SOLARMA PREMIUM THEME
// Dark-first design for alarm apps (used at night)
// ═══════════════════════════════════════════════════════════════

private val DarkColorScheme = darkColorScheme(
    primary = SunriseOrange,
    onPrimary = Color.White,
    primaryContainer = SunriseOrangeDark,
    onPrimaryContainer = Color.White,
    
    secondary = GoldenHour,
    onSecondary = Color.Black,
    secondaryContainer = GoldenHourDark,
    onSecondaryContainer = Color.White,
    
    tertiary = DawnPurple,
    onTertiary = Color.White,
    tertiaryContainer = DawnPurpleDark,
    onTertiaryContainer = Color.White,
    
    error = AlertRed,
    onError = Color.White,
    errorContainer = AlertRedLight,
    onErrorContainer = Color.White,
    
    background = NightSky,
    onBackground = TextPrimary,
    
    surface = NightSkyLight,
    onSurface = TextPrimary,
    
    surfaceVariant = NightSkyCard,
    onSurfaceVariant = TextSecondary,
    
    outline = Color(0x33FFFFFF),
    outlineVariant = Color(0x1AFFFFFF),
)

private val LightColorScheme = lightColorScheme(
    primary = SunriseOrange,
    onPrimary = Color.White,
    primaryContainer = SunriseOrangeLight,
    onPrimaryContainer = Color.Black,
    
    secondary = GoldenHour,
    onSecondary = Color.Black,
    secondaryContainer = GoldenHourLight,
    onSecondaryContainer = Color.Black,
    
    tertiary = DawnPurple,
    onTertiary = Color.White,
    tertiaryContainer = DawnPurpleLight,
    onTertiaryContainer = Color.Black,
    
    error = AlertRed,
    onError = Color.White,
    
    background = DaySky,
    onBackground = TextOnLight,
    
    surface = DaySkySurface,
    onSurface = TextOnLight,
    
    surfaceVariant = DaySkyCard,
    onSurfaceVariant = TextOnLightSecondary,
    
    outline = Color(0x33000000),
    outlineVariant = Color(0x1A000000),
)

@Composable
fun SolarmaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Disable dynamic colors to ensure brand consistency
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        // Only use dynamic colors if explicitly enabled
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SolarmaTypography,
        shapes = SolarmaShapes,
        content = content
    )
}
