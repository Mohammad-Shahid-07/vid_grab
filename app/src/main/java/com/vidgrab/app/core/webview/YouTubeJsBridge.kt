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
    val durationSeconds: Long = 0L,
    val playlistItemsJson: String? = null
)

/**
 * Kotlin-to-JavaScript Interface exposed to the WebView under window.VidGrabBridge.
 */
class YouTubeJsBridge(
    private val onDownloadTriggered: (DownloadTriggerPayload) -> Unit,
    private val onPayloadRefreshed: ((DownloadTriggerPayload) -> Unit)? = null,
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
    fun onDownloadClicked(url: String, itemType: String, title: String, inlinePlayerJson: String?, durationSeconds: Long, playlistItemsJson: String?) {
        val cleanUrl = if (itemType == "PLAYLIST") {
            val pid = com.vidgrab.app.core.extractor.DualEngineMetadataResolver.extractPlaylistId(url)
            if (pid != null) "https://www.youtube.com/playlist?list=$pid" else url
        } else {
            com.vidgrab.app.core.extractor.DualEngineMetadataResolver.sanitizeMediaUrl(url)
        }
        Timber.i("YouTubeJsBridge: onDownloadClicked [type=%s, cleanUrl=%s, title=%s, dur=%ds, hasPlaylistItems=%b]",
            itemType, cleanUrl, title, durationSeconds, !playlistItemsJson.isNullOrBlank())

        val payload = DownloadTriggerPayload(
            url = cleanUrl,
            itemType = itemType,
            title = title.ifBlank { "YouTube Media" },
            inlinePlayerJson = inlinePlayerJson?.takeIf { it.isNotBlank() },
            durationSeconds = durationSeconds.coerceAtLeast(0L),
            playlistItemsJson = playlistItemsJson?.takeIf { it.isNotBlank() }
        )

        // Post to Main Looper for Compose UI consumption
        mainHandler.post {
            onDownloadTriggered(payload)
        }
    }

    @JavascriptInterface
    fun onDownloadClicked(url: String, itemType: String, title: String, inlinePlayerJson: String?, durationSeconds: Long) {
        onDownloadClicked(url, itemType, title, inlinePlayerJson, durationSeconds, null)
    }

    @JavascriptInterface
    fun onDownloadClicked(url: String, itemType: String, title: String, inlinePlayerJson: String?) {
        onDownloadClicked(url, itemType, title, inlinePlayerJson, 0L, null)
    }

    /**
     * Late-arriving fresher player response for a trigger that was already delivered to the UI.
     * Native applies it only while the quality picker is still loading for the same URL, so this
     * can never re-open a dismissed sheet or clobber selections the user already made.
     */
    @JavascriptInterface
    fun onDownloadPayloadRefreshed(url: String, itemType: String, title: String, inlinePlayerJson: String?, durationSeconds: Long, playlistItemsJson: String?) {
        if (inlinePlayerJson.isNullOrBlank()) return
        val cleanUrl = if (itemType == "PLAYLIST") {
            val pid = com.vidgrab.app.core.extractor.DualEngineMetadataResolver.extractPlaylistId(url)
            if (pid != null) "https://www.youtube.com/playlist?list=$pid" else url
        } else {
            com.vidgrab.app.core.extractor.DualEngineMetadataResolver.sanitizeMediaUrl(url)
        }
        Timber.d("YouTubeJsBridge: onDownloadPayloadRefreshed [type=%s, cleanUrl=%s, %d bytes]", itemType, cleanUrl, inlinePlayerJson.length)

        val payload = DownloadTriggerPayload(
            url = cleanUrl,
            itemType = itemType,
            title = title.ifBlank { "YouTube Media" },
            inlinePlayerJson = inlinePlayerJson,
            durationSeconds = durationSeconds.coerceAtLeast(0L),
            playlistItemsJson = playlistItemsJson?.takeIf { it.isNotBlank() }
        )
        mainHandler.post {
            onPayloadRefreshed?.invoke(payload)
        }
    }

    @JavascriptInterface
    fun onFabPayloadReady(url: String, title: String, videoId: String?, json: String?, playlistItemsJson: String?) {
        if (!videoId.isNullOrBlank() && !json.isNullOrBlank()) {
            Timber.i("YouTubeJsBridge: onFabPayloadReady for video %s (%d bytes)", videoId, json.length)
            WebViewSessionManager.updatePlayerResponse(videoId, json)
        }
        val isPlaylist = url.contains("list=") || url.contains("/playlist")
        val cleanUrl = if (isPlaylist) {
            val pid = com.vidgrab.app.core.extractor.DualEngineMetadataResolver.extractPlaylistId(url)
            if (pid != null) "https://www.youtube.com/playlist?list=$pid" else url
        } else {
            com.vidgrab.app.core.extractor.DualEngineMetadataResolver.sanitizeMediaUrl(url)
        }

        val effectiveType = if (isPlaylist) "PLAYLIST" else if (videoId != null) "VIDEO" else "PAGE"
        Timber.i("YouTubeJsBridge: onFabPayloadReady [type=%s, cleanUrl=%s, hasPlaylistItems=%b]",
            effectiveType, cleanUrl, !playlistItemsJson.isNullOrBlank())

        val payload = DownloadTriggerPayload(
            url = cleanUrl,
            itemType = effectiveType,
            title = title.ifBlank { "YouTube Media" },
            inlinePlayerJson = json?.takeIf { it.isNotBlank() },
            playlistItemsJson = playlistItemsJson?.takeIf { it.isNotBlank() }
        )
        mainHandler.post {
            onDownloadTriggered(payload)
        }
    }

    @JavascriptInterface
    fun onFabPayloadReady(url: String, title: String, videoId: String?, json: String?) {
        onFabPayloadReady(url, title, videoId, json, null)
    }
}
