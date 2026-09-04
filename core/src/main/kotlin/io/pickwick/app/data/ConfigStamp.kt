package io.pickwick.app.data

/**
 * Minting the sync blob for a config about to be written.
 *
 * The other half of [ConfigMerge]: `describe` says what changed for a human,
 * this says what changed for the merge. Pure — the clock arrives as a
 * parameter — so every awkward case is a JVM unit test.
 */
object ConfigStamp {

    /** Edits from one device inside this window fold into one log line. */
    const val LOG_COALESCE_MS = 5 * 60 * 1000L

    /** Past this far ahead of the wall clock, a peer's clock is wrong, not fast. */
    const val CLOCK_SKEW_MAX_MS = 7L * 24 * 60 * 60 * 1000

    // --- Unit keys ------------------------------------------------------
    // The key space *is* the design: two parents can edit two units without
    // colliding, and cannot edit one without it being noticed.

    fun src(id: String) = "src|$id"
    fun kid(id: String) = "kid|$id"
    fun kidPin(id: String) = "kid.pin|$id"
    fun kidRules(id: String) = "kid.rules|$id"
    fun kidWindows(id: String) = "kid.windows|$id"
    fun kidPause(id: String) = "kid.pause|$id"
    fun kidBrk(id: String) = "kid.brk|$id"
    fun blk(id: String) = "blk|$id"
    fun allow(id: String) = "allow|$id"
    fun dev(token: String) = "dev|$token"

    /**
     * Per-kid overlays. The shape must match ConfigMerge.mergeOverlay, which
     * builds "$ns|$videoId|$kidId" from overlayKeys — a different spelling
     * here would mint stamps the merge never looks at, which is worse than
     * minting none because it looks fixed.
     */
    fun forKid(videoId: String, kidId: String) = "for|$videoId|$kidId"
    fun aforKid(videoId: String, kidId: String) = "afor|$videoId|$kidId"

    const val LIM_RULES = "lim.rules"
    const val LIM_WINDOWS = "lim.windows"
    const val LIM_PAUSE = "lim.pause"
    const val LIM_BRK = "lim.brk"
    const val AI = "ai"
    const val SETTINGS = "settings"
    const val MASTER = "master"

    /** The namespace of a unit key — what [SyncMeta.floor] is keyed by. */
    fun namespace(key: String): String = key.substringBefore('|')

    /** [config] as it should be written, plus whether a peer's clock looks wrong. */
    data class Result(val config: Whitelist, val clockLooksWrong: Boolean)

