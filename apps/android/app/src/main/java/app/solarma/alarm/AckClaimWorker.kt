package app.solarma.alarm

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.solarma.wallet.DeviceAckService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Returns the deposit right after the wake proof using the device key (no wallet),
 * retrying while the network or chain catches up. If the device key can't do it,
 * hands over to the wallet flow and asks the user to open the app.
 */
@HiltWorker
class AckClaimWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val deviceAckService: DeviceAckService,
    ) : CoroutineWorker(context, params) {
        companion object {
            private const val TAG = "Solarma.AckClaimWorker"
            private const val KEY_ALARM_ID = "alarm_id"

            /** ~10 attempts with linear backoff stays well inside the 30-minute deadline window. */
            private const val MAX_ATTEMPTS = 10

            fun enqueue(
                context: Context,
                alarmId: Long,
            ) {
                val request =
                    OneTimeWorkRequestBuilder<AckClaimWorker>()
                        .setInputData(workDataOf(KEY_ALARM_ID to alarmId))
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                        .build()

                WorkManager.getInstance(context)
                    .enqueueUniqueWork("ack_claim_$alarmId", ExistingWorkPolicy.KEEP, request)
            }
        }

        override suspend fun doWork(): Result {
            val alarmId = inputData.getLong(KEY_ALARM_ID, -1L)
            if (alarmId <= 0) return Result.failure()

            val outcome =
                try {
                    deviceAckService.ackAndClaim(alarmId)
                } catch (e: Exception) {
                    Log.e(TAG, "Device ack failed for alarm $alarmId", e)
                    DeviceAckService.Outcome.RETRY
                }
            Log.i(TAG, "Alarm $alarmId: $outcome (attempt ${runAttemptCount + 1})")

            return when {
                outcome == DeviceAckService.Outcome.RETURNED -> {
                    ClaimReminderNotifier.showReturned(applicationContext, alarmId)
                    Result.success()
                }
                outcome == DeviceAckService.Outcome.DONE -> Result.success()
                outcome == DeviceAckService.Outcome.RETRY && runAttemptCount + 1 < MAX_ATTEMPTS -> Result.retry()
                else -> {
                    // Device key can't (or keeps failing): sign with the wallet when the app is opened
                    deviceAckService.fallBackToWallet(alarmId)
                    ClaimReminderNotifier.show(applicationContext, alarmId)
                    Result.success()
                }
            }
        }
    }
