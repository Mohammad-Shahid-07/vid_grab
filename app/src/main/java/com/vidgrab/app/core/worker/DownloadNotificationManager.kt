package com.vidgrab.app.core.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import android.widget.RemoteViews
import com.vidgrab.app.MainActivity
import com.vidgrab.app.R
import com.vidgrab.app.core.database.TaskStatus
import java.util.Locale

object DownloadNotificationManager {

    const val CHANNEL_ID = "media_downloads_channel"
    const val FOREGROUND_NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Live progress and status of active YouTube media downloads"
                setShowBadge(false)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun buildProgressNotification(
        context: Context,
        title: String,
        progressPercent: Float,
        speedText: String,
        etaSeconds: Long,
        status: TaskStatus,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        thumbnailBitmap: android.graphics.Bitmap? = null,
        taskId: String = ""
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pctInt = progressPercent.toInt().coerceIn(0, 100)
        val isIndeterminate = progressPercent <= 0f && status != TaskStatus.DOWNLOADING

        val sizeString = if (totalBytes > 0L) {
            val dlMb = downloadedBytes.toFloat() / (1024f * 1024f)
            val totMb = totalBytes.toFloat() / (1024f * 1024f)
            String.format(Locale.US, "%.1f MB / %.1f MB (%d%%)", dlMb, totMb, pctInt)
        } else if (downloadedBytes > 0L) {
            val dlMb = downloadedBytes.toFloat() / (1024f * 1024f)
            String.format(Locale.US, "%.1f MB (%d%%)", dlMb, pctInt)
        } else {
            "Preparing download..."
        }

        val sanitizedSpeed = when {
            speedText.contains("ffmpeg", ignoreCase = true) ||
            speedText.contains("merger", ignoreCase = true) ||
            speedText.contains("mux", ignoreCase = true) ||
            speedText.contains("-c copy", ignoreCase = true) -> ""
            else -> speedText
        }

        val speedEtaString = when {
            status == TaskStatus.PAUSED -> "Paused"
            status == TaskStatus.MERGING -> "Merging audio and video..."
            status == TaskStatus.EXPANDING -> "Expanding playlist..."
            sanitizedSpeed.isNotBlank() && etaSeconds > 0 -> "$sanitizedSpeed • ETA ${etaSeconds}s"
            sanitizedSpeed.isNotBlank() -> sanitizedSpeed
            etaSeconds > 0 -> "ETA ${etaSeconds}s"
            else -> "Downloading..."
        }

        val badgeText = when (status) {
            TaskStatus.PAUSED -> "PAUSED"
            TaskStatus.MERGING -> "MERGING"
            TaskStatus.EXPANDING -> "EXPANDING"
            else -> "DOWNLOADING"
        }

        val notificationContentText = when {
            status == TaskStatus.MERGING -> "Merging audio and video..."
            status == TaskStatus.PAUSED -> "Paused"
            status == TaskStatus.EXPANDING -> "Expanding playlist..."
            else -> "$sizeString • $speedEtaString"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vidgrab_download)
            .setColor(0xFFE5A93C.toInt())
            .setColorized(true)
            .setContentTitle(title)
            .setContentText(notificationContentText)
            .setSubText(badgeText)
            .setOngoing(status != TaskStatus.PAUSED && status != TaskStatus.COMPLETED)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        // Attach action buttons for Pause/Resume and Cancel
        if (taskId.isNotBlank()) {
            val isPaused = status == TaskStatus.PAUSED
            val pauseResumeIntent = Intent(context, com.vidgrab.app.core.receiver.DownloadActionReceiver::class.java).apply {
                action = if (isPaused) com.vidgrab.app.core.receiver.DownloadActionReceiver.ACTION_RESUME else com.vidgrab.app.core.receiver.DownloadActionReceiver.ACTION_PAUSE
                putExtra(com.vidgrab.app.core.receiver.DownloadActionReceiver.EXTRA_TASK_ID, taskId)
            }
            val pauseResumePending = PendingIntent.getBroadcast(
                context,
                101,
                pauseResumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val cancelIntent = Intent(context, com.vidgrab.app.core.receiver.DownloadActionReceiver::class.java).apply {
                action = com.vidgrab.app.core.receiver.DownloadActionReceiver.ACTION_CANCEL
                putExtra(com.vidgrab.app.core.receiver.DownloadActionReceiver.EXTRA_TASK_ID, taskId)
            }
            val cancelPending = PendingIntent.getBroadcast(
                context,
                102,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                pauseResumePending
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPending
            )
        }

        try {
            // Custom Compact Layout
            val remoteViewsSmall = RemoteViews(context.packageName, R.layout.notification_download_custom).apply {
                setTextViewText(R.id.notif_title, title)
                setProgressBar(R.id.notif_progress, 100, pctInt, isIndeterminate)
                setTextViewText(R.id.notif_size, sizeString)
                setTextViewText(R.id.notif_speed_eta, speedEtaString)
                if (thumbnailBitmap != null) {
                    setImageViewBitmap(R.id.notif_thumbnail, thumbnailBitmap)
                }
            }

            // Custom Expanded Layout
            val remoteViewsBig = RemoteViews(context.packageName, R.layout.notification_download_custom_expanded).apply {
                setTextViewText(R.id.notif_title, title)
                setProgressBar(R.id.notif_progress, 100, pctInt, isIndeterminate)
                setTextViewText(R.id.notif_size, sizeString)
                setTextViewText(R.id.notif_speed_eta, speedEtaString)
                if (thumbnailBitmap != null) {
                    setImageViewBitmap(R.id.notif_thumbnail, thumbnailBitmap)
                }
            }

            builder.setCustomContentView(remoteViewsSmall)
            builder.setCustomBigContentView(remoteViewsBig)
            builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
        } catch (e: Throwable) {
            timber.log.Timber.w(e, "Custom RemoteViews attachment skipped, using native layout fallback")
            if (thumbnailBitmap != null) {
                builder.setLargeIcon(thumbnailBitmap)
            }
            builder.setProgress(100, pctInt, isIndeterminate)
        }

        return builder.build()
    }

    fun postCompletionNotification(context: Context, title: String, isAudio: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(if (isAudio) "Audio Download Complete" else "Video Download Complete")
            .setContentText(title)
            .setSubText("YouDrop")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
    }

    fun createForegroundInfo(notification: Notification): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }
}
