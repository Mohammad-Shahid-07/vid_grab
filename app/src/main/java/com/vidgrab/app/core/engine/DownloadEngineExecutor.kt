package com.vidgrab.app.core.engine

import android.content.Context
import com.vidgrab.app.core.database.DownloadTaskEntity
import com.vidgrab.app.core.database.TaskStatus
import com.vidgrab.app.core.network.NetworkConstants
import com.vidgrab.app.core.storage.MediaStoreExporter
import com.vidgrab.app.core.webview.WebViewSessionManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class ProgressUpdate(
    val progress: Float,
    val speedText: String,
    val etaSeconds: Long,
    val status: TaskStatus
)

/**
 * 2026 Resilient yt-dlp Execution Engine with 3-Stage Self-Healing Execution:
 *
 * Attempt 1 (Standard Defaults): Native yt-dlp default client selection (-N 3, no custom player_client).
 *
 * Attempt 2 (Clean Single-Stream Fallback): Clean client fallback (-N 1, youtube:player_client=default,-android_vr, safe formats).
 *
 * Attempt 3 (Authenticated Session Fallback): Full cookies + User-Agent session with forced Nightly OTA update on 403/Forbidden/Auth errors.
 */
object DownloadEngineExecutor {

    private val activeTasks = ConcurrentHashMap<String, Boolean>()

    suspend fun executeDownload(
        context: Context,
        task: DownloadTaskEntity,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val workDir = MediaStoreExporter.getTaskWorkDir(context, task.id)
        activeTasks[task.id] = true

        try {
            // =================================================================
            // STAGE 1 (Primary): Instant JVM Stream Engine (NewPipeExtractor + OkHttp Range + FFmpeg)
            // Starts in <300ms, zero Python cold start, direct 2MB chunks
            // =================================================================
            try {
                Timber.i("DownloadEngine [Stage 1]: Attempting Fast JVM direct stream download for task %s", task.id)
                onProgress(ProgressUpdate(1f, "Resolving stream (Fast JVM)...", -1L, TaskStatus.DOWNLOADING))

                val jvmFile = DirectJvmStreamDownloader.download(
                    context = context,
                    task = task,
                    workDir = workDir,
                    isCancelled = { activeTasks[task.id] != true },
                    onProgress = { p, eta, speedText ->
                        onProgress(
                            ProgressUpdate(
                                progress = p,
                                speedText = speedText,
                                etaSeconds = eta,
                                status = if (p >= 94f && speedText.contains("Merging")) TaskStatus.MERGING else TaskStatus.DOWNLOADING
                            )
                        )
                    }
                )

                if (jvmFile.exists() && jvmFile.length() > 0) {
                    Timber.i("DownloadEngine [Stage 1]: Success! Media file created: %s (%d bytes)", jvmFile.name, jvmFile.length())
                    return@withContext jvmFile
                }
            } catch (jvmEx: Exception) {
                if (activeTasks[task.id] != true || jvmEx is InterruptedException || jvmEx is kotlinx.coroutines.CancellationException) {
                    Timber.i("DownloadEngine: Task %s was paused or cancelled during Stage 1. Halting.", task.id)
                    throw jvmEx
                }
                Timber.w(jvmEx, "DownloadEngine [Stage 1] failed for task %s. Activating Stage 2 (yt-dlp)...", task.id)
            }

            if (activeTasks[task.id] != true) {
                Timber.i("DownloadEngine: Task %s was paused before Stage 2. Halting.", task.id)
                throw InterruptedException("Task was paused or cancelled")
            }

            // =================================================================
            // STAGE 2 (Fallback): yt-dlp Subprocess Engine with QuickJS (libqjs.so)
            // =================================================================
            cleanSandboxForRetry(workDir)
            onProgress(ProgressUpdate(2f, "Switching to yt-dlp engine...", -1L, TaskStatus.DOWNLOADING))

            val targetHeight = task.targetHeight ?: 1080
            val qjsFile = File(context.applicationInfo.nativeLibraryDir, "libqjs.so")
            val hasQjs = qjsFile.exists()

            // Attempt 2A: Native defaults with explicit libqjs.so path (if available)
            try {
                Timber.i("DownloadEngine [Stage 2A]: yt-dlp with QuickJS (hasQjs=%b) for task %s", hasQjs, task.id)
                return@withContext runDownloadSession(
                    context = context,
                    workDir = workDir,
                    task = task,
                    concurrency = 1,
                    useCookies = false,
                    userAgent = null,
                    extractorArgs = null,
                    formatSelector = task.formatSelector,
                    qjsPath = if (hasQjs) qjsFile.absolutePath else null,
                    onProgress = onProgress
                )
            } catch (e: Exception) {
                Timber.w(e, "DownloadEngine [Stage 2A] failed for task %s. Activating Stage 2B...", task.id)
            }

            // Attempt 2B: Clean fallback with mobile clients and safe format
            cleanSandboxForRetry(workDir)
            val safeFormat = if (task.isAudioOnly) {
                "bestaudio[ext=m4a]/bestaudio/best"
            } else {
                "bestvideo[height<=$targetHeight][ext=mp4]+bestaudio[ext=m4a]/best[height<=$targetHeight][ext=mp4]/best[height<=$targetHeight]/best"
            }

            try {
                Timber.i("DownloadEngine [Stage 2B]: Clean mobile clients fallback for task %s", task.id)
                return@withContext runDownloadSession(
                    context = context,
                    workDir = workDir,
                    task = task,
                    concurrency = 1,
                    useCookies = false,
                    userAgent = null,
                    extractorArgs = "youtube:player_client=ios,web_safari,mweb",
                    formatSelector = safeFormat,
                    qjsPath = null,
                    onProgress = onProgress
                )
            } catch (e: Exception) {
                Timber.w(e, "DownloadEngine [Stage 2B] failed for task %s. Activating Stage 2C (Authenticated)...", task.id)
            }

            // Attempt 2C: Authenticated session fallback with cookies + PO Token
            cleanSandboxForRetry(workDir)
            val ultraSimpleSelector = if (task.isAudioOnly) "bestaudio/best" else "best[height<=$targetHeight]/best"
            Timber.i("DownloadEngine [Stage 2C]: Authenticated fallback for task %s", task.id)

            return@withContext runDownloadSession(
                context = context,
                workDir = workDir,
                task = task,
                concurrency = 1,
                useCookies = true,
                userAgent = NetworkConstants.activeUserAgent,
                extractorArgs = WebViewSessionManager.getExtractorArgs(),
                formatSelector = ultraSimpleSelector,
                qjsPath = null,
                onProgress = onProgress
            )

        } catch (e: Exception) {
            val meaningfulError = extractMeaningfulErrorMessage(e)
            Timber.e(e, "DownloadEngine: All stages failed for task %s: %s", task.id, meaningfulError)
            throw RuntimeException(meaningfulError, e)
        } finally {
            activeTasks.remove(task.id)
        }
    }

