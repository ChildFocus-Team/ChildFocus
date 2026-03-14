package com.childfocus.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class ChildFocusAccessibilityService : AccessibilityService() {

    private val scope   = CoroutineScope(Dispatchers.IO)
    private var lastTitle = ""

    // ── Debounce: only classify once every 5 seconds per unique title ─────────
    private var lastClassifyTime = 0L
    private val DEBOUNCE_MS      = 5_000L

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // ── Pattern: minimized player (player collapsed to mini bar) ─────────────
    // YouTube exposes: "Minimized player <Title> <Title> @Channel ..."
    private val MINIMIZED_PATTERN = Pattern.compile(
        "Minimized player\\s+(.+?)\\s+\\1",
        Pattern.DOTALL
    )

    // ── Pattern: expanded player (full-screen / normal view) ──────────────────
    // YouTube exposes content descriptions like:
    //   "Gorillaz - New Gold, by Gorillaz, 12,345,678 views, 3 days ago"
    // We capture everything before the first ", by "
    private val EXPANDED_PATTERN = Pattern.compile(
        "^(.+?),\\s*by\\s+.+?,\\s*[\\d,.]+",
        Pattern.MULTILINE
    )

    // ── Pattern: direct video ID in URL (rare fallback) ───────────────────────
    private val URL_PATTERN = Pattern.compile("(?:v=|youtu\\.be/)([a-zA-Z0-9_-]{11})")

    // ── Junk filter: skip ad/sponsored strings — never send to Flask ──────────
    // Matches: "Sponsored", "Sponsored · 1 of 2 · 0:43", "Ad ·", "Skip Ad", etc.
    private val JUNK_REGEX = Regex(
        """^(Sponsored|Ad\s*[·•]|Skip\s*Ad|Visit\s*advertiser|\d+\s*of\s*\d+)""",
        RegexOption.IGNORE_CASE
    )

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes   = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                           AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageNames = arrayOf("com.google.android.youtube")
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags        = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        println("[CF_SERVICE] ✓ Connected — monitoring YouTube")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            event ?: return

            // ── Strategy 1: direct video ID in event text (rare) ─────────────
            val eventText = event.text?.joinToString(" ") ?: ""
            val urlMatch  = URL_PATTERN.matcher(eventText)
            if (urlMatch.find()) {
                handleVideoId(urlMatch.group(1) ?: return)
                return
            }

            // ── Strategy 2: scan full node tree ──────────────────────────────
            val root    = rootInActiveWindow ?: return
            val allText = collectAllNodeText(root)
            root.recycle()

            // Try URL pattern in tree first (most reliable when available)
            val urlInTree = URL_PATTERN.matcher(allText)
            if (urlInTree.find()) {
                handleVideoId(urlInTree.group(1) ?: return)
                return
            }

            // Try title extraction (minimized → expanded player order)
            val title = extractTitle(allText) ?: return

            // Skip ads / sponsored content — never waste a Flask call on these
            if (JUNK_REGEX.containsMatchIn(title)) {
                println("[CF_SERVICE] ⚡ Skipped junk: $title")
                return
            }

            // Debounce: same title within 5 s → skip
            val now = System.currentTimeMillis()
            if (title == lastTitle && (now - lastClassifyTime) < DEBOUNCE_MS) return

            lastTitle        = title
            lastClassifyTime = now

            println("[CF_SERVICE] ✓ Detected title: $title")
            scope.launch { classifyByTitle(title) }

        } catch (t: Throwable) {
            println("[CF_SERVICE] ✗ onAccessibilityEvent error: ${t.message}")
        }
    }

    /**
     * Try both player states in order:
     *   1. Minimized player  → "Minimized player <Title> <Title>"
     *   2. Expanded player   → "<Title>, by <Channel>, <views>"
     * Returns the first match, or null if neither fires.
     */
    private fun extractTitle(allText: String): String? {
        // Minimized player
        val minimized = MINIMIZED_PATTERN.matcher(allText)
        if (minimized.find()) {
            return minimized.group(1)?.trim()?.takeIf { it.length > 5 }
        }

        // Expanded / full-screen player — scan each line for ", by <Channel>,"
        val expanded = EXPANDED_PATTERN.matcher(allText)
        while (expanded.find()) {
            val candidate = expanded.group(1)?.trim() ?: continue
            // Must be >5 chars and not a generic YouTube UI label
            if (candidate.length > 5 &&
                !candidate.contains("Subscribe") &&
                !candidate.contains("Share") &&
                !candidate.contains("Like")) {
                return candidate
            }
        }

        return null
    }

    /**
     * Iterative BFS tree walk — prevents StackOverflowError on deep YouTube trees.
     */
    private fun collectAllNodeText(root: AccessibilityNodeInfo): String {
        val sb    = StringBuilder()
        val queue = LinkedList<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            try {
                node.text?.let               { sb.append(it).append(" ") }
                node.contentDescription?.let { sb.append(it).append(" ") }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.add(child)
                }
            } catch (_: Exception) {
                // skip malformed nodes
            } finally {
                if (node !== root) node.recycle()
            }
        }
        return sb.toString()
    }

    private fun handleVideoId(videoId: String) {
        val now = System.currentTimeMillis()
        if ((now - lastClassifyTime) < DEBOUNCE_MS) return
        lastClassifyTime = now

        scope.launch {
            classifyByUrl(
                videoId  = videoId,
                videoUrl = "https://www.youtube.com/watch?v=$videoId",
                thumbUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            )
        }
    }

    // ── Title-based classification (main path) ────────────────────────────────

    private fun classifyByTitle(title: String) {
        try {
            val body = JSONObject().apply { put("title", title) }

            val request = Request.Builder()
                .url("http://10.0.2.2:5000/classify_by_title")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = http.newCall(request).execute()
            val json     = JSONObject(response.body?.string() ?: return)

            handleClassificationResult(json)

        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ classify_by_title error: ${e.message}")
        }
    }

    // ── Direct URL classification (fallback when video ID is known) ───────────

    private fun classifyByUrl(videoId: String, videoUrl: String, thumbUrl: String) {
        try {
            val body = JSONObject().apply {
                put("video_url",     videoUrl)
                put("thumbnail_url", thumbUrl)
            }

            val request = Request.Builder()
                .url("http://10.0.2.2:5000/classify_full")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = http.newCall(request).execute()
            val json     = JSONObject(response.body?.string() ?: return)

            handleClassificationResult(json)

        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ classify_full error: ${e.message}")
        }
    }

    private fun handleClassificationResult(json: JSONObject) {
        val label   = json.optString("oir_label", "Neutral")
        val score   = json.optDouble("score_final", 0.5)
        val cached  = json.optBoolean("cached", false)
        val videoId = json.optString("video_id", "unknown")

        println("[CF_SERVICE] $videoId → $label ($score) cached=$cached")

        val intent = Intent("com.childfocus.CLASSIFICATION_RESULT").apply {
            putExtra("video_id",    videoId)
            putExtra("oir_label",   label)
            putExtra("score_final", score.toFloat())
            putExtra("cached",      cached)
        }
        sendBroadcast(intent)
    }

    override fun onInterrupt() {
        println("[CF_SERVICE] Interrupted")
    }
}
