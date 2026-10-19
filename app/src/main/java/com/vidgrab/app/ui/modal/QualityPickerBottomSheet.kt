package com.vidgrab.app.ui.modal

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgrab.app.core.extractor.DownloadRequestSpec
import com.vidgrab.app.core.extractor.ResolutionSource
import com.vidgrab.app.core.extractor.ResolvedMediaInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityPickerBottomSheet(
    viewModel: QualityPickerViewModel,
    onConfirmDownload: (DownloadRequestSpec) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by viewModel.uiState.collectAsState()

    if (state != null) {
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.dismiss()
                onDismiss()
            },
            sheetState = sheetState,
            containerColor = Color(0xFFFAF8F5),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
                    .animateContentSize()
            ) {
                when (val uiState = state) {
                    is QualityPickerUiState.Loading -> {
                        LoadingView(payloadTitle = uiState.payload.title)
                    }
                    is QualityPickerUiState.Success -> {
                        SuccessContentView(
                            state = uiState,
                            onTabSelected = { isAudioOnly -> viewModel.selectTab(isAudioOnly) },
                            onVideoHeightSelected = { h -> viewModel.selectVideoHeight(h) },
                            onAudioSelected = { ext, br -> viewModel.selectAudio(ext, br) },
                            onToggleBatchItem = { url -> viewModel.toggleBatchItem(url) },
                            onToggleSelectAll = { selectAll -> viewModel.toggleSelectAllBatch(selectAll) },
                            onStartDownload = {
                                viewModel.buildDownloadRequestSpec()?.let { spec ->
                                    viewModel.dismiss()
                                    onConfirmDownload(spec)
                                }
                            }
                        )
                    }
                    is QualityPickerUiState.Error -> {
                        ErrorView(message = uiState.message, onDismiss = onDismiss)
                    }
                    null -> {}
                }
            }
        }
    }
}

@Composable
fun LoadingView(payloadTitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            color = Color(0xFFE5A93C),
            modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "Resolving Format Options...",
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
            color = Color(0xFF241D1B)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = payloadTitle.take(48),
            fontSize = 12.sp,
            color = Color(0xFF7F7875),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "⚡ In-Memory / Warm JVM Extractor",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF2C6954)
        )
    }
}

