package app.solarma.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives BOOT_COMPLETED broadcast to restore alarms after device reboot.
 * Critical for alarm reliability.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "Solarma.BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Device booted, restoring alarms...")
            
            // Launch foreground service to restore alarms
            val serviceIntent = Intent(context, AlarmService::class.java).apply {
                action = AlarmService.ACTION_RESTORE_ALARMS
            }
            
            context.startForegroundService(serviceIntent)
        }
    }
}
