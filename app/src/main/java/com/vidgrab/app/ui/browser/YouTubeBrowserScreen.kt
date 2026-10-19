package com.vidgrab.app.ui.browser

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vidgrab.app.core.network.NetworkConstants
import com.vidgrab.app.core.queue.QueueRepository
import com.vidgrab.app.core.webview.AdBlockEngine
import com.vidgrab.app.core.webview.YouTubeJsBridge
import com.vidgrab.app.core.webview.YouTubeJsScripts
import com.vidgrab.app.ui.downloads.DownloadsScreen
import com.vidgrab.app.ui.modal.QualityPickerBottomSheet
import com.vidgrab.app.ui.modal.QualityPickerViewModel
import com.vidgrab.app.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.URLEncoder

enum class BrowserNavScreen {
    BROWSER,
    DOWNLOADS,
    SETTINGS
}

/**
 * Custom WebView that intercepts window visibility changes to maintain continuous audio playback
 * when backgrounded or when the device screen turns off.
 */
class BraveMediaWebView(context: android.content.Context) : WebView(context) {
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (AdBlockEngine.isBackgroundPlayEnabled.value && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeBrowserScreen(
    browserViewModel: BrowserViewModel = viewModel(),
    qualityPickerViewModel: QualityPickerViewModel = viewModel()
) {
    val context = LocalContext.current
    val queueRepository = remember { QueueRepository.getInstance(context) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var currentNavScreen by remember { mutableStateOf(BrowserNavScreen.BROWSER) }

    val currentUrl by browserViewModel.currentUrl.collectAsState()
    val pageTitle by browserViewModel.pageTitle.collectAsState()
    val pageProgress by browserViewModel.pageProgress.collectAsState()
    val isLoading by browserViewModel.isLoading.collectAsState()
    val canGoBack by browserViewModel.canGoBack.collectAsState()
    val sessionTokens by browserViewModel.sessionTokens.collectAsState()
    val pendingDownload by browserViewModel.pendingDownload.collectAsState()
    val qualityPickerUiState by qualityPickerViewModel.uiState.collectAsState()
    val activeTasks by queueRepository.activeTasks.collectAsState(initial = emptyList())
    val allTasks by queueRepository.allTasks.collectAsState(initial = emptyList())

    // Pause active HTML5 video whenever the quality picker is open or a download is pending
    LaunchedEffect(pendingDownload, qualityPickerUiState) {
        if (pendingDownload != null || qualityPickerUiState != null) {
            webViewInstance?.evaluateJavascript(
                "document.querySelectorAll('video').forEach(function(v) { try { v.pause(); } catch(e){} });",
                null
            )
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Notification Permission Launcher (Android 13+ / API 33+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Timber.i("POST_NOTIFICATIONS permission granted: %b", isGranted)
    }

    // Android System Back Navigation: route through WebView history when on Browser page
    BackHandler(enabled = currentNavScreen == BrowserNavScreen.BROWSER && canGoBack) {
        webViewInstance?.goBack()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                BrowserTopBar(
                    currentUrl = currentUrl,
                    isLoading = isLoading,
                    progress = pageProgress,
                    activeTaskCount = activeTasks.size,
                    onNavigate = { targetUrl ->
                        webViewInstance?.loadUrl(targetUrl)
                    },
                    onOpenQueue = { currentNavScreen = BrowserNavScreen.DOWNLOADS },
                    onOpenSettings = { currentNavScreen = BrowserNavScreen.SETTINGS }
                )
            },
            floatingActionButton = {
            // Radiant Gold Persistent Native FAB
            val currentType = browserViewModel.classifyUrl(currentUrl)
            if (currentType != "PAGE") {
                FloatingActionButton(
                    onClick = {
                        webViewInstance?.evaluateJavascript(
                            "document.querySelectorAll('video').forEach(function(v) { try { v.pause(); } catch(e){} });",
                            null
                        )
                        val liveUrl = webViewInstance?.url ?: currentUrl
                        val liveTitle = webViewInstance?.title ?: pageTitle
                        val payload = browserViewModel.createFabDownloadPayload(overrideUrl = liveUrl, overrideTitle = liveTitle)
                        qualityPickerViewModel.load(payload)
                    },
                    containerColor = Color(0xFFE5A93C),
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download $currentType",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    BraveMediaWebView(ctx).apply {
                        webViewInstance = this
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                            // Sanitize User-Agent: strips '; wv' and 'Version/X.X' to bypass Google Sign-In Error 403
                            val rawUa = userAgentString
                            val sanitizedUa = NetworkConstants.sanitizeUserAgent(rawUa)
                            userAgentString = sanitizedUa
                            NetworkConstants.activeUserAgent = sanitizedUa
                            Timber.i("WebView User-Agent sanitized: %s", sanitizedUa)
                        }

                        // Enable Third-Party Cookies (essential for Google Auth inside YouTube mobile)
                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        // Register JavascriptInterface for BotGuard tokens, button events, and URL sync
                        val jsBridge = YouTubeJsBridge(
                            onDownloadTriggered = { payload ->
                                post {
                                    evaluateJavascript(
                                        "document.querySelectorAll('video').forEach(function(v) { try { v.pause(); } catch(e){} });",
                                        null
                                    )
                                }
                                browserViewModel.onDownloadTriggered(payload)
                                qualityPickerViewModel.load(payload)
                            },
                            onUrlChanged = { newUrl, newTitle ->
                                browserViewModel.onPageFinished(newUrl, newTitle)
                            }
                        )
                        addJavascriptInterface(jsBridge, "VidGrabBridge")

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                val targetUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                                if (AdBlockEngine.isAdBlockEnabled.value && AdBlockEngine.shouldBlockRequest(targetUrl)) {
                                    return AdBlockEngine.createEmptyResourceResponse()
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                url?.let { browserViewModel.onPageStarted(it) }
                                view?.evaluateJavascript(YouTubeJsScripts.INJECTION_SCRIPT, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                url?.let { browserViewModel.onPageFinished(it, view?.title) }
                                browserViewModel.onNavigationStateChanged(canGoBack(), canGoForward())
                                view?.evaluateJavascript(YouTubeJsScripts.INJECTION_SCRIPT, null)
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                browserViewModel.onNavigationStateChanged(canGoBack(), canGoForward())
                                url?.let { browserViewModel.onPageStarted(it) }
                                view?.evaluateJavascript(YouTubeJsScripts.INJECTION_SCRIPT, null)
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val targetUrl = request?.url?.toString() ?: return false
                                return if (targetUrl.contains("youtube.com") || targetUrl.contains("accounts.google.com") || targetUrl.contains("youtu.be")) {
                                    false
                                } else {
                                    false
                                }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                super.onProgressChanged(view, newProgress)
                                browserViewModel.onProgressChanged(newProgress)
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                super.onReceivedTitle(view, title)
                                browserViewModel.onPageFinished(view?.url.orEmpty(), title)
                            }
                        }

                        loadUrl("https://m.youtube.com")
                    }
                },
                update = { webView ->
                    webViewInstance = webView
                }
            )

            // Milestone 3: Instant Quality Picker Modal BottomSheet
            QualityPickerBottomSheet(
                viewModel = qualityPickerViewModel,
                onConfirmDownload = { spec ->
                    // Pause video inside WebView immediately to free CDN bandwidth
                    webViewInstance?.evaluateJavascript(
                        "document.querySelectorAll('video').forEach(function(v) { try { v.pause(); } catch(e){} });",
                        null
                    )
                    // Request notification permission on Android 13+ if not granted
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    // Enqueue into Room DB & WorkManager
                    queueRepository.enqueue(spec)

                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Queued: ${spec.title.take(28)}... (${spec.outputExtension.uppercase()})"
                        )
                    }
                },
                onDismiss = {
                    browserViewModel.dismissDownloadTrigger()
                }
            )
        }
    }

