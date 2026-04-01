package com.childfocus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.net.URL

class BlockOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun loadThumbnail(videoId: String, imageView: ImageView) {
        serviceScope.launch {
            try {
                val url = URL("https://i.ytimg.com/vi/$videoId/mqdefault.jpg")
                val bitmap = BitmapFactory.decodeStream(url.openStream())
                mainHandler.post { imageView.setImageBitmap(bitmap) }
            } catch (e: Exception) {
                // thumbnail load failed silently — no placeholder needed
            }
        }
    }

    data class RecommendedVideo(
        val title: String,
        val videoId: String,
        val category: String
    )

    companion object {
        const val ACTION_SHOW    = "com.childfocus.overlay.SHOW"
        const val ACTION_HIDE    = "com.childfocus.overlay.HIDE"
        const val EXTRA_VIDEO_ID = "video_id"
        const val EXTRA_LABEL    = "label"
        const val EXTRA_SCORE    = "score"

        // These are verified working video IDs from the educational/neutral channels
        val RECOMMENDED_VIDEOS = listOf(
            // Educational
            RecommendedVideo("Sesame Street: Do De Rubber Duck",     "KpM6oFHmBBQ", "Educational"),
            RecommendedVideo("Sesame Street: Elmo's Got the Moves",  "FHmK8aiwXs8", "Educational"),
            RecommendedVideo("Khan Academy Kids: Adding Numbers",    "gA2B_kelXpg", "Educational"),
            RecommendedVideo("Numberblocks: One",                    "cpFHMNSPNTk", "Educational"),
            RecommendedVideo("Alphablocks: A",                       "BJCGMMWpEWo", "Educational"),
            RecommendedVideo("SciShow Kids: Why Do We Dream?",       "GiBGwFOL8qA", "Educational"),
            RecommendedVideo("Nat Geo Kids: Amazing Animals",        "iqHLDEMoiKQ", "Educational"),
            RecommendedVideo("Crash Course Kids: Ecosystems",        "IDV8ou3FbwI", "Educational"),
            RecommendedVideo("PBS Kids: Curious George",             "g_i_tVYTYp4", "Educational"),
            RecommendedVideo("Peekaboo Kidz: Solar System",          "mQrlgH97v94", "Educational"),
            // Neutral
            RecommendedVideo("Peppa Pig: Muddy Puddles",             "keXn2HB4MkI", "Neutral"),
            RecommendedVideo("Peppa Pig: The Playground",            "0SYcr5Qv0mg", "Neutral"),
            RecommendedVideo("Bluey: Camping",                       "UkH4kGzBPCk", "Neutral"),
            RecommendedVideo("Bluey: The Pool",                      "Ek5J3rEMjOU", "Neutral"),
            RecommendedVideo("Paw Patrol: Pups Save Ryder",          "bLzCVIFhZYE", "Neutral"),
            RecommendedVideo("Thomas & Friends: Go Go Thomas",       "S-b_RmMjqF8", "Neutral"),
            RecommendedVideo("Mr Bean: Do-It-Yourself Mr Bean",      "b-Kd9MuFMBo", "Neutral"),
            RecommendedVideo("Shaun the Sheep: Off the Baa",         "YK86H2Mc9kc", "Neutral"),
            RecommendedVideo("Lego: City Mini Movies",               "L4LqBNKBVgA", "Neutral"),
            RecommendedVideo("Paw Patrol: Sea Patrol",               "1R3VoAkHPEI", "Neutral"),
        )

        fun show(context: Context, videoId: String, label: String, score: Float) {
            val intent = Intent(context, BlockOverlayService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_VIDEO_ID, videoId)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_SCORE, score)
            }
            context.startForegroundService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, BlockOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                val videoId = intent.getStringExtra(EXTRA_VIDEO_ID) ?: ""
                val label   = intent.getStringExtra(EXTRA_LABEL)    ?: "Overstimulating"
                val score   = intent.getFloatExtra(EXTRA_SCORE, 0f)
                pauseYouTube()
                showOverlay(videoId, label, score)
            }
            ACTION_HIDE -> {
                removeOverlay()
                stopSelf()
            }
        }
        return START_STICKY
    }

    // Sends a media pause broadcast to stop YouTube playback
    private fun pauseYouTube() {
        // Standard music service pause command
        val pause = Intent("com.android.music.musicservicecommand").apply {
            putExtra("command", "pause")
        }
        sendBroadcast(pause)

        // Media button pause via AudioManager
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val downEvent = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_DOWN,
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
        )
        val upEvent = android.view.KeyEvent(
            android.view.KeyEvent.ACTION_UP,
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
        )
        audioManager.dispatchMediaKeyEvent(downEvent)
        audioManager.dispatchMediaKeyEvent(upEvent)
    }

    private fun showOverlay(videoId: String, label: String, score: Float) {
        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_blocked, null)

        overlayView?.apply {
            findViewById<TextView>(R.id.tvScore)?.text =
                "Score: ${"%.2f".format(score)} (threshold: 0.20)"

            val container = findViewById<ViewGroup>(R.id.llSuggestions)

            val picks =
                RECOMMENDED_VIDEOS.filter { it.category == "Educational" }.shuffled().take(3) +
                        RECOMMENDED_VIDEOS.filter { it.category == "Neutral" }.shuffled().take(3)


            picks.forEach { video ->
                val card = LayoutInflater.from(context)
                    .inflate(R.layout.item_recommendation, container, false)

                card.findViewById<TextView>(R.id.tv_video_title)?.text     = video.title
                card.findViewById<TextView>(R.id.tv_video_category)?.text  = video.category
                card.findViewById<ImageView>(R.id.iv_thumbnail)?.let { loadThumbnail(video.videoId, it) }

                card.setOnClickListener {
                    removeOverlay()
                    openRecommendedVideoClean(video.title)
                    stopSelf()
                }
                container.addView(card)
            }
        }

        windowManager.addView(overlayView, params)
    }

    /**
     * 1. Relaunch YouTube with FLAG_ACTIVITY_CLEAR_TASK — nukes the blocked
     *    video from the entire back stack so Back can never return to it.
     * 2. After YouTube is back on its home feed, open a search for the
     *    recommended video title.
     */
    private fun openRecommendedVideoClean(title: String) {
        val mainHandler = Handler(Looper.getMainLooper())

        // Step 1 — reset YouTube, wiping back stack
        val resetIntent = packageManager
            .getLaunchIntentForPackage("com.google.android.youtube")
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
        if (resetIntent != null) startActivity(resetIntent)

        // Step 2 — search for the recommended title after YouTube home loads
        mainHandler.postDelayed({
            val query = Uri.encode("$title for kids")
            val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                `package` = "com.google.android.youtube"
                putExtra("query", "$title for kids")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val webFallback = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=$query")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            try { startActivity(searchIntent) }
            catch (_: Exception) { try { startActivity(webFallback) } catch (_: Exception) {} }
        }, 900)
    }

    private fun removeOverlay() {
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }

    private fun startForegroundWithNotification() {
        val channelId = "childfocus_overlay"
        val channel = NotificationChannel(
            channelId,
            "ChildFocus Safety Overlay",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ChildFocus is active")
            .setContentText("Monitoring YouTube for overstimulating content")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .build()

        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }
}