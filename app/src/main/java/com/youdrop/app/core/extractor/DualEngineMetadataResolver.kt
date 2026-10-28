package com.youdrop.app.core.extractor

import com.youdrop.app.core.webview.DownloadTriggerPayload
import com.youdrop.app.core.webview.WebViewSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.AudioTrackType
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
        val targetPlaylistId = extractPlaylistId(payload.url)
        val cleanUrl = if (payload.itemType == "PLAYLIST" && targetPlaylistId != null) {
            "https://www.youtube.com/playlist?list=$targetPlaylistId"
        } else {
            sanitizeMediaUrl(payload.url)
        }
        val targetVideoId = if (payload.itemType == "PLAYLIST") null else extractVideoId(cleanUrl)
        val effectiveItemType = when {
            payload.itemType == "PLAYLIST" -> "PLAYLIST"
            payload.itemType == "CHANNEL" -> "CHANNEL"
            payload.itemType == "VIDEO" -> "VIDEO"
            targetPlaylistId != null && (payload.url.contains("/playlist") || !payload.url.contains("/watch")) -> "PLAYLIST"
            targetVideoId != null -> "VIDEO"
            targetPlaylistId != null -> "PLAYLIST"
            else -> payload.itemType
        }

        // =====================================================================
        // TIER 1: In-Memory WebView Live JSON (<5ms, guaranteed exact CDN sizes & formats)
        // =====================================================================
        if (effectiveItemType == "VIDEO" && targetVideoId != null) {
            val candidate = payload.inlinePlayerJson ?: if (WebViewSessionManager.latestVideoId == targetVideoId) {
                WebViewSessionManager.latestPlayerResponseJson
            } else null

            if (!candidate.isNullOrBlank()) {
                try {
                    val root = JSONObject(candidate)
                    val candidateVid = root.optJSONObject("videoDetails")?.optString("videoId")
                    if (candidateVid == targetVideoId || candidateVid.isNullOrBlank()) {
                        val memoryResult = parsePlayerResponseJson(
                            payload = payload,
                            root = root,
                            targetVideoId = targetVideoId,
                            source = ResolutionSource.TIER_1_WEBVIEW_MEMORY,
                            startTime = startTime
                        )
                        if (memoryResult != null && (memoryResult.videoOptions.isNotEmpty() || memoryResult.audioOptions.isNotEmpty())) {
                            Timber.i("DualEngine: Resolved %s via In-Memory Live Player Response in %d ms", targetVideoId, System.currentTimeMillis() - startTime)
                            return@withContext memoryResult
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "WebView memory parsing failed for %s", targetVideoId)
                }
            }

            // =================================================================
            // TIER 2: Fast InnerTube Client (<200ms, direct streams fallback)
            // =================================================================
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
                Timber.w(e, "InnerTube Fast Client resolution failed for %s, falling back to NewPipe", targetVideoId)
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
                    } else cleanUrl

                    var previewItems = emptyList<BatchPlaylistItem>()
                    var playlistTitle = payload.title
                    var playlistAuthor = "YouTube"
                    var playlistThumb: String? = null
                    var totalCount = 0

                    // Tier 1: In-Page Extracted Items (<1ms, instant & zero network overhead)
                    if (!payload.playlistItemsJson.isNullOrBlank()) {
                        try {
                            val arr = org.json.JSONArray(payload.playlistItemsJson)
                            val parsed = mutableListOf<BatchPlaylistItem>()
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val vUrl = obj.optString("url")
                                val vTitle = obj.optString("title").ifBlank { "Video #${i + 1}" }
                                val vAuthor = obj.optString("author").ifBlank { "YouTube" }
                                val vDur = obj.optLong("durationSeconds", 0L)
                                val vThumb = obj.optString("thumbnailUrl").takeIf { it.isNotBlank() }
                                if (vUrl.isNotBlank()) {
                                    parsed.add(
                                        BatchPlaylistItem(
                                            url = vUrl,
                                            title = vTitle,
                                            author = vAuthor,
                                            durationSeconds = vDur,
                                            thumbnailUrl = vThumb,
                                            isSelected = true
                                        )
                                    )
                                }
                            }
                            if (parsed.isNotEmpty()) {
                                previewItems = parsed
                                totalCount = parsed.size
                                playlistThumb = parsed.firstOrNull()?.thumbnailUrl
                                Timber.i("DualEngine: Resolved %d playlist items via In-Page DOM in %d ms", parsed.size, System.currentTimeMillis() - startTime)
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to parse in-page playlistItemsJson")
                        }
                    }

                    // Tier 2: InnerTube API Client Fallback (<250ms, direct JSON API)
                    if (previewItems.isEmpty() && targetPlaylistId != null) {
                        try {
                            val innerTubeItems = InnerTubeFastClient.fetchPlaylist(targetPlaylistId)
                            if (innerTubeItems.isNotEmpty()) {
                                previewItems = innerTubeItems
                                totalCount = innerTubeItems.size
                                playlistThumb = innerTubeItems.firstOrNull()?.thumbnailUrl
                                Timber.i("DualEngine: Resolved %d playlist items via InnerTube API in %d ms", innerTubeItems.size, System.currentTimeMillis() - startTime)
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "InnerTube fetchPlaylist failed for %s", targetPlaylistId)
                        }
                    }

                    // Tier 3: NewPipe Extractor JVM Fallback
                    if (previewItems.isEmpty()) {
                        try {
                            val playlistInfo = withTimeoutOrNull(5000L) {
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
                    }

                    // Tier 4: yt-dlp --flat-playlist Fallback (Zero-failure binary fallback)
                    if (previewItems.isEmpty()) {
                        try {
                            val req = com.yausername.youtubedl_android.YoutubeDLRequest(canonicalPlaylistUrl).apply {
                                addOption("--flat-playlist")
                                addOption("-J")
                                addOption("--no-warnings")
                            }
                            val res = com.yausername.youtubedl_android.YoutubeDL.getInstance().execute(req)
                            val root = JSONObject(res.out)
                            val entries = root.optJSONArray("entries")
                            if (entries != null) {
                                val ytdlItems = mutableListOf<BatchPlaylistItem>()
                                for (i in 0 until entries.length()) {
                                    val entry = entries.getJSONObject(i)
                                    val eId = entry.optString("id")
                                    val eTitle = entry.optString("title").ifBlank { "Video #${i + 1}" }
                                    val eAuthor = entry.optString("uploader").ifBlank { playlistAuthor }
                                    val eDur = entry.optLong("duration", 0L)
                                    val eThumb = "https://i.ytimg.com/vi/$eId/hqdefault.jpg"
                                    if (eId.isNotBlank()) {
                                        ytdlItems.add(
                                            BatchPlaylistItem(
                                                url = "https://www.youtube.com/watch?v=$eId",
                                                title = eTitle,
                                                author = eAuthor,
                                                durationSeconds = eDur,
                                                thumbnailUrl = eThumb,
                                                isSelected = true
                                            )
                                        )
                                    }
                                }
                                if (ytdlItems.isNotEmpty()) {
                                    previewItems = ytdlItems
                                    totalCount = ytdlItems.size
                                    Timber.i("DualEngine: Resolved %d playlist items via yt-dlp in %d ms", ytdlItems.size, System.currentTimeMillis() - startTime)
                                }
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "yt-dlp flat-playlist resolution failed for %s", canonicalPlaylistUrl)
                        }
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

                        val bestAudio = streamInfo.audioStreams
                            .sortedWith(
                                compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> {
                                    it.audioTrackType == AudioTrackType.ORIGINAL
                                }.thenByDescending {
                                    it.codec?.contains("mp4a", ignoreCase = true) == true ||
                                        it.format?.suffix?.equals("m4a", true) == true
                                }.thenByDescending { it.bitrate }
                            )
                            .firstOrNull()
                        val bestAudioBytes = if (bestAudio != null) {
                            val br = bestAudio.bitrate.toLong().takeIf { it > 0 } ?: 128000L
                            streamContentLength(bestAudio.content ?: bestAudio.url)
                                .takeIf { it > 0L } ?: ((br * durationSec) / 8L)
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
                                val rawVideoBytes = streamContentLength(vs.content ?: vs.url)
                                    .takeIf { it > 0L } ?: ((vBitrate * durationSec) / 8L)
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
                                    videoOnlyBytes = rawVideoBytes,
                                    audioStreamBytes = bestAudioBytes,
                                    estimatedTotalBytes = combinedBytes,
                                    formatSelector = FormatSelectorBuilder.buildVideoSelector(resHeight),
                                    directStreamUrl = vs.content ?: @Suppress("DEPRECATION") vs.url,
                                    matchedAudioStreamUrl = bestAudio?.content ?: @Suppress("DEPRECATION") bestAudio?.url,
                                    isMuxed = false
                                )
                            }

                        val audioOptions = streamInfo.audioStreams.map { a ->
                            val rawExt = a.format?.suffix?.lowercase() ?: "m4a"
                            val isOpus = a.codec?.contains("opus", ignoreCase = true) == true || rawExt == "webm"
                            val ext = when {
                                isOpus -> "opus"
                                rawExt == "mp4" -> "m4a"
                                else -> rawExt
                            }
                            val codecName = if (isOpus) "Opus" else "AAC"
                            val bitrateKbps = (a.bitrate / 1000).coerceAtLeast(48)
                            val aBytes = streamContentLength(a.content ?: a.url)
                                .takeIf { it > 0L }
                                ?: (((a.bitrate.toLong().takeIf { it > 0 } ?: 128000L) * durationSec) / 8L)

                            AudioQualityOption(
                                label = "$bitrateKbps kbps $codecName (${ext.uppercase()})",
                                extension = ext,
                                acodec = codecName,
                                bitrateKbps = bitrateKbps,
                                estimatedBytes = aBytes,
                                formatSelector = FormatSelectorBuilder.buildAudioSelector(ext),
                                directStreamUrl = a.content ?: @Suppress("DEPRECATION") a.url,
                                audioTrackId = a.audioTrackId,
                                audioTrackName = a.audioTrackName,
                                audioLanguage = a.audioLocale?.toLanguageTag(),
                                audioTrackType = a.audioTrackType?.name,
                                isDefaultTrack = a.audioTrackType == AudioTrackType.ORIGINAL
                            )
                        }.distinctBy { "${it.trackKey}_${it.extension}_${it.bitrateKbps}" }

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

    /**
     * Picks the most useful audio candidate: default/original track first, then playable URL,
     * then highest bitrate. Used to build the automatic video+audio pairing and fallbacks.
     */
    private fun isBetterAudioCandidate(
        candidate: AudioQualityOption,
        current: AudioQualityOption?
    ): Boolean {
        if (current == null) return true
        if (candidate.isOriginalOrDefault != current.isOriginalOrDefault) {
            return candidate.isOriginalOrDefault
        }
        if ((candidate.directStreamUrl != null) != (current.directStreamUrl != null)) {
            return candidate.directStreamUrl != null
        }
        return candidate.bitrateKbps > current.bitrateKbps
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
        var bestM4aAudio: AudioQualityOption? = null
        var bestOpusAudio: AudioQualityOption? = null
        var bestAnyAudio: AudioQualityOption? = null

        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.optJSONObject(i) ?: continue
            val mimeType = format.optString("mimeType")
            if (mimeType.startsWith("audio/")) {
                val rawAudioUrl = format.optString("url").takeIf { it.isNotBlank() }
                val directUrl = rawAudioUrl?.takeIf { it.startsWith("http") }

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

                val isOpus = mimeType.contains("opus")
                val ext = if (isOpus) "opus" else "m4a"
                val codec = if (isOpus) "Opus" else "AAC"
                val bitrateKbps = (bitrate / 1000L).toInt().coerceAtLeast(48)

                // Multi-language uploads expose one adaptive audio format per track.
                // Keeping this metadata lets the picker offer the exact track the user wants.
                val audioTrack = format.optJSONObject("audioTrack")
                val rawTrackId = audioTrack?.optString("id").orEmpty()
                val trackId = rawTrackId.takeIf { it.isNotBlank() && !it.equals("null", true) }
                val trackName = audioTrack?.optString("displayName")
                    ?.takeIf { it.isNotBlank() && !it.equals("null", true) }
                val isDefaultTrack = audioTrack?.optBoolean("audioIsDefault", false) == true
                val language = trackId?.substringBefore('.')?.takeIf { it.isNotBlank() }

                val audioOpt = AudioQualityOption(
                    label = "$bitrateKbps kbps $codec (${ext.uppercase()})",
                    extension = ext,
                    acodec = codec,
                    bitrateKbps = bitrateKbps,
                    estimatedBytes = audioBytes,
                    formatSelector = FormatSelectorBuilder.buildAudioSelector(ext),
                    directStreamUrl = directUrl,
                    audioTrackId = trackId,
                    audioTrackName = trackName,
                    audioLanguage = language,
                    audioTrackType = if (isDefaultTrack) AudioTrackType.ORIGINAL.name else null,
                    isDefaultTrack = isDefaultTrack
                )
                audioList.add(audioOpt)

                if (ext == "m4a" && isBetterAudioCandidate(audioOpt, bestM4aAudio)) {
                    bestM4aAudio = audioOpt
                }
                if (ext == "opus" && isBetterAudioCandidate(audioOpt, bestOpusAudio)) {
                    bestOpusAudio = audioOpt
                }
                if (isBetterAudioCandidate(audioOpt, bestAnyAudio)) {
                    bestAnyAudio = audioOpt
                }
            }
        }

        val fallbackAudioBytes = (128000L * durationSec) / 8L

        // 2. Process Video Streams (Preserve distinct codec variants: H.264, VP9, AV1)
        val videoVariants = mutableMapOf<String, VideoQualityOption>()

        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.optJSONObject(i) ?: continue
            val mimeType = format.optString("mimeType")
            if (mimeType.startsWith("video/")) {
                val height = format.optInt("height", 0)
                if (height > 0) {
                    val fps = format.optInt("fps", 30)
                    val rawVideoUrl = format.optString("url").takeIf { it.isNotBlank() }
                    val directUrl = rawVideoUrl?.takeIf { it.startsWith("http") }

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

                    val isAvc = mimeType.contains("avc1")
                    val isAv01 = mimeType.contains("av01")
                    val isVp9 = mimeType.contains("vp9") || mimeType.contains("vp09")
                    val codecBadge = when {
                        isAvc -> "H.264"
                        isAv01 -> "AV1"
                        isVp9 -> "VP9"
                        else -> "VP9"
                    }
                    val container = if (isAvc || isAv01) "mp4" else "webm"
                    val fpsTag = if (fps >= 50) "60" else "30"
                    val variantKey = "$height-$codecBadge-$fpsTag"

                    val pairedAudio = if (container == "mp4") {
                        bestM4aAudio ?: bestAnyAudio
                    } else {
                        bestOpusAudio ?: bestAnyAudio ?: bestM4aAudio
                    }
                    val pairedAudioBytes = pairedAudio?.estimatedBytes?.takeIf { it > 0L }
                        ?: bestAnyAudio?.estimatedBytes?.takeIf { it > 0L }
                        ?: fallbackAudioBytes
                    val pairedAudioUrl = pairedAudio?.directStreamUrl ?: bestM4aAudio?.directStreamUrl ?: bestAnyAudio?.directStreamUrl
                    val totalSize = rawVideoSize + pairedAudioBytes

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

                    val existing = videoVariants[variantKey]
                    if (existing == null || (existing.directStreamUrl == null && directUrl != null)) {
                        videoVariants[variantKey] = VideoQualityOption(
                            height = height,
                            label = label,
                            fps = fps,
                            vcodec = codecBadge,
                            container = container,
                            videoOnlyBytes = rawVideoSize,
                            audioStreamBytes = pairedAudioBytes,
                            estimatedTotalBytes = totalSize,
                            formatSelector = FormatSelectorBuilder.buildVideoSelector(height, codecBadge),
                            directStreamUrl = directUrl,
                            matchedAudioStreamUrl = pairedAudioUrl,
                            isMuxed = false
                        )
                    }
                }
            }
        }

        // 3. Process Progressive Pre-Muxed Formats (e.g., 360p itag 18, 720p itag 22)
        val progressiveFormats = streamingData.optJSONArray("formats")
        if (progressiveFormats != null) {
            for (i in 0 until progressiveFormats.length()) {
                val format = progressiveFormats.optJSONObject(i) ?: continue
                val mimeType = format.optString("mimeType")
                if (!mimeType.startsWith("video/")) continue
                val height = format.optInt("height", 0)
                if (height <= 0) continue

                val rawUrl = format.optString("url").takeIf { it.isNotBlank() }
                val directUrl = rawUrl?.takeIf { it.startsWith("http") }

                var contentLength = format.optString("contentLength").toLongOrNull() ?: format.optLong("contentLength", 0L)
                if (contentLength <= 0L && rawUrl != null && rawUrl.contains("clen=")) {
                    contentLength = rawUrl.substringAfter("clen=").substringBefore("&").toLongOrNull() ?: 0L
                }

                val bitrate = format.optLong("averageBitrate", 0L).takeIf { it > 0L }
                    ?: format.optLong("bitrate", 500000L)
                val totalSize = if (contentLength > 0L) contentLength else (bitrate * durationSec) / 8L

                val variantKey = "$height-H.264-30"
                val existing = videoVariants[variantKey]
                if (existing == null || (height <= 360 && directUrl != null)) {
                    val label = when (height) {
                        360 -> "360p Medium"
                        else -> "${height}p"
                    }
                    videoVariants[variantKey] = VideoQualityOption(
                        height = height,
                        label = label,
                        fps = format.optInt("fps", 30),
                        vcodec = "H.264",
                        container = "mp4",
                        videoOnlyBytes = totalSize,
                        audioStreamBytes = 0L,
                        estimatedTotalBytes = totalSize,
                        formatSelector = FormatSelectorBuilder.buildVideoSelector(height),
                        directStreamUrl = directUrl,
                        matchedAudioStreamUrl = null,
                        isMuxed = true
                    )
                }
            }
        }

        val videoOptions = videoVariants.values.sortedWith(
            compareByDescending<VideoQualityOption> { it.height }
                .thenBy { if (it.vcodec == "H.264") 0 else if (it.vcodec == "VP9") 1 else 2 }
                .thenByDescending { it.fps }
        )
        val audioOptions = audioList.distinctBy { "${it.trackKey}_${it.extension}_${it.bitrateKbps}" }

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

    /**
     * NewPipe does not expose a file-size field, but googlevideo URLs usually carry the exact
     * byte count as `clen=`. Using it beats bitrate × duration estimates by a wide margin.
     */
    private fun streamContentLength(url: String?): Long {
        if (url.isNullOrBlank()) return 0L
        return Regex("""[?&]clen=(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
    }
}
