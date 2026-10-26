package com.vidgrab.app.core.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.vidgrab.app.core.extractor.InnerTubeFastClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

object FastDirectDownloader {

    const val PARALLEL_CONNECTIONS = 8
    const val USER_AGENT = InnerTubeFastClient.VISIONOS_USER_AGENT
    const val REFERER = "https://www.youtube.com/"
    const val ORIGIN = "https://www.youtube.com"

    private val highSpeedDispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 32
    }

    private val highSpeedConnectionPool = ConnectionPool(32, 5, TimeUnit.MINUTES)

    private val streamHttpClient by lazy {
        com.vidgrab.app.App.sharedOkHttpClient.newBuilder()
            .dispatcher(highSpeedDispatcher)
            .connectionPool(highSpeedConnectionPool)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS) // No call timeout for large media streams
            .build()
    }

    private fun getActiveCookies(): String? {
        return try {
            android.webkit.CookieManager.getInstance().getCookie("https://m.youtube.com")
                ?: android.webkit.CookieManager.getInstance().getCookie("https://www.youtube.com")
        } catch (e: Exception) {
            null
        }
    }

    private fun createStreamRequestBuilder(url: String): Request.Builder {
        val isMweb = url.contains("c=MWEB") || url.contains("c=WEB")
        val ua = if (isMweb) com.vidgrab.app.core.network.NetworkConstants.activeUserAgent else USER_AGENT
        val referer = if (isMweb) "https://m.youtube.com/" else "https://www.youtube.com/"
        val origin = if (isMweb) "https://m.youtube.com" else "https://www.youtube.com"

        val builder = Request.Builder()
            .url(url)
            .addHeader("User-Agent", ua)
            .addHeader("Referer", referer)
            .addHeader("Origin", origin)

        val cookies = getActiveCookies()
        if (!cookies.isNullOrBlank()) {
            builder.addHeader("Cookie", cookies)
        }
        return builder
    }

    /**
     * Checks if a direct stream URL is present and not expired (with 60-second buffer, matching 1DM logic).
     */
    fun isStreamUrlValid(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val expireSeconds = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return true
        val expireMillis = expireSeconds * 1000L
        return expireMillis > (System.currentTimeMillis() + 60_000L)
    }

    /**
     * Downloads direct googlevideo.com streams using true IDM-style 8-connection parallel segmented streaming.
     */
    suspend fun downloadFast(
        taskId: String?,
        videoUrl: String?,
        audioUrl: String?,
        outputFile: File,
        onProgress: suspend (
            percent: Int,
            downloadedBytes: Long,
            totalBytes: Long,
            speedBytesPerSec: Long,
            activeConnections: Int,
            segmentProgressCsv: String
        ) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val startTime = System.currentTimeMillis()
            var lastCallbackTime = 0L

            if (taskId != null && DownloadEngineExecutor.isPaused(taskId)) {
                throw kotlinx.coroutines.CancellationException("Task $taskId was paused before start")
            }

            val parentDir = outputFile.parentFile ?: outputFile
            parentDir.mkdirs()

            // Case 1: Single stream (Audio-only OR pre-muxed progressive video)
            if (videoUrl.isNullOrBlank() || audioUrl.isNullOrBlank()) {
                val singleUrl = if (!videoUrl.isNullOrBlank()) videoUrl else (audioUrl ?: error("No direct URL provided"))
                val totalBytes = fetchContentLength(singleUrl)
                val downloadedAtomic = AtomicLong(0L)
                val activeConns = AtomicInteger(0)

                val segmentFractions = FloatArray(PARALLEL_CONNECTIONS) { 0f }

                // Download into a .part file so an interrupted attempt is never picked up as a finished media file.
                // The .vgstate sidecar records per-segment byte offsets so a paused transfer resumes instead of restarting.
                val partialFile = File(parentDir, outputFile.name + ".part")
                val stateFile = File(parentDir, outputFile.name + ".part.vgstate")

                val emitProgress: (Boolean) -> Unit = { force ->
                    val current = downloadedAtomic.get()
                    val now = System.currentTimeMillis()
                    if (force || now - lastCallbackTime >= 250 || (totalBytes > 0 && current >= totalBytes)) {
                        lastCallbackTime = now
                        val elapsedSec = ((now - startTime) / 1000.0).coerceAtLeast(0.1)
                        val speed = if (force) 0L else (current / elapsedSec).toLong()
                        val pct = if (totalBytes > 0) ((current * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
                        val csv = segmentFractions.joinToString(",") { String.format(java.util.Locale.US, "%.2f", it) }
                        val conns = activeConns.get().coerceIn(1, PARALLEL_CONNECTIONS)
                        kotlinx.coroutines.runBlocking {
                            onProgress(pct, current, totalBytes, speed, conns, csv)
                        }
                    }
                }

                downloadStreamInSegments(
                    taskId = taskId,
                    url = singleUrl,
                    targetFile = partialFile,
                    stateFile = stateFile,
                    totalBytes = totalBytes,
                    activeConns = activeConns,
                    onSegmentProgress = { segIndex, fraction ->
                        if (segIndex in segmentFractions.indices) {
                            segmentFractions[segIndex] = fraction
                        }
                    },
                    onResumedBytes = { resumed ->
                        if (resumed > 0L) {
                            downloadedAtomic.set(resumed)
                            emitProgress(true)
                        }
                    },
                    onBytesRead = { bytesJustRead ->
                        downloadedAtomic.addAndGet(bytesJustRead)
                        emitProgress(false)
                    }
                )

                if (partialFile.length() <= 0L) {
                    error("Direct stream produced an empty file")
                }
                // A pause that lands after the last byte must keep the finished .part resumable,
                // so the next run only has to rename it instead of transferring again.
                if (taskId != null && DownloadEngineExecutor.isPaused(taskId)) {
                    throw kotlinx.coroutines.CancellationException("Task $taskId was paused before finalizing")
                }
                SegmentResumeState.delete(stateFile)
                if (outputFile.exists() && !outputFile.delete()) {
                    error("Could not replace existing output file ${outputFile.name}")
                }
                if (!partialFile.renameTo(outputFile)) {
                    partialFile.copyTo(outputFile, overwrite = true)
                    partialFile.delete()
                }
                return@runCatching outputFile
            }

            // Case 2: Separate Video (.mp4) + Audio (.m4a) -> Download in Parallel + Native Mux!
            val tempVideo = File(parentDir, "${outputFile.nameWithoutExtension}_temp_v.mp4.part")
            val tempAudio = File(parentDir, "${outputFile.nameWithoutExtension}_temp_a.m4a.part")
            val videoStateFile = File(parentDir, "${outputFile.nameWithoutExtension}_temp_v.vgstate")
            val audioStateFile = File(parentDir, "${outputFile.nameWithoutExtension}_temp_a.vgstate")
            val partialFile = File(parentDir, outputFile.name + ".part")

            val (videoSize, audioSize) = coroutineScope {
                val vJob = async { fetchContentLength(videoUrl) }
                val aJob = async { fetchContentLength(audioUrl) }
                vJob.await() to aJob.await()
            }
            val combinedTotal = (videoSize.takeIf { it > 0 } ?: 0L) + (audioSize.takeIf { it > 0 } ?: 0L)
            val downloadedAtomic = AtomicLong(0L)
            val activeConns = AtomicInteger(0)

            val segmentFractions = FloatArray(PARALLEL_CONNECTIONS) { 0f }

            val reportProgress: (Boolean) -> Unit = { force ->
                val current = downloadedAtomic.get()
                val now = System.currentTimeMillis()
                if (force || now - lastCallbackTime >= 250) {
                    lastCallbackTime = now
                    val elapsedSec = ((now - startTime) / 1000.0).coerceAtLeast(0.1)
                    val speed = if (force) 0L else (current / elapsedSec).toLong()
                    val pct = if (combinedTotal > 0) ((current * 95L) / combinedTotal).toInt().coerceIn(0, 95) else 0
                    val csv = segmentFractions.joinToString(",") { String.format(java.util.Locale.US, "%.2f", it) }
                    val conns = activeConns.get().coerceIn(1, PARALLEL_CONNECTIONS)
                    kotlinx.coroutines.runBlocking {
                        onProgress(pct, current, combinedTotal, speed, conns, csv)
                    }
                }
            }

            // Download video (6 sockets) & audio (2 sockets) simultaneously = 8 concurrent connections!
            coroutineScope {
                val vJob = async {
                    downloadStreamInSegments(
                        taskId = taskId,
                        url = videoUrl,
                        targetFile = tempVideo,
                        stateFile = videoStateFile,
                        totalBytes = videoSize,
                        parallelCount = 6,
                        activeConns = activeConns,
                        onSegmentProgress = { idx, frac ->
                            if (idx in 0..5) segmentFractions[idx] = frac
                        },
                        onResumedBytes = { resumed ->
                            if (resumed > 0L) {
                                downloadedAtomic.addAndGet(resumed)
                                reportProgress(true)
                            }
                        },
                        onBytesRead = { delta ->
                            downloadedAtomic.addAndGet(delta)
                            reportProgress(false)
                        }
                    )
                }
                val aJob = async {
                    downloadStreamInSegments(
                        taskId = taskId,
                        url = audioUrl,
                        targetFile = tempAudio,
                        stateFile = audioStateFile,
                        totalBytes = audioSize,
                        parallelCount = 2,
                        activeConns = activeConns,
                        onSegmentProgress = { idx, frac ->
                            val slot = 6 + idx
                            if (slot in segmentFractions.indices) segmentFractions[slot] = frac
                        },
                        onResumedBytes = { resumed ->
                            if (resumed > 0L) {
                                downloadedAtomic.addAndGet(resumed)
                                reportProgress(true)
                            }
                        },
                        onBytesRead = { delta ->
                            downloadedAtomic.addAndGet(delta)
                            reportProgress(false)
                        }
                    )
                }
                awaitAll(vJob, aJob)
            }

            if (taskId != null && DownloadEngineExecutor.isPaused(taskId)) {
                throw kotlinx.coroutines.CancellationException("Task $taskId was paused before muxing")
            }

            // Fast hardware-level MP4 + M4A muxing (~0.5s). Both streams are complete at this
            // point, so a paused mux only costs the mux again — never the transfer.
            onProgress(97, combinedTotal, combinedTotal, 0L, 0, "")
            muxVideoAndAudio(tempVideo, tempAudio, partialFile)

            if (partialFile.length() <= 0L) {
                error("Muxing produced an empty media file")
            }
            // Both streams are complete; a pause here only costs the (fast) mux again.
            if (taskId != null && DownloadEngineExecutor.isPaused(taskId)) {
                throw kotlinx.coroutines.CancellationException("Task $taskId was paused after muxing")
            }
            if (outputFile.exists() && !outputFile.delete()) {
                error("Could not replace existing output file ${outputFile.name}")
            }
            if (!partialFile.renameTo(outputFile)) {
                partialFile.copyTo(outputFile, overwrite = true)
                partialFile.delete()
            }

            // Only a finished file clears the byte maps and the stream partials.
            SegmentResumeState.delete(videoStateFile)
            SegmentResumeState.delete(audioStateFile)
            runCatching { tempVideo.delete() }
            runCatching { tempAudio.delete() }

            val finalSize = outputFile.length().takeIf { it > 0L } ?: combinedTotal
            onProgress(100, finalSize, finalSize, 0L, 0, "")
            outputFile
        }
    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }

    private suspend fun downloadStreamInSegments(
        taskId: String?,
        url: String,
        targetFile: File,
        stateFile: File,
        totalBytes: Long,
        parallelCount: Int = PARALLEL_CONNECTIONS,
        activeConns: AtomicInteger,
        onSegmentProgress: (Int, Float) -> Unit = { _, _ -> },
        onResumedBytes: (Long) -> Unit = {},
        onBytesRead: (Long) -> Unit
    ) = coroutineScope {
        if (taskId != null && DownloadEngineExecutor.isPaused(taskId)) {
            throw kotlinx.coroutines.CancellationException("Task $taskId was paused")
        }

        // Unknown length (no clen=, no Content-Length): a byte map is impossible, so stream once
        // and accept that this particular stream cannot be resumed.
        if (totalBytes <= 0L) {
            activeConns.incrementAndGet()
            try {
                downloadByteRange(taskId, url, targetFile, startByte = 0L, endByte = -1L, onBytesRead = onBytesRead)
                onSegmentProgress(0, 1f)
            } finally {
                activeConns.decrementAndGet()
            }
            return@coroutineScope
        }

        // One socket for tiny streams (<= 2 MB), IDM-style parallel ranges above that.
        val requestedSegments = if (totalBytes <= 2L * 1024L * 1024L) 1 else parallelCount.coerceAtLeast(1)
        val resume = SegmentResumeState.open(
            stateFile = stateFile,
            streamId = SegmentResumeState.streamIdentity(url, totalBytes),
            totalBytes = totalBytes,
            planned = SegmentResumeState.plan(totalBytes, requestedSegments)
        )

        // Paint the restored byte map before the first socket opens so the UI resumes at the real
        // percentage instead of dropping to zero.
        resume.segments.indices.forEach { index -> onSegmentProgress(index, resume.fraction(index)) }
        resume.downloadedTotal().takeIf { it > 0L }?.let(onResumedBytes)

        try {
            resume.segments.mapIndexed { index, segment ->
                async(Dispatchers.IO) {
                    coroutineContext.ensureActive()
                    if (taskId != null && DownloadEngineExecutor.isPaused(taskId)) {
                        throw kotlinx.coroutines.CancellationException("Task $taskId was paused")
                    }
                    activeConns.incrementAndGet()
                    try {
                        val from = segment.nextOffset
                        if (from <= segment.end) {
                            downloadByteRange(
                                taskId = taskId,
                                url = url,
                                targetFile = targetFile,
                                startByte = from,
                                endByte = segment.end,
                                expectedBytes = segment.end - from + 1L
                            ) { bytesRead ->
                                resume.recordBytes(index, bytesRead)
                                onSegmentProgress(index, resume.fraction(index))
                                resume.persist()
                                onBytesRead(bytesRead)
                            }
                        }
                        onSegmentProgress(index, 1f)
                    } finally {
                        activeConns.decrementAndGet()
                    }
                }
            }.awaitAll()
        } finally {
            // Pause/cancellation lands here too: flush the byte map so the next run continues.
            resume.persist(force = true)
        }
    }

    private suspend fun downloadByteRange(
        taskId: String?,
        url: String,
        targetFile: File,
        startByte: Long,
        endByte: Long,
        expectedBytes: Long = -1L,
        onBytesRead: (Long) -> Unit
    ) {
        val reqBuilder = createStreamRequestBuilder(url)
        val isRangeRequest = endByte >= startByte
        if (isRangeRequest) {
            reqBuilder.addHeader("Range", "bytes=$startByte-$endByte")
        }

        streamHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.e("FastDirectDownloader: HTTP %d for url: %s", response.code, url)
                error("Stream HTTP error: ${response.code}")
            }

            // Anti-Corruption Defense: a ranged request MUST come back as 206 Partial Content.
            // If the server ignores Range and sends 200 OK, writing at a byte offset would corrupt the file.
            if (isRangeRequest) {
                if (response.code != 206) {
                    Timber.w("FastDirectDownloader: Server ignored byte range %d-%d (HTTP %d)", startByte, endByte, response.code)
                    error("Server ignored byte range request (returned ${response.code})")
                }
                val contentRange = response.header("Content-Range")
                val rangeStart = contentRange
                    ?.substringAfter("bytes ", "")
                    ?.substringBefore("-")
                    ?.trim()
                    ?.toLongOrNull()
                if (rangeStart != null && rangeStart != startByte) {
                    Timber.w("FastDirectDownloader: Wrong Content-Range start '%s' (expected %d)", contentRange, startByte)
                    error("Server returned a mismatched Content-Range")
                }
            }

            val body = response.body ?: error("Empty stream body")
            var totalRead = 0L

            RandomAccessFile(targetFile, "rw").use { raf ->
                raf.seek(startByte)
                val buffer = ByteArray(64 * 1024) // 64 KB buffer for high-throughput writes
                val input = body.byteStream()
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    coroutineContext.ensureActive()
                    if (taskId != null && DownloadEngineExecutor.isPaused(taskId)) {
                        throw kotlinx.coroutines.CancellationException("Task $taskId was paused")
                    }
                    raf.write(buffer, 0, read)
                    totalRead += read
                    onBytesRead(read.toLong())
                }
            }

            if (expectedBytes > 0L && totalRead != expectedBytes) {
                // A truncated body must never be treated as a completed segment.
                error("Short range read: expected $expectedBytes bytes but received $totalRead")
            }
        }
    }

    private fun fetchContentLength(url: String): Long {
        // 1. Check if 'clen=' parameter is already in the googlevideo.com URL (0ms network cost)
        Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let {
            if (it > 0L) return it
        }
        // 2. Otherwise request 1 byte with Range: bytes=0-0
        val req = createStreamRequestBuilder(url)
            .addHeader("Range", "bytes=0-0")
            .build()
        return runCatching {
            streamHttpClient.newCall(req).execute().use { res ->
                val contentRange = res.header("Content-Range")
                contentRange?.substringAfterLast('/')?.toLongOrNull()
                    ?: res.header("Content-Length")?.toLongOrNull()
                    ?: -1L
            }
        }.getOrDefault(-1L)
    }

    private fun muxVideoAndAudio(videoFile: File, audioFile: File, outputFile: File) {
        try {
            muxVideoAndAudioMp4(videoFile, audioFile, outputFile)
            return
        } catch (e: Exception) {
            Timber.w(e, "FastDirectDownloader: MediaMuxer failed, attempting FFmpeg -c copy fallback")
            runCatching { outputFile.delete() }
        }
        val ffmpegSuccess = muxViaFfmpeg(videoFile, audioFile, outputFile)
        if (!ffmpegSuccess) {
            throw RuntimeException("Both MediaMuxer and FFmpeg copy failed to mux audio and video")
        }
    }

    /**
     * Real FFmpeg stream-copy mux fallback (used when MediaMuxer cannot handle the codecs).
     * Runs the bundled libffmpeg.so binary directly from the app's native library dir.
     */
    private fun muxViaFfmpeg(videoFile: File, audioFile: File, outputFile: File): Boolean {
        return runCatching {
            val nativeDir = com.vidgrab.app.App.instance.applicationInfo.nativeLibraryDir
            val ffmpegBin = File(nativeDir, "libffmpeg.so")
            if (!ffmpegBin.exists() || !ffmpegBin.canExecute()) {
                Timber.w("FastDirectDownloader: libffmpeg.so missing at %s", ffmpegBin.absolutePath)
                return false
            }

            val command = listOf(
                ffmpegBin.absolutePath,
                "-y",
                "-hide_banner",
                "-loglevel", "error",
                "-i", videoFile.absolutePath,
                "-i", audioFile.absolutePath,
                "-c", "copy",
                "-movflags", "+faststart",
                outputFile.absolutePath
            )
            val process = ProcessBuilder(command).apply {
                environment()["LD_LIBRARY_PATH"] = nativeDir
                redirectErrorStream(true)
            }.start()

            process.inputStream.readBytes()
            val exitCode = process.waitFor()
            val success = exitCode == 0 && outputFile.exists() && outputFile.length() > 0L
            if (!success) {
                Timber.w("FastDirectDownloader: FFmpeg mux failed (exit=%d)", exitCode)
            }
            success
        }.getOrDefault(false)
    }

    /**
     * Uses Android's built-in MediaMuxer to combine MP4 video + M4A audio in ~0.5s without FFmpeg.
     */
    private fun muxVideoAndAudioMp4(videoFile: File, audioFile: File, outputFile: File) {
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()

        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            var videoTrackIndex = -1
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoExtractor.selectTrack(i)
                    videoTrackIndex = muxer.addTrack(format)
                    break
                }
            }

            var audioTrackIndex = -1
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioExtractor.selectTrack(i)
                    audioTrackIndex = muxer.addTrack(format)
                    break
                }
            }

            if (videoTrackIndex == -1 && audioTrackIndex == -1) {
                error("No valid video or audio track found for MediaMuxer")
            }

            muxer.start()
            val buffer = ByteBuffer.allocate(1024 * 1024) // 1 MB sample buffer
            val bufferInfo = MediaCodec.BufferInfo()

            if (videoTrackIndex != -1) {
                while (true) {
                    val sampleSize = videoExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                    bufferInfo.flags = videoExtractor.sampleFlags
                    muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                    videoExtractor.advance()
                }
            }

            if (audioTrackIndex != -1) {
                while (true) {
                    val sampleSize = audioExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                    bufferInfo.flags = audioExtractor.sampleFlags
                    muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                    audioExtractor.advance()
                }
            }

            muxer.stop()
            Timber.i("MediaMuxer successfully produced %s (%d bytes)", outputFile.name, outputFile.length())
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            runCatching { muxer.release() }
        }
    }
}
