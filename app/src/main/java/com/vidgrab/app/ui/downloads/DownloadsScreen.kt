package com.vidgrab.app.ui.downloads

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vidgrab.app.core.database.DownloadTaskEntity
import com.vidgrab.app.core.database.TaskStatus
import com.vidgrab.app.core.queue.QueueRepository
import com.vidgrab.app.ui.common.AsyncThumbnailImage
import com.vidgrab.app.ui.theme.AppTheme
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    queueRepository: QueueRepository,
    onNavigateBack: () -> Unit
) {
    BackHandler { onNavigateBack() }

    val allTasks by queueRepository.allTasks.collectAsState(initial = emptyList())
    val completedTasks by queueRepository.completedTasks.collectAsState(initial = emptyList())
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    val pausedTasks = remember(allTasks) {
        allTasks.filter { it.status == TaskStatus.PAUSED }
    }

    // Active downloading tasks: strictly sort so that DOWNLOADING / MERGING are at the VERY TOP
    val activeAndPendingTasks = remember(allTasks) {
        allTasks
            .filter { it.status in listOf(TaskStatus.DOWNLOADING, TaskStatus.MERGING, TaskStatus.EXPANDING, TaskStatus.PENDING) }
            .sortedWith(
                compareBy<DownloadTaskEntity> { task ->
                    when (task.status) {
                        TaskStatus.DOWNLOADING -> 1
                        TaskStatus.MERGING -> 2
                        TaskStatus.EXPANDING -> 3
                        TaskStatus.PENDING -> 4
                        else -> 5
                    }
                }.thenByDescending { it.updatedAt }
            )
    }

    val theme = AppTheme.colors
    val accent = AppTheme.accent

    Scaffold(
        modifier = Modifier.navigationBarsPadding(),
        containerColor = theme.phoneBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Downloads",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = theme.textPrimary
                        )
                        if (activeAndPendingTasks.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(accent.color, CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${activeAndPendingTasks.size} active",
                                    color = accent.text,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = theme.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = theme.phoneBg)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .animateContentSize()
        ) {
            // Action Pills Bar: "Pause All" & "Resume All"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = theme.cardBg,
                    border = BorderStroke(1.dp, theme.dividerColor),
                    modifier = Modifier.clickable { queueRepository.pauseAll() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = null,
                            tint = theme.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Pause All",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.textPrimary
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = theme.cardBg,
                    border = BorderStroke(1.dp, theme.dividerColor),
                    modifier = Modifier.clickable { queueRepository.resumeAll() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = accent.color,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Resume All",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.textPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "${allTasks.size} tasks",
                    fontSize = 12.sp,
                    color = theme.textSecondary
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Segmented Scrollable Tabs: All (Default), Downloading, Completed, Paused (Zero text wrapping)
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = theme.pillTrackBg,
                contentColor = accent.color,
                edgePadding = 0.dp,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = accent.color,
                        height = 3.dp
                    )
                },
                divider = {},
                modifier = Modifier.clip(RoundedCornerShape(12.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = "All (${allTasks.size})",
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = if (selectedTab == 0) theme.textPrimary else theme.textSecondary,
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = "Downloading (${activeAndPendingTasks.size})",
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = if (selectedTab == 1) theme.textPrimary else theme.textSecondary,
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Text(
                            text = "Completed (${completedTasks.size})",
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = if (selectedTab == 2) theme.textPrimary else theme.textSecondary,
                            fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = {
                        Text(
                            text = "Paused (${pausedTasks.size})",
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = if (selectedTab == 3) theme.textPrimary else theme.textSecondary,
                            fontWeight = if (selectedTab == 3) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tab Content
            when (selectedTab) {
                0 -> {
                    // All Tasks (Active & Pending at the top, then Paused, then Completed)
                    if (allTasks.isEmpty()) {
                        EmptyDownloadsPlaceholder("No downloads yet")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(activeAndPendingTasks, key = { "active_${it.id}" }) { task ->
                                DownloadCard(
                                    task = task,
                                    onPause = { queueRepository.pauseTask(task.id) },
                                    onResume = { queueRepository.resumeTask(task.id) },
                                    onCancel = { queueRepository.cancelTask(task.id) }
                                )
                            }
                            items(pausedTasks, key = { "paused_${it.id}" }) { task ->
                                DownloadCard(
                                    task = task,
                                    onPause = { },
                                    onResume = { queueRepository.resumeTask(task.id) },
                                    onCancel = { queueRepository.deleteTask(task.id) }
                                )
                            }
                            items(completedTasks, key = { "completed_${it.id}" }) { task ->
                                CompletedDownloadCard(
                                    task = task,
                                    onOpen = { openMediaFile(context, task.outputFileUri) },
                                    onDelete = { queueRepository.deleteTask(task.id) }
                                )
                            }
                        }
                    }
                }
                1 -> {
                    // Downloading (Active + Pending with Downloading strictly at top)
                    if (activeAndPendingTasks.isEmpty()) {
                        EmptyDownloadsPlaceholder("No active downloads")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(activeAndPendingTasks, key = { it.id }) { task ->
                                DownloadCard(
                                    task = task,
                                    onPause = { queueRepository.pauseTask(task.id) },
                                    onResume = { queueRepository.resumeTask(task.id) },
                                    onCancel = { queueRepository.cancelTask(task.id) }
                                )
                            }
                        }
                    }
                }
                2 -> {
                    // Completed
                    if (completedTasks.isEmpty()) {
                        EmptyDownloadsPlaceholder("No completed downloads yet")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(completedTasks, key = { it.id }) { task ->
                                CompletedDownloadCard(
                                    task = task,
                                    onOpen = { openMediaFile(context, task.outputFileUri) },
                                    onDelete = { queueRepository.deleteTask(task.id) }
                                )
                            }
                        }
                    }
                }
                3 -> {
                    // Paused
                    if (pausedTasks.isEmpty()) {
                        EmptyDownloadsPlaceholder("No paused downloads")
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(pausedTasks, key = { it.id }) { task ->
                                DownloadCard(
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
fun DownloadCard(
    task: DownloadTaskEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val isConnecting = (task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.PENDING || task.status == TaskStatus.EXPANDING) &&
            (task.downloadedBytes == 0L || task.progressPercent <= 2f) &&
            !task.speedText.contains("/s") &&
            !task.speedText.contains("Merging")

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(isConnecting) {
        if (isConnecting) {
            while (true) {
                delay(1000L)
                nowMs = System.currentTimeMillis()
            }
        }
    }
    val elapsedMs = nowMs - task.createdAt
    val downloadedStr = formatBytes(task.downloadedBytes)
    val totalStr = if (task.totalBytes > 0L) formatBytes(task.totalBytes) else ""
    val sizeProgressStr = if (totalStr.isNotEmpty()) "$downloadedStr / $totalStr" else downloadedStr

    val statusDisplayText = if (isConnecting) {
        val totalSuffix = if (totalStr.isNotEmpty()) " • $totalStr" else ""
        if (elapsedMs < 5000L) "Connecting$totalSuffix..." else "Download will start in a moment$totalSuffix..."
    } else {
        "$sizeProgressStr (${task.progressPercent.toInt()}%) • ${task.speedText.ifBlank { "Downloading..." }}"
    }

    val theme = AppTheme.colors
    val accent = AppTheme.accent

    Surface(
        color = theme.cardBg,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, theme.dividerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Video thumbnail with format badge
                Box(modifier = Modifier.size(width = 68.dp, height = 46.dp)) {
                    AsyncThumbnailImage(
                        url = task.thumbnailUrl,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = if (task.isAudioOnly) task.outputExt.uppercase() else "${task.targetHeight ?: 720}p",
                            color = accent.color,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    val statusColor = when (task.status) {
                        TaskStatus.DOWNLOADING -> accent.color
                        TaskStatus.MERGING -> theme.successGreen
                        TaskStatus.PAUSED -> theme.textSecondary
                        else -> theme.textSecondary
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when (task.status) {
                                TaskStatus.DOWNLOADING -> if (isConnecting) "CONNECTING" else "DOWNLOADING"
                                TaskStatus.MERGING -> "MERGING (-c copy)"
                                TaskStatus.PAUSED -> "PAUSED"
                                TaskStatus.EXPANDING -> "EXPANDING"
                                else -> "QUEUED"
                            },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusColor,
                            modifier = Modifier
                                .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = task.title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = theme.textPrimary
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Action Buttons
                if (task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.MERGING) {
                    IconButton(onClick = onPause, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause", tint = accent.color, modifier = Modifier.size(18.dp))
                    }
                } else if (task.status == TaskStatus.PAUSED) {
                    IconButton(onClick = onResume, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = accent.color, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = theme.textSecondary, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Radiant Progress Bar: Indeterminate when connecting, Determinate when bytes stream
            if (isConnecting) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = accent.color,
                    trackColor = theme.pillTrackBg
                )
            } else {
                LinearProgressIndicator(
                    progress = { task.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = accent.color,
                    trackColor = theme.pillTrackBg
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Status, Speed & ETA
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusDisplayText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (!isConnecting && task.etaSeconds > 0) {
                    Text(
                        text = "ETA ${task.etaSeconds}s",
                        fontSize = 11.sp,
                        color = theme.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun CompletedDownloadCard(
    task: DownloadTaskEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val theme = AppTheme.colors
    val accent = AppTheme.accent

    Surface(
        color = theme.cardBg,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, theme.dividerColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(width = 68.dp, height = 46.dp)) {
                AsyncThumbnailImage(
                    url = task.thumbnailUrl,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(2.dp)
                        .background(theme.successGreen, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = theme.textPrimary
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${task.outputExt.uppercase()} • ${formatBytes(task.totalBytes)}",
                    fontSize = 11.sp,
                    color = theme.textSecondary
                )
            }

            // Open Action
            IconButton(onClick = onOpen, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.FileOpen, contentDescription = "Open", tint = accent.color, modifier = Modifier.size(18.dp))
            }

            // Delete Action
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = theme.textSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun EmptyDownloadsPlaceholder(message: String) {
    val theme = AppTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = theme.textSecondary,
            fontSize = 14.sp
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024f * 1024f)
    return if (mb >= 1000f) {
        String.format(Locale.US, "%.1f GB", mb / 1024f)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

private fun openMediaFile(context: Context, uriString: String?) {
    if (uriString.isNullOrBlank()) {
        Toast.makeText(context, "Media file path is unavailable", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriString.toUri(), if (uriString.contains(".m4a") || uriString.contains(".opus") || uriString.contains(".mp3")) "audio/*" else "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        timber.log.Timber.w(e, "Could not open media intent for: %s", uriString)
        Toast.makeText(context, "No app available to play this media file", Toast.LENGTH_SHORT).show()
    }
}
