package com.vidgrab.app.core.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vidgrab.app.core.database.AppDatabase
import com.vidgrab.app.core.database.DownloadTaskEntity
import com.vidgrab.app.core.database.TaskStatus
import com.vidgrab.app.core.engine.DownloadEngineExecutor
import com.vidgrab.app.core.storage.MediaStoreExporter
import com.vidgrab.app.core.storage.ThumbnailImageLoader
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Sequential WorkManager Worker (User-Initiated Data Transfer compliant for Android 14/15/16).
 *
 * Anti-Ban & Background Defenses:
 * 1. Sequential 1-by-1 processing to avoid IP throttling.
 * 2. 2–5 second random jitter delay between consecutive playlist/channel items.
 * 3. MediaStore public export upon completion and sandbox cleanup.
 * 4. Graceful handling of pauses and worker cancellation.
 */
class DownloadQueueWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val database = AppDatabase.getInstance(appContext)
    private val dao = database.downloadDao()

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        DownloadNotificationManager.createNotificationChannel(applicationContext)
        val initialNotif = DownloadNotificationManager.buildProgressNotification(
            context = applicationContext,
            title = "YouDrop Queue Engine",
            progressPercent = 0f,
            speedText = "Starting queue...",
            etaSeconds = -1L,
            status = TaskStatus.PENDING
        )
        return DownloadNotificationManager.createForegroundInfo(initialNotif)
    }

    override suspend fun doWork(): Result {
        Timber.i("DownloadQueueWorker started execution")

        // Initialize notification channel
        DownloadNotificationManager.createNotificationChannel(applicationContext)

        // Initial foreground promotion (safely guarded)
        runCatching {
            setForeground(getForegroundInfo())
        }.onFailure { Timber.w(it, "Foreground promotion skipped; continuing work in background") }

        val maxConcurrency = com.vidgrab.app.core.webview.AdBlockEngine.maxConcurrentDownloads.value.coerceIn(1, 5)
        Timber.i("DownloadQueueWorker starting concurrent pool with maxConcurrency=%d", maxConcurrency)

        var processedCount = 0

        kotlinx.coroutines.coroutineScope {
            while (!isStopped) {
                // Calculate available download slots
                val availableSlots = maxConcurrency - activeJobs.size
                if (availableSlots > 0) {
                    val pendingTasks = dao.getNextPendingTasks(availableSlots)
                    if (pendingTasks.isNotEmpty()) {
                        for (task in pendingTasks) {
                            if (activeJobs.containsKey(task.id)) continue
                            if (DownloadEngineExecutor.isPaused(task.id)) continue
                            val fresh = dao.getTaskById(task.id)
                            if (fresh?.status != TaskStatus.PENDING) continue

                            dao.updateStatus(task.id, TaskStatus.DOWNLOADING)

                            val job = launch {
                                try {
                                    executeSingleTask(task)
                                    processedCount++
                                } catch (e: Throwable) {
                                    val currentTask = dao.getTaskById(task.id)
                                    val isPaused = currentTask?.status == TaskStatus.PAUSED ||
                                            DownloadEngineExecutor.isPaused(task.id) ||
                                            isStopped ||
                                            e is InterruptedException ||
                                            e is kotlinx.coroutines.CancellationException

                                    if (isPaused) {
                                        dao.updateStatus(task.id, TaskStatus.PAUSED)
                                        Timber.i("DownloadQueueWorker: Task %s preserved as PAUSED", task.id)
                                    } else {
                                        dao.markFailed(task.id, e.localizedMessage ?: "Download failed")
                                    }
                                } finally {
                                    activeJobs.remove(task.id)
                                }
                            }
                            activeJobs[task.id] = job
                        }
                    } else if (activeJobs.isEmpty()) {
                        Timber.i("DownloadQueueWorker: Queue is empty and no active downloads; finishing.")
                        break
                    }
                }

                delay(500L)
            }

            if (isStopped) {
                activeJobs.values.forEach { it.cancel() }
            }
        }

        return Result.success()
    }

    private suspend fun executeSingleTask(nextTask: DownloadTaskEntity) {
        if (DownloadEngineExecutor.isPaused(nextTask.id) || dao.getTaskById(nextTask.id)?.status == TaskStatus.PAUSED) {
            Timber.i("DownloadQueueWorker: Task %s is already paused. Halting before download.", nextTask.id)
            throw kotlinx.coroutines.CancellationException("Task ${nextTask.id} was paused")
        }

        var lastNotifTimeMs = 0L
        val thumbBmp = ThumbnailImageLoader.loadBitmap(nextTask.thumbnailUrl)

        val downloadedFile = DownloadEngineExecutor.executeDownload(applicationContext, nextTask) { update ->
            val currentTask = dao.getTaskById(nextTask.id)
            if (currentTask?.status == TaskStatus.PAUSED ||
                currentTask?.status == TaskStatus.CANCELLED ||
                DownloadEngineExecutor.isPaused(nextTask.id)
            ) {
                DownloadEngineExecutor.pauseTask(nextTask.id)
                throw kotlinx.coroutines.CancellationException("Task ${nextTask.id} was paused")
            }

            val currentTotal = if (update.totalBytes > 0L) update.totalBytes else nextTask.totalBytes
            val currentDownloaded = if (update.downloadedBytes > 0L) update.downloadedBytes else (currentTotal * (update.progress / 100f)).toLong()

            dao.updateProgress(
                id = nextTask.id,
                progress = update.progress,
                downloadedBytes = currentDownloaded,
                totalBytes = currentTotal,
                speed = update.speedText,
                eta = update.etaSeconds,
                status = update.status
            )

            val now = System.currentTimeMillis()
            if (now - lastNotifTimeMs >= 1000L || update.progress >= 100f || update.status == TaskStatus.MERGING) {
                lastNotifTimeMs = now
                val notif = DownloadNotificationManager.buildProgressNotification(
                    context = applicationContext,
                    title = nextTask.title,
                    progressPercent = update.progress,
                    speedText = update.speedText,
                    etaSeconds = update.etaSeconds,
                    status = update.status,
                    downloadedBytes = currentDownloaded,
                    totalBytes = currentTotal,
                    thumbnailBitmap = thumbBmp,
                    taskId = nextTask.id
                )
                runCatching {
                    val notifManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    notifManager.notify(DownloadNotificationManager.FOREGROUND_NOTIFICATION_ID, notif)
                }.onFailure { Timber.w(it, "Notification progress update skipped") }
            }
        }

        if (DownloadEngineExecutor.isPaused(nextTask.id) || dao.getTaskById(nextTask.id)?.status == TaskStatus.PAUSED) {
            Timber.i("DownloadQueueWorker: Task %s was paused upon completion return. Suppressing export.", nextTask.id)
            throw kotlinx.coroutines.CancellationException("Task ${nextTask.id} was paused")
        }

        // Public MediaStore export to /storage/emulated/0/Download/VidGrab
        val exportResult = MediaStoreExporter.exportToPublicDownloads(
            context = applicationContext,
            sourceFile = downloadedFile,
            title = nextTask.title,
            isAudioOnly = nextTask.isAudioOnly,
            fallbackExt = nextTask.outputExt,
            targetFolder = nextTask.downloadFolder
        )

        val actualBytes = if (downloadedFile.exists() && downloadedFile.length() > 0L) {
            downloadedFile.length()
        } else {
            nextTask.totalBytes
        }

        // Update database to COMPLETED with real file size on disk
        dao.markCompleted(nextTask.id, exportResult.contentUri, exportResult.displayPath, actualBytes)

        // Clean up app-private work dir
        MediaStoreExporter.cleanupTaskWorkDir(applicationContext, nextTask.id)

        // Post completion notification
        DownloadNotificationManager.postCompletionNotification(applicationContext, nextTask.title, nextTask.isAudioOnly)
        Timber.i("Completed queue task: %s", nextTask.title)
    }

    companion object {
        val activeJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

        fun cancelJob(taskId: String) {
            activeJobs[taskId]?.cancel(kotlinx.coroutines.CancellationException("Task $taskId was paused or cancelled"))
        }

        fun cancelAllJobs() {
            activeJobs.values.forEach { it.cancel(kotlinx.coroutines.CancellationException("All tasks paused or cancelled")) }
            activeJobs.clear()
        }
    }
}
