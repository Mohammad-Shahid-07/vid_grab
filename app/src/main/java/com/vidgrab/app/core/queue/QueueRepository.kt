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
            if (spec.isBatch) {
                if (spec.batchItems.isNotEmpty()) {
                    // Bulk insert pre-selected playlist items with genuine titles and thumbnails
                    val entities = spec.batchItems.mapIndexed { index, item ->
                        DownloadTaskEntity(
                            id = UUID.randomUUID().toString(),
                            url = item.url,
                            title = item.title.ifBlank { "${spec.title} (#${index + 1})" },
                            author = item.author.ifBlank { spec.author },
                            thumbnailUrl = item.thumbnailUrl,
                            isAudioOnly = spec.mediaType == "AUDIO",
                            targetHeight = spec.targetHeight,
                            formatSelector = spec.formatSelector,
                            outputExt = spec.outputExtension,
                            useAria2c = spec.enableAria2c,
                            playlistId = spec.url,
                            playlistTitle = spec.title,
                            playlistIndex = index + 1,
                            status = TaskStatus.PENDING,
                            totalBytes = spec.expectedSizeBytes
                        )
                    }
                    dao.insertTasks(entities)
                    Timber.i("QueueRepository: Enqueued %d batch items for playlist: %s", entities.size, spec.title)
                } else if (spec.batchUrls.isNotEmpty()) {
                    val entities = spec.batchUrls.mapIndexed { index, url ->
                        DownloadTaskEntity(
                            id = UUID.randomUUID().toString(),
                            url = url,
                            title = "${spec.title} (#${index + 1})",
                            author = spec.author,
                            isAudioOnly = spec.mediaType == "AUDIO",
                            targetHeight = spec.targetHeight,
                            formatSelector = spec.formatSelector,
                            outputExt = spec.outputExtension,
                            useAria2c = spec.enableAria2c,
                            playlistId = spec.url,
                            playlistTitle = spec.title,
                            playlistIndex = index + 1,
                            status = TaskStatus.PENDING,
                            totalBytes = spec.expectedSizeBytes
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
                val entity = DownloadTaskEntity(
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
                    totalBytes = spec.expectedSizeBytes
                )
                dao.insertTask(entity)
                Timber.i("QueueRepository: Enqueued single download: %s [%s]", spec.title, spec.formatSelector)
            }

            // Trigger WorkManager UIDT sequential execution
            startQueueWorker()
        }
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
                    totalBytes = spec.expectedSizeBytes
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
                totalBytes = spec.expectedSizeBytes
            )
            dao.insertTask(fallback)
        }
    }

    fun startQueueWorker() {
        val request = OneTimeWorkRequestBuilder<DownloadQueueWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
        Timber.d("QueueRepository: Scheduled OneTimeWorkRequest for DownloadQueueWorker")
    }

    fun pauseTask(taskId: String) {
        scope.launch {
            DownloadEngineExecutor.pauseTask(taskId)
            dao.updateStatus(taskId, TaskStatus.PAUSED)
            Timber.i("QueueRepository: Paused task %s", taskId)
        }
    }

    fun pauseAll() {
        scope.launch {
            DownloadEngineExecutor.pauseAllTasks()
            dao.pauseAllTasks()
            Timber.i("QueueRepository: Paused all active tasks")
        }
    }

    fun resumeTask(taskId: String) {
        scope.launch {
            dao.updateStatus(taskId, TaskStatus.PENDING)
            startQueueWorker()
            Timber.i("QueueRepository: Resumed task %s", taskId)
        }
    }

    fun resumeAll() {
        scope.launch {
            dao.resumeAllTasks()
            startQueueWorker()
            Timber.i("QueueRepository: Resumed all paused tasks")
        }
    }

    fun retryTask(taskId: String) {
        scope.launch {
            dao.resetForRetry(taskId)
            startQueueWorker()
            Timber.i("QueueRepository: Retried task %s", taskId)
        }
    }

    fun cancelTask(taskId: String) {
        scope.launch {
            DownloadEngineExecutor.cancelTask(context, taskId)
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
