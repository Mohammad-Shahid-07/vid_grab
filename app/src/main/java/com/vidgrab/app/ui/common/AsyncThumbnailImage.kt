package com.vidgrab.app.ui.common

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vidgrab.app.core.storage.ThumbnailStore
import com.vidgrab.app.ui.theme.AppTheme

/**
 * Downloads-list thumbnail. Delegates to [ThumbnailStore] so the image is cached in memory and on
 * disk instead of being re-fetched from i.ytimg.com on every recomposition.
 */
@Composable
fun AsyncThumbnailImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    videoId: String? = null
) {
    var bitmap by remember(url, videoId) {
        mutableStateOf<Bitmap?>(ThumbnailStore.getCachedBitmap(url, videoId))
    }

    LaunchedEffect(url, videoId) {
        if (bitmap == null && (!url.isNullOrBlank() || !videoId.isNullOrBlank())) {
            bitmap = ThumbnailStore.loadBitmap(url, videoId)
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
                modifier = modifier.background(AppTheme.colors.pillBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = AppTheme.colors.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
