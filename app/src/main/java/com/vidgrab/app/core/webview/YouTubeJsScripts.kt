package com.vidgrab.app.core.webview

/**
 * Self-contained, production-grade JavaScript payload injected into the embedded m.youtube.com WebView.
 *
 * Architecture & Defenses:
 * 1. Idempotency Guard: Ensured by window.__VIDGRAB_INJECTED__.
 * 2. BotGuard & Session Token Interceptor:
 *    - Monkey-patches window.fetch & XMLHttpRequest targeting /youtubei/v1/player to capture
 *      serviceIntegrityDimensions.poToken and context.client.visitorData.
 *    - Intercepts window.ytInitialPlayerResponse for sub-50ms Tier 1 format parsing.
 * 3. Shadow DOM Button Injection:
 *    - Icon-only, non-intrusive floating circular pill button on video cards, playlists, and channels.
 *    - Strict event.stopPropagation() & event.preventDefault() prevents navigating the WebView when tapping download.
 */
object YouTubeJsScripts {

    val INJECTION_SCRIPT: String = """
(function() {
    // =========================================================================
    // SECTION 0: Brave-Style Background Play (Page Visibility API Spoof)
    // =========================================================================
    try {
        Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
        Object.defineProperty(document, 'webkitHidden', { get: function() { return false; }, configurable: true });
        Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
        Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });

        var origDocAddEventListener = document.addEventListener;
        document.addEventListener = function(type, listener, options) {
            if (type === 'visibilitychange' || type === 'webkitvisibilitychange') {
                return;
            }
            return origDocAddEventListener.apply(this, arguments);
        };

        var origWinAddEventListener = window.addEventListener;
        window.addEventListener = function(type, listener, options) {
            if (type === 'visibilitychange' || type === 'webkitvisibilitychange') {
                return;
            }
            return origWinAddEventListener.apply(this, arguments);
        };
    } catch (e) {
        console.error('[VidGrab] Visibility spoof error:', e);
    }

    // =========================================================================
    // SECTION 0.5: Brave-Style AdBlock & Fast Ad-Skipper
    // =========================================================================
    function injectAdBlockStyles() {
        if (document.getElementById('__vidgrab_adblock_style__')) return;
        try {
            var style = document.createElement('style');
            style.id = '__vidgrab_adblock_style__';
            style.textContent = '' +
                'ytm-promoted-sparkles-web-renderer, ' +
                'ytm-promoted-video-renderer, ' +
                'ytm-companion-ad-renderer, ' +
                'ytm-statement-banner-renderer, ' +
                '.ytp-ad-overlay-container, ' +
                '.ytp-ad-message-container, ' +
                '.ad-showing .html5-video-player-ad, ' +
                'ytd-action-companion-ad-renderer { display: none !important; }';
            (document.head || document.documentElement).appendChild(style);
        } catch (e) {}
    }
    injectAdBlockStyles();

    function autoSkipVideoAds() {
        try {
            var video = document.querySelector('.ad-showing video, video.ad-interrupting');
            if (video) {
                video.muted = true;
                if (isFinite(video.duration) && video.duration > 0) {
                    video.currentTime = video.duration;
                }
            }
            var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-container button');
            if (skipBtn) {
                skipBtn.click();
            }
        } catch (e) {}
    }
    setInterval(autoSkipVideoAds, 100);

    if (window.__VIDGRAB_INJECTED__) {
        if (window.__vidgrabScanNodes) window.__vidgrabScanNodes();
        injectAdBlockStyles();
        return;
    }
    window.__VIDGRAB_INJECTED__ = true;

    // =========================================================================
    // SECTION 1: BotGuard (poToken, visitorData) & Player Response Interceptor
    // =========================================================================

    function extractTokensFromPayload(payload) {
        if (!payload || typeof payload !== 'object') return;
        try {
            var poToken = null;
            var visitorData = null;

            if (payload.serviceIntegrityDimensions && payload.serviceIntegrityDimensions.poToken) {
                poToken = payload.serviceIntegrityDimensions.poToken;
            }
            if (payload.context && payload.context.client && payload.context.client.visitorData) {
                visitorData = payload.context.client.visitorData;
            }

            if (poToken || visitorData) {
                if (window.VidGrabBridge && window.VidGrabBridge.onSessionTokens) {
                    window.VidGrabBridge.onSessionTokens(poToken || '', visitorData || '');
                }
            }
        } catch (e) {
            console.error('[VidGrab] Token extract error:', e);
        }
    }

    function checkInitialPlayerResponse() {
        try {
            if (window.ytInitialPlayerResponse && window.ytInitialPlayerResponse.videoDetails) {
                var vid = window.ytInitialPlayerResponse.videoDetails.videoId;
                var jsonStr = JSON.stringify(window.ytInitialPlayerResponse);
                if (window.VidGrabBridge && window.VidGrabBridge.onPlayerResponse) {
                    window.VidGrabBridge.onPlayerResponse(vid, jsonStr);
                }
            }
        } catch (e) {}
    }

    var originalFetch = window.fetch;
    window.fetch = function(input, init) {
        try {
            var url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
            if (url.indexOf('/youtubei/v1/player') !== -1 && init && init.body) {
                try {
                    var bodyObj = JSON.parse(init.body);
                    extractTokensFromPayload(bodyObj);
                } catch (e) {}
            }
        } catch (e) {}

        return originalFetch.apply(this, arguments).then(function(response) {
            try {
                var respUrl = response.url || (typeof input === 'string' ? input : '');
                if (respUrl.indexOf('/youtubei/v1/player') !== -1) {
                    response.clone().json().then(function(data) {
                        extractTokensFromPayload(data);
                        if (data && data.videoDetails && data.videoDetails.videoId) {
                            if (window.VidGrabBridge && window.VidGrabBridge.onPlayerResponse) {
                                window.VidGrabBridge.onPlayerResponse(data.videoDetails.videoId, JSON.stringify(data));
                            }
                        }
                    }).catch(function() {});
                }
            } catch (e) {}
            return response;
        });
    };

    var originalOpen = XMLHttpRequest.prototype.open;
    var originalSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url) {
        this.__vidgrabUrl = url;
        return originalOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function(body) {
        try {
            if (this.__vidgrabUrl && this.__vidgrabUrl.indexOf('/youtubei/v1/player') !== -1 && body) {
                try {
                    var bodyObj = JSON.parse(body);
                    extractTokensFromPayload(bodyObj);
                } catch (e) {}
            }
            this.addEventListener('load', function() {
                try {
                    if (this.__vidgrabUrl && this.__vidgrabUrl.indexOf('/youtubei/v1/player') !== -1) {
                        var data = JSON.parse(this.responseText);
                        extractTokensFromPayload(data);
                        if (data && data.videoDetails && data.videoDetails.videoId) {
                            if (window.VidGrabBridge && window.VidGrabBridge.onPlayerResponse) {
                                window.VidGrabBridge.onPlayerResponse(data.videoDetails.videoId, JSON.stringify(data));
                            }
                        }
                    }
                } catch (e) {}
            });
        } catch (e) {}
        return originalSend.apply(this, arguments);
    };

    function notifyUrlChange() {
        try {
            if (window.VidGrabBridge && window.VidGrabBridge.onUrlChanged) {
                window.VidGrabBridge.onUrlChanged(window.location.href, document.title);
            }
        } catch (e) {}
    }

    try {
        var origPushState = history.pushState;
        history.pushState = function() {
            var ret = origPushState.apply(this, arguments);
            setTimeout(notifyUrlChange, 100);
            return ret;
        };
        var origReplaceState = history.replaceState;
        history.replaceState = function() {
            var ret = origReplaceState.apply(this, arguments);
            setTimeout(notifyUrlChange, 100);
            return ret;
        };
    } catch (e) {}

    window.addEventListener('popstate', notifyUrlChange);
    window.addEventListener('yt-navigate-finish', function() {
        notifyUrlChange();
        setTimeout(checkInitialPlayerResponse, 200);
        setTimeout(window.__vidgrabScanNodes, 300);
    });
    window.addEventListener('load', function() {
        notifyUrlChange();
        checkInitialPlayerResponse();
        setTimeout(window.__vidgrabScanNodes, 300);
    });

    // =========================================================================
    // SECTION 2: Compact Icon-Only Shadow DOM Button Injection Layer
    // =========================================================================

    var TARGET_SELECTORS = [
        'ytm-rich-item-renderer',
        'ytm-video-with-context-renderer',
        'ytm-compact-video-renderer',
        'ytm-playlist-video-renderer',
        'ytm-reel-item-renderer',
        'ytm-slim-video-metadata-section-renderer',
        'ytm-playlist-header-renderer',
        'ytm-c4-tabbed-header-renderer',
        'yt-page-header-view-model'
    ].join(',');

    function createShadowButton(targetNode) {
        if (targetNode.querySelector('[data-vidgrab-host]')) return;

        var host = document.createElement('div');
        host.setAttribute('data-vidgrab-host', 'true');
        host.style.cssText = 'position: relative; display: inline-flex; align-items: center; justify-content: center; z-index: 9999; margin: 4px;';

        var shadow = host.attachShadow({ mode: 'open' });

        var style = document.createElement('style');
        style.textContent = `
            .vg-icon-btn {
                display: inline-flex;
                align-items: center;
                justify-content: center;
                width: 34px;
                height: 34px;
                background: #E5A93C;
                color: #FFFFFF;
                border: none;
                border-radius: 50%;
                cursor: pointer;
                box-shadow: 0 3px 8px rgba(229, 169, 60, 0.45);
                transition: transform 0.12s cubic-bezier(0.4, 0, 0.2, 1), background-color 0.15s ease;
                user-select: none;
                -webkit-tap-highlight-color: transparent;
                padding: 0;
            }
            .vg-icon-btn:active {
                transform: scale(0.90);
                background: #d99b26;
            }
            .vg-icon-btn svg {
                width: 18px;
                height: 18px;
                fill: currentColor;
            }
        `;

        var btn = document.createElement('button');
        btn.className = 'vg-icon-btn';
        btn.setAttribute('aria-label', 'Download');
        btn.innerHTML = `
            <svg viewBox="0 0 24 24">
                <path d="M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96zM17 13l-5 5-5-5h3V9h4v4h3z"/>
            </svg>
        `;

        function handleDownloadTrigger(e) {
            e.stopPropagation();
            e.stopImmediatePropagation();
            e.preventDefault();

            // Pause active HTML5 video to avoid CDN connection competition
            try {
                document.querySelectorAll('video').forEach(function(v) {
                    try { v.pause(); } catch(err) {}
                });
            } catch (err) {}

            var isWatchPage = window.location.pathname.indexOf('/watch') !== -1 || window.location.pathname.indexOf('/shorts/') !== -1;
            var isMetadataSection = targetNode.matches('ytm-slim-video-metadata-section-renderer');

            var rawUrl = window.location.href;
            var itemType = 'VIDEO';

            if (isMetadataSection && isWatchPage) {
                // Downloading the active video player on the watch page
                rawUrl = window.location.href;
                itemType = 'VIDEO';
            } else if (targetNode.matches('ytm-playlist-header-renderer') || window.location.pathname.indexOf('/playlist') !== -1) {
                var plAnchor = targetNode.querySelector('a[href*="/playlist"]');
                rawUrl = plAnchor ? plAnchor.href : window.location.href;
                itemType = 'PLAYLIST';
            } else if (targetNode.matches('ytm-c4-tabbed-header-renderer, yt-page-header-view-model') || (!isWatchPage && (window.location.pathname.indexOf('/@') !== -1 || window.location.pathname.indexOf('/channel/') !== -1))) {
                var chAnchor = targetNode.querySelector('a[href*="/@"], a[href*="/channel/"]');
                rawUrl = chAnchor ? chAnchor.href : window.location.href;
                itemType = 'CHANNEL';
            } else {
                // Video card (in feed, search results, or related list)
                var videoAnchor = targetNode.querySelector('a[href*="/watch"], a[href*="/shorts/"]');
                if (videoAnchor) {
                    rawUrl = videoAnchor.href;
                    itemType = 'VIDEO';
                } else {
                    rawUrl = window.location.href;
                    itemType = isWatchPage ? 'VIDEO' : 'PAGE';
                }
            }

            var titleEl = targetNode.querySelector('.media-item-headline, h3, .slim-video-information-title, .yt-page-header-view-model-wiz__title');
            var title = titleEl ? titleEl.innerText.trim() : document.title;

            // Extract target video ID
            var vidMatch = rawUrl.match(/(?:v=|\/shorts\/|youtu\.be\/)([a-zA-Z0-9_-]{11})/);
            var targetVid = vidMatch ? vidMatch[1] : null;

            var inlinePlayerJson = '';
            // Strictly guard: only send inlinePlayerJson if targetVid EXACTLY matches ytInitialPlayerResponse
            if (targetVid && window.ytInitialPlayerResponse && window.ytInitialPlayerResponse.videoDetails && window.ytInitialPlayerResponse.videoDetails.videoId === targetVid && window.ytInitialPlayerResponse.streamingData) {
                try {
                    inlinePlayerJson = JSON.stringify(window.ytInitialPlayerResponse);
                } catch (err) {}
            }

            if (window.VidGrabBridge && window.VidGrabBridge.onDownloadClicked) {
                window.VidGrabBridge.onDownloadClicked(rawUrl, itemType, title, inlinePlayerJson);
            }
        }

        btn.addEventListener('click', handleDownloadTrigger, true);
        btn.addEventListener('touchend', handleDownloadTrigger, true);

        shadow.appendChild(style);
        shadow.appendChild(btn);

        var insertionTarget = targetNode.querySelector('.media-item-metadata, .compact-media-item-metadata, .slim-video-metadata-actions, .playlist-header-content, .c4-tabbed-header-sub-menu');
        if (insertionTarget) {
            insertionTarget.appendChild(host);
        } else {
            targetNode.appendChild(host);
        }
    }

    window.__vidgrabScanNodes = function() {
        var elements = document.querySelectorAll(TARGET_SELECTORS);
        for (var i = 0; i < elements.length; i++) {
            createShadowButton(elements[i]);
        }
    };

    var observer = new MutationObserver(function(mutations) {
        window.__vidgrabScanNodes();
    });

    observer.observe(document.documentElement, {
        childList: true,
        subtree: true
    });

    window.__vidgrabScanNodes();
})();
    """.trimIndent()
}