    /**
     * What a settings edit becomes on disk. A genuine three-way diff, and
     * every part of that is load-bearing:
     *
     * - [previous] is the document **on disk right now**, read under the same
     *   lock as the write that follows. A co-parent's channel can land via
     *   `POST /config` from a LAN worker thread while a parent has Settings
     *   open. That channel is in [previous] and not in [base], and reading it
     *   as "the editor deleted it" would mint a durable, propagating tombstone
     *   at the form's next autosave — deleting a channel nobody touched.
     * - [base] is what the editor opened with. Only a difference between
     *   [base] and [next] is an edit by this parent.
     * - [next] is the form as it stands.
     *
     * [now] is the caller's wall clock, passed in rather than read here, so
     * the merge proper can stay provably clock-free.
     */
    fun stamped(
        previous: Whitelist,
        base: Whitelist,
        next: Whitelist,
        now: Long,
        who: String,
        by: String
    ): Result {
        val prevSync = previous.sync
        // Monotonic: a device whose clock came back wrong after a power cut
        // must still win its own edit, rather than losing every unit it
        // touches until somebody notices the date.
        val mint = maxOf(now, prevSync.docAt + 1)
        val clockOff = prevSync.docAt > now + CLOCK_SKEW_MAX_MS

        val at = LinkedHashMap(prevSync.at)
        val gone = LinkedHashMap(prevSync.gone)
        val changes = ArrayList<ConfigMerge.Change>()

        fun touch(key: String) { at[key] = mint }
        fun remove(key: String) {
            // Which of the two stamps survives depends on which way the unit
            // fails, and getting that backwards is silent in both directions.
            //
            // Fails ABSENT (a channel, a kid): the stamp goes. Keeping both
            // would let the unit satisfy its own causality check on the next
            // merge and come straight back.
            //
            // Fails CLOSED (a block): the stamp stays. Here it is lifting the
            // block that has to be proved, and the proof is precisely that
            // this side was holding the block when it removed it. Dropping
            // the stamp produced a document the merge could not tell apart
            // from a stale copy that had never seen the block — so it
            // refused it, correctly, and no unblock ever reached a
            // television. ConfigMergeTest.aDeliberateUnblockWorks asserts
            // the right shape but builds the document by hand, which is why
            // nothing caught that the stamper never produced it.
            if (!ConfigMerge.failsClosed(namespace(key))) at.remove(key)
            gone[key] = mint
        }
        fun readd(key: String) {
            // Deliberately the opposite of SavedListStore.add, which clears its
            // removal marker. Clearing the tombstone destroys the evidence that
            // this add came *after* the delete, so a deliberately re-added
            // channel would vanish again on the next sweep, forever — and the
            // collision banner's own "Add it back" would not work.
            at[key] = mint
        }

        // --- Channels ---------------------------------------------------
        val prevSrc = previous.sources.associateBy { it.id }
        val baseSrc = base.sources.associateBy { it.id }
        val nextSrc = next.sources.associateBy { it.id }
        val sources = ArrayList<WhitelistEntry>()
        nextSrc.forEach { (id, e) ->
            sources += e
            when {
                id !in baseSrc -> {
                    readd(src(id))
                    changes += line("src.add", "added ${label(e)}", who, by, mint)
                }
                baseSrc[id] != e -> {
                    touch(src(id))
                    changes += line("src.edit", "changed ${label(e)}", who, by, mint)
                }
            }
        }
        (baseSrc.keys - nextSrc.keys).forEach { id ->
            remove(src(id))
            changes += line("src.remove", "removed ${label(baseSrc.getValue(id))}", who, by, mint)
        }
        // In previous but never in base: it arrived under the open form. Carry
        // it, do not stamp it, and above all do not tombstone it.
        (prevSrc.keys - baseSrc.keys - nextSrc.keys).forEach { sources += prevSrc.getValue(it) }

        // --- Kids -------------------------------------------------------
        val prevKid = previous.profiles.associateBy { it.id }
        val baseKid = base.profiles.associateBy { it.id }
        val nextKid = next.profiles.associateBy { it.id }
        val profiles = ArrayList<Profile>()
        nextKid.forEach { (id, p) ->
            profiles += p
            val b = baseKid[id]
            if (b == null) {
                readd(kid(id))
                // And every unit that rides inside the profile object.
                // Removing a kid tombstones all six below; re-adding only
                // the kid left the other five tombstoned and unstamped, so
                // the merge stripped them straight back out of a kid who
                // had just been restored. The PIN is the one that matters:
                // the kid returns without it and the picker lets a sibling
                // into their profile. On a genuinely new kid there is no
                // tombstone, so readd is exactly touch.
                readd(kidPin(id))
                readd(kidRules(id))
                readd(kidWindows(id))
                readd(kidPause(id))
                readd(kidBrk(id))
                changes += line("kid.add", "added ${p.name}", who, by, mint)
            } else {
                if (b.name != p.name || b.age != p.age || b.avatar != p.avatar ||
                    b.colorArgb != p.colorArgb || b.lookAt != p.lookAt
                ) touch(kid(id))
                // Its own unit because it is a credential riding inside an
                // object: without this, fixing a kid's name on a stale copy
                // silently removes the code a co-parent set an hour ago, and
                // the picker then lets a sibling into that kid's profile.
                if (b.pin != p.pin) touch(kidPin(id))
                if (!sameRules(b.limits, p.limits)) touch(kidRules(id))
                if (b.limits.windows != p.limits.windows) touch(kidWindows(id))
                if (b.limits.pausedUntilMillis != p.limits.pausedUntilMillis) touch(kidPause(id))
                if (b.limits.breakPassUntilMillis != p.limits.breakPassUntilMillis) touch(kidBrk(id))
                if (b != p) changes += line("kid.edit", "changed ${p.name}'s settings", who, by, mint)
            }
        }
        (baseKid.keys - nextKid.keys).forEach { id ->
            listOf(kid(id), kidPin(id), kidRules(id), kidWindows(id), kidPause(id), kidBrk(id))
                .forEach { remove(it) }
            changes += line("kid.remove", "removed ${baseKid.getValue(id).name}", who, by, mint)
        }
        (prevKid.keys - baseKid.keys - nextKid.keys).forEach { profiles += prevKid.getValue(it) }

        // --- Sets -------------------------------------------------------
        val blocked = setUnit(
            previous.blockedVideoIds, base.blockedVideoIds, next.blockedVideoIds,
            ::blk, at, gone, mint
        )
        if (next.blockedVideoIds != base.blockedVideoIds) {
            changes += line("blk", "changed the blocked list", who, by, mint)
        }
        val aiAllowed = setUnit(
            previous.aiAllowedVideoIds, base.aiAllowedVideoIds, next.aiAllowedVideoIds,
            ::allow, at, gone, mint
        )
        if (next.aiAllowedVideoIds != base.aiAllowedVideoIds) {
            changes += line("allow", "changed the safe list", who, by, mint)
        }

        // --- Per-kid overlays, and which kid a device belongs to ---------
        //
        // These three were decided by the merge on their own unit stamps
        // and minted by nothing. A unit with no stamp and no tombstone is
        // absent — decide() reads `g == 0L -> a > 0` — so every one of
        // these edits was silently dropped by the first peer that merged
        // it. Blocking a video for one kid never reached the television,
        // and neither did dedicating a device to a kid.
        val blockedFor = overlayUnit(
            previous.blockedFor, base.blockedFor, next.blockedFor, "for", at, gone, mint
        )
        if (next.blockedFor != base.blockedFor) {
            changes += line("for", "changed what is blocked for one kid", who, by, mint)
        }
        val allowedFor = overlayUnit(
            previous.allowedFor, base.allowedFor, next.allowedFor, "afor", at, gone, mint
        )
        if (next.allowedFor != base.allowedFor) {
            changes += line("afor", "changed what is allowed for one kid", who, by, mint)
        }
        val deviceProfiles = mapUnit(
            previous.deviceProfiles, base.deviceProfiles, next.deviceProfiles,
            ::dev, at, gone, mint
        )

        // --- Sections ---------------------------------------------------
        val limits = section3(previous.limits, base.limits, next.limits)
        if (!sameRules(base.limits, next.limits)) touch(LIM_RULES)
        if (base.limits.windows != next.limits.windows) touch(LIM_WINDOWS)
        if (base.limits.pausedUntilMillis != next.limits.pausedUntilMillis) touch(LIM_PAUSE)
        if (base.limits.breakPassUntilMillis != next.limits.breakPassUntilMillis) touch(LIM_BRK)
        if (base.limits != next.limits) {
            changes += line("lim", "changed everyone's rules", who, by, mint)
        }

        val ai = section3(previous.ai, base.ai, next.ai)
        if (base.ai != next.ai) {
            touch(AI)
            changes += line("ai", "changed screening", who, by, mint)
        }

        val master = section3(
            previous.masterDeviceToken, base.masterDeviceToken, next.masterDeviceToken
        )
        if (base.masterDeviceToken != next.masterDeviceToken) touch(MASTER)

        if (settingsDiffer(base, next)) {
            touch(SETTINGS)
            changes += line("settings", "changed app settings", who, by, mint)
        }

        val out = next.copy(
            sources = sources,
            profiles = profiles,
            blockedVideoIds = blocked,
            aiAllowedVideoIds = aiAllowed,
            blockedFor = blockedFor,
            allowedFor = allowedFor,
            deviceProfiles = deviceProfiles,
            limits = limits,
            ai = ai,
            masterDeviceToken = master,
            sync = prune(
                SyncMeta(
                    v = SyncMeta.VERSION,
                    docAt = maxOf(mint, prevSync.docAt),
                    at = at,
                    gone = gone,
                    floor = prevSync.floor,
                    log = coalesce(prevSync.log, changes)
                )
            )
        )
        return Result(out, clockOff)
    }

