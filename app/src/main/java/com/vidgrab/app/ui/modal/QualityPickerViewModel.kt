package com.vidgrab.app.ui.modal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vidgrab.app.core.extractor.DownloadRequestSpec
import com.vidgrab.app.core.extractor.DualEngineMetadataResolver
import com.vidgrab.app.core.extractor.FormatSelectorBuilder
import com.vidgrab.app.core.extractor.ResolvedMediaInfo
import com.vidgrab.app.core.webview.DownloadTriggerPayload
import com.vidgrab.app.core.webview.WebViewSessionManager
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
        val selectedAudioExtension: String = "m4a",
        val selectedAudioBitrateKbps: Int = 160,
        val enableAria2cTurbo: Boolean = false,
        val selectedBatchUrls: Set<String> = emptySet(),
        val orderedBatchItems: List<com.vidgrab.app.core.extractor.BatchPlaylistItem> = emptyList(),
        val addNumberPrefix: Boolean = true,
        val cutSponsor: Boolean = false,
        val embedSubtitles: Boolean = false,
        val embedChapters: Boolean = false
    ) : QualityPickerUiState
    data class Error(val message: String) : QualityPickerUiState
}

class QualityPickerViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<QualityPickerUiState?>(null)
    val uiState: StateFlow<QualityPickerUiState?> = _uiState.asStateFlow()

    fun load(payload: DownloadTriggerPayload) {
        _uiState.value = QualityPickerUiState.Loading(payload)
        viewModelScope.launch {
            try {
                val resolved = DualEngineMetadataResolver.resolve(payload)
                val defaultHeight = resolved.videoOptions.firstOrNull { it.height <= 1080 }?.height
                    ?: resolved.videoOptions.firstOrNull()?.height ?: 1080

                val defaultAudio = resolved.audioOptions.firstOrNull { it.extension == "m4a" }
                    ?: resolved.audioOptions.firstOrNull()

                val playlistItems = (resolved as? ResolvedMediaInfo.PlaylistOrChannel)?.items ?: emptyList()
                val allBatchUrls = playlistItems.map { it.url }.toSet()

                _uiState.value = QualityPickerUiState.Success(
                    mediaInfo = resolved,
                    isAudioOnlyTab = false,
                    selectedVideoHeight = defaultHeight,
                    selectedAudioExtension = defaultAudio?.extension ?: "m4a",
                    selectedAudioBitrateKbps = defaultAudio?.bitrateKbps ?: 160,
                    enableAria2cTurbo = false,
                    selectedBatchUrls = allBatchUrls,
                    orderedBatchItems = playlistItems,
                    addNumberPrefix = true
                )
            } catch (e: Exception) {
                Timber.e(e, "Error resolving formats for %s", payload.url)
                _uiState.value = QualityPickerUiState.Error("Failed to parse quality formats: ${e.message}")
            }
        }
    }

    fun selectTab(isAudioOnly: Boolean) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        _uiState.value = current.copy(isAudioOnlyTab = isAudioOnly)
    }

    fun selectVideoHeight(height: Int) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        _uiState.value = current.copy(selectedVideoHeight = height)
    }

    fun selectAudio(extension: String, bitrate: Int) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        _uiState.value = current.copy(
            selectedAudioExtension = extension,
            selectedAudioBitrateKbps = bitrate
        )
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

    fun moveBatchItemUp(index: Int) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        if (index <= 0 || index >= current.orderedBatchItems.size) return
        val list = current.orderedBatchItems.toMutableList()
        val temp = list[index]
        list[index] = list[index - 1]
        list[index - 1] = temp
        _uiState.value = current.copy(orderedBatchItems = list)
    }

    fun moveBatchItemDown(index: Int) {
        val current = _uiState.value as? QualityPickerUiState.Success ?: return
        if (index < 0 || index >= current.orderedBatchItems.size - 1) return
        val list = current.orderedBatchItems.toMutableList()
        val temp = list[index]
        list[index] = list[index + 1]
        list[index + 1] = temp
        _uiState.value = current.copy(orderedBatchItems = list)
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
        _uiState.value = null
    }

    /**
     * Synthesizes the final DownloadRequestSpec to be handed to Room DB & WorkManager.
     */
    fun buildDownloadRequestSpec(): DownloadRequestSpec? {
        val state = _uiState.value as? QualityPickerUiState.Success ?: return null
        val info = state.mediaInfo

        val isAudio = state.isAudioOnlyTab
        val formatSelector = if (isAudio) {
            FormatSelectorBuilder.buildAudioSelector(state.selectedAudioExtension)
        } else {
            FormatSelectorBuilder.buildVideoSelector(state.selectedVideoHeight)
        }

        val ext = if (isAudio) state.selectedAudioExtension else "mp4"

        val isBatch = info is ResolvedMediaInfo.PlaylistOrChannel
        if (isBatch && state.selectedBatchUrls.isEmpty()) {
            return null
        }

        val rawSelectedItems = if (isBatch) {
            state.orderedBatchItems.filter { state.selectedBatchUrls.contains(it.url) }
        } else emptyList()

        val totalSelectedDuration = if (isBatch) {
            rawSelectedItems.sumOf { it.durationSeconds }.takeIf { it > 0 } ?: (rawSelectedItems.size.toLong() * 240L)
        } else {
            (info as? ResolvedMediaInfo.SingleVideo)?.durationSeconds?.takeIf { it > 0 } ?: 240L
        }

        val selectedSize = if (isAudio) {
            val br = state.selectedAudioBitrateKbps.toLong().takeIf { it > 0 } ?: 160L
            (br * 1000L / 8L) * totalSelectedDuration
        } else {
            val h = state.selectedVideoHeight
            val vBr = when (h) { 2160 -> 15000L; 1440 -> 8000L; 1080 -> 4500L; 720 -> 2500L; 480 -> 1200L; 360 -> 700L; 240 -> 400L; else -> 200L }
            ((vBr + 128L) * 1000L / 8L) * totalSelectedDuration
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

        return DownloadRequestSpec(
            url = info.url,
            title = info.title,
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
            poToken = WebViewSessionManager.currentPoToken,
            visitorData = WebViewSessionManager.currentVisitorData,
            enableAria2c = state.enableAria2cTurbo,
            concurrentFragments = 8
        )
    }
}
