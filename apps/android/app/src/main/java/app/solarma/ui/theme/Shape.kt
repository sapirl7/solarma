package app.solarma.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════════════════
// SOLARMA SHAPES
// Rounded, modern, friendly appearance
// ═══════════════════════════════════════════════════════════════

val SolarmaShapes = Shapes(
    // Small: Chips, small buttons
    small = RoundedCornerShape(8.dp),
    
    // Medium: Cards, dialogs
    medium = RoundedCornerShape(16.dp),
    
    // Large: Bottom sheets, full-screen cards
    large = RoundedCornerShape(24.dp),
    
    // Extra large: Modal sheets
    extraLarge = RoundedCornerShape(32.dp)
)

// Custom shape tokens for specific use cases
val AlarmCardShape = RoundedCornerShape(20.dp)
val TimePickerShape = RoundedCornerShape(28.dp)
val ButtonShape = RoundedCornerShape(12.dp)
val ChipShape = RoundedCornerShape(8.dp)
val BottomSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
