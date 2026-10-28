package com.youdrop.app.core.engine

import android.content.Context
import com.youdrop.app.core.database.DownloadTaskEntity
import com.youdrop.app.core.database.TaskStatus
import com.youdrop.app.core.extractor.AudioQualityOption
import com.youdrop.app.core.extractor.DualEngineMetadataResolver
import com.youdrop.app.core.extractor.FormatSelectorBuilder
import com.youdrop.app.core.extractor.InnerTubeFastClient
import com.youdrop.app.core.extractor.VideoQualityOption
import com.youdrop.app.core.extractor.formatBytes
import com.youdrop.app.core.network.NetworkConstants
import com.youdrop.app.core.storage.MediaStoreExporter
import com.youdrop.app.core.webview.WebViewSessionManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class ProgressUpdate(
    val progress: Float,
    val speedText: String,
    val etaSeconds: Long,
    val status: TaskStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val videoBytes: Long = 0L,
    val audioBytes: Long = 0L,
    val activeConnections: Int = 8,
    val segmentProgressCsv: String = ""
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

    /** Upper bound for the pre-flight player fetch; every engine can extract on its own after it. */
    private const val PREFLIGHT_RESOLVE_TIMEOUT_MS = 6_000L

    /**
     * The engine that last owned [task].id's sandbox. Pausing during a merge used to lose this
     * information, so resume started again from the primary native engine and re-downloaded bytes
     * the previous engine had already fetched. The marker is a tiny file inside the task workDir,
     * which already survives pause/resume and process death.
     */
    private const val RESUME_MARKER_FILE = ".youdrop-active-engine"
    private const val RESUME_ENGINE_FAST = "fast"
    private const val RESUME_ENGINE_JVM = "jvm"
    private const val RESUME_ENGINE_YTDLP_FAST = "ytdlp1"
    private const val RESUME_ENGINE_YTDLP_AUTH = "ytdlp2"

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
            val taskStartMs = System.currentTimeMillis()
            val startP = task.progressPercent.coerceAtLeast(0f)
            onProgress(ProgressUpdate(startP, if (startP > 0f) "Resuming..." else "Connecting...", -1L, TaskStatus.DOWNLOADING, activeConnections = 0))

            val targetHeight = task.targetHeight ?: 1080
            val qjsFile = File(context.applicationInfo.nativeLibraryDir, "libqjs.so")
            val hasQjs = qjsFile.exists()
            val cleanUrl = DualEngineMetadataResolver.sanitizeMediaUrl(task.url)
            val isYouTube = cleanUrl.contains("youtube.com") || cleanUrl.contains("youtu.be")

            // Audio-only formats such as MP3/FLAC require a real transcode, which only yt-dlp
            // can perform. Never let a native engine substitute a raw M4A/Opus stream for them.
            val outputExtLower = task.outputExt.lowercase()
            val nativeAudioCompatible = !task.isAudioOnly ||
                outputExtLower in setOf("m4a", "aac", "opus", "webm")

            // FastDirect muxes with Android's MediaMuxer, which only produces MP4 (AVC/AAC).
            // VP9/WebM merges and MKV fallbacks must go through FFmpeg (DirectJvm) or yt-dlp.
            val fastDirectSupportsContainer = task.isAudioOnly ||
                (outputExtLower != "webm" && outputExtLower != "mkv" &&
                    !task.videoCodecPreference.equals("VP9", ignoreCase = true))

            // =================================================================
            // STAGE 0: Native direct engines — selected engine first, then fallback
            // =================================================================
            var directVideo = task.directVideoUrl ?: task.resolvedVideoStreamUrl
            var directAudio = if (task.isDirectMuxed) null else (task.directAudioUrl ?: task.resolvedAudioStreamUrl)

            val isVideoNeeded = !task.isAudioOnly
            val isAudioNeeded = task.isAudioOnly || (!task.isDirectMuxed && directVideo != null)

            val isVideoValid = !isVideoNeeded || FastDirectDownloader.isStreamUrlValid(directVideo)
            val isAudioValid = !isAudioNeeded || FastDirectDownloader.isStreamUrlValid(directAudio)
            val needsReResolution = isYouTube && nativeAudioCompatible && (!isVideoValid || !isAudioValid)

            // Read the resume marker before the pre-flight: a yt-dlp resume never uses native
            // stream URLs, so it should not pay for a player fetch it will not consume.
            val resumeEngine = readActiveEngine(workDir) ?: inferResumeEngine(workDir)
            val resumeWithYtDlp = resumeEngine == RESUME_ENGINE_YTDLP_FAST ||
                resumeEngine == RESUME_ENGINE_YTDLP_AUTH

            // If direct URLs are missing or expired, re-resolve while honoring the user's
            // video codec and exact audio-track choices.
            if (needsReResolution && !resumeWithYtDlp) {
                val vid = DualEngineMetadataResolver.extractVideoId(cleanUrl)
                if (vid != null) {
                    // Pre-flight resolution must never hold the transfer hostage: both native engines
                    // and yt-dlp can extract on their own, so a slow player fetch is abandoned.
                    onProgress(
                        ProgressUpdate(0f, "Preparing...", -1L, TaskStatus.DOWNLOADING, activeConnections = 0)
                    )
                    val resolveStarted = System.currentTimeMillis()
                    try {
                        val payload = com.youdrop.app.core.webview.DownloadTriggerPayload(
                            url = "https://www.youtube.com/watch?v=$vid",
                            itemType = "VIDEO",
                            title = task.title,
                            inlinePlayerJson = null
                        )
                        val resolved = kotlinx.coroutines.withTimeoutOrNull(PREFLIGHT_RESOLVE_TIMEOUT_MS) {
                            DualEngineMetadataResolver.resolve(payload)
                        }
                        Timber.i(
                            "DownloadEngine: pre-flight resolve %s in %d ms",
                            if (resolved == null) "timed out" else "finished",
                            System.currentTimeMillis() - resolveStarted
                        )
                        if (resolved != null) {
                            val matchedVideo = selectReResolvedVideo(
                                resolved.videoOptions,
                                targetHeight,
                                task.videoCodecPreference
                            )
                            val matchedAudio = selectReResolvedAudio(resolved.audioOptions, task, matchedVideo)

                            directVideo = matchedVideo?.directStreamUrl
                            directAudio = if (matchedVideo?.isMuxed == true) null
                                else (matchedAudio?.directStreamUrl ?: matchedVideo?.matchedAudioStreamUrl)
                            Timber.i(
                                "DownloadEngine: Re-resolved direct streams for %s (video=%b %s, audio=%b %s)",
                                vid,
                                directVideo != null,
                                matchedVideo?.vcodec,
                                directAudio != null,
                                matchedAudio?.audioTrackName ?: matchedAudio?.extension
                            )
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "DownloadEngine: Direct stream re-resolution failed for %s", vid)
                    }
                }
            }

            val engineMode = DownloadEnginePreferences.getMode(context)
            val fastFirst = engineMode == DownloadEngineMode.FAST
            Timber.i(
                "DownloadEngine: Primary engine = %s, resume marker = %s for task %s",
                engineMode.name,
                resumeEngine ?: "none",
                task.id
            )

            // Fast parallel segmented engine.
            var firstByteLogged = false
            val attemptFastDirect: suspend () -> File? = {
                val canFastComplete = task.isAudioOnly ||
                    task.isDirectMuxed ||
                    (!directVideo.isNullOrBlank() && !directAudio.isNullOrBlank())

                if (!nativeAudioCompatible || !fastDirectSupportsContainer || !canFastComplete) {
                    null
                } else {
                    val sanitizedTitle = task.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120)
                    val directOutputFile = File(workDir, "$sanitizedTitle.${task.outputExt}")
                    try {
                        Timber.i("DownloadEngine [Direct/Fast]: Attempting FastDirectDownloader for task %s", task.id)
                        markActiveEngine(workDir, RESUME_ENGINE_FAST)
                        val fastResult = FastDirectDownloader.downloadFast(
                            taskId = task.id,
                            videoUrl = if (task.isAudioOnly) null else directVideo,
                            audioUrl = directAudio,
                            outputFile = directOutputFile
                        ) { pct, downloaded, total, speedBytesPerSec, activeConnections, segmentCsv ->
                            if (!firstByteLogged && downloaded > 0L) {
                                firstByteLogged = true
                                Timber.i(
                                    "DownloadEngine: first byte received after %d ms",
                                    System.currentTimeMillis() - taskStartMs
                                )
                            }
                            val speedText = if (speedBytesPerSec > 0) formatBytes(speedBytesPerSec) + "/s" else "Downloading..."
                            onProgress(
                                ProgressUpdate(
                                    progress = pct.toFloat(),
                                    speedText = speedText,
                                    etaSeconds = if (speedBytesPerSec > 0 && total > downloaded) ((total - downloaded) / speedBytesPerSec) else -1L,
                                    status = if (pct >= 96 && !task.isAudioOnly && !directVideo.isNullOrBlank() && !directAudio.isNullOrBlank()) TaskStatus.MERGING else TaskStatus.DOWNLOADING,
                                    downloadedBytes = downloaded,
                                    totalBytes = total,
                                    videoBytes = if (!task.isAudioOnly) downloaded else 0L,
                                    audioBytes = if (task.isAudioOnly) downloaded else 0L,
                                    activeConnections = activeConnections,
                                    segmentProgressCsv = segmentCsv
                                )
                            )
                        }
                        val candidate = fastResult.getOrNull()?.takeIf { it.exists() && it.length() > 0 }
                        if (candidate != null && !task.isAudioOnly && !task.isDirectMuxed && !hasAudioTrack(candidate)) {
                            Timber.w("DownloadEngine [Direct/Fast]: Produced video file lacks audio track for task %s, falling back to full mux", task.id)
                            candidate.delete()
                            null
                        } else {
                            candidate
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: InterruptedException) {
                        throw kotlinx.coroutines.CancellationException("Task ${task.id} was paused")
                    } catch (e: Exception) {
                        Timber.w(e, "DownloadEngine [Direct/Fast] failed for %s", task.id)
                        null
                    }
                }
            }

            // Legacy NewPipe JVM compatibility engine.
            val attemptDirectJvm: suspend () -> File? = {
                if (!isYouTube || !nativeAudioCompatible) {
                    null
                } else {
                    try {
                        Timber.i("DownloadEngine [Direct/JVM]: Attempting DirectJvmStreamDownloader for task %s", task.id)
                        markActiveEngine(workDir, RESUME_ENGINE_JVM)
                        val produced = DirectJvmStreamDownloader.download(
                            context = context,
                            task = task,
                            workDir = workDir,
                            isCancelled = { isPaused(task.id) || activeTasks[task.id] != true }
                        ) { pct, eta, speed ->
                            val estimatedTotal = task.totalBytes
                            val approxBytes = if (estimatedTotal > 0L) (estimatedTotal * pct / 100f).toLong() else 0L
                            onProgress(
                                ProgressUpdate(
                                    progress = pct,
                                    speedText = speed,
                                    etaSeconds = eta,
                                    status = if (pct >= 94f) TaskStatus.MERGING else TaskStatus.DOWNLOADING,
                                    downloadedBytes = approxBytes,
                                    totalBytes = estimatedTotal,
                                    activeConnections = 2
                                )
                            )
                        }
                        produced.takeIf { it.exists() && it.length() > 0 }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: InterruptedException) {
                        throw kotlinx.coroutines.CancellationException("Task ${task.id} was paused")
                    } catch (e: Exception) {
                        Timber.w(e, "DownloadEngine [Direct/JVM] failed for %s", task.id)
                        cleanLegacyArtifacts(workDir)
                        null
                    }
                }
            }

            val nativeAttempts: List<Pair<String, suspend () -> File?>> = when {
                // The task was mid-transfer inside yt-dlp. Native engines cannot reuse yt-dlp's
                // per-format files, so running one now would restart the transfer from zero and
                // can even overwrite the streams that are waiting to be merged.
                resumeWithYtDlp -> {
                    Timber.i(
                        "DownloadEngine: resuming yt-dlp (%s) for task %s; skipping native engines",
                        resumeEngine,
                        task.id
                    )
                    emptyList()
                }
                resumeEngine == RESUME_ENGINE_FAST -> listOf(
                    RESUME_ENGINE_FAST to attemptFastDirect,
                    RESUME_ENGINE_JVM to attemptDirectJvm
                )
                resumeEngine == RESUME_ENGINE_JVM -> listOf(
                    RESUME_ENGINE_JVM to attemptDirectJvm,
                    RESUME_ENGINE_FAST to attemptFastDirect
                )
                fastFirst -> listOf(
                    RESUME_ENGINE_FAST to attemptFastDirect,
                    RESUME_ENGINE_JVM to attemptDirectJvm
                )
                else -> listOf(
                    RESUME_ENGINE_JVM to attemptDirectJvm,
                    RESUME_ENGINE_FAST to attemptFastDirect
                )
            }

            for ((engineName, attempt) in nativeAttempts) {
                if (isPaused(task.id) || activeTasks[task.id] != true) {
                    throw kotlinx.coroutines.CancellationException("Task ${task.id} was paused or cancelled")
                }
                val started = System.currentTimeMillis()
                val result = attempt()
                if (result != null) {
                    Timber.i(
                        "DownloadEngine: %s engine produced %s in %d ms",
                        engineName,
                        result.name,
                        System.currentTimeMillis() - started
                    )
                    return@withContext result
                }
                Timber.w(
                    "DownloadEngine: %s engine gave up after %d ms",
                    engineName,
                    System.currentTimeMillis() - started
                )
            }

            // Neither native path produced a complete file. Remove partial legacy artifacts
            // so findFinishedMediaFile() cannot mistake them for the yt-dlp result.
            cleanLegacyArtifacts(workDir)
            cleanSandboxForRetry(workDir)

            // =================================================================
            // STAGE 1: Fast Public Mobile Client Path (starts in 2–4s fallback)
            // Uses ios,tv with player_skip=webpage,configs + QuickJS immediately
            // =================================================================
            // A task paused inside Stage 2 must not re-run Stage 1: its partial format files
            // belong to Stage 2's selector, and Stage 1 would download a different set first.
            if (resumeEngine != RESUME_ENGINE_YTDLP_AUTH) {
                try {
                    Timber.i("DownloadEngine [Stage 1]: Fast Mobile Client yt-dlp (ios,tv) for task %s", task.id)
                    markActiveEngine(workDir, RESUME_ENGINE_YTDLP_FAST)
                    return@withContext runDownloadSession(
                        context = context,
                        workDir = workDir,
                        task = task,
                        concurrency = 8,
                        useCookies = false,
                        userAgent = null,
                        extractorArgs = if (isYouTube) "youtube:player_client=ios,tv;player_skip=webpage,configs" else null,
                        formatSelector = task.formatSelector,
                        qjsPath = if (hasQjs) qjsFile.absolutePath else null,
                        onProgress = onProgress
                    )
                } catch (e: Exception) {
                    if (isPaused(task.id) || activeTasks[task.id] != true || e is InterruptedException || e is kotlinx.coroutines.CancellationException) {
                        Timber.i("DownloadEngine: Task %s was paused or cancelled during Stage 1. Halting.", task.id)
                        throw kotlinx.coroutines.CancellationException("Task ${task.id} was paused or cancelled")
                    }
                    Timber.w(e, "DownloadEngine [Stage 1] failed for task %s. Activating Stage 2 (Authenticated Fallback)...", task.id)
                }
            } else {
                Timber.i(
                    "DownloadEngine: task %s was paused in Stage 2; resuming consolidated fallback",
                    task.id
                )
            }

            if (isPaused(task.id) || activeTasks[task.id] != true) {
                Timber.i("DownloadEngine: Task %s was paused or cancelled before Stage 2. Halting.", task.id)
                throw kotlinx.coroutines.CancellationException("Task ${task.id} was paused or cancelled")
            }

            // =================================================================
            // STAGE 2: Authenticated Cookie Fallback (Age-restricted / Private / Bot-locked)
            // Uses tv,mweb,web + player_skip=webpage,configs + cookies + poToken + QuickJS
            // =================================================================
            cleanSandboxForRetry(workDir)
            val poArgs = WebViewSessionManager.getExtractorArgs()?.removePrefix("youtube:")?.let { ";$it" } ?: ""
            val authExtractorArgs = if (isYouTube) "youtube:player_client=tv,mweb,web;player_skip=webpage,configs$poArgs" else null
            val safeFormat = if (task.isAudioOnly) {
                FormatSelectorBuilder.buildAudioSelector(task.outputExt)
            } else {
                FormatSelectorBuilder.buildVideoSelector(
                    targetHeight,
                    task.videoCodecPreference ?: "Auto",
                    task.mergeAudioExtension ?: "Auto",
                    task.mergeAudioLanguage,
                    task.mergeAudioBitrateKbps
                )
            }

            Timber.i("DownloadEngine [Stage 2]: Authenticated fallback (tv,mweb,web) for task %s", task.id)
            markActiveEngine(workDir, RESUME_ENGINE_YTDLP_AUTH)
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
            // Do NOT clear pausedTasks here: the worker's cancellation handler reads it to keep the
            // row PAUSED. Removing it before that check reset paused tasks to PENDING, which made
            // the queue immediately restart them (most visibly while merging).
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
        val cleanUrl = com.youdrop.app.core.extractor.DualEngineMetadataResolver.sanitizeMediaUrl(task.url)
        // yt-dlp writes the exact post-move output path here. Reading it avoids guessing between
        // the merged file and leftover per-stream downloads (the old largest-file heuristic could
        // pick the raw audio/webm intermediate and report the wrong size).
        val outputPathFile = File(workDir, ".youdrop-final-path.txt").apply {
            if (exists()) delete()
        }
        val request = YoutubeDLRequest(cleanUrl).apply {
            addOption("--no-playlist")
            // Output template & line formatting
            addOption("-o", "${workDir.absolutePath}/%(title).120B.%(ext)s")
            addOption("--restrict-filenames")
            val ytdlpCacheDir = File(context.cacheDir, "ytdlp_cache").apply { mkdirs() }
            addOption("--cache-dir", ytdlpCacheDir.absolutePath)
            addOption("--no-mtime")
            addOption("--newline")
            addOption("--no-update")
            addOption("--retries", "3")
            addOption("--fragment-retries", "3")
            addOption("--socket-timeout", "15")
            // --print-to-file does not imply --quiet/--simulate (unlike --print), so the normal
            // progress stream stays intact.
            addCommands(
                listOf(
                    "--print-to-file",
                    "after_move:filepath",
                    outputPathFile.absolutePath
                )
            )

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
                val mergeContainer = when (task.outputExt.lowercase()) {
                    "webm" -> "webm"
                    "mkv" -> "mkv"
                    else -> "mp4"
                }
                addOption("--merge-output-format", mergeContainer)
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

        var streamIndex = 0
        var completedStreamsBytes = 0L
        var currentStreamTotalBytes = 0L
        var lastRawStreamPercent = 0f
        // yt-dlp prints "Downloading 1 format(s): 137+140"; the '+' count tells us whether the
        // running total still needs an audio reserve.
        var expectedStreamCount = 1
        val expectedCombinedTotal = task.totalBytes.takeIf { it > 0L } ?: 0L
        val expectedAudioBytes = task.audioBytes.takeIf { it > 0L } ?: 0L

        YoutubeDL.getInstance().execute(request, task.id) { progress, etaInSeconds, line ->
            val cleanLine = line.orEmpty()
            var stageText = extractSpeed(cleanLine)

            formatListRegex.find(cleanLine)?.groupValues?.getOrNull(1)?.let { formats ->
                expectedStreamCount = formats.count { it == '+' } + 1
            }

            if (cleanLine.contains("[download] Destination:")) {
                if (streamIndex > 0 && currentStreamTotalBytes > 0L) {
                    completedStreamsBytes += currentStreamTotalBytes
                    currentStreamTotalBytes = 0L
                    lastRawStreamPercent = 0f
                }
                streamIndex++
            }

            val hasDownloadActivity = cleanLine.contains("[download]") && (cleanLine.contains("%") || cleanLine.contains("/s"))
            if (hasDownloadActivity) {
                if (stageText.isBlank()) stageText = "Downloading..."
            } else if (cleanLine.contains("Downloading webpage") ||
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
            val regexPercent = percentRegex.find(cleanLine)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: -1f
            val rawP = if (progress > 0f) progress else regexPercent

            val totalMatch = totalBytesRegex.find(cleanLine)
            val parsedLineTotal = if (totalMatch != null) {
                parseSizeToBytes(totalMatch.groupValues[1], totalMatch.groupValues[2])
            } else 0L

            if (rawP in 0.1f..40f && lastRawStreamPercent >= 90f && currentStreamTotalBytes > 0L) {
                completedStreamsBytes += currentStreamTotalBytes
                currentStreamTotalBytes = 0L
                streamIndex = streamIndex.coerceAtLeast(1) + 1
            } else if (parsedLineTotal > 0L && currentStreamTotalBytes > 0L && parsedLineTotal < (currentStreamTotalBytes * 4L) / 10L && completedStreamsBytes == 0L) {
                completedStreamsBytes += currentStreamTotalBytes
                currentStreamTotalBytes = parsedLineTotal
                streamIndex = streamIndex.coerceAtLeast(1) + 1
            }

            if (rawP > 0f) lastRawStreamPercent = rawP
            if (parsedLineTotal > 0L) currentStreamTotalBytes = parsedLineTotal

            val unifiedTotalBytes = when {
                task.isAudioOnly -> {
                    currentStreamTotalBytes.takeIf { it > 0L } ?: expectedCombinedTotal
                }
                completedStreamsBytes > 0L && currentStreamTotalBytes > 0L -> {
                    // Both streams are now known exactly; never let the pre-flight estimate win.
                    completedStreamsBytes + currentStreamTotalBytes
                }
                currentStreamTotalBytes > 0L -> {
                    // One stream is known. Only reserve audio when a second stream is expected;
                    // a progressive format already carries its own audio.
                    val audioReserve = when {
                        expectedStreamCount <= 1 -> 0L
                        expectedAudioBytes > 0L -> expectedAudioBytes
                        else -> (currentStreamTotalBytes * 9L) / 100L
                    }
                    currentStreamTotalBytes + audioReserve
                }
                else -> expectedCombinedTotal
            }

            val currentStreamDownloaded = if (rawP > 0f && currentStreamTotalBytes > 0L) {
                (currentStreamTotalBytes * (rawP / 100f)).toLong()
            } else 0L

            val unifiedDownloadedBytes = if (currentStatus == TaskStatus.MERGING && unifiedTotalBytes > 0L) {
                unifiedTotalBytes
            } else {
                (completedStreamsBytes + currentStreamDownloaded).let {
                    if (unifiedTotalBytes > 0L) it.coerceAtMost(unifiedTotalBytes) else it
                }
            }

            val unifiedProgress = when {
                currentStatus == TaskStatus.MERGING -> 98f
                unifiedTotalBytes > 0L -> ((unifiedDownloadedBytes.toFloat() / unifiedTotalBytes.toFloat()) * 95f).coerceIn(0f, 95f)
                rawP > 0f -> rawP.coerceIn(0f, 95f)
                else -> 0f
            }

            val statusChanged = (currentStatus == TaskStatus.MERGING)
            val isFirstDownloadDispatch = lastDispatchedPercent < 0f && (unifiedProgress > 0f || hasDownloadActivity)
            val significantProgress = (unifiedProgress - lastDispatchedPercent) >= 0.5f || unifiedProgress >= 98f
            val timeElapsed = (now - lastDispatchTimeMs) >= 250L || isFirstDownloadDispatch

            if (significantProgress || timeElapsed || statusChanged || stageText.startsWith("yt-dlp:")) {
                lastDispatchedPercent = unifiedProgress
                lastDispatchTimeMs = now

                val effectiveSpeedText = when {
                    currentStatus == TaskStatus.MERGING -> "Merging audio and video..."
                    stageText.isNotBlank() -> stageText
                    unifiedProgress >= 100f -> "Finishing..."
                    else -> "Downloading..."
                }

                val update = ProgressUpdate(
                    progress = unifiedProgress,
                    speedText = effectiveSpeedText,
                    etaSeconds = etaInSeconds,
                    status = currentStatus,
                    downloadedBytes = unifiedDownloadedBytes,
                    totalBytes = unifiedTotalBytes,
                    videoBytes = if (streamIndex <= 1) unifiedDownloadedBytes else completedStreamsBytes,
                    audioBytes = if (streamIndex > 1) currentStreamDownloaded else 0L,
                    activeConnections = concurrency,
                    segmentProgressCsv = ""
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

        // Locate final produced media file. Prefer yt-dlp's own after-move path, then fall back
        // to the legacy scan for sessions where the print hook produced nothing.
        val reportedFinalFile = outputPathFile
            .takeIf { it.isFile }
            ?.readLines()
            ?.lastOrNull { it.isNotBlank() }
            ?.trim()
            ?.removeSurrounding("\"")
            ?.let { path -> File(path).takeIf { it.isFile && it.length() > 0L } }
        val finalFile = reportedFinalFile
            ?: MediaStoreExporter.findFinishedMediaFile(workDir)
            ?: throw IllegalStateException("Download finished but no output media file was found in ${workDir.absolutePath}")

        Timber.i("Download task %s produced media file: %s (%d bytes)", task.id, finalFile.name, finalFile.length())
        return finalFile
    }

    /**
     * Re-resolves a video stream while respecting the user's codec preference. Prefers an exact
     * height match, then the highest available height at or below the requested one.
     */
    private fun selectReResolvedVideo(
        options: List<VideoQualityOption>,
        targetHeight: Int,
        codecPreference: String?
    ): VideoQualityOption? {
        if (options.isEmpty()) return null
        val exact = options.filter { it.height == targetHeight }
        val atOrBelow = options.filter { it.height <= targetHeight }
        val normalizedCodec = codecPreference?.takeIf { it.isNotBlank() && !it.equals("Auto", true) }

        fun matchingCodec(pool: List<VideoQualityOption>): VideoQualityOption? =
            normalizedCodec?.let { codec -> pool.firstOrNull { it.vcodec.equals(codec, true) } }

        return matchingCodec(exact)
            ?: exact.firstOrNull { it.vcodec.equals("H.264", true) }
            ?: exact.firstOrNull()
            ?: matchingCodec(atOrBelow)
            ?: atOrBelow.firstOrNull()
            ?: options.firstOrNull()
    }

    /**
     * Re-resolves the muxed audio stream using the exact track chosen in the picker, falling back
     * to the default/original track and a container compatible with the video output.
     */
    private fun selectReResolvedAudio(
        options: List<AudioQualityOption>,
        task: DownloadTaskEntity,
        matchedVideo: VideoQualityOption?
    ): AudioQualityOption? {
        if (options.isEmpty()) return null

        if (task.isAudioOnly) {
            val extension = task.outputExt.lowercase()
            val bitrate = task.mergeAudioBitrateKbps
            val bitrateMatches: (AudioQualityOption) -> Boolean = { option ->
                bitrate == null || abs(option.bitrateKbps - bitrate) <= 16
            }
            return options.firstOrNull { it.extension.equals(extension, true) && it.isOriginalOrDefault && bitrateMatches(it) }
                ?: options.firstOrNull { it.extension.equals(extension, true) && bitrateMatches(it) }
                ?: options.firstOrNull { it.extension.equals(extension, true) && it.isOriginalOrDefault }
                ?: options.firstOrNull { it.extension.equals(extension, true) }
                ?: options.firstOrNull { it.isOriginalOrDefault }
                ?: options.first()
        }

        val preferredExtension = task.mergeAudioExtension
            ?.lowercase()
            ?.takeIf { it == "m4a" || it == "opus" }
            ?: if (matchedVideo?.vcodec?.equals("VP9", ignoreCase = true) == true) {
                "opus"
            } else if ((matchedVideo?.container ?: "mp4").equals("mp4", true)) {
                "m4a"
            } else {
                "opus"
            }
        val compatible = options.filter { it.extension.equals(preferredExtension, true) }
        val pool = compatible.ifEmpty { options }

        val trackId = task.mergeAudioTrackId
        val trackName = task.mergeAudioTrackName
        val language = task.mergeAudioLanguage
        val bitrate = task.mergeAudioBitrateKbps
        // Player-response and NewPipe bitrates for the same stream can differ by a few kbps.
        val bitrateMatches: (AudioQualityOption) -> Boolean = { option ->
            bitrate == null || abs(option.bitrateKbps - bitrate) <= 16
        }
        val matchesRequestedTrack: (AudioQualityOption) -> Boolean = { option ->
            (!trackId.isNullOrBlank() && option.audioTrackId == trackId) ||
                (!trackName.isNullOrBlank() && option.audioTrackName?.equals(trackName, true) == true) ||
                (!language.isNullOrBlank() && option.audioLanguage
                    ?.substringBefore('-')
                    ?.equals(language.substringBefore('-'), true) == true)
        }
        val hasTrackRequest = !trackId.isNullOrBlank() || !trackName.isNullOrBlank() || !language.isNullOrBlank()
        if (hasTrackRequest) {
            pool.filter { matchesRequestedTrack(it) && bitrateMatches(it) }
                .firstOrNull { it.isOriginalOrDefault }
                ?.let { return it }
            pool.filter { matchesRequestedTrack(it) && bitrateMatches(it) }
                .maxByOrNull { it.bitrateKbps }
                ?.let { return it }
            pool.filter { matchesRequestedTrack(it) }.maxByOrNull { it.bitrateKbps }?.let { return it }
            options.filter { matchesRequestedTrack(it) }.maxByOrNull { it.bitrateKbps }?.let { return it }
        }

        // No track metadata (or no match): honor the exact bitrate the user saw in the picker.
        if (bitrate != null) {
            options.filter { bitrateMatches(it) && it.extension.equals(preferredExtension, true) }
                .maxByOrNull { it.bitrateKbps }
                ?.let { return it }
        }

        return pool.filter { it.isOriginalOrDefault }.maxByOrNull { it.bitrateKbps }
            ?: pool.maxByOrNull { it.bitrateKbps }
    }

    /**
     * Deletes only files produced by the legacy JVM engine. yt-dlp's own partial downloads use a
     * different naming template and are intentionally preserved for resume.
     */
    private fun cleanLegacyArtifacts(workDir: File) {
        try {
            val legacyPrefixes = listOf(
                "output.",
                "temp_video.",
                "temp_audio.",
                "keep_part_",
                "trimmed_output."
            )
            workDir.listFiles()?.forEach { file ->
                val name = file.name.lowercase()
                if (name == "concat.txt" || legacyPrefixes.any { name.startsWith(it) }) {
                    file.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not clean legacy artifacts in %s", workDir.absolutePath)
        }
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

    private val resumeEngineNames = setOf(
        RESUME_ENGINE_FAST,
        RESUME_ENGINE_JVM,
        RESUME_ENGINE_YTDLP_FAST,
        RESUME_ENGINE_YTDLP_AUTH
    )

    /** yt-dlp names per-format streams `<title>.f<format_id>.<ext>[.part|.ytdl]`. */
    private val ytDlpFormatFile = Regex("""\.f[0-9a-z][0-9a-z_-]*\.[0-9a-z]+""")

    private fun markActiveEngine(workDir: File, engine: String) {
        try {
            workDir.mkdirs()
            val marker = File(workDir, RESUME_MARKER_FILE)
            if (!marker.exists() || marker.readText().trim() != engine) {
                marker.writeText(engine)
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not persist active engine %s in %s", engine, workDir.absolutePath)
        }
    }

    private fun readActiveEngine(workDir: File): String? {
        return try {
            val marker = File(workDir, RESUME_MARKER_FILE)
            if (!marker.isFile) return null
            marker.readText().trim().lowercase().takeIf { it in resumeEngineNames }
        } catch (e: Exception) {
            Timber.w(e, "Could not read active engine in %s", workDir.absolutePath)
            null
        }
    }

    /**
     * Best-effort reconstruction for tasks paused before the marker existed. yt-dlp wins over
     * native temp files because failed native attempts can leave stale artifacts behind while the
     * live transfer was already running inside yt-dlp.
     */
    private fun inferResumeEngine(workDir: File): String? {
        val names = workDir.listFiles()?.filter { it.isFile }?.map { it.name.lowercase() } ?: return null
        if (names.any { ytDlpFormatFile.containsMatchIn(it) }) {
            Timber.i("DownloadEngine: inferred yt-dlp resume for %s (format files present)", workDir.name)
            return RESUME_ENGINE_YTDLP_FAST
        }
        if (names.any { it.endsWith(".vgstate") || it.contains("_temp_v.") || it.contains("_temp_a.") }) {
            Timber.i("DownloadEngine: inferred FastDirect resume for %s (segment state present)", workDir.name)
            return RESUME_ENGINE_FAST
        }
        if (names.any { it.startsWith("temp_video.") || it.startsWith("temp_audio.") || it.startsWith("output.") }) {
            Timber.i("DownloadEngine: inferred DirectJvm resume for %s (temp streams present)", workDir.name)
            return RESUME_ENGINE_JVM
        }
        return null
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
            activeTasks.remove(taskId)
            pausedTasks.remove(taskId)
            YoutubeDL.getInstance().destroyProcessById(taskId)
            MediaStoreExporter.cleanupTaskWorkDir(context, taskId)
            Timber.i("Cancelled and cleaned up task: %s", taskId)
            true
        } catch (e: Exception) {
            Timber.e(e, "Error cancelling task %s", taskId)
            false
        }
    }

    private fun hasAudioTrack(file: File): Boolean {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) return true
            }
            false
        } catch (e: Exception) {
            true
        } finally {
            extractor.release()
        }
    }

    fun isTaskActive(taskId: String): Boolean = activeTasks.containsKey(taskId)

    private val percentRegex = Regex("""(\d+(?:\.\d+)?)%""")
    private val speedRegex = Regex("at\\s+([\\d.]+[KMGT]?i?B/s)")
    private val totalBytesRegex = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]?i?B)""", RegexOption.IGNORE_CASE)
    private val formatListRegex = Regex("""Downloading\s+\d+\s+format\(s\):\s*([0-9A-Za-z._-]+(?:\+[0-9A-Za-z._-]+)*)""")

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
