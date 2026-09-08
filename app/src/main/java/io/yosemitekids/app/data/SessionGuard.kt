package io.yosemitekids.app.data

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Which rule a remaining figure belongs to — and therefore which clock it
 * counts down on.
 *
 * This distinction is the whole reason the app's chrome can show a *live*
 * number without asking [SessionGuard] again every second. Budget and
 * sitting remainders have already been divided by the source's drain rate by
 * the time they leave [SessionGuard.remainingAll], so they fall one
 * millisecond per millisecond **of playback** and not at all while nothing is
 * playing. A blocked window is a stretch of the clock on the wall: bedtime
 * arrives whether the kid is watching, paused, or reading the You page — and
 * paused is exactly when a kid is most likely to be looking at the number.
 */
enum class LimitKind {
    /** Today's budget: sittings × length, plus grants. */
    BUDGET,
    /** The sitting cap before a break is forced. */
    SITTING,
    /** A blocked window (bedtime, school hours) closing in. */
    WINDOW,
    /** A parent's timeout: nothing is left, and nothing will give it back today. */
    PAUSED;

    /**
     * True when this number falls on the wall clock rather than on playback.
     *
     * An exhaustive `when` on purpose: adding a kind and forgetting to say
     * which clock it runs on would silently freeze it in the chrome, which is
     * the failure this whole type exists to prevent. This way it does not
     * compile.
     */
    val onWallClock: Boolean
        get() = when (this) {
            WINDOW, PAUSED -> true
            BUDGET, SITTING -> false
        }
}

/** One rule's wall-clock milliseconds left, and the clock it counts on. */
data class Remaining(val ms: Long, val kind: LimitKind)

/**
 * What the chrome should be showing [sinceMs] after [reads] were taken, of
 * which [playedMs] was spent actually playing. Null when no rule applies —
 * and null means the pill is **hidden**, never a placeholder.
 *
 * Pure, and deliberately so: it is the one piece of this that runs every
 * second, and it has to be provable without a device. Re-reading
 * [SessionGuard.remainingAll] at 1 Hz instead is not merely wasteful — it
 * calls `rolloverIfNewDay()`, which *writes* to preferences.
 *
 * Every candidate is carried, not just the binding one, and each is aged on
 * its own clock before the minimum is taken. Ageing only the binding
 * candidate would be wrong in the ordinary case: with twelve budget-minutes
 * left and bedtime thirteen minutes away the budget binds, but three minutes
 * of staring at the You page later bedtime is ten away and the budget is
 * still twelve. The switch between them happens here, between reads.
 */
fun interpolateRemainingMs(reads: List<Remaining>, sinceMs: Long, playedMs: Long = 0L): Long? {
    if (reads.isEmpty()) return null
    // Clamped rather than trusted: a wall clock that steps backwards must not
    // hand the kid time back, and no more of an interval can be spent playing
    // than the interval itself lasted.
    val since = sinceMs.coerceAtLeast(0L)
    val played = playedMs.coerceIn(0L, since)
    return reads.minOf { r ->
        (r.ms - if (r.kind.onWallClock) since else played).coerceAtLeast(0L)
    }
}

/**
 * Enforces the parent's screen-time rules, configured in the settings UI
 * (see [Limits]).
 *
 * Model (no forfeiting):
 *  - Daily budget = session minutes × weekday/weekend session count.
 *    Only actual watch time draws it down; stopping early wastes nothing.
 *  - The session length also caps one *sitting*: after that much
 *    continuous-ish watching, a break lock forces a rest — but only while a
 *    break length is set. Break "Off" = sittings merge freely; the daily
 *    budget and the blocked windows still apply.
 *  - A gap of the break length since last watching starts a fresh sitting.
 *  - Each [TimeWindow] (bedtime, school hours) blocks its clock stretch
 *    outright, whatever budget is left — unless it is marked "Allow
 *    listening", which blocks watching only and lets sound-only playback
 *    through (the minutes still draw the daily budget down).
 *  - Rules left unset simply don't apply — no rule has a hidden default; the
 *    parent's settings screen is the whole truth.
 */
class SessionGuard(context: Context, private val profileSuffix: String = "") {

    private val appContext = context.applicationContext
    private val prefs = appContext
        .getSharedPreferences("limits$profileSuffix", Context.MODE_PRIVATE)

