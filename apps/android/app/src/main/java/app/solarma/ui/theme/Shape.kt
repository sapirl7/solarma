package app.solarma.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════════════════
// SOLARMA SHAPES
// Sharp, Industrial, DeFi appearance
// ═══════════════════════════════════════════════════════════════

val SolarmaShapes = Shapes(
    // Small: Chips, small buttons
    small = RoundedCornerShape(4.dp),

    // Medium: Cards, dialogs
    medium = RoundedCornerShape(8.dp),

    // Large: Bottom sheets, full-screen cards
    large = RoundedCornerShape(12.dp),

    // Extra large: Modal sheets
    extraLarge = RoundedCornerShape(16.dp)
)

// Custom shape tokens for specific use cases
val AlarmCardShape = RoundedCornerShape(8.dp)
val TimePickerShape = RoundedCornerShape(12.dp)
val ButtonShape = RoundedCornerShape(4.dp)
val ChipShape = RoundedCornerShape(4.dp)
val BottomSheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
