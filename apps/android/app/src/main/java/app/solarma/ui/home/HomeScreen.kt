package app.solarma.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.solarma.data.local.AlarmEntity
import app.solarma.ui.components.*
import app.solarma.ui.theme.*
import app.solarma.LocalActivityResultSender
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
    onSettingsClick: () -> Unit = {}
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
                            // Animated sunrise icon
                            val rotation by rememberInfiniteTransition(label = "sun")
                                .animateFloat(
                                    initialValue = -5f,
                                    targetValue = 5f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(2000, easing = EaseInOutSine),
                                        repeatMode = RepeatMode.Reverse
                                    ),
                                    label = "sun_rotation"
                                )
                            Text("🌅", fontSize = 28.sp, modifier = Modifier.offset(y = rotation.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Solarma",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onSettingsClick) {
                            Icon(
                                Icons.Default.Settings, 
                                contentDescription = "Settings",
                                tint = TextSecondary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            floatingActionButton = {
                // Premium gradient FAB
                LargeFloatingActionButton(
                    onClick = onAddAlarm,
                    modifier = Modifier.shadow(
                        elevation = 16.dp,
                        shape = CircleShape,
                        ambientColor = SunriseOrange,
                        spotColor = SunriseOrange
                    ),
                    containerColor = Color.Transparent,
                    contentColor = Color.White
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .background(
                                brush = Brush.radialGradient(GradientSunrise),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Add, 
                            contentDescription = "Add Alarm",
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Animated stats card
                item {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInVertically()
                    ) {
                        StatsCard(
                            streak = uiState.currentStreak,
                            totalWakes = uiState.totalWakes,
                            savedSol = uiState.savedSol
                        )
                    }
                }
                
                // Wallet status with glow effect
                item {
                    WalletStatusCard(
                        isConnected = uiState.walletConnected,
                        balance = uiState.walletBalance,
                        onConnectClick = { viewModel.connectWallet(activityResultSender) }
                    )
                }
                
                // Section header
                item {
                    SectionHeader(
                        title = "UPCOMING ALARMS",
                        modifier = Modifier.padding(top = 8.dp)
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
                            enter = fadeIn(
                                animationSpec = tween(300, delayMillis = animDelay)
                            ) + slideInHorizontally(
                                animationSpec = tween(300, delayMillis = animDelay),
                                initialOffsetX = { it / 2 }
                            )
                        ) {
                            AlarmCard(
                                alarm = alarm,
                                onClick = { onAlarmClick(alarm.id) },
                                onToggle = { enabled -> viewModel.setAlarmEnabled(alarm.id, enabled) },
                                onDelete = { viewModel.deleteAlarm(alarm.id) }
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
    savedSol: Double
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = AlarmCardShape,
                ambientColor = DawnPurple.copy(alpha = 0.3f)
            ),
        shape = AlarmCardShape,
        colors = CardDefaults.cardColors(
            containerColor = NightSkyCard
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            DawnPurple.copy(alpha = 0.2f),
                            DawnPurple.copy(alpha = 0.05f)
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    value = "$streak",
                    label = "Day Streak",
                    emoji = "🔥",
                    color = SunriseOrange
                )
                StatItem(
                    value = "$totalWakes",
                    label = "Total Wakes",
                    emoji = "⏰",
                    color = GoldenHour
                )
                StatItem(
                    value = String.format("%.2f", savedSol),
                    label = "SOL Saved",
                    emoji = "💰",
                    color = MorningGreen
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
    color: Color = TextPrimary
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 32.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
    }
}

@Composable
fun WalletStatusCard(
    isConnected: Boolean,
    balance: Double?,
    onConnectClick: () -> Unit
) {
    val backgroundColor = if (isConnected) 
        MorningGreen.copy(alpha = 0.15f) 
    else 
        NightSkyCard
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AlarmCardShape,
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isConnected) {
                    PulsingDot(color = MorningGreen)
                } else {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(TextMuted)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isConnected) "Wallet Connected" else "Wallet Disconnected",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (isConnected) MorningGreen else TextPrimary
                    )
                    if (isConnected && balance != null) {
                        SolAmount(amount = balance, showSymbol = false)
                    }
                }
            }
            
            if (!isConnected) {
                GradientButton(
                    text = "Connect",
                    onClick = onConnectClick,
                    modifier = Modifier.width(120.dp)
                )
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
    onDelete: () -> Unit = {}
) {
    val time = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(alarm.alarmTimeMillis),
        ZoneId.systemDefault()
    )
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val dayFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
    
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = AlarmCardShape,
                ambientColor = if (alarm.isEnabled) SunriseOrange.copy(alpha = 0.2f) else Color.Transparent
            ),
        shape = AlarmCardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (alarm.isEnabled) NightSkyCard else NightSkyCard.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = time.format(timeFormatter),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (alarm.isEnabled) TextPrimary else TextMuted
                    )
                    if (alarm.isEnabled) {
                        Spacer(modifier = Modifier.width(8.dp))
                        PulsingDot(color = SunriseOrange, modifier = Modifier.size(8.dp))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (alarm.label.isNotEmpty()) alarm.label else time.format(dayFormatter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Wake proof indicator with tooltip
                val proofEmoji = when (alarm.wakeProofType) {
                    1 -> "🚶"
                    2 -> "📱"
                    3 -> "📷"
                    else -> "✅"
                }
                StatusChip(
                    text = proofEmoji,
                    color = DawnPurple
                )
                
                // Deposit indicator
                if (alarm.hasDeposit) {
                    StatusChip(
                        text = "◎",
                        color = GoldenHour
                    )
                }
                
                // Modern switch
                Switch(
                    checked = alarm.isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SunriseOrange,
                        checkedTrackColor = SunriseOrange.copy(alpha = 0.3f),
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = NightSkyLight
                    )
                )
                
                // Delete button
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete alarm",
                        tint = TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
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
        colors = CardDefaults.cardColors(
            containerColor = NightSkyCard.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Animated alarm icon
            val infiniteTransition = rememberInfiniteTransition(label = "empty")
            val scale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "empty_scale"
            )
            
            Text("⏰", fontSize = 64.sp, modifier = Modifier.scale(scale))
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "No alarms yet",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Create your first alarm and stake some SOL\nto start building your wake streak!",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            GradientButton(
                text = "Create Alarm",
                onClick = onAddClick,
                modifier = Modifier.width(180.dp)
            )
        }
    }
}
