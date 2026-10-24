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
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

object FastDirectDownloader {

    private const val CHUNK_SIZE = 9L * 1024L * 1024L
    const val USER_AGENT = InnerTubeFastClient.VISIONOS_USER_AGENT
    const val REFERER = "https://www.youtube.com/"
    const val ORIGIN = "https://www.youtube.com"

    private val streamHttpClient by lazy {
        com.vidgrab.app.App.sharedOkHttpClient.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS) // No call timeout for large media streams
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
     * Downloads direct googlevideo.com streams in < 1 second connect time.
     * Supports:
     *  1. Single muxed video stream OR audio-only stream
     *  2. Separate MP4 video + M4A audio streams (downloaded in parallel & muxed via Android MediaMuxer in ~0.5s)
     */
    suspend fun downloadFast(
        taskId: String?,
        videoUrl: String?,
        audioUrl: String?,
        outputFile: File,
        onProgress: suspend (percent: Int, downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val startTime = System.currentTimeMillis()
            var lastCallbackTime = 0L

            if (taskId != null && DownloadEngineExecutor.isPaused(taskId)) {
                throw kotlinx.coroutines.CancellationException("Task $taskId was paused before start")
            }

            // Case 1: Single stream (Audio-only OR pre-muxed video)
            if (videoUrl.isNullOrBlank() || audioUrl.isNullOrBlank()) {
                val singleUrl = if (!videoUrl.isNullOrBlank()) videoUrl else (audioUrl ?: error("No direct URL provided"))
                val totalBytes = fetchContentLength(singleUrl)
                val downloadedAtomic = AtomicLong(0L)

                downloadStreamInChunks(taskId, singleUrl, outputFile, totalBytes) { bytesJustRead ->
                    val current = downloadedAtomic.addAndGet(bytesJustRead)
                    val now = System.currentTimeMillis()
                    if (now - lastCallbackTime >= 300 || current == totalBytes) {
                        lastCallbackTime = now
                        val elapsedSec = ((now - startTime) / 1000.0).coerceAtLeast(0.1)
                        val speed = (current / elapsedSec).toLong()
                        val pct = if (totalBytes > 0) ((current * 100L) / totalBytes).toInt().coerceIn(0, 100) else 0
                        kotlinx.coroutines.runBlocking {
                            onProgress(pct, current, totalBytes, speed)
                        }
                    }
                }
                return@runCatching outputFile
            }

            // Case 2: Separate Video (.mp4) + Audio (.m4a) -> Download in Parallel + Native Mux!
            val parentDir = outputFile.parentFile ?: outputFile
            parentDir.mkdirs()
            val tempVideo = File(parentDir, "${outputFile.nameWithoutExtension}_temp_v.mp4")
            val tempAudio = File(parentDir, "${outputFile.nameWithoutExtension}_temp_a.m4a")

            try {
                val (videoSize, audioSize) = coroutineScope {
                    val vJob = async { fetchContentLength(videoUrl) }
                    val aJob = async { fetchContentLength(audioUrl) }
                    vJob.await() to aJob.await()
                }
                val combinedTotal = (videoSize.takeIf { it > 0 } ?: 0L) + (audioSize.takeIf { it > 0 } ?: 0L)
                val downloadedAtomic = AtomicLong(0L)

                val reportProgress: (Long) -> Unit = { delta ->
                    val current = downloadedAtomic.addAndGet(delta)
                    val now = System.currentTimeMillis()
                    if (now - lastCallbackTime >= 300) {
                        lastCallbackTime = now
                        val elapsedSec = ((now - startTime) / 1000.0).coerceAtLeast(0.1)
                        val speed = (current / elapsedSec).toLong()
                        val pct = if (combinedTotal > 0) ((current * 95L) / combinedTotal).toInt().coerceIn(0, 95) else 0
                        kotlinx.coroutines.runBlocking {
                            onProgress(pct, current, combinedTotal, speed)
                        }
                    }
                }

                // Download video & audio streams simultaneously
                coroutineScope {
                    val vJob = async { downloadStreamInChunks(taskId, videoUrl, tempVideo, videoSize, reportProgress) }
                    val aJob = async { downloadStreamInChunks(taskId, audioUrl, tempAudio, audioSize, reportProgress) }
                    awaitAll(vJob, aJob)
                }

                if (taskId != null && DownloadEngineExecutor.isPaused(taskId)) {
                    throw kotlinx.coroutines.CancellationException("Task $taskId was paused before muxing")
                }

                // Fast hardware-level MP4 + M4A muxing (takes ~0.5 seconds, zero re-encoding)
                onProgress(97, combinedTotal, combinedTotal, 0L)
                muxVideoAndAudioMp4(tempVideo, tempAudio, outputFile)
                onProgress(100, combinedTotal, combinedTotal, 0L)
                outputFile
            } finally {
                runCatching { tempVideo.delete() }
                runCatching { tempAudio.delete() }
            }
        }
    }

