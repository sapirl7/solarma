package app.solarma.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import app.solarma.ui.theme.SolarmaTheme
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * Full-screen alarm activity shown over lock screen.
 * Displays wake proof challenge options and handles dismissal.
 */
@AndroidEntryPoint
class AlarmActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "Solarma.AlarmActivity"
    }
    
    @Inject
    lateinit var alarmScheduler: AlarmScheduler
    
    private var alarmId: Long = -1
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        alarmId = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1)
        
        // Show over lock screen
        setupLockScreenFlags()
        
        setContent {
            SolarmaTheme {
                AlarmScreen(
                    onDismiss = { dismissAlarm() },
                    onSnooze = { snoozeAlarm() },
                    onStartStepChallenge = { startStepChallenge() },
                    onStartNfcChallenge = { startNfcChallenge() }
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
    
    private fun dismissAlarm() {
        // Stop the alarm service
        val intent = Intent(this, AlarmService::class.java).apply {
            action = AlarmService.ACTION_STOP_ALARM
        }
        startService(intent)
        finish()
    }
    
    private fun snoozeAlarm() {
        val intent = Intent(this, AlarmService::class.java).apply {
            action = AlarmService.ACTION_SNOOZE
        }
        startService(intent)
        finish()
    }
    
    private fun startStepChallenge() {
        // TODO: Launch step counter challenge
        // For now, dismiss after mock challenge
        dismissAlarm()
    }
    
    private fun startNfcChallenge() {
        // TODO: Launch NFC tag scan challenge
        dismissAlarm()
    }
}

@Composable
fun AlarmScreen(
    onDismiss: () -> Unit,
    onSnooze: () -> Unit,
    onStartStepChallenge: () -> Unit,
    onStartNfcChallenge: () -> Unit
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
                    text = "☀️",
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
                    text = "Wake up!",
                    fontSize = 24.sp,
                    color = Color(0xFFFFD700)
                )
            }
            
            // Challenge buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Complete a challenge to stop the alarm",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                
                // Step challenge button
                Button(
                    onClick = onStartStepChallenge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF8C00)
                    )
                ) {
                    Text("🚶 Walk 20 Steps", fontSize = 18.sp)
                }
                
                // NFC challenge button
                OutlinedButton(
                    onClick = onStartNfcChallenge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White
                    )
                ) {
                    Text("📱 Scan NFC Tag", fontSize = 18.sp)
                }
            }
            
            // Bottom buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Snooze button
                TextButton(
                    onClick = onSnooze,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.White.copy(alpha = 0.7f)
                    )
                ) {
                    Text("😴 Snooze (5 min)", fontSize = 14.sp)
                }
                
                // Skip button (only for free alarms)
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = Color.White.copy(alpha = 0.5f)
                    )
                ) {
                    Text("Skip (no deposit)", fontSize = 14.sp)
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
