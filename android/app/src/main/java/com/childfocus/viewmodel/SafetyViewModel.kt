package com.childfocus.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class ClassifyState {
    object Idle : ClassifyState()
    data class Analyzing(val videoId: String) : ClassifyState()
    data class Allowed(
        val videoId: String,
        val label:   String,
        val score:   Float,
        val cached:  Boolean = false,
    ) : ClassifyState()
    data class Blocked(
        val videoId: String,
        val label:   String,
        val score:   Float,
        val cached:  Boolean = false,
    ) : ClassifyState()
    data class Error(val videoId: String) : ClassifyState()
}

class SafetyViewModel(application: Application) : AndroidViewModel(application) {

    // ── Persist Safety Mode across restarts ────────────────────────────────────
    private val prefs = application.getSharedPreferences("childfocus_prefs", Context.MODE_PRIVATE)

    private val _safetyModeOn  = MutableStateFlow(prefs.getBoolean("safety_mode_on", false))
    val safetyModeOn: StateFlow<Boolean> = _safetyModeOn

    private val _classifyState = MutableStateFlow<ClassifyState>(ClassifyState.Idle)
    val classifyState: StateFlow<ClassifyState> = _classifyState

    // ── BroadcastReceiver: listens to ChildFocusAccessibilityService ───────────
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val videoId = intent?.getStringExtra("video_id")    ?: return
            val label   = intent.getStringExtra("oir_label")    ?: return
            val score   = intent.getFloatExtra("score_final", 0.5f)
            val cached  = intent.getBooleanExtra("cached", false)

            _classifyState.value = when (label) {
                "Analyzing"      -> ClassifyState.Analyzing(videoId)
                "Overstimulating" -> ClassifyState.Blocked(videoId, label, score, cached)
                "Error"          -> ClassifyState.Error(videoId)
                else             -> ClassifyState.Allowed(videoId, label, score, cached)
            }
        }
    }

    init {
        val filter = IntentFilter("com.childfocus.CLASSIFICATION_RESULT")
        application.registerReceiver(
            receiver,
            filter,
            Context.RECEIVER_NOT_EXPORTED,
        )
    }

    fun toggleSafetyMode() {
        val newValue = !_safetyModeOn.value
        _safetyModeOn.value = newValue
        // ── Save to SharedPreferences so it survives app restarts ──────────────
        prefs.edit().putBoolean("safety_mode_on", newValue).apply()
        if (!newValue) {
            _classifyState.value = ClassifyState.Idle
        }
    }

    fun dismissBlock() {
        _classifyState.value = ClassifyState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(receiver)
    }
}