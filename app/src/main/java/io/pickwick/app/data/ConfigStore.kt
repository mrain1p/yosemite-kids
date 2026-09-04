package io.pickwick.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Form-managed local configuration (channels, blocks, screen-time rules).
 * Serialized as JSON so it can also be pushed verbatim to paired devices.
 * Feeds the same Whitelist pipeline as the (advanced) URL-based lists.
 */
/** One lock for every ConfigStore instance — they all wrap the same file. */
private val FILE_LOCK = Any()

class ConfigStore internal constructor(
    private val file: File,
    /**
     * Null only in JVM unit tests. Everything reached through it is an Android
     * service — the Keystore ([secrets]), the per-kid namespace and the kid's
     * pending restyle — and each is guarded rather than faked, so a test
     * exercises the real serialize/merge/write paths and nothing else.
     */
    private val appContext: Context?
) {

    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "config.json"),
        context.applicationContext
    )

    /**
     * A store on a plain file, for tests. `save`, `saveRaw`, `load` and
     * `updatedAt` had no coverage at all before this existed: the only
     * constructor took a `Context` and there is no Robolectric here (the test
     * deps are junit and org.json). The merge work depends on being able to
     * drive two stores against two temp files from one JVM test.
     */
    internal constructor(file: File) : this(file, null)

    // Lazy on purpose: opening this is a Keystore round trip and load() sits on
    // the cold-start path, so a family that never turns on AI screening never
    // pays for it. See [withSecrets].
    private val secrets by lazy { appContext?.let { SecretStore(it) } }

    /**
     * Every config that touches disk registers its kids with the device-local
     * [ProfileNamespace] — the one place the first kid claims the legacy
     * (unsuffixed) stores, on every device, before any per-kid store is opened.
     */
    private fun registered(w: Whitelist): Whitelist {
        if (w.profiles.isNotEmpty()) {
            appContext?.let { ProfileNamespace(it).register(w.profiles.map { p -> p.id }) }
        }
        return w
    }

    /**
     * The last config that actually parsed. A file that exists but cannot be
     * read must not surface as "no channels, no kids, no rules": the next
     * [save] would write that emptiness over the real file, and the reconcile
     * would then push it to every device in the house. See [degraded].
     */
    @Volatile
    private var lastGood: Whitelist? = null

    /**
     * The file exists but did not parse, so [load] is serving the last good
     * copy — or an empty one, on a cold start where there is nothing better.
     * Callers that *invent* content (the kid migration, the master claim)
     * must refuse while this is set, or they mint into a config that is
     * missing everything and then persist it.
     */
    @Volatile
    var degraded: Boolean = false
        private set

    fun load(): Whitelist {
        val text = synchronized(FILE_LOCK) { if (file.exists()) file.readText() else null }
        val parsed = text?.let {
            runCatching { withSecrets(ConfigJson.fromJson(it)) }.getOrElse { e ->
                android.util.Log.e(
                    "Pickwick",
                    "config.json exists but does not parse — serving the last good copy",
                    e
                )
                null
            }
        }
        // Only a non-empty file that failed to parse is a degraded read. A
        // missing file is a fresh install, which is a legitimate empty config.
        degraded = parsed == null && !text.isNullOrBlank()
        if (parsed != null) lastGood = parsed
        val base = parsed ?: lastGood ?: Whitelist(emptyList(), emptySet())
        val out = registered(scrubLapsedPasses(base))
        return looks?.overlay(out) ?: out
    }

    /**
     * The same read, without the kid's un-adopted restyle laid over it.
     *
     * That overlay is a local rendering: it exists so a kid sees their new
     * avatar on this screen immediately, and the phone adopts it properly
     * through /looks. It is not part of the family document, and this file
     * already says so about the merge — mergeIncoming reads the raw bytes
     * because "merging from it would read a clock tick and a kid's private
     * choice as parent edits".
     *
     * The same reasoning applies on the way out and was not applied: a
     * config sent to a peer carrying the overlay puts a kid's un-adopted
     * choice into the family config, where the stamps are equal on both
     * sides and the merge breaks the tie on JSON string ordering.
     *
     * The API key is still included, because load() overlays it from
     * SecretStore and that is the only way a paired TV ever receives one —
     * the copy on disk never has it.
     */
    fun loadForPeers(): Whitelist {
        val text = synchronized(FILE_LOCK) { if (file.exists()) file.readText() else null }
        val parsed = text?.let {
            runCatching { withSecrets(ConfigJson.fromJson(it)) }.getOrNull()
        }
        val base = parsed ?: lastGood ?: Whitelist(emptyList(), emptySet())
        return registered(scrubLapsedPasses(base))
    }


    /**
     * A kid's own restyle, chosen on this device and not yet adopted by the
     * phone, laid over the file's profiles on every read — so it shows here
     * at once, and the file itself (the phone's copy) is never rewritten by
     * a kid. See [ProfileLooks].
     */
    private val looks by lazy { appContext?.let { ProfileLooks(it) } }

    /**
     * Every write goes through here: to a sibling temp file, then an atomic
     * rename over the real one. The LAN server's worker threads, the
     * ViewModel and the push scope all read this file while the settings
     * form saves it; a plain writeText truncates first and fills in after,
     * and a read in that gap parses as an *empty* whitelist — which on a TV
     * blanks the home screen, and on a phone is what the next reconcile
     * would happily push to every device. [FILE_LOCK] serializes the
     * process's own readers and writers; the rename covers everything else.
     */
    private fun writeAtomically(text: String) {
        synchronized(FILE_LOCK) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                // Some filesystems refuse to rename over an existing file.
                file.delete()
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                    tmp.delete()
                }
            }
        }
    }

    /**
     * A lapsed pass is spent and must fall out of the config, not linger.
     * Enforcement already ignores it, but the fingerprint and the legacy
     * bedtime keys (omitted while a pass is active — see [limitsToJson])
     * don't. Dropping it here changes the fingerprint exactly once, at the
     * first load after the lapse, and that mismatch is what re-pushes the
     * restored bedtime keys to a device still on a pre-windows build.
     */
    private fun scrubLapsedPasses(w: Whitelist): Whitelist {
        val now = System.currentTimeMillis()
        fun scrub(l: Limits): Limits {
            val windows =
                if (l.windows.none { (it.passUntilMillis ?: Long.MAX_VALUE) < now }) l.windows
                else l.windows.map {
                    if ((it.passUntilMillis ?: Long.MAX_VALUE) < now) it.copy(passUntilMillis = null) else it
                }
            val breakPass = l.breakPassUntilMillis?.takeIf { it >= now }
            return if (windows === l.windows && breakPass == l.breakPassUntilMillis) l
            else l.copy(windows = windows, breakPassUntilMillis = breakPass)
        }
        val limits = scrub(w.limits)
        val profiles = w.profiles.map { p -> p.copy(limits = scrub(p.limits)) }
        return if (limits === w.limits && profiles == w.profiles) w
        else w.copy(limits = limits, profiles = profiles)
    }

    /** Whether this config has AI screening set up at all. */
    private fun aiInUse(w: Whitelist) = w.ai.enabled || w.ai.model.isNotBlank()

    /**
     * The API key is kept out of `config.json` (see [SecretStore]); put it back
     * on the way out so every in-memory [Whitelist] is complete — screening,
     * the settings form and [fingerprint] all expect it to be there.
     */
    private fun withSecrets(w: Whitelist): Whitelist {
        if (w.ai.apiKey.isNotBlank()) {
            // Written by a build that still stored the key on disk. Move it and
            // rewrite the file without it, once — even when AI isn't set up,
            // because the key rides cloud backup for as long as it sits there.
            runCatching { commit(file.readText()) }
            return w
        }
        if (!aiInUse(w)) return w
        return w.copy(ai = w.ai.copy(apiKey = secrets?.aiApiKey().orEmpty()))
    }

    /**
     * Persist the key separately, but only when this config actually speaks to
     * the key — otherwise a save from a screen that never loaded it (AI off and
     * unconfigured) would quietly wipe a key the parent had set.
     */
    private fun rememberSecrets(w: Whitelist) {
        if (w.ai.apiKey.isNotBlank() || aiInUse(w)) secrets?.setAiApiKey(w.ai.apiKey)
    }

    /**
     * The one place config bytes reach disk.
     *
     * Every write must stash a key it carries and then strip it, so the file
     * never holds a credential — and there must be exactly one code path that
     * does so, or the next writer added anywhere forgets. `writeAtomically` is
     * called from here and nowhere else, and `scripts/check.ps1` fails the
     * build if that stops being true.
     *
     * Stripped surgically rather than re-serialized, so a field a newer phone
     * knows about and this build does not still survives the round trip.
     */
    private fun commit(json: String) {
        runCatching {
            val incoming = JSONObject(json).optJSONObject("ai")?.optString("apiKey").orEmpty()
            if (incoming.isNotBlank()) secrets?.setAiApiKey(incoming)
        }
        writeAtomically(ConfigJson.stripSecrets(json))
    }

    /**
     * Save a config the parent has edited, stamping what changed.
     *
     * [base] is what the editor opened with; without it the caller is saying
     * "whatever differs from disk is mine", which is right for the small
     * writers (the master claim, adopting a kid's restyle) and wrong for the
     * settings form. [who] and [by] name the device in the change log.
     *
     * Returns the bytes that were written, so a caller that also pushes sends
     * exactly what it saved — there is one wire shape for a config, not two.
     */
    /**
     * What was actually written. [config] is the stamped result, which is what
     * a form must adopt as its new baseline — it differs from what the form
     * handed in, because stamping carries forward any unit that arrived
     * underneath the open form. Adopting the form's own value instead would
     * make the next save read those carried units as fresh adds.
     *
     * [json] is the wire copy and carries the API key; the disk copy never
     * does. Push these bytes rather than re-serializing, or the push ships a
     * config without the stamps this save just minted.
     */
    data class Saved(val config: Whitelist, val json: String)

    fun save(
        whitelist: Whitelist,
        base: Whitelist? = null,
        who: String = "",
        by: String = ""
    ): Saved? = runCatching {
        synchronized(FILE_LOCK) {
            // Read under the same lock as the write. A co-parent's push can
            // land between an unlocked read and this write, and the stamper
            // needs the document as it actually is to tell "arrived underneath
            // me" from "the editor deleted it".
            // withSecrets so both sides of the comparison carry the key. Read
            // without it, an `ai` block identical except for the key would look
            // edited on every single save and log "changed screening" forever.
            // Deliberately not load(): that scrubs lapsed passes and lays a
            // kid's pending restyle over the profiles, so stamping against it
            // would attribute a clock tick to the parent as an edit.
            val previous = runCatching {
                if (file.exists()) withSecrets(ConfigJson.fromJson(file.readText())) else null
            }.getOrNull() ?: Whitelist(emptyList(), emptySet())
            val w = registered(whitelist)
            rememberSecrets(w)
            val stamped = ConfigStamp.stamped(
                previous = previous,
                base = base ?: previous,
                next = w,
                now = System.currentTimeMillis(),
                who = who,
                by = by
            )
            if (stamped.clockLooksWrong) {
                android.util.Log.w(
                    "Pickwick",
                    "a peer's config claims a date more than a week ahead — check the TV's clock"
                )
            }
            // The returned bytes are the *wire* copy and carry the key, because
            // a kid device needs it to screen. `commit` strips it on the way to
            // disk. One shape for a config, and only one place that strips.
            val json = ConfigJson.toJson(stamped.config)
            commit(json)
            Saved(stamped.config, json)
        }
    }.getOrNull()

    /**
     * Read-modify-write under the lock — the primitive every small writer
     * should use.
     *
     * The alternative, which this replaces, is to hand [save] a `Whitelist`
     * read minutes earlier. Under the stamper that is actively dangerous: a
     * channel merged in since that read looks like a fresh *add*, which clears
     * its tombstone and resurrects a channel a parent deleted, with no parent
     * action anywhere.
     */
    fun update(who: String = "", by: String = "", block: (Whitelist) -> Whitelist): Whitelist? =
        runCatching {
            synchronized(FILE_LOCK) {
                val previous = load()
                val next = block(previous)
                if (next == previous) return@synchronized previous
                val w = registered(next)
                rememberSecrets(w)
                val stamped = ConfigStamp.stamped(
                    previous = previous, base = previous, next = w,
                    now = System.currentTimeMillis(), who = who, by = by
                )
                commit(ConfigJson.toJson(stamped.config))
                stamped.config
            }
        }.getOrNull()

    /** What one incoming push did. Captured inside the lock, so it cannot describe someone else's write. */
    data class MergeOutcome(
        val before: Whitelist,
        val after: Whitelist,
        /** Something we hold is missing from the peer — worth pushing back. */
        val peerBehind: Boolean,
        val collisions: List<ConfigMerge.Collision>,
        val learned: List<ConfigMerge.Change>,
        /** False when the peer told us nothing new; nothing was written. */
        val changed: Boolean
    )

    /**
     * Take a pushed config by *merging* it, not by replacing what is here.
     *
     * This is the whole point of the sync work. `saveRaw` overwrote the file,
     * so two parents pushing to the same TV meant one of them lost everything
     * they had changed — silently, with nothing to look at afterwards.
     *
     * Read, merge and write happen under one lock. Two pushes arrive on two
     * different LAN worker threads (a pool of up to eight), and an unlocked
     * read-modify-write would interleave and lose one side, reintroducing
     * exactly the bug being fixed. The raw bytes are read rather than [load]:
     * `load` scrubs lapsed passes and lays a kid's un-adopted restyle over the
     * profiles, so merging from it would read a clock tick and a kid's private
     * choice as parent edits.
     */
    fun mergeIncoming(json: String, from: String? = null): MergeOutcome? {
        // Read before the lock: the Keystore is a slow round trip and holding
        // the config lock across it stalls every other reader.
        //
        // The merge needs this because the local side it is handed is the
        // disk copy, which never holds a key. Without it the local side looks
        // keyless, the incoming key wins unconditionally, and a peer holding
        // a stale key silently undoes a rotation.
        val heldKey = secrets?.aiApiKey().orEmpty()
        var carriedKey = ""
        val outcome = runCatching {
            synchronized(FILE_LOCK) {
                val localRaw = if (file.exists()) file.readText() else null
                val result = ConfigMerge.merge(localRaw, json, localApiKey = heldKey)
                carriedKey = result.apiKey
                // A payload we cannot read at all: the route answers 400 and
                // nothing here is touched, exactly as before.
                if (result.merged == null && !result.peerBehind && result.collisions.isEmpty()) {
                    if (runCatching { ConfigJson.fromJson(json) }.isFailure) return@synchronized null
                }
                val before = runCatching { localRaw?.let { ConfigJson.fromJson(it) } }.getOrNull()
                    ?: Whitelist(emptyList(), emptySet())
                val merged = result.merged
                val after = if (merged == null) before else {
                    val w = registered(ConfigJson.fromJson(merged))
                    // commit strips the key on the way to disk; the merge
                    // output never carried one to begin with.
                    commit(merged)
                    w
                }
                MergeOutcome(
                    before = before,
                    after = after,
                    peerBehind = result.peerBehind,
                    collisions = result.collisions,
                    learned = result.learned,
                    changed = merged != null
                )
            }
        }.getOrElse { e ->
            android.util.Log.w("Pickwick", "incoming config rejected", e)
            null
        } ?: return null

        // Outside the lock: the Keystore is a slow round trip and a LAN worker
        // holding the config lock across it would stall every other reader.
        if (carriedKey.isNotBlank()) secrets?.setAiApiKey(carriedKey)

        android.util.Log.i(
            "Pickwick",
            "config merged from ${from ?: "a peer"} → #${ConfigJson.fingerprint(outcome.after)} " +
                "changed=${outcome.changed} peerBehind=${outcome.peerBehind} " +
                "collisions=${outcome.collisions.size}"
        )
        return outcome
    }

    fun saveRaw(json: String): Boolean = runCatching {
        val w = registered(ConfigJson.fromJson(json)) // validate before accepting
        commit(json)
        // Symmetric with the reject log below: an accepted push names its hash
        // and the break rules it carried, so "the TV never got it" vs "it got
        // it but didn't enforce it" is answerable from logcat alone. Kids appear
        // by id rather than name — logcat is readable by anyone with the device
        // on a cable, and the id answers the same question.
        android.util.Log.i(
            "Pickwick",
            "config accepted #${ConfigJson.fingerprint(w)} break=${w.limits.breakMinutes} " +
                "perKid=${w.profiles.map { "${it.id}=${it.limits.breakMinutes}" }}"
        )
        true
    }.getOrElse { e ->
        // A rejected push is otherwise invisible on both ends — the phone shows
        // "out of sync" forever and nobody learns why.
        android.util.Log.w("Pickwick", "incoming config rejected", e)
        false
    }

    /**
     * What every peer receives — a push, and the answer to GET /config.
     *
     * Deliberately [loadForPeers] rather than [load]: a kid's un-adopted
     * restyle is a local rendering and must not travel.
     */
    fun rawJson(): String = ConfigJson.toJson(loadForPeers())

    /** When this device's config last changed (locally or via push). */
    fun updatedAt(): Long = runCatching {
        val text = synchronized(FILE_LOCK) { if (file.exists()) file.readText() else null }
        text?.let { JSONObject(it).optLong("updatedAt", 0L) } ?: 0L
    }.getOrDefault(0L)

    /**
     * The bookkeeping's fingerprint, for `/status`. Same cheap raw-peek shape
     * as [updatedAt] — no full parse, no secrets round trip, and it sits on a
     * LAN worker thread with a ten-second budget.
     *
     * Two fork peers count as in sync only when this *and* the config
     * fingerprint match. Without it, a TV holding a tombstone this phone has
     * never seen reads as "in sync ✓" and the deletion never travels, because
     * the reconcile short-circuits on hash equality and never fetches a body.
     */
    fun syncHash(): String = runCatching {
        val text = synchronized(FILE_LOCK) { if (file.exists()) file.readText() else null }
            ?: return@runCatching ConfigMerge.syncHash(SyncMeta.EMPTY)
        ConfigMerge.syncHash(ConfigMerge.syncFromJson(JSONObject(text)))
    }.getOrDefault(ConfigMerge.syncHash(SyncMeta.EMPTY))

}
