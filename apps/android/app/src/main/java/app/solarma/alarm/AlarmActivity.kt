package app.solarma.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.lifecycle.lifecycleScope
import app.solarma.data.local.AlarmEntity
import app.solarma.ui.theme.SolarmaTheme
import app.solarma.wakeproof.WakeProofEngine
import app.solarma.wakeproof.WakeProgress
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

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
    
    private var alarmId: Long = -1
    private var currentAlarm: AlarmEntity? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        alarmId = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1)
        
        // Show over lock screen
        setupLockScreenFlags()
        
        // Load alarm and start wake proof
        lifecycleScope.launch {
            currentAlarm = alarmRepository.getAlarm(alarmId)
            currentAlarm?.let { alarm ->
                wakeProofEngine.start(alarm)
            }
        }
        
        // Observe wake proof completion
        lifecycleScope.launch {
            wakeProofEngine.isComplete.collect { isComplete ->
                if (isComplete) {
                    Log.i(TAG, "Wake proof completed, dismissing alarm")
                    alarmRepository.markCompleted(alarmId, success = true)
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
                    onSnooze = { snoozeAlarm() },
                    onConfirmAwake = { wakeProofEngine.confirmAwake() }
                )
            }
        }
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
        }
        wakeProofEngine.stop()
        
        val intent = Intent(this, AlarmService::class.java).apply {
            action = AlarmService.ACTION_SNOOZE
        }
        startService(intent)
        finish()
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
    onSnooze: () -> Unit,
    onConfirmAwake: () -> Unit
) {
    val currentTime = remember { LocalTime.now() }
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
                    when (progress.type) {
                        WakeProofEngine.TYPE_STEPS -> {
                            StepsProgressView(progress)
                        }
                        WakeProofEngine.TYPE_NFC -> {
                            NfcProgressView(progress)
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
        LinearProgressIndicator(
            progress = { progress.progressPercent },
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
