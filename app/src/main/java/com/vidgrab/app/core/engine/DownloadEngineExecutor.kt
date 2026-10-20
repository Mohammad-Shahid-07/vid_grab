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
    val status: TaskStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L
)

/**
 * 2026 High-Performance Native yt-dlp Execution Engine (Seal / YTDLnis Architecture):
 *
 * Stage 1 (Fast Public Mobile Client): Native android,ios client (-N 8 concurrent fragments, --throttled-rate 100K,
 * socket timeout 8s, persistent base.js cache). Starts in ~1.5s without JS decipher cold-start.
 *
 * Stage 2 (Authenticated Fallback): Cookies + PO Token + QuickJS runtime for bot-locked, private, or age-restricted videos.
 *
 * External Downloader: Turbo-accelerated Aria2c multi-connection downloading when enabled.
 */
object DownloadEngineExecutor {

    private val activeTasks = ConcurrentHashMap<String, Boolean>()
    private val pausedTasks = ConcurrentHashMap.newKeySet<String>()

    fun isPaused(taskId: String): Boolean = pausedTasks.contains(taskId)

    fun resumeTask(taskId: String) {
        pausedTasks.remove(taskId)
    }

    fun resumeAllTasks() {
        pausedTasks.clear()
    }

    suspend fun executeDownload(
        context: Context,
        task: DownloadTaskEntity,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val workDir = MediaStoreExporter.getTaskWorkDir(context, task.id)

        if (isPaused(task.id)) {
            Timber.i("DownloadEngine: Task %s was already paused before start. Halting.", task.id)
            throw kotlinx.coroutines.CancellationException("Task ${task.id} was paused")
        }

        activeTasks[task.id] = true

        try {
            val startP = task.progressPercent.coerceAtLeast(0f)
            onProgress(ProgressUpdate(startP, if (startP > 2f) "Resuming..." else "Connecting...", -1L, TaskStatus.DOWNLOADING))

            val targetHeight = task.targetHeight ?: 1080
            val qjsFile = File(context.applicationInfo.nativeLibraryDir, "libqjs.so")
            val hasQjs = qjsFile.exists()
            val cleanUrl = com.vidgrab.app.core.extractor.DualEngineMetadataResolver.sanitizeMediaUrl(task.url)
            val isYouTube = cleanUrl.contains("youtube.com") || cleanUrl.contains("youtu.be")

            // =================================================================
            // STAGE 1: Fast Public Mobile Client Path (99% of videos, starts in ~1.5s)
            // CRITICAL: player_client=android,ios MUST have useCookies=false and userAgent=null
            // to avoid yt-dlp skipping mobile clients and to prevent Chrome UA conflicts!
            // =================================================================
            try {
                Timber.i("DownloadEngine [Stage 1]: Fast Mobile Client yt-dlp (android,ios) for task %s", task.id)
                return@withContext runDownloadSession(
                    context = context,
                    workDir = workDir,
                    task = task,
                    concurrency = 8,
                    useCookies = false,
                    userAgent = null,
                    extractorArgs = if (isYouTube) "youtube:player_client=android,ios" else null,
                    formatSelector = task.formatSelector,
                    qjsPath = null,
                    onProgress = onProgress
                )
            } catch (e: Exception) {
                if (isPaused(task.id) || activeTasks[task.id] != true || e is InterruptedException || e is kotlinx.coroutines.CancellationException) {
                    Timber.i("DownloadEngine: Task %s was paused or cancelled during Stage 1. Halting.", task.id)
                    throw kotlinx.coroutines.CancellationException("Task ${task.id} was paused or cancelled")
                }
                Timber.w(e, "DownloadEngine [Stage 1] failed for task %s. Activating Stage 2 (Authenticated Fallback)...", task.id)
            }

            if (isPaused(task.id) || activeTasks[task.id] != true) {
                Timber.i("DownloadEngine: Task %s was paused or cancelled before Stage 2. Halting.", task.id)
                throw kotlinx.coroutines.CancellationException("Task ${task.id} was paused or cancelled")
            }

            // =================================================================
            // STAGE 2: Authenticated Cookie Fallback (Age-restricted / Private / Bot-locked)
            // Uses tv,mweb,web + cookies + poToken + QuickJS
            // =================================================================
            cleanSandboxForRetry(workDir)
            val poArgs = WebViewSessionManager.getExtractorArgs()?.removePrefix("youtube:")?.let { ";$it" } ?: ""
            val authExtractorArgs = if (isYouTube) "youtube:player_client=tv,mweb,web$poArgs" else null
            val safeFormat = if (task.isAudioOnly) {
                "bestaudio[ext=m4a]/bestaudio/best"
            } else {
                "bestvideo[height<=$targetHeight][ext=mp4]+bestaudio[ext=m4a]/best[height<=$targetHeight][ext=mp4]/best[height<=$targetHeight]/best"
            }

            Timber.i("DownloadEngine [Stage 2]: Authenticated fallback (tv,mweb,web) for task %s", task.id)
            return@withContext runDownloadSession(
                context = context,
                workDir = workDir,
                task = task,
                concurrency = 4,
                useCookies = true,
                userAgent = NetworkConstants.activeUserAgent,
                extractorArgs = authExtractorArgs,
                formatSelector = safeFormat,
                qjsPath = if (hasQjs) qjsFile.absolutePath else null,
                onProgress = onProgress
            )

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException || isPaused(task.id)) {
                Timber.i("DownloadEngine: Task %s successfully paused/cancelled.", task.id)
                throw e
            }
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
        val cleanUrl = com.vidgrab.app.core.extractor.DualEngineMetadataResolver.sanitizeMediaUrl(task.url)
        val request = YoutubeDLRequest(cleanUrl).apply {
            addOption("--no-playlist")
            // Output template & line formatting
            addOption("-o", "${workDir.absolutePath}/%(title).120B.%(ext)s")
            addOption("--restrict-filenames")
            val ytdlpCacheDir = File(context.cacheDir, "ytdlp_cache").apply { mkdirs() }
            addOption("--cache-dir", ytdlpCacheDir.absolutePath)
            addOption("--force-ipv4")
            addOption("--no-mtime")
            addOption("--newline")
            addOption("--no-update")
            addOption("--retries", "3")
            addOption("--fragment-retries", "3")
            addOption("--socket-timeout", "8")

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

            // Downloader & Concurrency (Aria2c turbo or native concurrent fragments)
            if (task.useAria2c) {
                val aria2cFile = File(context.applicationInfo.nativeLibraryDir, "libaria2c.so")
                if (aria2cFile.exists()) {
                    addOption("--downloader", "aria2c")
                    addOption("--downloader-args", "aria2c:-x 8 -s 8 -k 1M -j 8")
                } else {
                    addOption("-N", "$concurrency")
                    addOption("--throttled-rate", "100K")
                }
            } else {
                addOption("-N", "$concurrency")
                addOption("--throttled-rate", "100K")
            }
            addOption("--continue")

            // SponsorBlock removal via native yt-dlp / FFmpeg
            if (task.cutSponsor) {
                addOption("--sponsorblock-remove", "sponsor")
            }

            // Format & Merge
            if (task.isAudioOnly) {
                addOption("-f", formatSelector)
                addOption("-x")
                addOption("--audio-format", task.outputExt)
                addOption("--embed-metadata")
                addOption("--embed-thumbnail")
                addOption("--convert-thumbnails", "jpg")
            } else {
                addOption("-f", formatSelector)
                addOption("--merge-output-format", "mp4")
            }

            // Advanced Options
            if (task.embedSubtitles) {
                addOption("--embed-subs")
            }
            if (task.embedChapters) {
                addOption("--embed-chapters")
            }
        }

        var currentStatus = TaskStatus.DOWNLOADING
        var lastDispatchedPercent = -1f
        var lastDispatchTimeMs = 0L

        YoutubeDL.getInstance().execute(request, task.id) { progress, etaInSeconds, line ->
            val cleanLine = line.orEmpty()
            var stageText = extractSpeed(cleanLine)

            if (cleanLine.contains("Downloading webpage") ||
                cleanLine.contains("Downloading android player API JSON") ||
                cleanLine.contains("Downloading player") ||
                cleanLine.contains("Extracting URL") ||
                cleanLine.contains("Downloading m3u8 information") ||
                cleanLine.contains("Downloading MPD manifest")
            ) {
                stageText = "Connecting..."
            } else if (cleanLine.contains("[Merger]") ||
                cleanLine.contains("[ExtractAudio]") ||
                cleanLine.contains("[ffmpeg]") ||
                cleanLine.contains("[FixupM4a]") ||
                cleanLine.contains("[VideoConvertor]")
            ) {
                currentStatus = TaskStatus.MERGING
                stageText = "Merging audio and video..."
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

                var parsedTotal = 0L
                var parsedDownloaded = 0L

                val totalMatch = totalBytesRegex.find(cleanLine)
                if (totalMatch != null) {
                    parsedTotal = parseSizeToBytes(totalMatch.groupValues[1], totalMatch.groupValues[2])
                }
                val dlMatch = downloadedBytesRegex.find(cleanLine)
                if (dlMatch != null) {
                    parsedDownloaded = parseSizeToBytes(dlMatch.groupValues[1], dlMatch.groupValues[2])
                }
                if (parsedTotal > 0L && parsedDownloaded <= 0L && p > 0f) {
                    parsedDownloaded = (parsedTotal * (p / 100f)).toLong()
                }

                val effectiveSpeedText = when {
                    currentStatus == TaskStatus.MERGING -> "Merging audio and video..."
                    stageText.isNotBlank() -> stageText
                    p >= 100f -> "Finishing..."
                    else -> "Downloading..."
                }

                val update = ProgressUpdate(
                    progress = p.coerceAtLeast(2f),
                    speedText = effectiveSpeedText,
                    etaSeconds = etaInSeconds,
                    status = currentStatus,
                    downloadedBytes = parsedDownloaded,
                    totalBytes = parsedTotal
                )

                if (isPaused(task.id) || activeTasks[task.id] != true) {
                    try {
                        YoutubeDL.getInstance().destroyProcessById(task.id)
                    } catch (_: Exception) {}
                    throw kotlinx.coroutines.CancellationException("Task ${task.id} was paused")
                }

                kotlinx.coroutines.runBlocking {
                    onProgress(update)
                }
            }
        }

        if (isPaused(task.id) || activeTasks[task.id] != true) {
            Timber.i("DownloadEngine: Task %s was paused or cancelled. Skipping media file location.", task.id)
            throw kotlinx.coroutines.CancellationException("Task ${task.id} was paused or cancelled")
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
            pausedTasks.add(taskId)
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
            pausedTasks.add(taskId)
            activeTasks.remove(taskId)
            YoutubeDL.getInstance().destroyProcessById(taskId)
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
    private val totalBytesRegex = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]?i?B)""", RegexOption.IGNORE_CASE)
    private val downloadedBytesRegex = Regex("""\[download\]\s+([\d.]+)\s*([KMGT]?i?B)\s+of""", RegexOption.IGNORE_CASE)

    private fun parseSizeToBytes(valueStr: String, unitStr: String): Long {
        val num = valueStr.toDoubleOrNull() ?: return 0L
        val unit = unitStr.uppercase()
        val multiplier = when {
            unit.startsWith("G") -> 1024L * 1024L * 1024L
            unit.startsWith("M") -> 1024L * 1024L
            unit.startsWith("K") -> 1024L
            else -> 1L
        }
        return (num * multiplier).toLong()
    }

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
