package app.solarma.alarm

import android.Manifest
import android.app.PendingIntent
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.solarma.data.local.AlarmEntity
import app.solarma.ui.theme.SolarmaTheme
import app.solarma.wakeproof.WakeProofEngine
import app.solarma.wakeproof.WakeProgress
import app.solarma.wakeproof.QrScanner
import app.solarma.wakeproof.NfcScanner
import app.solarma.ui.components.QrCameraPreview
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import androidx.work.WorkManager

/**
 * Full-screen alarm activity shown over lock screen.
 * ENFORCES wake proof completion before allowing dismissal.
 */
@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "Solarma.AlarmActivity"
    }
    
    @Inject
    lateinit var alarmRepository: AlarmRepository
    
    @Inject
    lateinit var wakeProofEngine: WakeProofEngine
    
    @Inject
    lateinit var transactionQueue: app.solarma.wallet.TransactionQueue
    
    @Inject
    lateinit var nfcScanner: NfcScanner
    
    @Inject
    lateinit var qrScanner: QrScanner
    
    // ActivityResultSender for MWA transactions (claim/snooze)
    private val activityResultSender by lazy {
        ActivityResultSender(this)
    }
    
    private var alarmId: Long = -1
    private var currentAlarm: AlarmEntity? = null
    private var pendingPermissionAlarm: AlarmEntity? = null
    private var nfcAdapter: NfcAdapter? = null
    private var nfcPendingIntent: PendingIntent? = null
    
    // Permission launcher for step counter (Android 10+)
    private val stepPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        handlePermissionResult(isGranted, WakeProofEngine.TYPE_STEPS)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        handlePermissionResult(isGranted, WakeProofEngine.TYPE_QR)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        alarmId = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1)
        
        // Show over lock screen
        setupLockScreenFlags()
        
        // Initialize NFC foreground dispatch
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        nfcPendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

        startWakeProof()
        
        // Observe wake proof completion
        lifecycleScope.launch {
            wakeProofEngine.isComplete.collect { isComplete ->
                if (isComplete) {
                    Log.i(TAG, "Wake proof completed, processing completion")
                    alarmRepository.markCompleted(alarmId, success = true)
                    // Queue on-chain transactions if alarm has deposit
                    currentAlarm?.let { alarm ->
                        if (alarm.hasDeposit && alarm.onchainPubkey != null) {
                            // H3: Queue ack_awake first (records proof on-chain)
                            Log.i(TAG, "Queueing ack_awake + claim for alarm ${alarm.id}")
                            queueAckAwake(alarm)
                            queueClaimDeposit(alarm)
                        }
                    }
                    dismissAlarm()
                }
            }
        }
        
        setContent {
            val progress by wakeProofEngine.progress.collectAsState()
            val isComplete by wakeProofEngine.isComplete.collectAsState()
            
            SolarmaTheme {
                AlarmScreen(
                    progress = progress,
                    isComplete = isComplete,
                    alarm = currentAlarm,
                    qrScanner = qrScanner,
                    onSnooze = { snoozeAlarm() },
                    onConfirmAwake = { wakeProofEngine.confirmAwake() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(
            this,
            nfcPendingIntent,
            arrayOf(IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)),
            null
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TECH_DISCOVERED) {
            val tag = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
            if (tag != null) {
                Log.i(TAG, "NFC tag detected in alarm flow")
                nfcScanner.handleTag(tag)
            }
        }
    }
    
    private fun startWakeProof() {
        lifecycleScope.launch {
            try {
                currentAlarm = alarmRepository.getAlarm(alarmId)
                currentAlarm?.let { alarm ->
                    Log.i(TAG, "Starting wake proof for alarm: ${alarm.id}, type=${alarm.wakeProofType}")
                    if (alarm.wakeProofType == WakeProofEngine.TYPE_STEPS &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        ContextCompat.checkSelfPermission(
                            this@AlarmActivity, Manifest.permission.ACTIVITY_RECOGNITION
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingPermissionAlarm = alarm
                        Log.i(TAG, "Requesting ACTIVITY_RECOGNITION permission")
                        stepPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                        return@launch
                    }

                    if (alarm.wakeProofType == WakeProofEngine.TYPE_QR &&
                        ContextCompat.checkSelfPermission(
                            this@AlarmActivity, Manifest.permission.CAMERA
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingPermissionAlarm = alarm
                        Log.i(TAG, "Requesting CAMERA permission")
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        return@launch
                    }

                    wakeProofEngine.start(alarm, lifecycleScope)
                } ?: run {
                    Log.e(TAG, "Alarm not found: $alarmId - using fallback")
                    // Fallback: create dummy alarm with TYPE_NONE for graceful handling
                    val fallbackAlarm = AlarmEntity(
                        id = alarmId,
                        alarmTimeMillis = System.currentTimeMillis(),
                        wakeProofType = WakeProofEngine.TYPE_NONE
                    )
                    currentAlarm = fallbackAlarm
                    wakeProofEngine.start(fallbackAlarm, lifecycleScope)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting wake proof", e)
                // SECURITY: Do NOT auto-confirm deposit alarms on error.
                // This prevents bypassing the commitment mechanism.
                if (currentAlarm?.hasDeposit != true) {
                    wakeProofEngine.confirmAwake()
                } else {
                    Log.e(TAG, "Deposit alarm proof error — NOT auto-confirming")
                }
            }
        }
    }

    private fun handlePermissionResult(isGranted: Boolean, type: Int) {
        val alarm = pendingPermissionAlarm
        if (alarm == null || alarm.wakeProofType != type) {
            return
        }

        if (isGranted) {
            wakeProofEngine.start(alarm, lifecycleScope)
        } else {
            // SECURITY: Deposit alarms must NOT get a free fallback on permission denial
            if (alarm.hasDeposit) {
                Log.w(TAG, "Permission denied for deposit alarm — no fallback")
            } else {
                val message = when (type) {
                    WakeProofEngine.TYPE_STEPS -> "Motion permission denied. Tap to confirm you're awake."
                    WakeProofEngine.TYPE_QR -> "Camera permission denied. Tap to confirm you're awake."
                    else -> "Permission denied. Tap to confirm you're awake."
                }
                wakeProofEngine.activateFallback(message)
            }
        }

        pendingPermissionAlarm = null
    }
    
    private fun setupLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    
    /**
     * Dismiss alarm - ONLY called when wake proof is complete.
     */
    private fun dismissAlarm() {
        wakeProofEngine.stop()
        
        // SECURITY: Cancel pending slash worker — user proved they're awake
        WorkManager.getInstance(this)
            .cancelUniqueWork("slash_alarm_$alarmId")
        Log.i(TAG, "Cancelled slash worker for alarm $alarmId")
        
        // Stop the alarm service
        val intent = Intent(this, AlarmService::class.java).apply {
            action = AlarmService.ACTION_STOP_ALARM
        }
        startService(intent)
        finish()
    }
    
    private fun snoozeAlarm() {
        lifecycleScope.launch {
            alarmRepository.snooze(alarmId)
            // Queue snooze transaction if alarm has onchain deposit
            currentAlarm?.let { alarm ->
                if (alarm.hasDeposit && alarm.onchainPubkey != null) {
                    queueSnoozeTransaction(alarm)
                }
            }
        }
        wakeProofEngine.stop()
        
        val intent = Intent(this, AlarmService::class.java).apply {
            action = AlarmService.ACTION_SNOOZE
        }
        startService(intent)
        finish()
    }
    
    /**
     * Queue snooze transaction for processing.
     */
    private suspend fun queueSnoozeTransaction(alarm: AlarmEntity) {
        try {
            val queueId = transactionQueue.enqueue(
                type = "SNOOZE",
                alarmId = alarm.id
            )
            Log.i(TAG, "Snooze transaction queued: queueId=$queueId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue snooze transaction", e)
        }
    }
    
    /**
     * Queue claim transaction for processing.
     * Since we're on lock screen, MWA can't open wallet popup.
     * Transaction will be processed when user returns to main app.
     */
    /**
     * H3: Queue ack_awake transaction to record wake proof on-chain.
     */
    private fun queueAckAwake(alarm: AlarmEntity) {
        lifecycleScope.launch {
            try {
                val queueId = transactionQueue.enqueue(
                    type = "ACK_AWAKE",
                    alarmId = alarm.id
                )
                Log.i(TAG, "ACK_AWAKE queued: queueId=$queueId, alarmId=${alarm.id}")
            } catch (e: Exception) {
                // Non-blocking: if ack fails, claim can still proceed
                Log.w(TAG, "Failed to queue ack_awake (non-critical)", e)
            }
        }
    }
    
    private fun queueClaimDeposit(alarm: AlarmEntity) {
        lifecycleScope.launch {
            try {
                val queueId = transactionQueue.enqueue(
                    type = "CLAIM",
                    alarmId = alarm.id
                )
                Log.i(TAG, "Claim transaction queued: queueId=$queueId, alarmId=${alarm.id}")
                
                // Show toast to user
                android.widget.Toast.makeText(
                    this@AlarmActivity,
                    "Deposit claim queued. Open app to complete.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to queue claim transaction", e)
            }
        }
    }
    
    override fun onDestroy() {
        wakeProofEngine.stop()
        super.onDestroy()
    }
}

@Composable
fun AlarmScreen(
    progress: WakeProgress,
    isComplete: Boolean,
    alarm: AlarmEntity?,
    qrScanner: QrScanner,
    onSnooze: () -> Unit,
    onConfirmAwake: () -> Unit
) {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            currentTime = LocalTime.now()
        }
    }
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    
    // Pulsing animation for the sun icon
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1a1a2e),
                        Color(0xFF16213e),
                        Color(0xFF0f3460)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            
            // Time display
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isComplete) "✅" else "☀️",
                    fontSize = 80.sp,
                    modifier = Modifier.scale(scale)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = currentTime.format(timeFormatter),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                Text(
                    text = if (isComplete) "Good morning!" else "Wake up!",
                    fontSize = 24.sp,
                    color = if (isComplete) Color(0xFF4CAF50) else Color(0xFFFFD700)
                )
            }
            
            // Challenge progress
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Progress indicator
                if (!isComplete) {
                    if (progress.fallbackActive) {
                        FallbackView(progress.message, onConfirmAwake)
                    } else {
                        when (progress.type) {
                            WakeProofEngine.TYPE_STEPS -> {
                                StepsProgressView(progress)
                            }
                            WakeProofEngine.TYPE_NFC -> {
                                NfcProgressView(progress)
                            }
                            WakeProofEngine.TYPE_QR -> {
                                QrProgressView(progress, qrScanner)
                            }
                            WakeProofEngine.TYPE_NONE -> {
                                NoProofView(onConfirmAwake)
                            }
                            else -> {
                                Text(
                                    text = progress.message,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    // Completed state
                    Text(
                        text = "Challenge complete! 🎉",
                        fontSize = 20.sp,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Error display
                progress.error?.let { error ->
                    Text(
                        text = error,
                        color = Color(0xFFFF6B6B),
                        fontSize = 14.sp
                    )
                }

                if (!isComplete &&
                    progress.requiresAction &&
                    progress.type != WakeProofEngine.TYPE_NONE &&
                    !progress.fallbackActive
                ) {
                    Button(
                        onClick = onConfirmAwake,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF8C00)
                        )
                    ) {
                        Text("I'm Awake! ☀️", fontSize = 18.sp)
                    }
                }
            }
            
            // Snooze button (always available)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextButton(
                    onClick = onSnooze,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.White.copy(alpha = 0.7f)
                    )
                ) {
                    Text("😴 Snooze (5 min)", fontSize = 14.sp)
                }
                
                // Deposit warning
                if (alarm?.hasDeposit == true) {
                    Text(
                        text = "⚠️ Snooze costs SOL",
                        color = Color(0xFFFF9800),
                        fontSize = 12.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun StepsProgressView(progress: WakeProgress) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "🚶 Walk to dismiss",
            fontSize = 16.sp,
            color = Color.White.copy(alpha = 0.7f)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Progress bar
        @Suppress("DEPRECATION")
        LinearProgressIndicator(
            progress = progress.progressPercent.coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = Color(0xFFFF8C00),
            trackColor = Color.White.copy(alpha = 0.2f)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "${progress.currentValue} / ${progress.targetValue} steps",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
fun NfcProgressView(progress: WakeProgress) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "📱",
            fontSize = 64.sp
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = progress.message,
            fontSize = 18.sp,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun NoProofView(onConfirm: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Confirm you're awake",
            fontSize = 16.sp,
            color = Color.White.copy(alpha = 0.7f)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF8C00)
            )
        ) {
            Text("I'm Awake! ☀️", fontSize = 18.sp)
        }
    }
}

@Composable
fun FallbackView(message: String, onConfirm: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (message.isNotBlank()) {
            Text(
                text = message,
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        NoProofView(onConfirm)
    }
}

@Composable
fun QrProgressView(
    progress: WakeProgress,
    qrScanner: QrScanner
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "📷 Scan QR Code",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = progress.message,
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.8f),
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Camera preview
        QrCameraPreview(
            qrScanner = qrScanner,
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
        )
        
        // Error message if any
        if (progress.error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = progress.error!!,
                fontSize = 14.sp,
                color = Color(0xFFFF5252),
                textAlign = TextAlign.Center
            )
        }
    }
}