@Composable
fun SuccessContentView(
    state: QualityPickerUiState.Success,
    onTabSelected: (Boolean) -> Unit,
    onVideoHeightSelected: (Int) -> Unit,
    onAudioSelected: (String, Int) -> Unit,
    onToggleBatchItem: (String) -> Unit,
    onToggleSelectAll: (Boolean) -> Unit,
    onStartDownload: () -> Unit
) {
    val info = state.mediaInfo
    val isBatch = info is ResolvedMediaInfo.PlaylistOrChannel

    Column(modifier = Modifier.fillMaxWidth()) {
        // Media Header Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(Color(0xFFE5A93C).copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isBatch) Icons.Default.VideoLibrary else Icons.Default.Movie,
                    contentDescription = null,
                    tint = Color(0xFFE5A93C),
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = info.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFF241D1B)
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val badgeColor = when (info.source) {
                        ResolutionSource.TIER_1_WEBVIEW_MEMORY -> Color(0xFF2C6954)
                        ResolutionSource.TIER_2_NEWPIPE_JVM -> Color(0xFFE5A93C)
                        ResolutionSource.TIER_3_SMART_PRESET -> Color(0xFF7F7875)
                    }

                    Text(
                        text = when (info.source) {
                            ResolutionSource.TIER_1_WEBVIEW_MEMORY -> "⚡ Instant (${info.resolutionTimeMs}ms)"
                            ResolutionSource.TIER_2_NEWPIPE_JVM -> "⚡ JVM (${info.resolutionTimeMs}ms)"
                            ResolutionSource.TIER_3_SMART_PRESET -> "🛡 Direct"
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier
                            .background(badgeColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )

                    if (isBatch) {
                        Spacer(modifier = Modifier.width(6.dp))
                        val p = info as ResolvedMediaInfo.PlaylistOrChannel
                        Text(
                            text = "${p.items.size} videos",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF7F7875)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Segmented Tab Switch: Video vs Audio
        TabRow(
            selectedTabIndex = if (state.isAudioOnlyTab) 1 else 0,
            containerColor = Color(0xFFF3ECE7),
            contentColor = Color(0xFFE5A93C),
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[if (state.isAudioOnlyTab) 1 else 0]),
                    color = Color(0xFFE5A93C),
                    height = 3.dp
                )
            },
            modifier = Modifier.clip(RoundedCornerShape(12.dp))
        ) {
            Tab(
                selected = !state.isAudioOnlyTab,
                onClick = { onTabSelected(false) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Video (MP4)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            )
            Tab(
                selected = state.isAudioOnlyTab,
                onClick = { onTabSelected(true) },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Audio (M4A/Opus)", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 2x2 Format Options Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!state.isAudioOnlyTab) {
                items(info.videoOptions) { option ->
                    val isSelected = option.height == state.selectedVideoHeight
                    QualityGridCard(
                        title = option.label,
                        subtitle = "${option.fps}fps",
                        sizeText = option.formattedSize,
                        isSelected = isSelected,
                        onClick = { onVideoHeightSelected(option.height) }
                    )
                }
            } else {
                items(info.audioOptions) { audio ->
                    val isSelected = audio.extension == state.selectedAudioExtension &&
                            audio.bitrateKbps == state.selectedAudioBitrateKbps
                    QualityGridCard(
                        title = audio.label,
                        subtitle = audio.extension.uppercase(),
                        sizeText = audio.formattedSize,
                        isSelected = isSelected,
                        onClick = { onAudioSelected(audio.extension, audio.bitrateKbps) }
                    )
                }
            }
        }

        // VidMate Playlist / Channel Item Selector (if in batch mode)
        if (isBatch) {
            val playlist = info as ResolvedMediaInfo.PlaylistOrChannel
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Batch Selection (${state.selectedBatchUrls.size}/${playlist.items.size} Selected)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFF241D1B)
                )
                Text(
                    text = if (state.selectedBatchUrls.size == playlist.items.size) "Deselect All" else "Select All",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE5A93C),
                    modifier = Modifier.clickable {
                        onToggleSelectAll(state.selectedBatchUrls.size != playlist.items.size)
                    }
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(playlist.items) { item ->
                    val checked = state.selectedBatchUrls.contains(item.url)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleBatchItem(item.url) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggleBatchItem(item.url) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFFE5A93C),
                                uncheckedColor = Color(0xFFC7BEB5)
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color(0xFF241D1B)
                            )
                            if (item.durationSeconds > 0) {
                                val mins = item.durationSeconds / 60
                                val secs = item.durationSeconds % 60
                                Text(
                                    text = String.format("%d:%02d", mins, secs),
                                    fontSize = 10.sp,
                                    color = Color(0xFF7F7875)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Primary Action CTA Button
        Button(
            onClick = onStartDownload,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5A93C)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isBatch) "Queue Batch (${state.selectedBatchUrls.size} Items)" else "Start Download",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
fun QualityGridCard(
    title: String,
    subtitle: String,
    sizeText: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) Color(0xFFE5A93C) else Color(0xFFEFEAE2)
    val bgColor = if (isSelected) Color(0xFFFFFBF2) else Color.White

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.5.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFF241D1B)
                )
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(Color(0xFFE5A93C), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sizeText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) Color(0xFFE5A93C) else Color(0xFF7F7875)
                )
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = Color(0xFF9E9792)
                )
            }
        }
    }
}

@Composable
fun ErrorView(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("⚠ Quality Resolution Error", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFFBA1A1A))
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, fontSize = 12.sp, color = Color(0xFF5F5B59))
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5A93C))
        ) {
            Text("Close", color = Color.White)
        }
    }
}
