package com.vidgrab.app.ui.modal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidgrab.app.core.extractor.AudioQualityOption
import com.vidgrab.app.core.extractor.DownloadRequestSpec
import com.vidgrab.app.core.extractor.DualEngineMetadataResolver
import com.vidgrab.app.core.extractor.FormatSelectorBuilder
import com.vidgrab.app.core.extractor.ResolvedMediaInfo
import com.vidgrab.app.core.extractor.VideoQualityOption
import com.vidgrab.app.core.webview.DownloadTriggerPayload
import com.vidgrab.app.core.webview.WebViewSessionManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

sealed interface QualityPickerUiState {
    data class Loading(val payload: DownloadTriggerPayload) : QualityPickerUiState
    data class Success(
        val mediaInfo: ResolvedMediaInfo,
        val isAudioOnlyTab: Boolean = false,
        val selectedVideoHeight: Int = 1080,
        val selectedVideoOption: VideoQualityOption? = null,
        val selectedAudioExtension: String = "m4a",
        val selectedAudioBitrateKbps: Int = 160,
        /** Exact audio stream key (track + container + bitrate) to mux with video; null = automatic. */
        val selectedMergeAudioStreamKey: String? = null,
        /** Playlist-wide video codec preference: Auto, H.264, VP9 or AV1. */
        val batchVideoCodec: String = "Auto",
        /** Playlist-wide audio container preference: Auto, M4A or Opus. */
        val batchMergeAudioExtension: String = "Auto",
        val enableAria2cTurbo: Boolean = false,
        val selectedBatchUrls: Set<String> = emptySet(),
        val orderedBatchItems: List<com.vidgrab.app.core.extractor.BatchPlaylistItem> = emptyList(),
        val addNumberPrefix: Boolean = true,
        val cutSponsor: Boolean = false,
        val embedSubtitles: Boolean = false,
        val embedChapters: Boolean = false,
        val selectedDownloadFolder: String = "Download/YouDrop"
    ) : QualityPickerUiState {

        /**
         * The exact audio stream that will be muxed into the output. Single source of truth for
         * both the picker's size labels and [QualityPickerViewModel.buildDownloadRequestSpec], so
         * what the user sees can never disagree with what gets queued.
         */
        val effectiveMergeAudio: AudioQualityOption?
            get() = if (isAudioOnlyTab) null else resolveMergeAudio(mediaInfo, this, selectedVideoOption)

        /** Container of the finished file, derived from the video + audio pair. */
        val effectiveOutputContainer: String
            get() = when {
                isAudioOnlyTab -> selectedAudioExtension
                mediaInfo is ResolvedMediaInfo.PlaylistOrChannel ->
                    resolveBatchContainer(batchVideoCodec, batchMergeAudioExtension)
                else -> resolveOutputContainer(selectedVideoOption, effectiveMergeAudio)
            }

        /**
         * Real size of the finished file: video stream + the audio stream the user actually picked.
         * Progressive (pre-muxed) formats already contain their audio, so they are used as-is.
         */
        val mergedSizeBytes: Long
            get() {
                val video = selectedVideoOption ?: return 0L
                if (video.isMuxed) {
                    return video.estimatedTotalBytes.takeIf { it > 0L } ?: video.videoOnlyBytes
                }
                val audioBytes = effectiveMergeAudio?.estimatedBytes?.takeIf { it > 0L }
                    ?: video.audioStreamBytes
                return video.videoOnlyBytes + audioBytes
            }
    }
    data class Error(val message: String) : QualityPickerUiState
}

class QualityPickerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<QualityPickerUiState?>(null)
    val uiState: StateFlow<QualityPickerUiState?> = _uiState.asStateFlow()

    private data class CachedResolution(
        val resolved: ResolvedMediaInfo,
        val storedAtMillis: Long
    )

    private val resolutionCache = ConcurrentHashMap<String, CachedResolution>()

    /** Bumped on every load(); stale resolve coroutines are ignored so a refresh can supersede them. */
    private var loadGeneration = 0

    fun load(payload: DownloadTriggerPayload) {
        val generation = ++loadGeneration

        // 5-minute memoization: reopening the picker for the same URL (videos and playlists),
        // including accidental dismissals, must not re-run the resolver or re-fire its requests.
        val cached = cacheLookup(payload.url)
        if (cached != null) {
            Timber.d("QualityPickerViewModel: cache hit for %s", payload.url)
            _uiState.value = buildSuccessState(cached)
            return
        }

        _uiState.value = QualityPickerUiState.Loading(payload)
        viewModelScope.launch {
            try {
                val resolved = DualEngineMetadataResolver.resolve(payload)
                if (generation != loadGeneration) return@launch // superseded by a newer load
                cacheStore(payload.url, resolved)
                _uiState.value = buildSuccessState(resolved)
            } catch (e: Exception) {
                if (generation != loadGeneration) return@launch
                Timber.e(e, "Error resolving formats for %s", payload.url)
                _uiState.value = QualityPickerUiState.Error("Failed to parse quality formats: ${e.message}")
            }
        }
    }

    private fun buildSuccessState(resolved: ResolvedMediaInfo): QualityPickerUiState.Success {
        val defaultHeight = resolved.videoOptions.firstOrNull { it.height <= 1080 }?.height
            ?: resolved.videoOptions.firstOrNull()?.height ?: 1080

        val defaultVideoOption = resolved.videoOptions.firstOrNull { it.height == defaultHeight && it.vcodec == "H.264" }
            ?: resolved.videoOptions.firstOrNull { it.height == defaultHeight }

        val defaultAudio = resolved.audioOptions.firstOrNull { it.extension == "m4a" }
            ?: resolved.audioOptions.firstOrNull()

        val playlistItems = (resolved as? ResolvedMediaInfo.PlaylistOrChannel)?.items ?: emptyList()
        val allBatchUrls = playlistItems.map { it.url }.toSet()

        return QualityPickerUiState.Success(
            mediaInfo = resolved,
            isAudioOnlyTab = false,
            selectedVideoHeight = defaultHeight,
            selectedVideoOption = defaultVideoOption,
            selectedAudioExtension = defaultAudio?.extension ?: "m4a",
            selectedAudioBitrateKbps = defaultAudio?.bitrateKbps ?: 160,
            enableAria2cTurbo = false,
            selectedBatchUrls = allBatchUrls,
            orderedBatchItems = playlistItems,
            addNumberPrefix = true
        )
    }

    private fun cacheLookup(url: String): ResolvedMediaInfo? {
        val entry = resolutionCache[url] ?: return null
        if (System.currentTimeMillis() - entry.storedAtMillis > CACHE_TTL_MS) {
            resolutionCache.remove(url)
            return null
        }
        return entry.resolved
    }

    private fun cacheStore(url: String, resolved: ResolvedMediaInfo) {
        val now = System.currentTimeMillis()
        resolutionCache[url] = CachedResolution(resolved, now)
        // Opportunistic prune keeps the map tiny (one entry per recently opened media URL).
        val iterator = resolutionCache.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.storedAtMillis > CACHE_TTL_MS) iterator.remove()
        }
    }

    fun selectTab(isAudioOnly: Boolean) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        val updatedFolder = if (isAudioOnly && current.selectedDownloadFolder == "Download/YouDrop") {
            "Music/YouDrop"
        } else if (!isAudioOnly && current.selectedDownloadFolder == "Music/YouDrop") {
            "Download/YouDrop"
        } else {
            current.selectedDownloadFolder
        }
        _uiState.value = current.copy(
            isAudioOnlyTab = isAudioOnly,
            selectedDownloadFolder = updatedFolder
        )
    }

    fun setDownloadFolder(folder: String) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        _uiState.value = current.copy(selectedDownloadFolder = folder)
    }

    fun selectVideoHeight(height: Int) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        val currentCodec = current.selectedVideoOption?.vcodec
        val bestOption = current.mediaInfo.videoOptions.firstOrNull { it.height == height && it.vcodec == currentCodec }
            ?: current.mediaInfo.videoOptions.firstOrNull { it.height == height && it.vcodec == "H.264" }
            ?: current.mediaInfo.videoOptions.firstOrNull { it.height == height }
        _uiState.value = current.copy(
            selectedVideoHeight = height,
            selectedVideoOption = bestOption
        )
    }

    fun selectVideoVariant(option: VideoQualityOption) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        _uiState.value = current.copy(
            selectedVideoHeight = option.height,
            selectedVideoOption = option
        )
    }

    fun selectAudio(extension: String, bitrate: Int) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        _uiState.value = current.copy(
            selectedAudioExtension = extension,
            selectedAudioBitrateKbps = bitrate
        )
    }

    fun selectMergeAudioStream(streamKey: String?) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        _uiState.value = current.copy(selectedMergeAudioStreamKey = streamKey)
    }

    fun selectBatchVideoCodec(codec: String) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        _uiState.value = current.copy(batchVideoCodec = codec)
    }

    fun selectBatchMergeAudioExtension(extension: String) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        _uiState.value = current.copy(batchMergeAudioExtension = extension)
    }

    fun toggleAria2c(enabled: Boolean) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        _uiState.value = current.copy(enableAria2cTurbo = enabled)
    }

    fun toggleBatchItem(url: String) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        val updated = current.selectedBatchUrls.toMutableSet()
        if (updated.contains(url)) updated.remove(url) else updated.add(url)
        _uiState.value = current.copy(selectedBatchUrls = updated)
    }

    fun toggleSelectAllBatch(selectAll: Boolean) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        val allUrls = if (current.mediaInfo is ResolvedMediaInfo.PlaylistOrChannel) {
            current.orderedBatchItems.map { it.url }.toSet()
        } else emptySet()

        _uiState.value = current.copy(selectedBatchUrls = if (selectAll) allUrls else emptySet())
    }

    fun toggleNumberPrefix(enabled: Boolean) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        _uiState.value = current.copy(addNumberPrefix = enabled)
    }

    fun moveBatchItem(fromIndex: Int, toIndex: Int) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        val list = current.orderedBatchItems.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        _uiState.value = current.copy(orderedBatchItems = list)
    }

    fun moveBatchItemUp(index: Int) {
        if (index > 0) moveBatchItem(index, index - 1)
    }

    fun moveBatchItemDown(index: Int) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        if (index < current.orderedBatchItems.size - 1) moveBatchItem(index, index + 1)
    }

    fun toggleCutSponsor(enabled: Boolean) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        _uiState.value = current.copy(cutSponsor = enabled)
    }

    fun toggleEmbedSubtitles(enabled: Boolean) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        _uiState.value = current.copy(embedSubtitles = enabled)
    }

    fun toggleEmbedChapters(enabled: Boolean) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        _uiState.value = current.copy(embedChapters = enabled)
    }

    fun dismiss() {
        // Invalidate any in-flight resolve so a late result can neither re-open the closed sheet
        // nor overwrite a newer interaction.
        loadGeneration++
        _uiState.value = null
    }

    /**
     * Synthesizes the final DownloadRequestSpec to be handed to Room DB & WorkManager.
     */
    fun buildDownloadRequestSpec(): DownloadRequestSpec? {
        val state = _uiState.value as? QualityPickerUiState.Success ?: return null
        val info = state.mediaInfo

        val isAudio = state.isAudioOnlyTab
        val isBatch = info is ResolvedMediaInfo.PlaylistOrChannel
        val chosenVideo = state.selectedVideoOption
            ?: info.videoOptions.firstOrNull { it.height == state.selectedVideoHeight }

        // The muxed audio stream is chosen independently from the Audio tab selection.
        // Automatic mode prefers the default/original track and a container compatible
        // with the video container (M4A for MP4, Opus for WebM).
        val mergeAudio = if (isAudio) null else state.effectiveMergeAudio
        // Auto keeps only the container preference: pinning its track/bitrate would make the
        // request look like an explicit pick and disable the progressive <=360p shortcut.
        val explicitAudioPick = !isAudio && !isBatch && state.selectedMergeAudioStreamKey != null
        val mergeAudioLanguage = if (explicitAudioPick) mergeAudio?.audioLanguage else null
        val mergeAudioBitrate = if (explicitAudioPick) mergeAudio?.bitrateKbps else null
        val mergeAudioPreference = when (mergeAudio?.extension?.lowercase()) {
            "opus" -> "Opus"
            "m4a" -> "M4A"
            else -> "Auto"
        }

        val formatSelector = when {
            isAudio -> FormatSelectorBuilder.buildAudioSelector(
                state.selectedAudioExtension,
                state.selectedAudioBitrateKbps.takeIf { it > 0 }
            )
            isBatch -> FormatSelectorBuilder.buildVideoSelector(
                state.selectedVideoHeight,
                state.batchVideoCodec,
                state.batchMergeAudioExtension
            )
            else -> FormatSelectorBuilder.buildVideoSelector(
                chosenVideo?.height ?: state.selectedVideoHeight,
                chosenVideo?.vcodec ?: "H.264",
                mergeAudioPreference,
                mergeAudioLanguage,
                mergeAudioBitrate
            )
        }

        val ext = when {
            isAudio -> state.selectedAudioExtension
            isBatch -> resolveBatchContainer(state.batchVideoCodec, state.batchMergeAudioExtension)
            else -> resolveOutputContainer(chosenVideo, mergeAudio)
        }

        if (isBatch && state.selectedBatchUrls.isEmpty()) {
            return null
        }

        val rawSelectedItems = if (isBatch) {
            state.orderedBatchItems.filter { state.selectedBatchUrls.contains(it.url) }
        } else emptyList()

        val totalSelectedDuration = if (isBatch) {
            rawSelectedItems.sumOf { it.durationSeconds }.takeIf { it > 0 }
                ?: (rawSelectedItems.size.toLong() * (info.durationSeconds.takeIf { it > 0 } ?: 240L))
        } else {
            info.durationSeconds.takeIf { it > 0 } ?: 240L
        }

        val selectedSize = if (!isBatch) {
            if (isAudio) {
                val match = info.audioOptions.firstOrNull {
                    it.extension.equals(state.selectedAudioExtension, ignoreCase = true) &&
                    (state.selectedAudioBitrateKbps <= 0 || it.bitrateKbps == state.selectedAudioBitrateKbps)
                } ?: info.audioOptions.firstOrNull { it.bitrateKbps == state.selectedAudioBitrateKbps }
                match?.estimatedBytes?.takeIf { it > 0L } ?: 0L
            } else {
                val videoBytes = chosenVideo?.videoOnlyBytes?.takeIf { it > 0L }
                if (chosenVideo?.isMuxed == true || videoBytes == null) {
                    chosenVideo?.estimatedTotalBytes?.takeIf { it > 0L } ?: 0L
                } else {
                    videoBytes + (mergeAudio?.estimatedBytes ?: chosenVideo.audioStreamBytes)
                }
            }
        } else {
            // Same estimate the picker shows on its CTA, so the queued task and the sheet agree.
            estimateBatchSize(
                height = state.selectedVideoHeight,
                durationSeconds = totalSelectedDuration,
                videoCodec = state.batchVideoCodec,
                audioPreference = state.batchMergeAudioExtension
            ).totalBytes
        }

        val batchItems = if (state.addNumberPrefix) {
            rawSelectedItems.mapIndexed { index, item ->
                val prefix = String.format("%02d - ", index + 1)
                val cleanTitle = if (item.title.matches(Regex("""^\d{1,3}\s*[-.]\s*.*"""))) {
                    item.title.replace(Regex("""^\d{1,3}\s*[-.]\s*"""), "")
                } else item.title
                item.copy(title = "$prefix$cleanTitle")
            }
        } else {
            rawSelectedItems
        }

        val batchUrls = batchItems.map { it.url }

        val singleSponsors = (info as? ResolvedMediaInfo.SingleVideo)?.sponsorSegments ?: emptyList()

        val vid = com.vidgrab.app.core.extractor.DualEngineMetadataResolver.extractVideoId(info.url)
        val cleanUrl = DualEngineMetadataResolver.sanitizeMediaUrl(info.url)
        val thumb = info.thumbnailUrl?.takeIf { it.isNotBlank() } ?: vid?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

        val chosenAudio = if (isAudio) {
            info.audioOptions.firstOrNull {
                it.extension.equals(state.selectedAudioExtension, ignoreCase = true) &&
                (state.selectedAudioBitrateKbps <= 0 || it.bitrateKbps == state.selectedAudioBitrateKbps)
            } ?: info.audioOptions.firstOrNull { it.bitrateKbps == state.selectedAudioBitrateKbps }
        } else {
            mergeAudio
        }

        val mergeAudioUrl = mergeAudio?.directStreamUrl
            ?: chosenVideo?.matchedAudioStreamUrl

        return DownloadRequestSpec(
            url = cleanUrl,
            title = info.title,
            author = (info as? ResolvedMediaInfo.SingleVideo)?.author ?: "YouTube",
            thumbnailUrl = thumb,
            mediaType = if (isAudio) "AUDIO" else "VIDEO",
            targetHeight = if (isAudio) null else state.selectedVideoHeight,
            formatSelector = formatSelector,
            outputExtension = ext,
            expectedSizeBytes = selectedSize,
            isBatch = isBatch,
            batchUrls = batchUrls,
            batchItems = batchItems,
            cutSponsor = state.cutSponsor,
            sponsorSegments = singleSponsors,
            embedSubtitles = state.embedSubtitles,
            embedChapters = state.embedChapters,
            downloadFolder = state.selectedDownloadFolder,
            poToken = WebViewSessionManager.currentPoToken,
            visitorData = WebViewSessionManager.currentVisitorData,
            enableAria2c = state.enableAria2cTurbo,
            concurrentFragments = 8,
            resolvedVideoStreamUrl = if (!isAudio) chosenVideo?.directStreamUrl else null,
            resolvedAudioStreamUrl = if (isAudio) chosenAudio?.directStreamUrl else if (chosenVideo?.isMuxed == true) null else mergeAudioUrl,
            directVideoUrl = if (!isAudio) chosenVideo?.directStreamUrl else null,
            directAudioUrl = if (isAudio) chosenAudio?.directStreamUrl else if (chosenVideo?.isMuxed == true) null else mergeAudioUrl,
            isDirectMuxed = if (isAudio) true else (chosenVideo?.isMuxed ?: false),
            videoCodecPreference = if (isBatch) state.batchVideoCodec.takeIf { it != "Auto" } else chosenVideo?.vcodec,
            mergeAudioTrackId = if (isBatch || isAudio || !explicitAudioPick) null else mergeAudio?.audioTrackId,
            mergeAudioTrackName = if (isBatch || isAudio || !explicitAudioPick) null else mergeAudio?.audioTrackName,
            mergeAudioLanguage = if (isBatch || isAudio || !explicitAudioPick) null else mergeAudio?.audioLanguage,
            mergeAudioExtension = when {
                isAudio -> null
                isBatch -> state.batchMergeAudioExtension.takeIf { it != "Auto" }?.lowercase()
                else -> mergeAudio?.extension
            },
            mergeAudioBitrateKbps = when {
                isBatch -> null
                isAudio -> state.selectedAudioBitrateKbps.takeIf { it > 0 }
                else -> mergeAudioBitrate
            }
        )
    }

    companion object {
        /** How long a resolved picker result stays valid for instant reopen (videos and playlists). */
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}

