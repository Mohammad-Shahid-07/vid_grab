package com.vidgrab.app.core.engine

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

data class YtDlpUpdateState(
    val version: String? = null,
    val isUpdating: Boolean = false,
    val lastUpdatedEpochMs: Long = 0L,
    val statusMessage: String = "Ready"
)

object YtDlpUpdater {

    private const val PREFS_NAME = "vidgrab_engine_prefs"
    private const val KEY_LAST_UPDATED = "last_updated_epoch_ms"
    private const val KEY_CACHED_VERSION = "cached_version"
    private const val THREE_DAYS_MS = 3L * 24 * 3600 * 1000L

    private val updateMutex = Mutex()
    private val _updateState = MutableStateFlow(YtDlpUpdateState())
    val updateState: StateFlow<YtDlpUpdateState> = _updateState.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUpdated = prefs.getLong(KEY_LAST_UPDATED, 0L)
        val cachedVer = prefs.getString(KEY_CACHED_VERSION, null)
        val currentVer = try {
            YoutubeDL.getInstance().version(context.applicationContext)
        } catch (e: Exception) {
            cachedVer
        }

        val hasValidVersion = !currentVer.isNullOrBlank()

        _updateState.value = YtDlpUpdateState(
            version = currentVer ?: cachedVer,
            isUpdating = false,
            lastUpdatedEpochMs = lastUpdated,
            statusMessage = if (hasValidVersion && lastUpdated > 0L) "Up to date ($currentVer)" else "Ready (${currentVer ?: "Installed"})"
        )
        Timber.i("YtDlpUpdater: Initialized with version: %s (lastUpdated=%d)", currentVer, lastUpdated)
    }

    suspend fun ensureUpdated(context: Context, force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastUpdated = prefs.getLong(KEY_LAST_UPDATED, 0L)
            val now = System.currentTimeMillis()

            val currentVer = try {
                YoutubeDL.getInstance().version(appContext)
            } catch (e: Exception) {
                null
            }

            val hasValidVersion = !currentVer.isNullOrBlank()
            val isOld = lastUpdated == 0L || (now - lastUpdated) > THREE_DAYS_MS
            val needsUpdate = force || !hasValidVersion || isOld

            Timber.i("YtDlpUpdater: Version check - current='%s', force=%b, needsUpdate=%b", currentVer, force, needsUpdate)

            if (!needsUpdate) {
                _updateState.value = _updateState.value.copy(
                    version = currentVer ?: _updateState.value.version,
                    lastUpdatedEpochMs = lastUpdated,
                    statusMessage = "Up to date ($currentVer)"
                )
                return@withContext true
            }

            _updateState.value = _updateState.value.copy(
                isUpdating = true,
                statusMessage = "Updating yt-dlp..."
            )
            Timber.i("YtDlpUpdater: Starting OTA update pipeline (current='%s')...", currentVer)

            var success = false
            var finalVersion: String? = null

            // Step 1: Attempt NIGHTLY update channel
            try {
                Timber.d("YtDlpUpdater: Running updateYoutubeDL(NIGHTLY)...")
                val status = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                    YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.NIGHTLY)
                }
                Timber.i("YtDlpUpdater: NIGHTLY update returned status: %s", status)
                finalVersion = YoutubeDL.getInstance().version(appContext)
                Timber.i("YtDlpUpdater: Post-NIGHTLY version: %s", finalVersion)

                if (!finalVersion.isNullOrBlank() &&
                    (status == YoutubeDL.UpdateStatus.DONE || status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE)) {
                    success = true
                }
            } catch (e: Exception) {
                Timber.w(e, "YtDlpUpdater: NIGHTLY update failed, trying STABLE fallback...")
            }

            // Step 2: Fallback to STABLE update channel if NIGHTLY didn't yield a binary
            if (!success) {
                try {
                    Timber.d("YtDlpUpdater: Running updateYoutubeDL(STABLE)...")
                    val status = kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                        YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
                    }
                    Timber.i("YtDlpUpdater: STABLE update returned status: %s", status)
                    finalVersion = YoutubeDL.getInstance().version(appContext)
                    Timber.i("YtDlpUpdater: Post-STABLE version: %s", finalVersion)

                    if (!finalVersion.isNullOrBlank() &&
                        (status == YoutubeDL.UpdateStatus.DONE || status == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE)) {
                        success = true
                    }
                } catch (stableEx: Exception) {
                    Timber.e(stableEx, "YtDlpUpdater: STABLE update fallback failed")
                }
            }

            val resolvedVer = finalVersion ?: currentVer
            if (!resolvedVer.isNullOrBlank()) {
                val updatedEpoch = System.currentTimeMillis()
                prefs.edit()
                    .putLong(KEY_LAST_UPDATED, updatedEpoch)
                    .putString(KEY_CACHED_VERSION, resolvedVer)
                    .apply()

                _updateState.value = YtDlpUpdateState(
                    version = resolvedVer,
                    isUpdating = false,
                    lastUpdatedEpochMs = updatedEpoch,
                    statusMessage = "Up to date ($resolvedVer)"
                )
                Timber.i("YtDlpUpdater: Verified yt-dlp version: %s", resolvedVer)
                return@withContext true
            } else {
                Timber.e("YtDlpUpdater: Update did not yield a valid version (version='%s')", resolvedVer)
                _updateState.value = _updateState.value.copy(
                    version = resolvedVer,
                    isUpdating = false,
                    statusMessage = "Update status: ${resolvedVer ?: "Failed"}"
                )
                return@withContext false
            }
        }
    }

    fun isUpdateInProgress(): Boolean = _updateState.value.isUpdating

    fun isUpdatedTo2026(context: Context): Boolean {
        val v = try { YoutubeDL.getInstance().version(context.applicationContext) } catch (e: Exception) { null }
        return v?.startsWith("2026") == true
    }
}
