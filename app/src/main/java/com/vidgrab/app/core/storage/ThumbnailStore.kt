package com.vidgrab.app.core.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.vidgrab.app.App
import com.vidgrab.app.core.network.NetworkConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for download thumbnails.
 *
 * Why this exists: the old downloader cards re-fetched every thumbnail from the network on each
 * render. i.ytimg.com regularly answers 403/404 for old or parameterised URLs, so cards stayed on
 * the placeholder forever. This store:
 *
 *  1. Normalises any YouTube thumbnail URL to a stable `hqdefault.jpg` (with mq/default fallbacks).
 *  2. Keeps a memory LruCache for instant recomposition.
 *  3. Persists the encoded bytes in app-private storage, so a thumbnail is fetched exactly once
 *     and remains available offline.
 *  4. Deduplicates concurrent loads for the same image.
 *
 * The same store is used by the downloads list, the queue drawer, the progress notification and
 * the prefetch performed when a task is queued.
 */
object ThumbnailStore {

    private const val DISK_BUDGET_BYTES = 64L * 1024L * 1024L
    private const val MAX_DISK_FILES = 600
    private const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L

    private val youtubeIdPattern = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val youtubeThumbIdPattern = Regex("""(?:/vi(?:ew_webp)?/|/vi/|[?&]v=)([A-Za-z0-9_-]{11})""")
    private val clenPattern = Regex("""[?&]clen=(\d+)""", RegexOption.IGNORE_CASE)

