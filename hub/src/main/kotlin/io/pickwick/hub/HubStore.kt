package io.pickwick.hub

import io.pickwick.app.data.ConfigJson
import io.pickwick.app.data.ConfigMerge
import io.pickwick.app.data.ConfigStamp
import io.pickwick.app.data.SyncMeta
import io.pickwick.app.data.Whitelist
import java.io.File

/**
 * The hub's copy of the family config.
 *
 * Deliberately the same shape as the app's `ConfigStore`, minus everything
 * Android: no Keystore, no SharedPreferences, no kid's pending restyle to
 * overlay. What it keeps is the part that matters for correctness — the atomic
 * write, one lock held across read-merge-write, and the merge itself, taken
 * from `:core` rather than reimplemented.
 *
 * The API key is **not** stored here. A hub is optional, so the key stays where
 * it already lives, on the devices; a config arriving with one has it stripped
 * before it touches this disk, exactly as the app does.
 */
class HubStore(
    internal val dataDir: File,
    /**
     * Run when this hub's copy actually moved, so devices can be told.
     *
     * Must not block: it is called with the write lock held, and the hub
     * serves on a fixed pool of four threads. [HubNudge] hands off to its
     * own worker and returns.
     */
    private val onChanged: () -> Unit = {}
) {

    private val file = File(dataDir, "config.json")

    /**
     * This box, in the change feed a parent reads on their phone. Short and
     * stable, like a device token prefix — "the hub" is a more useful answer
     * to "who changed this?" than eight characters of hex nobody can place.
     */
    private val BY = "hub"

    /**
     * One lock across read, merge and write.
     *
     * The server answers on a thread pool, so two devices can push at the same
     * instant. An unlocked read-modify-write would interleave and lose one
     * side — the exact bug the merge exists to fix, reintroduced a layer down
     * and much harder to see. The app's `mergeIncoming` holds its lock the same
     * way and for the same reason.
     */
    private val lock = Any()

    init {
        dataDir.mkdirs()
    }

    /** The stored config, or an empty one before anything has ever been written. */
    fun load(): Whitelist = synchronized(lock) {
        val text = raw() ?: return Whitelist(emptyList(), emptySet())
        runCatching { ConfigJson.fromJson(text) }.getOrElse {
            // A file that exists but will not parse must not read as "no
            // channels, no kids, no rules" — that emptiness would be merged
            // into every device that syncs next.
            System.err.println("config.json will not parse; refusing to serve it as empty")
            throw it
        }
    }

    /** The bytes on disk, exactly as they will be served. Null before the first write. */
    fun raw(): String? = synchronized(lock) {
        if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
    }

    fun fingerprint(): String = runCatching { ConfigJson.fingerprint(load()) }.getOrDefault("")

    fun syncHash(): String = runCatching { ConfigMerge.syncHash(load().sync) }
        .getOrDefault(ConfigMerge.syncHash(SyncMeta.EMPTY))

    fun updatedAt(): Long = runCatching {
        raw()?.let { org.json.JSONObject(it).optLong("updatedAt", 0L) } ?: 0L
    }.getOrDefault(0L)

    /** What one incoming push did — the same shape the app reports. */
    data class Outcome(
        val changed: Boolean,
        val peerBehind: Boolean,
        val hash: String,
        val syncHash: String
    )

    /**
     * Merge a pushed config in. Null when the body is not a readable config, so
     * the caller answers 400 and nothing here is touched.
     */
    fun merge(incoming: String, from: String?): Outcome? = synchronized(lock) {
        val result = ConfigMerge.merge(raw(), incoming)
        if (result.merged == null && !result.peerBehind) {
            // Either nothing new, or unreadable. Tell them apart the same way
            // the app does, so a malformed push is still a 400.
            if (runCatching { ConfigJson.fromJson(incoming) }.isFailure) return null
        }

        val beforeHash = fingerprintOf(raw())
        val beforeSync = syncHashOf(raw())
        result.merged?.let { commit(it) }
        val after = load()
        val afterHash = ConfigJson.fingerprint(after)
        val afterSync = ConfigMerge.syncHash(after.sync)

        // "Changed" means the settings or the sync history moved — NOT that the
        // bytes differ. `toJson` stamps `updatedAt` at serialization time, so
        // re-pushing an identical config produces different bytes every time;
        // reporting that as a change would have the hub announce news on every
        // sweep forever and log a merge that merged nothing.
        val changed = beforeHash != afterHash || beforeSync != afterSync
        if (changed) {
            println("merged a push from ${from ?: "a peer"} → #$afterHash")
        }
        outcome(
            changed = changed,
            peerBehind = result.peerBehind,
            hash = afterHash,
            syncHash = afterSync
        )
    }

    private fun fingerprintOf(text: String?): String =
        text?.let { runCatching { ConfigJson.fingerprint(ConfigJson.fromJson(it)) }.getOrNull() }.orEmpty()

    private fun syncHashOf(text: String?): String =
        text?.let { runCatching { ConfigMerge.syncHash(ConfigJson.fromJson(it).sync) }.getOrNull() }.orEmpty()

    /**
     * Every write goes through here, so there is one place that strips the API
     * key and one place that writes atomically. A second write path is how the
     * next one added forgets to do both.
     */
    private fun commit(json: String) {
        val safe = ConfigJson.stripSecrets(json)
        // Keep what is about to be displaced, if it is worth keeping. Here
        // rather than in the callers because this is the only write path and
        // the lock is already held — a second rotation site is how the next
        // one added forgets, the same reason the secret strip lives here.
        runCatching {
            HubVersions.rotate(
                this, raw(),
                ConfigJson.fingerprint(ConfigJson.fromJson(safe)),
                System.currentTimeMillis()
            )
        }
        val tmp = File(dataDir, "config.json.tmp")
        tmp.writeText(safe)
        if (!tmp.renameTo(file)) {
            // Some filesystems refuse a rename over an existing file.
            file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(safe)
                tmp.delete()
            }
        }
    }

    /**
     * Both write paths end here, so "the hub's copy moved" is decided once.
     *
     * Not in [commit]: `merge` commits on every sync because `toJson` stamps
     * `updatedAt` at serialization time, so a hook there would announce a
     * change on every poll from every device, forever. The fingerprint is
     * what actually moved or did not.
     */
    private fun outcome(changed: Boolean, peerBehind: Boolean, hash: String, syncHash: String): Outcome {
        if (changed) runCatching { onChanged() }
        return Outcome(changed = changed, peerBehind = peerBehind, hash = hash, syncHash = syncHash)
    }
    /**
     * An edit made on the hub itself, from the admin GUI.
     *
     * Stamped, like any other device's edit. An unstamped change would carry
     * no causality and lose to the first peer that synced, so a parent
     * changing something on the NAS would watch it silently revert — which
     * is worse than not offering the control at all.
     *
     * `previous` and `base` are the same document here, and that is correct
     * rather than a shortcut: unlike the phone, the hub holds no form open
     * across a co-parent's push. The read and the write happen inside one
     * lock, so there is no third version to diff against.
     */
    fun edit(
        who: String,
        now: Long,
        transform: (Whitelist) -> Whitelist
    ): Outcome = synchronized(lock) {
        val current = runCatching { load() }.getOrElse { Whitelist(emptyList(), emptySet()) }
        val beforeHash = ConfigJson.fingerprint(current)
        val beforeSync = ConfigMerge.syncHash(current.sync)

        val stamped = ConfigStamp.stamped(current, current, transform(current), now, who, BY)
        commit(ConfigJson.toJson(stamped.config))

        val after = load()
        val afterHash = ConfigJson.fingerprint(after)
        val afterSync = ConfigMerge.syncHash(after.sync)
        val changed = beforeHash != afterHash || beforeSync != afterSync
        if (changed) println("edited on the hub by $who → #$afterHash")
        outcome(changed, peerBehind = changed, hash = afterHash, syncHash = afterSync)
    }

    // There is deliberately no adopt()/replace() here.
    //
    // An unstamped whole-file write is the primitive a future session
    // implementing "restore" or "import" will reach for, and it is wrong for
    // both: a document written without stamps carries no causality, so the
    // first peer to sync discards it. Restores go through edit(), which
    // stamps. See HubVersions.
}
