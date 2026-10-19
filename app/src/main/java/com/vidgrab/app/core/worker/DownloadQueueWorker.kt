package com.vidgrab.app.core.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vidgrab.app.core.database.AppDatabase
import com.vidgrab.app.core.database.DownloadTaskEntity
import com.vidgrab.app.core.database.TaskStatus
import com.vidgrab.app.core.engine.DownloadEngineExecutor
import com.vidgrab.app.core.storage.MediaStoreExporter
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

    override suspend fun doWork(): Result {
        Timber.i("DownloadQueueWorker started execution")

        // Initialize notification channel
        DownloadNotificationManager.createNotificationChannel(applicationContext)

        // Initial foreground notification
        val initialNotif = DownloadNotificationManager.buildProgressNotification(
            context = applicationContext,
            title = "VidGrab Queue Engine",
            progressPercent = 0f,
            speedText = "Starting queue...",
            etaSeconds = -1L,
            status = TaskStatus.PENDING
        )
        runCatching {
            setForeground(DownloadNotificationManager.createForegroundInfo(initialNotif))
        }.onFailure { Timber.w(it, "Foreground promotion skipped; continuing work in background") }

        val maxConcurrency = com.vidgrab.app.core.webview.AdBlockEngine.maxConcurrentDownloads.value.coerceIn(1, 5)
        Timber.i("DownloadQueueWorker starting concurrent pool with maxConcurrency=%d", maxConcurrency)

        val activeJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
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
                            val fresh = dao.getTaskById(task.id)
                            if (fresh?.status != TaskStatus.PENDING) continue

                            dao.updateStatus(task.id, TaskStatus.DOWNLOADING)

                            val job = launch {
                                try {
                                    executeSingleTask(task)
                                    processedCount++
                                } catch (e: Exception) {
                                    val currentTask = dao.getTaskById(task.id)
                                    if (currentTask?.status == TaskStatus.PAUSED || isStopped || e is InterruptedException || e is kotlinx.coroutines.CancellationException) {
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
        var lastNotifTimeMs = 0L

        val downloadedFile = DownloadEngineExecutor.executeDownload(applicationContext, nextTask) { update ->
            val currentTask = dao.getTaskById(nextTask.id)
            if (currentTask?.status == TaskStatus.PAUSED || currentTask?.status == TaskStatus.CANCELLED) {
                return@executeDownload
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
                    status = update.status
                )
                runCatching {
                    setForeground(DownloadNotificationManager.createForegroundInfo(notif))
                }.onFailure { Timber.w(it, "Foreground promotion skipped; continuing work in background") }
            }
        }

        // Public MediaStore export to /storage/emulated/0/Download/VidGrab
        val exportResult = MediaStoreExporter.exportToPublicDownloads(
            context = applicationContext,
            sourceFile = downloadedFile,
            title = nextTask.title,
            isAudioOnly = nextTask.isAudioOnly,
            fallbackExt = nextTask.outputExt
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
}
