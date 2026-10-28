package com.youdrop.app.core.extractor

/**
 * Generates yt-dlp -f format selector expressions guaranteeing:
 * 1. H.264 (avc1) + M4A (mp4a) preference for universal hardware MP4 compatibility and fast FFmpeg -c copy.
 * 2. Automatic fallback to VP9/AV1 + Opus if H.264 is unavailable at the chosen resolution.
 * 3. Graceful downgrade to the next highest available resolution (height<=H) if the specific quality is absent.
 */
object FormatSelectorBuilder {

    /**
     * Builds the yt-dlp video format selector for a given max height constraint, video codec
     * preference, and audio container preference.
     *
     * [vcodec] accepts "H.264", "VP9", "AV1" or "Auto" (null / blank also means Auto).
     * [audioPreference] accepts "M4A", "Opus" or "Auto".
     */
    fun buildVideoSelector(
        height: Int,
        vcodec: String = "H.264",
        audioPreference: String = "Auto",
        audioLanguage: String? = null,
        audioBitrateKbps: Int? = null
    ): String {
        val normalizedCodec = vcodec.trim().uppercase()
        val normalizedAudio = audioPreference.trim().uppercase()
        val languageFilter = audioLanguage
            ?.trim()
            ?.substringBefore('-')
            ?.takeIf { it.matches(Regex("[A-Za-z]{2,3}")) }
            ?.let { "[language^=$it]" }
        val bitrateCap = audioBitrateKbps
            ?.takeIf { it > 0 }
            // Small tolerance: the player response and yt-dlp report abr slightly differently,
            // and an exact cap would otherwise exclude the very stream the user picked.
            ?.let { "[abr<=${it + 16}]" }

        // Preserve the proven default chains when no explicit audio preference is requested.
        if (normalizedAudio == "AUTO" && languageFilter == null && bitrateCap == null) {
            return when (normalizedCodec) {
                "VP9" -> "bestvideo[height<=$height][vcodec^=vp9]+bestaudio[acodec^=opus]/" +
                        "bestvideo[height<=$height][vcodec^=vp09]+bestaudio/" +
                        "bestvideo[height<=$height]+bestaudio/best"
                "AV1" -> "bestvideo[height<=$height][vcodec^=av01]+bestaudio[acodec^=mp4a]/" +
                        "bestvideo[height<=$height][vcodec^=av01]+bestaudio/" +
                        "bestvideo[height<=$height]+bestaudio/best"
                "H.264", "H264", "AVC", "AUTO", "" -> "bestvideo[height<=$height][vcodec^=avc1]+bestaudio[acodec^=mp4a]/" +
                        "bestvideo[height<=$height][ext=mp4]+bestaudio[ext=m4a]/" +
                        "bestvideo[height<=$height]+bestaudio/" +
                        "best[height<=$height]/best"
                else -> "bestvideo[height<=$height]+bestaudio/" +
                        "best[height<=$height]/best"
            }
        }

        val videoBase = when (normalizedCodec) {
            "VP9" -> "bestvideo[height<=$height][vcodec^=vp9]"
            "AV1" -> "bestvideo[height<=$height][vcodec^=av01]"
            "H.264", "H264", "AVC" -> "bestvideo[height<=$height][vcodec^=avc1]"
            // Auto with an explicit audio preference: bias to H.264 first for MP4 safety,
            // then let the generic fallback pick whatever exists at this height.
            else -> "bestvideo[height<=$height][vcodec^=avc1]"
        }
        val audioBase = when (normalizedAudio) {
            "M4A", "AAC" -> "bestaudio[ext=m4a]"
            "OPUS", "WEBM" -> "bestaudio[ext=webm]"
            else -> "bestaudio"
        }
        val preferredAudio = "$audioBase${bitrateCap.orEmpty()}${languageFilter.orEmpty()}"

        return "$videoBase+$preferredAudio/" +
                "$videoBase+$audioBase/" +
                "bestvideo[height<=$height]+bestaudio/" +
                "best[height<=$height]/best"
    }

    /**
     * Builds the yt-dlp audio-only format selector.
     * [bitrateKbps] caps the chosen stream (with a small tolerance) when the user picked a
     * concrete bitrate in the audio tab.
     */
    fun buildAudioSelector(extension: String, bitrateKbps: Int? = null): String {
        val cap = bitrateKbps
            ?.takeIf { it > 0 }
            ?.let { "[abr<=${it + 16}]" }
            .orEmpty()
        return when (extension.lowercase()) {
            "m4a", "aac" -> "bestaudio[ext=m4a]$cap/bestaudio[acodec^=mp4a]$cap/bestaudio$cap/best"
            "opus", "webm" -> "bestaudio[ext=webm]$cap/bestaudio[acodec^=opus]$cap/bestaudio$cap/best"
            else -> "bestaudio$cap/best"
        }
    }

    /**
     * Standard 2026 resolution presets for Tier 3 fallback and default displays.
     */
    fun buildStandardPresets(estimatedDurationSec: Long = 0L): Pair<List<VideoQualityOption>, List<AudioQualityOption>> {
        val dur = if (estimatedDurationSec > 0L) estimatedDurationSec else 180L
        val pairedAudioBytes = (128000L * dur) / 8L

        fun videoPreset(height: Int, label: String, fps: Int, vBitrate: Long): VideoQualityOption {
            val videoBytes = (vBitrate * dur) / 8L
            return VideoQualityOption(
                height = height,
                label = label,
                fps = fps,
                vcodec = "H.264",
                container = "mp4",
                videoOnlyBytes = videoBytes,
                audioStreamBytes = pairedAudioBytes,
                estimatedTotalBytes = videoBytes + pairedAudioBytes,
                formatSelector = buildVideoSelector(height)
            )
        }

        val videoPresets = listOf(
            videoPreset(1080, "1080p Full HD", 60, 2500000L),
            videoPreset(720, "720p HD", 60, 1200000L),
            videoPreset(480, "480p Standard", 30, 650000L),
            videoPreset(360, "360p Medium", 30, 400000L),
            videoPreset(240, "240p Compact", 30, 220000L),
            videoPreset(144, "144p Data Saver", 30, 110000L)
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
