package com.vidgrab.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidgrab.app.core.engine.YtDlpUpdater
import com.vidgrab.app.core.webview.AdBlockEngine
import kotlinx.coroutines.launch
import timber.log.Timber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val updateState by YtDlpUpdater.updateState.collectAsState()
    val isAdBlockEnabled by AdBlockEngine.isAdBlockEnabled.collectAsState()
    val isBackgroundPlayEnabled by AdBlockEngine.isBackgroundPlayEnabled.collectAsState()
    val maxConcurrent by AdBlockEngine.maxConcurrentDownloads.collectAsState()

    val prefs = remember { context.getSharedPreferences("vidgrab_engine_prefs", Context.MODE_PRIVATE) }
    var defaultAria2c by remember { mutableStateOf(prefs.getBoolean("default_use_aria2c", false)) }

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var isBatteryIgnoring by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && powerManager != null) {
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFFFAF8F5),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
                .animateContentSize()
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color(0xFFE5A93C).copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = Color(0xFFE5A93C), modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Settings & Engine Control",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color(0xFF241D1B)
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF7F7875))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // CARD 1: yt-dlp Engine & OTA Updates
            SettingsCard(
                title = "yt-dlp Core Engine (2026)",
                icon = Icons.Default.Sync
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Installed Version",
                                fontSize = 11.sp,
                                color = Color(0xFF7F7875)
                            )
                            Text(
                                text = updateState.version ?: "Checking...",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF241D1B)
                            )
                        }

                        // Status Badge
                        val badgeColor = if (updateState.isUpdating) Color(0xFFE5A93C) else Color(0xFF2C6954)
                        Text(
                            text = if (updateState.isUpdating) "UPDATING" else "READY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor,
                            modifier = Modifier
                                .background(badgeColor.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = updateState.statusMessage,
                        fontSize = 11.sp,
                        color = Color(0xFF7F7875)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                YtDlpUpdater.ensureUpdated(context, force = true)
                            }
                        },
                        enabled = !updateState.isUpdating,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE5A93C),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (updateState.isUpdating) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Updating yt-dlp to Nightly...", fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Check & Update yt-dlp Now (Nightly)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // CARD 2: Brave Browser Features
            SettingsCard(
                title = "Brave Browser Controls",
                icon = Icons.Default.Block
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // AdBlock Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Brave AdBlock & Sponsor Skip",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = Color(0xFF241D1B)
                            )
                            Text(
                                text = "Blocks ads, tracking endpoints, and auto-skips pre-rolls",
                                fontSize = 11.sp,
                                color = Color(0xFF7F7875)
                            )
                        }
                        Switch(
                            checked = isAdBlockEnabled,
                            onCheckedChange = { AdBlockEngine.setAdBlockEnabled(context, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFFE5A93C)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Background Audio Play Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Background Audio Play",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = Color(0xFF241D1B)
                            )
                            Text(
                                text = "Keeps audio playing with screen locked or app minimized",
                                fontSize = 11.sp,
                                color = Color(0xFF7F7875)
                            )
                        }
                        Switch(
                            checked = isBackgroundPlayEnabled,
                            onCheckedChange = { AdBlockEngine.setBackgroundPlayEnabled(context, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFFE5A93C)
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // CARD 3: Downloader & Power Management
            SettingsCard(
                title = "Downloader & Power Management",
                icon = Icons.Default.Speed
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Concurrent Downloads Selector (1 - 5)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Concurrent Downloads",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = Color(0xFF241D1B)
                            )
                            Text(
                                text = "Simultaneous downloads in parallel (1 to 5)",
                                fontSize = 11.sp,
                                color = Color(0xFF7F7875)
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            (1..5).forEach { count ->
                                val isSelected = count == maxConcurrent
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) Color(0xFFE5A93C) else Color(0xFFF3ECE7))
                                        .clickable { AdBlockEngine.setMaxConcurrentDownloads(context, count) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "$count",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else Color(0xFF5F5B59)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Default Aria2c Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Default Aria2c Multi-Stream",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = Color(0xFF241D1B)
                            )
                            Text(
                                text = "Uses 4 parallel connection chunks for new queue downloads",
                                fontSize = 11.sp,
                                color = Color(0xFF7F7875)
                            )
                        }
                        Switch(
                            checked = defaultAria2c,
                            onCheckedChange = { enabled ->
                                defaultAria2c = enabled
                                prefs.edit().putBoolean("default_use_aria2c", enabled).apply()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFFE5A93C)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Unrestricted Battery Optimization Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Background Battery Exemption",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = Color(0xFF241D1B)
                            )
                            Text(
                                text = if (isBatteryIgnoring) "Battery optimization is disabled (Safe)" else "App may be suspended when screen is off",
                                fontSize = 11.sp,
                                color = if (isBatteryIgnoring) Color(0xFF2C6954) else Color(0xFFE5A93C)
                            )
                        }

                        if (!isBatteryIgnoring && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            OutlinedButton(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Timber.e(e, "Could not open battery optimization settings")
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Allow", fontSize = 12.sp, color = Color(0xFFE5A93C))
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.BatteryChargingFull,
                                contentDescription = null,
                                tint = Color(0xFF2C6954),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    val theme = com.vidgrab.app.ui.theme.AppTheme.colors
    val accent = com.vidgrab.app.ui.theme.AppTheme.accent
    Surface(
        color = theme.cardBg,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, theme.dividerColor),
        shadowElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent.color, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = theme.textPrimary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
