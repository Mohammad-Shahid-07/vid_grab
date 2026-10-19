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
        val selectedBatchUrls: Set<String> = emptySet()
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

                val allBatchUrls = if (resolved is ResolvedMediaInfo.PlaylistOrChannel) {
                    resolved.items.map { it.url }.toSet()
                } else emptySet()

                _uiState.value = QualityPickerUiState.Success(
                    mediaInfo = resolved,
                    isAudioOnlyTab = false,
                    selectedVideoHeight = defaultHeight,
                    selectedAudioExtension = defaultAudio?.extension ?: "m4a",
                    selectedAudioBitrateKbps = defaultAudio?.bitrateKbps ?: 160,
                    enableAria2cTurbo = false,
                    selectedBatchUrls = allBatchUrls
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
            current.mediaInfo.items.map { it.url }.toSet()
        } else emptySet()

        _uiState.value = current.copy(selectedBatchUrls = if (selectAll) allUrls else emptySet())
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

        val selectedSize = if (isAudio) {
            info.audioOptions.firstOrNull { it.extension == state.selectedAudioExtension && it.bitrateKbps == state.selectedAudioBitrateKbps }?.estimatedBytes ?: 0L
        } else {
            info.videoOptions.firstOrNull { it.height == state.selectedVideoHeight }?.estimatedTotalBytes ?: 0L
        }

        val isBatch = info is ResolvedMediaInfo.PlaylistOrChannel
        val batchUrls = if (isBatch) state.selectedBatchUrls.toList() else emptyList()
        val batchItems = if (isBatch) {
            (info as ResolvedMediaInfo.PlaylistOrChannel).items.filter { state.selectedBatchUrls.contains(it.url) }
        } else emptyList()

        return DownloadRequestSpec(
            url = info.url,
            title = info.title,
            mediaType = if (isBatch) (if (isAudio) "AUDIO" else "VIDEO") else if (isAudio) "AUDIO" else "VIDEO",
            targetHeight = if (isAudio) null else state.selectedVideoHeight,
            formatSelector = formatSelector,
            outputExtension = ext,
            expectedSizeBytes = selectedSize,
            isBatch = isBatch,
            batchUrls = batchUrls,
            batchItems = batchItems,
            poToken = WebViewSessionManager.currentPoToken,
            visitorData = WebViewSessionManager.currentVisitorData,
            enableAria2c = state.enableAria2cTurbo,
            concurrentFragments = 8
        )
    }
}
