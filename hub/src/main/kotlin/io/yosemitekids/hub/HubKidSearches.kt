package io.yosemitekids.hub

import io.yosemitekids.app.data.RecentSearches
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Each kid's last few searches, for the chips on the browser's search page.
 *
 * The phone keeps the same list per kid on the device (`SearchHistoryStore`);
 * a browser has no store of its own that survives a cleared site, and the
 * page may not hold the rules anyway (guard 61), so the hub keeps it. The
 * rules - newest first, no repeats, eight, the × forgets one - are
 * `RecentSearches` in `:core`, shared with the phone; this file only holds
 * what comes back. One small JSON, `{kid: [query, ...]}`, written whole
 * through a temp file like the history is, so a crash mid-write leaves the
 * old list rather than half of one.
 */
class HubKidSearches(dataDir: File) {

    private val file = File(dataDir, "kid-searches.json")
    private val lock = Any()

    fun recent(kid: String): List<String> = synchronized(lock) {
        val arr = read().optJSONArray(kid) ?: return emptyList()
        (0 until arr.length()).map { arr.getString(it) }
    }

    /** [query] remembered for [kid]: the front of the list, once. */
    fun add(kid: String, query: String): List<String> = update(kid) { RecentSearches.add(it, query) }

    /** One term, gone - the × on a chip, which has to be real. */
    fun remove(kid: String, query: String): List<String> = update(kid) { RecentSearches.remove(it, query) }

    fun clear(kid: String): List<String> = update(kid) { emptyList() }

    /** Every kid's list, dropped: a kid removed from the family takes their searches with them. */
    fun forgetKid(kid: String) = synchronized(lock) {
        val root = read()
        if (root.has(kid)) { root.remove(kid); write(root) }
    }

    private fun update(kid: String, f: (List<String>) -> List<String>): List<String> = synchronized(lock) {
        val before = recent(kid)
        val after = f(before)
        if (after != before) {
            val root = read()
            if (after.isEmpty()) root.remove(kid) else root.put(kid, JSONArray(after))
            write(root)
        }
        after
    }

    private fun read(): JSONObject =
        runCatching { if (file.exists()) JSONObject(file.readText()) else JSONObject() }.getOrDefault(JSONObject())

    private fun write(root: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
