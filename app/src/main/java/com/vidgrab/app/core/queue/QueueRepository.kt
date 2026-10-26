package com.vidgrab.app.core.queue

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.vidgrab.app.core.database.AppDatabase
import com.vidgrab.app.core.database.DownloadTaskEntity
import com.vidgrab.app.core.database.TaskStatus
import com.vidgrab.app.core.engine.DownloadEngineExecutor
import com.vidgrab.app.core.extractor.DownloadRequestSpec
import com.vidgrab.app.core.extractor.DualEngineMetadataResolver
import com.vidgrab.app.core.storage.MediaStoreExporter
import com.vidgrab.app.core.storage.ThumbnailStore
import com.vidgrab.app.core.worker.DownloadNotificationManager
import com.vidgrab.app.core.worker.DownloadQueueWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import timber.log.Timber
import java.io.File
import java.util.UUID

class QueueRepository(private val context: Context) {

    private val database = AppDatabase.getInstance(context)
    private val dao = database.downloadDao()
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    val allTasks: Flow<List<DownloadTaskEntity>> = dao.getAllTasksFlow()
    val activeTasks: Flow<List<DownloadTaskEntity>> = dao.getActiveTasksFlow()
    val completedTasks: Flow<List<DownloadTaskEntity>> = dao.getCompletedTasksFlow()

    init {
        // Older builds stored the picker's bitrate estimate as the completed size (and only ever
        // let it grow). Correct every completed row once the real file can be queried.
        scope.launch {
            runCatching { reconcileCompletedSizes() }
                .onFailure { Timber.w(it, "Could not reconcile completed download sizes") }
        }
    }