    companion object {
        /** Deliberately parent-attributed, so the kid doesn't read it as a bug. */
        private const val PAUSED_MESSAGE =
            "A parent paused screen time for today. See you tomorrow 💛"

        /**
         * The day's allowance in ms: sittings × length, plus [bonusMs]. Null
         * when no limit is set. Pure, so a JVM test can hold a config-carried
         * grant against the budget without a Context.
         */
        internal fun budgetMs(l: Limits, weekend: Boolean, bonusMs: Long): Long? {
            val perSession = l.sessionMinutes ?: return null
            val count = (if (weekend) l.weekendSessions else l.weekdaySessions) ?: return null
            return perSession * count * 60_000L + bonusMs
        }

        /**
         * Today's extra time from both places it can come from: the legacy
         * LAN grant (an older phone, a plain number of ms) and the grants the
         * config carries, taken by id. One number, so the settings root, the
         * stats screen and the enforcement paths cannot disagree about it.
         */
        internal fun bonusMs(legacyBonusMs: Long, grants: List<Grant>): Long =
            legacyBonusMs + grants.sumOf { it.minutes } * 60_000L

        /**
         * What this kid has spent today: this device's own minutes, plus what
         * peers reported. One summand, and that is the whole of a shared
         * budget's arithmetic — sittings, break locks, blocked windows, pause
         * and grants stay device-local, because a sitting is a stretch in
         * front of one screen and only the *budget* was ever asked to be
         * shared.
         *
         * [ownMs] is always the live counter, exact to the second; [peerMs] is
         * a mirror refreshed off-main when a ledger arrives.
         */
        internal fun spentMs(ownMs: Long, peerMs: Long): Long = ownMs + peerMs

        /**
         * The peers' half: their mirrored minutes under a shared budget, and
         * nothing at all under any other scope.
         *
         * Pure and here rather than inline at the read site, so the one
         * property the whole feature rests on can be *proved* without a
         * device: under the default scope the arithmetic is byte-for-byte what
         * this app has always done, whatever happens to be sitting in the
         * mirror. See `SessionGuardBudgetTest`.
         *
         * [Limits.sharesBudget] rather than a test against a value: a scope
         * neither this build nor the mirror understands must fall back to the
         * behaviour the family already had, decided in one place (guard 49).
         */
        internal fun peerMs(l: Limits, mirroredMs: Long): Long =
            if (l.sharesBudget) mirroredMs else 0L
    }

    // ---- limits config (persisted at whitelist refresh) ----

    fun saveLimits(l: Limits) {
        // Which store got which rules — the first question in any "the TV is
        // still blocking" report, and invisible without this line.
        android.util.Log.i("YosemiteKids",
            "limits[$profileSuffix] <- session=${l.sessionMinutes} " +
                "wd=${l.weekdaySessions} we=${l.weekendSessions} break=${l.breakMinutes} " +
                "breakPass=${l.breakPassUntilMillis} " +
                "windows=${l.windows.joinToString { it.label }} paused=${l.pausedUntilMillis}"
        )
        prefs.edit()
            .putInt("l_session", l.sessionMinutes ?: -1)
            .putInt("l_wd", l.weekdaySessions ?: -1)
            .putInt("l_we", l.weekendSessions ?: -1)
            .putInt("l_break", l.breakMinutes ?: -1)
            .putString("l_windows", ConfigJson.windowsToJson(l.windows))
            .putLong("l_paused", l.pausedUntilMillis ?: -1L)
            .putLong("l_breakPass", l.breakPassUntilMillis ?: -1L)
            // Stored as written, unresolved. Every enforcement path below
            // reads its rules from this mirror rather than from the config, so
            // a scope that stopped at the config would be a switch a parent
            // turned on that changed nothing on the box doing the stopping.
            .putString("l_scope", l.budgetScope)
            .apply()
    }

    private fun limits(): Limits {
        fun get(key: String) = prefs.getInt(key, -1).takeIf { it >= 0 }
        return Limits(
            sessionMinutes = get("l_session"),
            weekdaySessions = get("l_wd"),
            weekendSessions = get("l_we"),
            breakMinutes = get("l_break"),
            windows = ConfigJson.windowsFromJson(prefs.getString("l_windows", null)),
            pausedUntilMillis = prefs.getLong("l_paused", -1L).takeIf { it > 0 },
            breakPassUntilMillis = prefs.getLong("l_breakPass", -1L).takeIf { it > 0 },
            budgetScope = prefs.getString("l_scope", null)
        )
    }

    /**
     * Parent timeout in force right now. Checked ahead of every other rule and
     * not waivable by grants or bedtime passes — only the parent's Resume (which
     * clears the field) or the deadline passing lifts it.
     */
    private fun isPaused(l: Limits): Boolean =
        System.currentTimeMillis() < (l.pausedUntilMillis ?: 0L)

