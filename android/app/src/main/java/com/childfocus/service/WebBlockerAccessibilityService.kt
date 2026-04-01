package com.childfocus.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that enforces the web blocklist permanently.
 *
 * When a blocked URL is detected:
 *  1. Closes the browser tab.
 *  2. Presses HOME to background the browser.
 *  3. Force-kills the browser process via ActivityManager.
 *  4. Shows BlockedWebActivity on top.
 *  5. Starts a watchdog that re-triggers the sequence only if the child
 *     reopens a browser and navigates to a blocked URL again.
 *
 * Requires android.permission.KILL_BACKGROUND_PROCESSES in AndroidManifest.xml.
 */
class WebBlockerAccessibilityService : AccessibilityService() {

    companion object {
        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.brave.browser",
            "com.sec.android.app.sbrowser",
            "com.UCMobile.intl",
            "com.kiwibrowser.browser",
            "com.vivaldi.browser"
        )

        private val URL_BAR_IDS = setOf(
            "com.android.chrome:id/url_bar",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.microsoft.emmx:id/url_bar",
            "com.opera.browser:id/url_field",
            "com.brave.browser:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text"
        )

        private val CLOSE_TAB_VIEW_IDS = listOf(
            "com.android.chrome:id/close_button",
            "com.brave.browser:id/close_button",
            "com.microsoft.emmx:id/close_button",
            "com.vivaldi.browser:id/close_button",
            "com.kiwibrowser.browser:id/close_button",
            "org.mozilla.firefox:id/mozac_browser_toolbar_close_tab_button",
            "com.sec.android.app.sbrowser:id/btn_close",
            "com.opera.browser:id/close_tab"
        )

        private val CLOSE_TAB_DESCRIPTIONS = listOf(
            "close tab",
            "close",
            "닫기",
            "关闭标签页"
        )

        private const val HOME_DELAY_MS         = 150L
        private const val KILL_DELAY_MS         = 500L
        private const val OVERLAY_DELAY_MS      = 650L

        // How long after a block sequence completes before the watchdog
        // starts polling — gives the kill + overlay time to fully settle.
        private const val WATCHDOG_START_DELAY_MS = 2000L

        // How often the watchdog polls once running.
        private const val WATCHDOG_INTERVAL_MS    = 800L

        // How long the watchdog stays active after a block.
        private const val WATCHDOG_DURATION_MS    = 30_000L

        // Minimum gap between two consecutive block triggers (prevents
        // the watchdog from firing while a block sequence is still running).
        private const val BLOCK_COOLDOWN_MS       = 3000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastBlockedUrl  = ""
    private var lastBlockedTime = 0L

    // True while the close/HOME/kill/overlay sequence is in progress.
    // The watchdog will not fire while this flag is set.
    private var blockInProgress = false

    private var watchdogStartMs = 0L
    private var watchdogRunning = false

    // -------------------------------------------------------------------------
    // Watchdog
    // -------------------------------------------------------------------------

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            // Stop if expired, or if a block sequence is already running
            if (!watchdogRunning ||
                System.currentTimeMillis() - watchdogStartMs > WATCHDOG_DURATION_MS ||
                blockInProgress) {
                watchdogRunning = false
                return
            }

            // Only act if a browser is currently in the foreground
            val activeRoot = rootInActiveWindow
            val activePkg  = activeRoot?.packageName?.toString() ?: ""
            activeRoot?.recycle()