    private suspend fun downloadStreamInChunks(
        taskId: String?,
        url: String,
        targetFile: File,
        totalBytes: Long,
        onBytesRead: (Long) -> Unit
    ) = coroutineScope {
        if (taskId != null && DownloadEngineExecutor.isPaused(taskId)) {
            throw kotlinx.coroutines.CancellationException("Task $taskId was paused")
        }

        if (totalBytes <= 10L * 1024L * 1024L) {
            // Direct single-stream fast download (fastest for audio and files <=10MB)
            downloadByteRange(taskId, url, targetFile, 0L, -1L, onBytesRead)
            return@coroutineScope
        }

        // Split into 9 MB ranges and download with up to 3 parallel workers
        val ranges = mutableListOf<Pair<Long, Long>>()
        var start = 0L
        while (start < totalBytes) {
            val end = (start + CHUNK_SIZE - 1).coerceAtMost(totalBytes - 1)
            ranges.add(start to end)
            start = end + 1
        }

        ranges.chunked(3).forEach { batch ->
            if (taskId != null && DownloadEngineExecutor.isPaused(taskId)) {
                throw kotlinx.coroutines.CancellationException("Task $taskId was paused")
            }
            batch.map { (rangeStart, rangeEnd) ->
                async(Dispatchers.IO) {
                    coroutineContext.ensureActive()
                    if (taskId != null && DownloadEngineExecutor.isPaused(taskId)) {
                        throw kotlinx.coroutines.CancellationException("Task $taskId was paused")
                    }
                    downloadByteRange(taskId, url, targetFile, rangeStart, rangeEnd, onBytesRead)
                }
            }.awaitAll()
        }
    }

    private suspend fun downloadByteRange(
        taskId: String?,
        url: String,
        targetFile: File,
        startByte: Long,
        endByte: Long,
        onBytesRead: (Long) -> Unit
    ) {
        val reqBuilder = createStreamRequestBuilder(url)
        if (endByte >= startByte) {
            reqBuilder.addHeader("Range", "bytes=$startByte-$endByte")
        }

        streamHttpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.e("FastDirectDownloader: HTTP %d for url: %s", response.code, url)
                error("Stream HTTP error: ${response.code}")
            }
            val body = response.body ?: error("Empty stream body")

            RandomAccessFile(targetFile, "rw").use { raf ->
                raf.seek(startByte)
                val buffer = ByteArray(64 * 1024) // 64 KB buffer for fast sequential disk writes
                val input = body.byteStream()
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    coroutineContext.ensureActive()
                    if (taskId != null && DownloadEngineExecutor.isPaused(taskId)) {
                        throw kotlinx.coroutines.CancellationException("Task $taskId was paused")
                    }
                    raf.write(buffer, 0, read)
                    onBytesRead(read.toLong())
                }
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
