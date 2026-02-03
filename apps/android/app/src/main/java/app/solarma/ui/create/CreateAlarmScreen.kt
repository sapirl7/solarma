package app.solarma.ui.create

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import app.solarma.LocalActivityResultSender
import app.solarma.ui.components.*
import app.solarma.ui.theme.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Premium create alarm screen with polished UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateAlarmScreen(
    viewModel: CreateAlarmViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    var state by remember { mutableStateOf(CreateAlarmState()) }
    var showTimePicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activityResultSender = LocalActivityResultSender.current
    val alarmManager = remember {
        context.getSystemService(AlarmManager::class.java)
    }
    val needsExactAlarmPermission =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            alarmManager != null &&
            !alarmManager.canScheduleExactAlarms()
    
    val saveState by viewModel.saveState.collectAsState()
    
    LaunchedEffect(saveState) {
        when (val currentState = saveState) {
            is SaveState.Success -> {
                Toast.makeText(context, "Alarm created!", Toast.LENGTH_SHORT).show()
                viewModel.resetState()
                onBack()
            }
            is SaveState.PendingConfirmation -> {
                Toast.makeText(
                    context,
                    "Deposit pending confirmation. Open app to finalize.",
                    Toast.LENGTH_LONG
                ).show()
                viewModel.resetState()
                onBack()
            }
            is SaveState.NeedsSigning -> {
                // Trigger MWA signing
                Toast.makeText(context, "Signing ${currentState.depositAmount} SOL deposit...", Toast.LENGTH_SHORT).show()
                viewModel.signDeposit(activityResultSender)
            }
            is SaveState.SigningFailed -> {
                Toast.makeText(context, "Signing failed: ${currentState.message}\nAlarm saved without deposit.", Toast.LENGTH_LONG).show()
                viewModel.resetState()
                onBack()
            }
            is SaveState.Error -> {
                Toast.makeText(context, currentState.message, Toast.LENGTH_LONG).show()
                viewModel.resetState()
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
                            "New Alarm",
                            style = MaterialTheme.typography.titleLarge,
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                if (needsExactAlarmPermission) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = AlarmCardShape,
                        colors = CardDefaults.cardColors(containerColor = NightSkyCard)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Exact alarm permission required",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Enable exact alarms so Solarma can wake you reliably.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SolanaGreen)
                            ) {
                                Text("Enable exact alarms")
                            }
                        }
                    }
                }

                // TIME PICKER CARD
                TimeCard(
                    time = state.time,
                    onClick = { showTimePicker = true }
                )
                
                // LABEL INPUT
                OutlinedTextField(
                    value = state.label,
                    onValueChange = { state = state.copy(label = it) },
                    label = { Text("Label (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = ButtonShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = TextMuted.copy(alpha = 0.3f),
                        focusedBorderColor = SolanaGreen,
                        unfocusedContainerColor = GraphiteSurface,
                        focusedContainerColor = GraphiteSurface
                    )
                )
                
                // WAKE PROOF SECTION
                SectionHeader(title = "WAKE PROOF CHALLENGE")
                
                WakeProofSelector(
                    selectedType = state.wakeProofType,
                    targetSteps = state.targetSteps,
                    onTypeChange = { state = state.copy(wakeProofType = it) },
                    onStepsChange = { state = state.copy(targetSteps = it) }
                )
                
                // DEPOSIT SECTION
                Spacer(modifier = Modifier.height(8.dp))
                DepositSection(
                    hasDeposit = state.hasDeposit,
                    amount = state.depositAmount,
                    customAmountText = state.customAmountText,
                    penaltyRoute = state.penaltyRoute,
                    donationAddress = state.donationAddress,
                    buddyAddress = state.buddyAddress,
                    onToggle = { state = state.copy(hasDeposit = it) },
                    onAmountChange = { state = state.copy(depositAmount = it) },
                    onCustomAmountChange = { state = state.copy(customAmountText = it) },
                    onRouteChange = { state = state.copy(penaltyRoute = it) },
                    onDonationAddressChange = { state = state.copy(donationAddress = it) },
                    onBuddyAddressChange = { state = state.copy(buddyAddress = it) }
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                // CREATE BUTTON
                GradientButton(
                    text = if (state.hasDeposit) 
                        "Create Alarm (${state.depositAmount} SOL)" 
                    else 
                        "Create Alarm",
                    onClick = { viewModel.save(state) },
                    enabled = saveState !is SaveState.Saving,
                    isLoading = saveState is SaveState.Saving,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
    
    if (showTimePicker) {
        TimePickerDialog(
            initialTime = state.time,
            onDismiss = { showTimePicker = false },
            onConfirm = { 
                state = state.copy(time = it)
                showTimePicker = false
            }
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeCard(
    time: LocalTime,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 12.dp,
                shape = TimePickerShape,
                ambientColor = SolanaGreen.copy(alpha = 0.3f)
            ),
        shape = TimePickerShape,
        colors = CardDefaults.cardColors(containerColor = NightSkyCard)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            SolanaGreen.copy(alpha = 0.1f),
                            Color.Transparent
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = time.format(DateTimeFormatter.ofPattern("HH:mm")),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap to change time",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun WakeProofSelector(
    selectedType: Int,
    targetSteps: Int,
    onTypeChange: (Int) -> Unit,
    onStepsChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WakeProofChip(
                iconText = "STEPS",
                label = "Steps",
                selected = selectedType == 1,
                onClick = { onTypeChange(1) },
                modifier = Modifier.weight(1f)
            )
            WakeProofChip(
                iconText = "NFC",
                label = "NFC",
                selected = selectedType == 2,
                onClick = { onTypeChange(2) },
                modifier = Modifier.weight(1f)
            )
            WakeProofChip(
                iconText = "QR",
                label = "QR Code",
                selected = selectedType == 3,
                onClick = { onTypeChange(3) },
                modifier = Modifier.weight(1f)
            )
            WakeProofChip(
                iconText = "NONE",
                label = "None",
                selected = selectedType == 0,
                onClick = { onTypeChange(0) },
                modifier = Modifier.weight(1f)
            )
        }
        
        // Mode description card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = AlarmCardShape,
            colors = CardDefaults.cardColors(containerColor = NightSkyCard.copy(alpha = 0.7f))
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text("INFO", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextMuted)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (selectedType) {
                        1 -> "Walk the required number of steps to dismiss the alarm. Your phone must detect actual movement — no cheating!"
                        2 -> "Scan a registered NFC tag (like a sticker on your bathroom mirror) to prove you got out of bed."
                        3 -> "Scan a QR code placed somewhere far from your bed to dismiss the alarm."
                        else -> "Simple tap to dismiss. No physical challenge required, but your deposit is still at stake!"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    lineHeight = 18.sp
                )
            }
        }
        
        // Steps slider
        AnimatedVisibility(
            visible = selectedType == 1,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = AlarmCardShape,
                colors = CardDefaults.cardColors(containerColor = NightSkyCard)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Target Steps",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = "≈ ${(targetSteps * 0.7).toInt()} meters to walk",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                        Text(
                            text = "$targetSteps",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = SolanaPurple
                        )
                    }
                    Slider(
                        value = targetSteps.toFloat(),
                        onValueChange = { onStepsChange(it.toInt()) },
                        valueRange = 10f..100f,
                        steps = 8,
                        colors = SliderDefaults.colors(
                            thumbColor = SolanaPurple,
                            activeTrackColor = SolanaPurple,
                            inactiveTrackColor = Graphite
                        )
                    )
                    Text(
                        text = "Tip: 50+ steps ensures you really get moving!",
                        style = MaterialTheme.typography.bodySmall,
                        color = SolanaGreen.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
fun WakeProofChip(
    iconText: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.05f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "chip_scale"
    )
    
    Surface(
        onClick = onClick,
        modifier = modifier
            .scale(scale)
            .height(80.dp),
        shape = AlarmCardShape,
        color = if (selected) SolanaPurple.copy(alpha = 0.2f) else GraphiteSurface,
        border = if (selected) 
            androidx.compose.foundation.BorderStroke(2.dp, SolanaPurple) 
        else 
            null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(iconText, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (selected) SolanaPurple else TextMuted)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) SolanaPurple else TextSecondary
            )
        }
    }
}

