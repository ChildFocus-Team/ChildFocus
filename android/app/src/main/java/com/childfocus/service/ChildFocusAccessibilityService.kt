package com.childfocus.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class ChildFocusAccessibilityService : AccessibilityService() {

<<<<<<< Updated upstream
    private val scope     = CoroutineScope(Dispatchers.IO)
    private var lastTitle = ""
    private var lastVideoId = ""

    // ── Emulator uses 10.0.2.2 to reach host machine ──────────────────────────────
    private val BASE_URL = "http://10.0.2.2:5000"
=======
    companion object {
        // ── Change this ONE line to switch targets ────────────────────────────
        // Emulator (Pixel 3a AVD)  → "10.0.2.2"
        // Physical (Infinix WiFi)  → "192.168.100.136"
        private const val FLASK_HOST = "10.0.2.2"
        private const val FLASK_PORT = 5000
        private const val BASE_URL   = "http://$FLASK_HOST:$FLASK_PORT"

        // After 5 minutes without a new title, reset so the same
        // video re-classifies (hits cache = instant response)
        private const val TITLE_RESET_MS = 5 * 60 * 1000L

        // ── YouTube UI noise — never a real video title ───────────────────────
        private val SKIP_TITLES = listOf(
            "Shorts", "Sponsored", "Advertisement", "Ad ·", "Skip Ads",
            "My Mix", "Next:", "Trending", "Explore", "Subscriptions",
            "Library", "Home", "Video player", "Minimized player",
            "Minimize", "Cast", "More options", "Hide controls",
            "Enter fullscreen", "Rewind", "Fast forward", "Navigate up",
            "Voice search", "Choose Premium",
        )

        // ── System/notification noise — catches Play Store, app notifications,
        //    and Google ad overlay panels ────────────────────────────────────
        // FIX 3: Added ad-overlay and sign-in panel keywords
        private val SKIP_SYSTEM = listOf(
            "PTE. LTD", "Installed", "Open app", "App image",
            "Update", "Install", "Download", "Notification",
            "Allow", "Deny", "Permission", "Settings",
            "Battery", "Charging", "Wi-Fi", "Bluetooth",
            // Ad overlay / sign-in panel noise
            "Sign up", "Sign in", "Log in", "Expand ad",
            "Serverless", "Meet Containers", "cloud.google",
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private var lastSentTitle  = ""
    private var lastSentTimeMs = 0L
>>>>>>> Stashed changes

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

<<<<<<< Updated upstream
    private val TITLE_PATTERN = Pattern.compile(
        "Minimized player\\s+(.+?)\\s+\\1",
        Pattern.DOTALL
    )

=======
    // ── Pattern 1: title before "views" count — most accurate for full titles ─
    // e.g. "Fools Garden - Lemon Tree (Official HD Video) 554K views"
    // FIX 1: Removed Pattern.CASE_INSENSITIVE — real titles start with a capital
    //         letter. Ad panel garbage like "panel Serverless Meet Containers..."
    //         starts lowercase and was incorrectly matched by the old flag.
    private val VIEWS_PATTERN = Pattern.compile(
        "([A-Z][^\\n]{10,150})\\s+[\\d.,]+[KMBkm]?\\s+views"
    )

    // ── Pattern 2: title before @ChannelName — fires during playback ──────────
    // e.g. "Fools Garden - Lemon Tree (Official HD Video) @FoolsGardenOfficial"
    private val AT_CHANNEL_PATTERN = Pattern.compile(
        "([A-Z][^\\n@]{10,150})\\s{1,4}@[\\w]{2,50}(?:\\s|$)"
    )

    // ── Pattern 3: repeated title (minimized player) ──────────────────────────
    // Handled by extractRepeatedTitle() scan below

    // ── Pattern 4: direct URL video ID ───────────────────────────────────────
>>>>>>> Stashed changes
    private val URL_PATTERN = Pattern.compile("(?:v=|youtu\\.be/)([a-zA-Z0-9_-]{11})")

    // ── FIX 2: Domain regex — real video titles never contain web domains ─────
    private val DOMAIN_PATTERN = Regex("\\b\\w+\\.(com|org|net|io|co|google|youtube)\\b")

    override fun onServiceConnected() {
        println("[CF_SERVICE] ✓ Connected — monitoring YouTube")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

<<<<<<< Updated upstream
        // Strategy 1: direct URL in event text
        val eventText = event.text?.joinToString(" ") ?: ""
        val urlMatch  = URL_PATTERN.matcher(eventText)
        if (urlMatch.find()) {
            val vid = urlMatch.group(1) ?: return
            if (vid != lastVideoId) {
                lastVideoId = vid
                handleVideoId(vid)
            }
            return
=======
        // Auto-reset after 5 min so revisiting same video hits cache
        val now = System.currentTimeMillis()
        if (lastSentTitle.isNotEmpty() && (now - lastSentTimeMs) > TITLE_RESET_MS) {
            println("[CF_SERVICE] ↺ Reset title memory after timeout")
            lastSentTitle  = ""
            lastSentTimeMs = 0L
>>>>>>> Stashed changes
        }

        // Strategy 0: direct URL in event text
        val eventText = event.text?.joinToString(" ") ?: ""
        val urlMatch  = URL_PATTERN.matcher(eventText)
        if (urlMatch.find()) { handleVideoId(urlMatch.group(1) ?: return); return }

        val root    = rootInActiveWindow ?: return
        val allText = collectAllNodeText(root)
        root.recycle()

<<<<<<< Updated upstream
        val urlInTree = URL_PATTERN.matcher(allText)
        if (urlInTree.find()) {
            val vid = urlInTree.group(1) ?: return
            if (vid != lastVideoId) {
                lastVideoId = vid
                handleVideoId(vid)
            }
            return
        }

        val titleMatch = TITLE_PATTERN.matcher(allText)
        if (titleMatch.find()) {
            val title = titleMatch.group(1)?.trim() ?: return
            if (title != lastTitle && title.length > 5) {
                lastTitle = title
                println("[CF_SERVICE] ✓ Title detected: $title")
                scope.launch { resolveAndClassify(title) }
            }
=======
        // Strategy 1: direct URL in tree
        val urlInTree = URL_PATTERN.matcher(allText)
        if (urlInTree.find()) { handleVideoId(urlInTree.group(1) ?: return); return }

        // Strategy 2: title before "views" — most accurate, avoids @-prefix cuts
        val viewsMatch = VIEWS_PATTERN.matcher(allText)
        if (viewsMatch.find()) {
            val t = viewsMatch.group(1)?.trim() ?: return
            if (isCleanTitle(t)) { dispatchTitle(t); return }
        }

        // Strategy 3: title before @channel — fallback for when views not visible
        val atMatch = AT_CHANNEL_PATTERN.matcher(allText)
        if (atMatch.find()) {
            val t = atMatch.group(1)?.trim() ?: return
            if (isCleanTitle(t)) { dispatchTitle(t); return }
        }

        // Strategy 4: repeated title scan (minimized player)
        val repeated = extractRepeatedTitle(allText)
        if (repeated != null && isCleanTitle(repeated)) {
            dispatchTitle(repeated)
>>>>>>> Stashed changes
        }
    }

    /**
     * Returns true only if the string looks like an actual video title:
     *  - Not in SKIP_TITLES or SKIP_SYSTEM list
     *  - Does not start with a lowercase letter (ad panel / UI label)
     *  - Does not contain a web domain (ad overlay / sign-in panel)
     *  - Does not contain 2+ uppercase abbreviations (e.g. "PTE. LTD. SG")
     *  - Reasonable length
     *
     * FIX 2: Added lowercase-first check and domain check.
     */
    private fun isCleanTitle(text: String): Boolean {
        if (text.length > 200) return false
        if (SKIP_TITLES.any { text.contains(it, ignoreCase = true) }) return false
        if (SKIP_SYSTEM.any { text.contains(it, ignoreCase = true) }) return false

        // Reject if it starts with a lowercase letter — UI labels, ad panels,
        // and garbage strings are almost never proper-cased like real titles.
        if (text.first().isLowerCase()) return false

        // Reject if text contains a web domain — real titles never do.
        if (DOMAIN_PATTERN.containsMatchIn(text)) return false

        // Reject if text contains 2+ uppercase abbreviations (e.g. "PTE. LTD.")
        val abbrCount = Regex("[A-Z]{2,4}\\.").findAll(text).count()
        if (abbrCount >= 2) return false

        return true
    }

    /**
     * Scan-based repeated title detection — more robust than regex \1.
     */
    private fun extractRepeatedTitle(text: String): String? {
        val candidates = text
            .split(Regex("[\\n|•·–—]+"))
            .map { it.trim() }
            .filter { it.length in 12..200 }
            .distinctBy { it.lowercase() }

        for (candidate in candidates) {
            if (!isCleanTitle(candidate)) continue
            var idx = 0; var count = 0
            while (true) {
                idx = text.indexOf(candidate, idx)
                if (idx == -1) break
                count++; idx += candidate.length
            }
            if (count >= 2) return candidate
        }
        return null
    }

    private fun dispatchTitle(title: String) {
        if (title.length <= 5) return
        if (title == lastSentTitle) return

        lastSentTitle  = title
        lastSentTimeMs = System.currentTimeMillis()
        println("[CF_SERVICE] ✓ Detected title: $title")

        // Immediately show spinner in UI
        broadcastResult(videoId = title, label = "Analyzing", score = 0f, cached = false)
        scope.launch { classifyByTitle(title) }
    }

    private fun handleVideoId(videoId: String) {
<<<<<<< Updated upstream
        println("[CF_SERVICE] ✓ Direct video ID: $videoId")
        scope.launch { classifyFull(videoId) }
=======
        if (videoId == lastSentTitle) return
        lastSentTitle  = videoId
        lastSentTimeMs = System.currentTimeMillis()
        broadcastResult(videoId = videoId, label = "Analyzing", score = 0f, cached = false)
        scope.launch {
            classifyByUrl(
                videoId  = videoId,
                videoUrl = "https://www.youtube.com/watch?v=$videoId",
                thumbUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            )
        }
>>>>>>> Stashed changes
    }

    private fun collectAllNodeText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        try {
            node.text?.let               { sb.append(it).append(" ") }
            node.contentDescription?.let { sb.append(it).append(" ") }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                sb.append(collectAllNodeText(child))
                child.recycle()
            }
        } catch (_: Exception) { }
        return sb.toString()
    }

<<<<<<< Updated upstream
    private fun resolveAndClassify(title: String) {
        try {
            val encoded = URLEncoder.encode(title, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/search?title=$encoded")
                .get()
=======
    private fun classifyByTitle(title: String) {
        try {
            val body    = JSONObject().apply { put("title", title) }
            val request = Request.Builder()
                .url("$BASE_URL/classify_by_title")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
>>>>>>> Stashed changes
                .build()
            val response = http.newCall(request).execute()
<<<<<<< Updated upstream
            val body     = response.body?.string()
            response.body?.close()

            if (!response.isSuccessful || body == null) {
                println("[CF_SERVICE] ✗ Search failed: HTTP ${response.code}")
                return
            }

            val json    = JSONObject(body)
            val videoId = json.optString("video_id", "")
            val resolved = json.optString("title", title)

            if (videoId.isEmpty()) {
                println("[CF_SERVICE] ✗ No video_id in search result")
                return
            }

            println("[CF_SERVICE] ✓ Resolved: $title → $videoId ($resolved)")
            classifyFull(videoId)

=======
            val json     = JSONObject(response.body?.string() ?: return)
            handleClassificationResult(json)
>>>>>>> Stashed changes
        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ Search error: ${e.message}")
        }
    }

<<<<<<< Updated upstream
    private fun classifyFull(videoId: String) {
        try {
            println("[CF_SERVICE] Classifying: $videoId")

            val body = JSONObject().apply {
                put("video_url",     "https://www.youtube.com/watch?v=$videoId")
                put("thumbnail_url", "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
=======
    private fun classifyByUrl(videoId: String, videoUrl: String, thumbUrl: String) {
        try {
            val body    = JSONObject().apply {
                put("video_url",     videoUrl)
                put("thumbnail_url", thumbUrl)
>>>>>>> Stashed changes
            }
            val request = Request.Builder()
                .url("$BASE_URL/classify_full")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = http.newCall(request).execute()
<<<<<<< Updated upstream
            val resBody  = response.body?.string()
            response.body?.close()

            if (!response.isSuccessful || resBody == null) {
                println("[CF_SERVICE] ✗ classify_full failed: HTTP ${response.code}")
                broadcastError(videoId)
                return
            }

            val json   = JSONObject(resBody)
            val label  = json.optString("oir_label", "Neutral")
            val score  = json.optDouble("final_score", json.optDouble("score_final", 0.5))
            val cached = json.optBoolean("cached", false)

            println("[CF_SERVICE] ✓ $videoId → $label ($score) cached=$cached")

            val intent = Intent("com.childfocus.CLASSIFICATION_RESULT").apply {
                putExtra("video_id",   videoId)
                putExtra("oir_label",  label)
                putExtra("score_final", score.toFloat())
                putExtra("cached",     cached)
            }
            sendBroadcast(intent)

=======
            val json     = JSONObject(response.body?.string() ?: return)
            handleClassificationResult(json)
>>>>>>> Stashed changes
        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ classify_full error: ${e.message}")
            broadcastError(videoId)
        }
    }

<<<<<<< Updated upstream
    private fun broadcastError(videoId: String) {
        val intent = Intent("com.childfocus.CLASSIFICATION_RESULT").apply {
            putExtra("video_id",  videoId)
            putExtra("oir_label", "Error")
            putExtra("score_final", 0.5f)
            putExtra("cached",    false)
=======
    private fun handleClassificationResult(json: JSONObject) {
        val label   = json.optString("oir_label", "Neutral")
        val score   = json.optDouble("score_final", 0.5)
        val cached  = json.optBoolean("cached", false)
        val videoId = json.optString("video_id", "unknown")
        println("[CF_SERVICE] $videoId → $label ($score) cached=$cached")
        broadcastResult(videoId = videoId, label = label, score = score.toFloat(), cached = cached)
    }

    private fun broadcastResult(videoId: String, label: String, score: Float, cached: Boolean) {
        val intent = Intent("com.childfocus.CLASSIFICATION_RESULT").apply {
            putExtra("video_id",    videoId)
            putExtra("oir_label",   label)
            putExtra("score_final", score)
            putExtra("cached",      cached)
>>>>>>> Stashed changes
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onInterrupt() {
        println("[CF_SERVICE] Interrupted")
        lastSentTitle  = ""
        lastSentTimeMs = 0L
    }
}