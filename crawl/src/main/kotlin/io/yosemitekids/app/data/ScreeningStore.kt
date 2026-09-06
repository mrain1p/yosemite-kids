package io.yosemitekids.app.data

import org.json.JSONObject
import java.io.File

/**
 * Persistent per-video AI verdicts. A video is screened once per rules version;
 * verdicts survive restarts so the catalog isn't re-screened (and re-billed) on
 * every launch. Title/channel/thumb are stored so stats can show what was blocked
 * even after the feed cache moves on.
 *
 * In :crawl rather than :app because the hub holds these too. A phone pushes
 * its verdicts to every paired peer on each sweep, the hub among them, and the
 * hub's review queue is built from nothing else — which is why it needs no
 * video cache of its own. Constructed from a File for the same reason
 * [ChannelIndex] is: the hub keeps its copy under /data and has no Context to
 * offer. The Android convenience constructor lives in :app, in
 * ScreeningStoreAndroid.kt.
 */
class ScreeningStore(private val file: File) {

    data class Entry(
        /** Strictest across kids — profile-unaware readers stay fail-closed. */
        val verdict: AiScreener.Verdict,
        val reason: String,
        val title: String,
        val channel: String,
        val thumb: String?,
        val rulesVersion: Int,
        val at: Long,
        /** Per-kid verdicts (profile id → verdict); empty on pre-profile entries. */
        val perProfile: Map<String, AiScreener.Verdict> = emptyMap(),
        /**
         * True once the pre-play deep check (description + tags + transcript)
         * has ruled. A deep verdict is the stronger evidence: the play gate
         * runs it once per video per rules version, and a title-only batch
         * must never overwrite it — see [Screener.screenBatch].
         */
        val deep: Boolean = false,
        /**
         * [AiScreener.noteHash] of the channel note this verdict was judged
         * under (0 = none). An ALLOW/REVIEW under a different note is stale —
         * the parent changed the channel's rules — and re-screens; a BLOCK
         * never goes stale: notes exist to catch more junk, not to un-block.
         */
        val noteHash: Int = 0
    ) {
        fun verdictFor(profileId: String?): AiScreener.Verdict =
            profileId?.let { perProfile[it] } ?: verdict
    }

    private fun signature() = Triple(file.path, file.lastModified(), file.length())

    /**
     * More than one instance of this store is alive at a time (the feed's screener
     * writes; the parent's review queue reads; the LAN server imports peer
     * verdicts), which is why the parsed map lives in the companion, not the
     * instance: a per-instance cache went stale after another instance wrote, and
     * rebuilding it re-parsed ~5000 entries of JSON on the main thread during feed
     * filtering. The signature check remains as cheap insurance against a write
     * that somehow bypassed [LOCK]. Length is checked alongside mtime because
     * filesystem timestamps are only second-granular, and a screening batch lands
     * well inside one second. Callers must hold [LOCK].
     */
    private fun all(): MutableMap<String, Entry> {
        cache?.let { if (signature() == cacheKey) return it }
        val map = mutableMapOf<String, Entry>()
        runCatching {
            if (file.exists()) {
                val root = JSONObject(file.readText())
                root.keys().forEach { id -> map[id] = parseEntry(root.getJSONObject(id)) }
            }
        }
        cache = map
        cacheKey = signature()
        return map
    }

    private fun parseEntry(o: JSONObject): Entry {
        val pp = o.optJSONObject("pp")?.let { obj ->
            obj.keys().asSequence().associateWith { pid ->
                runCatching { AiScreener.Verdict.valueOf(obj.getString(pid)) }
                    .getOrDefault(AiScreener.Verdict.REVIEW)
            }
        } ?: emptyMap()
        return Entry(
            verdict = runCatching { AiScreener.Verdict.valueOf(o.getString("v")) }
                .getOrDefault(AiScreener.Verdict.REVIEW),
            reason = o.optString("why"),
            title = o.optString("title"),
            channel = o.optString("channel"),
            thumb = o.optString("thumb").ifEmpty { null },
            rulesVersion = o.optInt("rv"),
            at = o.optLong("at"),
            perProfile = pp,
            deep = o.optBoolean("dc"),
            noteHash = o.optInt("nh")
        )
    }

    private fun entryJson(e: Entry): JSONObject = JSONObject()
        .put("v", e.verdict.name)
        .put("why", e.reason)
        .put("title", e.title)
        .put("channel", e.channel)
        .put("thumb", e.thumb ?: "")
        .put("rv", e.rulesVersion)
        .put("at", e.at)
        .apply { if (e.deep) put("dc", true) }
        .apply { if (e.noteHash != 0) put("nh", e.noteHash) }
        .apply {
            if (e.perProfile.isNotEmpty()) {
                put("pp", JSONObject().apply {
                    e.perProfile.forEach { (pid, v) -> put(pid, v.name) }
                })
            }
        }

