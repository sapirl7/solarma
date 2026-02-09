package app.solarma.ui.onboarding

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.solarma.R
import app.solarma.ui.components.GradientButton
import app.solarma.ui.components.SolarmaBackground
import app.solarma.ui.theme.*
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════
// ONBOARDING — 4-slide intro explaining the Solarma concept
// ═══════════════════════════════════════════════════════════════

private data class OnboardingPage(
    val emoji: String,
    val titleRes: Int,
    val subtitleRes: Int,
    val accentColor: Color
)

private val pages = listOf(
    OnboardingPage("⏰", R.string.onboarding_title_1, R.string.onboarding_sub_1, SolanaGreen),
    OnboardingPage("🔒", R.string.onboarding_title_2, R.string.onboarding_sub_2, SolanaPurple),
    OnboardingPage("☀️", R.string.onboarding_title_3, R.string.onboarding_sub_3, SolanaGreen),
    OnboardingPage("◎", R.string.onboarding_title_4, R.string.onboarding_sub_4, SolanaPurple),
)

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == pages.size - 1

    SolarmaBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Skip button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onFinish) {
                    Text(
                        text = stringResource(R.string.onboarding_skip),
                        color = TextMuted,
                        fontSize = 14.sp
                    )
                }
            }

            // Pager content
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                OnboardingPageContent(pages[page])
            }

            // Page indicator dots
            Row(
                modifier = Modifier.padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pages.size) { index ->
                    val isActive = pagerState.currentPage == index
                    val dotSize by animateDpAsState(
                        targetValue = if (isActive) 10.dp else 6.dp,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "dot_size"
                    )
                    Box(
                        modifier = Modifier
                            .size(width = if (isActive) 24.dp else dotSize, height = dotSize)
                            .clip(CircleShape)
                            .background(
                                if (isActive) SolanaGreen
                                else TextMuted.copy(alpha = 0.4f)
                            )
                    )
                }
            }

            // Bottom button
            GradientButton(
                text = stringResource(
                    if (isLastPage) R.string.onboarding_start
                    else R.string.onboarding_next
                ),
                onClick = {
                    if (isLastPage) {
                        onFinish()
                    } else {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            )
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    val infiniteTransition = rememberInfiniteTransition(label = "emoji_pulse")
    val emojiScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "emoji_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Emoji with glow card
        Card(
            modifier = Modifier.size(140.dp),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.cardColors(
                containerColor = page.accentColor.copy(alpha = 0.1f)
            )
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = page.emoji,
                    fontSize = 64.sp,
                    modifier = Modifier.scale(emojiScale)
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // Title
        Text(
            text = stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Subtitle
        Text(
            text = stringResource(page.subtitleRes),
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Accent line
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.horizontalGradient(
                        listOf(page.accentColor, page.accentColor.copy(alpha = 0.3f))
                    )
                )
        )
    }
}

// ── Preview ──────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun OnboardingPreview() {
    SolarmaTheme {
        OnboardingScreen(onFinish = {})
    }
}
