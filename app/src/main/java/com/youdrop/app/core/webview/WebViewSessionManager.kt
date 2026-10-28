package com.youdrop.app.core.webview

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.io.FileWriter

/**
 * Thread-safe singleton managing live YouTube WebView session state:
 * 1. BotGuard poToken & visitorData captured from intercepted /youtubei/v1/player calls.
 * 2. In-memory window.ytInitialPlayerResponse JSON for <50ms Tier 1 format extraction.
 * 3. Netscape cookies.txt exporter for downstream yt-dlp execution workers.
 */
object WebViewSessionManager {

    data class SessionTokens(
        val poToken: String? = null,
        val visitorData: String? = null,
        val updatedAt: Long = 0L
    ) {
        val isValid: Boolean get() = !poToken.isNullOrBlank() || !visitorData.isNullOrBlank()
    }

    data class WatchPageMetadata(
        val videoId: String,
        val playerResponseJson: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Chrome state reported by the page itself: whether a watch/shorts player currently occupies
     * the viewport, and whether YouTube is rendering its dark theme. The native download chip uses
     * both so it can match the page and stay off the video.
     */
    data class PageChrome(
        val playerVisible: Boolean = false,
        val isDarkTheme: Boolean = true
    )

    private val _sessionTokens = MutableStateFlow(SessionTokens())
    val sessionTokens: StateFlow<SessionTokens> = _sessionTokens.asStateFlow()

    private val _latestWatchMetadata = MutableStateFlow<WatchPageMetadata?>(null)
    val latestWatchMetadata: StateFlow<WatchPageMetadata?> = _latestWatchMetadata.asStateFlow()

    private val _pageChrome = MutableStateFlow(PageChrome())
    val pageChrome: StateFlow<PageChrome> = _pageChrome.asStateFlow()

    fun updatePageChrome(playerVisible: Boolean, isDarkTheme: Boolean) {
        val next = PageChrome(playerVisible = playerVisible, isDarkTheme = isDarkTheme)
        if (_pageChrome.value != next) {
            _pageChrome.value = next
        }
    }

    @Volatile
    var currentPoToken: String? = null
        private set

    @Volatile
    var currentVisitorData: String? = null
        private set

    @Volatile
    var latestVideoId: String? = null
        private set

    @Volatile
    var latestPlayerResponseJson: String? = null
        private set

    fun updateSessionTokens(poToken: String?, visitorData: String?) {
        val cleanPoToken = poToken?.takeIf { it.isNotBlank() }
        val cleanVisitorData = visitorData?.takeIf { it.isNotBlank() }

        if (cleanPoToken != null) currentPoToken = cleanPoToken
        if (cleanVisitorData != null) currentVisitorData = cleanVisitorData

        _sessionTokens.value = SessionTokens(
            poToken = currentPoToken,
            visitorData = currentVisitorData,
            updatedAt = System.currentTimeMillis()
        )

        Timber.d(
            "WebViewSessionManager: Tokens updated | poToken=%s, visitorData=%s",
            currentPoToken?.take(16)?.plus("...") ?: "none",
            currentVisitorData ?: "none"
        )
    }

    fun updatePlayerResponse(videoId: String, playerJson: String) {
        if (videoId.isBlank() || playerJson.isBlank()) return

        latestVideoId = videoId
        latestPlayerResponseJson = playerJson
        _latestWatchMetadata.value = WatchPageMetadata(videoId, playerJson)

        Timber.d("WebViewSessionManager: Cached watch page metadata for videoId=%s", videoId)
    }

    fun getExtractorArgs(): String? {
        val po = currentPoToken
        val visitor = currentVisitorData

        if (po.isNullOrBlank() && visitor.isNullOrBlank()) return null

        val parts = mutableListOf<String>()
        if (!po.isNullOrBlank()) {
            parts.add("po_token=mweb.gvs+$po")
        }
        if (!visitor.isNullOrBlank()) {
            parts.add("visitor_data=$visitor")
        }
        return "youtube:" + parts.joinToString(";")
    }

    /**
     * Reads live cookies from CookieManager and writes a strictly formatted
     * Netscape HTTP Cookie File for Python's http.cookiejar.MozillaCookieJar.
     */
    fun exportCookiesToNetscapeFile(context: Context): File? {
        return try {
            val cookieManager = CookieManager.getInstance()
            val mYtCookies = cookieManager.getCookie("https://m.youtube.com").orEmpty()
            val ytCookies = cookieManager.getCookie("https://www.youtube.com").orEmpty()

            val cookieMap = mutableMapOf<String, String>()

            fun parseCookies(raw: String) {
                if (raw.isBlank()) return
                raw.split(";").forEach { item ->
                    val trimmed = item.trim()
                    val eqIdx = trimmed.indexOf('=')
                    if (eqIdx > 0) {
                        val key = trimmed.substring(0, eqIdx).trim()
                        val value = trimmed.substring(eqIdx + 1).trim()
                        // Filter out keys or values containing illegal control characters
                        if (key.isNotEmpty() && !key.contains(" ") && !key.contains("\t") && !key.contains("\n") && !value.contains("\n")) {
                            cookieMap[key] = value
                        }
                    }
                }
            }

            parseCookies(ytCookies)
            parseCookies(mYtCookies)

            if (cookieMap.isEmpty()) {
                Timber.w("No valid cookies found in CookieManager for YouTube")
                return null
            }

            val cookiesFile = File(context.noBackupFilesDir, "cookies.txt")
            FileWriter(cookiesFile, false).use { writer ->
                // Exact Python MozillaCookieJar header specification
                writer.write("# Netscape HTTP Cookie File\n")
                writer.write("# https://curl.se/docs/http-cookies.html\n\n")

                // Far future expiration: 1 year from now
                val expiry = (System.currentTimeMillis() / 1000L) + (365L * 24 * 3600)

                for ((name, value) in cookieMap) {
                    // Strict 7 tab-separated columns:
                    // 1: domain (.youtube.com)
                    // 2: flag (TRUE)
                    // 3: path (/)
                    // 4: secure (TRUE)
                    // 5: expiration (epoch seconds)
                    // 6: name
                    // 7: value
                    writer.write(".youtube.com\tTRUE\t/\tTRUE\t$expiry\t$name\t$value\n")
                }
            }

            Timber.i("Exported %d clean Netscape cookies to %s", cookieMap.size, cookiesFile.absolutePath)
            cookiesFile
        } catch (e: Exception) {
            Timber.e(e, "Failed to export Netscape cookies file")
            null
        }
    }
}
