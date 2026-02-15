package app.solarma.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives BOOT_COMPLETED broadcast to restore alarms after device reboot.
 * Uses WorkManager for reliable execution (P1 improvement).
 */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "Solarma.BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON") {

            Log.i(TAG, "Boot completed, scheduling alarm restoration")

            // Use WorkManager for reliable execution
            // This is better than starting a foreground service directly
            RestoreAlarmsWorker.enqueue(context)
        }
    }
}
