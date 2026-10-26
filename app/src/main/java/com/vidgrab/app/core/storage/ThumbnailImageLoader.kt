package com.vidgrab.app.core.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vidgrab.app.App
import com.vidgrab.app.core.network.NetworkConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber

object ThumbnailImageLoader {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceIn(1024, 8192) // 1MB to 8MB

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    fun getCachedBitmap(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return synchronized(memoryCache) {
            memoryCache.get(url)
        }
    }

    suspend fun loadBitmap(url: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null

        getCachedBitmap(url)?.let { return@withContext it }

        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", NetworkConstants.activeUserAgent)
                .build()

            App.sharedOkHttpClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            synchronized(memoryCache) {
                                memoryCache.put(url, bmp)
                            }
                            return@withContext bmp
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.d(e, "Thumbnail load failed for %s", url)
        }
        null
    }
}

@Composable
fun RemoteThumbnailImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    fallbackIcon: ImageVector = Icons.Default.PlayArrow
) {
    var bitmap by remember(url) {
        mutableStateOf(ThumbnailImageLoader.getCachedBitmap(url))
    }

    LaunchedEffect(url) {
        if (bitmap == null && !url.isNullOrBlank()) {
            bitmap = ThumbnailImageLoader.loadBitmap(url)
        }
    }

    val currentBmp = bitmap
    if (currentBmp != null) {
        Image(
            bitmap = currentBmp.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    } else {
        val theme = com.vidgrab.app.ui.theme.AppTheme.colors
        Box(
            modifier = modifier.background(theme.pillBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = contentDescription,
                tint = theme.textMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
