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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class ChildFocusAccessibilityService : AccessibilityService() {

    private val scope     = CoroutineScope(Dispatchers.IO)
    private var lastTitle = ""
    private var lastVideoId = ""

    // ── Emulator uses 10.0.2.2 to reach host machine ──────────────────────────────
    private val BASE_URL = "http://10.0.2.2:5000"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val TITLE_PATTERN = Pattern.compile(
        "Minimized player\\s+(.+?)\\s+\\1",
        Pattern.DOTALL
    )

    private val URL_PATTERN = Pattern.compile("(?:v=|youtu\\.be/)([a-zA-Z0-9_-]{11})")

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
        event ?: return

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
        }

        // Strategy 2: extract title from node tree
        val root    = rootInActiveWindow ?: return
        val allText = collectAllNodeText(root)
        root.recycle()

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
        }
    }

    private fun handleVideoId(videoId: String) {
        println("[CF_SERVICE] ✓ Direct video ID: $videoId")
        scope.launch { classifyFull(videoId) }
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

    private fun resolveAndClassify(title: String) {
        try {
            val encoded = URLEncoder.encode(title, "UTF-8")
            val request = Request.Builder()
                .url("$BASE_URL/search?title=$encoded")
                .get()
                .build()

            val response = http.newCall(request).execute()
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

        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ Search error: ${e.message}")
        }
    }

    private fun classifyFull(videoId: String) {
        try {
            println("[CF_SERVICE] Classifying: $videoId")

            val body = JSONObject().apply {
                put("video_url",     "https://www.youtube.com/watch?v=$videoId")
                put("thumbnail_url", "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
            }

            val request = Request.Builder()
                .url("$BASE_URL/classify_full")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = http.newCall(request).execute()
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

        } catch (e: Exception) {
            println("[CF_SERVICE] ✗ classify_full error: ${e.message}")
            broadcastError(videoId)
        }
    }

    private fun broadcastError(videoId: String) {
        val intent = Intent("com.childfocus.CLASSIFICATION_RESULT").apply {
            putExtra("video_id",  videoId)
            putExtra("oir_label", "Error")
            putExtra("score_final", 0.5f)
            putExtra("cached",    false)
        }
        sendBroadcast(intent)
    }

    override fun onInterrupt() {
        println("[CF_SERVICE] Interrupted")
    }
}