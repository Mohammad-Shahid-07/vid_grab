package com.vidgrab.app.core.extractor

import java.util.UUID

enum class ResolutionSource(val displayName: String) {
    TIER_1_WEBVIEW_MEMORY("Tier 1: WebView Memory (<50ms)"),
    TIER_2_NEWPIPE_JVM("Tier 2: Warm JVM (~300ms)"),
    TIER_3_SMART_PRESET("Tier 3: Smart Preset Fallback")
}

data class VideoQualityOption(
    val height: Int,
    val label: String,
    val fps: Int = 30,
    val vcodec: String = "H.264",
    val container: String = "mp4",
    val estimatedTotalBytes: Long = 0L,
    val formatSelector: String
) {
    val formattedSize: String
        get() = formatBytes(estimatedTotalBytes)
}

data class AudioQualityOption(
    val label: String,
    val extension: String,
    val acodec: String,
    val bitrateKbps: Int,
    val estimatedBytes: Long = 0L,
    val formatSelector: String
) {
    val formattedSize: String
        get() = formatBytes(estimatedBytes)
}

data class BatchPlaylistItem(
    val url: String,
    val title: String,
    val author: String = "",
    val durationSeconds: Long = 0L,
    val thumbnailUrl: String? = null,
    val isSelected: Boolean = true
)

sealed interface ResolvedMediaInfo {
    val title: String
    val url: String
    val source: ResolutionSource
    val resolutionTimeMs: Long
    val videoOptions: List<VideoQualityOption>
    val audioOptions: List<AudioQualityOption>
    val thumbnailUrl: String?

    data class SingleVideo(
        val videoId: String,
        override val url: String,
        override val title: String,
        val author: String,
        val durationSeconds: Long,
        override val thumbnailUrl: String?,
        override val videoOptions: List<VideoQualityOption>,
        override val audioOptions: List<AudioQualityOption>,
        val sponsorSegments: List<Pair<Long, Long>> = emptyList(),
        override val source: ResolutionSource,
        override val resolutionTimeMs: Long
    ) : ResolvedMediaInfo

    data class PlaylistOrChannel(
        val id: String,
        override val url: String,
        override val title: String,
        val author: String,
        override val thumbnailUrl: String?,
        val isPlaylist: Boolean,
        val totalItemCount: Int,
        val items: List<BatchPlaylistItem>,
        override val videoOptions: List<VideoQualityOption>,
        override val audioOptions: List<AudioQualityOption>,
        override val source: ResolutionSource,
        override val resolutionTimeMs: Long
    ) : ResolvedMediaInfo

    data class FallbackPreset(
        override val url: String,
        override val title: String,
        val itemType: String,
        override val thumbnailUrl: String? = null,
        override val videoOptions: List<VideoQualityOption>,
        override val audioOptions: List<AudioQualityOption>,
        override val source: ResolutionSource = ResolutionSource.TIER_3_SMART_PRESET,
        override val resolutionTimeMs: Long = 0L
    ) : ResolvedMediaInfo
}

/**
 * Immutable download request contract to be queued into Room DB & WorkManager in Milestone 4.
 */
data class DownloadRequestSpec(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val author: String = "YouTube",
    val mediaType: String, // "VIDEO", "AUDIO", "PLAYLIST", "CHANNEL"
    val targetHeight: Int? = null,
    val formatSelector: String,
    val outputExtension: String = "mp4",
    val expectedSizeBytes: Long = 0L,
    val isBatch: Boolean = false,
    val batchUrls: List<String> = emptyList(),
    val batchItems: List<BatchPlaylistItem> = emptyList(),
    val cutSponsor: Boolean = false,
    val sponsorSegments: List<Pair<Long, Long>> = emptyList(),
    val embedSubtitles: Boolean = false,
    val embedChapters: Boolean = false,
    val poToken: String? = null,
    val visitorData: String? = null,
    val enableAria2c: Boolean = false,
    val concurrentFragments: Int = 8,
    val createdAt: Long = System.currentTimeMillis()
)

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "Unknown size"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format("%.2f GB", gb)
        mb >= 1.0 -> String.format("%.1f MB", mb)
        else -> String.format("%.0f KB", kb)
    }
}