/**
 * Chooses the output container for a single video. The pair has to be legal, otherwise the
 * muxer produces files that many players refuse:
 *   H.264/AV1 + AAC = MP4, VP9 + Opus = WebM, every mixed combination = MKV.
 */
internal fun resolveOutputContainer(
    video: VideoQualityOption?,
    audio: AudioQualityOption?
): String {
    if (video == null) return "mp4"
    if (video.isMuxed) return video.container.ifBlank { "mp4" }

    val videoFamily = video.vcodec.trim().uppercase()
    val audioExt = audio?.extension?.lowercase()

    return when {
        videoFamily == "VP9" && (audioExt == null || audioExt == "opus") -> "webm"
        videoFamily == "VP9" && audioExt == "m4a" -> "mkv"
        (videoFamily == "H.264" || videoFamily == "AV1" || videoFamily == "H264" || videoFamily == "AVC") &&
            (audioExt == null || audioExt == "m4a") -> "mp4"
        audioExt == null -> video.container.ifBlank { "mp4" }
        else -> "mkv"
    }
}

/**
 * Selects the audio stream to mux with [video]. An exact pick always wins, even when it forces
 * a different container. Otherwise the default/original track is preferred, using a container
 * that matches the video output (M4A with MP4, Opus with WebM).
 */
