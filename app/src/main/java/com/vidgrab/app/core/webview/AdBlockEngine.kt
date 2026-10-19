package com.vidgrab.app.core.webview

import android.content.Context
import android.webkit.WebResourceResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.ByteArrayInputStream

object AdBlockEngine {

    private const val PREFS_NAME = "vidgrab_browser_prefs"
    private const val KEY_AD_BLOCK = "is_ad_block_enabled"
    private const val KEY_BG_PLAY = "is_background_play_enabled"
    private const val KEY_MAX_CONCURRENT = "max_concurrent_downloads"

    private val _isAdBlockEnabled = MutableStateFlow(true)
    val isAdBlockEnabled: StateFlow<Boolean> = _isAdBlockEnabled.asStateFlow()

    private val _isBackgroundPlayEnabled = MutableStateFlow(true)
    val isBackgroundPlayEnabled: StateFlow<Boolean> = _isBackgroundPlayEnabled.asStateFlow()

    private val _maxConcurrentDownloads = MutableStateFlow(2)
    val maxConcurrentDownloads: StateFlow<Int> = _maxConcurrentDownloads.asStateFlow()

    private val blockedPatterns = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "ad.doubleclick.net",
        "static.doubleclick.net",
        "googleads.g.doubleclick.net",
        "googleads",
        "innovid.com",
        "2mdn.net",
        "youtube.com/api/stats/ads",
        "youtube.com/pagead/",
        "youtube.com/ptracking",
        "youtube.com/get_midroll_info",
        "ads.youtube.com",
        "adm.youtube.com",
        "google-analytics.com"
    )

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isAdBlockEnabled.value = prefs.getBoolean(KEY_AD_BLOCK, true)
        _isBackgroundPlayEnabled.value = prefs.getBoolean(KEY_BG_PLAY, true)
        _maxConcurrentDownloads.value = prefs.getInt(KEY_MAX_CONCURRENT, 2).coerceIn(1, 5)
    }

    fun setAdBlockEnabled(context: Context, enabled: Boolean) {
        _isAdBlockEnabled.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AD_BLOCK, enabled)
            .apply()
        Timber.i("AdBlockEngine: isAdBlockEnabled set to %b", enabled)
    }

    fun setBackgroundPlayEnabled(context: Context, enabled: Boolean) {
        _isBackgroundPlayEnabled.value = enabled
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BG_PLAY, enabled)
            .apply()
        Timber.i("AdBlockEngine: isBackgroundPlayEnabled set to %b", enabled)
    }

    fun setMaxConcurrentDownloads(context: Context, count: Int) {
        val clamped = count.coerceIn(1, 5)
        _maxConcurrentDownloads.value = clamped
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MAX_CONCURRENT, clamped)
            .apply()
        Timber.i("AdBlockEngine: maxConcurrentDownloads set to %d", clamped)
    }

    fun shouldBlockRequest(url: String): Boolean {
        if (!_isAdBlockEnabled.value) return false
        val lower = url.lowercase()
        return blockedPatterns.any { lower.contains(it) }
    }

    fun createEmptyResourceResponse(): WebResourceResponse {
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Content-Type" to "text/plain; charset=utf-8"
        )
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "No Content",
            headers,
            ByteArrayInputStream(ByteArray(0))
        )
    }
}
