package io.yosemitekids.hub

import io.yosemitekids.app.data.BackupFile
import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.Whitelist
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The hub's local version history, and rolling back to one.
 *
 * **A rollback is not a file copy.** Restoring a snapshot's bytes loses three
 * separate arguments with the merge, silently, on the next sync:
 *
 * 1. The snapshot carries the OLD per-unit stamps, so every peer that has
 *    edited since wins its unit straight back. The parent watches the rollback
 *    undo itself and nothing reports it.
 * 2. A channel deleted after the snapshot cannot come back. Presence needs the
 *    side holding the winning stamp to also hold a tombstone below it — the
 *    proof that the add came after the delete — and a byte-restored document
 *    holds neither. The merge correctly reads it as a stale copy talking.
 * 3. Even with no tombstone anywhere, a namespace floor refuses a one-sided row
 *    whose stamp predates it.
 *
 * And a fourth, worse than all of them: the bytes carry the snapshot's own
 * `gone` map, so restoring would discard every tombstone the hub had learned
 * from peers. On a parental-controls app, "press Restore and every channel
 * anyone ever removed comes back" is the worst available outcome.
 *
 * So a restore is an ordinary **stamped edit** whose `next` is the old
 * document. `ConfigStamp.stamped` mints `maxOf(now, docAt + 1)`, which
 * outstamps every peer and clears every floor; re-adds keep their tombstone,
 * which is exactly the proof resurrection requires; things missing from the
 * snapshot get real tombstones so the rollback's deletions propagate; and the
 * bookkeeping comes from the live document rather than the snapshot. It is also
 * per-unit, so a co-parent's unrelated edit made since is not bulldozed.
 */
object HubVersions {

    /**
     * Five, not three.
     *
     * Removing a channel in the GUI is one commit per channel, so tidying three
     * channels is three fingerprint-changing writes — and a three-slot ring
     * would evict the state from before the tidy-up during the very mistake a
     * parent is trying to undo.
     */
    internal const val KEEP = 5

    /**
     * Do not open a new slot if the newest is younger than this.
     *
     * A burst of edits is one act of intent. Without the window the ring holds
     * five steps of a single session and nothing from before it.
     */
    internal const val COALESCE_MS = 10 * 60 * 1000L

    private const val DIR = "versions"
    private val NAME = Regex("^v-(\\d+)[.]json$")

    private fun dir(store: HubStore): File = File(store.dataDir, DIR).apply { mkdirs() }

    private fun files(store: HubStore): List<File> =
        dir(store).listFiles()?.filter { NAME.matches(it.name) }
            ?.sortedByDescending { takenAt(it) } ?: emptyList()

    private fun takenAt(f: File): Long = NAME.find(f.name)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    /**
     * Keep [current] as a version, if it is worth keeping.
     *
     * Called with the bytes about to be displaced, from inside the write lock.
     * Rotation is keyed on the fingerprint rather than the bytes: `toJson`
     * re-stamps `updatedAt` on every serialization, so byte-difference is
     * always true and would burn the ring on sync noise alone.
     */
    fun rotate(store: HubStore, current: String?, incomingFingerprint: String, now: Long) {
        if (current.isNullOrBlank()) return
        val existing = runCatching { ConfigJson.fingerprint(ConfigJson.fromJson(current)) }
            .getOrNull() ?: return
        if (existing == incomingFingerprint) return

        val newest = files(store).firstOrNull()
        if (newest != null && now - takenAt(newest) < COALESCE_MS) return

        runCatching {
            File(dir(store), "v-$now.json").writeText(current)
            files(store).drop(KEEP).forEach { it.delete() }
        }
    }

    /** What the version list shows: when, what changed, how big. */
    fun list(store: HubStore): JSONArray {
        val out = JSONArray()
        files(store).forEach { f ->
            val parsed = runCatching { ConfigJson.fromJson(f.readText()) }.getOrNull()
            out.put(
                JSONObject()
                    .put("id", f.name)
                    .put("takenAt", takenAt(f))
                    .put("hash", parsed?.let { ConfigJson.fingerprint(it) } ?: "")
                    .put("channels", parsed?.sources?.size ?: -1)
                    .put("kids", parsed?.profiles?.size ?: -1)
                    .put("blocked", parsed?.blockedVideoIds?.size ?: -1)
                    .put("readable", parsed != null)
            )
        }
        return out
    }

    /** The snapshot behind an id, or null when it is missing or unreadable. */
    fun read(store: HubStore, id: String): Whitelist? {
        if (!NAME.matches(id)) return null      // never a path, only a slot name
        val f = File(dir(store), id)
        if (!f.isFile) return null
        return runCatching { ConfigJson.fromJson(f.readText()) }.getOrNull()
    }

    /**
     * Roll back to a version.
     *
     * The snapshot supplies content only. Everything else — the stamps, the
     * tombstones, the floors — comes from the live document, because that is
     * what makes this a deliberate reversal rather than a stale copy talking.
     */
    fun restore(store: HubStore, who: String, now: Long, id: String): Boolean {
        val snapshot = read(store, id) ?: return false
        return apply(store, who, now, snapshot)
    }

    /**
     * Restore from a file a parent uploaded — a backup taken here, or one a
     * phone exported. Returns false when the text is not a backup at all, so
     * the caller can say so rather than reporting a successful wipe.
     *
     * The same [apply] as the version ring on purpose. A file is exactly as
     * stale as a snapshot and loses exactly the same four arguments with the
     * merge if it is written as bytes, so there is one restore primitive here
     * and it is a stamped edit. [BackupFile.configIn] is what refuses an
     * unrelated JSON file: an empty document parses as a perfectly valid
     * config meaning "no channels, no kids, no rules".
     */
    fun restoreFile(store: HubStore, who: String, now: Long, text: String): Boolean {
        val document = BackupFile.configIn(text) ?: return false
        // A phone's `GET /config` carries the API key, so a file made from one
        // can too. It would be stripped on the way to disk anyway; taking it
        // out here means it never enters the document this hub is holding.
        val snapshot = runCatching { ConfigJson.fromJson(ConfigJson.stripSecrets(document)) }
            .getOrNull() ?: return false
        return apply(store, who, now, snapshot)
    }

    /**
     * The one restore primitive: content from [snapshot], bookkeeping from
     * the live document. See the four arguments a byte copy loses, above.
     */
    private fun apply(store: HubStore, who: String, now: Long, snapshot: Whitelist): Boolean {
        store.edit(who, now) { current ->
            // Bookkeeping is replaced wholesale by ConfigStamp.stamped from
            // the live document, so nothing of the snapshot's own sync block
            // survives — not its stamps, not its tombstones, and not its
            // change feed.
            snapshot.copy(sync = current.sync)
        }
        return true
    }
}
