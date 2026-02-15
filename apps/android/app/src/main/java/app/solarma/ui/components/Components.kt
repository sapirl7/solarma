package app.solarma.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.solarma.ui.theme.*

// ═══════════════════════════════════════════════════════════════
// SOLARMA PREMIUM UI COMPONENTS
// ═══════════════════════════════════════════════════════════════

/**
 * Full-screen gradient background used as the root container for all Solarma screens.
 *
 * Uses [GradientDarkSurface] to create a dark-to-darker vertical gradient
 * consistent with the Solana brand palette.
 *
 * @param modifier optional modifier for the outer [Box]
 * @param content composable content rendered on top of the gradient
 */
@Composable
fun SolarmaBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(GradientDarkSurface)
            ),
        content = content
    )
}

/**
 * Premium gradient button with press animation and loading state.
 *
 * Draws a horizontal gradient inside a [Button], with a spring scale
 * animation that shrinks slightly when disabled. Supports an inline
 * [CircularProgressIndicator] for asynchronous operations.
 *
 * @param text label displayed inside the button
 * @param onClick callback invoked when the button is clicked
 * @param modifier optional modifier
 * @param enabled whether the button accepts clicks (default `true`)
 * @param isLoading show a spinner instead of [text] (default `false`)
 * @param gradient colors for the horizontal gradient fill
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    gradient: List<Color> = GradientSolanaPrimary
) {
    val scale by animateFloatAsState(
        targetValue = if (enabled && !isLoading) 1f else 0.95f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "button_scale"
    )

    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier
            .scale(scale)
            .height(56.dp),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.horizontalGradient(
                        if (enabled) gradient else listOf(Color.Gray, Color.DarkGray)
                    ),
                    shape = ButtonShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Animated pulsing dot indicator for active alarms.
 *
 * Uses an infinite scale + alpha animation to create a heartbeat effect.
 *
 * @param color dot color, defaults to [SolanaGreen]
 * @param modifier optional modifier
 */
@Composable
fun PulsingDot(
    color: Color = SolanaGreen,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Box(
        modifier = modifier
            .size(12.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

/**
 * Formatted SOL amount display with the Solana symbol (◎).
 *
 * Shows the amount with 4-decimal precision in [SolanaPurple],
 * followed by a smaller "SOL" suffix in [TextSecondary].
 *
 * @param amount SOL value to display
 * @param modifier optional modifier
 * @param showSymbol whether to prepend the ◎ symbol (default `true`)
 */
@Composable
fun SolAmount(
    amount: Double,
    modifier: Modifier = Modifier,
    showSymbol: Boolean = true
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showSymbol) {
            Text(
                text = "◎ ",
                color = SolanaPurple,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = String.format(java.util.Locale.US, "%.4f", amount),
            color = SolanaPurple,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = " SOL",
            color = TextSecondary,
            fontSize = 14.sp
        )
    }
}

/**
 * Status chip with icon.
 */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = ChipShape,
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Section header with optional action.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = TextSecondary
        )
        action?.invoke()
    }
}

/**
 * Empty state placeholder.
 */
@Composable
fun EmptyState(
    emoji: String,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = emoji,
            fontSize = 64.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}
