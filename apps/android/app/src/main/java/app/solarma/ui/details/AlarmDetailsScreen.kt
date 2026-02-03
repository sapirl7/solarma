package app.solarma.ui.details

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.solarma.LocalActivityResultSender
import app.solarma.data.local.AlarmEntity
import app.solarma.ui.components.SolarmaBackground
import app.solarma.ui.theme.*
import app.solarma.wakeproof.WakeProofEngine
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Alarm details screen with emergency refund option.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmDetailsScreen(
    alarmId: Long,
    viewModel: AlarmDetailsViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
    onViewHistory: () -> Unit = {}
) {
    val alarm by viewModel.alarm.collectAsState()
    val refundState by viewModel.refundState.collectAsState()
    val pendingCreate by viewModel.pendingCreate.collectAsState()
    val context = LocalContext.current
    val activityResultSender = LocalActivityResultSender.current
    
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRefundDialog by remember { mutableStateOf(false) }
    
    LaunchedEffect(alarmId) {
        viewModel.loadAlarm(alarmId)
    }
    
    LaunchedEffect(refundState) {
        when (val state = refundState) {
            is RefundState.Success -> {
                Toast.makeText(context, "Refund successful!", Toast.LENGTH_SHORT).show()
                viewModel.resetRefundState()
            }
            is RefundState.Error -> {
                Toast.makeText(context, "Refund failed: ${state.message}", Toast.LENGTH_LONG).show()
                viewModel.resetRefundState()
            }
            else -> {}
        }
    }
    
    SolarmaBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = { 
                        Text(
                            "Alarm Details",
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = TextMuted
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->
            alarm?.let { currentAlarm ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Time card
                    AlarmTimeCard(currentAlarm)
                    
                    // Deposit card (if applicable)
                    if (currentAlarm.hasDeposit) {
                    DepositCard(
                        alarm = currentAlarm,
                        isRefunding = refundState is RefundState.Processing,
                        pendingConfirmation = pendingCreate,
                        onRefundClick = { showRefundDialog = true }
                    )
                }
                    
                    // Actions
                    OutlinedButton(
                        onClick = onViewHistory,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextSecondary
                        )
                    ) {
                        Text("VIEW TRANSACTION HISTORY")
                    }
                }
            } ?: run {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = SolanaGreen)
                }
            }
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog) {
        val hasDeposit = alarm?.hasDeposit == true
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(if (hasDeposit) "Cannot Delete" else "Delete Alarm?") },
            text = { 
                Text(
                    if (hasDeposit) 
                        "This alarm has a locked deposit. Please request an Emergency Refund first, then delete the alarm."
                    else 
                        "Are you sure you want to delete this alarm?"
                )
            },
            confirmButton = {
                if (hasDeposit) {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("OK")
                    }
                } else {
                    TextButton(
                        onClick = {
                            viewModel.deleteAlarm()
                            showDeleteDialog = false
                            onBack()
                        }
                    ) {
                        Text("Delete", color = Color(0xFFE53935))
                    }
                }
            },
            dismissButton = {
                if (!hasDeposit) {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
    
    // Refund confirmation dialog
    if (showRefundDialog) {
        AlertDialog(
            onDismissRequest = { showRefundDialog = false },
            title = { Text("EMERGENCY REFUND") },
            text = { 
                Text(
                    "Request emergency refund of your deposit?\n\n" +
                    "Note: A small penalty may apply. Use this only if you can't complete the wake proof."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.requestEmergencyRefund(activityResultSender)
                        showRefundDialog = false
                    }
                ) {
                    Text("Refund", color = SolanaPurple)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRefundDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun AlarmTimeCard(alarm: AlarmEntity) {
    val time = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(alarm.alarmTimeMillis),
        ZoneId.systemDefault()
    )
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val dateFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM")
    
    val proofType = when (alarm.wakeProofType) {
        WakeProofEngine.TYPE_STEPS -> "STEPS (${alarm.targetSteps})"
        WakeProofEngine.TYPE_NFC -> "NFC TAG"
        WakeProofEngine.TYPE_QR -> "QR CODE"
        else -> "NONE"
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = time.format(timeFormatter),
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = time.format(dateFormatter),
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SolanaPurple.copy(alpha = 0.15f)
            ) {
                Text(
                    text = proofType,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = SolanaPurple,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun DepositCard(
    alarm: AlarmEntity,
    isRefunding: Boolean,
    pendingConfirmation: Boolean,
    onRefundClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DEPOSIT LOCKED",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Text(
                        text = "${alarm.depositAmount} SOL",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = SolanaPurple
                    )
                }
                
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (pendingConfirmation) {
                        SolanaPurple.copy(alpha = 0.15f)
                    } else {
                        SolanaGreen.copy(alpha = 0.15f)
                    }
                ) {
                    Text(
                        text = if (pendingConfirmation) "PENDING" else "ACTIVE",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (pendingConfirmation) SolanaPurple else SolanaGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            alarm.onchainPubkey?.let { pubkey ->
                Text(
                    text = "PDA: ${pubkey.take(8)}...${pubkey.takeLast(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = onRefundClick,
                enabled = !isRefunding,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SolanaPurple,
                    disabledContainerColor = SolanaPurple.copy(alpha = 0.5f)
                )
            ) {
                if (isRefunding) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isRefunding) "Processing..." else "EMERGENCY REFUND")
            }
        }
    }
}
