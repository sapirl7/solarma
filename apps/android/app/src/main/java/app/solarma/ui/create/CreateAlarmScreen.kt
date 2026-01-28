package app.solarma.ui.create

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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.rounded.ArrowBack
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
    
    val saveState by viewModel.saveState.collectAsState()
    
    LaunchedEffect(saveState) {
        when (saveState) {
            is SaveState.Success -> {
                Toast.makeText(context, "Alarm created! ⏰", Toast.LENGTH_SHORT).show()
                viewModel.resetState()
                onBack()
            }
            is SaveState.Error -> {
                Toast.makeText(context, (saveState as SaveState.Error).message, Toast.LENGTH_LONG).show()
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
                                Icons.Rounded.ArrowBack, 
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
                        focusedBorderColor = SunriseOrange,
                        unfocusedContainerColor = NightSkyCard,
                        focusedContainerColor = NightSkyCard
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
                    penaltyRoute = state.penaltyRoute,
                    onToggle = { state = state.copy(hasDeposit = it) },
                    onAmountChange = { state = state.copy(depositAmount = it) },
                    onRouteChange = { state = state.copy(penaltyRoute = it) }
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
                ambientColor = SunriseOrange.copy(alpha = 0.3f)
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
                            SunriseOrange.copy(alpha = 0.1f),
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
                emoji = "🚶",
                label = "Steps",
                selected = selectedType == 1,
                onClick = { onTypeChange(1) },
                modifier = Modifier.weight(1f)
            )
            WakeProofChip(
                emoji = "📱",
                label = "NFC",
                selected = selectedType == 2,
                onClick = { onTypeChange(2) },
                modifier = Modifier.weight(1f)
            )
            WakeProofChip(
                emoji = "📷",
                label = "QR",
                selected = selectedType == 3,
                onClick = { onTypeChange(3) },
                modifier = Modifier.weight(1f)
            )
            WakeProofChip(
                emoji = "✅",
                label = "None",
                selected = selectedType == 0,
                onClick = { onTypeChange(0) },
                modifier = Modifier.weight(1f)
            )
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
                        Text(
                            text = "Target Steps",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Text(
                            text = "$targetSteps",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = DawnPurple
                        )
                    }
                    Slider(
                        value = targetSteps.toFloat(),
                        onValueChange = { onStepsChange(it.toInt()) },
                        valueRange = 10f..100f,
                        steps = 8,
                        colors = SliderDefaults.colors(
                            thumbColor = DawnPurple,
                            activeTrackColor = DawnPurple,
                            inactiveTrackColor = NightSkyLight
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun WakeProofChip(
    emoji: String,
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
        color = if (selected) DawnPurple.copy(alpha = 0.2f) else NightSkyCard,
        border = if (selected) 
            androidx.compose.foundation.BorderStroke(2.dp, DawnPurple) 
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
            Text(emoji, fontSize = 24.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) DawnPurple else TextSecondary
            )
        }
    }
}

@Composable
fun DepositSection(
    hasDeposit: Boolean,
    amount: Double,
    penaltyRoute: Int,
    onToggle: (Boolean) -> Unit,
    onAmountChange: (Double) -> Unit,
    onRouteChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AlarmCardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (hasDeposit) GoldenHour.copy(alpha = 0.1f) else NightSkyCard
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                            text = "Boost your accountability",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
                Switch(
                    checked = hasDeposit,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = GoldenHour,
                        checkedTrackColor = GoldenHour.copy(alpha = 0.3f),
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = NightSkyLight
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
                                selected = amount == sol,
                                onClick = { onAmountChange(sol) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    
                    // Penalty route
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "IF YOU FAIL",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PenaltyChip(
                            emoji = "🔥",
                            label = "Burn",
                            selected = penaltyRoute == 0,
                            onClick = { onRouteChange(0) },
                            modifier = Modifier.weight(1f)
                        )
                        PenaltyChip(
                            emoji = "🎁",
                            label = "Donate",
                            selected = penaltyRoute == 1,
                            onClick = { onRouteChange(1) },
                            modifier = Modifier.weight(1f)
                        )
                        PenaltyChip(
                            emoji = "👋",
                            label = "Buddy",
                            selected = penaltyRoute == 2,
                            onClick = { onRouteChange(2) },
                            modifier = Modifier.weight(1f)
                        )
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
        color = if (selected) GoldenHour else NightSkyLight,
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
    emoji: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = ChipShape,
        color = if (selected) AlertRed.copy(alpha = 0.15f) else NightSkyLight,
        border = if (selected) 
            androidx.compose.foundation.BorderStroke(2.dp, AlertRed) 
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
            Text(emoji, fontSize = 20.sp)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) AlertRed else TextSecondary
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
                    selectorColor = SunriseOrange,
                    timeSelectorSelectedContainerColor = SunriseOrange.copy(alpha = 0.2f),
                    timeSelectorSelectedContentColor = SunriseOrange
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
    val penaltyRoute: Int = 0
)
