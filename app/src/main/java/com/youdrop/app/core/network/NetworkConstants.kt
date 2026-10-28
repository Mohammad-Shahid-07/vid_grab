package com.youdrop.app.core.network

object NetworkConstants {

    /**
     * Standard 2026 Mobile Chrome User-Agent.
     * STRICT REQUIREMENT: Does NOT contain '; wv' or 'Version/X.X' tokens.
     * This ensures Google Sign-In succeeds within the embedded WebView without
     * triggering the 'Error 403: disallowed_useragent' security block.
     */
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /**
     * Thread-safe volatile mirror of the active sanitized User-Agent.
     * Initialized to DEFAULT_USER_AGENT and kept synchronized whenever the
     * WebView sanitizes and sets its WebSettings userAgentString.
     */
    @Volatile
    var activeUserAgent: String = DEFAULT_USER_AGENT

    const val CONNECT_TIMEOUT_SECONDS = 20L
    const val READ_TIMEOUT_SECONDS = 25L
    const val WRITE_TIMEOUT_SECONDS = 25L

    /**
     * Sanitizes any dynamic WebView User-Agent string by stripping Android WebView identifiers.
     */
    fun sanitizeUserAgent(rawUserAgent: String): String {
        return rawUserAgent
            .replace(Regex(";\\s*wv"), "")
            .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
            .trim()
    }
}
