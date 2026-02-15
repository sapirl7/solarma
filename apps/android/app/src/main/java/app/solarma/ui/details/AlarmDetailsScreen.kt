package app.solarma.ui.details

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.solarma.LocalActivityResultSender
import app.solarma.alarm.AlarmTiming
import app.solarma.data.local.AlarmEntity
import app.solarma.wallet.OnchainParameters
import app.solarma.ui.components.PenaltyRouteDisplay
import app.solarma.ui.components.SnoozePenaltyDisplay
import app.solarma.ui.components.SolarmaBackground
import app.solarma.ui.theme.*
import app.solarma.wakeproof.WakeProofEngine
import app.solarma.R
import androidx.compose.ui.res.stringResource
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
    var showSlashDialog by remember { mutableStateOf(false) }

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

                    // Deposit card (if applicable — only show when actual deposit exists)
                    val hasActiveDeposit = currentAlarm.hasDeposit && currentAlarm.depositLamports > 0
                    if (hasActiveDeposit) {
                        DepositCard(
                            alarm = currentAlarm,
                            isProcessing = refundState is RefundState.Processing,
                            pendingConfirmation = pendingCreate,
                            onRefundClick = { showRefundDialog = true },
                            onSlashClick = { showSlashDialog = true }
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
        val hasDeposit = alarm?.let { it.hasDeposit && it.depositLamports > 0 } == true
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(if (hasDeposit) "Cannot Delete" else "Delete Alarm?") },
            text = {
                Text(
                    if (hasDeposit)
                        "This alarm has a locked deposit. Use Emergency Refund (before alarm time) or Resolve Deposit (after deadline) to release funds first."
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
                    "Note: A ${OnchainParameters.EMERGENCY_REFUND_PENALTY_PERCENT}% penalty applies for early cancellation."
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

    // Slash (resolve expired alarm) dialog
    if (showSlashDialog) {
        val penaltyInfo = alarm?.let { PenaltyRouteDisplay.fromRoute(it.penaltyRoute) }
        AlertDialog(
            onDismissRequest = { showSlashDialog = false },
            title = { Text("RESOLVE EXPIRED ALARM") },
            text = {
                Text(
                    "This alarm has expired. Your deposit will be released according to your penalty route: ${penaltyInfo?.formatted ?: "Unknown"}.\n\n" +
                    "This action is final and cannot be undone."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.requestSlash(activityResultSender)
                        showSlashDialog = false
                    }
                ) {
                    Text("Resolve", color = WarningAmber)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSlashDialog = false }) {
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

/**
 * Alarm deposit state for determining available actions.
 */
private enum class AlarmDepositPhase {
    BEFORE_ALARM,   // Can emergency refund
    GRACE_PERIOD,   // Between alarm and deadline — no action available
    EXPIRED         // After deadline — can slash/resolve
}

@Composable
fun DepositCard(
    alarm: AlarmEntity,
    isProcessing: Boolean,
    pendingConfirmation: Boolean,
    onRefundClick: () -> Unit,
    onSlashClick: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Deadline = alarm time + grace period (matches on-chain logic)
    val deadlineMillis = alarm.alarmTimeMillis + AlarmTiming.GRACE_PERIOD_MILLIS
    val deadlineTime = LocalDateTime.ofInstant(
        Instant.ofEpochMilli(deadlineMillis),
        ZoneId.systemDefault()
    )
    val deadlineFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // Penalty route info
    val penaltyInfo = PenaltyRouteDisplay.fromRoute(alarm.penaltyRoute)

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
                        text = stringResource(R.string.deposit_locked),
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
                        text = if (pendingConfirmation) stringResource(R.string.status_pending) else stringResource(R.string.status_active),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (pendingConfirmation) SolanaPurple else SolanaGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Deadline and penalty info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Deadline",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = deadlineTime.format(deadlineFormatter),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = WarningAmber
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Penalty",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    Text(
                        text = penaltyInfo.formatted,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                }
                if (alarm.snoozeCount > 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Snoozed",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                        Text(
                            text = SnoozePenaltyDisplay.formatDisplay(alarm.snoozeCount),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = WarningAmber
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // PDA address — tap to copy
            alarm.onchainPubkey?.let { pubkey ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Graphite.copy(alpha = 0.5f))
                        .clickable {
                            clipboardManager.setText(AnnotatedString(pubkey))
                            Toast.makeText(context, "PDA copied!", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "PDA: ${pubkey.take(8)}…${pubkey.takeLast(8)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    Text(
                        text = "📋 Copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = SolanaPurple
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Determine which action is available
            val nowMillis = System.currentTimeMillis()
            val phase = when {
                nowMillis < alarm.alarmTimeMillis -> AlarmDepositPhase.BEFORE_ALARM
                nowMillis < deadlineMillis -> AlarmDepositPhase.GRACE_PERIOD
                else -> AlarmDepositPhase.EXPIRED
            }

            when (phase) {
                AlarmDepositPhase.BEFORE_ALARM -> {
                    Button(
                        onClick = onRefundClick,
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SolanaPurple,
                            disabledContainerColor = SolanaPurple.copy(alpha = 0.5f)
                        )
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isProcessing) "Processing..." else "EMERGENCY REFUND")
                    }
                }
                AlarmDepositPhase.GRACE_PERIOD -> {
                    Text(
                        text = "⏳ Waiting for deadline (${deadlineTime.format(deadlineFormatter)}) to resolve...",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningAmber,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                AlarmDepositPhase.EXPIRED -> {
                    Button(
                        onClick = onSlashClick,
                        enabled = !isProcessing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = WarningAmber,
                            disabledContainerColor = WarningAmber.copy(alpha = 0.5f)
                        )
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isProcessing) "Processing..." else "⚠️ RESOLVE EXPIRED DEPOSIT",
                            color = Color.Black
                        )
                    }
                }
            }
        }
    }
}