    fun get(videoId: String): Entry? = synchronized(LOCK) { all()[videoId] }

    fun putAll(entries: Map<String, Entry>) {
        synchronized(LOCK) {
            val map = all()
            map.putAll(entries)
            // Cap the file: keep the newest verdicts, drop ancient ones (they'd
            // simply be re-screened if that video ever resurfaces).
            if (map.size > 5000) {
                val keep = map.entries.sortedByDescending { it.value.at }.take(4000)
                map.clear()
                keep.forEach { map[it.key] = it.value }
            }
            persist(map)
        }
    }

    /** Blocked/review verdicts for the current rules, newest first — the stats feed. */
    fun flagged(rulesVersion: Int): List<Pair<String, Entry>> = synchronized(LOCK) {
        all().entries
            .filter { it.value.rulesVersion == rulesVersion && it.value.verdict != AiScreener.Verdict.ALLOW }
            .sortedByDescending { it.value.at }
            .map { it.key to it.value }
    }

    fun screenedCount(rulesVersion: Int): Int = synchronized(LOCK) {
        all().values.count { it.rulesVersion == rulesVersion }
    }

    /**
     * This device's verdicts under [rulesVersion], serialized for LAN verdict-
     * sharing: each video is billed to the AI once per rules version, by
     * whichever device sees it first — peers import the verdict instead.
     */
    fun exportJson(rulesVersion: Int): String = synchronized(LOCK) {
        val root = JSONObject()
        all().forEach { (id, e) -> if (e.rulesVersion == rulesVersion) root.put(id, entryJson(e)) }
        root.toString()
    }

    /**
     * Merges a peer's verdicts. Only entries under [rulesVersion] (this device's
     * current rules) count, and never over one we already hold for the same
     * rules — our own verdict is just as good, and not overwriting keeps a
     * pull-then-push exchange from ping-ponging entries between devices.
     * The one exception: a peer's deep (pre-play) verdict replaces our
     * title-only one — it judged the actual content, and taking it both saves
     * this device the play-time AI call and carries a TV's blacklist to the
     * phone. Deep-over-shallow is one-way, so it can't ping-pong either.
     * Returns how many were new.
     */
    /** Number of verdicts adopted; -1 when [json] isn't a verdict map at all. */
    fun importJson(json: String, rulesVersion: Int): Int {
        val incoming = runCatching { JSONObject(json) }.getOrNull() ?: return -1
        // The existing-check and the merge must sit under one lock acquisition:
        // the LAN server calls this from its own thread while the feed screener
        // is writing, and a gap between them re-creates the lost-update race.
        synchronized(LOCK) {
            val existing = all()
            val fresh = mutableMapOf<String, Entry>()
            incoming.keys().forEach { id ->
                val e = runCatching { parseEntry(incoming.getJSONObject(id)) }.getOrNull()
                    ?: return@forEach
                if (e.rulesVersion != rulesVersion) return@forEach
                val held = existing[id]?.takeIf { it.rulesVersion == rulesVersion }
                if (held == null || (e.deep && !held.deep)) fresh[id] = e
            }
            if (fresh.isNotEmpty()) putAll(fresh)
            return fresh.size
        }
    }

    /** Callers must hold [LOCK]. */
    private fun persist(map: Map<String, Entry>) {
        runCatching {
            val root = JSONObject()
            map.forEach { (id, e) -> root.put(id, entryJson(e)) }
            // Sibling temp file, then rename into place: a crash or power cut
            // mid-write must never truncate this file. all() swallows parse
            // failures, so a torn write wouldn't surface as an error — the whole
            // catalog would silently re-screen, re-billing the AI provider.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                // Android's rename replaces the target atomically; Windows (JVM
                // tests) refuses to, so fall back to delete-then-rename there.
                file.delete()
                tmp.renameTo(file)
            }
            // Our own write must not read as someone else's to the next all().
            cacheKey = signature()
        }
    }

    companion object {
        /**
         * The store is constructed in several places (feed screener, review
         * queue, stats, LAN import) over one file, so per-instance locking
         * (@Synchronized) never protected anything: two instances could
         * interleave read-modify-write and the last writer dropped the other's
         * paid verdicts. Same pattern as [DownloadStore] and [LocalLibrary].
         */
        private val LOCK = Any()

        /** Parsed map + source signature, shared across instances — see [all]. */
        private var cache: MutableMap<String, Entry>? = null
        private var cacheKey: Triple<String, Long, Long>? = null
    }
}
