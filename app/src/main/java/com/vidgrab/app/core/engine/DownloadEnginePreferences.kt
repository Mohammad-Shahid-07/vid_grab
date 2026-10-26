package com.vidgrab.app.core.engine

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Global app-wide selection of which native download path is attempted first.
 *
 * There is deliberately no per-download engine prompt: every task reads this single persisted
 * preference. FAST is the default: parallel segmented range downloader (FastDirectDownloader).
 * COMPATIBILITY prefers the legacy NewPipe + sequential range downloader, which is slower
 * on throttled CDNs but survives more edge cases (age-gated clients, rare containers).
 *
 * Whichever mode is selected, the other engine is attempted second before handing the
 * task over to yt-dlp, so a user choice can never make a download strictly worse.
 */
enum class DownloadEngineMode(val displayName: String) {
    FAST("Fast"),
    COMPATIBILITY("Compatibility")
}

object DownloadEnginePreferences {

    private const val PREFS_NAME = "vidgrab_download_engine_prefs"
    private const val KEY_MODE = "download_engine_mode"

    private val _mode = MutableStateFlow(DownloadEngineMode.FAST)
    val mode: StateFlow<DownloadEngineMode> = _mode.asStateFlow()

    fun init(context: Context) {
        _mode.value = read(context)
    }

    fun getMode(context: Context): DownloadEngineMode = read(context)

    fun setMode(context: Context, mode: DownloadEngineMode) {
        _mode.value = mode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
        Timber.i("DownloadEnginePreferences: primary engine set to %s", mode.name)
    }

    private fun read(context: Context): DownloadEngineMode {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MODE, DownloadEngineMode.FAST.name)
        return runCatching { DownloadEngineMode.valueOf(raw.orEmpty()) }
            .getOrDefault(DownloadEngineMode.FAST)
    }
}
