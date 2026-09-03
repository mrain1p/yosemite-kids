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
            runCatching { withSecrets(fromJson(it)) }.getOrElse { e ->
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
        writeAtomically(stripSecrets(json))
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
                if (file.exists()) withSecrets(fromJson(file.readText())) else null
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
            val json = toJson(stamped.config)
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
                commit(toJson(stamped.config))
                stamped.config
            }
        }.getOrNull()

    fun saveRaw(json: String): Boolean = runCatching {
        val w = registered(fromJson(json)) // validate before accepting
        commit(json)
        // Symmetric with the reject log below: an accepted push names its hash
        // and the break rules it carried, so "the TV never got it" vs "it got
        // it but didn't enforce it" is answerable from logcat alone. Kids appear
        // by id rather than name — logcat is readable by anyone with the device
        // on a cable, and the id answers the same question.
        android.util.Log.i(
            "Pickwick",
            "config accepted #${fingerprint(w)} break=${w.limits.breakMinutes} " +
                "perKid=${w.profiles.map { "${it.id}=${it.limits.breakMinutes}" }}"
        )
        true
    }.getOrElse { e ->
        // A rejected push is otherwise invisible on both ends — the phone shows
        // "out of sync" forever and nobody learns why.
        android.util.Log.w("Pickwick", "incoming config rejected", e)
        false
    }

    fun rawJson(): String = toJson(load())

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

    companion object {
        /** One lock for every ConfigStore instance — they all wrap the same file. */
        private val FILE_LOCK = Any()

        /**
         * Short content hash of what actually matters (channels, blocks, rules) —
         * two devices with equal fingerprints are provably in sync.
         */
        fun fingerprint(w: Whitelist): String {
            val canonical = buildString {
                w.sources.forEach {
                    append(it.id); append('|'); append(it.kind.name); append('|')
                    append(it.label ?: "")
                    // Appended only when non-default, so configs that never use
                    // multipliers keep the same hash as pre-multiplier builds.
                    if (it.timeMultiplierPercent != 100) {
                        append("|t"); append(it.timeMultiplierPercent)
                    }
                    // Same append-only rule: all-kids entries keep their old hash.
                    if (it.profileIds.isNotEmpty()) {
                        append("|v"); append(it.profileIds.sorted().joinToString(","))
                    }
                    // Note text is part of the fingerprint (an edit must push),
                    // newline-flattened so it can't fake an entry boundary.
                    if (!it.aiNote.isNullOrBlank()) {
                        append("|n"); append(it.aiNote.trim().replace('\n', ' '))
                    }
                    // Picked playlists, append-only like the rest: a pick must
                    // push (the rows are what the kid sees), an empty pick
                    // keeps the entry's old hash.
                    if (it.playlistIds.isNotEmpty()) {
                        append("|p"); append(it.playlistIds.joinToString(","))
                    }
                    append('\n')
                }
                append("B:"); append(w.blockedVideoIds.sorted().joinToString(","))
                append(";L:")
                append(limitsCanon(w.limits))
                // Appended only when set, so untouched configs keep their hash
                // across builds. Must be in the hash: the offline reconcile
                // (syncConfigState) only re-pushes on a fingerprint mismatch —
                // a pause that didn't change the hash would never reach a
                // device that slept through the original push.
                w.limits.pausedUntilMillis?.let { append(";P:"); append(it) }
                append(";AI:")
                append(
                    listOf(
                        w.ai.enabled, w.ai.baseUrl, w.ai.model, w.ai.apiKey,
                        w.ai.rules, w.ai.childAge, w.ai.rulesVersion
                    ).joinToString("|")
                )
                append(";AA:"); append(w.aiAllowedVideoIds.sorted().joinToString(","))
                // Everything profile-shaped is append-only-when-present, so a
                // family that never adds a second kid keeps its pre-profile hash.
                if (w.profiles.isNotEmpty()) {
                    append(";PR:")
                    w.profiles.forEach { p ->
                        append(
                            listOf(
                                p.id, p.name, p.colorArgb, p.avatar, p.age ?: -1,
                                p.pin ?: "",
                                limitsCanon(p.limits)
                            ).joinToString("|")
                        )
                        // A kid's own pause, append-only-when-set like the
                        // family one above: it has to move the hash or the
                        // offline reconcile would never carry it to the TV.
                        p.limits.pausedUntilMillis?.let { append(";P:"); append(it) }
                        // The look's timestamp rides the same way: a kid's
                        // restyle on the TV changes avatar/colour (already in
                        // the hash) *and* this, so both sides settle on one hash
                        // only once the phone has adopted the newer choice.
                        if (p.lookAt != 0L) { append(";LA:"); append(p.lookAt) }
                        append('\n')
                    }
                }
                fun appendOverlay(tag: String, overlay: Map<String, Set<String>>) {
                    if (overlay.isEmpty()) return
                    append(";$tag:")
                    overlay.entries.sortedBy { it.key }.forEach { (id, pids) ->
                        append(id); append('='); append(pids.sorted().joinToString(",")); append(';')
                    }
                }
                appendOverlay("BF", w.blockedFor)
                appendOverlay("AF", w.allowedFor)
                if (w.deviceProfiles.isNotEmpty()) {
                    append(";DP:")
                    w.deviceProfiles.entries.sortedBy { it.key }
                        .forEach { (t, p) -> append("$t=$p;") }
                }
                // Append-only-when-set, same reasoning as pauses: the offline
                // reconcile only re-pushes on a mismatch, so a master change
                // that didn't move the hash would never reach a sleeping TV.
                w.masterDeviceToken?.let { append(";MS:"); append(it) }
                // Appended only when switched off, so every existing config
                // keeps its hash across the build that introduced the flag.
                if (!w.sponsorSkip) append(";SB:off")
                if (!w.autoplayNext) append(";AP:off")
                if (!w.suggestSimilar) append(";SG:off")
                if (w.channelLayout != CHANNEL_LAYOUT_NEWEST) { append(";CL:"); append(w.channelLayout) }
                if (w.channelOrder != CHANNEL_ORDER_WATCHED) { append(";CO:"); append(w.channelOrder) }
                // Append-only-when-set, like every field added since: a
                // family that never touches these keeps its old hash.
                w.qualityTv?.let { append(";QT:"); append(it) }
                w.qualityPhone?.let { append(";QP:"); append(it) }
                w.pageSize?.let { append(";PS:"); append(it) }
                if (w.showVideoAge) append(";VA:1")
                // Append-only-when-set, same reasoning — and it must be in the
                // hash so the offline reconcile re-pushes a rate change.
                w.listenPercent?.let { append(";LN:"); append(it) }
            }
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray())
                .take(4)
                .joinToString("") { "%02x".format(it) }
        }

        /**
         * Drops the API key from a config payload, leaving every other field —
         * including ones this build doesn't know about — byte-for-byte alone.
         * Returns the input unchanged if it isn't parseable.
         */
        fun stripSecrets(json: String): String = runCatching {
            val root = JSONObject(json)
            val ai = root.optJSONObject("ai") ?: return@runCatching json
            if (!ai.has("apiKey")) return@runCatching json
            ai.remove("apiKey")
            root.toString(2)
        }.getOrDefault(json)

        /**
         * [includeSecrets] is true for anything that goes to another device (a
         * paired TV needs the key to screen), false for the copy that lands on
         * disk, which is cloud-backed-up.
         */
        fun toJson(w: Whitelist, includeSecrets: Boolean = true): String {
            val root = JSONObject()
            root.put("updatedAt", System.currentTimeMillis())
            root.put("entries", JSONArray().apply {
                w.sources.forEach { e ->
                    put(JSONObject().apply {
                        put("id", e.id)
                        put("url", e.url)
                        e.label?.let { put("label", it) }
                        put("kind", e.kind.name)
                        // Omitted at 100 — old builds ignore it, new builds default it.
                        if (e.timeMultiplierPercent != 100) put("time", e.timeMultiplierPercent)
                        // Omitted when visible to everyone, same reasoning.
                        if (e.profileIds.isNotEmpty()) put("profiles", JSONArray(e.profileIds.toList()))
                        if (!e.aiNote.isNullOrBlank()) put("note", e.aiNote.trim())
                        if (e.playlistIds.isNotEmpty()) put("playlists", JSONArray(e.playlistIds))
                    })
                }
            })
            root.put("blocked", JSONArray(w.blockedVideoIds.toList()))
            root.put("limits", limitsToJson(w.limits))
            root.put("ai", JSONObject().apply {
                put("enabled", w.ai.enabled)
                put("baseUrl", w.ai.baseUrl)
                put("model", w.ai.model)
                if (includeSecrets) put("apiKey", w.ai.apiKey)
                put("rules", w.ai.rules)
                w.ai.childAge?.let { put("childAge", it) }
                put("rulesVersion", w.ai.rulesVersion)
            })
            root.put("aiAllowed", JSONArray(w.aiAllowedVideoIds.toList()))
            // Profile fields are written only when used, so a single-kid family's
            // config stays byte-compatible with what older builds understand.
            if (w.profiles.isNotEmpty()) {
                root.put("profiles", JSONArray().apply {
                    w.profiles.forEach { p ->
                        put(JSONObject().apply {
                            put("id", p.id)
                            put("name", p.name)
                            put("color", p.colorArgb)
                            put("avatar", p.avatar)
                            p.age?.let { put("age", it) }
                            p.pin?.let { put("pin", it) }
                            if (p.lookAt != 0L) put("lookAt", p.lookAt)
                            put("limits", limitsToJson(p.limits))
                        })
                    }
                })
            }
            fun overlayJson(overlay: Map<String, Set<String>>) = JSONObject().apply {
                overlay.forEach { (id, pids) -> put(id, JSONArray(pids.toList())) }
            }
            if (w.blockedFor.isNotEmpty()) root.put("blockedFor", overlayJson(w.blockedFor))
            if (w.allowedFor.isNotEmpty()) root.put("allowedFor", overlayJson(w.allowedFor))
            if (w.deviceProfiles.isNotEmpty()) {
                root.put("deviceProfiles", JSONObject(w.deviceProfiles as Map<String, String>))
            }
            // Written only when chosen — absent means unclaimed (older builds).
            w.masterDeviceToken?.let { root.put("master", it) }
            // Written only when off — absent means on, including in configs
            // saved by builds that predate the flag.
            if (!w.sponsorSkip) root.put("sponsorSkip", false)
            if (!w.autoplayNext) root.put("autoplay", false)
            if (!w.suggestSimilar) root.put("suggest", false)
            if (w.channelLayout != CHANNEL_LAYOUT_NEWEST) root.put("channelLayout", w.channelLayout)
            if (w.channelOrder != CHANNEL_ORDER_WATCHED) root.put("channelOrder", w.channelOrder)
            w.qualityTv?.let { root.put("qualityTv", it) }
            w.qualityPhone?.let { root.put("qualityPhone", it) }
            w.pageSize?.let { root.put("pageSize", it) }
            if (w.showVideoAge) root.put("showVideoAge", true)
            // Written only when set — absent means listening off (see Whitelist).
            w.listenPercent?.let { root.put("listen", it) }
            // Last, and only when there is anything to say: a family that has
            // never edited since upgrading writes a byte-identical file, so no
            // fingerprint moves and no fleet-wide re-push fires at upgrade.
            if (!w.sync.isEmpty) root.put("sync", ConfigMerge.syncToJson(w.sync))
            return root.toString(2)
        }

        /**
         * Limits in the fingerprint's canonical form. The legacy trailing
         * `start,end` pair is kept for the one shape older builds could
         * express — a single every-day window — so a family whose bedtime was
         * migrated into the list keeps its existing hash and doesn't trigger a
         * pointless re-push. Richer schedules append instead, which is what
         * changes the hash for them exactly once, at the upgrade.
         *
         * Passes belong in here for the same reason pauses do: the offline
         * reconcile only re-pushes on a mismatch, so a pass that didn't move
         * the hash would never reach a device that slept through the push.
         */
        private fun limitsCanon(l: Limits): String {
            // "Allow listening" is outside what the legacy pair can say, so a
            // window carrying it takes the long form — otherwise ticking the
            // box on an ordinary every-day bedtime wouldn't move the hash and
            // would never reach the kid's phone.
            val legacy = l.windows.singleOrNull()
                ?.takeIf { it.days == ALL_DAYS && it.passUntilMillis == null && !it.allowListening }
            val base = listOf(
                l.sessionMinutes, l.weekdaySessions, l.weekendSessions, l.breakMinutes,
                legacy?.startMin, legacy?.endMin
            ).joinToString(",")
            // Appended only when set (same reason as pauses: the offline
            // reconcile only re-pushes on a mismatch, so a break skip that
            // didn't move the hash would never reach a sleeping device) — and
            // here rather than at the Whitelist level, so a per-kid skip moves
            // that kid's part of the hash too.
            val breakPass = l.breakPassUntilMillis?.let { ";BP:$it" } ?: ""
            // Same shape as the break pass: only present when set, so families
            // without the rule keep their hash across the build that added it.
            val minVideo = l.minVideoMinutes?.let { ";MV:$it" } ?: ""
            if (legacy != null || l.windows.isEmpty()) return base + breakPass + minVideo
            return base + l.windows.joinToString(";", prefix = ";W:") { w ->
                // Parent-typed text is scrubbed of this format's separators so
                // two different window lists can't canonicalize identically.
                "${w.id},${w.label.replace(Regex("[,;]"), " ")},${w.startMin},${w.endMin}," +
                    "${w.days.sorted().joinToString(".")},${w.passUntilMillis ?: 0}," +
                    if (w.allowListening) "1" else "0"
            } + breakPass + minVideo
        }

        private fun limitsToJson(l: Limits) = JSONObject().apply {
            l.sessionMinutes?.let { put("session", it) }
            l.weekdaySessions?.let { put("weekdaySessions", it) }
            l.weekendSessions?.let { put("weekendSessions", it) }
            l.breakMinutes?.let { put("breakMinutes", it) }
            l.minVideoMinutes?.let { put("minVideoMinutes", it) }
            if (l.windows.isNotEmpty()) put("windows", JSONArray(windowsToJson(l.windows)))
            // A single every-day window is exactly what pre-windows builds called
            // bedtime, so keep writing their keys for it: a family whose phone
            // updates before its TV keeps an enforced bedtime in the meantime.
            // Anything richer has no legacy equivalent and is left out rather
            // than flattened into a wrong one. "Allow listening" is the one
            // thing still written flat: a build that can't express it enforces
            // the window outright, which errs toward the stricter rule and is
            // what a TV does anyway. While a pass is active the keys
            // are omitted too — absent bedtime IS the pass, as far as an old
            // build can express it; they come back via the fingerprint change
            // when the lapsed pass is scrubbed on load.
            l.windows.singleOrNull()?.takeIf { it.days == ALL_DAYS && it.passUntilMillis == null }?.let {
                put("bedtimeStart", it.startMin)
                put("bedtimeEnd", it.endMin)
            }
            l.pausedUntilMillis?.let { put("pausedUntil", it) }
            l.breakPassUntilMillis?.let { put("breakPassUntil", it) }
        }

        /** Windows as a JSON array string — also the SharedPreferences form. */
        fun windowsToJson(windows: List<TimeWindow>): String =
            JSONArray().apply {
                windows.forEach { w ->
                    put(JSONObject().apply {
                        put("id", w.id)
                        put("label", w.label)
                        put("start", w.startMin)
                        put("end", w.endMin)
                        // Omitted when it applies every day, the common case.
                        if (w.days != ALL_DAYS) put("days", JSONArray(w.days.sorted()))
                        w.passUntilMillis?.let { put("passUntil", it) }
                        // Written only when set: absent means the plain block,
                        // which is what every pre-listening config meant.
                        if (w.allowListening) put("allowListening", true)
                    })
                }
            }.toString()

        fun windowsFromJson(text: String?): List<TimeWindow> = runCatching {
            val arr = JSONArray(text ?: return emptyList())
            (0 until arr.length()).mapNotNull { i ->
                // Per-window, so one malformed window (a newer build's shape, a
                // hand edit) drops alone instead of disabling every window this
                // list guards — bedtime included. Out-of-range minutes or days
                // would misfire in the week-minute math (negative modulo reads
                // as "always blocking"), so they drop too rather than enforce
                // something nobody configured.
                runCatching {
                    val w = arr.getJSONObject(i)
                    val start = w.getInt("start")
                    val end = w.getInt("end")
                    if (start !in 0..1439 || end !in 0..1439) return@runCatching null
                    val days = w.optJSONArray("days")?.let { d ->
                        (0 until d.length()).map { d.getInt(it) }
                            .filter { it in 1..7 } // Calendar.SUNDAY..SATURDAY
                            .toSet()
                    } ?: ALL_DAYS
                    // Present-but-empty days: block nothing, never inflate an
                    // ambiguous rule to every day.
                    if (days.isEmpty()) return@runCatching null
                    TimeWindow(
                        id = w.optString("id").ifEmpty { "w$i" },
                        label = w.optString("label", "Quiet time"),
                        startMin = start,
                        endMin = end,
                        days = days,
                        passUntilMillis = if (w.has("passUntil")) w.getLong("passUntil") else null,
                        allowListening = w.optBoolean("allowListening", false)
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())

        private fun limitsFromJson(lo: JSONObject): Limits {
            fun opt(name: String): Int? = if (lo.has(name)) lo.getInt(name) else null
            // A config written before windows existed carries only the bedtime
            // pair; migrate it in place so an upgrade never silently drops a
            // family's bedtime.
            val windows = if (lo.has("windows")) {
                windowsFromJson(lo.optJSONArray("windows")?.toString())
            } else {
                val start = opt("bedtimeStart")
                val end = opt("bedtimeEnd")
                if (start != null && end != null) {
                    listOf(TimeWindow(id = "bedtime", label = "Bedtime", startMin = start, endMin = end))
                } else emptyList()
            }
            return Limits(
                sessionMinutes = opt("session"),
                weekdaySessions = opt("weekdaySessions"),
                weekendSessions = opt("weekendSessions"),
                breakMinutes = opt("breakMinutes"),
                minVideoMinutes = opt("minVideoMinutes"),
                windows = windows,
                pausedUntilMillis = if (lo.has("pausedUntil")) lo.getLong("pausedUntil") else null,
                breakPassUntilMillis = if (lo.has("breakPassUntil")) lo.getLong("breakPassUntil") else null
            )
        }

        fun fromJson(text: String): Whitelist {
            val root = JSONObject(text)
            val entries = mutableListOf<WhitelistEntry>()
            val arr = root.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = runCatching { SourceKind.valueOf(o.getString("kind")) }
                    .getOrDefault(SourceKind.CHANNEL)
                val pidArr = o.optJSONArray("profiles")
                entries += WhitelistEntry(
                    id = o.getString("id"),
                    url = o.getString("url"),
                    label = o.optString("label").ifEmpty { null },
                    kind = kind,
                    timeMultiplierPercent = o.optInt("time", 100),
                    profileIds = pidArr?.let { arrPids ->
                        (0 until arrPids.length()).map { arrPids.getString(it) }.toSet()
                    } ?: emptySet(),
                    aiNote = o.optString("note").ifEmpty { null },
                    playlistIds = o.optJSONArray("playlists")?.let { pl ->
                        (0 until pl.length()).map { pl.getString(it) }.filter { it.isNotBlank() }
                    } ?: emptyList()
                )
            }
            val blocked = mutableSetOf<String>()
            val blockedArr = root.optJSONArray("blocked") ?: JSONArray()
            for (i in 0 until blockedArr.length()) blocked += blockedArr.getString(i)

            val lo = root.optJSONObject("limits") ?: JSONObject()

            val ao = root.optJSONObject("ai") ?: JSONObject()
            val ai = AiConfig(
                enabled = ao.optBoolean("enabled", false),
                baseUrl = ao.optString("baseUrl").ifEmpty { AiConfig().baseUrl },
                model = ao.optString("model"),
                apiKey = ao.optString("apiKey"),
                rules = ao.optString("rules"),
                childAge = if (ao.has("childAge")) ao.getInt("childAge") else null,
                rulesVersion = ao.optInt("rulesVersion", 0)
            )
            val aiAllowed = mutableSetOf<String>()
            val aiAllowedArr = root.optJSONArray("aiAllowed") ?: JSONArray()
            for (i in 0 until aiAllowedArr.length()) aiAllowed += aiAllowedArr.getString(i)

            val profiles = mutableListOf<Profile>()
            root.optJSONArray("profiles")?.let { arr2 ->
                for (i in 0 until arr2.length()) {
                    val o = arr2.getJSONObject(i)
                    profiles += Profile(
                        id = o.getString("id"),
                        name = o.optString("name").ifEmpty { "Kid ${i + 1}" },
                        colorArgb = o.optLong("color", PROFILE_COLORS.first()),
                        avatar = o.optString("avatar").ifEmpty { PROFILE_AVATARS.first() },
                        age = if (o.has("age")) o.getInt("age") else null,
                        limits = limitsFromJson(o.optJSONObject("limits") ?: JSONObject()),
                        pin = o.optString("pin").takeIf { isValidDirectionPin(it) },
                        lookAt = o.optLong("lookAt", 0L)
                    )
                }
            }
            fun overlay(name: String): Map<String, Set<String>> {
                val o = root.optJSONObject(name) ?: return emptyMap()
                return o.keys().asSequence().associateWith { id ->
                    val a = o.optJSONArray(id) ?: JSONArray()
                    (0 until a.length()).map { a.getString(it) }.toSet()
                }
            }
            val deviceProfiles = root.optJSONObject("deviceProfiles")?.let { o ->
                o.keys().asSequence().associateWith { o.getString(it) }
            } ?: emptyMap()

            return Whitelist(
                sources = entries.distinctBy { it.id },
                blockedVideoIds = blocked,
                limits = limitsFromJson(lo),
                ai = ai,
                aiAllowedVideoIds = aiAllowed,
                profiles = profiles,
                blockedFor = overlay("blockedFor"),
                allowedFor = overlay("allowedFor"),
                deviceProfiles = deviceProfiles,
                masterDeviceToken = root.optString("master").ifEmpty { null },
                sponsorSkip = root.optBoolean("sponsorSkip", true),
                autoplayNext = root.optBoolean("autoplay", true),
                suggestSimilar = root.optBoolean("suggest", true),
                channelLayout = root.optString("channelLayout").takeIf { it in CHANNEL_LAYOUTS }
                    ?: CHANNEL_LAYOUT_NEWEST,
                channelOrder = root.optString("channelOrder").takeIf { it in CHANNEL_ORDERS }
                    ?: CHANNEL_ORDER_WATCHED,
                listenPercent = if (root.has("listen")) root.getInt("listen") else null,
                qualityTv = root.optInt("qualityTv", 0).takeIf { it in PLAYBACK_QUALITIES },
                qualityPhone = root.optInt("qualityPhone", 0).takeIf { it in PLAYBACK_QUALITIES },
                pageSize = root.optInt("pageSize", 0).takeIf { it in PAGE_SIZES },
                showVideoAge = root.optBoolean("showVideoAge", false),
                // Outside the throwing path on purpose: a malformed or
                // future-versioned blob must cost the family its bookkeeping,
                // never its channels. See ConfigMerge.syncFromJson.
                sync = runCatching { ConfigMerge.syncFromJson(root) }.getOrDefault(SyncMeta.EMPTY)
            )
        }
    }
}