internal fun resolveMergeAudio(
    info: ResolvedMediaInfo,
    state: QualityPickerUiState.Success,
    video: VideoQualityOption?
): AudioQualityOption? {
    if (info.audioOptions.isEmpty()) return null

    val selectedKey = state.selectedMergeAudioStreamKey
    if (selectedKey != null) {
        info.audioOptions.firstOrNull { it.streamKey == selectedKey }?.let { return it }
        // Stale key (stream list changed): fall through to automatic selection.
    }

    val preferredExt = when {
        video?.vcodec?.equals("VP9", ignoreCase = true) == true -> "opus"
        (video?.container ?: "mp4").equals("mp4", ignoreCase = true) -> "m4a"
        else -> "opus"
    }
    val compatible = info.audioOptions.filter { it.extension.equals(preferredExt, ignoreCase = true) }
    val base = compatible.ifEmpty { info.audioOptions }

    return base.filter { it.isOriginalOrDefault }.maxByOrNull { it.bitrateKbps }
        ?: base.maxByOrNull { it.bitrateKbps }
}

/**
 * Chooses the output container for a playlist preference pair. Same legality rules as single
 * videos: VP9+Opus in WebM, AVC/AV1+AAC in MP4, mixed pairs in MKV.
 */
internal fun resolveBatchContainer(videoCodec: String, audioPreference: String): String {
    val video = videoCodec.trim().uppercase()
    val audio = audioPreference.trim().uppercase()
    return when {
        video == "VP9" && (audio == "AUTO" || audio == "OPUS") -> "webm"
        video == "VP9" && audio == "M4A" -> "mkv"
        (video == "H.264" || video == "AV1" || video == "AUTO") && audio == "OPUS" -> "mkv"
        else -> "mp4"
    }
}

