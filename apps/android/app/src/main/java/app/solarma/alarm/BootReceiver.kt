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

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        // Only the protected system broadcast is accepted. The receiver is exported
        // (required for BOOT_COMPLETED), so vendor QUICKBOOT actions would let any
        // app trigger alarm restoration and its missed-alarm slash queueing.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed, scheduling alarm restoration")

            // Use WorkManager for reliable execution
            // This is better than starting a foreground service directly
            RestoreAlarmsWorker.enqueue(context)
        }
    }
}