    private val memoryCache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024L) / 8L).toInt().coerceIn(1_024, 16_384)
    ) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount / 1024
    }

    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()
    private val diskMutex = Mutex()

    /** Backwards-compatible accessor used by a few call sites. */
    fun getCachedBitmap(url: String?, videoId: String? = null): Bitmap? {
        val key = normalizedKey(url, videoId) ?: return null
        return synchronized(memoryCache) { memoryCache.get(key) }
    }

    /**
     * Loads a thumbnail from memory, then disk, then the network. The result is always stored in
     * both caches so the next render (and the next offline launch) is instant.
     */
    suspend fun loadBitmap(url: String?, videoId: String? = null): Bitmap? {
        val candidates = candidateUrls(url, videoId)
        val key = candidates.firstOrNull() ?: return null

        synchronized(memoryCache) { memoryCache.get(key) }?.let { return it }

        // Coalesce duplicate requests: a list can render the same task in several places while
        // the first network call is still running.
        while (true) {
            val existing = inFlight[key]
            if (existing != null) return existing.await()

            val own = CompletableDeferred<Bitmap?>()
            val raced = inFlight.putIfAbsent(key, own)
            if (raced != null) return raced.await()

            try {
                val bitmap = withContext(Dispatchers.IO) { loadInternal(key, candidates) }
                own.complete(bitmap)
                return bitmap
            } catch (e: Exception) {
                Timber.d(e, "Thumbnail load failed for %s", key)
                own.complete(null)
                return null
            } finally {
                inFlight.remove(key, own)
            }
        }
    }

    /** Warms both caches before a queued download can ever render a card. */
    suspend fun prefetch(url: String?, videoId: String? = null) {
        if (url.isNullOrBlank() && videoId.isNullOrBlank()) return
        loadBitmap(url, videoId)
    }

    private suspend fun loadInternal(key: String, candidates: List<String>): Bitmap? {
        val dir = cacheDir() ?: return null
        val diskFile = File(dir, diskName(key))

        if (diskFile.isFile && diskFile.length() > 0L) {
            val cached = runCatching { BitmapFactory.decodeFile(diskFile.absolutePath) }.getOrNull()
            if (cached != null) {
                synchronized(memoryCache) { memoryCache.put(key, cached) }
                return cached
            }
            // Corrupt/truncated cache entry: remove it and fall through to the network.
            diskFile.delete()
        }

        for (candidate in candidates) {
            val bytes = fetchBytes(candidate) ?: continue
            val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            if (bitmap == null) continue

            synchronized(memoryCache) { memoryCache.put(key, bitmap) }
            runCatching {
                diskFile.parentFile?.mkdirs()
                diskFile.writeBytes(bytes)
                diskFile.setLastModified(System.currentTimeMillis())
            }
            pruneDiskCache(dir)
            return bitmap
        }
        return null
    }

    private fun fetchBytes(url: String): ByteArray? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return try {
            val request = Request.Builder()
                .url(url)
                // i.ytimg.com rejects requests that look like a bare HTTP client. Send the same
                // browser headers a YouTube page would send for its own <img> tags.
                .header("User-Agent", NetworkConstants.activeUserAgent.ifBlank { NetworkConstants.DEFAULT_USER_AGENT })
                .header("Referer", "https://www.youtube.com/")
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .build()

            App.sharedOkHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                if (body.contentLength() > MAX_IMAGE_BYTES) return null
                body.bytes().takeIf { it.isNotEmpty() && it.size <= MAX_IMAGE_BYTES }
            }
        } catch (e: Exception) {
            Timber.d(e, "Thumbnail request failed for %s", url.take(120))
            null
        }
    }

    private suspend fun pruneDiskCache(dir: File) {
        diskMutex.withLock {
            val files = dir.listFiles()?.filter { it.isFile } ?: return@withLock
            var totalBytes = files.sumOf { it.length() }
            var count = files.size
            if (count <= MAX_DISK_FILES && totalBytes <= DISK_BUDGET_BYTES) return@withLock

            files.sortedBy { it.lastModified() }.forEach { file ->
                if (count <= MAX_DISK_FILES && totalBytes <= DISK_BUDGET_BYTES) return@forEach
                val length = file.length()
                if (file.delete()) {
                    count--
                    totalBytes -= length
                }
            }
        }
    }

    /**
     * Stable key for a thumbnail. YouTube URLs are reduced to their video id so the same image
     * does not get cached under every `?sqp=...` variant the page happens to use.
     */
    private fun normalizedKey(url: String?, videoId: String?): String? =
        candidateUrls(url, videoId).firstOrNull()

    private fun candidateUrls(url: String?, videoId: String?): List<String> {
        val raw = url?.trim()?.takeIf { it.isNotBlank() }
        val explicitId = videoId?.trim()?.takeIf { youtubeIdPattern.matches(it) }
        val thumbId = extractYoutubeThumbId(raw)
        val id = explicitId ?: thumbId

        if (id != null && (raw == null || isYouTubeThumbnailUrl(raw) || thumbId != null)) {
            // hqdefault always exists and is 16:9 with tiny letterbox bars; mq/default are
            // progressively more reliable fallbacks for very old or deleted videos.
            return listOf(
                "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                "https://i.ytimg.com/vi/$id/mqdefault.jpg",
                "https://i.ytimg.com/vi/$id/default.jpg"
            )
        }

        return listOfNotNull(raw)
    }

    private fun extractYoutubeThumbId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return youtubeThumbIdPattern.find(url)?.groupValues?.getOrNull(1)
    }

    private fun isYouTubeThumbnailUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("ytimg.com") || lower.contains("img.youtube.com")
    }

    /** Extracts `clen=` from a googlevideo URL; useful for exact stream sizes elsewhere. */
    fun contentLengthFromUrl(url: String?): Long {
        if (url.isNullOrBlank()) return 0L
        return clenPattern.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
    }

    private fun cacheDir(): File? {
        return try {
            File(App.instance.filesDir, "thumbnails").apply { if (!exists()) mkdirs() }
        } catch (e: Exception) {
            Timber.d(e, "Thumbnail disk cache unavailable")
            null
        }
    }

    private fun diskName(key: String): String {
        // A stable, collision-resistant-enough name without pulling in a crypto hash.
        return "${Integer.toHexString(key.hashCode())}-${key.length}.img"
    }
}
