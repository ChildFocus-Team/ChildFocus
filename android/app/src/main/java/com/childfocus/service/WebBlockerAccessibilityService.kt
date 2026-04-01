package com.childfocus.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that monitors browser URL bars across ALL major Android browsers
 * and blocks any site matching the parent-configured blocked list.
 */
class WebBlockerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WebBlocker"

        val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "org.mozilla.focus",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.opera.gx",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.duckduckgo.mobile.android",
            "com.UCMobile.intl",
            "com.uc.browser.en",
            "com.vivaldi.browser",
            "com.kiwibrowser.browser",
            "mobi.mgeek.TunnyBrowser",
            "com.android.browser",
            "org.chromium.chrome",
        )

        private val URL_BAR_IDS = setOf(
            "url_bar",
            "url_field",
            "url",
            "address_bar",
            "omnibox",
            "mozac_browser_toolbar_url_view",
            "browser_toolbar_url",
            "location_bar_edit_text",
            "search_text",
            "aw_edit_url_bar",
            "url_bar_title",
        )

        const val EXTRA_BLOCKED_URL = "blocked_url"
    }

    private var lastCheckedUrl: String = ""
    private var lastBlockedUrl: String = ""
    private var lastBlockTime: Long    = 0L
    private val BLOCK_DEBOUNCE_MS      = 2000L
    private val handler                = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
            packageNames = BROWSER_PACKAGES.toTypedArray()
        }
        serviceInfo = info
        Log.d(TAG, "WebBlockerAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in BROWSER_PACKAGES) return

        val rootNode = rootInActiveWindow ?: return
        try {
            val url = extractUrl(rootNode) ?: return
            if (url == lastCheckedUrl) return
            lastCheckedUrl = url

            Log.d(TAG, "Browser URL detected [$pkg]: $url")

            if (WebBlockerManager.isBlocked(applicationContext, url)) {
                val now = System.currentTimeMillis()
                if (url == lastBlockedUrl && (now - lastBlockTime) < BLOCK_DEBOUNCE_MS) return
                lastBlockedUrl = url
                lastBlockTime  = now

                Log.w(TAG, "BLOCKING URL: $url")

                // Navigate back FIRST, then show overlay after short delay
                performGlobalAction(GLOBAL_ACTION_BACK)
                handler.postDelayed({
                    showBlockedOverlay(url)
                }, 300)
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun extractUrl(root: AccessibilityNodeInfo): String? {
        for (idFragment in URL_BAR_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(
                "${root.packageName}:id/$idFragment"
            )
            for (node in nodes) {
                val text = node.text?.toString()?.takeIf { it.isNotBlank() }
                node.recycle()
                if (text != null && looksLikeUrl(text)) return text
            }
        }
        return bfsForUrl(root)
    }

    private fun bfsForUrl(root: AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()
            if (text != null && looksLikeUrl(text) &&
                node.className?.contains("EditText", ignoreCase = true) == true) {
                return text
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun looksLikeUrl(text: String): Boolean {
        val t = text.trim().lowercase()
        return t.contains('.') && !t.contains(' ') &&
                (t.startsWith("http") || t.startsWith("www.") ||
                        t.matches(Regex("^[a-z0-9][a-z0-9\\-_.]+\\.[a-z]{2,}.*")))
    }

    private fun showBlockedOverlay(url: String) {
        val activityIntent = Intent(applicationContext, BlockedWebActivity::class.java).apply {
            putExtra(EXTRA_BLOCKED_URL, url)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(activityIntent)
    }

    override fun onInterrupt() {
        Log.d(TAG, "WebBlockerAccessibilityService interrupted")
    }
}