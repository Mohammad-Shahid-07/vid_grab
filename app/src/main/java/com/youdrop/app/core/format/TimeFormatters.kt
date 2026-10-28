package com.youdrop.app.core.format

import java.util.Locale

/**
 * Human-readable remaining-time label shared by the downloads list, the queue drawer and the
 * progress notification. A raw second count ("ETA 3847s") is never useful to a person, so every
 * surface must format it the same way:
 *
 *   45s
 *   3m 20s
 *   1h 05m
 */
fun formatEta(seconds: Long): String {
    if (seconds <= 0L || seconds == Long.MAX_VALUE) return ""

    return when {
        seconds < 60L -> "${seconds}s"
        seconds < 3600L -> {
            val minutes = seconds / 60L
            val remainingSeconds = seconds % 60L
            if (remainingSeconds == 0L) "${minutes}m" else "${minutes}m ${remainingSeconds}s"
        }
        else -> {
            val hours = seconds / 3600L
            val minutes = (seconds % 3600L) / 60L
            String.format(Locale.US, "%dh %02dm", hours, minutes)
        }
    }
}