    /**
     * Parent's "skip the next break", still unspent. One break only, per
     * device: the first break it waives writes the pass's own timestamp as
     * spent, so the same pass never covers a second one here. Expires at
     * midnight on its own (it's set to end-of-today and scrubbed on load).
     */
    private fun breakPassActive(l: Limits): Boolean {
        val pass = l.breakPassUntilMillis ?: return false
        return System.currentTimeMillis() < pass && prefs.getLong("breakPassSpent", 0) != pass
    }

    private fun spendBreakPass(l: Limits) {
        prefs.edit().putLong("breakPassSpent", l.breakPassUntilMillis ?: return).apply()
    }


    /** Daily watch budget in ms (incl. parent-granted bonus), or null when not configured. */
    private fun dailyBudgetMs(l: Limits): Long? = budgetMs(l, isWeekend(), bonusMs())

    /**
     * Minutes counted against today's budget: this device's own tally, plus
     * whatever peers have reported for the same kid and the same day.
     *
     * **The one and only read of `dailyWatchedMs`** outside the write in
     * [tick], and guard 45 in `scripts/check.*` holds it to that. There are
     * seven enforcement sites in this file and half a dozen screens showing a
     * number derived from them; one of them reading the raw tally while the
     * rest read this is a home screen promising forty minutes in front of a
     * player that stops at ten. That is the failure the accessor exists to
     * make impossible, and it is why the guard ships in the same commit.
     *
     * Enforcement stays local and immediate: a kid pressing play never waits
     * on a round trip, and the peers' figure is a prefs mirror rather than a
     * file read and a JSON parse, because [tick] runs on the main thread every
     * few seconds.
     *
     * [l] is passed rather than read here because every caller already holds
     * it, and because it is the rules — not the mirror — that decide whether
     * the peers' half counts at all.
     */
    private fun spentTodayMs(l: Limits): Long = spentMs(ownWatchedMs(), peerSpentMs(l))

    /**
     * This device's own tally, raw. The single reader of the store, which is
     * what guard 45 counts; the two callers that legitimately want the *own*
     * figure rather than the shared one are the write in [tick] and the
     * archive in [rolloverIfNewDay], because history is per device.
     */
    private fun ownWatchedMs(): Long = prefs.getLong("dailyWatchedMs", 0)

    /**
     * This device's own minutes today, and nothing learned from anyone.
     *
     * The figure the ledger cell this device authors is written from, and the
     * only caller that wants it: a cell that carried the *shared* total would
     * be read back by the peer that contributed half of it, added to that
     * peer's own live counter, and re-authored larger on the next round — a
     * budget that consumes itself in an afternoon, with every number on both
     * screens agreeing. Guard 49(c) keeps [watchedTodayMin] away from that
     * call site.
     */
    fun ownWatchedTodayMin(): Int {
        rolloverIfNewDay()
        return (ownWatchedMs() / 60_000L).toInt()
    }

    /**
     * What other devices have reported for this kid today, in ms — under a
     * shared budget, and zero otherwise.
     *
     * The gate is [Limits.sharesBudget], not the mirror: the mirror is
     * grow-only within a day, so a family that turned sharing off would
     * otherwise keep paying for the minutes it counted before they did.
     * Under the default scope this is arithmetically the behaviour the app
     * has always had, which `SessionGuardBudgetTest` asserts rather than
     * assumes.
     */
    private fun peerSpentMs(l: Limits): Long = peerMs(l, peerMirrorMs())

    /**
     * The mirror as stored: what peers last reported, whether or not it
     * counts. Kept apart from [peerSpentMs] because the two answer different
     * questions — "what did they say" and "does it apply here" — and only the
     * second may look at the rules. [notePeerMinutes] raises this one.
     */
    private fun peerMirrorMs(): Long = prefs.getLong("peerMs", 0)

    /**
     * Take a peers' total for today, from a merged [UsageLedger].
     *
     * `max`, never a plain write: cells are grow-only, and a mirror that could
     * fall would hand a kid minutes back every time a peer went quiet. Cleared
     * by the day rollover, so a stale figure can never outlive its day.
     *
     * Called by `UsageSync.mirror` and only under a shared budget. The rules
     * are checked *again* on the read side, deliberately: this store outlives
     * a parent turning sharing back off, and a mirror that had been allowed to
     * keep counting would go on charging a kid for their sibling's television
     * until midnight.
     */
    fun notePeerMinutes(minutes: Int) {
        rolloverIfNewDay()
        val ms = minutes.coerceAtLeast(0) * 60_000L
        if (ms > peerMirrorMs()) prefs.edit().putLong("peerMs", ms).apply()
    }

