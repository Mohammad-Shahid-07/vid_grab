package com.vidgrab.app.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vidgrab.app.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber

/**
 * High-performance, in-memory LRU thumbnail cache to avoid re-fetching images.
 */
object ThumbnailCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8 // 12.5% of heap

    val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }
}

@Composable
fun AsyncThumbnailImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    var bitmap by remember(url) {
        mutableStateOf<Bitmap?>(url?.let { ThumbnailCache.memoryCache.get(it) })
    }
    var isLoading by remember(url) { mutableStateOf(bitmap == null && !url.isNullOrBlank()) }

    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            bitmap = null
            isLoading = false
            return@LaunchedEffect
        }

        val cached = ThumbnailCache.memoryCache.get(url)
        if (cached != null) {
            bitmap = cached
            isLoading = false
            return@LaunchedEffect
        }

        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                    .build()

                App.sharedOkHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.body?.byteStream()?.use { stream ->
                            val decoded = BitmapFactory.decodeStream(stream)
                            if (decoded != null) {
                                ThumbnailCache.memoryCache.put(url, decoded)
                                withContext(Dispatchers.Main) {
                                    bitmap = decoded
                                    isLoading = false
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.d(e, "AsyncThumbnailImage: Failed to load thumbnail: %s", url)
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    Crossfade(targetState = bitmap, label = "thumb_crossfade") { currentBitmap ->
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = modifier
            )
        } else {
            Box(
                modifier = modifier.background(Color(0xFFEFE8DF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = Color(0xFFC7BEB5),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
