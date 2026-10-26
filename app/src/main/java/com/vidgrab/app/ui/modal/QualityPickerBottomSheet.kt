package com.vidgrab.app.ui.modal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.vidgrab.app.core.extractor.AudioQualityOption
import com.vidgrab.app.core.extractor.DownloadRequestSpec
import com.vidgrab.app.core.extractor.ResolvedMediaInfo
import com.vidgrab.app.core.extractor.VideoQualityOption
import com.vidgrab.app.core.extractor.formatBytes
import com.vidgrab.app.ui.theme.AppTheme
import kotlin.math.roundToInt

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
            val configuration = LocalConfiguration.current
            val maxSheetHeight = (configuration.screenHeightDp * 0.88f).dp
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 20.dp)
                    .heightIn(max = maxSheetHeight)
                    .animateContentSize(),
                color = theme.sheetBg,
                contentColor = theme.textPrimary,
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
                            onVideoVariantSelected = { variant -> viewModel.selectVideoVariant(variant) },
                            onMergeAudioStreamSelected = { key -> viewModel.selectMergeAudioStream(key) },
                            onBatchVideoCodecSelected = { codec -> viewModel.selectBatchVideoCodec(codec) },
                            onBatchMergeAudioExtensionSelected = { ext -> viewModel.selectBatchMergeAudioExtension(ext) },
                            onAudioSelected = { ext, br -> viewModel.selectAudio(ext, br) },
                            onToggleBatchItem = { url -> viewModel.toggleBatchItem(url) },
                            onToggleSelectAll = { selectAll -> viewModel.toggleSelectAllBatch(selectAll) },
                            onToggleNumberPrefix = { enabled -> viewModel.toggleNumberPrefix(enabled) },
                            onToggleCutSponsor = { enabled -> viewModel.toggleCutSponsor(enabled) },
                            onToggleEmbedSubtitles = { enabled -> viewModel.toggleEmbedSubtitles(enabled) },
                            onToggleEmbedChapters = { enabled -> viewModel.toggleEmbedChapters(enabled) },
                            onMoveItemUp = { index -> viewModel.moveBatchItemUp(index) },
                            onMoveItemDown = { index -> viewModel.moveBatchItemDown(index) },
                            onMoveItem = { from, to -> viewModel.moveBatchItem(from, to) },
                            onDownloadFolderSelected = { folder -> viewModel.setDownloadFolder(folder) },
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

/**
 * One selectable chip. [key] is the value handed back to the view model, [title] is what the user
 * reads. Keeping them as named fields makes the two impossible to swap at a call site.
 */
private data class SelectionChip(val key: String?, val title: String)

