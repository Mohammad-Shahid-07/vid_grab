package com.youdrop.app.ui.browser

import androidx.lifecycle.ViewModel
import com.youdrop.app.core.webview.DownloadTriggerPayload
import com.youdrop.app.core.webview.WebViewSessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BrowserViewModel : ViewModel() {

    private val _currentUrl = MutableStateFlow("https://m.youtube.com")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow("YouTube")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _pageProgress = MutableStateFlow(0)
    val pageProgress: StateFlow<Int> = _pageProgress.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    private val _pendingDownload = MutableStateFlow<DownloadTriggerPayload?>(null)
    val pendingDownload: StateFlow<DownloadTriggerPayload?> = _pendingDownload.asStateFlow()

    val sessionTokens: StateFlow<WebViewSessionManager.SessionTokens> = WebViewSessionManager.sessionTokens

    fun onPageStarted(url: String) {
        _currentUrl.value = url
        _isLoading.value = true
    }

    fun onPageFinished(url: String, title: String?) {
        _currentUrl.value = url
        _isLoading.value = false
        if (!title.isNullOrBlank()) {
            _pageTitle.value = title
        }
    }

    fun onProgressChanged(progress: Int) {
        _pageProgress.value = progress
        _isLoading.value = progress in 1..99
    }

    fun onNavigationStateChanged(canGoBack: Boolean, canGoForward: Boolean) {
        _canGoBack.value = canGoBack
        _canGoForward.value = canGoForward
    }

    fun onDownloadTriggered(payload: DownloadTriggerPayload) {
        _pendingDownload.value = payload
    }

    fun dismissDownloadTrigger() {
        _pendingDownload.value = null
    }

    /**
     * Determines the media category of a YouTube URL.
     */
    fun classifyUrl(url: String): String {
        return when {
            url.contains("/watch") || url.contains("/shorts/") || url.contains("youtu.be/") -> "VIDEO"
            url.contains("list=") || url.contains("/playlist") -> "PLAYLIST"
            url.contains("/@") || url.contains("/channel/") || url.contains("/c/") -> "CHANNEL"
            else -> "PAGE"
        }
    }

    /**
     * Synthesizes a download trigger payload from the current browser state for the native FAB.
     */
    fun createFabDownloadPayload(
        overrideUrl: String? = null,
        overrideTitle: String? = null,
        overrideInlineJson: String? = null
    ): DownloadTriggerPayload {
        val url = overrideUrl?.takeIf { it.isNotBlank() } ?: _currentUrl.value
        val title = overrideTitle?.takeIf { it.isNotBlank() } ?: _pageTitle.value
        val type = classifyUrl(url)

        val targetVid = com.youdrop.app.core.extractor.DualEngineMetadataResolver.extractVideoId(url)
        val inlineJson = overrideInlineJson?.takeIf { it.isNotBlank() }
            ?: if (type == "VIDEO" && targetVid != null && WebViewSessionManager.latestVideoId == targetVid) {
                WebViewSessionManager.latestPlayerResponseJson
            } else null

        var durationSec = 0L
        if (!inlineJson.isNullOrBlank()) {
            try {
                val root = org.json.JSONObject(inlineJson)
                val vd = root.optJSONObject("videoDetails")
                durationSec = vd?.optString("lengthSeconds")?.toLongOrNull() ?: vd?.optLong("lengthSeconds", 0L) ?: 0L
            } catch (e: Exception) {}
        }

        return DownloadTriggerPayload(
            url = url,
            itemType = type,
            title = title,
            inlinePlayerJson = inlineJson,
            durationSeconds = durationSec
        )
    }
}
