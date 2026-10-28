package com.youdrop.app

import android.app.Application
import android.os.Build
import com.youdrop.app.core.extractor.NewPipeDownloaderImpl
import com.youdrop.app.core.network.NetworkConstants
import com.youdrop.app.core.utils.NativeLibChecker
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.schabi.newpipe.extractor.NewPipe
import timber.log.Timber
import java.util.concurrent.TimeUnit

sealed interface EngineInitState {
    data object Uninitialized : EngineInitState
    data object Initializing : EngineInitState
    data class Ready(val diagnostics: NativeLibChecker.NativeDiagnostics) : EngineInitState
    data class Error(val cause: Throwable, val message: String) : EngineInitState
}

class App : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _engineState = MutableStateFlow<EngineInitState>(EngineInitState.Uninitialized)
    val engineState: StateFlow<EngineInitState> = _engineState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("YouDrop Application starting. API Level: %d, ABI: %s", Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.firstOrNull())

        // Build shared OkHttp client for Tier 2 Fast JVM extraction and general network I/O
        sharedOkHttpClient = OkHttpClient.Builder()
            .connectTimeout(NetworkConstants.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NetworkConstants.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(NetworkConstants.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .apply {
                if (BuildConfig.DEBUG) {
                    val logging = HttpLoggingInterceptor { message -> Timber.tag("OkHttp").d(message) }
                    logging.level = HttpLoggingInterceptor.Level.BASIC
                    addInterceptor(logging)
                }
            }
            .build()

        // Initialize notification channel for foreground downloads
        com.youdrop.app.core.worker.DownloadNotificationManager.createNotificationChannel(this)

        // Pre-warm database, queue repository, and engine preferences
        val queueRepo = com.youdrop.app.core.queue.QueueRepository.getInstance(this)
        queueRepo.resetInterruptedTasks()
        com.youdrop.app.core.engine.YtDlpUpdater.init(this)
        com.youdrop.app.core.engine.DownloadEnginePreferences.init(this)
        com.youdrop.app.core.webview.AdBlockEngine.init(this)

        // Asynchronously initialize native binaries and extraction engines to prevent ANR on cold start
        initEnginesAsync()
    }

    private fun initEnginesAsync() {
        applicationScope.launch {
            _engineState.value = EngineInitState.Initializing
            try {
                Timber.d("Initializing youtubedl-android core...")
                YoutubeDL.getInstance().init(this@App)

                // Launch background OTA update to latest 2026 yt-dlp release
                applicationScope.launch {
                    com.youdrop.app.core.engine.YtDlpUpdater.ensureUpdated(this@App, force = false)
                }

                Timber.d("Initializing FFmpeg binary wrapper...")
                FFmpeg.getInstance().init(this@App)

                Timber.d("Initializing Aria2c binary wrapper...")
                Aria2c.getInstance().init(this@App)

                // Inspect native directory to verify libqjs.so (QuickJS), libpython.so, and libffmpeg.so
                val diagnostics = NativeLibChecker.check(this@App)
                Timber.i(
                    "Native Lib Verification: Python=%b, FFmpeg=%b, Aria2c=%b, QuickJS=%b | Dir=%s",
                    diagnostics.isPythonPresent,
                    diagnostics.isFFmpegPresent,
                    diagnostics.isAria2cPresent,
                    diagnostics.isQuickJSPresent,
                    diagnostics.nativeDir
                )

                // Initialize Tier 2 Fast JVM Extractor (NewPipeExtractor)
                Timber.d("Initializing NewPipeExtractor with OkHttp Downloader...")
                val newPipeDownloader = NewPipeDownloaderImpl.create(sharedOkHttpClient)
                NewPipe.init(newPipeDownloader)

                _engineState.value = EngineInitState.Ready(diagnostics)
                Timber.i("All media extraction and muxing engines successfully initialized.")

            } catch (e: YoutubeDLException) {
                Timber.e(e, "Fatal error initializing youtubedl-android binaries")
                _engineState.value = EngineInitState.Error(e, "Failed to initialize native extraction engines: ${e.message}")
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error during engine initialization")
                _engineState.value = EngineInitState.Error(e, "Engine initialization failed: ${e.message}")
            }
        }
    }

    companion object {
        lateinit var instance: App
            private set

        lateinit var sharedOkHttpClient: OkHttpClient
            private set
    }
}
