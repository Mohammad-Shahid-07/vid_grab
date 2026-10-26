package com.vidgrab.app.core.engine

import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Durable per-segment byte map for [FastDirectDownloader].
 *
 * IDM-style segmented downloads write each range at its absolute file offset, so a paused transfer
 * can only be resumed when the exact progress of *every* segment is known. The file length alone is
 * useless: a 45%-complete 8-segment file is full of holes, not a 45% prefix.
 *
 * This sidecar keeps that map next to the `.part` file as small JSON:
 * `{ v, id, total, segments: [{ s, e, d }] }` where `d` is the number of bytes already written for
 * that range. Writes are atomic (tmp + rename) because the process can be killed at any moment, and
 * throttled to at most one flush per [PERSIST_INTERVAL_MS] plus a forced flush on pause/exit.
 *
 * The [streamId] guard is what makes resume safe: YouTube hands out a fresh URL (new `expire`/`sig`
 * params) for the same bytes, but a different `itag`/`clen` means different content, so a mismatched
 * state is discarded instead of corrupting the output.
 */
internal class SegmentResumeState private constructor(
    private val stateFile: File,
    val streamId: String,
    val totalBytes: Long,
    val segments: List<Segment>
) {

    data class Segment(val start: Long, val end: Long, var done: Long = 0L) {
        val length: Long get() = (end - start + 1L).coerceAtLeast(0L)
        val complete: Boolean get() = done >= length
        val nextOffset: Long get() = (start + done).coerceAtMost(end + 1L)
        val fraction: Float
            get() = if (length <= 0L) 1f else (done.toFloat() / length.toFloat()).coerceIn(0f, 1f)
    }

    private var lastPersistAt = 0L

    @Synchronized
    fun downloadedTotal(): Long = segments.sumOf { it.done }

    @Synchronized
    fun recordBytes(index: Int, bytes: Long) {
        val segment = segments.getOrNull(index) ?: return
        segment.done = (segment.done + bytes).coerceIn(0L, segment.length)
    }

    @Synchronized
    fun fraction(index: Int): Float = segments.getOrNull(index)?.fraction ?: 0f

    /**
     * Flushes the byte map. [force] is used on pause/completion paths where losing the last
     * second of progress would make the resumed percentage jump backwards.
     */
    @Synchronized
    fun persist(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAt < PERSIST_INTERVAL_MS) return
        lastPersistAt = now
        writeLocked()
    }

    private fun writeLocked() {
        try {
            val payload = JSONObject().apply {
                put("v", FORMAT_VERSION)
                put("id", streamId)
                put("total", totalBytes)
                put("segments", JSONArray().apply {
                    segments.forEach { segment ->
                        put(JSONObject().apply {
                            put("s", segment.start)
                            put("e", segment.end)
                            put("d", segment.done)
                        })
                    }
                })
            }
            val serialized = payload.toString()
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(serialized)
            if (stateFile.exists()) stateFile.delete()
            if (!tmp.renameTo(stateFile)) {
                stateFile.writeText(serialized)
                tmp.delete()
            }
        } catch (e: Exception) {
            // A missing state file only costs a restart, never corruption: the .part is still
            // guarded by the byte-range validation inside downloadByteRange.
            Timber.w(e, "SegmentResumeState: could not persist %s", stateFile.name)
        }
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val PERSIST_INTERVAL_MS = 1500L

        /** Splits [totalBytes] into deterministic ranges; identical inputs always yield identical offsets. */
        fun plan(totalBytes: Long, segmentCount: Int): List<Segment> {
            if (totalBytes <= 0L) return emptyList()
            val count = segmentCount.coerceAtLeast(1)
            val size = (totalBytes + count - 1) / count
            return (0 until count).mapNotNull { index ->
                val start = index * size
                if (start >= totalBytes) {
                    null
                } else {
                    val end = ((index + 1) * size - 1).coerceAtMost(totalBytes - 1)
                    Segment(start = start, end = end, done = 0L)
                }
            }
        }

        /**
         * Identity of the bytes behind [url]. `expire`/`sig`/`range` params rotate between runs, so
         * only the itag (format id) and the payload length are compared.
         */
        fun streamIdentity(url: String, totalBytes: Long): String {
            val itag = Regex("[?&]itag=(\\d+)").find(url)?.groupValues?.getOrNull(1)
            val clen = Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.getOrNull(1)
                ?: totalBytes.toString()
            return "${itag ?: "na"}:$clen"
        }

        /**
         * Restores the byte map for [planned] segments, or starts from zero when the file is absent,
         * unreadable, from an older format version, or describes different bytes.
         */
        fun open(
            stateFile: File,
            streamId: String,
            totalBytes: Long,
            planned: List<Segment>
        ): SegmentResumeState {
            val segments = restore(stateFile, streamId, totalBytes, planned)
            val resumedBytes = segments.sumOf { it.done }
            if (resumedBytes > 0L) {
                Timber.i(
                    "SegmentResumeState: resuming %s at %d/%d bytes (%s)",
                    stateFile.name,
                    resumedBytes,
                    totalBytes,
                    streamId
                )
            }
            return SegmentResumeState(stateFile, streamId, totalBytes, segments)
        }

        fun delete(stateFile: File) {
            runCatching { if (stateFile.exists()) stateFile.delete() }
            runCatching { File(stateFile.parentFile, stateFile.name + ".tmp").delete() }
        }

        private fun restore(
            stateFile: File,
            streamId: String,
            totalBytes: Long,
            planned: List<Segment>
        ): List<Segment> {
            val fresh = planned.map { it.copy(done = 0L) }
            if (!stateFile.exists() || planned.isEmpty()) return fresh

            return try {
                val root = JSONObject(stateFile.readText())
                if (root.optInt("v", 0) != FORMAT_VERSION) return fresh
                if (root.optString("id") != streamId) {
                    Timber.i("SegmentResumeState: stream identity changed, restarting %s", stateFile.name)
                    return fresh
                }
                if (root.optLong("total", -1L) != totalBytes) return fresh

                val array = root.optJSONArray("segments") ?: return fresh
                if (array.length() != planned.size) return fresh

                val restored = ArrayList<Segment>(planned.size)
                for (index in 0 until array.length()) {
                    val entry = array.optJSONObject(index) ?: return fresh
                    val segment = planned[index]
                    if (entry.optLong("s", -1L) != segment.start ||
                        entry.optLong("e", -1L) != segment.end
                    ) {
                        return fresh
                    }
                    restored.add(segment.copy(done = entry.optLong("d", 0L).coerceIn(0L, segment.length)))
                }
                restored
            } catch (e: Exception) {
                Timber.w(e, "SegmentResumeState: unreadable state %s, starting over", stateFile.name)
                fresh
            }
        }
    }
}