            if (activePkg in BROWSER_PACKAGES) {
                val url = extractCurrentUrl(activePkg)
                if (!url.isNullOrBlank() && WebBlockerManager.isBlocked(url)) {
                    // Bypass detected — stop watchdog then re-block
                    watchdogRunning = false
                    triggerBlock(url, activePkg)
                    return
                }
            }

            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun startWatchdog() {
        stopWatchdog()
        watchdogStartMs = System.currentTimeMillis()
        watchdogRunning = true
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_START_DELAY_MS)
    }

    private fun stopWatchdog() {
        watchdogRunning = false
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onServiceConnected() {
        WebBlockerManager.init(applicationContext)
        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 150L
            packageNames = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopWatchdog()
        mainHandler.removeCallbacksAndMessages(null)
    }

    // -------------------------------------------------------------------------
    // Event handler
    // -------------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in BROWSER_PACKAGES) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> checkAndBlock(pkg)
        }
    }

    override fun onInterrupt() {}

    // -------------------------------------------------------------------------
    // Core blocking logic
    // -------------------------------------------------------------------------

    private fun checkAndBlock(browserPackage: String) {
        val url = extractCurrentUrl(browserPackage) ?: return
        if (url.isBlank()) return

        if (!WebBlockerManager.isBlocked(url)) return

        // Close the tab IMMEDIATELY — happens on every detected event,
        // before cooldown or in-progress checks, so the tab vanishes
        // as fast as the accessibility event fires.
        closeCurrentTab()

        // If a full block sequence is already running, or the same URL
        // was just blocked, skip HOME / kill / overlay to avoid repeating.
        if (blockInProgress) return
        val now = System.currentTimeMillis()
        if (url == lastBlockedUrl && (now - lastBlockedTime) < BLOCK_COOLDOWN_MS) return

        triggerBlock(url, browserPackage)
    }

    private fun triggerBlock(url: String, browserPackage: String) {
        blockInProgress = true
        lastBlockedUrl  = url
        lastBlockedTime = System.currentTimeMillis()

        stopWatchdog()

        // Step 2: press HOME → browser goes to background
        mainHandler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
        }, HOME_DELAY_MS)

        // Step 3: kill the browser process
        mainHandler.postDelayed({
            forceCloseBrowser(browserPackage)
        }, KILL_DELAY_MS)

        // Step 4: show the blocked screen
        mainHandler.postDelayed({
            launchBlockedScreen(url)
        }, OVERLAY_DELAY_MS)

        // Step 5: clear the in-progress flag and start the watchdog.
        // Delay is WATCHDOG_START_DELAY_MS after the overlay appears so
        // everything has fully settled before we start polling again.
        mainHandler.postDelayed({
            blockInProgress = false
            startWatchdog()
        }, OVERLAY_DELAY_MS + WATCHDOG_START_DELAY_MS)
    }

    // -------------------------------------------------------------------------
    // Force close browser
    // -------------------------------------------------------------------------

    private fun forceCloseBrowser(browserPackage: String) {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(browserPackage)
        } catch (e: Exception) {
            // Permission missing or process already gone — safe to ignore
        }
    }

    // -------------------------------------------------------------------------
    // Tab closing — three-stage approach
    // -------------------------------------------------------------------------

    private fun closeCurrentTab() {
        if (tryCloseViaViewId()) return
        if (tryCloseViaContentDescription()) return
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun tryCloseViaViewId(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            for (id in CLOSE_TAB_VIEW_IDS) {
                val nodes = root.findAccessibilityNodeInfosByViewId(id)
                val btn = nodes.firstOrNull { it.isClickable }
                if (btn != null) {
                    btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }
            false
        } catch (e: Exception) {
            false
        } finally {
            root.recycle()
        }
    }

    private fun tryCloseViaContentDescription(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                if (node.isClickable && CLOSE_TAB_DESCRIPTIONS.any { desc.contains(it) }) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            false
        } catch (e: Exception) {
            false
        } finally {
            root.recycle()
        }
    }

    // -------------------------------------------------------------------------
    // URL extraction — checks only the active foreground window
    // -------------------------------------------------------------------------

    private fun extractCurrentUrl(browserPackage: String): String? {
        val root = rootInActiveWindow ?: return null
        // Only read the window if it actually belongs to the expected browser
        if (root.packageName?.toString() != browserPackage) {
            root.recycle()
            return null
        }
        return try {
            val byId = URL_BAR_IDS
                .asSequence()
                .mapNotNull { id -> root.findAccessibilityNodeInfosByViewId(id).firstOrNull() }
                .mapNotNull { it.text?.toString() }
                .firstOrNull { it.contains('.') }
            byId ?: findUrlInTree(root)
        } finally {
            root.recycle()
        }
    }

    private fun findUrlInTree(root: AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()
            if (!text.isNullOrBlank() && looksLikeUrl(text)) return text
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        return (t.startsWith("http://") || t.startsWith("https://") ||
                (t.contains('.') && !t.contains(' '))) && t.length < 2048
    }

    // -------------------------------------------------------------------------
    // Launch blocked screen
    // -------------------------------------------------------------------------

    private fun launchBlockedScreen(blockedUrl: String) {
        val intent = Intent(this, BlockedWebActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(BlockedWebActivity.EXTRA_BLOCKED_URL, blockedUrl)
        }
        startActivity(intent)
    }
}