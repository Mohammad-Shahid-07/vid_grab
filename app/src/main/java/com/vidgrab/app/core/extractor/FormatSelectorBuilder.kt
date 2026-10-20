package com.vidgrab.app.core.extractor

/**
 * Generates yt-dlp -f format selector expressions guaranteeing:
 * 1. H.264 (avc1) + M4A (mp4a) preference for universal hardware MP4 compatibility and fast FFmpeg -c copy.
 * 2. Automatic fallback to VP9/AV1 + Opus if H.264 is unavailable at the chosen resolution.
 * 3. Graceful downgrade to the next highest available resolution (height<=H) if the specific quality is absent.
 */
object FormatSelectorBuilder {

    /**
     * Builds the yt-dlp video format selector for a given max height constraint.
     */
    fun buildVideoSelector(height: Int): String {
        return "bestvideo[height<=$height][vcodec^=avc1]+bestaudio[acodec^=mp4a]/" +
                "bestvideo[height<=$height][ext=mp4]+bestaudio[ext=m4a]/" +
                "bestvideo[height<=$height]+bestaudio/" +
                "best[height<=$height]/best"
    }

    /**
     * Builds the yt-dlp audio-only format selector.
     */
    fun buildAudioSelector(extension: String): String {
        return when (extension.lowercase()) {
            "m4a", "aac" -> "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio/best"
            "opus", "webm" -> "bestaudio[ext=webm]/bestaudio[acodec^=opus]/bestaudio/best"
            else -> "bestaudio/best"
        }
    }

    /**
     * Standard 2026 resolution presets for Tier 3 fallback and default displays.
     */
    fun buildStandardPresets(estimatedDurationSec: Long = 0L): Pair<List<VideoQualityOption>, List<AudioQualityOption>> {
        val dur = if (estimatedDurationSec > 0L) estimatedDurationSec else 180L
        val videoPresets = listOf(
            VideoQualityOption(
                height = 1080,
                label = "1080p Full HD",
                fps = 60,
                vcodec = "H.264",
                container = "mp4",
                estimatedTotalBytes = ((2500000L + 128000L) * dur) / 8L,
                formatSelector = buildVideoSelector(1080)
            ),
            VideoQualityOption(
                height = 720,
                label = "720p HD",
                fps = 60,
                vcodec = "H.264",
                container = "mp4",
                estimatedTotalBytes = ((1200000L + 128000L) * dur) / 8L,
                formatSelector = buildVideoSelector(720)
            ),
            VideoQualityOption(
                height = 480,
                label = "480p Standard",
                fps = 30,
                vcodec = "H.264",
                container = "mp4",
                estimatedTotalBytes = ((650000L + 128000L) * dur) / 8L,
                formatSelector = buildVideoSelector(480)
            ),
            VideoQualityOption(
                height = 360,
                label = "360p Medium",
                fps = 30,
                vcodec = "H.264",
                container = "mp4",
                estimatedTotalBytes = ((400000L + 128000L) * dur) / 8L,
                formatSelector = buildVideoSelector(360)
            ),
            VideoQualityOption(
                height = 240,
                label = "240p Compact",
                fps = 30,
                vcodec = "H.264",
                container = "mp4",
                estimatedTotalBytes = ((220000L + 128000L) * dur) / 8L,
                formatSelector = buildVideoSelector(240)
            ),
            VideoQualityOption(
                height = 144,
                label = "144p Data Saver",
                fps = 30,
                vcodec = "H.264",
                container = "mp4",
                estimatedTotalBytes = ((110000L + 128000L) * dur) / 8L,
                formatSelector = buildVideoSelector(144)
            )
        )

        val audioPresets = listOf(
            AudioQualityOption(
                label = "M4A High Quality (160k AAC)",
                extension = "m4a",
                acodec = "AAC",
                bitrateKbps = 160,
                estimatedBytes = (160000L * dur) / 8L,
                formatSelector = buildAudioSelector("m4a")
            ),
            AudioQualityOption(
                label = "M4A Standard (128k AAC)",
                extension = "m4a",
                acodec = "AAC",
                bitrateKbps = 128,
                estimatedBytes = (128000L * dur) / 8L,
                formatSelector = buildAudioSelector("m4a")
            ),
            AudioQualityOption(
                label = "Opus High Efficiency (160k)",
                extension = "opus",
                acodec = "Opus",
                bitrateKbps = 160,
                estimatedBytes = (160000L * dur) / 8L,
                formatSelector = buildAudioSelector("opus")
            ),
            AudioQualityOption(
                label = "Opus Compact (70k)",
                extension = "opus",
                acodec = "Opus",
                bitrateKbps = 70,
                estimatedBytes = (70000L * dur) / 8L,
                formatSelector = buildAudioSelector("opus")
            ),
            AudioQualityOption(
                label = "MP3 Universal (192k)",
                extension = "mp3",
                acodec = "MP3",
                bitrateKbps = 192,
                estimatedBytes = (192000L * dur) / 8L,
                formatSelector = buildAudioSelector("mp3")
            ),
            AudioQualityOption(
                label = "FLAC Lossless Audio",
                extension = "flac",
                acodec = "FLAC",
                bitrateKbps = 320,
                estimatedBytes = (320000L * dur) / 8L,
                formatSelector = buildAudioSelector("flac")
            )
        )

        return Pair(videoPresets, audioPresets)
    }
}
