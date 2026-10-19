package com.vidgrab.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.vidgrab.app.ui.browser.YouTubeBrowserScreen
import com.vidgrab.app.ui.theme.ThemeManager
import com.vidgrab.app.ui.theme.VidGrabTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val sharedUrlState = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        setContent {
            VidGrabTheme(context = this) {
                val themeManager = remember { ThemeManager.getInstance(this) }
                val currentTheme by themeManager.themeState.collectAsState()

                // Dynamically sync system status and navigation bar icon colors with theme mode
                val insetsController = remember(window) {
                    androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                }
                LaunchedEffect(currentTheme.isDark) {
                    insetsController.isAppearanceLightStatusBars = !currentTheme.isDark
                    insetsController.isAppearanceLightNavigationBars = !currentTheme.isDark
                }

                MaterialTheme {
                    val engineState by App.instance.engineState.collectAsState()
                    val sharedUrl by sharedUrlState.collectAsState()

                    when (engineState) {
                        is EngineInitState.Ready -> {
                            // Core engines initialized; render embedded YouTube browser
                            YouTubeBrowserScreen(
                                initialSharedUrl = sharedUrl,
                                onSharedUrlConsumed = { sharedUrlState.value = null }
                            )
                        }
                        else -> {
                            // Splash / Loading / Error screen during cold startup
                            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding)
                                        .background(currentTheme.phoneBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    EngineStatusScreen(engineState = engineState)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val url = extractUrlFromIntent(intent)
        if (!url.isNullOrBlank()) {
            sharedUrlState.value = url
        }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
            val urlRegex = Regex("""https?://[^\s]+""")
            return urlRegex.find(text)?.value
        } else if (intent.action == Intent.ACTION_VIEW) {
            return intent.dataString
        }
        return null
    }
}

@Composable
fun EngineStatusScreen(engineState: EngineInitState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(24.dp)
    ) {
        Text(
            text = "VidGrab",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFE5A93C)
        )
        Text(
            text = "2026 Dual-Engine Extraction Core",
            fontSize = 14.sp,
            color = Color(0xFF5F5B59)
        )

        Spacer(modifier = Modifier.height(32.dp))

        when (val state = engineState) {
            is EngineInitState.Uninitialized, EngineInitState.Initializing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = Color(0xFFE5A93C)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Unpacking native engines & QuickJS runtime...",
                    fontSize = 13.sp,
                    color = Color(0xFF7F7875)
                )
            }
            is EngineInitState.Ready -> {
                Text(
                    text = "✓ Core Engines Ready",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF2C6954),
                    fontSize = 18.sp
                )
            }
            is EngineInitState.Error -> {
                Text(
                    text = "⚠ Engine Initialization Failed",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFBA1A1A),
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.message,
                    fontSize = 12.sp,
                    color = Color(0xFF5F5B59)
                )
            }
        }
    }
}
