package com.vidgrab.app.core.webview

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import timber.log.Timber

/**
 * Data payload emitted whenever the user taps an injected Shadow DOM download button
 * or the native Compose Floating Action Button.
 */
data class DownloadTriggerPayload(
    val url: String,
    val itemType: String, // "VIDEO", "PLAYLIST", "CHANNEL"
    val title: String,
    val inlinePlayerJson: String?,
    val durationSeconds: Long = 0L
)

/**
 * Kotlin-to-JavaScript Interface exposed to the WebView under window.VidGrabBridge.
 */
class YouTubeJsBridge(
    private val onDownloadTriggered: (DownloadTriggerPayload) -> Unit,
    private val onUrlChanged: ((String, String) -> Unit)? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onUrlChanged(url: String?, title: String?) {
        if (!url.isNullOrBlank()) {
            mainHandler.post {
                onUrlChanged?.invoke(url, title.orEmpty())
            }
        }
    }

    @JavascriptInterface
    fun onSessionTokens(poToken: String?, visitorData: String?) {
        Timber.d("YouTubeJsBridge: onSessionTokens received (poToken=%b, visitorData=%b)", !poToken.isNullOrBlank(), !visitorData.isNullOrBlank())
        WebViewSessionManager.updateSessionTokens(poToken, visitorData)
    }

    @JavascriptInterface
    fun onPlayerResponse(videoId: String?, json: String?) {
        if (!videoId.isNullOrBlank() && !json.isNullOrBlank()) {
            Timber.d("YouTubeJsBridge: onPlayerResponse received for videoId: %s (%d bytes)", videoId, json.length)
            WebViewSessionManager.updatePlayerResponse(videoId, json)
        }
    }

    @JavascriptInterface
    fun onDownloadClicked(url: String, itemType: String, title: String, inlinePlayerJson: String?, durationSeconds: Long) {
        val cleanUrl = com.vidgrab.app.core.extractor.DualEngineMetadataResolver.sanitizeMediaUrl(url)
        Timber.i("YouTubeJsBridge: onDownloadClicked [type=%s, cleanUrl=%s, title=%s, dur=%ds, hasInlineJson=%b]", itemType, cleanUrl, title, durationSeconds, !inlinePlayerJson.isNullOrBlank())
        val payload = DownloadTriggerPayload(
            url = cleanUrl,
            itemType = itemType,
            title = title.ifBlank { "YouTube Media" },
            inlinePlayerJson = inlinePlayerJson?.takeIf { it.isNotBlank() },
            durationSeconds = durationSeconds.coerceAtLeast(0L)
        )

        // Post to Main Looper for Compose UI consumption
        mainHandler.post {
            onDownloadTriggered(payload)
        }
    }

    @JavascriptInterface
    fun onDownloadClicked(url: String, itemType: String, title: String, inlinePlayerJson: String?) {
        onDownloadClicked(url, itemType, title, inlinePlayerJson, 0L)
    }
}
