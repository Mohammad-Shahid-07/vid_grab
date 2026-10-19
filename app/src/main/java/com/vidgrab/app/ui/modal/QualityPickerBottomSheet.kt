package com.vidgrab.app.ui.modal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgrab.app.core.extractor.DownloadRequestSpec
import com.vidgrab.app.core.extractor.ResolvedMediaInfo
import com.vidgrab.app.core.extractor.formatBytes
import com.vidgrab.app.ui.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualityPickerBottomSheet(
    viewModel: QualityPickerViewModel,
    onConfirmDownload: (DownloadRequestSpec) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by viewModel.uiState.collectAsState()
    val theme = AppTheme.colors

    if (state != null) {
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.dismiss()
                onDismiss()
            },
            sheetState = sheetState,
            containerColor = Color.Transparent,
            dragHandle = null,
            scrimColor = Color.Black.copy(alpha = 0.55f)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 20.dp)
                    .animateContentSize(),
                color = theme.sheetBg,
                shape = RoundedCornerShape(26.dp),
                border = BorderStroke(1.dp, theme.dividerColor),
                shadowElevation = 8.dp
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
                            onToggleNumberPrefix = { enabled -> viewModel.toggleNumberPrefix(enabled) },
                            onToggleCutSponsor = { enabled -> viewModel.toggleCutSponsor(enabled) },
                            onToggleEmbedSubtitles = { enabled -> viewModel.toggleEmbedSubtitles(enabled) },
                            onToggleEmbedChapters = { enabled -> viewModel.toggleEmbedChapters(enabled) },
                            onMoveItemUp = { index -> viewModel.moveBatchItemUp(index) },
                            onMoveItemDown = { index -> viewModel.moveBatchItemDown(index) },
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
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            color = Color(0xFFB58E52),
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Resolving Format Options...",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
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
    onToggleNumberPrefix: (Boolean) -> Unit,
    onToggleCutSponsor: (Boolean) -> Unit,
    onToggleEmbedSubtitles: (Boolean) -> Unit,
    onToggleEmbedChapters: (Boolean) -> Unit,
    onMoveItemUp: (Int) -> Unit,
    onMoveItemDown: (Int) -> Unit,
    onStartDownload: () -> Unit
) {
    val info = state.mediaInfo
    val isBatch = info is ResolvedMediaInfo.PlaylistOrChannel
    val theme = AppTheme.colors
    val accent = AppTheme.accent

    val selectedPlaylistItems = if (isBatch) {
        state.orderedBatchItems.filter { state.selectedBatchUrls.contains(it.url) }
    } else emptyList()

    val totalSelectedDurationSec = if (isBatch) {
        selectedPlaylistItems.sumOf { it.durationSeconds }.takeIf { it > 0 }
            ?: (state.selectedBatchUrls.size.toLong() * 240L)
    } else {
        (info as? ResolvedMediaInfo.SingleVideo)?.durationSeconds?.takeIf { it > 0 } ?: 240L
    }

    fun calculateVideoSizeText(height: Int): String {
        val vBr = when (height) {
            2160 -> 15000L
            1440 -> 8000L
            1080 -> 4500L
            720 -> 2500L
            480 -> 1200L
            360 -> 700L
            240 -> 400L
            else -> 200L
        }
        val bytes = ((vBr + 128L) * 1000L / 8L) * totalSelectedDurationSec
        return formatBytes(bytes)
    }

    fun calculateAudioSizeText(bitrateKbps: Int): String {
        val br = bitrateKbps.toLong().takeIf { it > 0 } ?: 160L
        val bytes = (br * 1000L / 8L) * totalSelectedDurationSec
        return formatBytes(bytes)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // 1. Subtle top drag handle
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .background(theme.dividerColor, RoundedCornerShape(2.dp))
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 2. Header Row: Video Title + Subtitle on Left, Pill Switch (Video | Audio) on Right
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp)
            ) {
                Text(
                    text = info.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = theme.textPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isBatch) "Select quality & playlist videos" else "Select quality & tap download below",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = theme.textSecondary
                )
            }

            // Compact Capsule Switch: Video | Audio
            Row(
                modifier = Modifier
                    .background(theme.pillTrackBg, RoundedCornerShape(18.dp))
                    .padding(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (!state.isAudioOnlyTab) theme.pillBg else Color.Transparent)
                        .clickable { onTabSelected(false) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Video",
                        fontSize = 12.sp,
                        fontWeight = if (!state.isAudioOnlyTab) FontWeight.Bold else FontWeight.Medium,
                        color = if (!state.isAudioOnlyTab) accent.color else theme.textSecondary
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (state.isAudioOnlyTab) theme.pillBg else Color.Transparent)
                        .clickable { onTabSelected(true) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "Audio",
                        fontSize = 12.sp,
                        fontWeight = if (state.isAudioOnlyTab) FontWeight.Bold else FontWeight.Medium,
                        color = if (state.isAudioOnlyTab) accent.color else theme.textSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. 3-Column Quality Selector Grid with Dynamic Sizes
        if (!state.isAudioOnlyTab) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                info.videoOptions.chunked(3).forEach { rowOptions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowOptions.forEach { option ->
                            val isSelected = option.height == state.selectedVideoHeight
                            val sizeLabel = if (isBatch) calculateVideoSizeText(option.height) else option.formattedSize
                            Box(modifier = Modifier.weight(1f)) {
                                FloatingQualityGridCard(
                                    title = "${option.height}p",
                                    sizeText = sizeLabel,
                                    isSelected = isSelected,
                                    onClick = {
                                        onVideoHeightSelected(option.height)
                                    }
                                )
                            }
                        }
                        val remainder = 3 - rowOptions.size
                        repeat(remainder) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                info.audioOptions.chunked(3).forEach { rowOptions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowOptions.forEach { audio ->
                            val isSelected = audio.extension == state.selectedAudioExtension &&
                                    audio.bitrateKbps == state.selectedAudioBitrateKbps
                            val sizeLabel = if (isBatch) calculateAudioSizeText(audio.bitrateKbps) else audio.formattedSize
                            Box(modifier = Modifier.weight(1f)) {
                                FloatingQualityGridCard(
                                    title = if (audio.bitrateKbps > 0) "${audio.bitrateKbps}k" else audio.extension.uppercase(),
                                    sizeText = sizeLabel,
                                    isSelected = isSelected,
                                    onClick = {
                                        onAudioSelected(audio.extension, audio.bitrateKbps)
                                    }
                                )
                            }
                        }
                        val remainder = 3 - rowOptions.size
                        repeat(remainder) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // 4. Batch Playlist Selection & Drag Reordering (if batch mode)
        if (isBatch) {
            Spacer(modifier = Modifier.height(14.dp))

            // Playlist Controls Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Playlist Videos (${state.selectedBatchUrls.size}/${state.orderedBatchItems.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = theme.textPrimary
                )

                Text(
                    text = if (state.selectedBatchUrls.size == state.orderedBatchItems.size) "Deselect All" else "Select All",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent.color,
                    modifier = Modifier.clickable {
                        onToggleSelectAll(state.selectedBatchUrls.size != state.orderedBatchItems.size)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Vertically Scrollable Playlist List with Unified Circular Selection Pill & Drag Handle
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 240.dp)
                    .background(theme.pillTrackBg, RoundedCornerShape(14.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                itemsIndexed(state.orderedBatchItems, key = { _, item -> item.url }) { index, item ->
                    val checked = state.selectedBatchUrls.contains(item.url)
                    var dragAccumulator by remember { mutableFloatStateOf(0f) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleBatchItem(item.url) }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Unified Circle Selection Pill
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(if (checked) accent.color else Color.Transparent)
                                .then(
                                    if (!checked) Modifier.border(BorderStroke(1.5.dp, theme.dividerColor), CircleShape)
                                    else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (state.addNumberPrefix) {
                                Text(
                                    text = String.format("%02d", index + 1),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (checked) accent.text else theme.textSecondary
                                )
                            } else {
                                if (checked) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = accent.text,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = theme.textPrimary
                            )
                            if (item.durationSeconds > 0) {
                                val mins = item.durationSeconds / 60
                                val secs = item.durationSeconds % 60
                                Text(
                                    text = String.format("%d:%02d", mins, secs),
                                    fontSize = 10.sp,
                                    color = theme.textSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        // Reorder Drag Handle (Replaces Up / Down buttons)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .pointerInput(index, state.orderedBatchItems.size) {
                                    detectVerticalDragGestures(
                                        onDragStart = { dragAccumulator = 0f },
                                        onDragEnd = { dragAccumulator = 0f },
                                        onDragCancel = { dragAccumulator = 0f },
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            dragAccumulator += dragAmount
                                            if (dragAccumulator > 36f && index < state.orderedBatchItems.size - 1) {
                                                onMoveItemDown(index)
                                                dragAccumulator = 0f
                                            } else if (dragAccumulator < -36f && index > 0) {
                                                onMoveItemUp(index)
                                                dragAccumulator = 0f
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(16.dp)
                                        .height(2.dp)
                                        .background(theme.textSecondary.copy(alpha = 0.5f), RoundedCornerShape(1.dp))
                                )
                                Box(
                                    modifier = Modifier
                                        .width(16.dp)
                                        .height(2.dp)
                                        .background(theme.textSecondary.copy(alpha = 0.5f), RoundedCornerShape(1.dp))
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Advanced Options Collapsible Card
        var advancedExpanded by remember { mutableStateOf(false) }
        val singleSponsors = (info as? ResolvedMediaInfo.SingleVideo)?.sponsorSegments ?: emptyList()
        val hasSponsors = singleSponsors.isNotEmpty()

        val activeCount = (if (isBatch && state.addNumberPrefix) 1 else 0) +
                (if (!isBatch && hasSponsors && state.cutSponsor) 1 else 0) +
                (if (state.embedSubtitles) 1 else 0) +
                (if (state.embedChapters) 1 else 0)

        Spacer(modifier = Modifier.height(10.dp))

        Surface(
            color = theme.pillTrackBg,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, theme.dividerColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { advancedExpanded = !advancedExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Advanced Options",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = theme.textPrimary
                        )
                        if (activeCount > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(accent.color.copy(alpha = 0.2f), CircleShape)
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "$activeCount active",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = accent.color
                                )
                            }
                        }
                    }

                    Icon(
                        imageVector = if (advancedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = theme.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = advancedExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Prefix Numbering (Playlist only)
                        if (isBatch) {
                            AdvancedOptionSwitchRow(
                                title = "Prefix numbering (01, 02...)",
                                subtitle = "Number tracks in playlist order",
                                checked = state.addNumberPrefix,
                                onCheckedChange = onToggleNumberPrefix,
                                accentColor = accent.color
                            )
                        }

                        // Cut Sponsor (Single video only, STRICTLY only if sponsor chapters detected!)
                        if (!isBatch && hasSponsors) {
                            AdvancedOptionSwitchRow(
                                title = "Cut sponsor segment",
                                subtitle = "Detected in chapter markers",
                                checked = state.cutSponsor,
                                onCheckedChange = onToggleCutSponsor,
                                accentColor = accent.color
                            )
                        }

                        // Embed Subtitles
                        AdvancedOptionSwitchRow(
                            title = "Embed subtitles",
                            subtitle = "Include subtitle stream in file",
                            checked = state.embedSubtitles,
                            onCheckedChange = onToggleEmbedSubtitles,
                            accentColor = accent.color
                        )

                        // Embed Chapters
                        AdvancedOptionSwitchRow(
                            title = "Embed chapters",
                            subtitle = "Include chapter markers in file",
                            checked = state.embedChapters,
                            onCheckedChange = onToggleEmbedChapters,
                            accentColor = accent.color
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 6. Primary Action CTA Button
        if (isBatch) {
            val canQueue = state.selectedBatchUrls.isNotEmpty()
            val totalSizeText = if (!state.isAudioOnlyTab) {
                calculateVideoSizeText(state.selectedVideoHeight)
            } else {
                calculateAudioSizeText(state.selectedAudioBitrateKbps)
            }

            Button(
                onClick = onStartDownload,
                enabled = canQueue,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent.color,
                    contentColor = accent.text,
                    disabledContainerColor = theme.dividerColor,
                    disabledContentColor = theme.textMuted
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp), tint = accent.text)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (canQueue) "Download Playlist (${state.selectedBatchUrls.size} Videos • $totalSizeText)" else "Select at least 1 video",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent.text
                )
            }
        } else {
            val ctaSize = if (!state.isAudioOnlyTab) {
                calculateVideoSizeText(state.selectedVideoHeight)
            } else {
                calculateAudioSizeText(state.selectedAudioBitrateKbps)
            }
            val ctaLabel = if (!state.isAudioOnlyTab) {
                "Download ${state.selectedVideoHeight}p ($ctaSize)"
            } else {
                val label = if (state.selectedAudioBitrateKbps > 0) "${state.selectedAudioBitrateKbps}k" else state.selectedAudioExtension.uppercase()
                "Download $label ($ctaSize)"
            }

            Button(
                onClick = onStartDownload,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent.color,
                    contentColor = accent.text
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp), tint = accent.text)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = ctaLabel,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent.text
                )
            }
        }
    }
}

@Composable
fun AdvancedOptionSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accentColor: Color
) {
    val theme = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = theme.textPrimary
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                color = theme.textSecondary
            )
        }

        Box(
            modifier = Modifier
                .width(38.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (checked) accentColor else theme.dividerColor)
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
}

@Composable
fun FloatingQualityGridCard(
    title: String,
    sizeText: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val theme = AppTheme.colors
    val accent = AppTheme.accent
    val bgColor = if (isSelected) accent.color else theme.pillBg
    val titleColor = if (isSelected) accent.text else theme.textPrimary
    val subtitleColor = if (isSelected) accent.text.copy(alpha = 0.85f) else theme.textSecondary

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = titleColor
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = sizeText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = subtitleColor
            )
        }
    }
}

@Composable
fun ErrorView(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Unable to resolve formats", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFFBA1A1A))
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, fontSize = 12.sp, color = Color(0xFF5F5B59))
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB58E52)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Close", color = Color.White)
        }
    }
}
