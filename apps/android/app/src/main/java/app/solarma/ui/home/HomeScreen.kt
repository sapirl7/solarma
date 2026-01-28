package app.solarma.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.solarma.data.local.AlarmEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Main home screen with alarm list, stats, and wallet status.
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
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("☀️", fontSize = 24.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Solarma")
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddAlarm,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Alarm")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Stats card
            item {
                StatsCard(
                    streak = uiState.currentStreak,
                    totalWakes = uiState.totalWakes,
                    savedSol = uiState.savedSol
                )
            }
            
            // Wallet status
            item {
                WalletStatusCard(
                    isConnected = uiState.walletConnected,
                    balance = uiState.walletBalance,
                    onConnectClick = { viewModel.connectWallet() }
                )
            }
            
            // Section header
            item {
                Text(
                    text = "Upcoming Alarms",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            // Alarm list
            if (uiState.alarms.isEmpty()) {
                item {
                    EmptyAlarmsCard(onAddClick = onAddAlarm)
                }
            } else {
                items(uiState.alarms) { alarm ->
                    AlarmCard(
                        alarm = alarm,
                        onClick = { onAlarmClick(alarm.id) }
                    )
                }
            }
            
            // Bottom spacer for FAB
            item { Spacer(modifier = Modifier.height(80.dp)) }
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
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(
                value = "$streak",
                label = "Day Streak",
                emoji = "🔥"
            )
            StatItem(
                value = "$totalWakes",
                label = "Total Wakes",
                emoji = "⏰"
            )
            StatItem(
                value = String.format("%.2f", savedSol),
                label = "SOL Saved",
                emoji = "💰"
            )
        }
    }
}

@Composable
fun StatItem(value: String, label: String, emoji: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 28.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun WalletStatusCard(
    isConnected: Boolean,
    balance: Double?,
    onConnectClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) 
                Color(0xFF1a472a) 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) Color.Green else Color.Gray)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isConnected) "Wallet Connected" else "Wallet Disconnected",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isConnected) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                    if (isConnected && balance != null) {
                        Text(
                            text = String.format("%.4f SOL", balance),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            
            if (!isConnected) {
                Button(onClick = onConnectClick) {
                    Text("Connect")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmCard(
    alarm: AlarmEntity,
    onClick: () -> Unit
) {
    val time = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(alarm.alarmTimeMillis),
        ZoneId.systemDefault()
    )
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val dayFormatter = DateTimeFormatter.ofPattern("EEE, d MMM")
    
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = time.format(timeFormatter),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (alarm.label.isNotEmpty()) alarm.label else time.format(dayFormatter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Wake proof indicator
                val proofEmoji = when (alarm.wakeProofType) {
                    1 -> "🚶"
                    2 -> "📱"
                    3 -> "📷"
                    else -> "❌"
                }
                Text(proofEmoji, fontSize = 20.sp)
                
                Spacer(modifier = Modifier.width(8.dp))
                
                // Deposit indicator
                if (alarm.hasDeposit) {
                    Text("💰", fontSize = 20.sp)
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Switch(
                    checked = alarm.isEnabled,
                    onCheckedChange = { /* TODO */ }
                )
            }
        }
    }
}

@Composable
fun EmptyAlarmsCard(onAddClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("⏰", fontSize = 48.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No alarms yet",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Create your first alarm to start building your wake streak!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onAddClick) {
                Text("Add Alarm")
            }
        }
    }
}
