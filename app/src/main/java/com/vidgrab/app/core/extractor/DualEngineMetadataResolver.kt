package com.vidgrab.app.core.extractor

import com.vidgrab.app.core.webview.DownloadTriggerPayload
import com.vidgrab.app.core.webview.WebViewSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import timber.log.Timber

/**
 * Three-Tier Fast Metadata Resolution Pipeline:
 * - Tier 1: In-Memory WebView window.ytInitialPlayerResponse (<50ms).
 * - Tier 2: Warm JVM NewPipeExtractor (~300ms).
 * - Tier 3: Zero-Failure Smart Preset Fallback (ensures yt-dlp can still download with cookies/tokens).
 */
object DualEngineMetadataResolver {

    /**
     * Extracts YouTube 11-character video ID from watch, shorts, embed, or short URLs.
     */
    fun extractVideoId(url: String): String? {
        val regex = Regex("""(?:v=|/shorts/|/embed/|youtu\.be/)([a-zA-Z0-9_-]{11})""")
        return regex.find(url)?.groupValues?.get(1)
    }

    /**
     * Extracts YouTube Playlist ID (e.g., list=PL...) from query params.
     */
    fun extractPlaylistId(url: String): String? {
        val regex = Regex("""(?:list=)([a-zA-Z0-9_-]+)""")
        return regex.find(url)?.groupValues?.get(1)
    }

    suspend fun resolve(payload: DownloadTriggerPayload): ResolvedMediaInfo = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val targetPlaylistId = extractPlaylistId(payload.url)
        val targetVideoId = extractVideoId(payload.url)
        val effectiveItemType = if (targetPlaylistId != null && (payload.itemType == "PLAYLIST" || payload.url.contains("/playlist") || !payload.url.contains("/watch"))) "PLAYLIST" else payload.itemType

        // ---------------------------------------------------------------------
        // TIER 1: In-Memory WebView JSON (<50ms)
        // Strict guard: ONLY accept memoryJson if its videoId EXACTLY matches targetVideoId!
        // ---------------------------------------------------------------------
        val memoryJson = if (effectiveItemType == "VIDEO" && targetVideoId != null) {
            val candidate = payload.inlinePlayerJson ?: if (WebViewSessionManager.latestVideoId == targetVideoId) {
                WebViewSessionManager.latestPlayerResponseJson
            } else null

            if (!candidate.isNullOrBlank()) {
                try {
                    val root = JSONObject(candidate)
                    val candidateVid = root.optJSONObject("videoDetails")?.optString("videoId")
                    if (candidateVid == targetVideoId) candidate else null
                } catch (e: Exception) {
                    null
                }
            } else null
        } else null

        if (memoryJson != null && targetVideoId != null) {
            try {
                val tier1Result = parseTier1PlayerResponse(payload, memoryJson, targetVideoId, startTime)
                if (tier1Result != null && tier1Result.videoId == targetVideoId && tier1Result.videoOptions.isNotEmpty()) {
                    Timber.i("DualEngine: Resolved %s via Tier 1 In-Memory in %d ms", targetVideoId, System.currentTimeMillis() - startTime)
                    return@withContext tier1Result
                }
            } catch (e: Exception) {
                Timber.w(e, "Tier 1 memory parsing failed for %s, falling back to Tier 2", targetVideoId)
            }
        }

