package app.solarma.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import app.solarma.BuildConfig
import app.solarma.LocalActivityResultSender
import app.solarma.MainActivity
import app.solarma.NfcTagCallback
import app.solarma.ui.components.QrCodeImage
import app.solarma.ui.theme.*
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings screen with premium dark UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val importState by viewModel.importState.collectAsState()
    var showNfcDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showStepsDialog by remember { mutableStateOf(false) }
    var showDepositDialog by remember { mutableStateOf(false) }
    var showPenaltyDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val activityResultSender = LocalActivityResultSender.current
    val activity = context as? MainActivity

    val nfcCallback = remember {
        object : NfcTagCallback {
            override fun onTagDetected(tagHash: String) {
                viewModel.registerNfcTag(tagHash)
                Toast.makeText(context, "NFC tag registered", Toast.LENGTH_SHORT).show()
                showNfcDialog = false
            }
        }
    }

    LaunchedEffect(showNfcDialog) {
        activity?.setNfcTagCallback(if (showNfcDialog) nfcCallback else null)
    }

    LaunchedEffect(importState) {
        when (val state = importState) {
            is ImportState.Success -> {
                Toast.makeText(
                    context,
                    "Imported ${state.result.imported}, updated ${state.result.updated}, skipped ${state.result.skipped}",
                    Toast.LENGTH_LONG
                ).show()
                viewModel.resetImportState()
            }
            is ImportState.Error -> {
                Toast.makeText(context, "Import failed: ${state.message}", Toast.LENGTH_LONG).show()
                viewModel.resetImportState()
            }
            else -> Unit
        }
    }

    Scaffold(
        containerColor = DeepBlack,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
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
                .background(
                    Brush.verticalGradient(
                        colors = listOf(DeepBlack, Graphite)
                    )
                )
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Wallet Section
            SettingsSection(title = "Wallet") {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Outlined.AccountBalanceWallet,
                        title = "Connected Wallet",
                        subtitle = uiState.walletAddress?.take(8)?.plus("...") ?: "Not connected",
                        trailingContent = {
                            if (uiState.walletAddress != null) {
                                TextButton(onClick = { viewModel.disconnect() }) {
                                    Text("Disconnect", color = SolanaGreen)
                                }
                            } else {
                                TextButton(onClick = {
                                    coroutineScope.launch {
                                        viewModel.connect(activityResultSender)
                                    }
                                }) {
                                    Text("Connect", color = SolanaGreen)
                                }
                            }
                        }
                    )

                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f))

                    SettingsRow(
                        icon = Icons.Outlined.Hub,
                        title = "Network",
                        subtitle = if (uiState.isDevnet) "Devnet" else "Mainnet",
                        trailingContent = {
                            Switch(
                                checked = uiState.isDevnet,
                                onCheckedChange = { viewModel.setDevnet(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = SolanaGreen,
                                    checkedTrackColor = SolanaGreen.copy(alpha = 0.3f)
                                )
                            )
                        }
                    )

                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f))

                    val isImporting = importState is ImportState.Loading
                    SettingsRow(
                        icon = Icons.Outlined.Sync,
                        title = "Import Onchain Alarms",
                        subtitle = if (isImporting) "Importing..." else "Restore alarms from blockchain",
                        trailingContent = if (isImporting) {
                            {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = SolanaGreen
                                )
                            }
                        } else {
                            null
                        },
                        onClick = if (isImporting) null else {
                            {
                                coroutineScope.launch {
                                    viewModel.importOnchainAlarms()
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Alarm Defaults Section
            SettingsSection(title = "Alarm Defaults") {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Outlined.DirectionsWalk,
                        title = "Default Steps",
                        subtitle = "${uiState.defaultSteps} steps to dismiss",
                        onClick = { showStepsDialog = true }
                    )

                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f))

                    SettingsRow(
                        icon = Icons.Outlined.Payments,
                        title = "Default Deposit",
                        subtitle = "${uiState.defaultDepositSol} SOL",
                        onClick = { showDepositDialog = true }
                    )

                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f))

                    SettingsRow(
                        icon = Icons.Outlined.LocalFireDepartment,
                        title = "Default Penalty",
                        subtitle = when (uiState.defaultPenalty) {
                            0 -> "Burn"
                            1 -> "Donate"
                            else -> "Buddy"
                        },
                        onClick = { showPenaltyDialog = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Wake Proof Section
            SettingsSection(title = "Wake Proof") {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Outlined.Nfc,
                        title = "NFC Tag",
                        subtitle = if (uiState.nfcTagRegistered) "REGISTERED" else "Tap to set up",
                        onClick = { showNfcDialog = true }
                    )

                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f))

                    SettingsRow(
                        icon = Icons.Outlined.QrCode2,
                        title = "QR Code",
                        subtitle = if (uiState.qrCodeRegistered) "REGISTERED: ${uiState.qrCode}" else "Tap to generate",
                        onClick = { showQrDialog = true }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Sound Section
            SettingsSection(title = "Sound & Vibration") {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Outlined.VolumeUp,
                        title = "Alarm Sound",
                        subtitle = uiState.soundName,
                        onClick = { /* Pick sound */ }
                    )

                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f))

                    SettingsRow(
                        icon = Icons.Outlined.Vibration,
                        title = "Vibration",
                        subtitle = if (uiState.vibrationEnabled) "Enabled" else "Disabled",
                        trailingContent = {
                            Switch(
                                checked = uiState.vibrationEnabled,
                                onCheckedChange = { viewModel.setVibration(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = SolanaGreen,
                                    checkedTrackColor = SolanaGreen.copy(alpha = 0.3f)
                                )
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Debug Section
            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            SettingsSection(title = "Debug") {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Outlined.BugReport,
                        title = "Share Logs",
                        subtitle = "Send crash logs as file",
                        onClick = {
                            scope.launch {
                                try {
                                    val logs = withContext(Dispatchers.IO) {
                                        // Get more logs (3000 lines)
                                        val process = Runtime.getRuntime().exec(
                                            arrayOf("logcat", "-d", "-t", "3000", "--pid=${android.os.Process.myPid()}")
                                        )
                                        val reader = BufferedReader(InputStreamReader(process.inputStream))
                                        reader.readText()
                                    }

                                    // Write logs to file
                                    val timestamp = java.text.SimpleDateFormat("yyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())
                                    val logFile = java.io.File(context.cacheDir, "solarma_logs_$timestamp.txt")
                                    logFile.writeText("=== SOLARMA LOGS ===\n$timestamp\n\n$logs")

                                    // Share file via FileProvider
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        logFile
                                    )

                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "Solarma Logs $timestamp")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share Logs"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // About Section
            SettingsSection(title = "About") {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Outlined.Info,
                        title = "Version",
                        subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                    )

                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f))

                    SettingsRow(
                        icon = Icons.Outlined.Code,
                        title = "Open Source",
                        subtitle = "github.com/sapirl7/solarma",
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, "https://github.com/sapirl7/solarma".toUri())
                            context.startActivity(intent)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    // NFC Registration Dialog
    if (showNfcDialog) {
        AlertDialog(
            onDismissRequest = { showNfcDialog = false },
            title = { Text("NFC TAG SETUP") },
            text = {
                Column {
                    if (uiState.nfcTagRegistered) {
                        Text("Your NFC tag is registered!")
                        Text(
                            "Tag hash: ${uiState.nfcTagHash?.take(16)}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    } else {
                        Text("Place an NFC tag near your phone to register it.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tip: Put the tag in your bathroom or kitchen - somewhere you need to walk to!",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            },
            confirmButton = {
                if (uiState.nfcTagRegistered) {
                    TextButton(onClick = {
                        viewModel.clearNfcTag()
                        showNfcDialog = false
                    }) {
                        Text("Clear Tag", color = Color(0xFFE53935))
                    }
                } else {
                    TextButton(onClick = {
                        Toast.makeText(context, "Hold your phone near NFC tag", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Scan Now", color = SolanaGreen)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showNfcDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // QR Code Dialog
    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            title = { Text("QR CODE SETUP") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (uiState.qrCodeRegistered && uiState.qrCode != null) {
                        Text("Your QR code is ready!")
                        Spacer(modifier = Modifier.height(16.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            QrCodeImage(
                                text = uiState.qrCode!!,
                                size = 180.dp,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            uiState.qrCode!!,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Print this code and place it where you need to walk!",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    } else {
                        Text("Generate a unique QR code that you'll scan to dismiss the alarm.")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Print it and put it somewhere far from your bed!",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            },
            confirmButton = {
                if (uiState.qrCodeRegistered) {
                    TextButton(onClick = {
                        viewModel.clearQrCode()
                    }) {
                        Text("Regenerate", color = SolanaGreen)
                    }
                } else {
                    TextButton(onClick = {
                        viewModel.generateQrCode()
                        Toast.makeText(context, "QR Code generated!", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Generate", color = SolanaGreen)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Steps Dialog
    if (showStepsDialog) {
        var steps by remember { mutableStateOf(uiState.defaultSteps.toString()) }
        AlertDialog(
            onDismissRequest = { showStepsDialog = false },
            title = { Text("DEFAULT STEPS") },
            text = {
                Column {
                    Text("How many steps to dismiss alarm?")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = steps,
                        onValueChange = { steps = it.filter { c -> c.isDigit() } },
                        label = { Text("Steps") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    steps.toIntOrNull()?.let { viewModel.setDefaultSteps(it) }
                    showStepsDialog = false
                }) {
                    Text("Save", color = SolanaGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStepsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Deposit Dialog
    if (showDepositDialog) {
        var deposit by remember { mutableStateOf(uiState.defaultDepositSol.toString()) }
        AlertDialog(
            onDismissRequest = { showDepositDialog = false },
            title = { Text("DEFAULT DEPOSIT") },
            text = {
                Column {
                    Text("Default SOL deposit amount")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = deposit,
                        onValueChange = { deposit = it },
                        label = { Text("SOL") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deposit.toDoubleOrNull()?.let { viewModel.setDefaultDeposit(it) }
                    showDepositDialog = false
                }) {
                    Text("Save", color = SolanaGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDepositDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Penalty Dialog
    if (showPenaltyDialog) {
        AlertDialog(
            onDismissRequest = { showPenaltyDialog = false },
            title = { Text("DEFAULT PENALTY") },
            text = {
                Column {
                    listOf(
                        0 to "Burn - SOL destroyed forever",
                        1 to "Donate - SOL goes to Solarma",
                        2 to "Buddy - SOL goes to friend"
                    ).forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    viewModel.setDefaultPenalty(value)
                                    showPenaltyDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.defaultPenalty == value,
                                onClick = {
                                    viewModel.setDefaultPenalty(value)
                                    showPenaltyDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPenaltyDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Text(
        text = title,
        color = SolanaGreen,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
    content()
}

@Composable
private fun SettingsCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GraphiteSurface)
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onClick)
                else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = SolanaGreen,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 16.sp
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }
        }

        if (trailingContent != null) {
            trailingContent()
        } else if (onClick != null) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "Navigate to $title",
                tint = TextSecondary
            )
        }
    }
}
