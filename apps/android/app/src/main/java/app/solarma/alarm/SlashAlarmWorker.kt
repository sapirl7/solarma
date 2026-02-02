package app.solarma.alarm

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.solarma.wallet.TransactionQueue
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Worker that enqueues slash transaction after deadline.
 */
@HiltWorker
class SlashAlarmWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val alarmRepository: AlarmRepository,
    private val transactionQueue: TransactionQueue
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "Solarma.SlashWorker"
        private const val KEY_ALARM_ID = "alarm_id"

        fun enqueue(context: Context, alarmId: Long, delayMillis: Long) {
            val request = OneTimeWorkRequestBuilder<SlashAlarmWorker>()
                .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_ALARM_ID to alarmId))
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "slash_alarm_$alarmId",
                    ExistingWorkPolicy.REPLACE,
                    request
                )
        }
    }

    override suspend fun doWork(): Result {
        val alarmId = inputData.getLong(KEY_ALARM_ID, -1L)
        if (alarmId <= 0) return Result.failure()

        val alarm = alarmRepository.getAlarm(alarmId) ?: return Result.success()
        if (!alarm.hasDeposit) return Result.success()

        if (!transactionQueue.hasActive("SLASH", alarmId)) {
            transactionQueue.enqueue(type = "SLASH", alarmId = alarmId)
            Log.i(TAG, "Queued slash for alarm $alarmId")
        }

        return Result.success()
    }
}
