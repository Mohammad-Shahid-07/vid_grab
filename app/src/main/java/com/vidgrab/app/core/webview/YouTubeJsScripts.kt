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
    if (window.__VIDGRAB_INJECTED__) {
        if (window.__vidgrabScanNodes) window.__vidgrabScanNodes();
        return;
    }
    window.__VIDGRAB_INJECTED__ = true;

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

    function parsePlayerObject(text, start) {
        while (/\s/.test(text[start] || '') && start < text.length) start++;
        if (text[start] !== '{') throw new SyntaxError('Expected object');
        var depth = 0, quoted = false, escaped = false;
        for (var i = start; i < text.length; i++) {
            var c = text[i];
            if (quoted) {
                if (escaped) escaped = false;
                else if (c === '\\') escaped = true;
                else if (c === '"') quoted = false;
            } else if (c === '"') quoted = true;
            else if (c === '{') depth++;
            else if (c === '}' && --depth === 0) return JSON.parse(text.slice(start, i + 1));
        }
        throw new SyntaxError('Incomplete player response');
    }

    function getCurrentVideoId() {
        try {
            var url = new URL(window.location.href);
            var path = url.pathname;
            if (path === '/watch') return url.searchParams.get('v') || '';
            var match = /^\/(?:shorts|live|v|embed)\/([\w-]+)\/?$/.exec(path);
            if (match && match[1]) return match[1];
            var player = document.getElementById('movie_player') || document.getElementById('shorts-player');
            if (player && typeof player.getVideoData === 'function') {
                var source = player.getVideoData().video_id;
                if (source && source !== 'videoseries' && source !== 'live_stream') return source;
            }
        } catch (e) {}
        return '';
    }

    function readPlayerResponseFromPage(wantedVid) {
        function accept(val, playerId) {
            if (!val || typeof val !== 'object') return null;
            var vid = val.videoDetails && val.videoDetails.videoId;
            if (!wantedVid || vid === wantedVid || (!vid && playerId === wantedVid)) return val;
            return null;
        }

        // 1. Inspect HTML5 player instances (movie_player, shorts-player)
        var playerIds = ['shorts-player', 'movie_player', 'player'];
        for (var i = 0; i < playerIds.length; i++) {
            var el = document.getElementById(playerIds[i]);
            if (el && typeof el.getPlayerResponse === 'function') {
                var playerId = (el.getVideoData && typeof el.getVideoData === 'function') ? el.getVideoData().video_id : null;
                var res = accept(el.getPlayerResponse(), playerId);
                if (res && res.streamingData) return res;
            }
        }

        // 2. Check window.__vidgrabLatestPlayerResponse
        if (window.__vidgrabLatestPlayerResponse) {
            var res = accept(window.__vidgrabLatestPlayerResponse);
            if (res && res.streamingData) return res;
        }

        // 3. Check window.ytInitialPlayerResponse
        if (window.ytInitialPlayerResponse) {
            var res = accept(window.ytInitialPlayerResponse);
            if (res && res.streamingData) return res;
        }

        // 4. Scan document <script> tags for ytInitialPlayerResponse assignment
        var scripts = document.getElementsByTagName('script');
        var pattern = /\bytInitialPlayerResponse\s*=\s*/g;
        for (var s = 0; s < scripts.length; s++) {
            var text = scripts[s].textContent || '';
            var match;
            while ((match = pattern.exec(text)) !== null) {
                try {
                    var parsed = accept(parsePlayerObject(text, pattern.lastIndex));
                    if (parsed && parsed.streamingData) return parsed;
                } catch (e) {}
            }
        }
        return null;
    }

    // 1DM In-Browser Direct Stream Fetcher (VISIONOS client contract)
    // Runs inside browser session: bypasses bot-check/sign-in requirements & supplies unthrottled direct URLs
    async function fetchVisionOsResponse(wantedVid) {
        if (!wantedVid) return null;
        try {
            var config = window.ytcfg;
            var visitor = config && typeof config.get === 'function' ? config.get('VISITOR_DATA') : '';
            var headers = {
                'Content-Type': 'application/json',
                'X-Youtube-Client-Name': '101',
                'X-Youtube-Client-Version': '1.02'
            };
            if (visitor) headers['X-Goog-Visitor-Id'] = visitor;

            var client = {
                clientName: 'VISIONOS',
                clientVersion: '1.02',
                hl: 'en',
                deviceMake: 'Apple',
                deviceModel: 'RealityDevice17,1',
                osName: 'visionOS',
                osVersion: '26.5.23O471',
                userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15'
            };
            if (visitor) client.visitorData = visitor;

            var body = JSON.stringify({
                videoId: wantedVid,
                context: { client: client },
                playbackContext: {
                    contentPlaybackContext: {
                        html5Preference: 'HTML5_PREF_WANTS'
                    }
                }
            });

            var resp = await window.fetch('/youtubei/v1/player?prettyPrint=false', {
                method: 'POST',
                headers: headers,
                body: body,
                credentials: 'omit'
            });

            if (resp.ok) {
                var json = await resp.json();
                if (json && json.playabilityStatus && json.playabilityStatus.status === 'OK' && json.streamingData) {
                    return json;
                }
            }
        } catch (e) {
            console.error('[VidGrab] In-page VISIONOS fetch error:', e);
        }
        return null;
    }

    window.__vidgrabReadResponse = readPlayerResponseFromPage;
    window.__vidgrabFetchDirectPlayerJson = async function(wantedVid) {
        var vis = await fetchVisionOsResponse(wantedVid);
        if (vis && vis.streamingData) {
            window.__vidgrabLatestPlayerResponse = vis;
            return vis;
        }
        var pageRes = readPlayerResponseFromPage(wantedVid);
        if (pageRes && pageRes.streamingData) {
            window.__vidgrabLatestPlayerResponse = pageRes;
            return pageRes;
        }
        return null;
    };

    var lastSentResponseVideoId = '';
    function checkInitialPlayerResponse() {
        try {
            var curVid = getCurrentVideoId();
            if (!curVid || curVid === lastSentResponseVideoId) return;

            var res = readPlayerResponseFromPage(curVid);
            if (res && res.videoDetails && res.videoDetails.videoId) {
                window.__vidgrabLatestPlayerResponse = res;
                lastSentResponseVideoId = res.videoDetails.videoId;
                if (window.VidGrabBridge && window.VidGrabBridge.onPlayerResponse) {
                    window.VidGrabBridge.onPlayerResponse(res.videoDetails.videoId, JSON.stringify(res));
                }
            }
            if (curVid && (!res || !res.streamingData)) {
                fetchVisionOsResponse(curVid).then(function(vis) {
                    if (vis && vis.streamingData && vis.videoDetails && vis.videoDetails.videoId) {
                        window.__vidgrabLatestPlayerResponse = vis;
                        lastSentResponseVideoId = vis.videoDetails.videoId;
                        if (window.VidGrabBridge && window.VidGrabBridge.onPlayerResponse) {
                            window.VidGrabBridge.onPlayerResponse(vis.videoDetails.videoId, JSON.stringify(vis));
                        }
                    }
                }).catch(function(){});
            }
        } catch (e) {}
    }
    checkInitialPlayerResponse();

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
                                window.__vidgrabLatestPlayerResponse = data;
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
                            window.__vidgrabLatestPlayerResponse = data;
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

    // =========================================================================
    // SECTION 2: Single Dedicated Download Button Injection Layer
    // =========================================================================

    var CARD_CONTAINER_SELECTORS = [
        'ytm-rich-item-renderer',
        'ytm-video-with-context-renderer',
        'ytm-compact-video-renderer',
        'ytm-compact-playlist-renderer',
        'ytm-compact-channel-renderer',
        'ytm-playlist-video-renderer',
        'ytm-playlist-panel-video-renderer',
        'ytm-reel-item-renderer',
        'ytm-shorts-lockup-view-model',
        'ytm-radio-renderer',
        'ytd-rich-item-renderer',
        'ytd-video-renderer',
        'ytd-compact-video-renderer',
        'ytd-playlist-video-renderer'
    ].join(',');

    function getTopCard(node) {
        if (!node || !node.closest) return null;
        var top = node.closest('ytm-rich-item-renderer, ytd-rich-item-renderer');
        if (top) return top;
        top = node.closest('ytm-video-with-context-renderer, ytd-video-renderer');
        if (top) return top;
        top = node.closest('ytm-compact-video-renderer, ytm-compact-playlist-renderer, ytd-compact-video-renderer');
        if (top) return top;
        top = node.closest('ytm-playlist-video-renderer, ytm-playlist-panel-video-renderer, ytd-playlist-video-renderer');
        if (top) return top;
        top = node.closest('ytm-reel-item-renderer, ytm-shorts-lockup-view-model');
        if (top) return top;
        top = node.closest('ytm-radio-renderer, ytm-compact-channel-renderer');
        if (top) return top;
        return node.closest(CARD_CONTAINER_SELECTORS);
    }

    // Detail headers (watch metadata, playlist landing page, channel page) are intentionally
    // excluded: the native FAB owns downloads there. Only feed/card chips are injected, so the
    // page never grows a second download button above Subscribe/Play.
    var PAGE_CHROME_CONTAINERS = 'ytm-slim-video-metadata-section-renderer, ytm-playlist-header-renderer, ytm-c4-tabbed-header-renderer, yt-page-header-view-model';

    function getPlaylistVideosFromPage() {
        var items = [];
        var seenIds = {};

        // Priority 1: Parse window.ytInitialData
        try {
            function findPlaylistVideos(obj) {
                if (!obj || typeof obj !== 'object') return;
                if (obj.playlistVideoRenderer) {
                    var vr = obj.playlistVideoRenderer;
                    var vid = vr.videoId;
                    if (vid && !seenIds[vid]) {
                        seenIds[vid] = true;
                        var title = (vr.title && vr.title.runs && vr.title.runs[0]) ? vr.title.runs[0].text : (vr.title ? (vr.title.simpleText || '') : '');
                        var author = (vr.shortBylineText && vr.shortBylineText.runs && vr.shortBylineText.runs[0]) ? vr.shortBylineText.runs[0].text : '';
                        var durSec = parseInt(vr.lengthSeconds, 10) || 0;
                        var thumb = (vr.thumbnail && vr.thumbnail.thumbnails && vr.thumbnail.thumbnails[0]) ? vr.thumbnail.thumbnails[vr.thumbnail.thumbnails.length - 1].url : ('https://i.ytimg.com/vi/' + vid + '/hqdefault.jpg');
                        items.push({
                            videoId: vid,
                            url: 'https://www.youtube.com/watch?v=' + vid,
                            title: title || ('Video #' + (items.length + 1)),
                            author: author || 'YouTube',
                            durationSeconds: durSec,
                            thumbnailUrl: thumb,
                            isSelected: true
                        });
                    }
                    return;
                }
                for (var key in obj) {
                    if (obj.hasOwnProperty(key)) {
                        findPlaylistVideos(obj[key]);
                    }
                }
            }
            if (window.ytInitialData) {
                findPlaylistVideos(window.ytInitialData);
            }
        } catch(e) {}

        // Priority 2: DOM row scraping fallback
        if (items.length === 0) {
            try {
                var rows = document.querySelectorAll('ytm-playlist-video-renderer, ytm-compact-video-renderer, [class*="playlist-video"]');
                for (var i = 0; i < rows.length; i++) {
                    var row = rows[i];
                    var a = row.querySelector('a[href*="/watch"]');
                    if (!a) continue;
                    var vm = a.href.match(/(?:v=|\/shorts\/|youtu\.be\/)([a-zA-Z0-9_-]{11})/);
                    var vid = vm ? vm[1] : null;
                    if (!vid || seenIds[vid]) continue;
                    seenIds[vid] = true;

                    var titleEl = row.querySelector('.media-item-headline, .compact-media-item-headline, h3, h4, [class*="headline"], [class*="title"]');
                    var title = titleEl ? titleEl.innerText.trim() : ('Video #' + (items.length + 1));

                    var bylineEl = row.querySelector('.media-item-byline, [class*="byline"], [class*="uploader"]');
                    var author = bylineEl ? bylineEl.innerText.trim() : 'YouTube';

                    var badgeEl = row.querySelector('[class*="duration"], [class*="time-status"], .badge-shape-wiz__text');
                    var durSec = 0;
                    if (badgeEl && badgeEl.innerText) {
                        var parts = badgeEl.innerText.trim().split(':').map(function(p){ return parseInt(p, 10) || 0; });
                        if (parts.length === 2) durSec = (parts[0] * 60) + parts[1];
                        else if (parts.length === 3) durSec = (parts[0] * 3600) + (parts[1] * 60) + parts[2];
                    }

                    var img = row.querySelector('img');
                    var thumb = img && img.src ? img.src : ('https://i.ytimg.com/vi/' + vid + '/hqdefault.jpg');

                    items.push({
                        videoId: vid,
                        url: 'https://www.youtube.com/watch?v=' + vid,
                        title: title,
                        author: author,
                        durationSeconds: durSec,
                        thumbnailUrl: thumb,
                        isSelected: true
                    });
                }
            } catch(e) {}
        }

        return items;
    }
    window.__vidgrabGetPlaylistVideos = getPlaylistVideosFromPage;

    // =========================================================================
    // SECTION: Theme-aware button styling (explicit colors, no page CSS variables)
    // =========================================================================
    // Page CSS variables (--yt-spec-*) proved unreliable in shadow-DOM contexts and left
    // buttons with black-on-black fallback colors. We detect YouTube's active theme instead
    // and drive explicit high-contrast colors, re-checking cheaply on the existing 1s tick.
    function vgIsDarkTheme() {
        try {
            var root = document.documentElement;
            if (root && (root.hasAttribute('dark') || root.classList.contains('dark'))) return true;
            if (document.querySelector('ytm-app[dark], ytd-app[dark], html[dark]')) return true;
            var body = document.body;
            if (body && (body.hasAttribute('dark') || body.classList.contains('dark'))) return true;
            if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) return true;
        } catch (e) {}
        return false;
    }

    var vgThemedButtons = [];
    var vgLastDark = vgIsDarkTheme();

    function vgRegisterThemeButton(btn) {
        vgThemedButtons.push(btn);
        // Bounded registry: drop the oldest half if a long session injects huge lists.
        if (vgThemedButtons.length > 120) {
            vgThemedButtons = vgThemedButtons.slice(-60);
        }
    }

    function vgSyncButtonThemes() {
        var dark = vgIsDarkTheme();
        if (dark === vgLastDark) return;
        vgLastDark = dark;
        for (var i = vgThemedButtons.length - 1; i >= 0; i--) {
            var b = vgThemedButtons[i];
            if (!b || !b.isConnected) {
                vgThemedButtons.splice(i, 1);
                continue;
            }
            try {
                if (dark) b.classList.add('vg-dark');
                else b.classList.remove('vg-dark');
            } catch (e) {}
        }
    }

    function createShadowButton(cardNode) {
        var topCard = getTopCard(cardNode);
        if (!topCard) return;
        // Never inject into a page-level header (the native FAB covers those pages).
        if (topCard.matches(PAGE_CHROME_CONTAINERS)) return;

        // Clean up any extra duplicate buttons that might exist in this card
        var existingHosts = topCard.querySelectorAll('[data-vidgrab-host]');
        if (existingHosts.length > 1) {
            for (var k = 1; k < existingHosts.length; k++) {
                try { existingHosts[k].remove(); } catch(e){}
            }
        }
        // If the card already has exactly 1 button, do not inject another
        if (existingHosts.length >= 1 || topCard.hasAttribute('data-vidgrab-has-btn')) {
            topCard.setAttribute('data-vidgrab-has-btn', 'true');
            return;
        }

        // Tag card as processed immediately to prevent concurrent duplicate injections
        topCard.setAttribute('data-vidgrab-has-btn', 'true');

        // Target placement:
        // Priority 1: Directly before the 3-dots action menu container (cleanest native placement)
        var menuBtn = topCard.querySelector('ytm-menu-renderer, .media-item-menu, button.item-action, button[aria-label*="Action" i], button[aria-label*="menu" i]');
        var menuContainer = menuBtn ? (menuBtn.closest('ytm-menu-renderer, .media-item-menu') || menuBtn) : null;

        // Priority 2: In metadata/details row
        var metaContainer = (!menuContainer) ? topCard.querySelector('.details, .media-item-metadata, .compact-media-item-metadata, .slim-video-information-title-and-actions') : null;

        // Priority 3: Beside headline/title
        var titleEl = (!menuContainer && !metaContainer) ? topCard.querySelector('.media-item-headline, .compact-media-item-headline, h1, h2, h3, [class*="headline"]') : null;
        var titleParent = (titleEl && titleEl.parentNode && titleEl.parentNode !== topCard) ? titleEl.parentNode : null;

        var insertionTarget = null;
        var insertBeforeNode = null;

        if (menuContainer && menuContainer.parentNode) {
            insertionTarget = menuContainer.parentNode;
            insertBeforeNode = menuContainer;
        } else if (metaContainer) {
            insertionTarget = metaContainer;
            insertBeforeNode = null;
        } else if (titleParent) {
            insertionTarget = titleParent;
            insertBeforeNode = null;
        }

        // Strictly avoid appending to card root or thumbnail containers
        if (!insertionTarget) {
            return;
        }

        var host = document.createElement('div');
        host.setAttribute('data-vidgrab-host', 'true');
        host.style.cssText = 'display: inline-flex; align-items: center; justify-content: center; margin-left: 2px; margin-right: 0; flex-shrink: 0; align-self: center;';

        var shadow = host.attachShadow({ mode: 'open' });

        var style = document.createElement('style');
        style.textContent = '' +
            // Bare icon button: YouTube's own action icons have no permanent container, so a
            // painted circle immediately reads as foreign UI. Only the press state is visible.
            '.vg-btn {' +
            '    display: inline-flex;' +
            '    align-items: center;' +
            '    justify-content: center;' +
            '    width: 40px;' +
            '    height: 40px;' +
            '    padding: 0;' +
            '    border: none;' +
            '    border-radius: 50%;' +
            '    background: transparent;' +
            '    color: #0f0f0f;' +
            '    cursor: pointer;' +
            '    user-select: none;' +
            '    -webkit-tap-highlight-color: transparent;' +
            '    transition: background-color 0.15s ease, transform 0.12s ease;' +
            '}' +
            '.vg-btn.vg-dark {' +
            '    background: transparent;' +
            '    color: #f1f1f1;' +
            '}' +
            '.vg-btn:active {' +
            '    transform: scale(0.92);' +
            '    background: rgba(0, 0, 0, 0.10);' +
            '}' +
            '.vg-btn.vg-dark:active {' +
            '    background: rgba(255, 255, 255, 0.16);' +
            '}' +
            '.vg-btn svg {' +
            '    width: 24px;' +
            '    height: 24px;' +
            '    fill: currentColor;' +
            '    pointer-events: none;' +
            '}' +
            '.vg-btn.vg-compact {' +
            '    width: 36px;' +
            '    height: 36px;' +
            '}' +
            '.vg-btn.vg-compact svg {' +
            '    width: 22px;' +
            '    height: 22px;' +
            '}' +
            '.vg-spinner {' +
            '    display: none;' +
            '    width: 16px;' +
            '    height: 16px;' +
            '    border: 2px solid currentColor;' +
            '    border-top-color: transparent;' +
            '    border-radius: 50%;' +
            '    animation: vgSpin 0.7s linear infinite;' +
            '}' +
            '.vg-btn.vg-loading svg {' +
            '    display: none;' +
            '}' +
            '.vg-btn.vg-loading .vg-spinner {' +
            '    display: inline-block;' +
            '}' +
            '@keyframes vgSpin {' +
            '    to { transform: rotate(360deg); }' +
            '}';

        var isCompactContext = topCard.matches('ytm-compact-video-renderer, ytm-compact-playlist-renderer');

        var btn = document.createElement('button');
        btn.className = 'vg-btn' + (isCompactContext ? ' vg-compact' : '');
        btn.setAttribute('aria-label', 'Download');
        if (vgIsDarkTheme()) btn.classList.add('vg-dark');
        vgRegisterThemeButton(btn);

        var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
        svg.setAttribute('viewBox', '0 0 24 24');
        var iconPath = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        iconPath.setAttribute('d', 'M17 18v1H6v-1h11zm-.5-6.6-.7-.7-3.3 3.3V4h-1v10l-3.3-3.3-.7.7 4.5 4.5 4.5-4.5z');
        svg.appendChild(iconPath);
        btn.appendChild(svg);

        var spinner = document.createElement('span');
        spinner.className = 'vg-spinner';
        btn.appendChild(spinner);

        function setBtnLoading(loading) {
            if (loading) {
                btn.classList.add('vg-loading');
                if (btn.__vgLoadingTimer) clearTimeout(btn.__vgLoadingTimer);
                btn.__vgLoadingTimer = setTimeout(function() { setBtnLoading(false); }, 4000);
            } else {
                btn.classList.remove('vg-loading');
                if (btn.__vgLoadingTimer) {
                    clearTimeout(btn.__vgLoadingTimer);
                    btn.__vgLoadingTimer = null;
                }
            }
        }
        // Exposed so the native ack (sheet opened) can stop the spinner deterministically.
        btn.__vgSetLoading = setBtnLoading;

        function handleDownloadTrigger(e) {
            e.stopPropagation();
            e.stopImmediatePropagation();
            e.preventDefault();

            // Instant feedback + debounce (touchend and click both fire on Android WebView).
            var triggerNow = Date.now();
            if (btn.__vgLastTrigger && (triggerNow - btn.__vgLastTrigger) < 700) {
                return;
            }
            btn.__vgLastTrigger = triggerNow;
            window.__vidgrabActiveTriggerBtn = btn;
            setBtnLoading(true);

            try {
                document.querySelectorAll('video').forEach(function(v) {
                    try { v.pause(); } catch(err) {}
                });
            } catch (err) {}

            var isWatchPage = window.location.pathname.indexOf('/watch') !== -1 || window.location.pathname.indexOf('/shorts/') !== -1;
            var isPlaylistPage = window.location.pathname.indexOf('/playlist') !== -1;
            var isMetadataSection = topCard.matches('ytm-slim-video-metadata-section-renderer');
            var isPlaylistHeader = topCard.matches('ytm-playlist-header-renderer');
            var isPlaylistCard = topCard.matches('ytm-compact-playlist-renderer') ||
                (topCard.querySelector('a[href*="list="]') && !topCard.querySelector('a[href*="/watch?v="]'));

            // Check if this card represents a single video row inside a playlist page or feed
            var videoAnchor = topCard.querySelector('a[href*="/watch"], a[href*="/shorts/"]');
            var isIndividualVideo = videoAnchor && !isPlaylistHeader && !isPlaylistCard;

            var rawUrl = window.location.href;
            var itemType = 'VIDEO';
            var playlistItems = null;

            if (isMetadataSection && isWatchPage) {
                rawUrl = window.location.href;
                itemType = 'VIDEO';
            } else if (isPlaylistHeader || (isPlaylistPage && !isIndividualVideo) || isPlaylistCard) {
                // Entire Playlist Download
                var plAnchor = topCard.querySelector('a[href*="list="], a[href*="/playlist"]');
                var candidatePlUrl = plAnchor ? plAnchor.href : window.location.href;
                var plMatch = candidatePlUrl.match(/[?&]list=([a-zA-Z0-9_-]+)/);
                var playlistId = plMatch ? plMatch[1] : '';
                rawUrl = playlistId ? ('https://www.youtube.com/playlist?list=' + playlistId) : candidatePlUrl;
                itemType = 'PLAYLIST';
                if (isPlaylistPage || isPlaylistHeader) {
                    playlistItems = getPlaylistVideosFromPage();
                }
            } else if (topCard.matches('ytm-c4-tabbed-header-renderer, yt-page-header-view-model') || (!isWatchPage && (window.location.pathname.indexOf('/@') !== -1 || window.location.pathname.indexOf('/channel/') !== -1))) {
                var chAnchor = topCard.querySelector('a[href*="/@"], a[href*="/channel/"]');
                rawUrl = chAnchor ? chAnchor.href : window.location.href;
                itemType = 'CHANNEL';
            } else if (videoAnchor) {
                rawUrl = videoAnchor.href;
                itemType = 'VIDEO';
            } else {
                rawUrl = window.location.href;
                itemType = isWatchPage ? 'VIDEO' : (isPlaylistPage ? 'PLAYLIST' : 'PAGE');
                if (itemType === 'PLAYLIST') {
                    playlistItems = getPlaylistVideosFromPage();
                }
            }

            var titleEl = topCard.querySelector('.media-item-headline, .compact-media-item-headline, h1, h2, h3, h4, .slim-video-information-title, [class*="lockup-title"], [class*="video-title"], [class*="headline"], [class*="title"]');
            var title = titleEl ? titleEl.innerText.trim() : document.title;

            var vidMatch = rawUrl.match(/(?:v=|\/shorts\/|youtu\.be\/)([a-zA-Z0-9_-]{11})/);
            var targetVid = (itemType === 'PLAYLIST') ? null : (vidMatch ? vidMatch[1] : null);

            var durationSec = 0;
            try {
                // 1. Check meta tag itemprop="duration"
                var metaDur = document.querySelector('meta[itemprop="duration"]');
                if (metaDur && metaDur.content && isWatchPage) {
                    var m = metaDur.content.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/);
                    if (m) {
                        var h = parseInt(m[1], 10) || 0;
                        var min = parseInt(m[2], 10) || 0;
                        var s = parseInt(m[3], 10) || 0;
                        durationSec = (h * 3600) + (min * 60) + s;
                    }
                }

                // 2. Check HTML5 video element on watch or shorts page
                if (durationSec <= 0 && isWatchPage) {
                    var videoEl = document.querySelector('video.html5-main-video') || document.querySelector('video');
                    if (videoEl && isFinite(videoEl.duration) && videoEl.duration > 0) {
                        durationSec = Math.round(videoEl.duration);
                    }
                }

                // 3. Check ytInitialPlayerResponse if videoId matches
                if (durationSec <= 0 && window.ytInitialPlayerResponse && window.ytInitialPlayerResponse.videoDetails) {
                    if (!targetVid || window.ytInitialPlayerResponse.videoDetails.videoId === targetVid) {
                        durationSec = parseInt(window.ytInitialPlayerResponse.videoDetails.lengthSeconds, 10) || 0;
                    }
                }

                // 4. Check duration badges or time status overlays in topCard
                if (durationSec <= 0 && topCard) {
                    var badgeEl = topCard.querySelector('.badge-shape-wiz__text, ytd-thumbnail-overlay-time-status-renderer, [class*="time-status"], [class*="duration"], .media-item-duration, ytm-thumbnail-overlay-time-status-renderer, span[role="text"]');
                    if (badgeEl && badgeEl.innerText) {
                        var parts = badgeEl.innerText.trim().split(':').map(function(p) { return parseInt(p, 10) || 0; });
                        if (parts.length === 2) {
                            durationSec = (parts[0] * 60) + parts[1];
                        } else if (parts.length === 3) {
                            durationSec = (parts[0] * 3600) + (parts[1] * 60) + parts[2];
                        }
                    }
                }
            } catch (err) {}

            var cleanUrl = rawUrl;
            if (itemType === 'PLAYLIST') {
                var plm = rawUrl.match(/[?&]list=([a-zA-Z0-9_-]+)/);
                if (plm) cleanUrl = 'https://www.youtube.com/playlist?list=' + plm[1];
            } else if (targetVid && (rawUrl.indexOf('/watch') !== -1 || rawUrl.indexOf('/shorts/') !== -1 || rawUrl.indexOf('youtu.be/') !== -1)) {
                cleanUrl = 'https://www.youtube.com/watch?v=' + targetVid;
            }

            var inlinePlayerJson = '';
            var candidate = null;
            if (targetVid) {
                candidate = readPlayerResponseFromPage(targetVid);
            }
            if (!candidate && !targetVid) {
                candidate = readPlayerResponseFromPage();
            }
            if (candidate) {
                try {
                    inlinePlayerJson = JSON.stringify(candidate);
                    var effectiveVid = candidate.videoDetails && candidate.videoDetails.videoId ? candidate.videoDetails.videoId : targetVid;
                    if (effectiveVid && window.VidGrabBridge && window.VidGrabBridge.onPlayerResponse) {
                        window.VidGrabBridge.onPlayerResponse(effectiveVid, inlinePlayerJson);
                    }
                } catch (err) {}
            }

            var playlistItemsJson = (itemType === 'PLAYLIST' && playlistItems && playlistItems.length > 0) ? JSON.stringify(playlistItems) : '';

            function sendTrigger(jsonStr) {
                if (window.VidGrabBridge && window.VidGrabBridge.onDownloadClicked) {
                    window.VidGrabBridge.onDownloadClicked(cleanUrl, itemType, title, jsonStr || '', durationSec, playlistItemsJson);
                }
            }

            // Instant popup: hand the trigger to native right away using whatever inline player
            // response is already in memory, so the sheet opens (and shows its loader) immediately.
            sendTrigger(inlinePlayerJson);

            // Then fetch a fresher direct player response in the background and hand it over as a
            // payload refresh. Native only applies this while the picker is still loading for the
            // same URL, so it upgrades slow resolutions without re-opening a dismissed sheet.
            if (itemType !== 'PLAYLIST' && targetVid && window.__vidgrabFetchDirectPlayerJson) {
                window.__vidgrabFetchDirectPlayerJson(targetVid).then(function(res) {
                    var freshJson = res ? JSON.stringify(res) : '';
                    if (freshJson && freshJson !== inlinePlayerJson &&
                        window.VidGrabBridge && window.VidGrabBridge.onDownloadPayloadRefreshed) {
                        try {
                            window.VidGrabBridge.onDownloadPayloadRefreshed(cleanUrl, itemType, title, freshJson, durationSec, playlistItemsJson);
                        } catch (err) {}
                    }
                }).catch(function() {});
            }
        }

        btn.addEventListener('click', handleDownloadTrigger, true);
        btn.addEventListener('touchend', handleDownloadTrigger, true);

        shadow.appendChild(style);
        shadow.appendChild(btn);

        if (insertBeforeNode) {
            insertionTarget.insertBefore(host, insertBeforeNode);
        } else {
            insertionTarget.appendChild(host);
        }
    }

    window.__vidgrabScanNodes = function() {
        // 1. Purge any accidental stray buttons placed inside thumbnails or avatar elements.
        // Also remove chips injected by an older build into watch/playlist/channel headers, so
        // the detail pages end up with only the native FAB after an in-session update.
        try {
            var staleChromeButtons = document.querySelectorAll(
                'ytm-slim-video-metadata-section-renderer [data-vidgrab-host], ' +
                'ytm-playlist-header-renderer [data-vidgrab-host], ' +
                'ytm-c4-tabbed-header-renderer [data-vidgrab-host], ' +
                'yt-page-header-view-model [data-vidgrab-host]'
            );
            for (var c = 0; c < staleChromeButtons.length; c++) {
                try { staleChromeButtons[c].remove(); } catch(e) {}
            }
            var strayButtons = document.querySelectorAll(
                '.media-item-thumbnail-container [data-vidgrab-host], ' +
                'ytm-thumbnail-overlay-time-status-renderer [data-vidgrab-host], ' +
                '[class*="thumbnail"] [data-vidgrab-host], ' +
                'ytm-channel-thumbnail-with-link-renderer [data-vidgrab-host]'
            );
            for (var s = 0; s < strayButtons.length; s++) {
                strayButtons[s].remove();
            }
        } catch(e) {}

        // 2. Scan canonical card containers
        var cards = document.querySelectorAll(CARD_CONTAINER_SELECTORS);
        for (var i = 0; i < cards.length; i++) {
            var top = getTopCard(cards[i]);
            if (top) {
                createShadowButton(top);
            }
        }

        // 3. Fallback scan on video and playlist anchors to ensure no cards are missed
        var anchors = document.querySelectorAll('a[href*="/watch?v="], a[href*="/shorts/"], a[href*="list="]');
        for (var j = 0; j < anchors.length; j++) {
            var topAnchor = getTopCard(anchors[j]);
            if (topAnchor) {
                createShadowButton(topAnchor);
            }
        }
    };

    // =========================================================================
    // SECTION 3: Debounced MutationObserver + Continuous Scanning
    // =========================================================================
    var scanTimeout = null;
    function scheduleScan() {
        if (scanTimeout) return;
        scanTimeout = setTimeout(function() {
            scanTimeout = null;
            if (window.__vidgrabScanNodes) window.__vidgrabScanNodes();
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

    function initObserver() {
        var target = document.documentElement || document.body;
        if (target) {
            observer.observe(target, {
                childList: true,
                subtree: true
            });
        } else {
            document.addEventListener('DOMContentLoaded', function() {
                var delayedTarget = document.documentElement || document.body;
                if (delayedTarget) {
                    observer.observe(delayedTarget, {
                        childList: true,
                        subtree: true
                    });
                }
            });
        }
    }
    initObserver();

    function notifyUrlChange() {
        lastSentResponseVideoId = '';
        if (window.VidGrabBridge && window.VidGrabBridge.onUrlChanged) {
            window.VidGrabBridge.onUrlChanged(window.location.href, document.title);
        }
        checkInitialPlayerResponse();
        var curVid = getCurrentVideoId();
        if (curVid && window.__vidgrabFetchDirectPlayerJson) {
            window.__vidgrabFetchDirectPlayerJson(curVid).then(function(res) {
                if (res && window.VidGrabBridge && window.VidGrabBridge.onPlayerResponse) {
                    window.VidGrabBridge.onPlayerResponse(curVid, JSON.stringify(res));
                }
            }).catch(function(){});
        }
        scheduleScan();
        if (window.__vidgrabReportPageChrome) window.__vidgrabReportPageChrome();
    }

    window.addEventListener('yt-navigate-finish', notifyUrlChange);
    window.addEventListener('popstate', notifyUrlChange);
    window.addEventListener('load', notifyUrlChange);

    window.addEventListener('scroll', scheduleScan, { passive: true });
    setInterval(function() {
        checkInitialPlayerResponse();
        if (window.__vidgrabScanNodes) window.__vidgrabScanNodes();
        vgSyncButtonThemes();
        if (window.__vidgrabReportPageChrome) window.__vidgrabReportPageChrome();
    }, 1000);

    // Called by native the moment the quality picker sheet is visible, so the tapped button can
    // drop its spinner and the flow never leaves the user staring at a loading icon.
    window.__vidgrabTriggerAck = function() {
        var activeBtn = window.__vidgrabActiveTriggerBtn;
        if (activeBtn && activeBtn.__vgSetLoading) {
            try { activeBtn.__vgSetLoading(false); } catch (err) {}
        }
        window.__vidgrabActiveTriggerBtn = null;
    };

    // =========================================================================
    // SECTION 4: Page chrome reporter (native download chip placement + theme)
    // =========================================================================
    // The native chip lives above the WebView, so it cannot know that the watch player is filling
    // the screen. This reports only when something changes: one rect read per scroll frame, zero
    // bridge calls while the state is stable.
    var vgLastPlayerVisible = null;
    var vgLastDarkTheme = null;

    function vgReportPageChrome() {
        try {
            var path = window.location.pathname || '';
            var isWatch = path.indexOf('/watch') !== -1 || path.indexOf('/shorts/') !== -1;
            var visible = false;
            if (isWatch) {
                var player = document.querySelector('video.html5-main-video') ||
                    document.querySelector('#movie_player') ||
                    document.querySelector('ytm-player') ||
                    document.querySelector('.html5-video-player');
                if (player) {
                    var rect = player.getBoundingClientRect();
                    var vh = window.innerHeight || document.documentElement.clientHeight || 0;
                    visible = rect.height >= 80 && rect.bottom > 0 && rect.top < vh;
                }
            }
            var dark = vgIsDarkTheme();
            if (visible === vgLastPlayerVisible && dark === vgLastDarkTheme) return;
            vgLastPlayerVisible = visible;
            vgLastDarkTheme = dark;
            if (window.VidGrabBridge && window.VidGrabBridge.onPageChromeState) {
                window.VidGrabBridge.onPageChromeState(visible, dark);
            }
        } catch (e) {}
    }
    window.__vidgrabReportPageChrome = vgReportPageChrome;

    var vgChromeRafPending = false;
    window.addEventListener('scroll', function() {
        if (vgChromeRafPending) return;
        vgChromeRafPending = true;
        requestAnimationFrame(function() {
            vgChromeRafPending = false;
            vgReportPageChrome();
        });
    }, { passive: true });

    window.__vidgrabScanNodes();
    checkInitialPlayerResponse();
    vgReportPageChrome();
})();
    """.trimIndent()
}