    fun enqueue(spec: DownloadRequestSpec) {
        scope.launch {
            val sponsorJson = spec.sponsorSegments.takeIf { it.isNotEmpty() }?.let { segs ->
                segs.joinToString(";") { "${it.first}-${it.second}" }
            }

            val queueGroupTimestamp = System.currentTimeMillis()

            if (spec.isBatch) {
                if (spec.batchItems.isNotEmpty()) {
                    // Bulk insert pre-selected playlist items with genuine titles and thumbnails
                    val entities = spec.batchItems.mapIndexed { index, item ->
                        val itemBytes = estimateItemBytes(
                            isAudio = spec.mediaType == "AUDIO",
                            targetHeight = spec.targetHeight,
                            durationSeconds = item.durationSeconds
                        )
                        val itemVid = DualEngineMetadataResolver.extractVideoId(item.url)
                        val itemThumb = item.thumbnailUrl?.takeIf { it.isNotBlank() } ?: itemVid?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
                        DownloadTaskEntity(
                            id = UUID.randomUUID().toString(),
                            url = item.url,
                            videoId = itemVid,
                            title = item.title.ifBlank { "${spec.title} (#${index + 1})" },
                            author = item.author.ifBlank { spec.author },
                            thumbnailUrl = itemThumb,
                            isAudioOnly = spec.mediaType == "AUDIO",
                            targetHeight = spec.targetHeight,
                            formatSelector = spec.formatSelector,
                            outputExt = spec.outputExtension,
                            useAria2c = spec.enableAria2c,
                            playlistId = spec.url,
                            playlistTitle = spec.title,
                            playlistIndex = index + 1,
                            queueGroup = queueGroupTimestamp,
                            queueIndex = index + 1,
                            status = TaskStatus.PENDING,
                            totalBytes = itemBytes,
                            cutSponsor = spec.cutSponsor,
                            sponsorSegmentsJson = sponsorJson,
                            embedSubtitles = spec.embedSubtitles,
                            embedChapters = spec.embedChapters,
                            downloadFolder = spec.downloadFolder,
                            videoCodecPreference = spec.videoCodecPreference,
                            mergeAudioExtension = spec.mergeAudioExtension
                        )
                    }
                    dao.insertTasks(entities)
                    prefetchThumbnails(entities)
                    Timber.i("QueueRepository: Enqueued %d batch items for playlist: %s", entities.size, spec.title)
                } else if (spec.batchUrls.isNotEmpty()) {
                    val defaultItemBytes = estimateItemBytes(
                        isAudio = spec.mediaType == "AUDIO",
                        targetHeight = spec.targetHeight,
                        durationSeconds = 240L
                    )
                    val entities = spec.batchUrls.mapIndexed { index, url ->
                        val itemVid = DualEngineMetadataResolver.extractVideoId(url)
                        val itemThumb = itemVid?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
                        DownloadTaskEntity(
                            id = UUID.randomUUID().toString(),
                            url = url,
                            videoId = itemVid,
                            title = "${spec.title} (#${index + 1})",
                            author = spec.author,
                            thumbnailUrl = itemThumb,
                            isAudioOnly = spec.mediaType == "AUDIO",
                            targetHeight = spec.targetHeight,
                            formatSelector = spec.formatSelector,
                            outputExt = spec.outputExtension,
                            useAria2c = spec.enableAria2c,
                            playlistId = spec.url,
                            playlistTitle = spec.title,
                            playlistIndex = index + 1,
                            queueGroup = queueGroupTimestamp,
                            queueIndex = index + 1,
                            status = TaskStatus.PENDING,
                            totalBytes = defaultItemBytes,
                            cutSponsor = spec.cutSponsor,
                            sponsorSegmentsJson = sponsorJson,
                            embedSubtitles = spec.embedSubtitles,
                            embedChapters = spec.embedChapters,
                            downloadFolder = spec.downloadFolder,
                            videoCodecPreference = spec.videoCodecPreference,
                            mergeAudioExtension = spec.mergeAudioExtension
                        )
                    }
                    dao.insertTasks(entities)
                    prefetchThumbnails(entities)
                    Timber.i("QueueRepository: Enqueued %d batch items for playlist: %s", entities.size, spec.title)
                } else {
                    // Lazy Expansion: Expand playlist on the fly via NewPipeExtractor
                    expandAndEnqueuePlaylist(spec)
                }
            } else {
                // Single Video / Audio
                val targetVid = DualEngineMetadataResolver.extractVideoId(spec.url)
                val fallbackThumb = targetVid?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
                val effectiveThumb = spec.thumbnailUrl?.takeIf { it.isNotBlank() } ?: fallbackThumb

                val entity = DownloadTaskEntity(
                    id = spec.id,
                    url = spec.url,
                    videoId = targetVid,
                    title = spec.title,
                    author = spec.author,
                    thumbnailUrl = effectiveThumb,
                    isAudioOnly = spec.mediaType == "AUDIO",
                    targetHeight = spec.targetHeight,
                    formatSelector = spec.formatSelector,
                    outputExt = spec.outputExtension,
                    useAria2c = spec.enableAria2c,
                    queueGroup = queueGroupTimestamp,
                    queueIndex = 0,
                    status = TaskStatus.PENDING,
                    totalBytes = spec.expectedSizeBytes,
                    cutSponsor = spec.cutSponsor,
                    sponsorSegmentsJson = sponsorJson,
                    embedSubtitles = spec.embedSubtitles,
                    embedChapters = spec.embedChapters,
                    downloadFolder = spec.downloadFolder,
                    resolvedVideoStreamUrl = spec.resolvedVideoStreamUrl,
                    resolvedAudioStreamUrl = spec.resolvedAudioStreamUrl,
                    directVideoUrl = spec.directVideoUrl ?: spec.resolvedVideoStreamUrl,
                    directAudioUrl = spec.directAudioUrl ?: spec.resolvedAudioStreamUrl,
                    isDirectMuxed = spec.isDirectMuxed,
                    videoCodecPreference = spec.videoCodecPreference,
                    mergeAudioTrackId = spec.mergeAudioTrackId,
                    mergeAudioTrackName = spec.mergeAudioTrackName,
                    mergeAudioLanguage = spec.mergeAudioLanguage,
                    mergeAudioExtension = spec.mergeAudioExtension,
                    mergeAudioBitrateKbps = spec.mergeAudioBitrateKbps
                )
                dao.insertTask(entity)
                ThumbnailStore.prefetch(effectiveThumb, targetVid)
                Timber.i("QueueRepository: Enqueued single download: %s [%s] with thumb: %s", spec.title, spec.formatSelector, effectiveThumb)
            }

            // Trigger WorkManager UIDT sequential execution
            startQueueWorker()
        }
    }

    private fun prefetchThumbnails(tasks: List<DownloadTaskEntity>) {
        // Warm the local image cache in the background; playback and the worker are never blocked.
        tasks.forEach { task ->
            scope.launch { ThumbnailStore.prefetch(task.thumbnailUrl, task.videoId) }
        }
    }

