package com.vidgrab.app.core.engine

import android.content.Context
import com.vidgrab.app.App
import com.vidgrab.app.core.database.DownloadTaskEntity
import com.vidgrab.app.core.network.NetworkConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.abs

/**
 * Stage 1 Fast JVM Direct Stream Downloader:
 *
 * Eliminates the 2-5 second Python cold start and yt-dlp client extraction failures:
 * 1. Resolves StreamInfo directly in the warm JVM via NewPipeExtractor (~200ms).
 * 2. Downloads stream URLs directly via OkHttp using 2MB Range chunks (bypassing YouTube CDN throttling).
 * 3. Progressive <= 360p streams are saved directly as MP4 with zero FFmpeg needed.
 * 4. High-res (720p/1080p) streams download video and audio in sequence and mux with FFmpeg -c copy.
 */
object DirectJvmStreamDownloader {

    private const val CHUNK_SIZE = 2 * 1024 * 1024L // 2MB chunking for YouTube CDN

    /**
     * The 1-byte size probe runs on a 6 s deadline. On the shared client (20 s connect / 25 s read)
     * a stalled probe kept the whole task sitting at "Connecting..." before requesting any bytes.
     */
    private val probeClient by lazy {
        App.sharedOkHttpClient.newBuilder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(9, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    suspend fun download(
        context: Context,
        task: DownloadTaskEntity,
        workDir: File,
        isCancelled: () -> Boolean = { false },
        onProgress: suspend (progress: Float, etaSeconds: Long, speedText: String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val initP = task.progressPercent.coerceAtLeast(1f)
        onProgress(initP, -1L, if (task.progressPercent > 2f) "Resuming..." else "Connecting...")

        val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, task.url)
        val durationSec = streamInfo.duration.coerceAtLeast(1L)
        Timber.i("DirectJvm: Resolved StreamInfo for '%s' (duration=%ds, videos=%d, audios=%d)",
            streamInfo.name, durationSec, streamInfo.videoStreams.size, streamInfo.audioStreams.size)

        if (isCancelled()) throw InterruptedException("Download cancelled by user")

        if (task.isAudioOnly) {
            val audioFile = downloadAudioOnly(streamInfo, task, workDir, isCancelled, onProgress)
            return@withContext if (task.cutSponsor && !task.sponsorSegmentsJson.isNullOrBlank()) {
                cutSponsorSegments(context, audioFile, task.sponsorSegmentsJson, isCancelled, onProgress)
            } else {
                audioFile
            }
        } else {
            val videoFile = downloadVideo(context, streamInfo, task, workDir, isCancelled, onProgress)
            return@withContext if (task.cutSponsor && !task.sponsorSegmentsJson.isNullOrBlank()) {
                cutSponsorSegments(context, videoFile, task.sponsorSegmentsJson, isCancelled, onProgress)
            } else {
                videoFile
            }
        }
    }

    private suspend fun downloadAudioOnly(
        streamInfo: StreamInfo,
        task: DownloadTaskEntity,
        workDir: File,
        isCancelled: () -> Boolean,
        onProgress: suspend (Float, Long, String) -> Unit
    ): File {
        val audioStreams = streamInfo.audioStreams
        if (audioStreams.isEmpty()) {
            throw IllegalStateException("No audio streams available for this video")
        }

        val preferredExt = task.outputExt.lowercase()
        val requestedTrackId = task.mergeAudioTrackId
        val requestedBitrateKbps = task.mergeAudioBitrateKbps
        val chosenStream: AudioStream = audioStreams
            .sortedWith(
                compareByDescending<AudioStream> {
                    !requestedTrackId.isNullOrBlank() && it.audioTrackId == requestedTrackId
                }.thenByDescending {
                    it.format?.suffix?.equals(preferredExt, true) == true
                }.thenByDescending {
                    requestedBitrateKbps != null && abs((it.bitrate / 1000) - requestedBitrateKbps) <= 16
                }.thenByDescending {
                    it.audioTrackType == AudioTrackType.ORIGINAL || it.audioTrackId == null
                }.thenByDescending {
                    it.codec?.contains("mp4a", ignoreCase = true) == true ||
                        it.format?.suffix?.equals("m4a", true) == true
                }.thenByDescending { it.bitrate }
            )
            .firstOrNull() ?: throw IllegalStateException("No audio streams available for this video")

        val streamUrl = chosenStream.content ?: chosenStream.url ?: throw IllegalStateException("Audio stream URL is null")
        val actualExt = chosenStream.format?.suffix ?: preferredExt
        val outputFile = File(workDir, "output.$actualExt")

        Timber.i("DirectJvm: Selected AudioStream bitrate=%d, format=%s, url=%s", chosenStream.bitrate, actualExt, streamUrl.take(60))

        downloadStreamUrl(
            url = streamUrl,
            outputFile = outputFile,
            label = "Audio",
            startProgress = 0f,
            endProgress = 100f,
            isCancelled = isCancelled,
            onProgress = onProgress
        )

        return outputFile
    }

    private suspend fun downloadVideo(
        context: Context,
        streamInfo: StreamInfo,
        task: DownloadTaskEntity,
        workDir: File,
        isCancelled: () -> Boolean,
        onProgress: suspend (Float, Long, String) -> Unit
    ): File {
        val targetHeight = task.targetHeight ?: 1080
        val requestedTrackId = task.mergeAudioTrackId
        val requestedBitrateKbps = task.mergeAudioBitrateKbps
        val preferredCodec = task.videoCodecPreference?.takeIf {
            it.isNotBlank() && !it.equals("Auto", true)
        }

        // Step A: For <= 360p, check if a progressive pre-muxed video stream (video + audio)
        // exists. Only safe when the user did not request a specific codec or audio stream:
        // progressive streams are always H.264 with the original audio.
        val codecRequiresDash = preferredCodec != null && !preferredCodec.equals("H.264", true)
        val containerAllowsProgressive = task.outputExt.equals("mp4", ignoreCase = true)
        if (containerAllowsProgressive && targetHeight <= 360 && requestedTrackId.isNullOrBlank() &&
            requestedBitrateKbps == null && !codecRequiresDash
        ) {
            val progressiveMuxed = streamInfo.videoStreams.firstOrNull {
                val res = if (it.width > 0 && it.height > 0) minOf(it.width, it.height) else it.height
                res in 1..targetHeight
            }
            val muxedUrl = progressiveMuxed?.content ?: progressiveMuxed?.url
            if (progressiveMuxed != null && !muxedUrl.isNullOrBlank()) {
                val res = if (progressiveMuxed.width > 0 && progressiveMuxed.height > 0) minOf(progressiveMuxed.width, progressiveMuxed.height) else progressiveMuxed.height
                Timber.i("DirectJvm: Found pre-muxed progressive %dp stream! Downloading directly...", res)
                val outputFile = File(workDir, "output.mp4")

                downloadStreamUrl(
                    url = muxedUrl,
                    outputFile = outputFile,
                    label = "${res}p Video",
                    startProgress = 2f,
                    endProgress = 100f,
                    isCancelled = isCancelled,
                    onProgress = onProgress
                )
                return outputFile
            }
        }

        // Step B: For high-res (720p, 1080p+) or DASH streams, select video-only + audio streams
        val allVideo = streamInfo.videoOnlyStreams.ifEmpty { streamInfo.videoStreams }
        if (allVideo.isEmpty()) {
            throw IllegalStateException("No video streams available for resolution <= ${targetHeight}p")
        }

        val chosenVideo: VideoStream = allVideo
            .filter { it.effectiveHeight() in 1..targetHeight }
            .sortedWith(
                compareByDescending<VideoStream> { it.effectiveHeight() }
                    .thenByDescending { stream ->
                        when {
                            preferredCodec == null -> false
                            preferredCodec.equals("H.264", true) ->
                                stream.codec?.contains("avc", ignoreCase = true) == true
                            preferredCodec.equals("VP9", true) ->
                                stream.codec?.contains("vp9", ignoreCase = true) == true ||
                                    stream.codec?.contains("vp09", ignoreCase = true) == true
                            preferredCodec.equals("AV1", true) ->
                                stream.codec?.contains("av01", ignoreCase = true) == true
                            else -> false
                        }
                    }
                    .thenByDescending { it.codec?.contains("avc", ignoreCase = true) == true }
                    .thenByDescending { it.bitrate }
            ).firstOrNull() ?: allVideo.minByOrNull {
                Math.abs(it.effectiveHeight() - targetHeight)
            }!!

        // Container compatibility: MP4 video output takes M4A/AAC; WebM-oriented codecs take Opus.
        val chosenVideoContainerIsMp4 = chosenVideo.codec?.contains("vp9", ignoreCase = true) != true &&
            chosenVideo.codec?.contains("vp09", ignoreCase = true) != true
        val requestedAudioExt = task.mergeAudioExtension?.lowercase()?.takeIf { it == "m4a" || it == "opus" }
        val preferredAudioExt = requestedAudioExt
            ?: if (chosenVideoContainerIsMp4) "m4a" else "opus"

        val audioStreams = streamInfo.audioStreams
        val requestedTrackName = task.mergeAudioTrackName
        val requestedLanguage = task.mergeAudioLanguage
        val hasTrackRequest = !requestedTrackId.isNullOrBlank() ||
            !requestedTrackName.isNullOrBlank() ||
            !requestedLanguage.isNullOrBlank()
        val streamMatchesRequestedTrack: (AudioStream) -> Boolean = { stream ->
            (!requestedTrackId.isNullOrBlank() && stream.audioTrackId == requestedTrackId) ||
                (!requestedTrackName.isNullOrBlank() &&
                    stream.audioTrackName?.equals(requestedTrackName, true) == true) ||
                (!requestedLanguage.isNullOrBlank() && stream.audioLocale
                    ?.language
                    ?.equals(requestedLanguage.substringBefore('-'), true) == true)
        }
        val exactAudio = if (hasTrackRequest || requestedBitrateKbps != null) {
            audioStreams.firstOrNull { stream ->
                val trackMatches = !hasTrackRequest || streamMatchesRequestedTrack(stream)
                val bitrateMatches = requestedBitrateKbps == null ||
                    abs((stream.bitrate / 1000) - requestedBitrateKbps) <= 16
                val extMatches = requestedAudioExt == null ||
                    stream.format?.suffix?.equals(requestedAudioExt, true) == true
                trackMatches && bitrateMatches && extMatches
            }
        } else {
            // Auto: no explicit stream was requested, so let the sorted fallback pick the
            // highest-quality compatible track instead of the first listed one.
            null
        }

        val chosenAudio: AudioStream = exactAudio ?: audioStreams
            .sortedWith(
                compareByDescending<AudioStream> {
                    !requestedTrackId.isNullOrBlank() && it.audioTrackId == requestedTrackId
                }.thenByDescending {
                    !requestedTrackName.isNullOrBlank() &&
                        it.audioTrackName?.equals(requestedTrackName, true) == true
                }.thenByDescending {
                    !requestedLanguage.isNullOrBlank() && it.audioLocale
                        ?.language
                        ?.equals(requestedLanguage.substringBefore('-'), true) == true
                }.thenByDescending {
                    requestedBitrateKbps != null &&
                        abs((it.bitrate / 1000) - requestedBitrateKbps) <= 16
                }.thenByDescending {
                    it.format?.suffix?.equals(preferredAudioExt, true) == true
                }.thenByDescending {
                    it.audioTrackType == AudioTrackType.ORIGINAL || it.audioTrackId == null
                }.thenByDescending {
                    it.codec?.contains("mp4a", ignoreCase = true) == true ||
                        it.format?.suffix?.equals("m4a", true) == true
                }.thenByDescending { it.bitrate }
            )
            .firstOrNull() ?: throw IllegalStateException("No matching audio stream for video muxing")

        val videoUrl = chosenVideo.content ?: chosenVideo.url ?: throw IllegalStateException("Video stream URL is null")
        val audioUrl = chosenAudio.content ?: chosenAudio.url ?: throw IllegalStateException("Audio stream URL is null")

        val effectiveVideoRes = if (chosenVideo.width > 0 && chosenVideo.height > 0) minOf(chosenVideo.width, chosenVideo.height) else chosenVideo.height
        Timber.i("DirectJvm: Selected VideoStream=%dp (%s) and AudioStream=%d kbps",
            effectiveVideoRes, chosenVideo.codec, chosenAudio.bitrate / 1000)

        val tempVideo = File(workDir, "temp_video.mp4")
        val tempAudio = File(workDir, "temp_audio.${chosenAudio.format?.suffix ?: "m4a"}")

        // 1. Download Video Stream (progress 2% -> 78%)
        downloadStreamUrl(
            url = videoUrl,
            outputFile = tempVideo,
            label = "${effectiveVideoRes}p Video",
            startProgress = 0f,
            endProgress = 78f,
            isCancelled = isCancelled,
            onProgress = onProgress
        )

        if (isCancelled()) throw InterruptedException("Download cancelled by user")

        // 2. Download Audio Stream (progress 78% -> 92%)
        downloadStreamUrl(
            url = audioUrl,
            outputFile = tempAudio,
            label = "Audio",
            startProgress = 78f,
            endProgress = 92f,
            isCancelled = isCancelled,
            onProgress = onProgress
        )

        if (isCancelled()) throw InterruptedException("Download cancelled by user")

        // 3. Lossless Muxing (94% -> 100%)
        onProgress(94f, 2L, "Merging audio and video...")

        // Honor the container promised by the picker: VP9+Opus in WebM, AVC/AV1+AAC in MP4,
        // and anything mixed (H.264+Opus, VP9+AAC) in MKV. MKV is the universal fallback.
        val preferredContainer = task.outputExt.lowercase()
        val muxCandidates = when (preferredContainer) {
            "webm" -> listOf(File(workDir, "output.webm"), File(workDir, "output.mkv"))
            "mkv" -> listOf(File(workDir, "output.mkv"), File(workDir, "output.mp4"))
            else -> listOf(File(workDir, "output.mp4"), File(workDir, "output.mkv"))
        }

        for (candidate in muxCandidates) {
            if (candidate.exists()) candidate.delete()
            if (runFFmpegMux(context, tempVideo, tempAudio, candidate, isCancelled) &&
                candidate.exists() && candidate.length() > 0
            ) {
                tempVideo.delete()
                tempAudio.delete()
                onProgress(100f, 0L, "Complete")
                return candidate
            }
            // Never leave a rejected container behind for MediaStoreExporter to find.
            if (candidate.exists()) candidate.delete()
        }

        throw IllegalStateException("Failed to merge video and audio streams")
    }

    private suspend fun cutSponsorSegments(
        context: Context,
        inputFile: File,
        sponsorJson: String,
        isCancelled: () -> Boolean,
        onProgress: suspend (Float, Long, String) -> Unit
    ): File {
        val segments = sponsorJson.split(";").mapNotNull { part ->
            val range = part.split("-")
            if (range.size == 2) {
                val s = range[0].toLongOrNull()
                val e = range[1].toLongOrNull()
                if (s != null && e != null && e > s) Pair(s, e) else null
            } else null
        }.sortedBy { it.first }

        if (segments.isEmpty() || isCancelled()) return inputFile

        onProgress(97f, 1L, "Removing sponsor content...")
        val ffmpegBin = findFFmpegExecutable(context) ?: return inputFile

        try {
            val parentDir = inputFile.parentFile ?: return inputFile
            val keepParts = mutableListOf<File>()
            var curStart = 0L

            for ((idx, seg) in segments.withIndex()) {
                if (seg.first > curStart) {
                    val partFile = File(parentDir, "keep_part_$idx.mp4")
                    if (partFile.exists()) partFile.delete()

                    val duration = seg.first - curStart
                    val cmd = listOf(
                        ffmpegBin.absolutePath,
                        "-y",
                        "-ss", curStart.toString(),
                        "-t", duration.toString(),
                        "-i", inputFile.absolutePath,
                        "-c", "copy",
                        partFile.absolutePath
                    )
                    val proc = ProcessBuilder(cmd).directory(parentDir).redirectErrorStream(true).start()
                    val code = runProcessSafely(proc, timeoutSeconds = 600L) { isCancelled() }
                    if (code == 0 && partFile.exists() && partFile.length() > 0) {
                        keepParts.add(partFile)
                    }
                }
                curStart = maxOf(curStart, seg.second)
            }

            // Final part after last sponsor segment
            val lastPartFile = File(parentDir, "keep_part_last.mp4")
            if (lastPartFile.exists()) lastPartFile.delete()
            val lastCmd = listOf(
                ffmpegBin.absolutePath,
                "-y",
                "-ss", curStart.toString(),
                "-i", inputFile.absolutePath,
                "-c", "copy",
                lastPartFile.absolutePath
            )
            val lastProc = ProcessBuilder(lastCmd).directory(parentDir).redirectErrorStream(true).start()
            val lastCode = runProcessSafely(lastProc, timeoutSeconds = 600L) { isCancelled() }
            if (lastCode == 0 && lastPartFile.exists() && lastPartFile.length() > 0) {
                keepParts.add(lastPartFile)
            }

            if (keepParts.isNotEmpty()) {
                val concatList = File(parentDir, "concat.txt")
                concatList.writeText(keepParts.joinToString("\n") { "file '${it.name}'" })

                val finalTrimmed = File(parentDir, "trimmed_output.mp4")
                if (finalTrimmed.exists()) finalTrimmed.delete()

                val concatCmd = listOf(
                    ffmpegBin.absolutePath,
                    "-y",
                    "-f", "concat",
                    "-safe", "0",
                    "-i", concatList.absolutePath,
                    "-c", "copy",
                    finalTrimmed.absolutePath
                )
                val concatProc = ProcessBuilder(concatCmd).directory(parentDir).redirectErrorStream(true).start()
                val concatCode = runProcessSafely(concatProc, timeoutSeconds = 600L) { isCancelled() }

                keepParts.forEach { it.delete() }
                concatList.delete()

                if (concatCode == 0 && finalTrimmed.exists() && finalTrimmed.length() > 0) {
                    inputFile.delete()
                    finalTrimmed.renameTo(inputFile)
                    Timber.i("DirectJvm: Successfully cut sponsor segments! Final size: %d", inputFile.length())
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "DirectJvm: Failed to trim sponsor segments, preserving original media.")
        }
        return inputFile
    }

    private fun runProcessSafely(
        proc: Process,
        timeoutSeconds: Long = 600L,
        isCancelled: () -> Boolean = { false }
    ): Int {
        val drainThread = kotlin.concurrent.thread(isDaemon = true, name = "ffmpeg-drain") {
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> Timber.v("FFmpeg: %s", line) }
                }
            } catch (_: Exception) {}
        }

        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutSeconds * 1000L) {
            if (isCancelled()) {
                proc.destroyForcibly()
                drainThread.interrupt()
                return -1
            }
            if (proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                drainThread.join(500)
                return proc.exitValue()
            }
        }

