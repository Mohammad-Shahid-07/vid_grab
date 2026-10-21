package com.vidgrab.app.core.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class ExportResult(
    val contentUri: String,
    val displayPath: String
)

/**
 * Manages the 2-stage Android 10–16 Scoped Storage Pipeline:
 * Stage 1: App-private sandbox directory (File(externalFilesDir, "work_queue/$taskId"))
 *          where yt-dlp, FFmpeg, and aria2c can freely write, resume .part files, and mux.
 * Stage 2: MediaStore public export to /storage/emulated/0/Download/VidGrab with IS_PENDING = 0,
 *          MediaScannerConnection indexing, and fail-safe filesystem fallback.
 */
object MediaStoreExporter {

    fun getTaskWorkDir(context: Context, taskId: String): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "work_queue/$taskId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun cleanupTaskWorkDir(context: Context, taskId: String) {
        try {
            val dir = getTaskWorkDir(context, taskId)
            if (dir.exists()) {
                dir.deleteRecursively()
                Timber.d("Cleaned up sandbox work directory for taskId: %s", taskId)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to clean sandbox dir for taskId: %s", taskId)
        }
    }

    /**
     * Recursively locates the largest completed media file, filtering out control/partial files.
     */
    fun findFinishedMediaFile(workDir: File): File? {
        val validExtensions = setOf("mp4", "mkv", "webm", "m4a", "mp3", "opus", "flv", "ts")

        val files = workDir.walkTopDown()
            .filter { file ->
                file.isFile &&
                        !file.name.endsWith(".part") &&
                        !file.name.endsWith(".ytdl") &&
                        !file.name.endsWith(".aria2") &&
                        !file.name.endsWith(".json") &&
                        !file.name.endsWith(".txt") &&
                        file.extension.lowercase() in validExtensions
            }
            .toList()

        return files.maxByOrNull { it.length() }
    }

    fun exportToPublicDownloads(
        context: Context,
        sourceFile: File,
        title: String,
        isAudioOnly: Boolean,
        fallbackExt: String,
        targetFolder: String? = null
    ): ExportResult {
        // Detect actual extension produced by yt-dlp (e.g. .mkv or .webm if mp4 merge failed)
        val actualExt = sourceFile.extension.ifBlank { fallbackExt }
        val sanitizedTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120)
        val extSuffix = if (actualExt.isNotBlank()) ".$actualExt" else ""

        val effectiveFolder = targetFolder?.trim('/')?.takeIf { it.isNotBlank() } ?: (if (isAudioOnly) "Music/YouDrop" else "Download/YouDrop")
        val baseDirType = if (effectiveFolder.startsWith("Music", ignoreCase = true)) {
            Environment.DIRECTORY_MUSIC
        } else if (effectiveFolder.startsWith("Movies", ignoreCase = true)) {
            Environment.DIRECTORY_MOVIES
        } else {
            Environment.DIRECTORY_DOWNLOADS
        }
        val subName = effectiveFolder.substringAfter('/', "YouDrop")

        val publicDir = File(Environment.getExternalStoragePublicDirectory(baseDirType), subName)
        if (!publicDir.exists()) publicDir.mkdirs()

        var fileName = "$sanitizedTitle$extSuffix"
        var counter = 1
        while (File(publicDir, fileName).exists()) {
            fileName = "$sanitizedTitle ($counter)$extSuffix"
            counter++
        }

        val mimeType = resolveMimeType(actualExt, isAudioOnly)
        val relativeSubDir = "$baseDirType/$subName"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeSubDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val collectionUri = if (baseDirType == Environment.DIRECTORY_MUSIC) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else if (baseDirType == Environment.DIRECTORY_MOVIES) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }

                val itemUri = resolver.insert(collectionUri, contentValues)
                    ?: throw IllegalStateException("Failed to create MediaStore entry in $relativeSubDir")

                resolver.openOutputStream(itemUri)?.use { outStream ->
                    FileInputStream(sourceFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                } ?: throw IllegalStateException("Could not open output stream for MediaStore URI: $itemUri")

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)

                val displayPath = "/storage/emulated/0/$relativeSubDir/$fileName"
                MediaScannerConnection.scanFile(context, arrayOf(displayPath), arrayOf(mimeType), null)
                Timber.i("Successfully exported to MediaStore: %s -> %s", fileName, itemUri)

                return ExportResult(
                    contentUri = itemUri.toString(),
                    displayPath = displayPath
                )
            } catch (e: Exception) {
                Timber.w(e, "MediaStore export failed; executing fail-safe legacy export")
                return exportViaDirectFilesystem(context, sourceFile, fileName, mimeType, publicDir)
            }
        } else {
            return exportViaDirectFilesystem(context, sourceFile, fileName, mimeType, publicDir)
        }
    }

    private fun exportViaDirectFilesystem(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String,
        publicDir: File
    ): ExportResult {
        if (!publicDir.exists()) publicDir.mkdirs()

        var targetFile = File(publicDir, fileName)
        if (targetFile.exists()) {
            val baseName = targetFile.nameWithoutExtension
            val ext = targetFile.extension.let { if (it.isNotBlank()) ".$it" else "" }
            var counter = 1
            while (targetFile.exists()) {
                targetFile = File(publicDir, "$baseName ($counter)$ext")
                counter++
            }
        }

        FileInputStream(sourceFile).use { inStream ->
            FileOutputStream(targetFile).use { outStream ->
                inStream.copyTo(outStream)
            }
        }

        MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf(mimeType), null)
        val uri = Uri.fromFile(targetFile).toString()

        Timber.i("Exported via direct filesystem: %s", targetFile.absolutePath)
        return ExportResult(
            contentUri = uri,
            displayPath = targetFile.absolutePath
        )
    }

    private fun resolveMimeType(ext: String, isAudioOnly: Boolean): String {
        return when (ext.lowercase()) {
            "mp4" -> if (isAudioOnly) "audio/mp4" else "video/mp4"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/ogg"
            "mp3" -> "audio/mpeg"
            "webm" -> if (isAudioOnly) "audio/webm" else "video/webm"
            "mkv" -> "video/x-matroska"
            else -> if (isAudioOnly) "audio/*" else "video/*"
        }
    }
}