    private suspend fun runDownloadSession(
        context: Context,
        workDir: File,
        task: DownloadTaskEntity,
        concurrency: Int,
        useCookies: Boolean,
        userAgent: String?,
        extractorArgs: String?,
        formatSelector: String,
        qjsPath: String? = null,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): File {
        val request = YoutubeDLRequest(task.url).apply {
            // Output template & line formatting
            addOption("-o", "${workDir.absolutePath}/%(title).120B.%(ext)s")
            addOption("--restrict-filenames")
            addOption("--no-cache-dir")
            addOption("--force-ipv4")
            addOption("--no-mtime")
            addOption("--newline")
            addOption("--no-update")
            addOption("--retries", "2")
            addOption("--fragment-retries", "2")
            addOption("--socket-timeout", "12")

            // QuickJS Explicit Path
            if (!qjsPath.isNullOrBlank()) {
                addOption("--js-runtimes", "quickjs:$qjsPath")
            }

            // User-Agent (only passed if explicitly specified)
            if (!userAgent.isNullOrBlank()) {
                addOption("--user-agent", userAgent)
            }

            // Live Cookies (if enabled for this attempt)
            if (useCookies) {
                val cookiesFile = WebViewSessionManager.exportCookiesToNetscapeFile(context)
                if (cookiesFile != null && cookiesFile.exists() && cookiesFile.length() > 0) {
                    addOption("--cookies", cookiesFile.absolutePath)
                }
            }

            // Extractor Args (only passed if explicitly specified)
            if (!extractorArgs.isNullOrBlank()) {
                addOption("--extractor-args", extractorArgs)
            }

            // Downloader & Concurrency: single-stream to prevent YouTube CDN TCP connection reset
            addOption("-N", "$concurrency")
            addOption("--continue")

            // Format & Merge
            if (task.isAudioOnly) {
                addOption("-f", formatSelector)
                addOption("-x")
                addOption("--audio-format", task.outputExt)
            } else {
                addOption("-f", formatSelector)
                addOption("--merge-output-format", "mp4")
            }
        }

        var currentStatus = TaskStatus.DOWNLOADING
        var lastDispatchedPercent = -1f
        var lastDispatchTimeMs = 0L

        YoutubeDL.getInstance().execute(request, task.id) { progress, etaInSeconds, line ->
            val cleanLine = line.orEmpty()
            var stageText = extractSpeed(cleanLine)

            if (cleanLine.contains("Downloading webpage")) {
                stageText = "yt-dlp: Fetching webpage..."
            } else if (cleanLine.contains("Downloading android player API JSON") || cleanLine.contains("Downloading player")) {
                stageText = "yt-dlp: Solving player API..."
            } else if (cleanLine.contains("Extracting URL")) {
                stageText = "yt-dlp: Resolving stream..."
            } else if (cleanLine.contains("[Merger]") || cleanLine.contains("[ExtractAudio]") || cleanLine.contains("[ffmpeg]")) {
                currentStatus = TaskStatus.MERGING
                stageText = "Merging with FFmpeg..."
            }

            val now = System.currentTimeMillis()
            val p = progress.coerceIn(0f, 100f)

            // Debounce progress dispatches to avoid process stdout pipe deadlocks & SQLite write contention
            val statusChanged = (currentStatus == TaskStatus.MERGING)
            val significantProgress = (p - lastDispatchedPercent) >= 1.0f || p == 100f
            val timeElapsed = (now - lastDispatchTimeMs) >= 500L

            if (significantProgress || timeElapsed || statusChanged || stageText.startsWith("yt-dlp:")) {
                lastDispatchedPercent = p
                lastDispatchTimeMs = now

                val update = ProgressUpdate(
                    progress = p.coerceAtLeast(2f),
                    speedText = stageText.ifBlank { "Downloading stream..." },
                    etaSeconds = etaInSeconds,
                    status = currentStatus
                )

                kotlinx.coroutines.runBlocking {
                    onProgress(update)
                }
            }
        }

        // Locate final produced media file
        val finalFile = MediaStoreExporter.findFinishedMediaFile(workDir)
            ?: throw IllegalStateException("Download finished but no output media file was found in ${workDir.absolutePath}")

        Timber.i("Download task %s produced media file: %s (%d bytes)", task.id, finalFile.name, finalFile.length())
        return finalFile
    }

