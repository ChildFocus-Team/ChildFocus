package com.childfocus.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.childfocus.BlockOverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ── Sealed state that the UI observes ──────────────────────────────────────
sealed class ClassifyState {
    object Idle : ClassifyState()
    data class Analyzing(val videoId: String) : ClassifyState()
    data class Blocked(
        val videoId: String,
        val label: String,
        val score: Float
    ) : ClassifyState()
    data class Safe(val videoId: String, val label: String) : ClassifyState()
}

class SafetyViewModel(application: Application) : AndroidViewModel(application) {

    // ── Safety mode toggle ─────────────────────────────────────────────────
    private val _safetyModeOn = MutableStateFlow(false)
    val safetyModeOn: StateFlow<Boolean> = _safetyModeOn

    // ── Classification state ───────────────────────────────────────────────
    private val _classifyState = MutableStateFlow<ClassifyState>(ClassifyState.Idle)
    val classifyState: StateFlow<ClassifyState> = _classifyState

    // ── Broadcast receiver ─────────────────────────────────────────────────
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!_safetyModeOn.value) return          // ignore if safety mode is off

            val videoId = intent.getStringExtra("video_id") ?: return
            val label   = intent.getStringExtra("oir_label") ?: "Neutral"
            val score   = intent.getFloatExtra("score_final", 0f)

            when {
                // "Analyzing" is a transient state — show spinner
                label == "Analyzing" -> {
                    _classifyState.value = ClassifyState.Analyzing(videoId)
                }

                // Overstimulating threshold — trigger block
                label.equals("Overstimulating", ignoreCase = true) ||
                        label.equals("Overstimulation", ignoreCase = true) -> {
                    _classifyState.value = ClassifyState.Blocked(videoId, label, score)
                    // Also start the overlay service directly so it works even
                    // when the app is in the background / screen is on YouTube
                    BlockOverlayService.show(context, videoId, label, score)
                }

                else -> {
                    _classifyState.value = ClassifyState.Safe(videoId, label)
                }
            }
        }
    }

    init {
        // Register for classification results from ChildFocusAccessibilityService
        LocalBroadcastManager.getInstance(application).registerReceiver(
            receiver,
            IntentFilter("com.childfocus.CLASSIFICATION_RESULT")
        )
    }

    // ── Public actions ─────────────────────────────────────────────────────

    fun toggleSafetyMode() {
        _safetyModeOn.value = !_safetyModeOn.value
        if (!_safetyModeOn.value) {
            // Turning off — hide any active overlay and reset state
            BlockOverlayService.hide(getApplication())
            _classifyState.value = ClassifyState.Idle
        }
    }

    fun dismissBlock() {
        BlockOverlayService.hide(getApplication())
        _classifyState.value = ClassifyState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        LocalBroadcastManager.getInstance(getApplication()).unregisterReceiver(receiver)
    }
}