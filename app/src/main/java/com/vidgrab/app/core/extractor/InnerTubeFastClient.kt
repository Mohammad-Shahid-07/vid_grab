package com.vidgrab.app.core.extractor

import android.webkit.CookieManager
import com.vidgrab.app.App
import com.vidgrab.app.core.webview.WebViewSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * High-Speed InnerTube API Client (<250ms).
 * Directly invokes /youtubei/v1/player to extract unthrottled streamingData with direct URLs,
 * bypassing heavy HTML scraping, NewPipe cold starts, and Python yt-dlp initialization.
 *
 * Implements 2026 multi-client fallback strategy (VISIONOS -> IOS -> ANDROID)
 * to avoid 60s playback cutoff from ANDROID_VR and DroidGuard 403 blocks.
 */
object InnerTubeFastClient {

    private const val INNERTUBE_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val fastHttpClient by lazy {
        App.sharedOkHttpClient.newBuilder()
            .callTimeout(2500, TimeUnit.MILLISECONDS)
            .connectTimeout(2000, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .build()
    }

    private data class InnerTubeClientConfig(
        val name: String,
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val clientNumberHeader: String,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val androidSdkVersion: Int? = null,
        val supportsWebCookies: Boolean = false
    )

    private val clientStrategies = listOf(
        // 1. ANDROID (Native mobile stream: direct, unthrottled, un-ciphered URLs)
        InnerTubeClientConfig(
            name = "ANDROID",
            clientName = "ANDROID",
            clientVersion = "19.44.38",
            userAgent = "com.google.android.youtube/19.44.38 (Linux; U; Android 15) gzip",
            clientNumberHeader = "3",
            androidSdkVersion = 35,
            osName = "Android",
            osVersion = "15",
            supportsWebCookies = false
        ),
        // 2. IOS (Clean raw MP4/AAC streams)
        InnerTubeClientConfig(
            name = "IOS",
            clientName = "IOS",
            clientVersion = "19.45.4",
            userAgent = "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)",
            clientNumberHeader = "5",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iPhone",
            osVersion = "18.1.0.22B83",
            supportsWebCookies = false
        ),
        // 3. VISIONOS (Clean web fallback)
        InnerTubeClientConfig(
            name = "VISIONOS",
            clientName = "VISIONOS",
            clientVersion = "0.1",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15",
            clientNumberHeader = "101",
            supportsWebCookies = true
        )
    )

    suspend fun fetchPlayerResponse(videoId: String): JSONObject? = withContext(Dispatchers.IO) {
        val poToken = WebViewSessionManager.currentPoToken
        val visitorData = WebViewSessionManager.currentVisitorData
        val webCookies = try {
            CookieManager.getInstance().getCookie("https://www.youtube.com")
        } catch (e: Exception) {
            null
        }

        for (config in clientStrategies) {
            val startTime = System.currentTimeMillis()
            try {
                val payload = buildRequestPayload(videoId, config, poToken, visitorData)
                val requestBuilder = Request.Builder()
                    .url(INNERTUBE_PLAYER_URL)
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .header("User-Agent", config.userAgent)
                    .header("Content-Type", "application/json")
                    .header("X-YouTube-Client-Name", config.clientNumberHeader)
                    .header("X-YouTube-Client-Version", config.clientVersion)

                // CRITICAL: NEVER attach MWEB browser cookies to ANDROID or IOS mobile clients
                if (config.supportsWebCookies && !webCookies.isNullOrBlank()) {
                    requestBuilder.header("Cookie", webCookies)
                }

                fastHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - startTime
                    if (!response.isSuccessful) {
                        Timber.d("InnerTubeFastClient [%s]: HTTP %d in %d ms", config.name, response.code, elapsed)
                        return@use
                    }

                    val bodyStr = response.body?.string() ?: return@use
                    val json = JSONObject(bodyStr)

                    // Validate playability
                    val playability = json.optJSONObject("playabilityStatus")
                    val status = playability?.optString("status")
                    if (status != "OK") {
                        Timber.d("InnerTubeFastClient [%s]: Playability status = %s in %d ms", config.name, status, elapsed)
                        return@use
                    }

                    if (hasDirectStreamUrls(json)) {
                        Timber.i("InnerTubeFastClient [%s]: Successfully resolved direct streams for %s in %d ms!",
                            config.name, videoId, elapsed)
                        return@withContext json
                    } else {
                        Timber.d("InnerTubeFastClient [%s]: Formats contain signatureCipher or missing direct googlevideo URLs, trying next client...", config.name)
                    }
                }
            } catch (e: Exception) {
                Timber.d(e, "InnerTubeFastClient [%s]: Request failed for %s", config.name, videoId)
            }
        }

        null
    }

    private fun hasDirectStreamUrls(root: JSONObject): Boolean {
        val streamingData = root.optJSONObject("streamingData") ?: return false
        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            ?: streamingData.optJSONArray("formats")
            ?: return false

        var hasDirectVideo = false
        var hasDirectAudio = false

        for (i in 0 until adaptiveFormats.length()) {
            val fmt = adaptiveFormats.optJSONObject(i) ?: continue
            val directUrl = fmt.optString("url")
            if (directUrl.isNotBlank() && directUrl.contains("googlevideo.com")) {
                val mime = fmt.optString("mimeType")
                if (mime.startsWith("video/")) hasDirectVideo = true
                if (mime.startsWith("audio/")) hasDirectAudio = true
                // Combined formats (muxed audio+video in 'formats')
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) {
                    hasDirectVideo = true
                    hasDirectAudio = true
                }
            }
        }

        return (hasDirectVideo && hasDirectAudio) || (hasDirectAudio && adaptiveFormats.length() > 0)
    }

    private fun buildRequestPayload(
        videoId: String,
        config: InnerTubeClientConfig,
        poToken: String?,
        visitorData: String?
    ): JSONObject {
        val clientObj = JSONObject().apply {
            put("clientName", config.clientName)
            put("clientVersion", config.clientVersion)
            put("hl", "en")
            put("gl", "US")
            if (!visitorData.isNullOrBlank()) {
                put("visitorData", visitorData)
            }
            if (config.deviceMake != null) {
                put("deviceMake", config.deviceMake)
            }
            if (config.deviceModel != null) {
                put("deviceModel", config.deviceModel)
            }
            if (config.osName != null) {
                put("osName", config.osName)
            }
            if (config.osVersion != null) {
                put("osVersion", config.osVersion)
            }
            if (config.androidSdkVersion != null) {
                put("androidSdkVersion", config.androidSdkVersion)
            }
        }

        val contextObj = JSONObject().apply {
            put("client", clientObj)
        }

        val root = JSONObject().apply {
            put("context", contextObj)
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        // Only attach MWEB poToken if client supports web cookies/session
        if (config.supportsWebCookies && !poToken.isNullOrBlank()) {
            val integrityObj = JSONObject().apply {
                put("poToken", poToken)
            }
            root.put("serviceIntegrityDimensions", integrityObj)
        }

        return root
    }
}