    // The two bonus stores are each read in exactly one place — guard 16 in
    // scripts/check.* holds it to that — so the LAN path and the config path
    // can never be summed differently by two callers.
    private fun legacyBonusMs(): Long = prefs.getLong("bonusMs", 0)
    private fun knownGrants(): List<Grant> = ConfigJson.grantsFromJson(prefs.getString("grants", null))
    private fun bonusMs(): Long = bonusMs(legacyBonusMs(), knownGrants())

    /**
     * Parent grant from a build that predates grants in the config (`POST
     * /grant` with no id): adds minutes to today's budget, ends any break
     * lock, starts a fresh sitting, and waives every blocked window for the
     * granted minutes. Resets at midnight.
     */
    fun grantExtraMinutes(minutes: Int) {
        rolloverIfNewDay()
        prefs.edit()
            .putLong("bonusMs", legacyBonusMs() + minutes * 60_000L)
            .apply()
        lift(minutes)
    }

    /**
     * Take the config's grants for this kid — the same effects as
     * [grantExtraMinutes], counted by id. Idempotent: a grant the LAN fast
     * path already delivered adds nothing when the config carrying it lands,
     * and vice versa, so the two paths can never disagree about today's
     * budget. Other days are ignored outright — expiry belongs to the
     * stamper (the phone's next save tombstones them) and to midnight here.
     * Returns what was new, so the caller can tell the kid.
     */
    fun applyGrants(grants: List<Grant>): List<Grant> {
        rolloverIfNewDay()
        val today = FamilyDay.of(System.currentTimeMillis())
        val fresh = Grants.unseen(knownGrants(), grants.filter { it.date == today }).distinctBy { it.id }
        if (fresh.isEmpty()) return fresh
        prefs.edit()
            .putString("grants", ConfigJson.grantsToJson(knownGrants() + fresh))
            .apply()
        lift(fresh.sumOf { it.minutes })
        return fresh
    }

    /** One grant, however it arrived. True when it was new here. */
    fun applyGrant(grant: Grant): Boolean = applyGrants(listOf(grant)).isNotEmpty()

