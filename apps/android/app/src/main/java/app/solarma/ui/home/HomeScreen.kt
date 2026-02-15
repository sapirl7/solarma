package app.solarma.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.solarma.LocalActivityResultSender
import app.solarma.R
import app.solarma.data.local.AlarmEntity
import app.solarma.ui.components.*
import app.solarma.ui.components.PenaltyRouteDisplay
import app.solarma.ui.theme.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Premium home screen with animations and gradient backgrounds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onAddAlarm: () -> Unit = {},
    onAlarmClick: (Long) -> Unit = {},
    onSettingsClick: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    // Get ActivityResultSender from CompositionLocal
    val activityResultSender = LocalActivityResultSender.current

    SolarmaBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "SOLARMA",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                letterSpacing = 2.sp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.dev_test_badge),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = WarningAmber,
                                letterSpacing = 1.sp,
                                modifier =
                                    Modifier
                                        .background(
                                            WarningAmber.copy(alpha = 0.15f),
                                            RoundedCornerShape(4.dp),
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.settings),
                                tint = TextSecondary,
                            )
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                        ),
                )
            },
            floatingActionButton = {
                // Premium gradient FAB
                LargeFloatingActionButton(
                    onClick = onAddAlarm,
                    modifier =
                        Modifier.shadow(
                            elevation = 16.dp,
                            shape = CircleShape,
                            ambientColor = SolanaGreen,
                            spotColor = SolanaGreen,
                        ),
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(96.dp)
                                .background(
                                    brush = Brush.radialGradient(GradientSolanaPrimary),
                                    shape = CircleShape,
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "Add Alarm",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            },
        ) { padding ->
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Animated stats card
                item {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically(),
                    ) {
                        StatsCard(
                            streak = uiState.currentStreak,
                            totalWakes = uiState.totalWakes,
                            savedSol = uiState.savedSol,
                        )
                    }
                }

                // Wallet status with glow effect
                item {
                    WalletStatusCard(
                        isConnected = uiState.walletConnected,
                        balance = uiState.walletBalance,
                        walletAddress = uiState.walletAddress,
                        onConnectClick = { viewModel.connectWallet(activityResultSender) },
                    )
                }

                // Section header
                item {
                    SectionHeader(
                        title = "UPCOMING ALARMS",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                // Alarm list with staggered animation
                if (uiState.alarms.isEmpty()) {
                    item {
                        EmptyAlarmsCard(onAddClick = onAddAlarm)
                    }
                } else {
                    itemsIndexed(uiState.alarms) { index, alarm ->
                        val animDelay = index * 50
                        AnimatedVisibility(
                            visible = true,
                            enter =
                                fadeIn(
                                    animationSpec = tween(300, delayMillis = animDelay),
                                ) +
                                    slideInHorizontally(
                                        animationSpec = tween(300, delayMillis = animDelay),
                                        initialOffsetX = { it / 2 },
                                    ),
                        ) {
                            AlarmCard(
                                alarm = alarm,
                                onClick = { onAlarmClick(alarm.id) },
                                onToggle = { enabled -> viewModel.setAlarmEnabled(alarm.id, enabled) },
                                onDelete = { viewModel.deleteAlarm(alarm.id) },
                            )
                        }
                    }
                }

                // Bottom spacer for FAB
                item { Spacer(modifier = Modifier.height(100.dp)) }
            }
        }
    }
}

@Composable
fun StatsCard(
    streak: Int,
    totalWakes: Int,
    savedSol: Double,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 8.dp,
                    shape = AlarmCardShape,
                    ambientColor = SolanaPurple.copy(alpha = 0.3f),
                ),
        shape = AlarmCardShape,
        colors =
            CardDefaults.cardColors(
                containerColor = GraphiteSurface,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        brush =
                            Brush.horizontalGradient(
                                colors =
                                    listOf(
                                        SolanaPurple.copy(alpha = 0.2f),
                                        SolanaPurple.copy(alpha = 0.05f),
                                    ),
                            ),
                    ),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatItem(
                    value = "$streak",
                    label = "Day Streak",
                    emoji = "STREAK",
                    color = SolanaGreen,
                )
                StatItem(
                    value = "$totalWakes",
                    label = "Total Wakes",
                    emoji = "WAKES",
                    color = SolanaPurple,
                )
                StatItem(
                    value = String.format(java.util.Locale.US, "%.2f", savedSol),
                    label = "SOL Saved",
                    emoji = "SAVED",
                    color = SolanaGreen,
                )
            }
        }
    }
}

@Composable
fun StatItem(
    value: String,
    label: String,
    emoji: String,
    color: Color = TextPrimary,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = emoji,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
        )
    }
}