@Composable
private fun SelectionChipRow(
    label: String,
    options: List<SelectionChip>,
    selectedKey: String?,
    accentColor: Color,
    onSelected: (String?) -> Unit
) {
    val theme = AppTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = theme.textSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEach { chip ->
                val selected = chip.key == selectedKey
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelected(chip.key) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) accentColor.copy(alpha = 0.16f) else theme.pillTrackBg,
                    border = BorderStroke(
                        1.dp,
                        if (selected) accentColor else theme.dividerColor.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                        }
                        Text(
                            text = chip.title,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) accentColor else theme.textPrimary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

private fun audioTrackChipLabel(track: AudioQualityOption): String {
    val raw = track.audioTrackName?.takeIf { it.isNotBlank() }
        ?: track.audioLanguage?.takeIf { it.isNotBlank() }
        ?: "Default"
    val cleaned = raw
        .replace("(original)", "", ignoreCase = true)
        .replace(" original", "", ignoreCase = true)
        .replace(Regex("\\s+"), " ")
        .trim()
    return cleaned.ifBlank { "Default" }.let { if (it.length > 26) it.take(25) + "…" else it }
}

/**
 * "English · Opus 160k · 8.1 MB" for multi-track uploads, "AAC 128k · 4.2 MB" for the usual video.
 * The container is intentionally omitted: the resulting container is stated once, on the live
 * summary line under the row, because it depends on the video/audio pair, not on the audio alone.
 */
private fun audioStreamChipLabel(stream: AudioQualityOption, allStreams: List<AudioQualityOption>): String {
    val multiTrack = allStreams.map { it.trackKey }.distinct().size > 1
    val trackPart = if (multiTrack) audioTrackChipLabel(stream) + " · " else ""
    val codecPart = buildString {
        append(stream.acodec)
        if (stream.bitrateKbps > 0) append(" ${stream.bitrateKbps}k")
    }
    val sizePart = stream.formattedSize.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    return "$trackPart$codecPart$sizePart"
}

@Composable
fun LoadingView(payloadTitle: String) {
    val theme = AppTheme.colors
    val accent = AppTheme.accent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            color = accent.color,
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Resolving Format Options...",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = theme.textPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = payloadTitle.take(48),
            fontSize = 12.sp,
            color = theme.textSecondary,
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
    onVideoVariantSelected: (VideoQualityOption) -> Unit,
    onMergeAudioStreamSelected: (String?) -> Unit,
    onBatchVideoCodecSelected: (String) -> Unit,
    onBatchMergeAudioExtensionSelected: (String) -> Unit,
    onAudioSelected: (String, Int) -> Unit,
    onToggleBatchItem: (String) -> Unit,
    onToggleSelectAll: (Boolean) -> Unit,
    onToggleNumberPrefix: (Boolean) -> Unit,
    onToggleCutSponsor: (Boolean) -> Unit,
    onToggleEmbedSubtitles: (Boolean) -> Unit,
    onToggleEmbedChapters: (Boolean) -> Unit,
    onMoveItemUp: (Int) -> Unit,
    onMoveItemDown: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit = { _, _ -> },
    onDownloadFolderSelected: (String) -> Unit,
    onStartDownload: () -> Unit
) {
    val info = state.mediaInfo
    val isBatch = info is ResolvedMediaInfo.PlaylistOrChannel
    val theme = AppTheme.colors
    val accent = AppTheme.accent

    var draggingItemUrl by remember { mutableStateOf<String?>(null) }
    var draggingFromIndex by remember { mutableIntStateOf(-1) }
    var draggingTargetIndex by remember { mutableIntStateOf(-1) }
    val haptics = LocalHapticFeedback.current
    val mainScrollState = rememberScrollState()

    val selectedPlaylistItems = if (isBatch) {
        state.orderedBatchItems.filter { state.selectedBatchUrls.contains(it.url) }
    } else emptyList()

    val totalSelectedDurationSec = if (isBatch) {
        selectedPlaylistItems.sumOf { it.durationSeconds }.takeIf { it > 0 }
            ?: (state.selectedBatchUrls.size.toLong() * (info.durationSeconds.takeIf { it > 0 } ?: 240L))
    } else {
        info.durationSeconds.takeIf { it > 0 } ?: 240L
    }

    fun calculateVideoSizeText(height: Int): String {
        if (!isBatch) {
            if (height == state.selectedVideoHeight && state.selectedVideoOption != null && state.selectedVideoOption.estimatedTotalBytes > 0L) {
                return state.selectedVideoOption.formattedSize
            }
            val match = info.videoOptions.firstOrNull { it.height == height }
            if (match != null && match.estimatedTotalBytes > 0L) {
                return match.formattedSize
            }
            val estBitrate = when {
                height >= 2160 -> 15_000_000L
                height >= 1440 -> 8_000_000L
                height >= 1080 -> 4_000_000L
                height >= 720 -> 2_000_000L
                height >= 480 -> 1_000_000L
                else -> 500_000L
            }
            val estBytes = (estBitrate * totalSelectedDurationSec) / 8L
            return formatBytes(estBytes)
        } else {
            val option = info.videoOptions.firstOrNull { it.height == height }
            val bitrateBps = if (option != null && option.estimatedTotalBytes > 0L && info.durationSeconds > 0) {
                (option.estimatedTotalBytes * 8L) / info.durationSeconds
            } else {
                when {
                    height >= 2160 -> 15_000_000L
                    height >= 1440 -> 8_000_000L
                    height >= 1080 -> 4_000_000L
                    height >= 720 -> 2_000_000L
                    height >= 480 -> 1_000_000L
                    else -> 500_000L
                }
            }
            val totalBytes = (bitrateBps * totalSelectedDurationSec) / 8L
            return formatBytes(totalBytes)
        }
    }

    fun calculateAudioSizeText(extension: String = "", bitrateKbps: Int): String {
        if (!isBatch) {
            val match = info.audioOptions.firstOrNull {
                (extension.isBlank() || it.extension.equals(extension, ignoreCase = true)) &&
                (bitrateKbps <= 0 || it.bitrateKbps == bitrateKbps)
            } ?: info.audioOptions.firstOrNull { it.bitrateKbps == bitrateKbps }
            if (match != null && match.estimatedBytes > 0L) {
                return match.formattedSize
            }
        }
        val targetBitrate = if (bitrateKbps > 0) bitrateKbps else 128
        val bytesPerSec = (targetBitrate * 1000L) / 8L
        val totalBytes = bytesPerSec * totalSelectedDurationSec
        return formatBytes(totalBytes)
    }

    /**
     * Size of the finished file for [option]: its video stream plus the audio stream that will
     * actually be muxed. Progressive formats already contain their audio, so they are used as-is.
     * This is what makes the number change the moment a different audio track is picked.
     */
    fun mergedSizeFor(option: VideoQualityOption?): String {
        if (option == null) return ""
        if (option.isMuxed) return option.formattedSize
        val audioBytes = state.effectiveMergeAudio?.estimatedBytes?.takeIf { it > 0L }
            ?: option.audioStreamBytes
        return formatBytes(option.videoOnlyBytes + audioBytes)
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

        Spacer(modifier = Modifier.height(10.dp))

        // Middle Scrollable Body (prevents overflow and enables smooth scrolling)
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(mainScrollState, enabled = draggingItemUrl == null)
        ) {
            // 3. 3-Column Quality Selector Grid with Dynamic Sizes
            if (!state.isAudioOnlyTab) {
                val distinctHeights = remember(info.videoOptions) {
                    info.videoOptions.map { it.height }.distinct().sortedDescending()
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    distinctHeights.chunked(3).forEach { rowHeights ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowHeights.forEach { height ->
                                val isSelected = height == state.selectedVideoHeight
                                val defaultOptionForHeight = remember(info.videoOptions, height) {
                                    info.videoOptions.firstOrNull { it.height == height && it.vcodec == "H.264" }
                                        ?: info.videoOptions.firstOrNull { it.height == height }
                                }
                                val displayOption = if (isSelected) (state.selectedVideoOption ?: defaultOptionForHeight) else defaultOptionForHeight
                                val sizeLabel = if (isBatch) calculateVideoSizeText(height) else mergedSizeFor(displayOption)
                                val qualityBadge = when (height) {
                                    2160 -> "4K UHD"
                                    1440 -> "2K QHD"
                                    1080 -> "Full HD"
                                    720 -> "HD"
                                    480 -> "Standard"
                                    360 -> "Medium"
                                    240 -> "Compact"
                                    144 -> "Data Saver"
                                    else -> "${height}p"
                                }
                                val cardSubtitle = if (sizeLabel.isNotBlank()) sizeLabel else qualityBadge
                                Box(modifier = Modifier.weight(1f)) {
                                    FloatingQualityGridCard(
                                        title = "${height}p",
                                        sizeText = cardSubtitle,
                                        isSelected = isSelected,
                                        onClick = {
                                            onVideoHeightSelected(height)
                                        }
                                    )
                                }
                            }
                            val remainder = 3 - rowHeights.size
                            repeat(remainder) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                // Sleek Dynamic Codec Sub-Pill Row for Selected Resolution (Approach A)
                if (!isBatch) {
                    val variantsForSelected = remember(info.videoOptions, state.selectedVideoHeight) {
                        info.videoOptions.filter { it.height == state.selectedVideoHeight }
                    }
                    if (variantsForSelected.size > 1) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Video format / codec",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = theme.textSecondary
                            )
                            Text(
                                text = "${variantsForSelected.size} options available",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Normal,
                                color = theme.textMuted
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            variantsForSelected.forEach { variant ->
                                val isVariantSelected = state.selectedVideoOption?.let {
                                    it.vcodec == variant.vcodec && it.fps == variant.fps && it.container == variant.container && it.directStreamUrl == variant.directStreamUrl
                                } ?: (variant == variantsForSelected.first())

                                val fpsTag = if (variant.fps >= 50) " 60fps" else ""
                                val variantTitle = "${variant.vcodec}$fpsTag · ${variant.container.uppercase()}"
                                val variantSize = mergedSizeFor(variant)

                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onVideoVariantSelected(variant) },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isVariantSelected) accent.color.copy(alpha = 0.16f) else theme.pillTrackBg,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isVariantSelected) accent.color else theme.dividerColor.copy(alpha = 0.5f)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (isVariantSelected) {
                                            Box(
                                                modifier = Modifier
                                                    .size(6.dp)
                                                    .clip(CircleShape)
                                                    .background(accent.color)
                                            )
                                        }
                                        Text(
                                            text = variantTitle,
                                            fontSize = 11.sp,
                                            fontWeight = if (isVariantSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isVariantSelected) accent.color else theme.textPrimary
                                        )
                                        if (variantSize.isNotBlank()) {
                                            Text(
                                                text = "($variantSize)",
                                                fontSize = 10.sp,
                                                fontWeight = if (isVariantSelected) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isVariantSelected) accent.color else theme.textSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (!isBatch) {
                    val selectedFormat = state.selectedVideoOption
                    if (selectedFormat != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val selectedSize = mergedSizeFor(selectedFormat)
                        Text(
                            text = "Selected: ${selectedFormat.vcodec} · ${selectedFormat.container.uppercase()} · " +
                                "${selectedFormat.fps}fps" +
                                selectedSize.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                            fontSize = 10.sp,
                            color = theme.textMuted
                        )
                    }

                    // Audio stream selector: every concrete stream (track + codec + bitrate), so any
                    // video can be paired with any audio. Progressive formats carry their own audio.
                    val audioStreams = remember(info.audioOptions) {
                        info.audioOptions
                            .distinctBy { it.streamKey }
                            .sortedWith(
                                compareByDescending<AudioQualityOption> { it.isOriginalOrDefault }
                                    .thenByDescending { it.extension.equals("m4a", ignoreCase = true) }
                                    .thenByDescending { it.bitrateKbps }
                            )
                    }
                    val videoIsMuxed = state.selectedVideoOption?.isMuxed == true

                    if (videoIsMuxed) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Audio is already inside this format. Pick a DASH quality to choose a separate audio track.",
                            fontSize = 10.sp,
                            color = theme.textMuted
                        )
                    } else if (audioStreams.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        if (audioStreams.size == 1) {
                            val only = audioStreams.first()
                            val detail = buildString {
                                append(only.acodec)
                                if (only.bitrateKbps > 0) append(" ${only.bitrateKbps}k")
                                only.formattedSize.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                            }
                            Text(
                                text = "Audio: $detail (included in this video)",
                                fontSize = 11.sp,
                                color = theme.textSecondary
                            )
                        } else {
                            SelectionChipRow(
                                label = "Audio track (merged)",
                                options = listOf(SelectionChip(null, "Auto (best)")) +
                                    audioStreams.map { stream ->
                                        SelectionChip(
                                            key = stream.streamKey,
                                            title = audioStreamChipLabel(stream, audioStreams)
                                        )
                                    },
                                selectedKey = state.selectedMergeAudioStreamKey,
                                accentColor = accent.color,
                                onSelected = onMergeAudioStreamSelected
                            )
                        }

                        // Live result line: proves the pick was applied and states the real final size.
                        val effectiveAudio = state.effectiveMergeAudio
                        if (effectiveAudio != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            val isAuto = state.selectedMergeAudioStreamKey == null
                            val audioLabel = audioStreamChipLabel(effectiveAudio, audioStreams)
                                .let { if (isAuto) "$it (auto)" else it }
                            val videoBytes = state.selectedVideoOption?.videoOnlyBytes ?: 0L
                            val totalBytes = state.mergedSizeBytes
                            Text(
                                text = buildString {
                                    if (videoBytes > 0L) append("Video ${formatBytes(videoBytes)} + ")
                                    append(audioLabel)
                                    if (totalBytes > 0L) append(" = ${formatBytes(totalBytes)}")
                                    append(" · saved as ${state.effectiveOutputContainer.uppercase()}")
                                },
                                fontSize = 9.5.sp,
                                color = theme.textMuted
                            )
                        }
                    }
                } else {
                    // Playlist mode never resolves per-item variants, so expose honest
                    // preference chips instead of fabricated per-video codec options.
                    Spacer(modifier = Modifier.height(12.dp))
                    SelectionChipRow(
                        label = "Video codec preference",
                        options = listOf("Auto", "H.264", "VP9", "AV1").map { SelectionChip(it, it) },
                        selectedKey = state.batchVideoCodec,
                        accentColor = accent.color,
                        onSelected = { codec -> codec?.let(onBatchVideoCodecSelected) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionChipRow(
                        label = "Merge audio preference",
                        options = listOf("Auto", "M4A", "Opus").map { SelectionChip(it, it) },
                        selectedKey = state.batchMergeAudioExtension,
                        accentColor = accent.color,
                        onSelected = { ext -> ext?.let(onBatchMergeAudioExtensionSelected) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Auto keeps AAC in MP4 and Opus in WebM. Picking the other codec saves that playlist as MKV.",
                        fontSize = 9.5.sp,
                        color = theme.textMuted
                    )
                }
            } else {
            val distinctAudios = remember(info.audioOptions) {
                info.audioOptions.distinctBy { "${it.extension.lowercase()}_${it.bitrateKbps}" }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                distinctAudios.chunked(3).forEach { rowOptions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowOptions.forEach { audio ->
                            val isSelected = audio.extension == state.selectedAudioExtension &&
                                    audio.bitrateKbps == state.selectedAudioBitrateKbps
                            val sizeLabel = if (isBatch) calculateAudioSizeText(audio.extension, audio.bitrateKbps) else audio.formattedSize
                            val audioBadge = "${audio.acodec} ${if (audio.bitrateKbps > 0) "${audio.bitrateKbps}k" else ""}".trim()
                            val cardSubtitle = if (sizeLabel.isNotBlank()) sizeLabel else audioBadge
                            val audioTitle = "${audio.extension.uppercase()} ${if (audio.bitrateKbps > 0) "${audio.bitrateKbps}k" else ""}".trim()
                            Box(modifier = Modifier.weight(1f)) {
                                FloatingQualityGridCard(
                                    title = audioTitle,
                                    sizeText = cardSubtitle,
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

            // Seamless Playlist List with Stable Unique Key and Continuous Gesture Detection
            val density = LocalDensity.current
            val fallbackSlotHeightPx = with(density) { 40.dp.toPx() }
            val rowSpacingPx = with(density) { 2.dp.toPx() }
            val listVerticalPaddingPx = with(density) { 4.dp.toPx() }
            val listHorizontalPaddingPx = with(density) { 6.dp.toPx() }
            var listRowHeightPx by remember { mutableFloatStateOf(fallbackSlotHeightPx - rowSpacingPx) }
            var listSlotHeightPx by remember { mutableFloatStateOf(fallbackSlotHeightPx) }

            // Animated Y of the translucent drop placeholder (the slot the dragged video will land in).
            val animatedPlaceholderY by animateFloatAsState(
                targetValue = listVerticalPaddingPx + draggingTargetIndex.coerceAtLeast(0) * listSlotHeightPx,
                animationSpec = tween(140),
                label = "dropSlotOffset"
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.pillTrackBg, RoundedCornerShape(14.dp))
                    .drawBehind {
                        // Draw the destination slot behind the rows so the user always sees where the drop lands.
                        if (draggingItemUrl != null && draggingTargetIndex >= 0 && listRowHeightPx > 0f) {
                            val corner = CornerRadius(8.dp.toPx())
                            val left = listHorizontalPaddingPx
                            val slotWidth = size.width - listHorizontalPaddingPx * 2f
                            if (slotWidth > 0f) {
                                drawRoundRect(
                                    color = accent.color.copy(alpha = 0.10f),
                                    topLeft = Offset(left, animatedPlaceholderY),
                                    size = Size(slotWidth, listRowHeightPx),
                                    cornerRadius = corner
                                )
                                drawRoundRect(
                                    color = accent.color.copy(alpha = 0.75f),
                                    topLeft = Offset(left, animatedPlaceholderY),
                                    size = Size(slotWidth, listRowHeightPx),
                                    cornerRadius = corner,
                                    style = Stroke(width = 1.5.dp.toPx())
                                )
                            }
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                state.orderedBatchItems.forEachIndexed { index, item ->
                    key(item.url) {
                        val checked = state.selectedBatchUrls.contains(item.url)
                        val isDragging = draggingItemUrl == item.url
                        // rememberUpdatedState keeps the gesture closure from capturing a stale index
                        // after rows are reordered, so every subsequent drag targets the correct row.
                        val currentIndex by rememberUpdatedState(index)
                        val currentCount by rememberUpdatedState(state.orderedBatchItems.size)
                        var dragOffsetY by remember { mutableFloatStateOf(0f) }
                        var rowSlotHeightPx by remember { mutableFloatStateOf(fallbackSlotHeightPx) }

                        // Rows between the drag origin and the live drop target slide one slot over
                        // to physically open a gap where the dragged video will land.
                        val rowShiftTarget = if (draggingItemUrl != null && !isDragging && draggingFromIndex >= 0) {
                            when {
                                draggingFromIndex < draggingTargetIndex && index > draggingFromIndex && index <= draggingTargetIndex -> -rowSlotHeightPx
                                draggingTargetIndex < draggingFromIndex && index >= draggingTargetIndex && index < draggingFromIndex -> rowSlotHeightPx
                                else -> 0f
                            }
                        } else 0f
                        val animatedRowShift by animateFloatAsState(
                            targetValue = rowShiftTarget,
                            animationSpec = tween(140),
                            label = "rowShift"
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { size ->
                                    if (size.height > 0) {
                                        rowSlotHeightPx = size.height.toFloat() + rowSpacingPx
                                        listRowHeightPx = size.height.toFloat()
                                        listSlotHeightPx = size.height.toFloat() + rowSpacingPx
                                    }
                                }
                                .graphicsLayer {
                                    translationY = if (isDragging) dragOffsetY else animatedRowShift
                                    shadowElevation = if (isDragging) 8f else 0f
                                }
                                .background(
                                    if (isDragging) theme.pillBg.copy(alpha = 0.95f) else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
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
                                Text(
                                    text = String.format("%02d", index + 1),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (checked) accent.text else theme.textSecondary
                                )
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

                            Spacer(modifier = Modifier.width(4.dp))

                            // Micro Up/Down Arrows for One-Tap Reordering
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (index > 0) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .clickable { onMoveItemUp(index) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardArrowUp,
                                            contentDescription = "Move Up",
                                            tint = theme.textSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                if (index < state.orderedBatchItems.size - 1) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .clickable { onMoveItemDown(index) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = "Move Down",
                                            tint = theme.textSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(2.dp))

                            // Reorder Drag Handle (Keyed strictly to item.url so gestures never cancel mid-drag)
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .pointerInput(item.url) {
                                        detectVerticalDragGestures(
                                            onDragStart = {
                                                draggingItemUrl = item.url
                                                draggingFromIndex = currentIndex
                                                draggingTargetIndex = currentIndex
                                                dragOffsetY = 0f
                                            },
                                            onDragEnd = {
                                                val targetIndex = draggingTargetIndex
                                                if (targetIndex >= 0 && targetIndex != currentIndex) {
                                                    onMoveItem(currentIndex, targetIndex)
                                                }
                                                dragOffsetY = 0f
                                                draggingItemUrl = null
                                                draggingFromIndex = -1
                                                draggingTargetIndex = -1
                                            },
                                            onDragCancel = {
                                                dragOffsetY = 0f
                                                draggingItemUrl = null
                                                draggingFromIndex = -1
                                                draggingTargetIndex = -1
                                            },
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                val slot = rowSlotHeightPx.coerceAtLeast(1f)
                                                val maxUp = -currentIndex * slot
                                                val maxDown = ((currentCount - 1 - currentIndex).coerceAtLeast(0)) * slot
                                                dragOffsetY = (dragOffsetY + dragAmount).coerceIn(maxUp, maxDown)

                                                // Track the live drop target so the placeholder and the row gap follow the finger.
                                                val newTarget = (currentIndex + (dragOffsetY / slot).roundToInt())
                                                    .coerceIn(0, (currentCount - 1).coerceAtLeast(0))
                                                if (newTarget != draggingTargetIndex) {
                                                    draggingTargetIndex = newTarget
                                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                                            .background(
                                                if (isDragging) accent.color else theme.textSecondary.copy(alpha = 0.5f),
                                                RoundedCornerShape(1.dp)
                                            )
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(16.dp)
                                            .height(2.dp)
                                            .background(
                                                if (isDragging) accent.color else theme.textSecondary.copy(alpha = 0.5f),
                                                RoundedCornerShape(1.dp)
                                            )
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(16.dp)
                                            .height(2.dp)
                                            .background(
                                                if (isDragging) accent.color else theme.textSecondary.copy(alpha = 0.5f),
                                                RoundedCornerShape(1.dp)
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 5. Download Location Selector Row
        DownloadLocationPickerRow(
            selectedFolder = state.selectedDownloadFolder,
            onFolderSelected = onDownloadFolderSelected,
            accentColor = accent.color,
            textColor = theme.textPrimary,
            subtextColor = theme.textSecondary,
            trackBg = theme.pillTrackBg
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 6. Advanced Options Collapsible Card
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
        } // End of scrollable middle Column

        Spacer(modifier = Modifier.height(10.dp))

        // 6. Primary Action CTA Button (Sticky at Bottom)
        if (isBatch) {
            val canQueue = state.selectedBatchUrls.isNotEmpty()
            val totalSizeText = if (!state.isAudioOnlyTab) {
                calculateVideoSizeText(state.selectedVideoHeight)
            } else {
                calculateAudioSizeText(state.selectedAudioExtension, state.selectedAudioBitrateKbps)
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
                val sizeSuffix = if (totalSizeText.isNotBlank()) " • $totalSizeText" else ""
                Text(
                    text = if (canQueue) "Download Playlist (${state.selectedBatchUrls.size} Videos$sizeSuffix)" else "Select at least 1 video",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent.text
                )
            }
        } else {
            val ctaSize = if (!state.isAudioOnlyTab) {
                mergedSizeFor(state.selectedVideoOption).takeIf { it.isNotBlank() }
                    ?: calculateVideoSizeText(state.selectedVideoHeight)
            } else {
                calculateAudioSizeText(state.selectedAudioExtension, state.selectedAudioBitrateKbps)
            }
            val ctaLabel = if (!state.isAudioOnlyTab) {
                val codecStr = state.selectedVideoOption?.let { " · ${it.vcodec}" } ?: ""
                if (ctaSize.isNotBlank()) "Download ${state.selectedVideoHeight}p$codecStr ($ctaSize)" else "Download ${state.selectedVideoHeight}p$codecStr"
            } else {
                val label = "${state.selectedAudioExtension.uppercase()} ${if (state.selectedAudioBitrateKbps > 0) "${state.selectedAudioBitrateKbps}k" else ""}".trim()
                if (ctaSize.isNotBlank()) "Download $label ($ctaSize)" else "Download $label"
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
                    .background(if (checked) AppTheme.accent.text else theme.textSecondary)
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
    val theme = AppTheme.colors
    val accent = AppTheme.accent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Unable to resolve formats", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = theme.errorRed)
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, fontSize = 12.sp, color = theme.textSecondary)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(
                containerColor = accent.color,
                contentColor = accent.text
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Close", color = accent.text)
        }
    }
}

@Composable
fun DownloadLocationPickerRow(
    selectedFolder: String,
    onFolderSelected: (String) -> Unit,
    accentColor: Color,
    textColor: Color,
    subtextColor: Color,
    trackBg: Color
) {
    var showCustomDialog by remember { mutableStateOf(false) }
    var customText by remember(selectedFolder) { mutableStateOf(selectedFolder) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Download Folder",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
            }
            Text(
                text = selectedFolder,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Quick Selector Pills: Downloads, Music, Movies, Custom
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val presets = listOf("Download/YouDrop", "Music/YouDrop", "Movies/YouDrop")
            presets.forEach { preset ->
                val isSelected = selectedFolder == preset
                val label = when (preset) {
                    "Download/YouDrop" -> "Downloads"
                    "Music/YouDrop" -> "Music"
                    "Movies/YouDrop" -> "Movies"
                    else -> preset
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) accentColor.copy(alpha = 0.15f) else trackBg)
                        .border(
                            width = 1.dp,
                            color = if (isSelected) accentColor else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onFolderSelected(preset) }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) accentColor else subtextColor
                    )
                }
            }

            // Custom Folder Button
            val isCustom = !presets.contains(selectedFolder)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isCustom) accentColor.copy(alpha = 0.15f) else trackBg)
                    .border(
                        width = 1.dp,
                        color = if (isCustom) accentColor else Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { showCustomDialog = true }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Custom Folder",
                    tint = if (isCustom) accentColor else subtextColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }

    if (showCustomDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            title = { Text("Custom Download Folder", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Enter relative folder path inside public storage (e.g., Download/Podcasts):",
                        fontSize = 12.sp,
                        color = subtextColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = customText.trim().trim('/')
                        if (trimmed.isNotBlank()) {
                            onFolderSelected(trimmed)
                        }
                        showCustomDialog = false
                    }
                ) {
                    Text("Save", color = accentColor, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text("Cancel", color = subtextColor)
                }
            }
        )
    }
}
