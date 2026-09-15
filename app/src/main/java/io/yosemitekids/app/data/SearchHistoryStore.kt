package io.yosemitekids.app.data

import android.content.Context
import org.json.JSONArray

/**
 * The kid's last few searches, per kid, for the chips on the search page. A
 * child who can't type well repeats the same handful of searches; one tap on
 * "dinosaurs" beats spelling it every time. Device-local, never synced.
 */
class SearchHistoryStore(context: Context, profileSuffix: String = "") {

    private val prefs = context.applicationContext
        .getSharedPreferences("search_history$profileSuffix", Context.MODE_PRIVATE)

    fun recent(): List<String> = runCatching {
        val arr = JSONArray(prefs.getString("recent", "[]"))
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun add(query: String) {
        // The rules are RecentSearches in :core, shared with the hub's per-kid
        // list for the browser; this store only holds what comes back.
        val next = RecentSearches.add(recent(), query)
        if (next == recent()) return
        prefs.edit().putString("recent", JSONArray(next).toString()).apply()
    }

    /**
     * One term, gone. Search history is the single thing a kid may delete on
     * their own — everything else they collect is cleared by a parent — so the
     * per-chip × has to be real, not a Clear-all in disguise.
     */
    fun remove(query: String) {
        val next = RecentSearches.remove(recent(), query)
        prefs.edit().putString("recent", JSONArray(next).toString()).apply()
    }

    fun clear() {
        prefs.edit().remove("recent").apply()
    }

    companion object {
        const val MAX = RecentSearches.MAX
    }
}
