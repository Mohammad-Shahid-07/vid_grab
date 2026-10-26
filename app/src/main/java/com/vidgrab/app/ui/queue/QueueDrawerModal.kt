package com.vidgrab.app.ui.queue

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import com.vidgrab.app.core.storage.RemoteThumbnailImage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgrab.app.core.database.DownloadTaskEntity
import com.vidgrab.app.core.database.TaskStatus
import com.vidgrab.app.core.extractor.formatBytes
import com.vidgrab.app.core.format.formatEta
import com.vidgrab.app.core.queue.QueueRepository
import com.vidgrab.app.ui.theme.AppTheme
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueDrawerModal(
    queueRepository: QueueRepository,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val allTasks by queueRepository.allTasks.collectAsState(initial = emptyList())
    val completedTasks by queueRepository.completedTasks.collectAsState(initial = emptyList())
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val theme = AppTheme.colors
    val accent = AppTheme.accent

    val failedTasks = remember(allTasks) { allTasks.filter { it.status == TaskStatus.FAILED } }
    val pausedTasks = remember(allTasks) { allTasks.filter { it.status == TaskStatus.PAUSED } }
    val activeAndPendingTasks = remember(allTasks) {
        allTasks.filter { it.status in listOf(TaskStatus.DOWNLOADING, TaskStatus.MERGING, TaskStatus.EXPANDING, TaskStatus.PENDING) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.sheetBg,
        contentColor = theme.textPrimary,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .animateContentSize()
        ) {
            // Header: Title and Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Downloads",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = theme.textPrimary
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = theme.textSecondary, modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Pills Bar: "Pause All" & "Resume All"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = theme.cardBg,
                    border = BorderStroke(1.dp, theme.dividerColor),
                    modifier = Modifier.clickable { queueRepository.pauseAll() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null, tint = theme.textSecondary, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pause All", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = theme.textPrimary)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = theme.cardBg,
                    border = BorderStroke(1.dp, theme.dividerColor),
                    modifier = Modifier.clickable { queueRepository.resumeAll() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = accent.color, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Resume All", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = theme.textPrimary)
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "${allTasks.size} total",
                    fontSize = 11.sp,
                    color = theme.textSecondary,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3-Segmented Tabs: Downloading, Completed, Paused
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = theme.pillTrackBg,
                contentColor = accent.color,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = accent.color,
                        height = 3.dp
                    )
                },
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            "Downloading (${activeAndPendingTasks.size + failedTasks.size})",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            "Completed (${completedTasks.size})",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Text(
                            "Paused (${pausedTasks.size})",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tab Content
            when (selectedTab) {
                0 -> {
                    // Downloading & Pending (+ Failed at top)
                    val count = activeAndPendingTasks.size + failedTasks.size
                    if (count == 0) {
                        EmptyQueuePlaceholder("No active or queued downloads")
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                            items(failedTasks, key = { it.id }) { task ->
                                FailedTaskCard(
                                    task = task,
                                    onRetry = { queueRepository.retryTask(task.id) },
                                    onDelete = { queueRepository.deleteTask(task.id) }
                                )
                            }
                            items(activeAndPendingTasks, key = { it.id }) { task ->
                                ActiveTaskRow(
                                    task = task,
                                    onPause = { queueRepository.pauseTask(task.id) },
                                    onResume = { queueRepository.resumeTask(task.id) },
                                    onCancel = { queueRepository.cancelTask(task.id) }
                                )
                            }
                        }
                    }
                }
                1 -> {
                    // Completed
                    if (completedTasks.isEmpty()) {
                        EmptyQueuePlaceholder("No completed downloads yet")
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                            items(completedTasks, key = { it.id }) { task ->
                                CompletedTaskRow(
                                    task = task,
                                    onOpen = { openMediaFile(context, task.outputFileUri) },
                                    onOpenYouTube = { onOpenUrl(task.url) },
                                    onDelete = { queueRepository.deleteTask(task.id) }
                                )
                            }
                        }
                    }
                }
                2 -> {
                    // Paused
                    if (pausedTasks.isEmpty()) {
                        EmptyQueuePlaceholder("No paused downloads")
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                            items(pausedTasks, key = { it.id }) { task ->
                                ActiveTaskRow(
                                    task = task,
                                    onPause = { },
                                    onResume = { queueRepository.resumeTask(task.id) },
                                    onCancel = { queueRepository.deleteTask(task.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveTaskRow(
    task: DownloadTaskEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val theme = AppTheme.colors
    val accent = AppTheme.accent
    Surface(
        color = theme.cardBg,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, theme.dividerColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 16:9 Thumbnail with format badge
                Box(modifier = Modifier.size(width = 68.dp, height = 44.dp)) {
                    RemoteThumbnailImage(
                        url = task.thumbnailUrl,
                        videoId = task.videoId,
                        contentDescription = task.title,
                        fallbackIcon = if (task.isAudioOnly) Icons.Default.MusicNote else Icons.Default.PlayArrow,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                    )
                    // Format Tag Overlay
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = if (task.isAudioOnly) task.outputExt.uppercase() else "${task.targetHeight ?: 1080}p",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val statusColor = when (task.status) {
                            TaskStatus.DOWNLOADING -> accent.color
                            TaskStatus.MERGING -> theme.successGreen
                            TaskStatus.PAUSED -> theme.textMuted
                            else -> theme.textSecondary
                        }

                        Text(
                            text = when (task.status) {
                                TaskStatus.DOWNLOADING -> "DOWNLOADING"
                                TaskStatus.MERGING -> "MERGING"
                                TaskStatus.PAUSED -> "PAUSED"
                                TaskStatus.EXPANDING -> "EXPANDING"
                                else -> "PENDING"
                            },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            modifier = Modifier
                                .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = "${task.progressPercent.toInt()}%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent.color
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = task.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = theme.textPrimary
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Action Buttons: Pause / Resume, Cancel
                if (task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.MERGING) {
                    IconButton(onClick = onPause, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause", tint = accent.color, modifier = Modifier.size(18.dp))
                    }
                } else if (task.status == TaskStatus.PAUSED) {
                    IconButton(onClick = onResume, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = accent.color, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = theme.textSecondary, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Radiant Gold Progress Bar
            LinearProgressIndicator(
                progress = { task.progressPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = accent.color,
                trackColor = theme.pillTrackBg
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Live Size Counters & Speed
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val sizeText = if (task.totalBytes > 0L) {
                    "${formatBytes(task.downloadedBytes)} / ${formatBytes(task.totalBytes)}"
                } else if (task.downloadedBytes > 0L) {
                    formatBytes(task.downloadedBytes)
                } else "Starting..."

                Text(
                    text = "$sizeText • ${task.speedText.ifBlank { "Downloading..." }}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = theme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = if (task.etaSeconds > 0) "ETA ${formatEta(task.etaSeconds)}" else "",
                    fontSize = 10.sp,
                    color = theme.textSecondary
                )
            }
        }
    }
}

@Composable
fun CompletedTaskRow(
    task: DownloadTaskEntity,
    onOpen: () -> Unit,
    onOpenYouTube: () -> Unit,
    onDelete: () -> Unit
) {
    val theme = AppTheme.colors
    val accent = AppTheme.accent
    Surface(
        color = theme.cardBg,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, theme.dividerColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 16:9 Thumbnail
            Box(modifier = Modifier.size(width = 68.dp, height = 44.dp)) {
                RemoteThumbnailImage(
                    url = task.thumbnailUrl,
                    videoId = task.videoId,
                    contentDescription = task.title,
                    fallbackIcon = if (task.isAudioOnly) Icons.Default.MusicNote else Icons.Default.PlayArrow,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                )
                // Format Tag Overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (task.isAudioOnly) task.outputExt.uppercase() else "${task.targetHeight ?: 1080}p",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = theme.textPrimary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(theme.successGreen.copy(alpha = 0.12f), RoundedCornerShape(3.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "COMPLETED",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.successGreen
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${task.outputExt.uppercase()} • ${formatBytes(task.totalBytes)}",
                        fontSize = 10.sp,
                        color = theme.textSecondary
                    )
                }
            }

            // Open Action
            IconButton(onClick = onOpen, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.FileOpen, contentDescription = "Open", tint = accent.color, modifier = Modifier.size(18.dp))
            }

            // Open on YouTube (in-app browser)
            IconButton(onClick = onOpenYouTube, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.SmartDisplay,
                    contentDescription = "Open on YouTube",
                    tint = accent.color,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Delete Action
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = theme.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun EmptyQueuePlaceholder(text: String) {
    val theme = AppTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 13.sp, color = theme.textSecondary)
    }
}

private fun openMediaFile(context: Context, fileUri: String?) {
    if (fileUri.isNullOrBlank()) return
    try {
        val uri = Uri.parse(fileUri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (fileUri.endsWith(".m4a") || fileUri.endsWith(".opus")) "audio/*" else "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Timber.e(e, "Could not open media file URI: %s", fileUri)
    }
}

@Composable
fun FailedTaskCard(
    task: DownloadTaskEntity,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    val theme = AppTheme.colors
    Surface(
        color = theme.errorRed.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, theme.errorRed.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "FAILED",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.errorRed,
                    modifier = Modifier
                        .background(theme.errorRed.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = "${task.outputExt.uppercase()}${if (task.targetHeight != null) " • ${task.targetHeight}p" else ""}",
                    fontSize = 10.sp,
                    color = theme.textSecondary
                )

                Spacer(modifier = Modifier.weight(1f))

                // Retry Button
                IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry",
                        tint = theme.errorRed,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Delete Button
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = theme.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = task.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = theme.textPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            val errorText = task.errorMessage?.ifBlank { null } ?: "Unknown error occurred during extraction or download."
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.pillTrackBg, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = errorText,
                    color = theme.errorRed,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
