package app.solarma.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service for handling active alarms.
 * Maintains wake lock, plays alarm sound, and manages the challenge flow.
 */
@AndroidEntryPoint
class AlarmService : Service() {

    companion object {
        private const val TAG = "Solarma.AlarmService"
        const val ACTION_ALARM_TRIGGERED = "app.solarma.ALARM_TRIGGERED"
        const val ACTION_RESTORE_ALARMS = "app.solarma.RESTORE_ALARMS"
        const val ACTION_STOP_ALARM = "app.solarma.STOP_ALARM"
        const val ACTION_SNOOZE = "app.solarma.SNOOZE"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "solarma_alarm_channel"
        private const val WAKELOCK_TAG = "Solarma:AlarmWakeLock"
    }

    @Inject
    lateinit var alarmRepository: AlarmRepository

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var currentAlarmId: Long = -1
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ALARM_TRIGGERED -> {
                val alarmId = intent.getLongExtra(AlarmReceiver.EXTRA_ALARM_ID, -1)
                handleAlarmTriggered(alarmId)
            }
            ACTION_RESTORE_ALARMS -> {
                restoreAlarms()
                stopSelf()
            }
            ACTION_STOP_ALARM -> {
                stopAlarm()
            }
            ACTION_SNOOZE -> {
                handleSnooze()
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleAlarmTriggered(alarmId: Long) {
        Log.i(TAG, "Handling alarm: id=$alarmId")
        currentAlarmId = alarmId

        serviceScope.launch {
            alarmRepository.markTriggered(alarmId)
            val alarm = alarmRepository.getAlarm(alarmId) ?: return@launch
            if (alarm.hasDeposit) {
                val deadlineMillis = alarm.alarmTimeMillis + AlarmTiming.GRACE_PERIOD_MILLIS
                val delay = deadlineMillis - System.currentTimeMillis()
                SlashAlarmWorker.enqueue(applicationContext, alarmId, delay)
            }
        }

        // Acquire wake lock
        acquireWakeLock()

        // Start foreground with notification
        val notification = buildAlarmNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Start alarm sound and vibration
        startAlarmSound()
        startVibration()

        // Launch full-screen alarm activity
        launchAlarmActivity(alarmId)
    }

    private fun restoreAlarms() {
        Log.i(TAG, "Restoring alarms after boot")
        serviceScope.launch {
            try {
                val missed = alarmRepository.restoreAllAlarms()
                Log.i(TAG, "Restored alarms, $missed missed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore alarms", e)
            }
        }
    }

    private fun handleSnooze() {
        Log.i(TAG, "Snooze requested for alarm: $currentAlarmId")
        // Stop current alarm sounds
        stopAlarmSound()
        stopVibration()

        // NOTE: Do NOT call alarmRepository.snooze() here.
        // AlarmActivity.snoozeAlarm() already handles snooze logic
        // (reschedule + on-chain transaction). Calling it here too
        // would double-snooze and double-charge the on-chain penalty.

        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        stopSelf()
    }

    fun stopAlarm() {
        Log.i(TAG, "Stopping alarm: $currentAlarmId")
        stopAlarmSound()
        stopVibration()
        stopForeground(STOP_FOREGROUND_REMOVE)
        releaseWakeLock()
        stopSelf()
    }

    // ==================== Wake Lock ====================

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKELOCK_TAG
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minute timeout max
        }
        Log.d(TAG, "Wake lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
        wakeLock = null
    }

    // ==================== Sound ====================

    private fun startAlarmSound() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            Log.d(TAG, "Alarm sound started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm sound", e)
        }
    }

    private fun stopAlarmSound() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
        Log.d(TAG, "Alarm sound stopped")
    }

    // ==================== Vibration ====================

    private fun startVibration() {
        vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(android.os.VibratorManager::class.java)
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.let {
            val pattern = longArrayOf(0, 500, 200, 500) // vibrate pattern
            val effect = VibrationEffect.createWaveform(pattern, 0) // repeat from index 0
            it.vibrate(effect)
            Log.d(TAG, "Vibration started")
        }
    }

    private fun stopVibration() {
        vibrator?.cancel()
        vibrator = null
        Log.d(TAG, "Vibration stopped")
    }

    // ==================== Notification ====================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Solarma Alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alarm notifications"
            setSound(null, null) // Sound handled separately
            enableVibration(false) // Vibration handled separately
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildAlarmNotification(): Notification {
        val fullScreenIntent = Intent(this, AlarmActivity::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, currentAlarmId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AlarmService::class.java).apply {
            action = ACTION_STOP_ALARM
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(this, AlarmService::class.java).apply {
            action = ACTION_SNOOZE
        }
        val snoozePendingIntent = PendingIntent.getService(
            this, 2, snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Solarma Alarm")
            .setContentText("Complete your wake proof challenge!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            // NOTE: "Stop" button removed - must complete wake proof to dismiss
            .addAction(android.R.drawable.ic_popup_sync, "Snooze", snoozePendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun launchAlarmActivity(alarmId: Long) {
        val intent = Intent(this, AlarmActivity::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        stopAlarmSound()
        stopVibration()
        releaseWakeLock()
        super.onDestroy()
    }
}
