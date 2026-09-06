package io.yosemitekids.app.data

import org.json.JSONObject

/**
 * The pull half of the search index: a device fetches from the hub what the
 * hub has crawled further than it holds. Pure apart from the two functions
 * it is handed — the hub's /index-status body and a fetch of one source —
 * so the app's ConfigSync and a JVM test drive the same code.
 *
 * Devices pull; the hub never pushes. It keeps no credential on any device,
 * and a device that could be pushed to could be truncated.
 */
object IndexPull {

    /**
     * The first build that pulls from a hub. Older devices still receive
     * the index the old way, relayed by the master phone (MainViewModel.
     * syncIndex): once the hub holds the slot, the phone pulls from it and
     * pushes on to any TV older than this.
     */
    const val FIRST_PULLING_VERSION_CODE = 6

    /** One source as the hub advertises it in /index-status. */
    data class Remote(val count: Int, val complete: Boolean, val hash: Int)

    /**
     * Whether the hub's copy is worth fetching over what this device holds.
     * A local copy that is ahead — a phone that was master before the hub
     * took over — is left alone: fetching would gain nothing, and the union
     * import below could not shrink it anyway.
     */
    fun shouldFetch(local: ChannelIndex.SourceState?, remote: Remote): Boolean = when {
        local == null -> remote.count > 0 || remote.complete
        remote.count > local.count -> true
        remote.complete && !local.complete -> true
        // Same size, different content: a newer upload replaced nothing but
        // moved the newest id. Cheap to take, and the union keeps ours.
        remote.count == local.count && remote.hash != local.contentHash() -> true
        else -> false
    }

    fun parseStatus(body: String?): Map<String, Remote> {
        val o = runCatching { JSONObject(body ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().mapNotNull { id ->
            o.optJSONObject(id)?.let { e ->
                id to Remote(e.optInt("count", 0), e.optBoolean("complete", false), e.optInt("hash", 0))
            }
        }.toMap()
    }

    /**
     * Pull every wanted source the hub holds more of. Returns how many were
     * imported.
     *
     * @param wanted the config's source ids: a source the hub still lists
     *   and this config dropped is not fetched, so the worker's drop pass
     *   and this never fight.
     * @param status the hub's /index-status body, or null when unreachable.
     * @param fetch one source in the /index wire format, or null.
     */
    suspend fun pull(
        index: ChannelIndex,
        wanted: Set<String>,
        status: String?,
        fetch: suspend (String) -> String?
    ): Int {
        val remote = parseStatus(status)
        if (remote.isEmpty()) return 0
        val local = index.allStates()
        var imported = 0
        for ((id, r) in remote) {
            if (id !in wanted) continue
            if (!shouldFetch(local[id], r)) continue
            val body = fetch(id) ?: continue
            if (index.importSourceUnion(id, body)) imported++
        }
        return imported
    }
}
