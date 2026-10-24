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
import com.vidgrab.app.core.worker.DownloadQueueWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import timber.log.Timber
import java.util.UUID

class QueueRepository(private val context: Context) {

    private val database = AppDatabase.getInstance(context)
    private val dao = database.downloadDao()
    private val workManager = WorkManager.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO)

    val allTasks: Flow<List<DownloadTaskEntity>> = dao.getAllTasksFlow()
    val activeTasks: Flow<List<DownloadTaskEntity>> = dao.getActiveTasksFlow()
    val completedTasks: Flow<List<DownloadTaskEntity>> = dao.getCompletedTasksFlow()

    fun enqueue(spec: DownloadRequestSpec) {
        scope.launch {
            val sponsorJson = spec.sponsorSegments.takeIf { it.isNotEmpty() }?.let { segs ->
                segs.joinToString(";") { "${it.first}-${it.second}" }
            }

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
                            status = TaskStatus.PENDING,
                            totalBytes = itemBytes,
                            cutSponsor = spec.cutSponsor,
                            sponsorSegmentsJson = sponsorJson,
                            embedSubtitles = spec.embedSubtitles,
                            embedChapters = spec.embedChapters,
                            downloadFolder = spec.downloadFolder
                        )
                    }
                    dao.insertTasks(entities)
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
                            status = TaskStatus.PENDING,
                            totalBytes = defaultItemBytes,
                            cutSponsor = spec.cutSponsor,
                            sponsorSegmentsJson = sponsorJson,
                            embedSubtitles = spec.embedSubtitles,
                            embedChapters = spec.embedChapters,
                            downloadFolder = spec.downloadFolder
                        )
                    }
                    dao.insertTasks(entities)
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
                    isDirectMuxed = spec.isDirectMuxed
                )
                dao.insertTask(entity)
                Timber.i("QueueRepository: Enqueued single download: %s [%s] with thumb: %s", spec.title, spec.formatSelector, effectiveThumb)
            }

            // Trigger WorkManager UIDT sequential execution
            startQueueWorker()
        }
    }

    private fun estimateItemBytes(isAudio: Boolean, targetHeight: Int?, durationSeconds: Long): Long {
        return 0L
    }

    private suspend fun expandAndEnqueuePlaylist(spec: DownloadRequestSpec) {
        try {
            val playlistInfo = PlaylistInfo.getInfo(ServiceList.YouTube, spec.url)
            val items = playlistInfo.relatedItems
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
                    status = TaskStatus.PENDING,
                    totalBytes = itemBytes,
                    cutSponsor = spec.cutSponsor,
                    embedSubtitles = spec.embedSubtitles,
                    embedChapters = spec.embedChapters
                )
            }
            dao.insertTasks(entities)
            Timber.i("QueueRepository: Lazily expanded and enqueued %d playlist items", entities.size)
        } catch (e: Exception) {
            Timber.e(e, "Failed to lazily expand playlist: %s", spec.url)
            // Fallback: enqueue parent URL directly as single task
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
                status = TaskStatus.PENDING,
                totalBytes = spec.expectedSizeBytes,
                cutSponsor = spec.cutSponsor,
                embedSubtitles = spec.embedSubtitles,
                embedChapters = spec.embedChapters
            )
            dao.insertTask(fallback)
        }
    }

    fun startQueueWorker() {
        scope.launch {
            val activeCount = dao.getActiveDownloadingCount()
            val policy = if (activeCount == 0) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
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
            dao.updateStatus(taskId, TaskStatus.PAUSED)
            Timber.i("QueueRepository: Paused task %s", taskId)
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
            dao.updateStatus(taskId, TaskStatus.PENDING)
            startQueueWorker()
            Timber.i("QueueRepository: Resumed task %s", taskId)
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
        scope.launch {
            dao.updateStatus(taskId, TaskStatus.CANCELLED)
            Timber.i("QueueRepository: Cancelled task %s", taskId)
        }
    }

    fun deleteTask(taskId: String) {
        scope.launch {
            DownloadEngineExecutor.cancelTask(context, taskId)
            dao.deleteTask(taskId)
            Timber.i("QueueRepository: Deleted task %s from database", taskId)
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