        proc.destroyForcibly()
        drainThread.interrupt()
        Timber.e("FFmpeg process timed out after %d seconds", timeoutSeconds)
        return -1
    }

    private fun findFFmpegExecutable(context: Context): File? {
        val candidates = listOf(
            File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/bin/ffmpeg"),
            File(context.filesDir, "youtubedl-android/packages/ffmpeg/usr/bin/ffmpeg"),
            File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        )
        return candidates.firstOrNull { it.exists() }
    }

    private fun runFFmpegMux(
        context: Context,
        videoFile: File,
        audioFile: File,
        outputFile: File,
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        return try {
            val ffmpegBin = findFFmpegExecutable(context)
                ?: throw IllegalStateException("FFmpeg executable not found")

            val cmd = listOf(
                ffmpegBin.absolutePath,
                "-y",
                "-i", videoFile.absolutePath,
                "-i", audioFile.absolutePath,
                "-c", "copy",
                outputFile.absolutePath
            )
            Timber.i("DirectJvm: Running FFmpeg via ProcessBuilder: %s", cmd.joinToString(" "))

            val process = ProcessBuilder(cmd)
                .directory(videoFile.parentFile)
                .redirectErrorStream(true)
                .start()

            val exitCode = runProcessSafely(process, timeoutSeconds = 600L) { isCancelled() }
            Timber.i("DirectJvm: FFmpeg exited with code %d", exitCode)
            outputFile.exists() && outputFile.length() > 0
        } catch (e: Exception) {
            Timber.w(e, "FFmpeg copy mux failed for %s", outputFile.name)
            false
        }
    }

    private suspend fun downloadStreamUrl(
        url: String,
        outputFile: File,
        label: String,
        startProgress: Float,
        endProgress: Float,
        isCancelled: () -> Boolean,
        onProgress: suspend (Float, Long, String) -> Unit
    ) {
        // Attempt 1: Check Content-Length via lightweight Range request
        var totalBytes = -1L
        try {
            val testReq = Request.Builder()
                .url(url)
                .header("User-Agent", NetworkConstants.activeUserAgent)
                .header("Range", "bytes=0-1")
                .build()

            probeClient.newCall(testReq).execute().use { response ->
                if (response.code == 206) {
                    val cr = response.header("Content-Range")
                    totalBytes = cr?.substringAfter('/')?.toLongOrNull() ?: -1L
                } else if (response.code == 200) {
                    totalBytes = response.body?.contentLength() ?: -1L
                }
            }
        } catch (e: Exception) {
            Timber.d(e, "Range probe failed, attempting direct stream...")
        }

        Timber.d("DirectJvm: Stream probed. totalBytes=%d", totalBytes)

        if (totalBytes > 0 && outputFile.exists() && outputFile.length() >= totalBytes) {
            Timber.i("DirectJvm: Stream %s already completely downloaded (%d bytes). Skipping fetch.", outputFile.name, outputFile.length())
            onProgress(endProgress, 0L, "Complete")
            return
        }

        // If Range chunking is viable, download in 2MB slices
        if (totalBytes > 0) {
            downloadWithChunks(
                url = url,
                totalBytes = totalBytes,
                outputFile = outputFile,
                label = label,
                startProgress = startProgress,
                endProgress = endProgress,
                isCancelled = isCancelled,
                onProgress = onProgress
            )
        } else {
            // Fallback: Stream directly with Range resume support
            downloadDirectStream(
                url = url,
                outputFile = outputFile,
                label = label,
                startProgress = startProgress,
                endProgress = endProgress,
                isCancelled = isCancelled,
                onProgress = onProgress
            )
        }
    }

    private suspend fun downloadWithChunks(
        url: String,
        totalBytes: Long,
        outputFile: File,
        label: String,
        startProgress: Float,
        endProgress: Float,
        isCancelled: () -> Boolean,
        onProgress: suspend (Float, Long, String) -> Unit
    ) {
        val client = App.sharedOkHttpClient
        val existingLength = if (outputFile.exists()) outputFile.length() else 0L
        if (totalBytes > 0 && existingLength >= totalBytes) {
            onProgress(endProgress, 0L, "Complete")
            return
        }
        var currentStart = if (existingLength in 1 until totalBytes) existingLength else 0L
        var downloadedBytes = currentStart
        val initialResumeOffset = currentStart
        val startTimeMs = System.currentTimeMillis()
        var lastEmitTimeMs = 0L

        // Immediately emit existing progress if resuming
        if (downloadedBytes > 0 && totalBytes > 0) {
            val initFraction = downloadedBytes.toFloat() / totalBytes
            val initP = startProgress + (initFraction * (endProgress - startProgress))
            onProgress(initP.coerceIn(startProgress, endProgress), -1L, String.format(Locale.US, "Resuming • %s", label))
        }

        RandomAccessFile(outputFile, "rw").use { raf ->
            while (currentStart < totalBytes && !isCancelled()) {
                val currentEnd = minOf(currentStart + CHUNK_SIZE - 1, totalBytes - 1)
                val chunkReq = Request.Builder()
                    .url(url)
                    .header("User-Agent", NetworkConstants.activeUserAgent)
                    .header("Range", "bytes=$currentStart-$currentEnd")
                    .build()

                var success = false
                var attempts = 0
                var lastEx: Exception? = null

                while (!success && attempts < 3 && !isCancelled()) {
                    attempts++
                    try {
                        client.newCall(chunkReq).execute().use { res ->
                            if (res.code != 206 && (currentStart > 0 || currentEnd < totalBytes - 1)) {
                                throw IllegalStateException("Expected HTTP 206 Partial Content for byte range $currentStart-$currentEnd, got HTTP ${res.code}")
                            } else if (!res.isSuccessful) {
                                throw IllegalStateException("HTTP ${res.code} downloading chunk bytes $currentStart-$currentEnd")
                            }

                            val body = res.body ?: throw IllegalStateException("Empty body for chunk")
                            val stream: InputStream = body.byteStream()
                            val buf = ByteArray(64 * 1024)
                            var bytesRead: Int
                            var chunkBytesRead = 0L

                            raf.seek(currentStart)
                            while (stream.read(buf).also { bytesRead = it } != -1) {
                                if (isCancelled()) throw InterruptedException("Download cancelled")
                                raf.write(buf, 0, bytesRead)
                                chunkBytesRead += bytesRead
                                downloadedBytes = currentStart + chunkBytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastEmitTimeMs >= 250L || downloadedBytes == totalBytes) {
                                    lastEmitTimeMs = now
                                    val elapsedSec = (now - startTimeMs).coerceAtLeast(1L) / 1000f
                                    val speedBytesPerSec = if (elapsedSec > 0) (downloadedBytes - initialResumeOffset) / elapsedSec else 0f
                                    val speedMb = speedBytesPerSec / (1024f * 1024f)
                                    val fraction = downloadedBytes.toFloat() / totalBytes
                                    val currentP = startProgress + (fraction * (endProgress - startProgress))
                                    val eta = if (speedBytesPerSec > 0) ((totalBytes - downloadedBytes) / speedBytesPerSec).toLong() else -1L

                                    val speedText = String.format(Locale.US, "%.1f MB/s • %s", speedMb, label)
                                    onProgress(currentP.coerceIn(startProgress, endProgress), eta, speedText)
                                }
                            }
                            success = true
                        }
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: Exception) {
                        lastEx = e
                        if (attempts < 3) {
                            delay(500L * attempts)
                        }
                    }
                }

                if (!success) {
                    throw lastEx ?: IllegalStateException("Failed to download chunk $currentStart-$currentEnd after 3 attempts")
                }

                currentStart = currentEnd + 1
            }
        }
    }

    private suspend fun downloadDirectStream(
        url: String,
        outputFile: File,
        label: String,
        startProgress: Float,
        endProgress: Float,
        isCancelled: () -> Boolean,
        onProgress: suspend (Float, Long, String) -> Unit
    ) {
        val client = App.sharedOkHttpClient
        val existingBytes = if (outputFile.exists()) outputFile.length() else 0L

        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", NetworkConstants.activeUserAgent)

        if (existingBytes > 0L) {
            reqBuilder.header("Range", "bytes=$existingBytes-")
        }

        client.newCall(reqBuilder.build()).execute().use { res ->
            if (!res.isSuccessful && res.code != 206) throw IllegalStateException("HTTP ${res.code} connecting to stream")
            val body = res.body ?: throw IllegalStateException("Empty body from stream")
            val isPartial = res.code == 206
            val totalBytes = if (isPartial) {
                val cr = res.header("Content-Range")
                cr?.substringAfterLast('/')?.toLongOrNull() ?: (existingBytes + (body.contentLength().takeIf { it > 0 } ?: 0L))
            } else {
                body.contentLength()
            }

            if (totalBytes > 0 && existingBytes >= totalBytes) {
                onProgress(endProgress, 0L, "Complete")
                return
            }

            var downloadedBytes = if (isPartial) existingBytes else 0L
            val initialResumeOffset = downloadedBytes
            val appendMode = isPartial && existingBytes > 0L

            val stream = body.byteStream()
            val buf = ByteArray(64 * 1024)
            val startTimeMs = System.currentTimeMillis()
            var lastEmitTimeMs = 0L

            FileOutputStream(outputFile, appendMode).use { fos ->
                var bytesRead: Int
                while (stream.read(buf).also { bytesRead = it } != -1) {
                    if (isCancelled()) throw InterruptedException("Download cancelled")
                    fos.write(buf, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastEmitTimeMs >= 250L) {
                        lastEmitTimeMs = now
                        val elapsedSec = (now - startTimeMs).coerceAtLeast(1L) / 1000f
                        val speedBytesPerSec = if (elapsedSec > 0) (downloadedBytes - initialResumeOffset) / elapsedSec else 0f
                        val speedMb = speedBytesPerSec / (1024f * 1024f)

                        val currentP = if (totalBytes > 0) {
                            startProgress + ((downloadedBytes.toFloat() / totalBytes) * (endProgress - startProgress))
                        } else {
                            startProgress + 5f
                        }
                        val eta = if (totalBytes > 0 && speedBytesPerSec > 0) ((totalBytes - downloadedBytes) / speedBytesPerSec).toLong() else -1L
                        val speedText = String.format(Locale.US, "%.1f MB/s • %s", speedMb, label)
                        onProgress(currentP.coerceIn(startProgress, endProgress), eta, speedText)
                    }
                }
            }
        }
    }
}

private fun VideoStream.effectiveHeight(): Int =
    if (width > 0 && height > 0) minOf(width, height) else height
