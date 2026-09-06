package io.yosemitekids.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Describing, and later merging, one config against another.
 *
 * Everything here is pure — no `Context`, no disk, no clock — so the awkward
 * cases are JVM unit tests rather than something a parent discovers on a
 * Tuesday. See `docs/PLAN-sync.md` for the full design; this file currently
 * carries only [describe], the structural diff that answers "what am I about
 * to overwrite?" before a Pull.
 */
object ConfigMerge {

    /**
     * One difference, in the family's words.
     *
     * [code] is the stable machine name for the kind of change (`src.add`,
     * `kid.limits`, `settings`) and is what the change log and the collision
     * banner will key on later. [text] is the line a parent reads. Nothing
     * here ever carries a credential — see the AI branch of [describe].
     */
    data class Change(
        val code: String,
        val text: String,
        /** Stable identity, so two devices' logs union rather than duplicate. Empty for a diff line. */
        val id: String = "",
        /** The minted ordering stamp. Zero for a diff line, which was never logged. */
        val at: Long = 0L,
        /**
         * The minting device's raw wall clock, which is what the feed shows.
         * Separate from [at] because [at] is forced monotonic (see `stamped`):
         * a device with a wrong clock still has to be able to win an edit, but
         * a parent should never be shown the fiction that produces.
         */
        val shownAt: Long = 0L,
        /** First 8 hex of the minting device's pairing token — identity, for coalescing. */
        val by: String = "",
        /** The phone's name as the parent set it — identity, for reading. */
        val who: String = ""
    )

    /**
     * What changes if [b] replaces [a]: adds, removes and edits, in the order
     * a parent cares about — channels first, then kids and their rules, then
     * blocks, then the family-wide settings.
     *
     * Deliberately a *summary*, not a field-by-field dump. The screen it feeds
     * is a confirmation the parent reads in two seconds under the existing
     * "this replaces everything on that device" wording, so a hundred-line
     * diff would be read as noise and dismissed, which is the failure mode
     * this is meant to fix.
     */
    fun describe(a: Whitelist, b: Whitelist): List<Change> {
        val out = ArrayList<Change>()

        // --- Channels and playlists ------------------------------------
        val was = a.sources.associateBy { it.id }
        val now = b.sources.associateBy { it.id }
        (now.keys - was.keys).sorted().forEach {
            out += Change("src.add", "adds ${name(now.getValue(it))}")
        }
        (was.keys - now.keys).sorted().forEach {
            out += Change("src.remove", "removes ${name(was.getValue(it))}")
        }
        (was.keys intersect now.keys).sorted().forEach { id ->
            val o = was.getValue(id)
            val n = now.getValue(id)
            if (o.label != n.label) {
                out += Change("src.label", "renames ${name(o)} to ${name(n)}")
            }
            if (o.profileIds != n.profileIds) {
                out += Change("src.kids", "changes who can see ${name(n)}")
            }
            if (o.playlistIds != n.playlistIds) {
                out += Change("src.playlists", "changes the playlists shown for ${name(n)}")
            }
            if (o.aiNote != n.aiNote) {
                out += Change("src.note", "changes the screening note for ${name(n)}")
            }
            if (o.timeMultiplierPercent != n.timeMultiplierPercent) {
                out += Change(
                    "src.rate",
                    "changes how fast ${name(n)} uses screen time " +
                        "(${o.timeMultiplierPercent}% to ${n.timeMultiplierPercent}%)"
                )
            }
        }

        // --- Kids -------------------------------------------------------
        val kidsWas = a.profiles.associateBy { it.id }
        val kidsNow = b.profiles.associateBy { it.id }
        (kidsNow.keys - kidsWas.keys).sorted().forEach {
            out += Change("kid.add", "adds ${kidsNow.getValue(it).name}")
        }
        (kidsWas.keys - kidsNow.keys).sorted().forEach {
            // Named as strongly as it deserves: removing a kid takes their
            // rules, grants and per-kid stores with them.
            out += Change("kid.remove", "removes ${kidsWas.getValue(it).name} and their settings")
        }
        (kidsWas.keys intersect kidsNow.keys).sorted().forEach { id ->
            val o = kidsWas.getValue(id)
            val n = kidsNow.getValue(id)
            if (o.name != n.name) out += Change("kid.name", "renames ${o.name} to ${n.name}")
            if (o.age != n.age) out += Change("kid.age", "changes ${n.name}'s age")
            if (o.avatar != n.avatar || o.colorArgb != n.colorArgb) {
                out += Change("kid.look", "changes ${n.name}'s picture or colour")
            }
            // The value never appears, only that it moved.
            if (o.pin != n.pin) {
                out += Change(
                    "kid.pin",
                    if (n.pin.isNullOrBlank()) "removes ${n.name}'s code"
                    else "changes ${n.name}'s code"
                )
            }
            describeLimits(o.limits, n.limits, n.name).forEach { out += it }
        }

        // --- Extra minutes ---------------------------------------------
        // Dated, because this is a "what am I about to overwrite" summary
        // and a grant from last Tuesday is not the same news as one from
        // today.
        val grantsWas = a.grants.associateBy { it.id }
        val grantsNow = b.grants.associateBy { it.id }
        (grantsNow.keys - grantsWas.keys).sorted().forEach {
            val g = grantsNow.getValue(it)
            out += Change("grant", "adds ${g.minutes} extra minutes for ${Grants.whose(g, b.profiles)} on ${g.date}")
        }
        (grantsWas.keys - grantsNow.keys).sorted().forEach {
            val g = grantsWas.getValue(it)
            out += Change("grant", "removes ${g.minutes} extra minutes for ${Grants.whose(g, a.profiles)} on ${g.date}")
        }

        // --- Blocks and allow lists ------------------------------------
        countChange(
            a.blockedVideoIds.size, b.blockedVideoIds.size, "blocked video", "blocked videos"
        )?.let { out += Change("blk", it) }
        countChange(
            a.aiAllowedVideoIds.size, b.aiAllowedVideoIds.size,
            "video allowed past screening", "videos allowed past screening"
        )?.let { out += Change("allow", it) }
        if (a.blockedFor != b.blockedFor) {
            out += Change("for", "changes which videos are blocked for individual kids")
        }
        if (a.allowedFor != b.allowedFor) {
            out += Change("afor", "changes which videos are allowed for individual kids")
        }

        // --- Family-wide rules and settings ----------------------------
        describeLimits(a.limits, b.limits, null).forEach { out += it }

        // The API key and the endpoint are never rendered. The key is a
        // credential; the endpoint is close enough to one that a screenshot of
        // this dialog should not carry it.
        if (a.ai.enabled != b.ai.enabled) {
            out += Change("ai", if (b.ai.enabled) "turns AI screening on" else "turns AI screening off")
        }
        if (a.ai.model != b.ai.model) out += Change("ai", "changes the screening model")
        if (a.ai.rules != b.ai.rules) out += Change("ai", "changes the screening rules")
        if (a.ai.childAge != b.ai.childAge) out += Change("ai", "changes the age used for screening")

        settingsChanges(a, b).forEach { out += it }

        if (a.masterDeviceToken != b.masterDeviceToken) {
            out += Change("master", "changes which device builds the search index")
        }

        return out
    }

    /** True when [b] would change nothing on a device holding [a]. */
    fun identical(a: Whitelist, b: Whitelist): Boolean = describe(a, b).isEmpty()

    // --- The sync blob: hashing and serialization -----------------------