    private suspend fun reconcileCompletedSizes() {
        val completed = dao.getCompletedTasks()
        if (completed.isEmpty()) return

        var corrected = 0
        for (task in completed) {
            val fromUri = MediaStoreExporter.sizeBytesOf(context, task.outputFileUri)
            val fromPath = task.outputFilePath
                ?.let { path -> File(path).length().takeIf { it > 0L } }
                ?: 0L
            val realSize = fromUri.takeIf { it > 0L } ?: fromPath
            if (realSize > 0L && (task.totalBytes != realSize || task.downloadedBytes != realSize)) {
                dao.correctCompletedSize(task.id, realSize)
                corrected++
            }
        }
        if (corrected > 0) {
            Timber.i("QueueRepository: Corrected file sizes for %d completed task(s)", corrected)
        }
    }

    private fun estimateItemBytes(isAudio: Boolean, targetHeight: Int?, durationSeconds: Long): Long {
        val dur = if (durationSeconds > 0) durationSeconds else 180L
        if (isAudio) {
            return (128000L * dur) / 8L
        }
        val h = targetHeight ?: 720
        val vBitrate = when (h) {
            2160 -> 12000000L
            1440 -> 6000000L
            1080 -> 2500000L
            720 -> 1200000L
            480 -> 650000L
            360 -> 400000L
            240 -> 220000L
            144 -> 110000L
            else -> 1200000L
        }
        return ((vBitrate + 128000L) * dur) / 8L
    }

    private suspend fun expandAndEnqueuePlaylist(spec: DownloadRequestSpec) {
        try {
            val playlistInfo = PlaylistInfo.getInfo(ServiceList.YouTube, spec.url)
            val items = playlistInfo.relatedItems
            val queueGroupTimestamp = System.currentTimeMillis()
            val entities = items.mapIndexedNotNull { index, item ->
                val cleanUrl = when {
                    item.url.isNullOrBlank() -> null
                    item.url.startsWith("http://") || item.url.startsWith("https://") -> item.url
                    item.url.startsWith("/") -> "https://www.youtube.com${item.url}"
                    else -> "https://www.youtube.com/watch?v=${item.url}"
                } ?: return@mapIndexedNotNull null

                val itemBytes = estimateItemBytes(
                    isAudio = spec.mediaType == "AUDIO",
                    targetHeight = spec.targetHeight,
                    durationSeconds = item.duration
                )

                DownloadTaskEntity(
                    id = UUID.randomUUID().toString(),
                    url = cleanUrl,
                    title = item.name.orEmpty().ifBlank { "${spec.title} (#${index + 1})" },
                    author = item.uploaderName.orEmpty(),
                    thumbnailUrl = item.thumbnails.firstOrNull()?.url,
                    isAudioOnly = spec.mediaType == "AUDIO",
                    targetHeight = spec.targetHeight,
                    formatSelector = spec.formatSelector,
                    outputExt = spec.outputExtension,
                    useAria2c = spec.enableAria2c,
                    playlistId = spec.url,
                    playlistTitle = spec.title,
                    playlistIndex = index + 1,
                    queueGroup = queueGroupTimestamp,
                    queueIndex = index + 1,
                    status = TaskStatus.PENDING,
                    totalBytes = itemBytes,
                    cutSponsor = spec.cutSponsor,
                    embedSubtitles = spec.embedSubtitles,
                    embedChapters = spec.embedChapters,
                    videoCodecPreference = spec.videoCodecPreference,
                    mergeAudioExtension = spec.mergeAudioExtension
                )
            }
            dao.insertTasks(entities)
            prefetchThumbnails(entities)
            Timber.i("QueueRepository: Lazily expanded and enqueued %d playlist items", entities.size)
        } catch (e: Exception) {
            Timber.e(e, "Failed to lazily expand playlist: %s", spec.url)
            // Never enqueue a playlist URL as a single PENDING task (which would only download video 1). Mark as FAILED.
            val fallback = DownloadTaskEntity(
                id = spec.id,
                url = spec.url,
                title = spec.title,
                author = spec.author,
                isAudioOnly = spec.mediaType == "AUDIO",
                targetHeight = spec.targetHeight,
                formatSelector = spec.formatSelector,
                outputExt = spec.outputExtension,
                useAria2c = spec.enableAria2c,
                queueGroup = System.currentTimeMillis(),
                queueIndex = 0,
                status = TaskStatus.FAILED,
                errorMessage = "Failed to load playlist: ${e.localizedMessage ?: "Unknown error"}",
                totalBytes = spec.expectedSizeBytes,
                cutSponsor = spec.cutSponsor,
                embedSubtitles = spec.embedSubtitles,
                embedChapters = spec.embedChapters,
                videoCodecPreference = spec.videoCodecPreference,
                mergeAudioExtension = spec.mergeAudioExtension
            )
            dao.insertTask(fallback)
        }
    }