    private fun cleanSandboxForRetry(workDir: File) {
        try {
            workDir.listFiles()?.forEach { file ->
                val name = file.name.lowercase()
                // Never delete .part, .ytdl, or .aria2 as they contain resumed byte states!
                if (name.endsWith(".temp") ||
                    name.endsWith(".json") ||
                    name.endsWith(".txt")) {
                    file.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not clean sandbox files in %s", workDir.absolutePath)
        }
    }

    fun pauseTask(taskId: String): Boolean {
        return try {
            activeTasks.remove(taskId)
            val killed = YoutubeDL.getInstance().destroyProcessById(taskId)
            Timber.i("Paused task process: %s (result=%b)", taskId, killed)
            true
        } catch (e: Exception) {
            Timber.e(e, "Error pausing task %s", taskId)
            false
        }
    }

    fun pauseAllTasks() {
        val runningIds = activeTasks.keys.toList()
        for (id in runningIds) {
            pauseTask(id)
        }
        activeTasks.clear()
        Timber.i("Paused all active engine tasks (%d tasks stopped)", runningIds.size)
    }

    fun cancelTask(context: Context, taskId: String): Boolean {
        return try {
            YoutubeDL.getInstance().destroyProcessById(taskId)
            activeTasks.remove(taskId)
            MediaStoreExporter.cleanupTaskWorkDir(context, taskId)
            Timber.i("Cancelled and cleaned up task: %s", taskId)
            true
        } catch (e: Exception) {
            Timber.e(e, "Error cancelling task %s", taskId)
            false
        }
    }

    fun isTaskActive(taskId: String): Boolean = activeTasks.containsKey(taskId)

    private val speedRegex = Regex("at\\s+([\\d.]+[KMGT]?i?B/s)")

    private fun extractSpeed(line: String): String {
        val match = speedRegex.find(line)
        return match?.groupValues?.getOrNull(1) ?: ""
    }

    private fun extractMeaningfulErrorMessage(throwable: Throwable): String {
        val fullMsg = throwable.message ?: return "Unknown download execution error"
        val lines = fullMsg.lines().map { it.trim() }.filter { it.isNotBlank() }

        // Find error line starting with ERROR: (ignore WARNING:)
        val errorLine = lines.lastOrNull { it.startsWith("ERROR:") }
        if (errorLine != null) {
            return errorLine.removePrefix("ERROR:").trim()
        }

        // Other meaningful error signatures
        val specificError = lines.lastOrNull {
            !it.startsWith("WARNING:") && (it.contains("HTTP Error") || it.contains("Sign in") || it.contains("Precondition check failed"))
        }
        if (specificError != null) {
            return specificError
        }

        // Filter out WARNING lines and take the last 2 lines
        val nonWarningLines = lines.filter { !it.startsWith("WARNING:") }
        if (nonWarningLines.isNotEmpty()) {
            return nonWarningLines.takeLast(2).joinToString(" | ")
        }

        return lines.takeLast(2).joinToString(" | ")
    }
}
