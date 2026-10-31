package app.solarma.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.solarma.MainActivity

/**
 * Prompts the user to open Solarma right after the wake proof so the queued
 * ack_awake + claim can be signed before the on-chain deadline.
 */
object ClaimReminderNotifier {
    private const val CHANNEL_ID = "solarma_claim_channel"
    private const val NOTIFICATION_ID_OFFSET = 100_000

    fun show(
        context: Context,
        alarmId: Long,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Deposit claims", NotificationManager.IMPORTANCE_HIGH),
            )
        }

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID_OFFSET + alarmId.toInt(),
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("You're awake — get your deposit back")
                .setContentText("Open Solarma and approve in your wallet before the deadline.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

        manager.notify(NOTIFICATION_ID_OFFSET + alarmId.toInt(), notification)
    }
}
