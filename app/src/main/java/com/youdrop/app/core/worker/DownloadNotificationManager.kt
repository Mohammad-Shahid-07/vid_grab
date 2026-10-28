package com.youdrop.app.core.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.graphics.ColorUtils
import androidx.work.ForegroundInfo
import android.widget.RemoteViews
import com.youdrop.app.MainActivity
import com.youdrop.app.R
import com.youdrop.app.core.database.DownloadTaskEntity
import com.youdrop.app.core.database.TaskStatus
import com.youdrop.app.core.format.formatEta
import com.youdrop.app.ui.theme.ThemeManager
import java.util.Locale

object DownloadNotificationManager {

    const val CHANNEL_ID = "media_downloads_channel"
    const val FOREGROUND_NOTIFICATION_ID = 1001

    private val notificationIdMap = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val nextNotificationId = java.util.concurrent.atomic.AtomicInteger(10000)

    fun getNotificationId(taskId: String): Int {
        if (taskId.isBlank()) return FOREGROUND_NOTIFICATION_ID
        return notificationIdMap.computeIfAbsent(taskId) {
            val id = nextNotificationId.getAndIncrement()
            if (id > 2000000) nextNotificationId.set(10000)
            id
        }
    }

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
        val isIndeterminate = progressPercent <= 0f && downloadedBytes <= 0L && status == TaskStatus.DOWNLOADING

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
            sanitizedSpeed.isNotBlank() && etaSeconds > 0 -> "$sanitizedSpeed • ETA ${formatEta(etaSeconds)}"
            sanitizedSpeed.isNotBlank() -> sanitizedSpeed
            etaSeconds > 0 -> "ETA ${formatEta(etaSeconds)}"
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

        // Follow the app's active accent instead of the retired gold, and keep the custom
        // RemoteViews readable in both the light and dark system notification shade.
        val accentArgb = ThemeManager.getInstance(context).accentState.value.color.toArgb()
        val isNightShade = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        // Light shades need a darker accent for readable speed text; dark shades keep the raw accent.
        val notificationAccentColor = if (isNightShade) {
            accentArgb
        } else {
            ColorUtils.blendARGB(accentArgb, android.graphics.Color.BLACK, 0.35f)
        }
        val notificationTextColor = if (isNightShade) {
            android.graphics.Color.WHITE
        } else {
            android.graphics.Color.rgb(24, 24, 27)
        }
        val notificationTrackColor = if (isNightShade) {
            android.graphics.Color.rgb(63, 63, 70)
        } else {
            android.graphics.Color.rgb(228, 228, 231)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_youdrop_download)
            .setColor(notificationAccentColor)
            .setColorized(false)
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
            val pauseResumeIntent = Intent(context, com.youdrop.app.core.receiver.DownloadActionReceiver::class.java).apply {
                action = if (isPaused) com.youdrop.app.core.receiver.DownloadActionReceiver.ACTION_RESUME else com.youdrop.app.core.receiver.DownloadActionReceiver.ACTION_PAUSE
                putExtra(com.youdrop.app.core.receiver.DownloadActionReceiver.EXTRA_TASK_ID, taskId)
            }
            val pauseRequestCode = (taskId.hashCode() and 0x3FFFFFFF) * 2
            val cancelRequestCode = pauseRequestCode + 1
            val pauseResumePending = PendingIntent.getBroadcast(
                context,
                pauseRequestCode,
                pauseResumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val cancelIntent = Intent(context, com.youdrop.app.core.receiver.DownloadActionReceiver::class.java).apply {
                action = com.youdrop.app.core.receiver.DownloadActionReceiver.ACTION_CANCEL
                putExtra(com.youdrop.app.core.receiver.DownloadActionReceiver.EXTRA_TASK_ID, taskId)
            }
            val cancelPending = PendingIntent.getBroadcast(
                context,
                cancelRequestCode,
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
                applyAppThemeColors(notificationAccentColor, notificationTextColor, notificationTrackColor)
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
                applyAppThemeColors(notificationAccentColor, notificationTextColor, notificationTrackColor)
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

    fun postStatusNotification(context: Context, task: DownloadTaskEntity) {
        val notification = buildProgressNotification(
            context = context,
            title = task.title,
            progressPercent = task.progressPercent,
            speedText = task.speedText,
            etaSeconds = task.etaSeconds,
            status = task.status,
            downloadedBytes = task.downloadedBytes,
            totalBytes = task.totalBytes,
            thumbnailBitmap = null,
            taskId = task.id
        )
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(getNotificationId(task.id), notification)
        }.onFailure { timber.log.Timber.w(it, "Could not post status notification for task %s", task.id) }
    }

    fun cancelProgressNotification(context: Context, taskId: String) {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val id = notificationIdMap.remove(taskId) ?: getNotificationId(taskId)
            manager.cancel(id)
        }.onFailure { timber.log.Timber.w(it, "Could not cancel progress notification for task %s", taskId) }
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
            .setColor(ThemeManager.getInstance(context).accentState.value.color.toArgb())
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
    }

    /** Applies the active accent and a shade-appropriate text color to the custom views. */
    private fun RemoteViews.applyAppThemeColors(
        accentColor: Int,
        textColor: Int,
        progressTrackColor: Int
    ) {
        // RemoteViews can only tint a ProgressBar through ColorStateList on Android 12+.
        // Below that the system default tint is used instead of a hardcoded gold.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setColorStateList(R.id.notif_progress, "setProgressTintList", ColorStateList.valueOf(accentColor))
            setColorStateList(
                R.id.notif_progress,
                "setProgressBackgroundTintList",
                ColorStateList.valueOf(progressTrackColor)
            )
        }
        setTextColor(R.id.notif_title, textColor)
        setTextColor(R.id.notif_size, textColor)
        setTextColor(R.id.notif_speed_eta, accentColor)
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