    /**
     * Hold the tombstone map to [SyncMeta.MAX_TOMBSTONES], raising the
     * namespace floor by whatever is dropped — so an evicted tombstone still
     * refuses to let its subject come back.
     */
    fun prune(sync: SyncMeta): SyncMeta {
        if (sync.gone.size <= SyncMeta.MAX_TOMBSTONES) return sync
        val ordered = sync.gone.entries.sortedByDescending { it.value }
        val floor = LinkedHashMap(sync.floor)
        ordered.drop(SyncMeta.MAX_TOMBSTONES).forEach { (k, v) ->
            val ns = namespace(k)
            floor[ns] = maxOf(floor[ns] ?: 0L, v)
        }
        return sync.copy(
            gone = ordered.take(SyncMeta.MAX_TOMBSTONES).associate { it.key to it.value },
            floor = floor
        )
    }

    // --- helpers --------------------------------------------------------

    private fun label(e: WhitelistEntry) = e.label?.takeIf { it.isNotBlank() } ?: e.id

    /** The limits scalars — everything except the three separately-stamped units. */
    private fun sameRules(a: Limits, b: Limits) =
        a.sessionMinutes == b.sessionMinutes &&
            a.weekdaySessions == b.weekdaySessions &&
            a.weekendSessions == b.weekendSessions &&
            a.breakMinutes == b.breakMinutes &&
            a.minVideoMinutes == b.minVideoMinutes

    /**
     * Three-way pick for a section: the editor's value when they changed it,
     * otherwise whatever is on disk now — which may have been merged in from a
     * co-parent while this form sat open.
     */
    private fun <T> section3(previous: T, base: T, next: T): T = if (base != next) next else previous

