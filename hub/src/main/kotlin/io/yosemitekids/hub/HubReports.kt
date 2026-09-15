package io.yosemitekids.hub

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the clients have said went wrong, kept where a parent can read it.
 *
 * A device drains its own ring ([io.yosemitekids.app.data.Diag]) into
 * `POST /report` the next time its sweep reaches this hub; a child's browser
 * posts its page errors to `POST /kid/report`. Each entry lands in two
 * places at once: **stdout**, one line each, so `docker logs` carries the
 * whole family's trail beside the hub's own; and this ring, which
 * `/api/state` serves and the console's Devices page draws.
 *
 * **In memory, on purpose.** The container log is already durable for as
 * long as Docker keeps it, and a second copy on the volume would bring
 * rotation, a backup-envelope question and a privacy question with it. A
 * restart forgets the ring and keeps the log, which is the right way round
 * for a trail whose unit is "this afternoon".
 *
 * Bounded twice: [MAX] entries in the ring, [MAX_PER_POST] accepted from one
 * call, and every string cut to [MAX_TEXT], because all of it comes off the
 * wire from a credential that can do nothing else here.
 */
class HubReports(private val now: () -> Long = { System.currentTimeMillis() }) {

    data class Entry(
        /** When the client says it happened, wall-clock ms. */
        val at: Long,
        /** When this hub heard about it. Later than [at] for a device that was offline. */
        val heardAt: Long,
        /** "device" or "browser". */
        val from: String,
        /** The device's name as enrolled, or the child a browser watches as. */
        val who: String,
        /** tv / phone / tablet / browser, or blank. */
        val kind: String,
        val version: String,
        /** warn / error / crash / page. */
        val level: String,
        val message: String
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("at", at).put("heardAt", heardAt).put("from", from).put("who", who)
            .put("kind", kind).put("version", version).put("level", level).put("msg", message)
    }

    private val lock = Any()
    private val ring = ArrayDeque<Entry>()

    /**
     * Take one client's post. [entries] is whatever it sent, already parsed;
     * anything malformed is skipped rather than refused, because one bad row
     * must not cost the rest of a trail. Returns how many were kept.
     */
    fun record(from: String, who: String, kind: String?, version: String?, entries: JSONArray?): Int {
        val arr = entries ?: return 0
        var kept = 0
        val heard = now()
        val list = ArrayList<Entry>()
        for (i in 0 until minOf(arr.length(), MAX_PER_POST)) {
            val o = arr.optJSONObject(i) ?: continue
            val msg = o.optString("msg").trim().take(MAX_TEXT)
            if (msg.isEmpty()) continue
            list += Entry(
                at = o.optLong("at", heard),
                heardAt = heard,
                from = from,
                who = who.take(64),
                kind = kind.orEmpty().take(16),
                version = version.orEmpty().take(32),
                level = o.optString("level").take(16).ifEmpty { "warn" },
                message = msg
            )
            kept++
        }
        synchronized(lock) {
            list.forEach { e ->
                ring.addLast(e)
                while (ring.size > MAX) ring.removeFirst()
            }
        }
        // The container log, one line per entry: the client's own time, who,
        // what. This is the half a parent without the console can still
        // reach, and the half that survives a restart.
        list.forEach { e ->
            println("report ${e.from} ${e.who} (${listOf(e.kind, e.version).filter { it.isNotBlank() }.joinToString(" ")}) [${e.level}] ${e.message}")
        }
        return kept
    }

    /** Newest first, for the console. */
    fun recent(limit: Int = MAX): JSONArray = synchronized(lock) {
        JSONArray().apply { ring.reversed().take(limit).forEach { put(it.toJson()) } }
    }

    fun size(): Int = synchronized(lock) { ring.size }

    companion object {
        const val MAX = 300
        const val MAX_PER_POST = 50
        const val MAX_TEXT = 500
    }
}
