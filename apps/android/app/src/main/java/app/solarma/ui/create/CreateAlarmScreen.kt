package app.solarma.ui.create

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Screen for creating a new alarm.
 * Uses CreateAlarmViewModel to actually save alarms.
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
    
    // Observe save state
    val saveState by viewModel.saveState.collectAsState()
    
    // Handle save result
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
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Alarm") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Time selector
            Card(
                onClick = { showTimePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.time.format(DateTimeFormatter.ofPattern("HH:mm")),
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Tap to change time",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            
            // Label
            OutlinedTextField(
                value = state.label,
                onValueChange = { state = state.copy(label = it) },
                label = { Text("Label (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            // Wake Proof selection
            Text(
                text = "Wake Proof Challenge",
                style = MaterialTheme.typography.titleMedium
            )
            
            Column(Modifier.selectableGroup()) {
                WakeProofOption(
                    emoji = "🚶",
                    title = "Walk Steps",
                    description = "Walk ${state.targetSteps} steps to dismiss",
                    selected = state.wakeProofType == 1,
                    onClick = { state = state.copy(wakeProofType = 1) }
                )
                WakeProofOption(
                    emoji = "📱",
                    title = "NFC Tag",
                    description = "Scan registered NFC tag",
                    selected = state.wakeProofType == 2,
                    onClick = { state = state.copy(wakeProofType = 2) }
                )
                WakeProofOption(
                    emoji = "📷",
                    title = "QR Code",
                    description = "Scan registered QR code",
                    selected = state.wakeProofType == 3,
                    onClick = { state = state.copy(wakeProofType = 3) }
                )
                WakeProofOption(
                    emoji = "❌",
                    title = "None",
                    description = "Simple dismiss button",
                    selected = state.wakeProofType == 0,
                    onClick = { state = state.copy(wakeProofType = 0) }
                )
            }
            
            // Steps count slider (if steps selected)
            if (state.wakeProofType == 1) {
                Column {
                    Text("Target Steps: ${state.targetSteps}")
                    Slider(
                        value = state.targetSteps.toFloat(),
                        onValueChange = { state = state.copy(targetSteps = it.toInt()) },
                        valueRange = 10f..100f,
                        steps = 8
                    )
                }
            }
            
            // Deposit option
            HorizontalDivider()
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "💰 Add SOL Deposit",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Stake SOL to boost accountability",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = state.hasDeposit,
                    onCheckedChange = { state = state.copy(hasDeposit = it) }
                )
            }
            
            if (state.hasDeposit) {
                DepositOptions(
                    amount = state.depositAmount,
                    onAmountChange = { state = state.copy(depositAmount = it) },
                    penaltyRoute = state.penaltyRoute,
                    onRouteChange = { state = state.copy(penaltyRoute = it) }
                )
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Save button - NOW USES VIEWMODEL
            Button(
                onClick = { viewModel.save(state) },
                enabled = saveState !is SaveState.Saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                if (saveState is SaveState.Saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        text = if (state.hasDeposit) 
                            "Create Alarm (${state.depositAmount} SOL)" 
                        else 
                            "Create Alarm",
                        fontSize = 18.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
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
fun WakeProofOption(
    emoji: String,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(emoji, fontSize = 24.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun DepositOptions(
    amount: Double,
    onAmountChange: (Double) -> Unit,
    penaltyRoute: Int,
    onRouteChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Amount buttons
        Text("Deposit Amount")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(0.01, 0.05, 0.1, 0.5).forEach { sol ->
                FilterChip(
                    selected = amount == sol,
                    onClick = { onAmountChange(sol) },
                    label = { Text("$sol SOL") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        
        // Penalty route
        Text("If You Fail")
        Column(Modifier.selectableGroup()) {
            PenaltyOption(
                emoji = "🔥",
                title = "Burn",
                description = "SOL is permanently burned",
                selected = penaltyRoute == 0,
                onClick = { onRouteChange(0) }
            )
            PenaltyOption(
                emoji = "🎁",
                title = "Donate",
                description = "SOL goes to charity",
                selected = penaltyRoute == 1,
                onClick = { onRouteChange(1) }
            )
            PenaltyOption(
                emoji = "👋",
                title = "Buddy",
                description = "SOL goes to a friend",
                selected = penaltyRoute == 2,
                onClick = { onRouteChange(2) }
            )
        }
    }
}

@Composable
fun PenaltyOption(
    emoji: String,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(emoji, fontSize = 20.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        text = {
            TimePicker(state = timePickerState)
        }
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
