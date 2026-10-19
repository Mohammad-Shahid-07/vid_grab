package com.vidgrab.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {
    BackHandler { onNavigateBack() }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val updateState by YtDlpUpdater.updateState.collectAsState()
    val isAdBlockEnabled by AdBlockEngine.isAdBlockEnabled.collectAsState()
    val isBackgroundPlayEnabled by AdBlockEngine.isBackgroundPlayEnabled.collectAsState()
    val maxConcurrent by AdBlockEngine.maxConcurrentDownloads.collectAsState()

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

    Scaffold(
        containerColor = Color(0xFFFAF8F5),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = Color(0xFF241D1B)
                    )
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
                .verticalScroll(rememberScrollState())
                .animateContentSize()
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // SECTION 1: DOWNLOAD ENGINE CONCURRENCY
            SettingsCard(title = "Download Engine Concurrency", icon = Icons.Default.Speed) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Parallel Downloads",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Color(0xFF241D1B)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Controls simultaneous downloads (2-3 recommended, up to 5).",
                        fontSize = 12.sp,
                        color = Color(0xFF7F7875)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 2, 3, 4, 5).forEach { count ->
                            val isSelected = count == maxConcurrent
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Color(0xFFE5A93C) else Color(0xFFF3ECE7))
                                    .clickable {
                                        AdBlockEngine.setMaxConcurrentDownloads(context, count)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$count",
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.sp,
                                    color = if (isSelected) Color.White else Color(0xFF5F5B59)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // SECTION 2: BROWSER & PRIVACY
            SettingsCard(title = "Browser & Playback", icon = Icons.Default.Block) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Brave AdBlock & Shield",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = Color(0xFF241D1B)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Blocks video pre-rolls, banner ads, and tracking telemetry.",
                                fontSize = 12.sp,
                                color = Color(0xFF7F7875)
                            )
                        }
                        Switch(
                            checked = isAdBlockEnabled,
                            onCheckedChange = { AdBlockEngine.setAdBlockEnabled(context, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFFE5A93C),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFFD6CEC7)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Background Audio Playback",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = Color(0xFF241D1B)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Maintains continuous audio when screen is turned off or app is minimized.",
                                fontSize = 12.sp,
                                color = Color(0xFF7F7875)
                            )
                        }
                        Switch(
                            checked = isBackgroundPlayEnabled,
                            onCheckedChange = { AdBlockEngine.setBackgroundPlayEnabled(context, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFFE5A93C),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFFD6CEC7)
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // SECTION 3: STORAGE LOCATION
            SettingsCard(title = "Storage & Destination", icon = Icons.Default.Folder) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Public Downloads Directory",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Color(0xFF241D1B)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFF3ECE7),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "/storage/emulated/0/Download/VidGrab/",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFF4A4441),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "All downloaded files are automatically cataloged into Android's system MediaStore.",
                        fontSize = 11.sp,
                        color = Color(0xFF7F7875)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // SECTION 4: ENGINE DIAGNOSTICS & OTA UPDATE
            SettingsCard(title = "Extraction Engine", icon = Icons.Default.Sync) {
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
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        if (updateState.isUpdating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Updating yt-dlp & QuickJS...", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Check for Nightly Engine OTA Update", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // SECTION 5: BATTERY OPTIMIZATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isBatteryIgnoring) {
                SettingsCard(title = "Background Stability", icon = Icons.Default.BatteryChargingFull) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Unrestricted Battery",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = Color(0xFF241D1B)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Disable battery optimization so large downloads are not killed by OEM power managers.",
                            fontSize = 12.sp,
                            color = Color(0xFF7F7875)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Timber.w(e, "Could not open battery optimization intent")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5A93C)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        ) {
                            Text("Allow Unrestricted Battery", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
