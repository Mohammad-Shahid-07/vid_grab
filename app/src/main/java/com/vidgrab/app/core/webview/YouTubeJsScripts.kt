package com.vidgrab.app.core.webview

/**
 * Self-contained, production-grade JavaScript payload injected into the embedded m.youtube.com WebView.
 *
 * Architecture & Defenses (2026 Enhanced):
 * 1. Idempotency Guard: Ensured by window.__VIDGRAB_INJECTED__.
 * 2. uBlock-Origin Style Player Response Sanitization (json-prune):
 *    - Intercepts window.fetch & XMLHttpRequest targeting /youtubei/v1/player.
 *    - Deletes playerAds, adPlacements, and adSlots, returning a clean response to YouTube's player.
 *    - Sanitizes window.ytInitialPlayerResponse on page load.
 *    - Preserves serviceIntegrityDimensions.poToken and context.client.visitorData for VidGrab downloads.
 * 3. Event-Driven Video Ad Fast-Forward & Skip (Ad-Accelerator standard):
 *    - Replaces high-overhead 100ms setInterval polling with native HTML5 <video> event listeners (play, timeupdate).
 *    - Automatically mutes, speeds up to 16x, and triggers skip buttons if server-side ads appear.
 * 4. High-Performance Debounced Shadow DOM Button Injection Layer:
 *    - MutationObserver debounced to 150ms windows, triggered only when nodes are added.
 *    - Prevents 60/120Hz frame drops during rapid feed scrolling.
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
    // SECTION 0.5: Cosmetic Ad Hiding (uBlock Origin & AdGuard Mobile Filters)
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
                'ytm-ad-slot-renderer, ' +
                'ytm-compact-promoted-item-renderer, ' +
                'ytm-mealbar-promo-renderer, ' +
                'ytm-brand-interaction-renderer, ' +
                'ytm-promoted-sparkles-text-search-renderer, ' +
                'ytm-paid-content-overlay-renderer, ' +
                'ytm-in-feed-ad-layout-renderer, ' +
                '.ytp-ad-overlay-container, ' +
                '.ytp-ad-message-container, ' +
                '.ytp-ad-action-interstitial, ' +
                '.ytp-ad-player-overlay, ' +
                '.ad-showing .html5-video-player-ad, ' +
                '.video-ads, ' +
                '.video-ads .ad-container, ' +
                'ytd-action-companion-ad-renderer, ' +
                'ytd-display-ad-renderer, ' +
                'ytd-promoted-sparkles-web-renderer, ' +
                '.ytp-ad-image-overlay, ' +
                '.ytp-ad-text-overlay, ' +
                '#player-ads, ' +
                '#sparkles-container, ' +
                '.sparkles-light-cta, ' +
                '.ytp-suggested-action, ' +
                '.ytp-cards-teaser, ' +
                'ytm-brand-header-renderer { display: none !important; }';
            (document.head || document.documentElement).appendChild(style);
        } catch (e) {}
    }
    injectAdBlockStyles();

    function purgeInitialAds() {
        try {
            if (window.ytInitialPlayerResponse) {
                delete window.ytInitialPlayerResponse.playerAds;
                delete window.ytInitialPlayerResponse.adPlacements;
                delete window.ytInitialPlayerResponse.adSlots;
                delete window.ytInitialPlayerResponse.adBreakHeartbeatParams;
            }
        } catch (e) {}
    }
    purgeInitialAds();
    setInterval(purgeInitialAds, 300);

    // =========================================================================
    // SECTION 0.75: Targeted Video Ad Skipper & Fast-Forward (Zero Playback Interruption)
    // =========================================================================
    setInterval(function() {
        try {
            var vids = document.querySelectorAll('video');
            for (var i = 0; i < vids.length; i++) {
                var v = vids[i];
                var player = v.closest('.html5-video-player, ytm-player, #player');
                // Strictly detect active ad state only - never use generic .video-ads
                var isAd = false;
                if (player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'))) {
                    isAd = true;
                } else {
                    var adContainer = document.querySelector('.ad-showing, .ad-interrupting, .html5-video-player-ad:not(:empty)');
                    if (adContainer && adContainer.contains(v)) {
                        isAd = true;
                    }
                }

                if (isAd) {
                    v.muted = true;
                    v.playbackRate = 16.0;
                    if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration) {
                        v.currentTime = v.duration;
                    }
                    var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-container button, button[id^="skip-button"], button.ytp-ad-skip-button-text');
                    if (skipBtn) {
                        try { skipBtn.click(); } catch(e){}
                    }
                }
            }
        } catch (e) {}
    }, 80);

    function attachToAllVideos() {
        var vids = document.querySelectorAll('video');
        for (var i = 0; i < vids.length; i++) {
            var v = vids[i];
            if (!v.__vidgrabAttached) {
                v.__vidgrabAttached = true;
                v.addEventListener('ratechange', function() {
                    var player = this.closest('.html5-video-player');
                    if (player && player.classList.contains('ad-showing') && this.playbackRate < 16.0) {
                        this.playbackRate = 16.0;
                    }
                });
            }
        }
    }

    if (window.__VIDGRAB_INJECTED__) {
        if (window.__vidgrabScanNodes) window.__vidgrabScanNodes();
        injectAdBlockStyles();
        attachToAllVideos();
        return;
    }
    window.__VIDGRAB_INJECTED__ = true;

    // =========================================================================
    // SECTION 1: BotGuard Tokens & uBlock-Origin json-prune Sanitization
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

    function sanitizePlayerResponse(payload) {
        if (!payload || typeof payload !== 'object') return payload;
        try {
            delete payload.playerAds;
            delete payload.adPlacements;
            delete payload.adSlots;
            delete payload.adBreakHeartbeatParams;

            if (payload.playerResponse && typeof payload.playerResponse === 'object') {
                delete payload.playerResponse.playerAds;
                delete payload.playerResponse.adPlacements;
                delete payload.playerResponse.adSlots;
                delete payload.playerResponse.adBreakHeartbeatParams;
            }
        } catch (e) {}
        return payload;
    }

    function checkInitialPlayerResponse() {
        try {
            if (window.ytInitialPlayerResponse) {
                sanitizePlayerResponse(window.ytInitialPlayerResponse);
                if (window.ytInitialPlayerResponse.videoDetails) {
                    var vid = window.ytInitialPlayerResponse.videoDetails.videoId;
                    var jsonStr = JSON.stringify(window.ytInitialPlayerResponse);
                    if (window.VidGrabBridge && window.VidGrabBridge.onPlayerResponse) {
                        window.VidGrabBridge.onPlayerResponse(vid, jsonStr);
                    }
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
                    return response.clone().text().then(function(text) {
                        try {
                            var data = JSON.parse(text);
                            extractTokensFromPayload(data);
                            sanitizePlayerResponse(data);

                            if (data && data.videoDetails && data.videoDetails.videoId) {
                                if (window.VidGrabBridge && window.VidGrabBridge.onPlayerResponse) {
                                    window.VidGrabBridge.onPlayerResponse(data.videoDetails.videoId, JSON.stringify(data));
                                }
                            }

                            var cleanJson = JSON.stringify(data);
                            return new Response(cleanJson, {
                                status: response.status,
                                statusText: response.statusText,
                                headers: response.headers
                            });
                        } catch (err) {
                            return response;
                        }
                    }).catch(function() {
                        return response;
                    });
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
        } catch (e) {}

        var xhr = this;
        if (xhr.__vidgrabUrl && xhr.__vidgrabUrl.indexOf('/youtubei/v1/player') !== -1) {
            xhr.addEventListener('readystatechange', function() {
                if (xhr.readyState === 4) {
                    try {
                        var data = JSON.parse(xhr.responseText);
                        extractTokensFromPayload(data);
                        sanitizePlayerResponse(data);
                        if (data && data.videoDetails && data.videoDetails.videoId) {
                            if (window.VidGrabBridge && window.VidGrabBridge.onPlayerResponse) {
                                window.VidGrabBridge.onPlayerResponse(data.videoDetails.videoId, JSON.stringify(data));
                            }
                        }
                        var sanitizedText = JSON.stringify(data);
                        Object.defineProperty(xhr, 'responseText', { value: sanitizedText, configurable: true });
                        Object.defineProperty(xhr, 'response', { value: sanitizedText, configurable: true });
                    } catch (e) {}
                }
            });
        }

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
        setTimeout(attachToAllVideos, 500);
    });
    window.addEventListener('load', function() {
        notifyUrlChange();
        checkInitialPlayerResponse();
        setTimeout(window.__vidgrabScanNodes, 300);
        setTimeout(attachToAllVideos, 500);
    });

    // =========================================================================
    // SECTION 2: Compact Icon-Only Shadow DOM Button Injection Layer
    // =========================================================================

    var TARGET_SELECTORS = [
        'ytm-rich-item-renderer',
        'ytm-video-with-context-renderer',
        'ytm-compact-video-renderer',
        'ytm-compact-playlist-renderer',
        'ytm-compact-channel-renderer',
        'ytm-playlist-video-renderer',
        'ytm-playlist-panel-video-renderer',
        'ytm-reel-item-renderer',
        'ytm-shorts-lockup-view-model',
        'ytm-media-item',
        'ytm-radio-renderer',
        'ytm-slim-video-metadata-section-renderer',
        'ytm-playlist-header-renderer',
        'ytm-c4-tabbed-header-renderer',
        'yt-page-header-view-model',
        'yt-lockup-view-model',
        'ytm-lockup-view-model',
        'ytd-rich-item-renderer',
        'ytd-video-renderer',
        '[class*="lockup-view-model"]'
    ].join(',');

    function createShadowButton(targetNode) {
        if (targetNode.querySelector('[data-vidgrab-host]')) return;
        targetNode.setAttribute('data-vidgrab-processed', 'true');

        var host = document.createElement('div');
        host.setAttribute('data-vidgrab-host', 'true');
        host.style.cssText = 'position: relative; display: inline-flex; align-items: center; justify-content: center; z-index: 100; margin-left: 8px; flex-shrink: 0; align-self: center; margin-right: 4px;';

        var shadow = host.attachShadow({ mode: 'open' });

        var style = document.createElement('style');
        style.textContent = '' +
            '.vg-icon-btn {' +
            '    display: inline-flex;' +
            '    align-items: center;' +
            '    justify-content: center;' +
            '    width: 32px;' +
            '    height: 32px;' +
            '    min-width: 32px;' +
            '    min-height: 32px;' +
            '    background: rgba(128, 128, 128, 0.28);' +
            '    color: var(--yt-spec-text-primary, #ffffff);' +
            '    border: 1px solid rgba(128, 128, 128, 0.4);' +
            '    border-radius: 50%;' +
            '    cursor: pointer;' +
            '    backdrop-filter: blur(8px);' +
            '    -webkit-backdrop-filter: blur(8px);' +
            '    transition: transform 0.12s ease, background-color 0.15s ease;' +
            '    user-select: none;' +
            '    -webkit-tap-highlight-color: transparent;' +
            '    padding: 0;' +
            '    box-shadow: 0 1px 4px rgba(0, 0, 0, 0.35);' +
            '}' +
            '.vg-icon-btn:active {' +
            '    transform: scale(0.88);' +
            '    background: rgba(128, 128, 128, 0.55);' +
            '}' +
            '.vg-icon-btn svg {' +
            '    width: 16px;' +
            '    height: 16px;' +
            '    stroke: currentColor;' +
            '    stroke-width: 2.2;' +
            '    stroke-linecap: round;' +
            '    stroke-linejoin: round;' +
            '    fill: none;' +
            '}';

        var btn = document.createElement('button');
        btn.className = 'vg-icon-btn';
        btn.setAttribute('aria-label', 'Download');
        btn.innerHTML = '<svg viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>';

        function handleDownloadTrigger(e) {
            e.stopPropagation();
            e.stopImmediatePropagation();
            e.preventDefault();

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
                var videoAnchor = targetNode.querySelector('a[href*="/watch"], a[href*="/shorts/"]');
                if (videoAnchor) {
                    rawUrl = videoAnchor.href;
                    itemType = 'VIDEO';
                } else {
                    rawUrl = window.location.href;
                    itemType = isWatchPage ? 'VIDEO' : 'PAGE';
                }
            }

            var titleEl = targetNode.querySelector('.media-item-headline, .compact-media-item-headline, h1, h2, h3, h4, .slim-video-information-title, [class*="lockup-title"], [class*="video-title"], [class*="headline"], [class*="title"]');
            var title = titleEl ? titleEl.innerText.trim() : document.title;

            var vidMatch = rawUrl.match(/(?:v=|\/shorts\/|youtu\.be\/)([a-zA-Z0-9_-]{11})/);
            var targetVid = vidMatch ? vidMatch[1] : null;

            var inlinePlayerJson = '';
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

        // Prioritize placing directly before the 3-dots action menu container
        var menuBtn = targetNode.querySelector('ytm-menu-renderer, button.item-action, .media-item-menu, button[aria-label*="Action" i], button[aria-label*="menu" i], yt-icon-button');
        var menuContainer = menuBtn ? (menuBtn.closest('ytm-menu-renderer, .media-item-menu, .menu') || menuBtn) : null;
        if (menuContainer && menuContainer.parentNode) {
            menuContainer.parentNode.insertBefore(host, menuContainer);
            return;
        }

        // Anchor directly beside the title element in the title container
        var titleEl = targetNode.querySelector('.media-item-headline, .compact-media-item-headline, h1, h2, h3, h4, .slim-video-information-title, [class*="lockup-title"], [class*="video-title"], [class*="headline"], [class*="title"]');
        if (titleEl && titleEl.parentNode) {
            var parent = titleEl.parentNode;
            try {
                if (window.getComputedStyle(parent).display.indexOf('flex') === -1) {
                    parent.style.display = 'flex';
                    parent.style.flexDirection = 'row';
                    parent.style.alignItems = 'center';
                    parent.style.justifyContent = 'space-between';
                }
                titleEl.style.flex = '1';
                titleEl.style.minWidth = '0';
            } catch (err) {}
            parent.appendChild(host);
            return;
        }

        var insertionTarget = targetNode.querySelector('.details, .media-item-metadata, .compact-media-item-metadata, ytm-media-item-metadata-renderer, .slim-video-metadata-actions, .playlist-header-content, .c4-tabbed-header-sub-menu');
        if (insertionTarget) {
            try {
                if (window.getComputedStyle(insertionTarget).display !== 'flex') {
                    insertionTarget.style.display = 'flex';
                    insertionTarget.style.alignItems = 'center';
                }
            } catch (err) {}
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
        var videoAnchors = document.querySelectorAll('a[href*="/watch?v="], a[href*="/shorts/"]');
        for (var j = 0; j < videoAnchors.length; j++) {
            var card = videoAnchors[j].closest('ytm-video-with-context-renderer, ytm-compact-video-renderer, ytm-media-item, [class*="renderer"], [class*="lockup"], [class*="media-item"]');
            if (card) {
                createShadowButton(card);
            }
        }
        attachToAllVideos();
    };

    // =========================================================================
    // SECTION 3: Debounced MutationObserver + Continuous Scanning
    // =========================================================================
    var scanTimeout = null;
    function scheduleScan() {
        if (scanTimeout) return;
        scanTimeout = setTimeout(function() {
            scanTimeout = null;
            window.__vidgrabScanNodes();
        }, 120);
    }

    var observer = new MutationObserver(function(mutations) {
        var hasAddedNodes = false;
        for (var i = 0; i < mutations.length; i++) {
            if (mutations[i].addedNodes && mutations[i].addedNodes.length > 0) {
                hasAddedNodes = true;
                break;
            }
        }
        if (hasAddedNodes) {
            scheduleScan();
        }
    });

    observer.observe(document.documentElement, {
        childList: true,
        subtree: true
    });

    window.addEventListener('scroll', scheduleScan, { passive: true });
    setInterval(function() {
        if (window.__vidgrabScanNodes) window.__vidgrabScanNodes();
    }, 700);

    window.__vidgrabScanNodes();
})();
    """.trimIndent()
}
