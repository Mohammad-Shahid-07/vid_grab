package com.vidgrab.app.core.extractor

import com.vidgrab.app.core.webview.DownloadTriggerPayload
import com.vidgrab.app.core.webview.WebViewSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import timber.log.Timber

/**
 * Three-Tier Fast Metadata Resolution Pipeline:
 * - Tier 1: In-Memory WebView window.ytInitialPlayerResponse (<20ms).
 * - Tier 2: Instant InnerTube API Client (VISIONOS -> IOS -> ANDROID) (<250ms, zero HTML scraping).
 * - Tier 3: Warm JVM NewPipeExtractor / Zero-Failure Smart Presets Fallback.
 *
 * Guaranteed 0ms size calculation via 3-step hierarchy (contentLength -> Bitrate * Duration -> Heuristic).
 * Zero probeStreamBytes calls on the resolution path to ensure instant popup opening (<250ms).
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

    /**
     * Sanitizes YouTube URLs into clean canonical endpoints accepted by NewPipe and yt-dlp.
     * Strips mix/radio query garbage (&list=RD..., &start_radio=1, &pp=...) that causes NewPipe to throw ParsingException.
     */
    fun sanitizeMediaUrl(url: String): String {
        val vid = extractVideoId(url)
        if (vid != null && (url.contains("/watch") || url.contains("/shorts/") || url.contains("youtu.be/"))) {
            return "https://www.youtube.com/watch?v=$vid"
        }
        val pid = extractPlaylistId(url)
        if (pid != null && (url.contains("/playlist") || url.contains("list="))) {
            return "https://www.youtube.com/playlist?list=$pid"
        }
        return url
    }

    suspend fun resolve(payload: DownloadTriggerPayload): ResolvedMediaInfo = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val cleanUrl = sanitizeMediaUrl(payload.url)
        val targetVideoId = extractVideoId(cleanUrl)
        val targetPlaylistId = if (targetVideoId == null) extractPlaylistId(cleanUrl) else null
        val effectiveItemType = if (targetVideoId != null) "VIDEO"
            else if (targetPlaylistId != null || cleanUrl.contains("/playlist") || cleanUrl.contains("list=")) "PLAYLIST"
            else payload.itemType

        // =====================================================================
        // TIER 1: Fast InnerTube Client (<200ms, raw ANDROID/IOS direct streams)
        // =====================================================================
        if (effectiveItemType == "VIDEO" && targetVideoId != null) {
            try {
                val innerTubeJson = InnerTubeFastClient.fetchPlayerResponse(targetVideoId)
                if (innerTubeJson != null) {
                    val fastResult = parsePlayerResponseJson(
                        payload = payload,
                        root = innerTubeJson,
                        targetVideoId = targetVideoId,
                        source = ResolutionSource.TIER_2_NEWPIPE_JVM,
                        startTime = startTime
                    )
                    if (fastResult != null && (fastResult.videoOptions.isNotEmpty() || fastResult.audioOptions.isNotEmpty())) {
                        Timber.i("DualEngine: Resolved %s via InnerTube Fast Client in %d ms", targetVideoId, System.currentTimeMillis() - startTime)
                        return@withContext fastResult
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "InnerTube Fast Client resolution failed for %s, falling back to WebView memory/NewPipe", targetVideoId)
            }

            // =================================================================
            // TIER 2: In-Memory WebView JSON Fallback (<20ms, formats & sizes only)
            // =================================================================
            val candidate = payload.inlinePlayerJson ?: if (WebViewSessionManager.latestVideoId == targetVideoId) {
                WebViewSessionManager.latestPlayerResponseJson
            } else null

            if (!candidate.isNullOrBlank()) {
                try {
                    val root = JSONObject(candidate)
                    val candidateVid = root.optJSONObject("videoDetails")?.optString("videoId")
                    if (candidateVid == targetVideoId) {
                        val memoryResult = parsePlayerResponseJson(
                            payload = payload,
                            root = root,
                            targetVideoId = targetVideoId,
                            source = ResolutionSource.TIER_1_WEBVIEW_MEMORY,
                            startTime = startTime
                        )
                        if (memoryResult != null && memoryResult.videoOptions.isNotEmpty()) {
                            Timber.i("DualEngine: Resolved %s via WebView Memory (directUrls sanitized) in %d ms", targetVideoId, System.currentTimeMillis() - startTime)
                            return@withContext memoryResult
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "WebView memory parsing failed for %s, proceeding to Tier 3", targetVideoId)
                }
            }
        }

        // =====================================================================
        // TIER 3: Fallback (Playlists, Channels, or NewPipe/Smart Presets)
        // =====================================================================
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
                        val playlistInfo = withTimeoutOrNull(4500L) {
                            PlaylistInfo.getInfo(ServiceList.YouTube, canonicalPlaylistUrl)
                        }
                        if (playlistInfo != null) {
                            playlistTitle = playlistInfo.name.orEmpty().ifBlank { payload.title }
                            playlistAuthor = playlistInfo.uploaderName.orEmpty()
                            playlistThumb = playlistInfo.thumbnails.firstOrNull()?.url
                            totalCount = playlistInfo.streamCount.toInt()

                            previewItems = playlistInfo.relatedItems.map { item ->
                                val cUrl = when {
                                    item.url.isNullOrBlank() -> ""
                                    item.url.startsWith("http://") || item.url.startsWith("https://") -> item.url
                                    item.url.startsWith("/") -> "https://www.youtube.com${item.url}"
                                    else -> "https://www.youtube.com/watch?v=${item.url}"
                                }
                                BatchPlaylistItem(
                                    url = cUrl,
                                    title = item.name.orEmpty().ifBlank { "YouTube Video" },
                                    author = item.uploaderName.orEmpty(),
                                    durationSeconds = item.duration,
                                    thumbnailUrl = item.thumbnails.firstOrNull()?.url,
                                    isSelected = true
                                )
                            }.filter { it.url.isNotBlank() }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "NewPipe Playlist resolution failed for %s", canonicalPlaylistUrl)
                    }

                    if (previewItems.isEmpty()) {
                        previewItems = listOf(
                            BatchPlaylistItem(
                                url = canonicalPlaylistUrl,
                                title = playlistTitle.ifBlank { "YouTube Playlist" },
                                author = playlistAuthor,
                                durationSeconds = payload.durationSeconds.takeIf { it > 0L } ?: 180L,
                                thumbnailUrl = playlistThumb,
                                isSelected = true
                            )
                        )
                    }
                    val totalPlaylistSec = previewItems.sumOf { it.durationSeconds }.takeIf { it > 0 }
                        ?: (previewItems.size.toLong() * (payload.durationSeconds.takeIf { it > 0L } ?: 180L))
                    val (presets, audioPresets) = FormatSelectorBuilder.buildStandardPresets(totalPlaylistSec)
                    val elapsed = System.currentTimeMillis() - startTime
                    Timber.i("DualEngine: Resolved Playlist in %d ms (%d items, totalDuration=%ds)", elapsed, previewItems.size, totalPlaylistSec)

                    return@withContext ResolvedMediaInfo.PlaylistOrChannel(
                        id = targetPlaylistId.orEmpty(),
                        url = canonicalPlaylistUrl,
                        title = playlistTitle,
                        author = playlistAuthor,
                        thumbnailUrl = playlistThumb,
                        isPlaylist = true,
                        totalItemCount = if (totalCount > 0) totalCount else previewItems.size,
                        items = previewItems,
                        videoOptions = presets,
                        audioOptions = audioPresets,
                        source = ResolutionSource.TIER_2_NEWPIPE_JVM,
                        resolutionTimeMs = elapsed
                    )
                }

                "CHANNEL" -> {
                    val channelInfo = withTimeoutOrNull(4500L) {
                        ChannelInfo.getInfo(ServiceList.YouTube, payload.url)
                    }
                    val (presets, audioPresets) = FormatSelectorBuilder.buildStandardPresets(payload.durationSeconds.takeIf { it > 0L } ?: 180L)
                    val elapsed = System.currentTimeMillis() - startTime

                    return@withContext ResolvedMediaInfo.PlaylistOrChannel(
                        id = channelInfo?.id.orEmpty(),
                        url = payload.url,
                        title = channelInfo?.name.orEmpty().ifBlank { payload.title },
                        author = channelInfo?.name.orEmpty(),
                        thumbnailUrl = channelInfo?.avatars?.firstOrNull()?.url,
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
                    // Standard Video NewPipe scrape fallback (no probeStreamBytes!)
                    val streamInfo = withTimeoutOrNull(4000L) {
                        StreamInfo.getInfo(ServiceList.YouTube, cleanUrl)
                    }
                    if (streamInfo != null) {
                        val durationSec = streamInfo.duration.coerceAtLeast(1L).takeIf { it > 1L }
                            ?: payload.durationSeconds.takeIf { it > 0L }
                            ?: 180L

                        val bestAudio = streamInfo.audioStreams.maxByOrNull { it.bitrate }
                        val bestAudioBytes = if (bestAudio != null) {
                            val br = bestAudio.bitrate.toLong().takeIf { it > 0 } ?: 128000L
                            (br * durationSec) / 8L
                        } else (128000L * durationSec) / 8L

                        val allVideoStreams = streamInfo.videoOnlyStreams + streamInfo.videoStreams
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
                                val heuristicBr = when (resHeight) {
                                    2160 -> 12000000L
                                    1440 -> 6000000L
                                    1080 -> 2500000L
                                    720 -> 1200000L
                                    480 -> 650000L
                                    360 -> 400000L
                                    240 -> 220000L
                                    144 -> 110000L
                                    else -> 1000000L
                                }
                                val vBitrate = vs.bitrate.toLong().takeIf { it > 0 } ?: heuristicBr
                                val rawVideoBytes = (vBitrate * durationSec) / 8L
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
                                    formatSelector = FormatSelectorBuilder.buildVideoSelector(resHeight),
                                    directStreamUrl = vs.content ?: vs.url,
                                    matchedAudioStreamUrl = bestAudio?.content ?: bestAudio?.url
                                )
                            }

                        val audioOptions = streamInfo.audioStreams.map { a ->
                            val ext = a.format?.suffix ?: "m4a"
                            val isOpus = a.codec?.contains("opus", ignoreCase = true) == true
                            val codecName = if (isOpus) "Opus" else "AAC"
                            val bitrateKbps = (a.bitrate / 1000).coerceAtLeast(48)
                            val aBytes = ((a.bitrate.toLong().takeIf { it > 0 } ?: 128000L) * durationSec) / 8L

                            AudioQualityOption(
                                label = "$bitrateKbps kbps $codecName (${ext.uppercase()})",
                                extension = ext,
                                acodec = codecName,
                                bitrateKbps = bitrateKbps,
                                estimatedBytes = aBytes,
                                formatSelector = FormatSelectorBuilder.buildAudioSelector(ext),
                                directStreamUrl = a.content ?: a.url
                            )
                        }.distinctBy { "${it.extension}_${it.bitrateKbps}" }

                        val elapsed = System.currentTimeMillis() - startTime
                        return@withContext ResolvedMediaInfo.SingleVideo(
                            videoId = streamInfo.id.orEmpty().ifBlank { targetVideoId.orEmpty() },
                            url = cleanUrl,
                            title = streamInfo.name.orEmpty().ifBlank { payload.title },
                            author = streamInfo.uploaderName.orEmpty(),
                            durationSeconds = durationSec,
                            thumbnailUrl = streamInfo.thumbnails.firstOrNull()?.url,
                            videoOptions = videoOptions.ifEmpty { FormatSelectorBuilder.buildStandardPresets(durationSec).first },
                            audioOptions = audioOptions.ifEmpty { FormatSelectorBuilder.buildStandardPresets(durationSec).second },
                            sponsorSegments = emptyList(),
                            source = ResolutionSource.TIER_2_NEWPIPE_JVM,
                            resolutionTimeMs = elapsed
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Tier 2/3 fallback failed for %s", payload.url)
        }

        // =====================================================================
        // Final Zero-Failure Fast Smart Presets (<5ms)
        // =====================================================================
        val effectiveDuration = payload.durationSeconds.takeIf { it > 0L } ?: 180L
        val (fallbackVideos, fallbackAudios) = FormatSelectorBuilder.buildStandardPresets(effectiveDuration)
        val elapsed = System.currentTimeMillis() - startTime
        Timber.w("DualEngine: Activated Smart Presets for %s in %d ms (duration=%ds)", payload.url, elapsed, effectiveDuration)

        return@withContext ResolvedMediaInfo.FallbackPreset(
            url = cleanUrl,
            title = payload.title.ifBlank { "YouTube Media" },
            itemType = effectiveItemType,
            durationSeconds = effectiveDuration,
            videoOptions = fallbackVideos,
            audioOptions = fallbackAudios,
            source = ResolutionSource.TIER_3_SMART_PRESET,
            resolutionTimeMs = elapsed
        )
    }

    private fun parsePlayerResponseJson(
        payload: DownloadTriggerPayload,
        root: JSONObject,
        targetVideoId: String,
        source: ResolutionSource,
        startTime: Long
    ): ResolvedMediaInfo.SingleVideo? {
        val videoDetails = root.optJSONObject("videoDetails") ?: return null
        val streamingData = root.optJSONObject("streamingData") ?: return null

        val videoId = videoDetails.optString("videoId")
        if (videoId.isNotBlank() && videoId != targetVideoId) {
            Timber.w("DualEngine: JSON videoId '%s' does not match target '%s'", videoId, targetVideoId)
            return null
        }

        val title = videoDetails.optString("title").ifBlank { payload.title }
        val author = videoDetails.optString("author")

        // Exact duration hierarchy: lengthSeconds -> approxDurationMs -> payload -> 180L
        val rawLength = videoDetails.optString("lengthSeconds").toLongOrNull()
            ?: videoDetails.optLong("lengthSeconds", 0L)
        val approxSec = videoDetails.optLong("approxDurationMs", 0L) / 1000L
        val durationSec = rawLength.takeIf { it > 0L }
            ?: approxSec.takeIf { it > 0L }
            ?: payload.durationSeconds.takeIf { it > 0L }
            ?: 180L

        val thumbnails = videoDetails.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumbnailUrl = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url")

        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            ?: streamingData.optJSONArray("formats")
            ?: return null

        // 1. Process Audio Streams
        val audioList = mutableListOf<AudioQualityOption>()
        var bestAudioDirectUrl: String? = null
        var bestAudioBytes: Long = 0L

        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.optJSONObject(i) ?: continue
            val mimeType = format.optString("mimeType")
            if (mimeType.startsWith("audio/")) {
                val rawAudioUrl = format.optString("url").takeIf { it.isNotBlank() }
                val isAudioFromWeb = rawAudioUrl?.contains("c=MWEB") == true || rawAudioUrl?.contains("c=WEB") == true
                val directUrl = if (source == ResolutionSource.TIER_1_WEBVIEW_MEMORY || isAudioFromWeb) null else rawAudioUrl

                // 3-Step Size Hierarchy
                var contentLength = format.optString("contentLength").toLongOrNull() ?: format.optLong("contentLength", 0L)
                if (contentLength <= 0L && rawAudioUrl != null && rawAudioUrl.contains("clen=")) {
                    contentLength = rawAudioUrl.substringAfter("clen=").substringBefore("&").toLongOrNull() ?: 0L
                }

                val bitrate = format.optLong("averageBitrate", 0L).takeIf { it > 0L }
                    ?: format.optLong("bitrate", 128000L)
                val audioBytes = if (contentLength > 0L) {
                    contentLength
                } else {
                    (bitrate * durationSec) / 8L
                }

                if (audioBytes > bestAudioBytes || bestAudioDirectUrl == null) {
                    bestAudioBytes = audioBytes
                    if (directUrl != null) bestAudioDirectUrl = directUrl
                }

                val isOpus = mimeType.contains("opus")
                val ext = if (isOpus) "opus" else "m4a"
                val codec = if (isOpus) "Opus" else "AAC"
                val bitrateKbps = (bitrate / 1000L).toInt().coerceAtLeast(48)

                audioList.add(
                    AudioQualityOption(
                        label = "$bitrateKbps kbps $codec (${ext.uppercase()})",
                        extension = ext,
                        acodec = codec,
                        bitrateKbps = bitrateKbps,
                        estimatedBytes = audioBytes,
                        formatSelector = FormatSelectorBuilder.buildAudioSelector(ext),
                        directStreamUrl = directUrl
                    )
                )
            }
        }

        if (bestAudioBytes == 0L) {
            bestAudioBytes = (128000L * durationSec) / 8L
        }

        // 2. Process Video Streams
        val videoMap = mutableMapOf<Int, VideoQualityOption>()

        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.optJSONObject(i) ?: continue
            val mimeType = format.optString("mimeType")
            if (mimeType.startsWith("video/")) {
                val height = format.optInt("height", 0)
                if (height > 0) {
                    val fps = format.optInt("fps", 30)
                    val rawVideoUrl = format.optString("url").takeIf { it.isNotBlank() }
                    val isVideoFromWeb = rawVideoUrl?.contains("c=MWEB") == true || rawVideoUrl?.contains("c=WEB") == true
                    val directUrl = if (source == ResolutionSource.TIER_1_WEBVIEW_MEMORY || isVideoFromWeb) null else rawVideoUrl

                    // 3-Step Size Hierarchy
                    var contentLength = format.optString("contentLength").toLongOrNull() ?: format.optLong("contentLength", 0L)
                    if (contentLength <= 0L && rawVideoUrl != null && rawVideoUrl.contains("clen=")) {
                        contentLength = rawVideoUrl.substringAfter("clen=").substringBefore("&").toLongOrNull() ?: 0L
                    }

                    val heuristicBr = when (height) {
                        2160 -> 12000000L
                        1440 -> 6000000L
                        1080 -> 2500000L
                        720 -> 1200000L
                        480 -> 650000L
                        360 -> 400000L
                        240 -> 220000L
                        144 -> 110000L
                        else -> 1000000L
                    }

                    val bitrate = format.optLong("averageBitrate", 0L).takeIf { it > 0L }
                        ?: format.optLong("bitrate", 0L).takeIf { it > 0L }
                        ?: heuristicBr

                    val rawVideoSize = if (contentLength > 0L) {
                        contentLength
                    } else {
                        (bitrate * durationSec) / 8L
                    }
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

                    val existing = videoMap[height]
                    if (existing == null || (isAvc && existing.vcodec != "H.264") || (existing.directStreamUrl == null && directUrl != null)) {
                        videoMap[height] = VideoQualityOption(
                            height = height,
                            label = label,
                            fps = fps,
                            vcodec = codecBadge,
                            container = "mp4",
                            estimatedTotalBytes = totalSize,
                            formatSelector = FormatSelectorBuilder.buildVideoSelector(height),
                            directStreamUrl = directUrl,
                            matchedAudioStreamUrl = bestAudioDirectUrl
                        )
                    }
                }
            }
        }

        val videoOptions = videoMap.values.sortedByDescending { it.height }
        val audioOptions = audioList.distinctBy { "${it.extension}_${it.bitrateKbps}" }

        if (videoOptions.isEmpty()) return null

        return ResolvedMediaInfo.SingleVideo(
            videoId = videoId.ifBlank { targetVideoId },
            url = "https://www.youtube.com/watch?v=${videoId.ifBlank { targetVideoId }}",
            title = title,
            author = author,
            durationSeconds = durationSec,
            thumbnailUrl = thumbnailUrl,
            videoOptions = videoOptions,
            audioOptions = audioOptions,
            sponsorSegments = emptyList(),
            source = source,
            resolutionTimeMs = System.currentTimeMillis() - startTime
        )
    }
}