/** Split estimate so the picker can show `video + audio = total` for a whole playlist. */
internal data class BatchSizeEstimate(val videoBytes: Long, val audioBytes: Long) {
    val totalBytes: Long get() = videoBytes + audioBytes
}

/**
 * Playlist items are only resolved at download time, so the picker can never know their exact
 * bitrates. This deterministic estimate uses the same height→bitrate table and audio preference
 * as the queued request, which keeps the CTA and the task card consistent.
 */
internal fun estimateBatchSize(
    height: Int,
    durationSeconds: Long,
    videoCodec: String,
    audioPreference: String
): BatchSizeEstimate {
    if (durationSeconds <= 0L) return BatchSizeEstimate(0L, 0L)
    val videoBps = when {
        height >= 2160 -> 15_000_000L
        height >= 1440 -> 8_000_000L
        height >= 1080 -> 4_000_000L
        height >= 720 -> 2_000_000L
        height >= 480 -> 1_000_000L
        else -> 500_000L
    }
    val audioKbps = when (audioPreference.trim().uppercase()) {
        "M4A" -> 128
        "OPUS" -> 160
        else -> if (videoCodec.equals("VP9", ignoreCase = true)) 160 else 128
    }
    return BatchSizeEstimate(
        videoBytes = (videoBps * durationSeconds) / 8L,
        audioBytes = (audioKbps * 1000L * durationSeconds) / 8L
    )
}
