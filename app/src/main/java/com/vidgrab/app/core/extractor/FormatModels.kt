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
    val videoOnlyBytes: Long = 0L,
    val audioStreamBytes: Long = 0L,
    val estimatedTotalBytes: Long = 0L,
    val formatSelector: String,
    val directStreamUrl: String? = null,
    val matchedAudioStreamUrl: String? = null,
    val isMuxed: Boolean = false
) {
    val formattedSize: String
        get() = formatBytes(if (estimatedTotalBytes > 0L) estimatedTotalBytes else (videoOnlyBytes + audioStreamBytes))
}

data class AudioQualityOption(
    val label: String,
    val extension: String,
    val acodec: String,
    val bitrateKbps: Int,
    val estimatedBytes: Long = 0L,
    val formatSelector: String,
    val directStreamUrl: String? = null,
    /** YouTube audio-track identifier (e.g. "en-US.4"); null for legacy single-track responses. */
    val audioTrackId: String? = null,
    /** Human readable track name (e.g. "English (US) original"). */
    val audioTrackName: String? = null,
    /** ISO language code when known. */
    val audioLanguage: String? = null,
    /** NewPipe's AudioTrackType name (ORIGINAL, DUBBED, DESCRIPTIVE, SECONDARY). */
    val audioTrackType: String? = null,
    /** True for YouTube's default / original audio track. */
    val isDefaultTrack: Boolean = false
) {
    val formattedSize: String
        get() = formatBytes(estimatedBytes)

    /** Stable key for grouping multiple bitrates of the same physical audio track. */
    val trackKey: String
        get() = audioTrackId?.takeIf { it.isNotBlank() }
            ?: audioTrackName?.takeIf { it.isNotBlank() }
            ?: "default"

    /**
     * Identifies one concrete audio stream (track + container + bitrate). Used by the picker so a
     * user can mux any specific audio stream into any video stream, IDM style.
     */
    val streamKey: String
        get() = "$trackKey|${extension.lowercase()}|$bitrateKbps"

    /**
     * True when this is the uploader's original/default track. Some player responses omit the
     * audioTrack object for the original track, so the absence of a track id is also treated
     * as original unless a track name explicitly says otherwise.
     */
    val isOriginalOrDefault: Boolean
        get() = isDefaultTrack ||
            audioTrackType.equals("ORIGINAL", ignoreCase = true) ||
            audioTrackName?.contains("original", ignoreCase = true) == true ||
            (audioTrackId == null && audioTrackName == null)
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
    val durationSeconds: Long

    data class SingleVideo(
        val videoId: String,
        override val url: String,
        override val title: String,
        val author: String,
        override val durationSeconds: Long,
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
    ) : ResolvedMediaInfo {
        override val durationSeconds: Long
            get() = items.filter { it.isSelected }.sumOf { it.durationSeconds }
    }

    data class FallbackPreset(
        override val url: String,
        override val title: String,
        val itemType: String,
        override val durationSeconds: Long = 0L,
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
    val thumbnailUrl: String? = null,
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
    val downloadFolder: String? = null,
    val poToken: String? = null,
    val visitorData: String? = null,
    val enableAria2c: Boolean = false,
    val concurrentFragments: Int = 8,
    val resolvedVideoStreamUrl: String? = null,
    val resolvedAudioStreamUrl: String? = null,
    val directVideoUrl: String? = resolvedVideoStreamUrl,
    val directAudioUrl: String? = resolvedAudioStreamUrl,
    val isDirectMuxed: Boolean = false,
    /** Preferred video codec family ("H.264", "VP9", "AV1", or null for Auto). */
    val videoCodecPreference: String? = null,
    /** Exact audio track id to mux with the video (null = automatic/default). */
    val mergeAudioTrackId: String? = null,
    val mergeAudioTrackName: String? = null,
    val mergeAudioLanguage: String? = null,
    /** Preferred container for the muxed audio stream ("m4a", "opus", or null for Auto). */
    val mergeAudioExtension: String? = null,
    /** Exact bitrate of the chosen merge-audio stream, when the user picked one. */
    val mergeAudioBitrateKbps: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
        else -> String.format(java.util.Locale.US, "%.0f KB", kb)
    }
}