@Composable
fun DepositSection(
    hasDeposit: Boolean,
    amount: Double,
    customAmountText: String,
    penaltyRoute: Int,
    donationAddress: String,
    buddyAddress: String,
    onToggle: (Boolean) -> Unit,
    onAmountChange: (Double) -> Unit,
    onCustomAmountChange: (String) -> Unit,
    onRouteChange: (Int) -> Unit,
    onDonationAddressChange: (String) -> Unit,
    onBuddyAddressChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AlarmCardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (hasDeposit) SolanaPurple.copy(alpha = 0.1f) else GraphiteSurface
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("💰", fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Add SOL Deposit",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Lock SOL to stay accountable",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Switch(
                    checked = hasDeposit,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = SolanaPurple,
                        checkedTrackColor = SolanaPurple.copy(alpha = 0.3f),
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = Graphite
                    )
                )
            }
            
            AnimatedVisibility(
                visible = hasDeposit,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Deposit explanation
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = SolanaPurple.copy(alpha = 0.1f))
                    ) {
                        Text(
                            text = "Smart Contract: Your SOL is locked on-chain. Wake up on time → get it back. Fail → penalty applies.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary,
                            modifier = Modifier.padding(12.dp),
                            lineHeight = 18.sp
                        )
                    }
                    
                    // Amount chips
                    Text(
                        text = "DEPOSIT AMOUNT",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0.01, 0.05, 0.1, 0.5).forEach { sol ->
                            AmountChip(
                                amount = sol,
                                selected = amount == sol && customAmountText.isEmpty(),
                                onClick = { 
                                    onAmountChange(sol)
                                    onCustomAmountChange("")
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    
                    // Custom amount input
                    OutlinedTextField(
                        value = customAmountText,
                        onValueChange = { text ->
                            onCustomAmountChange(text)
                            text.toDoubleOrNull()?.let { onAmountChange(it) }
                        },
                        label = { Text("Custom amount (SOL)") },
                        placeholder = { Text("e.g. 1.5") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = TextMuted.copy(alpha = 0.3f),
                            focusedBorderColor = SolanaPurple,
                            unfocusedContainerColor = Graphite,
                            focusedContainerColor = Graphite
                        )
                    )
                    
                    // Penalty route
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "IF YOU FAIL TO WAKE UP",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PenaltyChip(
                            iconText = "BURN",
                            label = "Burn",
                            selected = penaltyRoute == 0,
                            onClick = { onRouteChange(0) },
                            modifier = Modifier.weight(1f)
                        )
                        PenaltyChip(
                            iconText = "GIVE",
                            label = "Donate",
                            selected = penaltyRoute == 1,
                            onClick = { onRouteChange(1) },
                            modifier = Modifier.weight(1f)
                        )
                        PenaltyChip(
                            iconText = "BUDDY",
                            label = "Buddy",
                            selected = penaltyRoute == 2,
                            onClick = { onRouteChange(2) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    // Penalty explanation
                    Text(
                        text = when (penaltyRoute) {
                            0 -> "Burn: SOL is permanently destroyed. Maximum stakes!"
                            1 -> "Donate: SOL goes to Solarma project development."
                            else -> "Buddy: SOL goes to your accountability partner."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    
                    // Donate goes to fixed Solarma treasury - no input needed
                    AnimatedVisibility(
                        visible = penaltyRoute == 1,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Text(
                            text = "100% goes to Solarma development. Thank you!",
                            style = MaterialTheme.typography.bodySmall,
                            color = SolanaPurple,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                        )
                    }
                    
                    // Buddy address input (visible when Buddy is selected)
                    AnimatedVisibility(
                        visible = penaltyRoute == 2,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = buddyAddress,
                                onValueChange = onBuddyAddressChange,
                                label = { Text("Buddy's Wallet Address") },
                                placeholder = { Text("Your friend's Solana address") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = TextMuted.copy(alpha = 0.3f),
                                    focusedBorderColor = SolanaPurple,
                                    unfocusedContainerColor = Graphite,
                                    focusedContainerColor = Graphite
                                )
                            )
                            Text(
                                text = "Your accountability partner will receive SOL if you fail to wake up",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted,
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AmountChip(
    amount: Double,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = ChipShape,
        color = if (selected) SolanaPurple else Graphite,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, TextMuted.copy(alpha = 0.2f))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${amount}◎",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.Black else TextPrimary
            )
        }
    }
}

@Composable
fun PenaltyChip(
    iconText: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = ChipShape,
        color = if (selected) ErrorCrimson.copy(alpha = 0.15f) else Graphite,
        border = if (selected) 
            androidx.compose.foundation.BorderStroke(2.dp, ErrorCrimson) 
        else 
            androidx.compose.foundation.BorderStroke(1.dp, TextMuted.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(iconText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (selected) ErrorCrimson else TextMuted)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) ErrorCrimson else TextSecondary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initialTime: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { 
                    onConfirm(LocalTime.of(timePickerState.hour, timePickerState.minute))
                }
            ) {
                Text("OK", color = SunriseOrange)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
        text = {
            TimePicker(
                state = timePickerState,
                colors = TimePickerDefaults.colors(
                    selectorColor = SolanaGreen,
                    timeSelectorSelectedContainerColor = SolanaGreen.copy(alpha = 0.2f),
                    timeSelectorSelectedContentColor = SolanaGreen
                )
            )
        },
        containerColor = NightSkyCard,
        shape = AlarmCardShape
    )
}

/**
 * State for create alarm screen.
 */
data class CreateAlarmState(
    val time: LocalTime = LocalTime.of(7, 0),
    val label: String = "",
    val wakeProofType: Int = 1,
    val targetSteps: Int = 20,
    val hasDeposit: Boolean = false,
    val depositAmount: Double = 0.05,
    val customAmountText: String = "",
    val penaltyRoute: Int = 0,
    val donationAddress: String = "",
    val buddyAddress: String = ""
)
