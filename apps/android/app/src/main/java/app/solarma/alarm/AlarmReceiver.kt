package app.solarma.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives alarm trigger broadcasts from AlarmManager.
 * Starts the AlarmService to handle the wake-up flow.
 */
class AlarmReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "Solarma.AlarmReceiver"
        const val EXTRA_ALARM_ID = "alarm_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1)
        Log.i(TAG, "Alarm triggered: id=$alarmId")

        if (alarmId == -1L) {
            Log.e(TAG, "Invalid alarm ID")
            return
        }

        // Start foreground service for alarm handling
        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_ALARM_TRIGGERED
            putExtra(EXTRA_ALARM_ID, alarmId)
        }

        context.startForegroundService(serviceIntent)
    }
}
