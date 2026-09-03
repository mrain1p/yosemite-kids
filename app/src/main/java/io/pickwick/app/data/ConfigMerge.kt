package io.pickwick.app.data

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
    data class Change(val code: String, val text: String)

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
}
