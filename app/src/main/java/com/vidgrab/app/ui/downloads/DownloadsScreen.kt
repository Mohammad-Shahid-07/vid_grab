package com.vidgrab.app.ui.downloads

import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vidgrab.app.core.database.DownloadTaskEntity
import com.vidgrab.app.core.database.TaskStatus
import com.vidgrab.app.core.queue.QueueRepository
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

    Scaffold(
        containerColor = Color(0xFFFAF8F5),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Downloads",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color(0xFF241D1B)
                        )
                        if (activeAndPendingTasks.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFFE5A93C), CircleShape)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${activeAndPendingTasks.size} active",
                                    color = Color.White,
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
                            tint = Color(0xFF241D1B)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFAF8F5))
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
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFEFEAE2)),
                    modifier = Modifier.clickable { queueRepository.pauseAll() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pause,
                            contentDescription = null,
                            tint = Color(0xFF7F7875),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Pause All",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF241D1B)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFEFEAE2)),
                    modifier = Modifier.clickable { queueRepository.resumeAll() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFFE5A93C),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Resume All",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF241D1B)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "${allTasks.size} total tasks",
                    fontSize = 12.sp,
                    color = Color(0xFF7F7875)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Segmented Tabs: Downloading, Completed, Paused
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFFF3ECE7),
                contentColor = Color(0xFFE5A93C),
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = Color(0xFFE5A93C),
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
                            text = "Downloading (${activeAndPendingTasks.size})",
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = "Completed (${completedTasks.size})",
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Text(
                            text = "Paused (${pausedTasks.size})",
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tab Content
            when (selectedTab) {
                0 -> {
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
                1 -> {
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
                2 -> {
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
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFFEFEAE2)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColor = when (task.status) {
                    TaskStatus.DOWNLOADING -> Color(0xFFE5A93C)
                    TaskStatus.MERGING -> Color(0xFF2C6954)
                    TaskStatus.PAUSED -> Color(0xFF8C827A)
                    else -> Color(0xFF7F7875)
                }

                Text(
                    text = when (task.status) {
                        TaskStatus.DOWNLOADING -> "DOWNLOADING"
                        TaskStatus.MERGING -> "MERGING (-c copy)"
                        TaskStatus.PAUSED -> "PAUSED"
                        TaskStatus.EXPANDING -> "EXPANDING"
                        else -> "PENDING"
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "${task.outputExt.uppercase()}${if (task.targetHeight != null) " • ${task.targetHeight}p" else ""}",
                    fontSize = 11.sp,
                    color = Color(0xFF7F7875)
                )

                Spacer(modifier = Modifier.weight(1f))

                // Action Buttons
                if (task.status == TaskStatus.DOWNLOADING || task.status == TaskStatus.MERGING) {
                    IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause", tint = Color(0xFFE5A93C), modifier = Modifier.size(20.dp))
                    }
                } else if (task.status == TaskStatus.PAUSED) {
                    IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = Color(0xFFE5A93C), modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color(0xFF7F7875), modifier = Modifier.size(18.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = task.title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = Color(0xFF241D1B)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Radiant Gold Progress Bar
            LinearProgressIndicator(
                progress = { task.progressPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Color(0xFFE5A93C),
                trackColor = Color(0xFFF3ECE7)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Speed & ETA
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${task.progressPercent.toInt()}% • ${task.speedText.ifBlank { "Starting download..." }}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF5F5B59),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = if (task.etaSeconds > 0) "ETA ${task.etaSeconds}s" else "",
                    fontSize = 11.sp,
                    color = Color(0xFF7F7875)
                )
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
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFFEFEAE2)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFFE5A93C).copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFFE5A93C),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFF241D1B)
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${task.outputExt.uppercase()} • ${formatBytes(task.totalBytes)}",
                    fontSize = 11.sp,
                    color = Color(0xFF7F7875)
                )
            }

            // Open Action
            IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.FileOpen, contentDescription = "Open", tint = Color(0xFFE5A93C), modifier = Modifier.size(20.dp))
            }

            // Delete Action
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFF7F7875), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun EmptyDownloadsPlaceholder(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = Color(0xFF9E9792),
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
    if (uriString.isNullOrBlank()) return
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriString.toUri(), if (uriString.contains(".m4a") || uriString.contains(".opus") || uriString.contains(".mp3")) "audio/*" else "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        timber.log.Timber.w(e, "Could not open media intent for: %s", uriString)
    }
}
