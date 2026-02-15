package app.solarma.alarm

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Worker that restores all scheduled alarms after device boot.
 * More reliable than starting a foreground service from BootReceiver.
 */
@HiltWorker
class RestoreAlarmsWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val alarmRepository: AlarmRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "Solarma.RestoreWorker"
        const val WORK_NAME = "restore_alarms"

        /**
         * Enqueue unique work to restore alarms.
         * Called from BootReceiver.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<RestoreAlarmsWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )

            Log.i(TAG, "Enqueued restore alarms work")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting alarm restoration")

        return try {
            alarmRepository.restoreAllAlarms()
            Log.i(TAG, "Alarm restoration complete")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Alarm restoration failed", e)
            Result.retry()
        }
    }
}
