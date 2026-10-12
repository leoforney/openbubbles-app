package com.bluebubbles.messaging.services.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bluebubbles.messaging.R
import java.util.concurrent.TimeUnit

/**
 * Sends one queued message (see [OutgoingMessageQueue]). Only the message's database id is passed;
 * all content stays in the database.
 */
class SendMessageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val messageId = inputData.getLong(KEY_MESSAGE_ID, -1L)
        if (messageId < 0) return Result.success()
        return when (OutgoingMessageQueue.sendPending(applicationContext, messageId, runAttemptCount)) {
            OutgoingMessageQueue.Outcome.RETRY -> Result.retry()
            // Failed messages are marked in the database; succeed so the chat's chain continues.
            else -> Result.success()
        }
    }

    /** Required for expedited work on Android 11 and below. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sending messages", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_stat_icon)
            .setContentTitle("Sending message…")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        // Only called below Android 12 (newer versions run expedited work as jobs), where no
        // foreground service type is required.
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val KEY_MESSAGE_ID = "messageId"
        private const val CHANNEL_ID = "com.bluebubbles.outgoing_messages"
        private const val NOTIFICATION_ID = 3884786

        /** Appends the message to its chat's chain so messages in a chat are sent in order. */
        fun enqueue(context: Context, chatId: Long, messageId: Long) {
            val request = OneTimeWorkRequestBuilder<SendMessageWorker>()
                .setInputData(workDataOf(KEY_MESSAGE_ID to messageId))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("outgoing_message")
                .build()
            WorkManager.getInstance(context)
                .beginUniqueWork("send_chat_$chatId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
                .enqueue()
        }
    }
}