    // Full-Page Downloads Screen
    if (currentNavScreen == BrowserNavScreen.DOWNLOADS) {
        DownloadsScreen(
            queueRepository = queueRepository,
            onNavigateBack = { currentNavScreen = BrowserNavScreen.BROWSER }
        )
    }

    // Full-Page Settings Screen
    if (currentNavScreen == BrowserNavScreen.SETTINGS) {
        SettingsScreen(
            onNavigateBack = { currentNavScreen = BrowserNavScreen.BROWSER }
        )
    }
    }
}

@Composable
fun BrowserTopBar(
    currentUrl: String,
    isLoading: Boolean,
    progress: Int,
    activeTaskCount: Int,
    onNavigate: (String) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val clipboardManager = LocalClipboardManager.current
    var inputText by remember { mutableStateOf("") }
    var isInputFocused by remember { mutableStateOf(false) }

    LaunchedEffect(currentUrl) {
        if (!isInputFocused) {
            inputText = currentUrl
        }
    }

    fun submitSearch() {
        val trimmed = inputText.trim()
        if (trimmed.isNotBlank()) {
            val finalUrl = when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                trimmed.contains("youtube.com") || trimmed.contains("youtu.be") -> "https://$trimmed"
                else -> {
                    val encoded = try {
                        URLEncoder.encode(trimmed, "UTF-8")
                    } catch (e: Exception) {
                        trimmed
                    }
                    "https://m.youtube.com/results?search_query=$encoded"
                }
            }
            onNavigate(finalUrl)
            focusManager.clearFocus()
        }
    }

    Surface(
        color = Color(0xFFFAF8F5),
        shadowElevation = 1.dp,
        modifier = Modifier.statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Item 1: Search / URL Input Box with gold focus ring
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(Color(0xFFF3ECE7), RoundedCornerShape(20.dp))
                        .border(
                            width = 1.5.dp,
                            color = if (isInputFocused) Color(0xFFE5A93C) else Color.Transparent,
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (isInputFocused) Color(0xFFE5A93C) else Color(0xFF7F7875),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (inputText.isEmpty()) {
                                Text(
                                    text = "Search or paste YouTube URL...",
                                    fontSize = 13.sp,
                                    color = Color(0xFF9E9792),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = Color(0xFF241D1B)
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { isInputFocused = it.isFocused }
                            )
                        }
                        if (inputText.isNotEmpty()) {
                            IconButton(
                                onClick = { inputText = "" },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = Color(0xFF7F7875),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFE5A93C).copy(alpha = 0.15f))
                                    .clickable {
                                        val clip = clipboardManager.getText()?.text?.trim()
                                        if (!clip.isNullOrBlank()) {
                                            inputText = clip
                                            submitSearch()
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Paste",
                                        tint = Color(0xFFE5A93C),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "Paste",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFE5A93C)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Item 2: Downloads Icon Button (borderless, no bg, with active count badge)
                IconButton(
                    onClick = onOpenQueue,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Downloads",
                            tint = if (activeTaskCount > 0) Color(0xFFE5A93C) else Color(0xFF241D1B),
                            modifier = Modifier.size(24.dp)
                        )
                        if (activeTaskCount > 0) {
                            Box(
                                modifier = Modifier
                                    .size(15.dp)
                                    .background(Color(0xFFE5A93C), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "$activeTaskCount",
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Item 3: Settings Icon Button (borderless, no bg)
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color(0xFF241D1B),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Thin radiant gold progress indicator when loading
            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = Color(0xFFE5A93C),
                    trackColor = Color.Transparent
                )
            }
        }
    }
}