    fun startQueueWorker() {
        scope.launch {
            val activeCount = dao.getActiveDownloadingCount()
            // Always KEEP so existing live worker and active downloads are never cancelled mid-flight
            val policy = ExistingWorkPolicy.KEEP
            val request = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            workManager.enqueueUniqueWork(
                WORK_NAME,
                policy,
                request
            )
            Timber.d("QueueRepository: Scheduled OneTimeWorkRequest for DownloadQueueWorker (activeCount=%d, policy=%s)", activeCount, policy)
        }
    }

    fun pauseTask(taskId: String) {
        DownloadEngineExecutor.pauseTask(taskId)
        DownloadQueueWorker.cancelJob(taskId)
        scope.launch {
            val updatedRows = dao.updateStatusIfNotTerminal(taskId, TaskStatus.PAUSED)
            if (updatedRows > 0) {
                dao.getTaskById(taskId)?.let { task ->
                    DownloadNotificationManager.postStatusNotification(context, task)
                }
                Timber.i("QueueRepository: Paused task %s", taskId)
            }
        }
    }

    fun pauseAll() {
        DownloadEngineExecutor.pauseAllTasks()
        DownloadQueueWorker.cancelAllJobs()
        scope.launch {
            dao.pauseAllTasks()
            Timber.i("QueueRepository: Paused all active tasks")
        }
    }

    fun resumeTask(taskId: String) {
        DownloadEngineExecutor.resumeTask(taskId)
        scope.launch {
            val updatedRows = dao.updateStatusIfNotTerminal(taskId, TaskStatus.PENDING)
            if (updatedRows > 0) {
                startQueueWorker()
                Timber.i("QueueRepository: Resumed task %s", taskId)
            }
        }
    }

    fun resumeAll() {
        DownloadEngineExecutor.resumeAllTasks()
        scope.launch {
            dao.resumeAllTasks()
            startQueueWorker()
            Timber.i("QueueRepository: Resumed all paused tasks")
        }
    }

    fun retryTask(taskId: String) {
        DownloadEngineExecutor.resumeTask(taskId)
        scope.launch {
            dao.resetForRetry(taskId)
            startQueueWorker()
            Timber.i("QueueRepository: Retried task %s", taskId)
        }
    }

    fun cancelTask(taskId: String) {
        DownloadEngineExecutor.cancelTask(context, taskId)
        DownloadQueueWorker.cancelJob(taskId)
        DownloadNotificationManager.cancelProgressNotification(context, taskId)
        scope.launch {
            dao.deleteTask(taskId)
            Timber.i("QueueRepository: Cancelled and deleted task %s", taskId)
        }
    }

    fun resetInterruptedTasks() {
        scope.launch {
            val interruptedCount = dao.countInterruptedTasks()
            if (interruptedCount > 0) {
                dao.resetInterruptedTasks()
                startQueueWorker()
                Timber.i("QueueRepository: Reset and re-queued %d interrupted task(s)", interruptedCount)
            }
        }
    }

    fun deleteTask(taskId: String) {
        scope.launch {
            val task = dao.getTaskById(taskId)
            DownloadEngineExecutor.cancelTask(context, taskId)
            dao.deleteTask(taskId)
            DownloadNotificationManager.cancelProgressNotification(context, taskId)

            task?.let { t ->
                val uriStr = t.outputFileUri
                if (!uriStr.isNullOrBlank()) {
                    try {
                        val uri = android.net.Uri.parse(uriStr)
                        if (uri.scheme == "content") {
                            context.contentResolver.delete(uri, null as String?, null as Array<String>?)
                        } else if (uri.scheme == "file") {
                            val filePath = uri.path
                            if (!filePath.isNullOrBlank()) {
                                File(filePath).delete()
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Could not delete media file for %s", taskId)
                    }
                }
                val displayPath = t.outputFilePath
                if (!displayPath.isNullOrBlank()) {
                    try {
                        val f = File(displayPath)
                        if (f.exists()) f.delete()
                    } catch (_: Exception) {}
                }
            }
            Timber.i("QueueRepository: Deleted task %s from database and storage", taskId)
        }
    }

    companion object {
        const val WORK_NAME = "vidgrab_download_queue"

        @Volatile
        private var INSTANCE: QueueRepository? = null

        fun getInstance(context: Context): QueueRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = QueueRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