        // ---------------------------------------------------------------------
        // TIER 2: Warm JVM NewPipeExtractor (~300ms)
        // ---------------------------------------------------------------------
        try {
            when (effectiveItemType) {
                "PLAYLIST" -> {
                    val canonicalPlaylistUrl = if (targetPlaylistId != null) {
                        "https://www.youtube.com/playlist?list=$targetPlaylistId"
                    } else payload.url

                    var previewItems = emptyList<BatchPlaylistItem>()
                    var playlistTitle = payload.title
                    var playlistAuthor = "YouTube"
                    var playlistThumb: String? = null
                    var totalCount = 0

                    try {
                        val playlistInfo = PlaylistInfo.getInfo(ServiceList.YouTube, canonicalPlaylistUrl)
                        playlistTitle = playlistInfo.name.orEmpty().ifBlank { payload.title }
                        playlistAuthor = playlistInfo.uploaderName.orEmpty()
                        playlistThumb = playlistInfo.thumbnails.firstOrNull()?.url
                        totalCount = playlistInfo.streamCount.toInt()

                        previewItems = playlistInfo.relatedItems.map { item ->
                            val cleanUrl = when {
                                item.url.isNullOrBlank() -> ""
                                item.url.startsWith("http://") || item.url.startsWith("https://") -> item.url
                                item.url.startsWith("/") -> "https://www.youtube.com${item.url}"
                                else -> "https://www.youtube.com/watch?v=${item.url}"
                            }
                            BatchPlaylistItem(
                                url = cleanUrl,
                                title = item.name.orEmpty().ifBlank { "YouTube Video" },
                                author = item.uploaderName.orEmpty(),
                                durationSeconds = item.duration,
                                thumbnailUrl = item.thumbnails.firstOrNull()?.url,
                                isSelected = true
                            )
                        }.filter { it.url.isNotBlank() }
                    } catch (e: Exception) {
                        Timber.w(e, "NewPipe Playlist resolution failed for %s; attempting yt-dlp flat-playlist extraction", canonicalPlaylistUrl)
                    }

                    // Fallback to yt-dlp --flat-playlist -J if NewPipe produced 0 items
                    if (previewItems.isEmpty()) {
                        try {
                            val request = com.yausername.youtubedl_android.YoutubeDLRequest(canonicalPlaylistUrl)
                                .addOption("--flat-playlist")
                                .addOption("-J")
                            val response = com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(request)
                            val root = JSONObject(response.out)
                            playlistTitle = root.optString("title", playlistTitle)
                            playlistAuthor = root.optString("uploader", playlistAuthor)
                            val entries = root.optJSONArray("entries")
                            if (entries != null) {
                                val list = mutableListOf<BatchPlaylistItem>()
                                for (i in 0 until entries.length()) {
                                    val entry = entries.optJSONObject(i) ?: continue
                                    val id = entry.optString("id")
                                    val title = entry.optString("title", "Video #${i + 1}")
                                    val duration = entry.optLong("duration", 0L)
                                    val uploader = entry.optString("uploader", playlistAuthor)
                                    val itemThumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                                    list.add(
                                        BatchPlaylistItem(
                                            url = "https://www.youtube.com/watch?v=$id",
                                            title = title,
                                            author = uploader,
                                            durationSeconds = duration,
                                            thumbnailUrl = itemThumb,
                                            isSelected = true
                                        )
                                    )
                                }
                                previewItems = list
                                totalCount = list.size
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "yt-dlp flat-playlist extraction also failed for %s", canonicalPlaylistUrl)
                        }
                    }

                    val (presets, audioPresets) = FormatSelectorBuilder.buildStandardPresets()
                    val elapsed = System.currentTimeMillis() - startTime
                    Timber.i("DualEngine: Resolved Playlist in %d ms (%d items)", elapsed, previewItems.size)

                    return@withContext ResolvedMediaInfo.PlaylistOrChannel(
                        id = targetPlaylistId ?: canonicalPlaylistUrl,
                        url = canonicalPlaylistUrl,
                        title = playlistTitle,
                        author = playlistAuthor,
                        thumbnailUrl = playlistThumb,
                        isPlaylist = true,
                        totalItemCount = totalCount.coerceAtLeast(previewItems.size),
                        items = previewItems,
                        videoOptions = presets,
                        audioOptions = audioPresets,
                        source = ResolutionSource.TIER_2_NEWPIPE_JVM,
                        resolutionTimeMs = elapsed
                    )
                }

                "CHANNEL" -> {
                    val channelInfo = ChannelInfo.getInfo(ServiceList.YouTube, payload.url)
                    val (presets, audioPresets) = FormatSelectorBuilder.buildStandardPresets()
                    val elapsed = System.currentTimeMillis() - startTime
                    Timber.i("DualEngine: Resolved Channel via Tier 2 in %d ms", elapsed)

                    return@withContext ResolvedMediaInfo.PlaylistOrChannel(
                        id = channelInfo.id.orEmpty(),
                        url = payload.url,
                        title = channelInfo.name.orEmpty().ifBlank { payload.title },
                        author = channelInfo.name.orEmpty(),
                        thumbnailUrl = channelInfo.avatars.firstOrNull()?.url,
                        isPlaylist = false,
                        totalItemCount = 0,
                        items = emptyList(),
                        videoOptions = presets,
                        audioOptions = audioPresets,
                        source = ResolutionSource.TIER_2_NEWPIPE_JVM,
                        resolutionTimeMs = elapsed
                    )
                }

                else -> {
                    // Standard Video
                    val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, payload.url)
                    val durationSec = streamInfo.duration.coerceAtLeast(1L)

                    // Best Audio Stream for combined file size calculation
                    val bestAudio = streamInfo.audioStreams.maxByOrNull { it.bitrate }
                    val bestAudioBytes = bestAudio?.let { audio ->
                        (audio.bitrate.toLong() * durationSec) / 8L
                    } ?: ((128L * 1000L / 8L) * durationSec)

                    // Combine progressive and adaptive video-only streams
                    val allVideoStreams = streamInfo.videoOnlyStreams + streamInfo.videoStreams

                    // Group Video Streams by effective resolution (handling 9:16 vertical Shorts 1080x1920 as 1080p), preferring H.264
                    val groupedVideos = allVideoStreams
                        .filter { it.height > 0 }
                        .groupBy { vs ->
                            if (vs.width > 0 && vs.height > 0) minOf(vs.width, vs.height) else vs.height
                        }
                        .mapValues { (_, streams) ->
                            streams.firstOrNull { it.codec?.contains("avc", ignoreCase = true) == true } ?: streams.first()
                        }

                    val videoOptions = groupedVideos.entries
                        .sortedByDescending { it.key }
                        .map { (resHeight, vs) ->
                            val rawVideoBytes = (vs.bitrate.toLong() * durationSec) / 8L
                            val combinedBytes = rawVideoBytes + bestAudioBytes

                            val label = when (resHeight) {
                                2160 -> "4K Ultra HD"
                                1440 -> "2K Quad HD"
                                1080 -> "1080p Full HD"
                                720 -> "720p HD"
                                480 -> "480p Standard"
                                360 -> "360p Medium"
                                240 -> "240p Compact"
                                else -> "${resHeight}p"
                            }

                            val codecBadge = if (vs.codec?.contains("avc", ignoreCase = true) == true) "H.264" else "VP9"

                            VideoQualityOption(
                                height = resHeight,
                                label = label,
                                fps = vs.fps,
                                vcodec = codecBadge,
                                container = "mp4",
                                estimatedTotalBytes = combinedBytes,
                                formatSelector = FormatSelectorBuilder.buildVideoSelector(resHeight)
                            )
                        }

                    val audioOptions = streamInfo.audioStreams.map { a ->
                        val aBytes = (a.bitrate.toLong() * durationSec) / 8L
                        val ext = a.format?.suffix ?: "m4a"
                        val isOpus = a.codec?.contains("opus", ignoreCase = true) == true
                        val codecName = if (isOpus) "Opus" else "AAC"
                        val bitrateKbps = (a.bitrate / 1000).coerceAtLeast(48)
                        val label = "$bitrateKbps kbps $codecName (${ext.uppercase()})"

                        AudioQualityOption(
                            label = label,
                            extension = ext,
                            acodec = codecName,
                            bitrateKbps = bitrateKbps,
                            estimatedBytes = aBytes,
                            formatSelector = FormatSelectorBuilder.buildAudioSelector(ext)
                        )
                    }.distinctBy { "${it.extension}_${it.bitrateKbps}" }

                    val elapsed = System.currentTimeMillis() - startTime
                    Timber.i("DualEngine: Resolved Video via Tier 2 in %d ms (%d video, %d audio formats)", elapsed, videoOptions.size, audioOptions.size)

                    return@withContext ResolvedMediaInfo.SingleVideo(
                        videoId = streamInfo.id.orEmpty(),
                        url = payload.url,
                        title = streamInfo.name.orEmpty().ifBlank { payload.title },
                        author = streamInfo.uploaderName.orEmpty(),
                        durationSeconds = durationSec,
                        thumbnailUrl = streamInfo.thumbnails.firstOrNull()?.url,
                        videoOptions = videoOptions.ifEmpty { FormatSelectorBuilder.buildStandardPresets(durationSec).first },
                        audioOptions = audioOptions.ifEmpty { FormatSelectorBuilder.buildStandardPresets(durationSec).second },
                        source = ResolutionSource.TIER_2_NEWPIPE_JVM,
                        resolutionTimeMs = elapsed
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Tier 2 NewPipe resolution failed (age-gate / network / challenge), falling back to Tier 3 Smart Presets")
        }

        // ---------------------------------------------------------------------
        // TIER 3: Zero-Failure Smart Preset Fallback
        // ---------------------------------------------------------------------
        val (fallbackVideos, fallbackAudios) = FormatSelectorBuilder.buildStandardPresets()
        val elapsed = System.currentTimeMillis() - startTime
        Timber.w("DualEngine: Activated Tier 3 Smart Presets for %s in %d ms", payload.url, elapsed)

        return@withContext ResolvedMediaInfo.FallbackPreset(
            url = payload.url,
            title = payload.title.ifBlank { "YouTube Media" },
            itemType = payload.itemType,
            videoOptions = fallbackVideos,
            audioOptions = fallbackAudios,
            source = ResolutionSource.TIER_3_SMART_PRESET,
            resolutionTimeMs = elapsed
        )
    }

    private fun parseTier1PlayerResponse(
        payload: DownloadTriggerPayload,
        jsonStr: String,
        targetVideoId: String,
        startTime: Long
    ): ResolvedMediaInfo.SingleVideo? {
        val root = JSONObject(jsonStr)
        val videoDetails = root.optJSONObject("videoDetails") ?: return null
        val streamingData = root.optJSONObject("streamingData") ?: return null

        val videoId = videoDetails.optString("videoId")
        if (videoId != targetVideoId) {
            Timber.w("DualEngine: Tier 1 JSON videoId '%s' does not match target '%s'", videoId, targetVideoId)
            return null
        }
        val title = videoDetails.optString("title").ifBlank { payload.title }
        val author = videoDetails.optString("author")
        val durationSec = videoDetails.optLong("lengthSeconds", 240L).coerceAtLeast(1L)
        val thumbnails = videoDetails.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumbnailUrl = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url")

        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: return null

        // Best Audio Stream
        var bestAudioBytes: Long = 0L
        val audioList = mutableListOf<AudioQualityOption>()

        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.getJSONObject(i)
            val mimeType = format.optString("mimeType")
            if (mimeType.startsWith("audio/")) {
                val contentLength = format.optLong("contentLength", 0L)
                val bitrate = format.optLong("bitrate", 128000L)
                val size = if (contentLength > 0L) contentLength else (bitrate * durationSec) / 8L
                if (size > bestAudioBytes) bestAudioBytes = size

                val isOpus = mimeType.contains("opus")
                val ext = if (isOpus) "opus" else "m4a"
                val codec = if (isOpus) "Opus" else "AAC"
                val bitrateKbps = (bitrate / 1000L).toInt()

                audioList.add(
                    AudioQualityOption(
                        label = "$bitrateKbps kbps $codec (${ext.uppercase()})",
                        extension = ext,
                        acodec = codec,
                        bitrateKbps = bitrateKbps,
                        estimatedBytes = size,
                        formatSelector = FormatSelectorBuilder.buildAudioSelector(ext)
                    )
                )
            }
        }
        if (bestAudioBytes == 0L) bestAudioBytes = (128L * 1000L / 8L) * durationSec

        // Video streams
        val videoMap = mutableMapOf<Int, VideoQualityOption>()

        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.getJSONObject(i)
            val mimeType = format.optString("mimeType")
            if (mimeType.startsWith("video/")) {
                val height = format.optInt("height", 0)
                if (height > 0) {
                    val fps = format.optInt("fps", 30)
                    val contentLength = format.optLong("contentLength", 0L)
                    val bitrate = format.optLong("bitrate", 1500000L)
                    val rawVideoSize = if (contentLength > 0L) contentLength else (bitrate * durationSec) / 8L
                    val totalSize = rawVideoSize + bestAudioBytes

                    val isAvc = mimeType.contains("avc1")
                    val codecBadge = if (isAvc) "H.264" else "VP9"

                    val label = when (height) {
                        2160 -> "4K Ultra HD"
                        1440 -> "2K Quad HD"
                        1080 -> "1080p Full HD"
                        720 -> "720p HD"
                        480 -> "480p Standard"
                        360 -> "360p Medium"
                        240 -> "240p Compact"
                        144 -> "144p Data Saver"
                        else -> "${height}p"
                    }

                    // Prefer H.264 if we already have this resolution recorded
                    val existing = videoMap[height]
                    if (existing == null || (isAvc && existing.vcodec != "H.264")) {
                        videoMap[height] = VideoQualityOption(
                            height = height,
                            label = label,
                            fps = fps,
                            vcodec = codecBadge,
                            container = "mp4",
                            estimatedTotalBytes = totalSize,
                            formatSelector = FormatSelectorBuilder.buildVideoSelector(height)
                        )
                    }
                }
            }
        }

        val videoOptions = videoMap.values.sortedByDescending { it.height }
        val audioOptions = audioList.distinctBy { "${it.extension}_${it.bitrateKbps}" }

        if (videoOptions.isEmpty()) return null

        return ResolvedMediaInfo.SingleVideo(
            videoId = videoId,
            url = payload.url,
            title = title,
            author = author,
            durationSeconds = durationSec,
            thumbnailUrl = thumbnailUrl,
            videoOptions = videoOptions,
            audioOptions = audioOptions,
            source = ResolutionSource.TIER_1_WEBVIEW_MEMORY,
            resolutionTimeMs = System.currentTimeMillis() - startTime
        )
    }
}