    /**
     * What extra minutes buy beyond the budget: the break lock ends, a fresh
     * sitting starts, and every blocked window is waived for that long. The
     * pass runs from now, not from the tap — a TV that wakes ten minutes
     * after "20 more minutes" still gives the kid twenty, which is what the
     * parent meant.
     */
    private fun lift(minutes: Int) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong("lockUntil", 0)
            .putLong("sittingWatchedMs", 0)
            .putLong(
                "windowPassUntil",
                maxOf(prefs.getLong("windowPassUntil", 0), now + minutes * 60_000L)
            )
            .apply()
    }

    // ---- enforcement ----

    /**
     * Null if playback may start; otherwise a kid-friendly reason.
     * [multiplierPercent] is the source's screen-time drain rate: at 0 (FREE)
     * an exhausted budget doesn't block — but bedtime and break locks still do.
     * [listening] means sound-only with the screen off, which windows marked
     * "Allow listening" let through; everything else (the pause, the budget,
     * break locks, every other window) applies exactly as it does to watching.
     */
    fun checkStart(multiplierPercent: Int = 100, listening: Boolean = false): String? =
        checkStartInner(multiplierPercent, listening).also { reason ->
            // The enforced store + the rule state behind the verdict. Pairs with
            // the saveLimits line: matching suffixes and values = config applied;
            // anything else points straight at the broken link.
            android.util.Log.i("YosemiteKids",
                "checkStart[$profileSuffix listening=$listening] -> ${reason ?: "ok"} " +
                    "(break=${prefs.getInt("l_break", -1)} " +
                    "lockUntil=${prefs.getLong("lockUntil", 0)} " +
                    "sitting=${prefs.getLong("sittingWatchedMs", 0) / 60_000}m " +
                    "daily=${spentTodayMs(limits()) / 60_000}m)"
            )
        }

    private fun checkStartInner(multiplierPercent: Int, listening: Boolean): String? {
        rolloverIfNewDay()
        val l = limits()
        val now = System.currentTimeMillis()

        if (isPaused(l)) return PAUSED_MESSAGE
        activeWindow(l, listening)?.let { return windowMessage(l, it, listening) }

        val lockUntil = prefs.getLong("lockUntil", 0)
        if (l.breakMinutes == null) {
            // The break rule was turned off while a lock was armed — the parent
            // did that to end the lockout, so honor it now, not at midnight.
            // The sitting counter goes too: it only feeds the lock, and letting
            // it accumulate would spring an instant lock if the rule comes back.
            if (lockUntil != 0L || prefs.getLong("sittingWatchedMs", 0) != 0L) {
                prefs.edit().putLong("lockUntil", 0).putLong("sittingWatchedMs", 0).apply()
            }
        } else if (now < lockUntil) {
            // Skip mid-break lifts the running break — that's the one the
            // parent is looking at.
            if (breakPassActive(l)) {
                spendBreakPass(l)
                prefs.edit().putLong("lockUntil", 0).putLong("sittingWatchedMs", 0).apply()
            } else {
                return "Time for a break! You can watch again at ${timeOf(lockUntil)} ⏰"
            }
        }

        startFreshSittingAfterGap(l, now)

        if (multiplierPercent > 0) dailyBudgetMs(l)?.let { budget ->
            if (spentTodayMs(l) >= budget) {
                return "That's all the watching for today! 🌟"
            }
        }
        return null
    }

    /**
     * Called every few seconds while playback is actually running. Null to
     * continue; otherwise a kid-friendly reason to stop now. [listening] is
     * the caller's current mode, so a window marked "Allow listening" arriving
     * mid-story doesn't stop it — the player switches to sound-only instead.
     */
    /**
     * [multiplierPercent] is the playing source's drain rate: a FREE (0%)
     * source is exempt from the daily budget here exactly as it is in
     * [checkStart], [blockReason] and [remainingMs] — the sitting cap and the
     * windows still apply.
     */
    fun tick(deltaMs: Long, listening: Boolean = false, multiplierPercent: Int = 100): String? {
        rolloverIfNewDay()
        val l = limits()
        val now = System.currentTimeMillis()
        // A pause longer than the break inside the player is a break too —
        // the same rule checkStart and remainingMs apply, read against the
        // *previous* watch time, so it must run before that is overwritten.
        startFreshSittingAfterGap(l, now)
        prefs.edit().putLong("lastWatchAt", now).apply()

        // Mid-playback too: the pushed config lands, the next tick stops the video.
        if (isPaused(l)) return PAUSED_MESSAGE
        activeWindow(l, listening)?.let { return windowMessage(l, it, listening) }

        // The read half of the one write. Everything else in this file asks
        // spentTodayMs(); this is where the device's own tally is raised, and
        // the comparison below still goes through the summand, so the enforcer
        // and every screen work from one number.
        val own = ownWatchedMs() + deltaMs
        val sitting = prefs.getLong("sittingWatchedMs", 0) + deltaMs
        prefs.edit().putLong("dailyWatchedMs", own).putLong("sittingWatchedMs", sitting).apply()

        if (multiplierPercent > 0) dailyBudgetMs(l)?.let { budget ->
            if (spentMs(own, peerSpentMs(l)) >= budget) return "That's all the watching for today! 🌟"
        }
        // No break rule → nothing to arm; the sitting cap only exists to force
        // a rest of the configured length. The daily budget above still caps
        // the day, so this isn't unlimited watching.
        val breakLen = l.breakMinutes ?: return null
        val sittingCapMs = l.sessionMinutes?.let { it * 60_000L } ?: return null
        if (sitting >= sittingCapMs) {
            // The parent's skip: no lock, a fresh sitting, and the film plays
            // on. The daily budget above still caps the day.
            if (breakPassActive(l)) {
                spendBreakPass(l)
                prefs.edit().putLong("sittingWatchedMs", 0).apply()
                return null
            }
            prefs.edit().putLong("lockUntil", now + breakLen * 60_000L).apply()
            return "Time for a break! Great watching 🎉"
        }
        return null
    }

    /**
     * Wall-clock milliseconds of watching left before some rule will stop
     * playback, or null when no rule applies. Budget and sitting-cap remainders
     * are converted through the source's drain rate ([multiplierPercent]: at 50,
     * 10 budget-minutes last 20 real minutes; at 0 they never run out), while
     * bedtime distance is clock time and never scales. Drives the kid's
     * "5 minutes left" warning, so it must never say more time than tick() will
     * actually allow.
     */
    fun remainingMs(multiplierPercent: Int = 100, listening: Boolean = false): Long? =
        remainingAll(multiplierPercent, listening).minOfOrNull { it.ms }

    /**
     * The same answer, one entry per rule that could stop playback, each
     * saying which clock it counts on ([LimitKind]).
     *
     * [remainingMs] is the minimum of these, so the two can never disagree.
     * This is the shape the chrome needs: a caller that only knows the
     * minimum cannot age it correctly, because "hold the number while nothing
     * is playing" is right for the budget and wrong for bedtime. Prefs reads
     * and a possible day rollover write — call it off-main, on the cadence
     * the caller already has, and put [interpolateRemainingMs] in between.
     */
    fun remainingAll(multiplierPercent: Int = 100, listening: Boolean = false): List<Remaining> {
        rolloverIfNewDay()
        val l = limits()
        // A parent timeout is the whole answer: no other rule can give time
        // back while it stands, and nothing about it ticks.
        if (isPaused(l)) return listOf(Remaining(0, LimitKind.PAUSED))
        val candidates = mutableListOf<Remaining>()
        if (multiplierPercent > 0) {
            dailyBudgetMs(l)?.let { budget ->
                candidates += Remaining(
                    (budget - spentTodayMs(l))
                        .coerceAtLeast(0) * 100 / multiplierPercent,
                    LimitKind.BUDGET
                )
            }
            // The sitting cap only counts while it can actually lock (break
            // set, and no unspent skip waiting to waive it). A break-length
            // gap since the last watch means the next press starts a fresh
            // sitting (see startFreshSittingAfterGap) — read it that way here
            // too, or the home chip says "less than a minute" right after a
            // break the kid has fully served.
            // Bound locally: `breakMinutes` is now a public property of another
            // module, which Kotlin will not smart-cast across.
            val breakMins = l.breakMinutes
            if (breakMins != null && !breakPassActive(l)) l.sessionMinutes?.let { cap ->
                val gapMs = breakMins * 60_000L
                val lastWatch = prefs.getLong("lastWatchAt", 0)
                val sitting =
                    if (lastWatch > 0 && System.currentTimeMillis() - lastWatch >= gapMs) 0L
                    else prefs.getLong("sittingWatchedMs", 0)
                candidates += Remaining(
                    (cap * 60_000L - sitting).coerceAtLeast(0) * 100 / multiplierPercent,
                    LimitKind.SITTING
                )
            }
        }
        msUntilWindow(l, listening)?.let {
            candidates += Remaining(it.coerceAtLeast(0), LimitKind.WINDOW)
        }
        return candidates
    }

    /**
     * The blocked windows this device is enforcing, for the kid's own page to
     * name them. Read from the same store [checkStart] enforces from, so the
     * card on You can never list a window the TV is not actually applying.
     * A prefs read plus a JSON parse — off-main.
     */
    fun windows(): List<TimeWindow> = limits().windows

    /**
     * What would stop a play press right now, phrased for the kid, or null when
     * nothing would — the home screen's banner. A read-only twin of
     * [checkStart]: that one is a *play attempt* and spends a break pass, lifts
     * a lapsed lock and logs, none of which a screen that merely looks may do.
     */
    fun blockReason(multiplierPercent: Int = 100): String? {
        rolloverIfNewDay()
        val l = limits()
        val now = System.currentTimeMillis()
        if (isPaused(l)) return PAUSED_MESSAGE
        activeWindow(l)?.let { return windowMessage(l, it) }
        val lockUntil = prefs.getLong("lockUntil", 0)
        if (l.breakMinutes != null && now < lockUntil && !breakPassActive(l)) {
            return "Time for a break! You can watch again at ${timeOf(lockUntil)} ⏰"
        }
        if (multiplierPercent > 0) dailyBudgetMs(l)?.let { budget ->
            if (spentTodayMs(l) >= budget) {
                return "That's all the watching for today! 🌟"
            }
        }
        return null
    }

    /** Clock ms until the next window closes playback, or null when there are none. */
    private fun msUntilWindow(l: Limits, listening: Boolean): Long? {
        val windows = liveWindows(l, listening)
        if (windows.isEmpty()) return null
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (TimeWindows.activeAt(windows, day, nowMin) != null) {
            // Inside a window only a grant keeps playback alive, and only for
            // the minutes it bought.
            (prefs.getLong("windowPassUntil", 0) - now).coerceAtLeast(0)
        } else {
            (TimeWindows.minutesUntilNextStart(windows, day, nowMin) ?: return null) * 60_000L
        }
    }

    /** A break-length gap since last watching starts a new sitting (nothing lost).
     *  No break rule → no sitting rhythm to reset (checkStart zeroes it instead). */
    private fun startFreshSittingAfterGap(l: Limits, now: Long) {
        val gapMs = l.breakMinutes?.let { it * 60_000L } ?: return
        val lastWatch = prefs.getLong("lastWatchAt", 0)
        if (lastWatch > 0 && now - lastWatch >= gapMs) {
            prefs.edit().putLong("sittingWatchedMs", 0).apply()
        }
    }

    // ---- clock helpers ----

    /**
     * Roll the day's counters when the day has actually moved on.
     *
     * The day is [FamilyDay]'s, in the compact `yyyyMMdd` spelling this store
     * has always used, so no install needs a prefs migration — and it goes
     * through [FamilyDay.rollover], which keeps the **later** of the stored
     * day and the clock's.
     *
     * That is a fix, not a tidy-up. This read `if (previous != today)`, so a
     * clock that stepped *backwards* a day — a television that lost NTP and
     * came back guessing, a child who found the date setting — zeroed
     * `dailyWatchedMs` and handed out a whole second budget, today, in the
     * shipped device-local mode. Rolling forward only costs nothing (a day
     * that really has passed still rolls, once) and it is a prerequisite for
     * any shared count: a device that walked its own day backwards would
     * author ledger cells under yesterday and read today's aggregate as empty.
     */
    private fun rolloverIfNewDay() {
        val previous = prefs.getString("day", null)
        val today = FamilyDay.rollover(
            previous, FamilyDay.compact(FamilyDay.of(System.currentTimeMillis()))
        )
        if (previous != today) {
            // Archive the finished day before clearing, so trends have history.
            val watched = ownWatchedMs()
            if (previous != null && watched > 0) {
                val history = (prefs.getString("history", "") ?: "")
                    .lines().filter { it.isNotBlank() }
                    .takeLast(59) // keep ~60 days
                prefs.edit()
                    .putString("history", (history + "$previous=${watched / 60_000}").joinToString("\n"))
                    .apply()
            }
            prefs.edit()
                .putString("day", today)
                .putLong("dailyWatchedMs", 0)
                // Yesterday's peers are yesterday's too: the mirror is a sum
                // for one day, and a stale one would quietly spend a new day's
                // budget before the kid touched anything.
                .putLong("peerMs", 0)
                .putLong("sittingWatchedMs", 0)
                .putLong("lockUntil", 0)
                .putLong("bonusMs", 0)
                // Yesterday's grants are yesterday's; the config's copies
                // expire on the phone's next save, and applyGrants ignores
                // any other day, so nothing can put them back here.
                .remove("grants")
                .putLong("windowPassUntil", 0)
                .putLong("breakPassSpent", 0)
                .apply()
        }
    }

    /**
     * Minutes left in today's budget under [l] (limits straight from the
     * config, so the who's-watching tiles don't depend on this profile having
     * been active since the last rules push). Null when no budget is set.
     */
    /**
     * Today's allowance in minutes: sessions × length, plus any bonus granted
     * today. Null when no limit is set.
     *
     * Public so the settings root can draw "used of budget" from the same
     * numbers [remainingTodayMin] enforces. The root card once derived "used"
     * as total − remaining with a total that ignored the bonus, and read
     * "110 min left today" over "0 of 90 min used" the moment a parent granted
     * twenty minutes.
     *
     * [grants] are the config's for this kid ([Whitelist.grantsFor]); a caller
     * holding the config passes them so a device that has only just synced
     * counts them here and now, not on its next refresh.
     */
    fun dailyBudgetMin(l: Limits, grants: List<Grant> = emptyList()): Int? {
        applyGrants(grants)
        return budgetMs(l, isWeekend(), bonusMs())?.let { (it / 60_000L).toInt() }
    }

    /**
     * Minutes counted against this kid's budget today, bonus or not — the same
     * number the enforcement above works from, so no screen can promise time
     * the player will not give.
     */
    fun watchedTodayMin(): Int {
        rolloverIfNewDay()
        return (spentTodayMs(limits()) / 60_000L).toInt()
    }

    fun remainingTodayMin(l: Limits, grants: List<Grant> = emptyList()): Int? {
        applyGrants(grants)
        if (isPaused(l)) return 0
        val budget = budgetMs(l, isWeekend(), bonusMs()) ?: return null
        return ((budget - spentTodayMs(l)).coerceAtLeast(0) / 60_000L).toInt()
    }

    /** yyyyMMdd → minutes watched, for the trend chart (excludes today). */
    fun history(): List<Pair<String, Int>> =
        (prefs.getString("history", "") ?: "")
            .lines().filter { it.isNotBlank() }
            .mapNotNull { line ->
                val (day, mins) = line.split('=').let {
                    if (it.size == 2) it[0] to it[1].toIntOrNull() else null to null
                }
                if (day != null && mins != null) day to mins else null
            }

    /** Everything the phone's stats screen needs about screen time. */
    data class Snapshot(
        val watchedTodayMin: Int,
        val budgetTodayMin: Int?,
        val bonusTodayMin: Int,
        val sittingWatchedMin: Int,
        val sittingCapMin: Int?,
        val state: String,
        val breakUntil: String?
    )

    fun snapshot(): Snapshot {
        rolloverIfNewDay()
        val l = limits()
        val now = System.currentTimeMillis()
        // A lock left over from before the break rule was disabled is dead
        // (checkStart clears it on the next play attempt) — don't report it.
        val lockUntil = if (l.breakMinutes == null) 0 else prefs.getLong("lockUntil", 0)
        val budget = dailyBudgetMs(l)
        val watched = spentTodayMs(l)
        val blocking = activeWindow(l)
        val state = when {
            isPaused(l) -> "Paused by parent"
            // The parent's stats screen shows this next to what's playing, so a
            // bedtime that lets a story through has to say so — otherwise it
            // reads as "Bedtime" while the kid is audibly still listening.
            blocking != null ->
                if (blocking.allowListening) "${blocking.label} (listening)" else blocking.label
            budget != null && watched >= budget -> "Daily limit reached"
            now < lockUntil -> "On a break"
            else -> "Can watch"
        }
        return Snapshot(
            watchedTodayMin = (watched / 60_000).toInt(),
            budgetTodayMin = budget?.let { (it / 60_000).toInt() },
            // Through the one sum, or the stats screen would count the LAN
            // grants and not the config-carried ones (guard 16).
            bonusTodayMin = (bonusMs() / 60_000).toInt(),
            sittingWatchedMin = (prefs.getLong("sittingWatchedMs", 0) / 60_000).toInt(),
            sittingCapMin = l.sessionMinutes,
            state = state,
            breakUntil = lockUntil.takeIf { it > now }?.let { timeOf(it) }
        )
    }

    private fun isWeekend(): Boolean {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY
    }

    /**
     * Windows still in force: a parent's pass takes one out for its occurrence,
     * and while [listening] (sound only, screen off) the ones marked "Allow
     * listening" don't apply — see [TimeWindow.allowListening].
     */
    private fun liveWindows(l: Limits, listening: Boolean = false): List<TimeWindow> {
        val now = System.currentTimeMillis()
        return l.windows.filter {
            now >= (it.passUntilMillis ?: 0L) && !(listening && it.allowListening)
        }
    }

    /**
     * A window that blocks watching right now but lets sound-only playback
     * through, or null — either nothing is blocking, or what blocks isn't
     * waivable this way. An overlapping strict window wins: the stricter rule
     * is the one a parent means when two cover the same minute.
     *
     * Drives the player's switch into listening: what this returns non-null
     * for is exactly what [checkStart] refuses at 100% but allows while
     * listening.
     */
    fun listenOnlyWindow(): TimeWindow? {
        rolloverIfNewDay()
        val l = limits()
        if (isPaused(l)) return null
        val blocking = activeWindow(l) ?: return null
        return blocking.takeIf { activeWindow(l, listening = true) == null }
    }

    /** The window blocking playback right now, or null. */
    private fun activeWindow(l: Limits, listening: Boolean = false): TimeWindow? {
        // A grant waives every window for the minutes it bought — a parent
        // handing out 20 more minutes at 19:40 means them, not "except bedtime".
        if (System.currentTimeMillis() < prefs.getLong("windowPassUntil", 0)) return null
        val cal = Calendar.getInstance()
        return TimeWindows.activeAt(
            liveWindows(l, listening),
            cal.get(Calendar.DAY_OF_WEEK),
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        )
    }

    /**
     * Names the window and says when it lifts — "when can I watch again" is the
     * question a blocked kid actually has, and the reopening time answers it
     * even when the label ("School hours") wouldn't.
     */
    private fun windowMessage(l: Limits, w: TimeWindow, listening: Boolean = false): String {
        val cal = Calendar.getInstance()
        val mins = TimeWindows.blockedForMin(
            liveWindows(l, listening),
            cal.get(Calendar.DAY_OF_WEEK),
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        )
        val label = w.label.trim().ifEmpty { "Quiet time" }
        val emoji = if (label.equals("bedtime", ignoreCase = true)) "🌙" else "⏰"
        // Overlapping windows chain, so the time comes from the chain, not from
        // this window's own end — otherwise we'd promise a reopening that the
        // very next window immediately takes back.
        return if (mins == null) "It's ${label.lowercase(Locale.getDefault())} right now $emoji"
        else "It's ${label.lowercase(Locale.getDefault())} — " +
            "you can watch again at ${timeOf(System.currentTimeMillis() + mins * 60_000L)} $emoji"
    }

    /**
     * The household's own clock convention: a 24-hour home reads "19:30", not
     * "7:30 PM" — the kid is learning to read the clock on the wall, and this
     * should match it.
     */
    private fun timeOf(epochMs: Long): String {
        val pattern = if (android.text.format.DateFormat.is24HourFormat(appContext)) "H:mm" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epochMs))
    }
}