    /**
     * A fingerprint of the *bookkeeping* — stamps, tombstones and floors — so
     * two devices can tell "we hold the same config" from "we hold the same
     * config but one of us knows about a deletion the other doesn't".
     *
     * Built from a canonically sorted string and never from
     * `JSONObject.toString()`. Android's `JSONObject` is `LinkedHashMap`-backed
     * and therefore insertion-ordered, so two devices holding identical maps
     * assembled in different orders would hash differently and push at each
     * other forever. The JVM tests' `org.json` uses a plain `HashMap`, so no
     * test in this repo would ever catch that.
     *
     * The log and [SyncMeta.docAt] are excluded on purpose: a log line is not
     * state, and two devices holding the same rules with different log tails
     * must read as in sync.
     */
    fun syncHash(sync: SyncMeta): String {
        val canonical = buildString {
            fun section(tag: Char, m: Map<String, Long>) {
                m.entries.sortedBy { it.key }.forEach {
                    append(tag); append(':'); append(it.key)
                    append('='); append(it.value); append('\n')
                }
            }
            section('a', sync.at)
            section('g', sync.gone)
            section('f', sync.floor)
        }
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .take(4)
            .joinToString("") { "%02x".format(it) }
    }

    fun syncToJson(sync: SyncMeta): org.json.JSONObject {
        val o = org.json.JSONObject()
        o.put("v", sync.v)
        if (sync.docAt > 0) o.put("docAt", sync.docAt)
        fun map(name: String, m: Map<String, Long>) {
            if (m.isEmpty()) return
            val j = org.json.JSONObject()
            // Sorted so the file is diffable and two devices holding the same
            // map write the same bytes. The hash does not depend on it, but a
            // parent reading config.json over adb should not see it shuffle.
            m.entries.sortedBy { it.key }.forEach { j.put(it.key, it.value) }
            o.put(name, j)
        }
        map("at", sync.at)
        map("gone", sync.gone)
        map("floor", sync.floor)
        if (sync.log.isNotEmpty()) {
            val arr = org.json.JSONArray()
            sync.log.forEach { c ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", c.id).put("at", c.at).put("shownAt", c.shownAt)
                        .put("by", c.by).put("who", c.who)
                        .put("code", c.code).put("text", c.text)
                )
            }
            o.put("log", arr)
        }
        return o
    }

    /**
     * Reads the blob, refusing to let a bad one cost the config.
     *
     * A version this build does not know reads as **no sync block at all**
     * rather than as an error: the rest of the document is a perfectly good
     * config, and a newer peer's bookkeeping is exactly the thing this build
     * is not equipped to reason about. Same for a malformed entry — the
     * channels still load.
     */
    fun syncFromJson(root: org.json.JSONObject): SyncMeta {
        val o = root.optJSONObject("sync") ?: return SyncMeta.EMPTY
        val v = o.optInt("v", 0)
        if (v != SyncMeta.VERSION) return SyncMeta.EMPTY
        fun map(name: String): Map<String, Long> {
            val j = o.optJSONObject(name) ?: return emptyMap()
            val out = LinkedHashMap<String, Long>()
            j.keys().forEach { k -> j.optLong(k, 0L).takeIf { it != 0L }?.let { out[k] = it } }
            return out
        }
        val log = ArrayList<Change>()
        o.optJSONArray("log")?.let { arr ->
            for (i in 0 until arr.length()) {
                runCatching {
                    val e = arr.getJSONObject(i)
                    log += Change(
                        code = e.optString("code"),
                        text = e.optString("text"),
                        id = e.optString("id"),
                        at = e.optLong("at", 0L),
                        shownAt = e.optLong("shownAt", 0L),
                        by = e.optString("by"),
                        who = e.optString("who")
                    )
                }
            }
        }
        return SyncMeta(
            v = v,
            docAt = o.optLong("docAt", 0L),
            at = map("at"),
            gone = map("gone"),
            floor = map("floor"),
            log = log
        )
    }

    private fun name(e: WhitelistEntry): String = e.label?.takeIf { it.isNotBlank() } ?: e.id

    /**
     * Screen-time differences for one kid, or for the family when [who] is
     * null. Phrased as "45 to 30 min" rather than as field names: this is the
     * change a parent most needs to recognise at a glance, and it is the one
     * the collision banner will later have to offer to put back.
     */
    private fun describeLimits(o: Limits, n: Limits, who: String?): List<Change> {
        val out = ArrayList<Change>()
        val whose = if (who == null) "everyone's" else "$who's"
        val code = if (who == null) "lim" else "kid.limits"

        if (o.sessionMinutes != n.sessionMinutes) {
            out += Change(code, "changes $whose sitting length ${mins(o.sessionMinutes)} to ${mins(n.sessionMinutes)}")
        }
        if (o.weekdaySessions != n.weekdaySessions) {
            out += Change(code, "changes $whose weekday sittings ${count(o.weekdaySessions)} to ${count(n.weekdaySessions)}")
        }
        if (o.weekendSessions != n.weekendSessions) {
            out += Change(code, "changes $whose weekend sittings ${count(o.weekendSessions)} to ${count(n.weekendSessions)}")
        }
        if (o.breakMinutes != n.breakMinutes) {
            out += Change(code, "changes $whose break ${mins(o.breakMinutes)} to ${mins(n.breakMinutes)}")
        }
        if (o.minVideoMinutes != n.minVideoMinutes) {
            out += Change(code, "changes the shortest video $whose list will show")
        }
        if (o.windows != n.windows) {
            out += Change(
                if (who == null) "lim.windows" else "kid.windows",
                "changes $whose allowed hours"
            )
        }
        if (o.pausedUntilMillis != n.pausedUntilMillis) {
            out += Change(
                if (who == null) "lim.pause" else "kid.pause",
                if (n.pausedUntilMillis == null) "lifts $whose pause" else "pauses $whose watching"
            )
        }
        if (o.breakPassUntilMillis != n.breakPassUntilMillis) {
            out += Change(
                if (who == null) "lim.brk" else "kid.brk",
                if (n.breakPassUntilMillis == null) "ends $whose break early"
                else "skips $whose break"
            )
        }
        return out
    }

    /** The loose family-wide scalars, named individually so the line is useful. */
    private fun settingsChanges(a: Whitelist, b: Whitelist): List<Change> {
        val moved = ArrayList<String>()
        if (a.sponsorSkip != b.sponsorSkip) moved += "sponsor skipping"
        if (a.autoplayNext != b.autoplayNext) moved += "autoplay"
        if (a.suggestSimilar != b.suggestSimilar) moved += "suggestions"
        if (a.channelLayout != b.channelLayout) moved += "channel page layout"
        if (a.channelOrder != b.channelOrder) moved += "channel order"
        if (a.listenPercent != b.listenPercent) moved += "listening"
        if (a.qualityTv != b.qualityTv) moved += "TV picture quality"
        if (a.qualityPhone != b.qualityPhone) moved += "phone picture quality"
        if (a.pageSize != b.pageSize) moved += "how many videos a page shows"
        if (a.showVideoAge != b.showVideoAge) moved += "showing when a video came out"
        if (a.deviceProfiles != b.deviceProfiles) moved += "which kid each device is for"
        return if (moved.isEmpty()) emptyList()
        else listOf(Change("settings", "changes " + humanList(moved)))
    }

    private fun countChange(was: Int, now: Int, one: String, many: String): String? = when {
        now > was -> "adds ${plural(now - was, one, many)}"
        now < was -> "removes ${plural(was - now, one, many)}"
        else -> null
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

    private fun mins(v: Int?): String = v?.let { "$it min" } ?: "no limit"

    private fun count(v: Int?): String = v?.toString() ?: "no limit"

    /** "a, b and c" — the app writes for parents, not for a log file. */
    private fun humanList(items: List<String>): String = when (items.size) {
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }

    // --- The merge ------------------------------------------------------
    //
    // Two configs in, one out. Unit by unit, so two parents who changed two
    // different things both keep their change — which whole-file
    // last-writer-wins cannot do, and which is the entire reason any of this
    // exists. See `docs/PLAN-sync.md` for the rules and why each is shaped
    // the way it is.
    //
    // Deliberately at the JSON level and never through `Whitelist`. `saveRaw`
    // guarantees that a field a newer phone knows about survives a round trip
    // through an older one, and that guarantee holds only while nothing
    // re-serializes from the model. A model-level merge would be the first
    // thing to break it, and would additionally lose unknown fields *nested
    // inside* known objects, which no top-level passthrough can rescue.

    /** What a merge produced. [merged] is null when the local copy already had it all. */
    data class Result(
        /** The bytes to write, or null when nothing about the local copy changed. */
        val merged: String?,
        /** The peer is missing something we hold, so it is worth pushing back to. */
        val peerBehind: Boolean,
        /** Units where both sides had edited and the local side lost. */
        val collisions: List<Collision>,
        /** Log lines the local side had not seen — a co-parent's activity. */
        val learned: List<Change>,
        /**
         * The API key, out of band. [merged] can never contain one: it is
         * stripped from both inputs before anything is compared, so no merge
         * result and no collision record can carry a credential.
         */
        val apiKey: String
    )

    /** Both sides had edited one unit, and the local value lost. */
    data class Collision(
        val unit: String,
        val code: String,
        /** What this device held, as compact JSON — enough to offer "put it back". */
        val mine: String,
        val mineAt: Long,
        val theirs: String,
        val theirsAt: Long
    )

    /** How a namespace behaves when the evidence is ambiguous. */
    private enum class Safe { ABSENT, PRESENT, SCALAR }

    /**
     * Which way a unit falls when nobody can prove the later act.
     *
     * Not uniform, and the asymmetry is the point. For a block, the safe
     * answer is that it *stays*: presence wins ties and never needs proof,
     * while lifting a block does. So a parent unblocking ten minutes after a
     * co-parent blocked still works — their copy carries the block, which is
     * the proof — but a re-block from a phone that never saw the unblock is
     * honoured rather than silently discarded. For a channel or a kid the
     * polarity inverts: absence is the safe answer, so asserting *presence*
     * against a tombstone is what needs proof, and a channel a parent removed
     * cannot come back because a stale phone still lists it.
     */
    private fun safeState(ns: String): Safe = when (ns) {
        "blk", "for" -> Safe.PRESENT
        // A grant fails absent like a channel: the tombstone the stamper
        // mints when its day has passed must beat a stale copy still listing
        // it, or expiry would never settle.
        "src", "kid", "kid.pin", "allow", "afor", "dev", "grant" -> Safe.ABSENT
        else -> Safe.SCALAR
    }

    /**
     * Whether a namespace fails closed — presence wins ties, and lifting is
     * the act that needs proof.
     *
     * Exposed because ConfigStamp has to remove a unit differently
     * depending on the answer, and a second copy of this table in the
     * stamper would be a table that drifts. One source, two readers.
     */
    internal fun failsClosed(ns: String): Boolean = safeState(ns) == Safe.PRESENT

    private data class Side(val root: JSONObject, val sync: SyncMeta, val legacy: Boolean)

    /**
     * Merge [incoming] into [local].
     *
     * Takes **no clock**, and the signature is the enforcement: idempotence
     * and associativity are then structural rather than properties that hold
     * only while a test freezes time, and a device whose RTC came back wrong
     * cannot drop a parent's live pause into the shared document just by
     * taking part in a sync.
     */
    /**
     * [localApiKey] is the key this device actually holds, supplied out of
     * band because on Android it is never in [local]: the caller passes the
     * disk copy, and the key is stripped before anything reaches disk.
     *
     * Without it the local side always looked keyless, so the incoming key
     * won unconditionally — and a rotation could be undone by any peer that
     * still held the old one, silently, reporting "in sync" afterwards.
     */
    fun merge(local: String?, incoming: String, localApiKey: String = ""): Result {
        // Refuse anything that is not a config, exactly as the route did
        // before: the caller answers 400 and the contract is unchanged.
        val inRoot = runCatching { JSONObject(incoming) }.getOrNull()
            ?: return Result(null, false, emptyList(), emptyList(), "")
        if (runCatching { ConfigJson.fromJson(incoming) }.isFailure) {
            return Result(null, false, emptyList(), emptyList(), "")
        }
        val incomingKey = inRoot.optJSONObject("ai")?.optString("apiKey").orEmpty()

        // Fresh install, or a file we cannot read. Adopting a valid peer
        // document beats propagating our own unreadable emptiness — `load()`
        // turns that into an empty Whitelist, which the next save writes over
        // the real file and the next sweep pushes to the whole house.
        val locRoot = local?.takeIf { it.isNotBlank() }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        if (locRoot == null || runCatching { ConfigJson.fromJson(local!!) }.isFailure) {
            return Result(
                merged = inRoot.toString(2),
                peerBehind = false,
                collisions = emptyList(),
                learned = ConfigMerge.syncFromJson(inRoot).log,
                // We are adopting their document wholesale, but a key they
                // never carried is not theirs to erase.
                apiKey = incomingKey.ifBlank { localApiKey }
            )
        }
        // Whatever the local document happens to say, plus what the caller
        // actually holds. On Android the former is always empty.
        val localKey = locRoot.optJSONObject("ai")?.optString("apiKey")
            .orEmpty().ifBlank { localApiKey }

        // The key never reaches the merge, so it can never reach the output.
        stripKey(locRoot)
        stripKey(inRoot)

        val L = normalise(locRoot)
        val R = normalise(inRoot)

        val out = JSONObject(locRoot.toString())   // rebuild from local: unknown roots survive
        val at = LinkedHashMap<String, Long>()
        val gone = LinkedHashMap<String, Long>()
        // Every unit a loop asked about. The tombstone carry below is for the
        // units nobody asked about; a decided unit already had its say.
        val decided = HashSet<String>()
        val floor = LinkedHashMap<String, Long>()
        (L.sync.floor.keys + R.sync.floor.keys).forEach { ns ->
            floor[ns] = maxOf(L.sync.floor[ns] ?: 0L, R.sync.floor[ns] ?: 0L)
        }
        val collisions = ArrayList<Collision>()

        /**
         * Resolve one unit: is it present, and whose value wins?
         *
         * Every quantity is a `max` over both sides or an existential over
         * them, so the rule is symmetric and therefore commutative by
         * construction rather than by testing.
         */
        fun decide(key: String): Decision {
            decided += key
            val la = L.sync.at[key] ?: 0L
            val ra = R.sync.at[key] ?: 0L
            val lg = L.sync.gone[key] ?: 0L
            val rg = R.sync.gone[key] ?: 0L
            val a = maxOf(la, ra)
            val g = maxOf(lg, rg)
            val ns = ConfigStamp.namespace(key)

            // An evicted tombstone still refuses its subject — but only
            // against a stamp that is real evidence. A synthesised rank
            // (see [normalise]) means "no evidence either way", which a floor
            // is not entitled to read as a delete: without the exemption a
            // restored backup, or a never-edited upgraded device, would lose
            // its whole content on first contact with any peer that has ever
            // dropped a tombstone.
            val below = a > SYNTHETIC_AT_MAX && a < (floor[ns] ?: 0L) && (la == 0L || ra == 0L)

            // The tombstone the merged blob will carry. A lift the fail-closed
            // rule rejects must not leave its tombstone behind: the merged copy
            // would then hold the block's stamp beside the peer's tombstone,
            // which is exactly what a *deliberate* lift looks like, and the
            // next merge of the same peer would read its own bookkeeping as
            // proof and lift after all — the block came and went on alternate
            // pushes. A rejected lift is forgotten; the peer, told it is
            // behind, adopts the block on its next pull.
            var carriedGone = g
            val present = when {
                below -> false
                g == 0L -> a > 0
                a == 0L -> false
                else -> {
                    // Did the side asserting the later act actually *see* the
                    // earlier one? That is what makes it a deliberate reversal
                    // rather than a stale copy talking.
                    val sawTombstone = (la == a && lg in 1 until a) || (ra == a && rg in 1 until a)
                    val sawAdd = (lg == g && la in 1 until g) || (rg == g && ra in 1 until g)
                    when (safeState(ns)) {
                        Safe.ABSENT -> a > g && sawTombstone
                        Safe.PRESENT -> {
                            val lifted = g > a && sawAdd
                            if (g > a && !lifted) carriedGone = 0L
                            !lifted
                        }
                        Safe.SCALAR -> true
                    }
                }
            }
            val fromLocal = when {
                la > ra -> true
                ra > la -> false
                else -> null   // a tie: the caller breaks it on the value
            }
            return Decision(present, a, carriedGone, fromLocal, la, ra)
        }

        /**
         * Record the local side losing a unit both sides had edited — the
         * banner's raw material. Filtered on *both* sides having a real stamp,
         * so adopting something the local side never touched is not a
         * collision, it is just news.
         */
        fun collide(key: String, code: String, d: Decision, mine: String?, theirs: String?) {
            if (d.localAt <= 0 || d.remoteAt <= 0) return
            if (d.localAt >= d.remoteAt) return
            if (mine == theirs) return
            collisions += Collision(key, code, mine.orEmpty(), d.localAt, theirs.orEmpty(), d.remoteAt)
        }

        // --- entries -----------------------------------------------------
        run {
            val lm = byId(L.root, "entries")
            val rm = byId(R.root, "entries")
            val kept = ArrayList<Pair<Long, JSONObject>>()
            (lm.keys + rm.keys).forEach { id ->
                val key = ConfigStamp.src(id)
                val d = decide(key)
                if (!d.present) { if (d.gone > 0) gone[key] = d.gone; return@forEach }
                val mine = lm[id]
                val theirs = rm[id]
                val pick = pickValue(d, mine, theirs) ?: return@forEach
                collide(key, "src", d, mine?.toString(), theirs?.toString())
                at[key] = d.at
                if (d.gone > 0) gone[key] = d.gone
                kept += d.at to pick
            }
            // Canonical order, identical on every device. Entry order feeds
            // fingerprint's canonical string, so a device-dependent order
            // ("local first, then theirs") would have two phones hash the same
            // content differently and read "differs" forever after one
            // concurrent add.
            putLike(
                out, locRoot, "entries",
                JSONArray().also { arr ->
                    kept.sortedWith(compareBy({ it.first }, { it.second.optString("id") }))
                        .forEach { arr.put(it.second) }
                }
            )
        }

        // --- profiles ----------------------------------------------------
        run {
            val lm = byId(L.root, "profiles")
            val rm = byId(R.root, "profiles")
            val kept = ArrayList<Pair<Long, JSONObject>>()
            (lm.keys + rm.keys).forEach { id ->
                val key = ConfigStamp.kid(id)
                val d = decide(key)
                if (!d.present) {
                    listOf(
                        key, ConfigStamp.kidPin(id), ConfigStamp.kidRules(id),
                        ConfigStamp.kidWindows(id), ConfigStamp.kidPause(id), ConfigStamp.kidBrk(id)
                    ).forEach { k ->
                        val dk = decide(k)
                        if (dk.gone > 0) gone[k] = dk.gone
                    }
                    return@forEach
                }
                val mine = lm[id]
                val theirs = rm[id]
                val base = pickValue(d, mine, theirs) ?: return@forEach
                collide(key, "kid", d, mine?.toString(), theirs?.toString())
                at[key] = d.at
                if (d.gone > 0) gone[key] = d.gone

                // Assembled, not adopted whole. The PIN is its own unit
                // because it is a credential riding inside an object: without
                // that, a co-parent fixing the spelling of a kid's name on a
                // stale copy silently removes the code set an hour earlier,
                // and the picker then lets a sibling into that kid's profile.
                val kid = JSONObject(base.toString())
                fieldUnit(ConfigStamp.kidPin(id), ::decide, at, gone, mine, theirs, kid, "pin")
                mergeKidLimits(id, ::decide, at, gone, mine, theirs, kid)
                kept += d.at to kid
            }
            putLike(
                out, locRoot, "profiles",
                JSONArray().also { arr ->
                    kept.sortedWith(compareBy({ it.first }, { it.second.optString("id") }))
                        .forEach { arr.put(it.second) }
                }
            )
        }

        // --- extra minutes -----------------------------------------------
        // One unit per tap, keyed by id like a channel, so two phones that
        // granted on the same day both survive and the kid gets the sum. A
        // grant is immutable once minted, so there is no value to collide on.
        // Nothing here reads a clock: a grant for a day that has passed
        // merges like any other and is ignored by the guard until a phone's
        // next save tombstones it — and that tombstone wins here, because
        // the namespace fails absent.
        run {
            val lm = byId(L.root, "grants")
            val rm = byId(R.root, "grants")
            val kept = ArrayList<Pair<Long, JSONObject>>()
            (lm.keys + rm.keys).forEach { id ->
                val key = ConfigStamp.grant(id)
                val d = decide(key)
                if (!d.present) { if (d.gone > 0) gone[key] = d.gone; return@forEach }
                val pick = pickValue(d, lm[id], rm[id]) ?: return@forEach
                at[key] = d.at
                if (d.gone > 0) gone[key] = d.gone
                kept += d.at to pick
            }
            putLike(
                out, locRoot, "grants",
                JSONArray().also { arr ->
                    kept.sortedWith(compareBy({ it.first }, { it.second.optString("id") }))
                        .forEach { arr.put(it.second) }
                }
            )
        }

        // --- sets --------------------------------------------------------
        mergeSet(L.root, R.root, out, locRoot, "blocked", ConfigStamp::blk, ::decide, at, gone)
        mergeSet(L.root, R.root, out, locRoot, "aiAllowed", ConfigStamp::allow, ::decide, at, gone)

        // --- per-kid overlays --------------------------------------------
        mergeOverlay(L.root, R.root, out, locRoot, "blockedFor", "for", ::decide, at, gone)
        mergeOverlay(L.root, R.root, out, locRoot, "allowedFor", "afor", ::decide, at, gone)

        // --- device assignments ------------------------------------------
        run {
            val lm = strMap(L.root, "deviceProfiles")
            val rm = strMap(R.root, "deviceProfiles")
            val o = JSONObject()
            (lm.keys + rm.keys).sorted().forEach { token ->
                val key = ConfigStamp.dev(token)
                val d = decide(key)
                if (!d.present) { if (d.gone > 0) gone[key] = d.gone; return@forEach }
                val v = when (d.fromLocal) {
                    true -> lm[token]
                    false -> rm[token]
                    null -> listOfNotNull(lm[token], rm[token]).minOrNull()
                } ?: return@forEach
                at[key] = d.at
                if (d.gone > 0) gone[key] = d.gone
                o.put(token, v)
            }
            putLike(out, locRoot, "deviceProfiles", o)
        }

        // --- limits ------------------------------------------------------
        mergeLimits(L.root, R.root, out, ::decide, at, gone, collisions)

        // --- ai ----------------------------------------------------------
        // Which side won the ai unit, so the key can follow the same
        // decision instead of "whichever document I read last".
        var aiFromLocal: Boolean? = null
        run {
            val d = decide(ConfigStamp.AI)
            aiFromLocal = d.fromLocal
            val mine = L.root.optJSONObject("ai")
            val theirs = R.root.optJSONObject("ai")
            val pick = pickValue(d, mine, theirs)
            if (pick != null) {
                val ai = JSONObject(pick.toString())
                // A rules change invalidates every cached verdict, so the
                // version must move whenever the *judging inputs* differ —
                // picking one side and keeping its number would hand devices
                // rules they have never screened under a version they already
                // have verdicts for, and those verdicts decide what a child
                // sees.
                val lv = mine?.optInt("rulesVersion", 0) ?: 0
                val rv = theirs?.optInt("rulesVersion", 0) ?: 0
                val judgingDiffers = mine != null && theirs != null &&
                    judgingInputs(mine) != judgingInputs(theirs)
                // The number has to be a function of the two documents, not of
                // how many times they met: "max + 1 whenever they differ" moved
                // the fingerprint on every push from a stale peer that kept
                // its old rules, so a hub never settled and a phone could
                // never match it. The loser must re-screen, which only needs
                // the winner's rules to arrive under a version the loser has
                // no verdicts for — bump past the loser's number when it
                // would collide, otherwise the winner's own number stands.
                val fromMine = pick === mine
                val winnerV = if (fromMine) lv else rv
                val loserV = if (fromMine) rv else lv
                val version = when {
                    !judgingDiffers -> maxOf(lv, rv)
                    loserV >= winnerV -> loserV + 1
                    else -> winnerV
                }
                ai.put("rulesVersion", version)
                out.put("ai", ai)
                if (d.at > 0) at[ConfigStamp.AI] = d.at
                collide(ConfigStamp.AI, "ai", d, mine?.toString(), theirs?.toString())
            }
        }

        // --- master ------------------------------------------------------
        run {
            val d = decide(ConfigStamp.MASTER)
            val mine = L.root.optString("master").ifEmpty { null }
            val theirs = R.root.optString("master").ifEmpty { null }
            // On a tie the lexicographically smaller token wins. "Keep the
            // local one" is not commutative, so two co-parents who both
            // claimed would never converge and would both keep running the
            // rate-limit-expensive crawl.
            val pick = when (d.fromLocal) {
                true -> mine
                false -> theirs
                null -> listOfNotNull(mine, theirs).minOrNull()
            }
            if (pick != null) {
                out.put("master", pick)
                if (d.at > 0) at[ConfigStamp.MASTER] = d.at
            } else out.remove("master")
        }

        // --- the loose settings group ------------------------------------
        run {
            val d = decide(ConfigStamp.SETTINGS)
            val from = when (d.fromLocal) {
                false -> R.root
                true -> L.root
                null -> if (settingsOf(L.root).toString() <= settingsOf(R.root).toString()) L.root else R.root
            }
            SETTINGS_KEYS.forEach { k ->
                if (from.has(k)) out.put(k, from.get(k)) else out.remove(k)
            }
            if (d.at > 0) at[ConfigStamp.SETTINGS] = d.at
            collide(
                ConfigStamp.SETTINGS, "settings", d,
                settingsOf(L.root).toString(), settingsOf(R.root).toString()
            )
        }

        // Cross-section coherence, then the bookkeeping.
        scrubReferences(out)
        // A tombstone whose subject is listed by NEITHER side is visited by no
        // loop above: every loop walks content ids, and a settled delete has
        // none. It must still travel (PLAN-sync R14, "tombstones are
        // permanent"). Dropping it here made a delete enforceable for exactly
        // one merge: the next push in which nobody listed the unit lost the
        // tombstone, any stale peer still holding the unit re-added it as new
        // (no tombstone anywhere reads as a plain add), the parent's next save
        // deleted it again, and a hub's log flipped between two hashes forever.
        // Carrying the newer of the two is what the loops do for the units they
        // do visit; prune below still caps the blob.
        (L.sync.gone.keys + R.sync.gone.keys).forEach { k ->
            if (k in decided) return@forEach
            if (k !in gone) gone[k] = maxOf(L.sync.gone[k] ?: 0L, R.sync.gone[k] ?: 0L)
            // In a fail-closed namespace the lift's proof is the block's own
            // stamp, and it travels with the tombstone or a stale block
            // outranks a deliberate unblock the moment nobody lists the unit.
            if (failsClosed(k.substringBefore('|')) && k !in at) {
                val stamp = maxOf(L.sync.at[k] ?: 0L, R.sync.at[k] ?: 0L)
                if (stamp > 0) at[k] = stamp
            }
        }
        val learned = mergeLogs(L.sync.log, R.sync.log)
            .filterNot { c -> L.sync.log.any { it.id == c.id } }
        val sync = ConfigStamp.prune(
            SyncMeta(
                v = SyncMeta.VERSION,
                docAt = maxOf(L.sync.docAt, R.sync.docAt),
                at = at,
                gone = gone,
                floor = floor,
                log = mergeLogs(L.sync.log, R.sync.log)
            )
        )
        if (!sync.isEmpty) out.put("sync", syncToJson(sync)) else out.remove("sync")
        // Never let a merged document claim a moment the peer invented.
        out.put("updatedAt", maxOf(locRoot.optLong("updatedAt", 0L), inRoot.optLong("updatedAt", 0L)))

        val changedLocally = !sameDoc(out, locRoot)
        val peerBehind = !sameDoc(out, inRoot)
        return Result(
            merged = if (changedLocally) out.toString(2) else null,
            peerBehind = peerBehind,
            collisions = collisions,
            learned = learned,
            apiKey = pickKey(aiFromLocal, localKey, incomingKey)
        )
    }

    private data class Decision(
        val present: Boolean,
        val at: Long,
        val gone: Long,
        /** true = local wins, false = remote wins, null = tie, break on value. */
        val fromLocal: Boolean?,
        val localAt: Long,
        val remoteAt: Long
    )

    /** On a tie, the lexicographically smaller compact form — symmetric, so both sides agree. */
    /**
     * The API key, resolved by the same stamp that decided the ai unit.
     *
     * It used to be "whatever the peer sent, if anything" — the one field in
     * the whole document merged by last-read-wins rather than by stamp. A
     * parent rotating the key while a TV was off got it back: the TV woke,
     * served its stale key, the phone adopted it, pushed it on, and both ends
     * settled on the old one reporting "in sync". If the rotation was because
     * the key had leaked, the app restored the leaked credential.
     *
     * A blank incoming key deliberately never clears a real one. It cannot be
     * told apart from "this peer holds no key at all" — a hub strips it before
     * writing, and so does every device's own disk copy — so treating blank as
     * an instruction would let the commonest peer in the fleet wipe the key on
     * first contact. The cost is that clearing a key does not propagate; that
     * is a smaller and much less alarming failure than the one it prevents.
     */
    internal fun pickKey(aiFromLocal: Boolean?, mine: String, theirs: String): String = when {
        theirs.isBlank() -> mine
        mine.isBlank() -> theirs
        mine == theirs -> mine
        aiFromLocal == true -> mine
        aiFromLocal == false -> theirs
        // A tie means neither side edited the ai unit more recently, so there
        // is nothing to prefer. Both sides must still land on the same answer
        // or they never converge — the same lexicographic rule master uses.
        else -> minOf(mine, theirs)
    }

    private fun pickValue(d: Decision, mine: JSONObject?, theirs: JSONObject?): JSONObject? =
        when (d.fromLocal) {
            true -> mine ?: theirs
            false -> theirs ?: mine
            null -> when {
                mine == null -> theirs
                theirs == null -> mine
                mine.toString() <= theirs.toString() -> mine
                else -> theirs
            }
        }

    /**
     * A legacy document — no `sync` block — gets synthesised stamps and **no
     * tombstones whatsoever**.
     *
     * `toJson` mints `updatedAt` at *serialization* time, so every document an
     * older build serves claims to be brand new. Deriving a deletion from what
     * such a document happens to be missing would let a phone that spent a
     * fortnight in a drawer, or a fresh install whose config is empty,
     * permanently delete a family's entire setup. So absence carries no
     * information here, ever.
     *
     * Members get *positional* rank — 0, 1, 2 — which is about twelve orders
     * of magnitude below any real millisecond stamp, so the canonical ordering
     * reproduces the file's existing order exactly for a never-merged config
     * without reading a clock.
     */
    private fun normalise(root: JSONObject): Side {
        val existing = syncFromJson(root)
        if (!existing.isEmpty) return Side(root, existing, legacy = false)
        val at = LinkedHashMap<String, Long>()
        // One-based. Zero means "no stamp at all", which is what makes a unit
        // absent — so a legacy document's first channel would vanish if rank
        // started at 0.
        idsOf(root, "entries").forEachIndexed { i, id -> at[ConfigStamp.src(id)] = i + 1L }
        idsOf(root, "profiles").forEachIndexed { i, id -> at[ConfigStamp.kid(id)] = i + 1L }
        idsOf(root, "grants").forEachIndexed { i, id -> at[ConfigStamp.grant(id)] = i + 1L }
        strsOf(root, "blocked").forEach { at[ConfigStamp.blk(it)] = 1L }
        strsOf(root, "aiAllowed").forEach { at[ConfigStamp.allow(it)] = 1L }
        overlayKeys(root, "blockedFor").forEach { at["for|$it"] = 1L }
        overlayKeys(root, "allowedFor").forEach { at["afor|$it"] = 1L }
        strMap(root, "deviceProfiles").keys.forEach { at[ConfigStamp.dev(it)] = 1L }
        return Side(root, SyncMeta(at = at), legacy = true)
    }

    // --- merge helpers --------------------------------------------------

    /**
     * Stamps at or below this are synthesised positional ranks from a legacy
     * document, not real times. A real stamp is epoch milliseconds — about
     * 1.7e12 — so the two bands cannot be confused, and the distinction is
     * what lets the floor refuse a genuinely stale row while never refusing
     * one that simply predates the format.
     */
    private const val SYNTHETIC_AT_MAX = 1_000_000L

    /** The loose family-wide scalars that share one stamp. */
    private val SETTINGS_KEYS = listOf(
        "sponsorSkip", "autoplay", "suggest", "channelLayout", "channelOrder",
        "listen", "qualityTv", "qualityPhone", "pageSize", "showVideoAge"
    )

    /**
     * A kid whose id no longer exists cannot be referenced. The sentinel is
     * never a valid 8-hex id, so `visibleTo` answers false for every kid —
     * the entry is hidden rather than shown to everyone.
     */
    const val PROFILE_NONE = "-"

    private fun stripKey(root: JSONObject) {
        root.optJSONObject("ai")?.remove("apiKey")
    }

    /**
     * Write a container, preserving whether the local document had the key at
     * all when the result is empty.
     *
     * `toJson` is not uniform about this — `blocked` is always written, even
     * empty, while `profiles` is omitted when empty — so a merge that picked
     * its own convention would report a change on a document identical to
     * itself. That is not cosmetic: the reconcile would then push on every
     * sweep, forever, against every device in the house.
     */
    private fun putLike(out: JSONObject, local: JSONObject, field: String, value: Any) {
        val empty = when (value) {
            is JSONArray -> value.length() == 0
            is JSONObject -> value.length() == 0
            else -> false
        }
        when {
            !empty -> out.put(field, value)
            local.has(field) -> out.put(field, value)
            else -> out.remove(field)
        }
    }

    private fun jsonObjects(root: JSONObject, field: String): List<JSONObject> {
        val arr = root.optJSONArray(field) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun byId(root: JSONObject, field: String): Map<String, JSONObject> {
        // First wins, matching every `distinctBy { it.id }` in the app — a
        // duplicate id is a pre-existing data problem, and the merge must not
        // be the place that starts resolving it differently.
        val out = LinkedHashMap<String, JSONObject>()
        jsonObjects(root, field).forEach { o ->
            o.optString("id").takeIf { it.isNotBlank() }?.let { out.putIfAbsent(it, o) }
        }
        return out
    }

    private fun idsOf(root: JSONObject, field: String): List<String> =
        byId(root, field).keys.toList()

    private fun strsOf(root: JSONObject, field: String): List<String> {
        val arr = root.optJSONArray(field) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private fun strMap(root: JSONObject, field: String): Map<String, String> {
        val o = root.optJSONObject(field) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        o.keys().forEach { k -> o.optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it } }
        return out
    }

    /** `videoId|kidId` pairs, which is how an overlay is keyed as units. */
    private fun overlayKeys(root: JSONObject, field: String): List<String> {
        val o = root.optJSONObject(field) ?: return emptyList()
        val out = ArrayList<String>()
        o.keys().forEach { videoId ->
            strsOf(o, videoId).forEach { kidId -> out += "$videoId|$kidId" }
        }
        return out
    }

    private fun settingsOf(root: JSONObject): JSONObject =
        JSONObject().also { o -> SETTINGS_KEYS.forEach { k -> if (root.has(k)) o.put(k, root.get(k)) } }

    /**
     * What the AI actually judges on. A change to any of it invalidates every
     * cached verdict on every device.
     *
     * Encoded as a JSON array rather than joined with a delimiter. With a
     * plain separator, rules="a b" with model="c" produces the same string as
     * rules="a" with model="b c", so a real rules change would compare equal,
     * skip the rulesVersion bump, and leave every device reusing cached
     * verdicts against rules it had never screened under — on a child-safety
     * judgement. JSON quotes and escapes each value, so none of them can
     * impersonate a boundary, and it needs no unprintable sentinel byte.
     */
    private fun judgingInputs(ai: JSONObject): String = JSONArray(
        listOf(
            ai.optString("rules"), ai.optString("model"),
            ai.optString("baseUrl"), ai.optString("childAge")
        )
    ).toString()

    /** Compares two documents ignoring key order, which `toString` does not. */
    private fun sameDoc(a: JSONObject, b: JSONObject): Boolean = canonical(a) == canonical(b)

    private fun canonical(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { "$it:${canonical(v.get(it))}" }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.get(it)) }
        else -> v?.toString() ?: "null"
    }

    /** One field inside an object that is its own unit — currently just a kid's PIN. */
    private fun fieldUnit(
        key: String,
        decide: (String) -> Decision,
        at: MutableMap<String, Long>,
        gone: MutableMap<String, Long>,
        mine: JSONObject?,
        theirs: JSONObject?,
        into: JSONObject,
        field: String
    ) {
        val d = decide(key)
        if (d.gone > 0) gone[key] = d.gone
        if (!d.present) { into.remove(field); return }
        val from = when (d.fromLocal) {
            true -> mine
            false -> theirs
            // A tie: neither side stamped the unit more recently. "Mine" here
            // meant each side kept its own, so two devices holding different
            // unstamped limits pushed at each other forever. Both must land
            // on the same answer — the canonical-order rule pickKey uses.
            null -> when {
                mine == null -> theirs
                theirs == null -> mine
                canonical(mine.opt(field)) <= canonical(theirs.opt(field)) -> mine
                else -> theirs
            }
        }
        if (from != null && from.has(field)) into.put(field, from.get(field)) else into.remove(field)
        if (d.at > 0) at[key] = d.at
    }

    private fun mergeKidLimits(
        id: String,
        decide: (String) -> Decision,
        at: MutableMap<String, Long>,
        gone: MutableMap<String, Long>,
        mine: JSONObject?,
        theirs: JSONObject?,
        into: JSONObject
    ) {
        val ml = mine?.optJSONObject("limits")
        val tl = theirs?.optJSONObject("limits")
        if (ml == null && tl == null) return
        val merged = limitsUnion(
            id = id, decide = decide, at = at, gone = gone, mine = ml, theirs = tl
        )
        if (merged.length() > 0) into.put("limits", merged) else into.remove("limits")
    }

    /**
     * A limits object assembled from four separately-stamped units, then its
     * legacy bedtime pair recomputed.
     *
     * The recompute is not cosmetic. Taking windows from one side and pass
     * state from the other otherwise leaves an old TV enforcing a bedtime the
     * phone deleted, or skipping one it set — and the flat pair is all such a
     * TV enforces.
     */
    private fun limitsUnion(
        id: String?,
        decide: (String) -> Decision,
        at: MutableMap<String, Long>,
        gone: MutableMap<String, Long>,
        mine: JSONObject?,
        theirs: JSONObject?
    ): JSONObject {
        fun keyFor(part: String) = if (id == null) {
            when (part) {
                "rules" -> ConfigStamp.LIM_RULES
                "windows" -> ConfigStamp.LIM_WINDOWS
                "pause" -> ConfigStamp.LIM_PAUSE
                else -> ConfigStamp.LIM_BRK
            }
        } else when (part) {
            "rules" -> ConfigStamp.kidRules(id)
            "windows" -> ConfigStamp.kidWindows(id)
            "pause" -> ConfigStamp.kidPause(id)
            else -> ConfigStamp.kidBrk(id)
        }

        fun sideFor(part: String): JSONObject? {
            val k = keyFor(part)
            val d = decide(k)
            if (d.at > 0) at[k] = d.at
            if (d.gone > 0) gone[k] = d.gone
            return when (d.fromLocal) {
                true -> mine
                false -> theirs
                // A tie with different content: "mine" meant each side kept
                // its own, so two devices holding different unstamped limits
                // pushed at each other forever. Same canonical-order rule as
                // pickKey and the settings group, so both land on one answer.
                null -> when {
                    mine == null -> theirs
                    theirs == null -> mine
                    canonical(mine) <= canonical(theirs) -> mine
                    else -> theirs
                }
            }
        }

        val out = JSONObject()
        // Anything this build does not model survives from whichever side is
        // carrying the rules, rather than being dropped on the floor.
        sideFor("rules")?.let { src ->
            src.keys().forEach { k ->
                if (k !in LIMITS_OWNED) out.put(k, src.get(k))
            }
            LIMITS_RULES_KEYS.forEach { k -> if (src.has(k)) out.put(k, src.get(k)) }
        }
        sideFor("windows")?.let { src -> if (src.has("windows")) out.put("windows", src.get("windows")) }
        sideFor("pause")?.let { src -> if (src.has("pausedUntil")) out.put("pausedUntil", src.get("pausedUntil")) }
        sideFor("brk")?.let { src -> if (src.has("breakPassUntil")) out.put("breakPassUntil", src.get("breakPassUntil")) }
        refreshLegacyBedtime(out)
        return out
    }

    private val LIMITS_RULES_KEYS = listOf(
        "session", "weekdaySessions", "weekendSessions", "breakMinutes", "minVideoMinutes"
    )

    /** Every key `limitsUnion` decides for itself; anything else is passed through. */
    private val LIMITS_OWNED = (
        LIMITS_RULES_KEYS + listOf("windows", "pausedUntil", "breakPassUntil", "bedtimeStart", "bedtimeEnd")
        ).toSet()

    /**
     * Rewrite the flat `bedtimeStart`/`bedtimeEnd` pair from the merged
     * windows, by the same rule `limitsToJson` uses: a single every-day window
     * with no active pass is exactly what pre-windows builds called bedtime.
     * Anything richer has no legacy equivalent and is left out rather than
     * flattened into a wrong one.
     */
    internal fun refreshLegacyBedtime(limits: JSONObject) {
        limits.remove("bedtimeStart")
        limits.remove("bedtimeEnd")
        val arr = limits.optJSONArray("windows") ?: return
        if (arr.length() != 1) return
        val w = arr.optJSONObject(0) ?: return
        if (w.has("days") || w.has("passUntil")) return
        limits.put("bedtimeStart", w.optInt("start"))
        limits.put("bedtimeEnd", w.optInt("end"))
    }

    private fun mergeLimits(
        local: JSONObject,
        remote: JSONObject,
        out: JSONObject,
        decide: (String) -> Decision,
        at: MutableMap<String, Long>,
        gone: MutableMap<String, Long>,
        collisions: MutableList<Collision>
    ) {
        val mine = local.optJSONObject("limits")
        val theirs = remote.optJSONObject("limits")
        if (mine == null && theirs == null) return
        // Always written when either side had the key. Removing an *empty*
        // limits object that the local file carried would make the merge
        // report a change on a document that is otherwise identical, and the
        // sweep would then push on every pass forever.
        out.put("limits", limitsUnion(null, decide, at, gone, mine, theirs))
        val d = decide(ConfigStamp.LIM_RULES)
        if (d.localAt > 0 && d.remoteAt > d.localAt && mine?.toString() != theirs?.toString()) {
            collisions += Collision(
                ConfigStamp.LIM_RULES, "lim",
                mine?.toString().orEmpty(), d.localAt,
                theirs?.toString().orEmpty(), d.remoteAt
            )
        }
    }

    private fun mergeSet(
        local: JSONObject,
        remote: JSONObject,
        out: JSONObject,
        locRoot: JSONObject,
        field: String,
        key: (String) -> String,
        decide: (String) -> Decision,
        at: MutableMap<String, Long>,
        gone: MutableMap<String, Long>
    ) {
        val members = (strsOf(local, field) + strsOf(remote, field)).distinct()
        val kept = ArrayList<String>()
        members.forEach { m ->
            val k = key(m)
            val d = decide(k)
            if (d.gone > 0) gone[k] = d.gone
            if (!d.present) {
                // In a fail-closed namespace the lift is what needs proof, and
                // the proof is the block's own stamp: decide() accepts an
                // unblock only when the side asserting it saw the block
                // (`sawAdd` reads `at` against `gone`). Dropping `at` with the
                // unit made the next push of the same stale block unprovable,
                // so it came back, was lifted again, and the hub flipped
                // between two hashes on every push. Keep the proof travelling.
                if (failsClosed(k.substringBefore('|')) && d.at > 0) at[k] = d.at
                return@forEach
            }
            if (d.at > 0) at[k] = d.at
            kept += m
        }
        // Sorted, so two devices holding the same set write the same bytes.
        putLike(out, locRoot, field, JSONArray().also { a -> kept.sorted().forEach { a.put(it) } })
    }

    private fun mergeOverlay(
        local: JSONObject,
        remote: JSONObject,
        out: JSONObject,
        locRoot: JSONObject,
        field: String,
        ns: String,
        decide: (String) -> Decision,
        at: MutableMap<String, Long>,
        gone: MutableMap<String, Long>
    ) {
        val pairs = (overlayKeys(local, field) + overlayKeys(remote, field)).distinct()
        val kept = LinkedHashMap<String, MutableSet<String>>()
        pairs.forEach { pair ->
            val k = "$ns|$pair"
            val d = decide(k)
            if (d.gone > 0) gone[k] = d.gone
            if (!d.present) {
                // Same as mergeSet: a lifted per-kid block keeps the block's
                // stamp, or the lift cannot be proved on the next push.
                if (failsClosed(ns) && d.at > 0) at[k] = d.at
                return@forEach
            }
            if (d.at > 0) at[k] = d.at
            val videoId = pair.substringBefore('|')
            val kidId = pair.substringAfter('|')
            kept.getOrPut(videoId) { linkedSetOf() } += kidId
        }
        putLike(
            out, locRoot, field,
            JSONObject().also { o ->
                kept.keys.sorted().forEach { v ->
                    o.put(v, JSONArray().also { a -> kept.getValue(v).sorted().forEach { a.put(it) } })
                }
            }
        )
    }

    /**
     * Drop references to kids the config no longer has, across entries,
     * per-kid overlays and device assignments — and resolve the one incoherent
     * state a merge can produce, the same video blocked *and* allowed for one
     * kid.
     *
     * The critical part is what happens to an entry whose kid list becomes
     * empty *by scrubbing*: it must NOT fall back to the "visible to everyone"
     * meaning an empty list normally carries. That fails open, so a channel
     * restricted to a removed fourteen-year-old would become visible to the
     * six-year-old. It gets the [PROFILE_NONE] sentinel instead and is hidden
     * until a parent assigns someone.
     */
    internal fun scrubReferences(root: JSONObject) {
        val valid = idsOf(root, "profiles").toSet()

        jsonObjects(root, "entries").forEach { e ->
            val listed = strsOf(e, "profiles")
            if (listed.isEmpty()) return@forEach
            val kept = listed.filter { it in valid }
            when {
                kept == listed -> {}
                kept.isEmpty() -> e.put("profiles", JSONArray().put(PROFILE_NONE))
                else -> e.put("profiles", JSONArray().also { a -> kept.sorted().forEach { a.put(it) } })
            }
        }

        listOf("blockedFor", "allowedFor").forEach { field ->
            val o = root.optJSONObject(field) ?: return@forEach
            val cleaned = JSONObject()
            o.keys().forEach { videoId ->
                val kept = strsOf(o, videoId).filter { it in valid }
                if (kept.isNotEmpty()) {
                    cleaned.put(videoId, JSONArray().also { a -> kept.sorted().forEach { a.put(it) } })
                }
            }
            if (cleaned.length() > 0) root.put(field, cleaned) else root.remove(field)
        }

        root.optJSONObject("deviceProfiles")?.let { o ->
            val cleaned = JSONObject()
            o.keys().forEach { token ->
                o.optString(token).takeIf { it in valid }?.let { cleaned.put(token, it) }
            }
            if (cleaned.length() > 0) root.put("deviceProfiles", cleaned) else root.remove("deviceProfiles")
        }

        // Blocked and allowed for the same kid is a child-safety question, so
        // it is never decided by a generic tie-break: the block holds.
        val blockedFor = root.optJSONObject("blockedFor")
        val allowedFor = root.optJSONObject("allowedFor")
        if (blockedFor != null && allowedFor != null) {
            val cleaned = JSONObject()
            allowedFor.keys().forEach { videoId ->
                val blocked = strsOf(blockedFor, videoId).toSet()
                val kept = strsOf(allowedFor, videoId).filterNot { it in blocked }
                if (kept.isNotEmpty()) {
                    cleaned.put(videoId, JSONArray().also { a -> kept.sorted().forEach { a.put(it) } })
                }
            }
            if (cleaned.length() > 0) root.put("allowedFor", cleaned) else root.remove("allowedFor")
        }
    }

    /** Union by id, oldest first, capped. A log line is data, not state. */
    private fun mergeLogs(a: List<Change>, b: List<Change>): List<Change> =
        (a + b).associateBy { it.id }.values
            .sortedWith(compareBy({ it.at }, { it.id }))
            .takeLast(SyncMeta.MAX_LOG)
}

