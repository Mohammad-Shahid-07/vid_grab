package com.vidgrab.app.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class TaskStatus {
    PENDING,
    EXPANDING,
    DOWNLOADING,
    MERGING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Entity(
    tableName = "download_tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["playlistId"]),
        Index(value = ["createdAt"])
    ]
)
data class DownloadTaskEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val videoId: String? = null,
    val title: String,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val isAudioOnly: Boolean = false,
    val targetHeight: Int? = null,
    val formatSelector: String,
    val outputExt: String = "mp4",
    val useAria2c: Boolean = false,
    val playlistId: String? = null,
    val playlistTitle: String? = null,
    val playlistIndex: Int? = null,
    val status: TaskStatus = TaskStatus.PENDING,
    val progressPercent: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedText: String = "",
    val etaSeconds: Long = -1L,
    val outputFileUri: String? = null,
    val outputFilePath: String? = null,
    val errorMessage: String? = null,
    val cutSponsor: Boolean = false,
    val sponsorSegmentsJson: String? = null,
    val embedSubtitles: Boolean = false,
    val embedChapters: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
