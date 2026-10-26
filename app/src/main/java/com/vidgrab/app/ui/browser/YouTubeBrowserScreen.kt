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
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import com.vidgrab.app.core.webview.WebViewSessionManager
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.vidgrab.app.ui.modal.QualityPickerUiState
import com.vidgrab.app.ui.modal.QualityPickerViewModel
import com.vidgrab.app.ui.settings.SettingsScreen
import com.vidgrab.app.ui.theme.AppTheme
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
    initialSharedUrl: String? = null,
    onSharedUrlConsumed: () -> Unit = {},
    browserViewModel: BrowserViewModel = viewModel(),
    qualityPickerViewModel: QualityPickerViewModel = viewModel()
) {
    val context = LocalContext.current
    val theme = AppTheme.colors
    val accent = AppTheme.accent
    val queueRepository = remember { QueueRepository.getInstance(context) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var currentNavScreen by remember { mutableStateOf(BrowserNavScreen.BROWSER) }

    DisposableEffect(Unit) {
        onDispose {
            webViewInstance?.apply {
                stopLoading()
                loadUrl("about:blank")
                removeJavascriptInterface("VidGrabBridge")
                destroy()
            }
            webViewInstance = null
        }
    }

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

    // Auto-open shared links directly with download popup
    LaunchedEffect(initialSharedUrl) {
        if (!initialSharedUrl.isNullOrBlank()) {
            val targetUrl = initialSharedUrl
            currentNavScreen = BrowserNavScreen.BROWSER
            webViewInstance?.let { webView ->
                if (webView.url != targetUrl) {
                    webView.loadUrl(targetUrl)
                }
            }
            browserViewModel.onPageStarted(targetUrl)
            val payload = browserViewModel.createFabDownloadPayload(
                overrideUrl = targetUrl,
                overrideTitle = "Resolving..."
            )
            qualityPickerViewModel.load(payload)
            onSharedUrlConsumed()
        }
    }

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
            containerColor = theme.phoneBg,
            contentColor = theme.textPrimary,
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
            // Large accent FAB on every media page (video, shorts, playlist, channel). It stays
            // visible while the player is on screen: the old placement used to hide it exactly
            // when a download is most likely.
            val currentType = browserViewModel.classifyUrl(currentUrl)
            AnimatedVisibility(
                visible = currentNavScreen == BrowserNavScreen.BROWSER && currentType != "PAGE",
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                FloatingActionButton(
                    onClick = {
                        val liveUrl = webViewInstance?.url ?: currentUrl
                        val liveTitle = webViewInstance?.title ?: pageTitle
                        val isPlaylist = liveUrl.contains("/playlist") || liveUrl.contains("list=")
                        val targetVid = if (isPlaylist) null else com.vidgrab.app.core.extractor.DualEngineMetadataResolver.extractVideoId(liveUrl)

                        // Pause video playback immediately
                        webViewInstance?.evaluateJavascript(
                            "document.querySelectorAll('video').forEach(function(v) { try { v.pause(); } catch(e){} });",
                            null
                        )

                        // Fast-path for single videos: In-memory live cache (<5ms)
                        if (!isPlaylist && targetVid != null && WebViewSessionManager.latestVideoId == targetVid && !WebViewSessionManager.latestPlayerResponseJson.isNullOrBlank()) {
                            val cachedPayload = browserViewModel.createFabDownloadPayload(
                                overrideUrl = liveUrl,
                                overrideTitle = liveTitle,
                                overrideInlineJson = WebViewSessionManager.latestPlayerResponseJson
                            )
                            qualityPickerViewModel.load(cachedPayload)
                            return@FloatingActionButton
                        }

                        // Show initial shimmer while payload resolves (<200ms)
                        val initialPayload = browserViewModel.createFabDownloadPayload(overrideUrl = liveUrl, overrideTitle = liveTitle)
                        qualityPickerViewModel.load(initialPayload)

                        val jsCode = """
                            (function() {
                                try {
                                    var isPlaylist = window.location.pathname.indexOf('/playlist') !== -1 || window.location.href.indexOf('list=') !== -1;
                                    var vid = isPlaylist ? null : '$targetVid';
                                    var url = '$liveUrl';
                                    var title = ${org.json.JSONObject.quote(liveTitle)};
                                    var playlistItems = isPlaylist && (typeof window.__vidgrabGetPlaylistVideos === 'function') ? window.__vidgrabGetPlaylistVideos() : [];
                                    var playlistJson = playlistItems.length > 0 ? JSON.stringify(playlistItems) : '';
                                    
                                    function deliver(finalJson) {
                                        if (window.VidGrabBridge && window.VidGrabBridge.onFabPayloadReady) {
                                            window.VidGrabBridge.onFabPayloadReady(url, title, vid, finalJson || '', playlistJson);
                                        }
                                    }

                                    if (!isPlaylist && window.__vidgrabFetchDirectPlayerJson && vid) {
                                        window.__vidgrabFetchDirectPlayerJson(vid).then(function(res) {
                                            deliver(res ? JSON.stringify(res) : '');
                                        }).catch(function() {
                                            deliver('');
                                        });
                                    } else {
                                        deliver('');
                                    }
                                } catch(e) {
                                    if (window.VidGrabBridge && window.VidGrabBridge.onFabPayloadReady) {
                                        window.VidGrabBridge.onFabPayloadReady('$liveUrl', ${org.json.JSONObject.quote(liveTitle)}, '$targetVid', '', '');
                                    }
                                }
                            })();
                        """.trimIndent()
                        webViewInstance?.evaluateJavascript(jsCode, null)
                    },
                    modifier = Modifier
                        .padding(8.dp)
                        .size(68.dp),
                    shape = CircleShape,
                    containerColor = accent.color,
                    contentColor = accent.text,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 10.dp,
                        pressedElevation = 4.dp,
                        focusedElevation = 8.dp,
                        hoveredElevation = 8.dp
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Download $currentType",
                        modifier = Modifier.size(30.dp),
                        tint = accent.text
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

                        // Hardware Acceleration & Smooth Scrolling
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        isScrollbarFadingEnabled = true

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            cacheMode = WebSettings.LOAD_DEFAULT
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

                        // ServiceWorker is allowed to run uninhibited to prevent player stream decoding failures

                        // Register JavascriptInterface for BotGuard tokens, button events, and URL sync
                        val jsBridge = YouTubeJsBridge(
                            onDownloadTriggered = { payload ->
                                post {
                                    evaluateJavascript(
                                        "document.querySelectorAll('video').forEach(function(v) { try { v.pause(); } catch(e){} });",
                                        null
                                    )
                                    // The sheet is (or is about to be) visible: stop the injected button spinner.
                                    evaluateJavascript("if (window.__vidgrabTriggerAck) window.__vidgrabTriggerAck();", null)
                                }
                                browserViewModel.onDownloadTriggered(payload)
                                qualityPickerViewModel.load(payload)
                            },
                            onPayloadRefreshed = { payload ->
                                // Only upgrade an in-flight resolve for the same URL. If the picker
                                // already showed (Success), the backlog refresh is irrelevant; if it was
                                // dismissed, we must not re-open it.
                                val current = qualityPickerViewModel.uiState.value
                                if (current is QualityPickerUiState.Loading && current.payload.url == payload.url) {
                                    Timber.i("Refreshing in-flight picker payload for %s", payload.url)
                                    qualityPickerViewModel.load(payload)
                                }
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
                                url?.let {
                                    browserViewModel.onPageStarted(it)
                                    browserViewModel.onPageFinished(it, view?.title)
                                }
                                view?.evaluateJavascript(YouTubeJsScripts.INJECTION_SCRIPT, null)
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val targetUri = request?.url ?: return false
                                val host = targetUri.host?.lowercase(java.util.Locale.ROOT) ?: return false
                                val isYouTubeOrGoogle = host == "youtube.com" || host.endsWith(".youtube.com") ||
                                        host == "youtu.be" || host.endsWith(".youtu.be") ||
                                        host == "accounts.google.com" || host == "consent.youtube.com" ||
                                        host == "myaccount.google.com"

                                return if (isYouTubeOrGoogle) {
                                    false
                                } else {
                                    try {
                                        val externalIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, targetUri).apply {
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        view?.context?.startActivity(externalIntent)
                                    } catch (e: Exception) {
                                        Timber.w(e, "Failed to launch external browser for %s", targetUri)
                                    }
                                    true
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

                        val startUrl = if (!initialSharedUrl.isNullOrBlank()) initialSharedUrl else "https://m.youtube.com"
                        loadUrl(startUrl)
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
            onNavigateBack = { currentNavScreen = BrowserNavScreen.BROWSER },
            onOpenUrl = { url ->
                currentNavScreen = BrowserNavScreen.BROWSER
                webViewInstance?.loadUrl(url)
            }
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

    val theme = AppTheme.colors
    val accent = AppTheme.accent

    Surface(
        color = theme.phoneBg,
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
                // Item 1: Search / URL Input Box with dynamic accent focus ring
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .background(theme.pillBg, RoundedCornerShape(20.dp))
                        .border(
                            width = 1.5.dp,
                            color = if (isInputFocused) accent.color else Color.Transparent,
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
                            tint = if (isInputFocused) accent.color else theme.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (inputText.isEmpty()) {
                                Text(
                                    text = "Search or paste YouTube URL...",
                                    fontSize = 13.sp,
                                    color = theme.textMuted,
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
                                    color = theme.textPrimary
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { isInputFocused = it.isFocused }
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (inputText.isNotEmpty()) {
                                IconButton(
                                    onClick = { inputText = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = theme.textSecondary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(theme.pillTrackBg)
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
                                        tint = accent.color,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "Paste",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = accent.color
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Item 2: Downloads Button (Clean capsule pill when active, standard icon when idle)
                if (activeTaskCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.color.copy(alpha = 0.18f))
                            .clickable(onClick = onOpenQueue)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = "Downloads",
                                tint = accent.color,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "$activeTaskCount",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = accent.color
                            )
                        }
                    }
                } else {
                    IconButton(
                        onClick = onOpenQueue,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Downloads",
                            tint = theme.textPrimary,
                            modifier = Modifier.size(22.dp)
                        )
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
                        tint = theme.textPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Thin radiant accent progress indicator when loading
            AnimatedVisibility(
                visible = isLoading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = accent.color,
                    trackColor = Color.Transparent
                )
            }
        }
    }
}
