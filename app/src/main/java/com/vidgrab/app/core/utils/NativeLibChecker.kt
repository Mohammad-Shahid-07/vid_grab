package com.vidgrab.app.core.utils

import android.content.Context
import java.io.File

/**
 * Diagnostic utility that verifies that native binaries required by youtubedl-android
 * and yt-dlp have been unpacked uncompressed into nativeLibraryDir as required
 * by Android 10+ W^X SELinux execution policies.
 */
object NativeLibChecker {

    data class NativeDiagnostics(
        val nativeDir: String,
        val isPythonPresent: Boolean,
        val isFFmpegPresent: Boolean,
        val isAria2cPresent: Boolean,
        val isQuickJSPresent: Boolean,
        val allCriticalLibsPresent: Boolean,
        val foundLibraries: List<String>
    )

    fun check(context: Context): NativeDiagnostics {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val files = nativeDir.listFiles()?.map { it.name } ?: emptyList()

        val isPythonPresent = files.any { it.startsWith("libpython") && it.endsWith(".so") }
        val isFFmpegPresent = files.contains("libffmpeg.so")
        val isAria2cPresent = files.contains("libaria2c.so")
        // yt-dlp requires libqjs.so for --js-runtimes quickjs in 2026
        val isQuickJSPresent = files.contains("libqjs.so") || files.any { it.contains("qjs") }

        // Critical minimum: Python, FFmpeg, and QuickJS
        val allCritical = isPythonPresent && isFFmpegPresent && isQuickJSPresent

        return NativeDiagnostics(
            nativeDir = nativeDir.absolutePath,
            isPythonPresent = isPythonPresent,
            isFFmpegPresent = isFFmpegPresent,
            isAria2cPresent = isAria2cPresent,
            isQuickJSPresent = isQuickJSPresent,
            allCriticalLibsPresent = allCritical,
            foundLibraries = files
        )
    }
}