    private fun setUnit(
        previous: Set<String>,
        base: Set<String>,
        next: Set<String>,
        key: (String) -> String,
        at: MutableMap<String, Long>,
        gone: MutableMap<String, Long>,
        mint: Long
    ): Set<String> {
        val out = LinkedHashSet(next)
        (next - base).forEach { at[key(it)] = mint }
        // Same polarity rule as remove(): a fail-closed unit keeps its add
        // stamp beside the tombstone, because lifting it is the act that has
        // to be proved and that stamp is the proof.
        (base - next).forEach {
            val k = key(it)
            if (!ConfigMerge.failsClosed(namespace(k))) at.remove(k)
            gone[k] = mint
        }
        // Arrived under the open form: keep it, do not stamp, do not tombstone.
        (previous - base - next).forEach { out += it }
        return out
    }

    /**
     * A map of video id to the kids it applies to, diffed pair by pair.
     *
     * Per pair rather than per video, because the merge decides per pair:
     * two parents blocking the same video for different kids must both
     * land, not overwrite each other.
     */
    private fun overlayUnit(
        previous: Map<String, Set<String>>,
        base: Map<String, Set<String>>,
        next: Map<String, Set<String>>,
        ns: String,
        at: MutableMap<String, Long>,
        gone: MutableMap<String, Long>,
        mint: Long
    ): Map<String, Set<String>> {
        fun pairs(m: Map<String, Set<String>>): Set<String> =
            m.entries.flatMap { (video, kids) -> kids.map { "$video|$it" } }.toSet()

        val b = pairs(base)
        val n = pairs(next)
        (n - b).forEach { at["$ns|$it"] = mint }
        (b - n).forEach {
            if (!ConfigMerge.failsClosed(ns)) at.remove("$ns|$it")
            gone["$ns|$it"] = mint
        }

        // Arrived under the open form: keep it, do not stamp, do not
        // tombstone — the same rule setUnit follows.
        val out = LinkedHashMap<String, MutableSet<String>>()
        (n + (pairs(previous) - b - n)).forEach { pair ->
            out.getOrPut(pair.substringBefore(Char(124))) { linkedSetOf() } +=
                pair.substringAfter(Char(124))
        }
        return out.mapValues { it.value.toSet() }
    }

    /** Which kid a device is dedicated to, one unit per device token. */
    private fun mapUnit(
        previous: Map<String, String>,
        base: Map<String, String>,
        next: Map<String, String>,
        key: (String) -> String,
        at: MutableMap<String, Long>,
        gone: MutableMap<String, Long>,
        mint: Long
    ): Map<String, String> {
        val out = LinkedHashMap(next)
        next.forEach { (token, kid) -> if (base[token] != kid) at[key(token)] = mint }
        (base.keys - next.keys).forEach { at.remove(key(it)); gone[key(it)] = mint }
        (previous.keys - base.keys - next.keys).forEach { out[it] = previous.getValue(it) }
        return out
    }

    private fun settingsDiffer(a: Whitelist, b: Whitelist) =
        a.sponsorSkip != b.sponsorSkip || a.autoplayNext != b.autoplayNext ||
            a.suggestSimilar != b.suggestSimilar || a.channelLayout != b.channelLayout ||
            a.channelOrder != b.channelOrder || a.listenPercent != b.listenPercent ||
            a.qualityTv != b.qualityTv || a.qualityPhone != b.qualityPhone ||
            a.pageSize != b.pageSize || a.showVideoAge != b.showVideoAge ||
            a.deviceProfiles != b.deviceProfiles

    private fun line(code: String, text: String, who: String, by: String, at: Long) =
        ConfigMerge.Change(
            code = code,
            text = text,
            // Deterministic rather than random: nothing here may read a clock
            // or an RNG, and (device, unit, stamp) is already unique.
            id = idFor(by, code, text, at),
            at = at,
            shownAt = at,
            by = by,
            who = who
        )

    private fun idFor(by: String, code: String, text: String, at: Long): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("$by|$code|$text|$at".toByteArray())
            .take(4)
            .joinToString("") { "%02x".format(it) }

    /**
     * Append the new lines, folding a burst from one device into the last one.
     *
     * Coalesces on [ConfigMerge.Change.by] — the device token — and never on
     * `who`: two parents with the same phone model are both "Pixel 7 Pro", and
     * coalescing on the display name would have one parent's line silently
     * replace the other's, destroying the attribution the log exists for.
     */
    private fun coalesce(
        existing: List<ConfigMerge.Change>,
        fresh: List<ConfigMerge.Change>
    ): List<ConfigMerge.Change> {
        val out = ArrayList(existing)
        fresh.forEach { c ->
            val last = out.lastOrNull()
            val burst = last != null && last.by == c.by && last.code == c.code &&
                c.at - last.at in 0..LOG_COALESCE_MS
            if (burst) out[out.lastIndex] = c else out += c
        }
        return if (out.size <= SyncMeta.MAX_LOG) out else out.takeLast(SyncMeta.MAX_LOG)
    }
}
