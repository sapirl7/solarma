package app.solarma.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.solarma.MainActivity
import app.solarma.wallet.PendingTransactionDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Worker that notifies user to open app and finalize pending deposit.
 */
@HiltWorker
class PendingCreateNotificationWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val pendingTransactionDao: PendingTransactionDao,
    ) : CoroutineWorker(context, params) {
        companion object {
            private const val TAG = "Solarma.PendingCreateWorker"
            private const val CHANNEL_ID = "solarma_tx_channel"
            private const val KEY_ALARM_ID = "alarm_id"

            fun enqueue(
                context: Context,
                alarmId: Long,
            ) {
                val request =
                    OneTimeWorkRequestBuilder<PendingCreateNotificationWorker>()
                        .setInputData(workDataOf(KEY_ALARM_ID to alarmId))
                        .build()

                WorkManager.getInstance(context)
                    .enqueueUniqueWork(
                        "pending_create_$alarmId",
                        ExistingWorkPolicy.REPLACE,
                        request,
                    )
            }
        }

        override suspend fun doWork(): Result {
            val alarmId = inputData.getLong(KEY_ALARM_ID, -1L)
            if (alarmId <= 0) return Result.failure()

            val pendingCount = pendingTransactionDao.countActiveByTypeAndAlarm("CREATE_ALARM", alarmId)
            if (pendingCount <= 0) return Result.success()

            createChannelIfNeeded()
            showNotification(alarmId)
            Log.i(TAG, "Pending create notification shown for alarm $alarmId")
            return Result.success()
        }

        private fun showNotification(alarmId: Long) {
            val intent =
                Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            val pendingIntent =
                PendingIntent.getActivity(
                    applicationContext,
                    alarmId.toInt(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notification =
                NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Deposit pending confirmation")
                    .setContentText("Open Solarma to finalize your onchain alarm.")
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()

            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(alarmId.toInt(), notification)
        }

        private fun createChannelIfNeeded() {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing != null) return
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Solarma Transactions",
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            manager.createNotificationChannel(channel)
        }
    }