/**
 * Per-unit sync bookkeeping, carried inside `config.json` under `"sync"`.
 *
 * A *unit* is the smallest thing two parents can edit independently: one
 * channel (`src|<id>`), one kid's rules (`kid.rules|<id>`), one blocked video
 * (`blk|<id>`), the loose app settings as a group (`settings`). The key space
 * is the whole design — see `docs/PLAN-sync.md`.
 *
 * None of it is ever enforced or shown as a rule. It exists so a merge can
 * tell "Dad added this" from "Mum removed it", which whole-file
 * last-writer-wins cannot, and so the change log can answer "why did the TV
 * change?".
 */
data class SyncMeta(
    /** Format version. A document whose `v` this build does not know reads as no sync block at all. */
    val v: Int = VERSION,
    /**
     * The highest stamp this document carries. Every new stamp is minted above
     * it, so a device whose clock came back wrong after a power cut can still
     * win its own edit instead of losing every unit it touches.
     */
    val docAt: Long = 0L,
    /** unit key → when it was last edited. */
    val at: Map<String, Long> = emptyMap(),
    /**
     * unit key → when it was deleted. Permanent, because age-based pruning is
     * clock-dependent: two devices on different clocks would prune different
     * sets and then rewrite and re-exchange forever.
     */
    val gone: Map<String, Long> = emptyMap(),
    /**
     * namespace → the newest tombstone stamp ever dropped by the cap. A
     * one-sided row older than the floor is not admitted, which is what stops
     * an evicted tombstone from letting its subject come back.
     */
    val floor: Map<String, Long> = emptyMap(),
    /** The recent change log, newest last, capped at [MAX_LOG]. */
    val log: List<ConfigMerge.Change> = emptyList()
) {
    val isEmpty: Boolean
        get() = at.isEmpty() && gone.isEmpty() && floor.isEmpty() && log.isEmpty()

    companion object {
        /** Bump only for a change an older build could misread. Unknown versions read as absent. */
        const val VERSION = 1

        /** Tombstones kept before the oldest is dropped and [floor] rises in its place. */
        const val MAX_TOMBSTONES = 1000

        /** Change-log lines carried in the config. It is a feed, not an audit trail. */
        const val MAX_LOG = 30

        val EMPTY = SyncMeta()
    }
}
