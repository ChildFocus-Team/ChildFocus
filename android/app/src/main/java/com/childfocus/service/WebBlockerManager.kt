package com.childfocus.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Manages the blocked sites list with full persistence via SharedPreferences.
 *
 * Crash-proof design:
 * - No lateinit — nullable prefs with safe fallbacks everywhere.
 * - init() is idempotent (safe to call multiple times from any component).
 * - Handles migration: if the old version stored blocked_sites as a String
 *   instead of a Set<String>, the bad value is wiped and we start fresh.
 */
object WebBlockerManager {

    private const val PREFS_NAME        = "web_blocker_prefs"
    private const val KEY_BLOCKED_SITES = "blocked_sites"
    private const val KEY_ENABLED       = "blocker_enabled"

    private var prefs: SharedPreferences? = null
    private val cache = mutableSetOf<String>()
    private var cacheLoaded = false

    // -------------------------------------------------------------------------
    // Initialisation — idempotent, safe to call from multiple entry points
    // -------------------------------------------------------------------------

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadCache()
    }

    private fun loadCache() {
        if (cacheLoaded) return
        cache.clear()
        try {
            // New format: Set<String>
            cache.addAll(
                prefs?.getStringSet(KEY_BLOCKED_SITES, emptySet()) ?: emptySet()
            )
        } catch (e: ClassCastException) {
            // Old format stored the value as a plain String — wipe it and
            // start fresh so the app no longer crashes on launch.
            prefs?.edit { remove(KEY_BLOCKED_SITES) }
        }
        cacheLoaded = true
    }

    // -------------------------------------------------------------------------
    // Enable / disable toggle
    // -------------------------------------------------------------------------

    var isEnabled: Boolean
        get() = prefs?.getBoolean(KEY_ENABLED, true) ?: true
        set(value) { prefs?.edit { putBoolean(KEY_ENABLED, value) } }

    // -------------------------------------------------------------------------
    // Blocked sites management
    // -------------------------------------------------------------------------

    fun getBlockedSites(): Set<String> = cache.toSet()

    fun addSite(rawUrl: String) {
        val domain = normalise(rawUrl)
        if (domain.isNotEmpty() && cache.add(domain)) persist()
    }

    fun removeSite(rawUrl: String) {
        if (cache.remove(normalise(rawUrl))) persist()
    }

    fun clearAll() {
        cache.clear()
        persist()
    }

    // -------------------------------------------------------------------------
    // Blocking check
    // -------------------------------------------------------------------------

    fun isBlocked(url: String): Boolean {
        if (!isEnabled) return false
        val host = normalise(url)
        return cache.any { blocked -> host == blocked || host.endsWith(".$blocked") }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun persist() {
        prefs?.edit { putStringSet(KEY_BLOCKED_SITES, cache.toSet()) }
    }

    fun normalise(raw: String): String = raw
        .trim()
        .lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .substringBefore("/")
        .substringBefore("?")
        .substringBefore("#")
        .trim()
}