@Composable
fun WalletStatusCard(
    isConnected: Boolean,
    balance: Double?,
    walletAddress: String? = null,
    onConnectClick: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val backgroundColor =
        if (isConnected) {
            SolanaGreen.copy(alpha = 0.1f)
        } else {
            GraphiteSurface
        }

    val showFaucetButton = isConnected && (balance == null || balance < 0.1)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AlarmCardShape,
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isConnected) {
                        PulsingDot(color = SolanaGreen)
                    } else {
                        Box(
                            modifier =
                                Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(TextMuted),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isConnected) "Wallet Connected" else "Wallet Disconnected",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            color = if (isConnected) SolanaGreen else TextPrimary,
                        )
                        if (isConnected && walletAddress != null) {
                            Text(
                                text = walletAddress.take(4) + ".." + walletAddress.takeLast(4),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                        if (isConnected && balance != null) {
                            SolAmount(amount = balance, showSymbol = false)
                        }
                    }
                }

                if (!isConnected) {
                    GradientButton(
                        text = "Connect",
                        onClick = onConnectClick,
                        modifier = Modifier.width(120.dp),
                    )
                } else if (showFaucetButton) {
                    OutlinedButton(
                        onClick = { uriHandler.openUri("https://faucet.solana.com") },
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = SolanaPurple,
                            ),
                        border =
                            ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                                brush =
                                    Brush.horizontalGradient(
                                        listOf(SolanaPurple, SolanaPurple.copy(alpha = 0.5f)),
                                    ),
                            ),
                    ) {
                        Text("Get Test SOL →", fontSize = 12.sp)
                    }
                }
            }

            // Devnet testing notice — always visible when connected
            if (isConnected) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(WarningAmber.copy(alpha = 0.12f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.devnet_banner),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = WarningAmber,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmCard(
    alarm: AlarmEntity,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val time =
        LocalDateTime.ofInstant(
            Instant.ofEpochMilli(alarm.alarmTimeMillis),
            ZoneId.systemDefault(),
        )
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val dayFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")

    Card(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 4.dp,
                    shape = AlarmCardShape,
                    ambientColor = if (alarm.isEnabled) SolanaGreen.copy(alpha = 0.2f) else Color.Transparent,
                ),
        shape = AlarmCardShape,
        colors =
            CardDefaults.cardColors(
                containerColor = if (alarm.isEnabled) GraphiteSurface else GraphiteSurface.copy(alpha = 0.5f),
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = time.format(timeFormatter),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (alarm.isEnabled) TextPrimary else TextMuted,
                    )
                    if (alarm.isEnabled) {
                        Spacer(modifier = Modifier.width(8.dp))
                        PulsingDot(color = SolanaGreen, modifier = Modifier.size(8.dp))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (alarm.label.isNotEmpty()) alarm.label else time.format(dayFormatter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                // Show deposit amount and penalty route on alarm card
                if (alarm.hasDeposit && alarm.depositAmount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val penaltyInfo = PenaltyRouteDisplay.fromRoute(alarm.penaltyRoute)
                        Text(
                            text = "◎ ${String.format(java.util.Locale.US, "%.2f", alarm.depositAmount)} SOL ${penaltyInfo.emoji}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = SolanaPurple,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Wake proof indicator with tooltip
                val proofText =
                    when (alarm.wakeProofType) {
                        1 -> "STEPS"
                        2 -> "NFC"
                        3 -> "QR"
                        else -> "NONE"
                    }
                StatusChip(
                    text = proofText,
                    color = SolanaPurple,
                )

                // Modern switch
                Switch(
                    checked = alarm.isEnabled,
                    onCheckedChange = onToggle,
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = SolanaGreen,
                            checkedTrackColor = SolanaGreen.copy(alpha = 0.3f),
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = Graphite,
                        ),
                )

                // Delete button — hide if alarm has onchain deposit
                if (alarm.onchainPubkey == null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete alarm",
                            tint = TextMuted,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyAlarmsCard(onAddClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AlarmCardShape,
        colors =
            CardDefaults.cardColors(
                containerColor = GraphiteSurface.copy(alpha = 0.5f),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("NO ALARMS", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextMuted)
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.no_alarms_title),
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.no_alarms_body),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            GradientButton(
                text = stringResource(R.string.create_alarm),
                onClick = onAddClick,
                modifier = Modifier.width(180.dp),
            )
        }
    }
}

// ── Previews ──────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun AlarmCardPreview() {
    val sampleAlarm =
        AlarmEntity(
            id = 1,
            alarmTimeMillis = System.currentTimeMillis() + 3_600_000,
            label = "Morning Workout",
            isEnabled = true,
            wakeProofType = 1,
            targetSteps = 30,
            hasDeposit = true,
            depositAmount = 0.5,
            penaltyRoute = 0,
            snoozeCount = 2,
        )
    AlarmCard(alarm = sampleAlarm, onClick = {})
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
private fun EmptyAlarmsCardPreview() {
    EmptyAlarmsCard(onAddClick = {})
}
