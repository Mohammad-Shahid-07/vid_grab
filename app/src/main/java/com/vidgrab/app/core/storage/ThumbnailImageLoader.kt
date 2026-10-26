package com.vidgrab.app.core.storage

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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

/**
 * Compatibility facade over [ThumbnailStore].
 *
 * Every thumbnail surface (queue drawer, notification, downloads list) shares one memory + disk
 * cache, so the same video never costs two network requests and the image survives process death.
 */
object ThumbnailImageLoader {

    fun getCachedBitmap(url: String?, videoId: String? = null): Bitmap? =
        ThumbnailStore.getCachedBitmap(url, videoId)

    suspend fun loadBitmap(url: String?, videoId: String? = null): Bitmap? =
        ThumbnailStore.loadBitmap(url, videoId)
}

@Composable
fun RemoteThumbnailImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    fallbackIcon: ImageVector = Icons.Default.PlayArrow,
    videoId: String? = null
) {
    var bitmap by remember(url, videoId) {
        mutableStateOf(ThumbnailStore.getCachedBitmap(url, videoId))
    }

    LaunchedEffect(url, videoId) {
        if (bitmap == null && (!url.isNullOrBlank() || !videoId.isNullOrBlank())) {
            bitmap = ThumbnailStore.loadBitmap(url, videoId)
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
