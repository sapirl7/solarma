package app.solarma.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules alarms using AlarmManager with exact timing.
 * Uses setExactAndAllowWhileIdle for reliability in Doze mode.
 */
@Singleton
class AlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "Solarma.AlarmScheduler"
        private const val REQUEST_CODE_BASE = 10000
        private const val REQUEST_CODE_MOD = 1_000_000
    }
    
    private val alarmManager: AlarmManager by lazy {
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
    
    /**
     * Schedule an alarm for the given time.
     * @param alarmId Unique identifier for the alarm
     * @param triggerAtMillis Exact time to trigger in milliseconds
     */
    fun schedule(alarmId: Long, triggerAtMillis: Long) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCodeFor(alarmId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Use setExactAndAllowWhileIdle for maximum reliability
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: Check canScheduleExactAlarms
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.i(TAG, "Alarm scheduled: id=$alarmId, time=$triggerAtMillis")
            } else {
                Log.e(TAG, "Cannot schedule exact alarms - permission not granted")
                // Fallback to inexact alarm
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } else {
            // Pre-Android 12
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            Log.i(TAG, "Alarm scheduled: id=$alarmId, time=$triggerAtMillis")
        }
    }
    
    /**
     * Cancel a scheduled alarm.
     */
    fun cancel(alarmId: Long) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCodeFor(alarmId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.cancel(pendingIntent)
        Log.i(TAG, "Alarm cancelled: id=$alarmId")
    }
    
    /**
     * Check if exact alarms can be scheduled (Android 12+).
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    private fun requestCodeFor(alarmId: Long): Int {
        val hash = (alarmId xor (alarmId ushr 32)).toInt() and 0x7fffffff
        return REQUEST_CODE_BASE + (hash % REQUEST_CODE_MOD)
    }
}
