package io.pickwick.app.data

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
        val q = query.trim()
        if (q.isEmpty()) return
        val next = listOf(q) + recent().filterNot { it.equals(q, ignoreCase = true) }
        prefs.edit().putString("recent", JSONArray(next.take(MAX)).toString()).apply()
    }

    fun clear() {
        prefs.edit().remove("recent").apply()
    }

    companion object {
        const val MAX = 8
    }
}
