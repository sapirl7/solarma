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
import app.solarma.wallet.TransactionProcessor
import app.solarma.wallet.TransactionQueue
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Worker that enqueues slash transaction after deadline.
 */
@HiltWorker
class SlashAlarmWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val alarmRepository: AlarmRepository,
        private val transactionQueue: TransactionQueue,
    ) : CoroutineWorker(context, params) {
        companion object {
            private const val TAG = "Solarma.SlashWorker"
            private const val KEY_ALARM_ID = "alarm_id"

            fun enqueue(
                context: Context,
                alarmId: Long,
                delayMillis: Long,
            ) {
                val request =
                    OneTimeWorkRequestBuilder<SlashAlarmWorker>()
                        .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf(KEY_ALARM_ID to alarmId))
                        .build()

                WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                        "slash_alarm_$alarmId",
                        ExistingWorkPolicy.REPLACE,
                        request,
                    )
            }
        }

        override suspend fun doWork(): Result {
            val alarmId = inputData.getLong(KEY_ALARM_ID, -1L)
            if (alarmId <= 0) return Result.failure()

            val alarm = alarmRepository.getAlarm(alarmId) ?: return Result.success()
            if (!alarm.hasDeposit) return Result.success()

            // SECURITY: If user already completed wake proof, do not slash
            val lastTriggered = alarm.lastTriggeredAt ?: alarm.alarmTimeMillis
            val completed = alarm.lastCompletedAt
            if (completed != null && completed >= lastTriggered) {
                Log.i(TAG, "Skipping slash for alarm $alarmId — already completed")
                return Result.success()
            }

            // If a claim is already queued, user woke up — skip slash
            if (transactionQueue.hasActive(TransactionProcessor.TYPE_ACK_AND_CLAIM, alarmId) ||
                transactionQueue.hasActive(TransactionProcessor.TYPE_CLAIM, alarmId)
            ) {
                Log.i(TAG, "Skipping slash for alarm $alarmId — claim already queued")
                return Result.success()
            }

            // Re-check against the on-chain deadline: a snooze that landed moved it later.
            val remaining = AlarmTiming.deadlineMillis(alarm) - System.currentTimeMillis()
            if (remaining > 0) {
                enqueue(applicationContext, alarmId, remaining)
                return Result.success()
            }

            // The processor re-reads the chain and waits out the buddy-only window if needed.
            if (!transactionQueue.hasActive(TransactionProcessor.TYPE_SLASH, alarmId)) {
                transactionQueue.enqueue(type = TransactionProcessor.TYPE_SLASH, alarmId = alarmId)
                Log.i(TAG, "Queued slash for alarm $alarmId")
            }

            return Result.success()
        }
    }
