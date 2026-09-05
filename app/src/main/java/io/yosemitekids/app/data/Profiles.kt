package io.yosemitekids.app.data

import android.content.Context
import org.json.JSONObject

/**
 * Device-local mapping profileId → SharedPreferences/file suffix. The first
 * profile this device ever sees keeps the empty suffix, so it silently inherits
 * everything the device recorded back when "the device was the profile" —
 * watch history, resume points, screen-time budget. No copy, no migration.
 */
class ProfileNamespace(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("profile_ns", Context.MODE_PRIVATE)

    /**
     * Ensure every profile has a suffix. Call whenever a config with profiles
     * loads. Only this path may hand out the legacy "" suffix, and only to the
     * first-listed profile while the map is empty — profiles[0] is the one the
     * parent grew out of the original single-kid setup.
     */
    fun register(profileIds: List<String>) {
        if (profileIds.isEmpty()) return
        synchronized(LOCK) {
            val map = load()
            if (map.isEmpty()) map[profileIds.first()] = ""
            profileIds.forEach { id -> map.getOrPut(id) { "_$id" } }
            // commit, not apply: this decides who inherits the legacy stores,
            // and the caller goes on to open them on the strength of it.
            prefs.edit().putString("map", JSONObject(map as Map<String, String>).toString()).commit()
        }
    }

    /** Suffix for a profile ("" = legacy stores). Null falls back to legacy. */
    fun suffixFor(profileId: String?): String = synchronized(LOCK) {
        profileId ?: return ""
        val map = load()
        map[profileId]?.let { return it }
        // An id that arrives ahead of any config registration (a peer's
        // watch-state push can beat the config push to a fresh device) gets a
        // real suffix, never the legacy "" — only a config load may decide who
        // inherits the pre-profile stores.
        map[profileId] = "_$profileId"
        prefs.edit().putString("map", JSONObject(map as Map<String, String>).toString()).apply()
        return "_$profileId"
    }

    private fun load(): MutableMap<String, String> = runCatching {
        val o = JSONObject(prefs.getString("map", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    companion object {
        /**
         * Class-level, and deliberately not `@Synchronized`. Callers build a
         * fresh `ProfileNamespace` per use — `ConfigStore.registered` does it
         * on every load, from the ViewModel and from LAN worker threads — so
         * an instance lock guards a throwaway object and provides no mutual
         * exclusion whatsoever. The read-modify-write below decides which kid
         * inherits the legacy unsuffixed stores; losing that race hands one
         * kid another kid's watch history, resume points and screen-time
         * budget, which is silent and unrecoverable.
         */
        private val LOCK = Any()
    }
}

/**
 * Which kid this device is currently showing. On a dedicated device (assigned
 * in the parent settings) the answer never changes; on a shared one the
 * who's-watching screen sets it, and a sitting-length gap re-asks — the same
 * rhythm as the session guard's break timer, so "new sitting" means the same
 * thing everywhere. With no break rule set there is no such rhythm: the screen
 * asks once each time the app launches and then leaves the pick alone.
 */
class ActiveProfileStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("active_profile", Context.MODE_PRIVATE)

    fun activeId(): String? = prefs.getString("active", null)

    fun setActive(profileId: String) {
        prefs.edit()
            .putString("active", profileId)
            .putLong("last_active_at", System.currentTimeMillis())
            .apply()
    }

    /** Poked while the app is in use, so the re-ask gap measures real absence. */
    fun touch() {
        prefs.edit().putLong("last_active_at", System.currentTimeMillis()).apply()
    }

    /**
     * Whether a shared device should ask who's watching again. Mid-sitting
     * (back from the player, between episodes) it must not nag.
     *
     * No break rule → no sitting rhythm to measure, and no hidden default: the
     * ask happens once per app launch instead. Returning true here makes launch
     * resolution skip the remembered kid; the mid-session ON_START re-ask is
     * gated off separately (see MainActivity) so this never fires between videos.
     */
    fun needsReask(breakMinutes: Int?): Boolean {
        if (breakMinutes == null) return true
        val last = prefs.getLong("last_active_at", 0L)
        if (last == 0L) return true
        return System.currentTimeMillis() - last >= breakMinutes * 60_000L
    }
}
