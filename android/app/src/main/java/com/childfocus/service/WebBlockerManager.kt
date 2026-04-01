package com.childfocus.service

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages the list of blocked websites using SharedPreferences.
 * Provides thread-safe access to the blocked sites list.
 */
object WebBlockerManager {

    private const val PREFS_NAME = "web_blocker_prefs"
    private const val KEY_BLOCKED_SITES = "blocked_sites"
    private const val KEY_BLOCKER_ENABLED = "blocker_enabled"
    private const val DELIMITER = "|||"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns all blocked site patterns (lowercased, trimmed). */
    fun getBlockedSites(context: Context): Set<String> {
        val raw = getPrefs(context).getString(KEY_BLOCKED_SITES, "") ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split(DELIMITER)
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** Adds a site to the blocked list. Returns false if already present. */
    fun addSite(context: Context, site: String): Boolean {
        val cleaned = cleanSite(site)
        if (cleaned.isBlank()) return false
        val current = getBlockedSites(context).toMutableSet()
        if (current.contains(cleaned)) return false
        current.add(cleaned)
        saveSites(context, current)
        return true
    }

    /** Removes a site from the blocked list. */
    fun removeSite(context: Context, site: String) {
        val cleaned = cleanSite(site)
        val current = getBlockedSites(context).toMutableSet()
        current.remove(cleaned)
        saveSites(context, current)
    }

    /** Replaces entire blocked list. */
    fun setSites(context: Context, sites: Set<String>) {
        val cleaned = sites.map { cleanSite(it) }.filter { it.isNotEmpty() }.toSet()
        saveSites(context, cleaned)
    }

    fun isEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_BLOCKER_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_BLOCKER_ENABLED, enabled).apply()
    }

    /**
     * Checks whether a given URL/window title matches any blocked site pattern.
     * Matches on domain, subdomain, or keyword presence.
     */
    fun isBlocked(context: Context, urlOrTitle: String): Boolean {
        if (!isEnabled(context)) return false
        val lower = urlOrTitle.lowercase()
        return getBlockedSites(context).any { pattern ->
            lower.contains(pattern)
        }
    }

    private fun saveSites(context: Context, sites: Set<String>) {
        getPrefs(context).edit()
            .putString(KEY_BLOCKED_SITES, sites.joinToString(DELIMITER))
            .apply()
    }

    private fun cleanSite(site: String): String {
        return site.trim().lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
    }
}