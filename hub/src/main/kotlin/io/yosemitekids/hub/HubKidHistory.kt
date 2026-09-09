package io.yosemitekids.hub

import io.yosemitekids.app.data.KidHome
import java.io.File
import org.json.JSONObject

/**
 * How far a browser got through each video, per kid.
 *
 * ### Why the hub holds this at all
 *
 * Every other face keeps its own watch history on the device that did the
 * watching — `WatchHistoryStore` is SharedPreferences on a phone and on a
 * television, and neither ever syncs it. A browser has no such place: the one
 * storage it owns is the tab's, which a child clears by opening a new one, and
 * anything the page *told* the hub about itself is something a page could tell
 * it wrongly. So the box remembers, keyed by the credential it minted.
 *
 * That makes this the browser's device store, not a fifth copy of anybody
 * else's. A tablet's Keep watching row is that tablet's, exactly as the
 * television's is the television's — the same rule the rest of the project
 * already follows, arrived at from the other direction.
 *
 * ### Bounded, because a child's browser writes it
 *
 * This is the only store on this box a request without an admin session can
 * grow, so it is capped twice: [MAX_VIDEOS_PER_KID] rows per kid, oldest
 * watch evicted first, and nothing at all is written for a video the caller
 * has not been allowed to play. The cap is a file-size bound and nothing more
 * subtle — three hundred rows is more than a child watches in a year of
 * Saturdays, and evicting the oldest costs them a resume position on something
 * they finished with.
 */
class HubKidHistory(dataDir: File, private val now: () -> Long = { System.currentTimeMillis() }) {

    private val file = File(dataDir, "kid-history.json")
    private val lock = Any()

    companion object {
        /**
         * Rows kept per kid. See the class KDoc: a bound on a file a child's
         * browser can grow, chosen so that reaching it is not something a real
         * child does.
         */
        const val MAX_VIDEOS_PER_KID = 300

        /**
         * A position this far through counts as finished.
         *
         * [KidHome.FINISHED_FRACTION] and not a number of its own: "the same
         * grace the app gives" was a comment, and a comment is not a mechanism.
         * A video the hub calls finished is one the app calls finished because
         * both read the same constant.
         */
        const val FINISHED_FRACTION = KidHome.FINISHED_FRACTION

        /**
         * Positions closer together than this are not written.
         *
         * The page beats every [HubKidServer.BEAT_SECONDS] because the watch
         * meter needs it to; the history does not, and rewriting a JSON file
         * on every beat of every stream is the shape of problem a NAS notices.
         */
        const val WRITE_INTERVAL_MS = 30 * 1000L
    }

    private fun read(): JSONObject = synchronized(lock) {
        runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }

    private fun write(root: JSONObject) = synchronized(lock) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /**
     * Note where [videoUrl] got to for [kid].
     *
     * Returns false when the write was skipped as too soon after the last one —
     * useful to a test, and to nothing else.
     */
    fun save(kid: String, videoUrl: String, positionMs: Long, durationMs: Long): Boolean =
        synchronized(lock) {
            if (durationMs <= 0) return false
            val root = read()
            val forKid = root.optJSONObject(kid) ?: JSONObject()
            val at = now()
            val existing = forKid.optJSONObject(videoUrl)
            // A finished video is always written even if it is early: the whole
            // point of the last write is that it moves the row OUT of Keep
            // watching, and dropping it would leave the credits on screen there
            // for ever.
            val finished = positionMs.toFloat() / durationMs >= FINISHED_FRACTION
            if (!finished && existing != null && at - existing.optLong("at") < WRITE_INTERVAL_MS) {
                return false
            }
            forKid.put(
                videoUrl,
                JSONObject()
                    .put("pos", positionMs)
                    .put("dur", durationMs)
                    .put("at", at)
            )
            evictOldest(forKid)
            root.put(kid, forKid)
            write(root)
            true
        }

    /** Oldest watch first out, so the cap costs a resume position and never the newest. */
    private fun evictOldest(forKid: JSONObject) {
        if (forKid.length() <= MAX_VIDEOS_PER_KID) return
        val byAge = forKid.keys().asSequence().toList()
            .sortedBy { forKid.optJSONObject(it)?.optLong("at") ?: 0L }
        for (url in byAge.take(forKid.length() - MAX_VIDEOS_PER_KID)) forKid.remove(url)
    }

    /** Everything this kid has watched in a browser, as the shared shelf rules read it. */
    fun pointsFor(kid: String): Map<String, KidHome.WatchPoint> {
        val forKid = read().optJSONObject(kid) ?: return emptyMap()
        val out = HashMap<String, KidHome.WatchPoint>(forKid.length())
        for (url in forKid.keys()) {
            val row = forKid.optJSONObject(url) ?: continue
            val dur = row.optLong("dur")
            if (dur <= 0) continue
            val fraction = (row.optLong("pos").toFloat() / dur).coerceIn(0f, 1f)
            out[url] = KidHome.WatchPoint(
                fraction = fraction,
                lastWatchedAt = row.optLong("at"),
                isFinished = fraction >= FINISHED_FRACTION
            )
        }
        return out
    }

    /** Where to start [videoUrl] from, in ms — 0 for one never watched or already finished. */
    fun resumeMs(kid: String, videoUrl: String): Long {
        val row = read().optJSONObject(kid)?.optJSONObject(videoUrl) ?: return 0L
        val dur = row.optLong("dur")
        val pos = row.optLong("pos")
        if (dur <= 0 || pos <= 0) return 0L
        // Finished means start again, not "show the last four seconds".
        return if (pos.toFloat() / dur >= FINISHED_FRACTION) 0L else pos
    }

    /** Forget one kid's history — what the admin console's "clear" will call. */
    fun clear(kid: String) = synchronized(lock) {
        val root = read()
        root.remove(kid)
        write(root)
    }
}
