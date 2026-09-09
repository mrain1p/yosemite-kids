package io.yosemitekids.hub

import io.yosemitekids.app.data.Budget
import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.FamilyDay
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.Screening
import io.yosemitekids.app.data.ScreeningRules
import io.yosemitekids.app.data.ScreeningStore
import io.yosemitekids.app.data.TimeWindows
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry

/**
 * "May this child play this video, here, now?" — the hub's whole answer, in
 * one call, for a caller that has no [io.yosemitekids.app.data.SessionGuard].
 *
 * A device answers this for itself and always has. The browser cannot: it
 * holds no config, no verdicts, no ledger and no rules, and everything it
 * could be told it could also be told to ignore. So the box decides, and
 * [mayPlay] re-derives **everything** from what this hub already holds. A page
 * calls exactly this and computes nothing of its own; anything it were allowed
 * to compute would be a rule with two implementations, and the one a child's
 * browser runs is the one nobody can inspect.
 *
 * **Not one rule here is new.** Blocks are `Whitelist.isBlockedFor`, per-kid
 * visibility is `WhitelistEntry.visibleTo`, parent overrides are
 * `Whitelist.allowedIdsFor`, screening is `Screening.isVisible` in `:crawl`
 * (guard 46), windows are `TimeWindows.activeAt`, the budget is `Budget` in
 * `:core` (guard 50), and the minutes are the ledger's. A second copy of any
 * of them would not throw and would not log — it would let a video the
 * television hides play on a tablet, and what a parent reports is "the filter
 * does not work", with nothing anywhere to say which of the two answered.
 *
 * **It stops nobody watching anything by itself.** This is a verdict, not an
 * enforcer: the hub cannot reach into a television, and a device's own
 * `SessionGuard` remains the thing that stops a child mid-video (D4). What
 * this exists for is the one viewer that has no such thing.
 *
 * ### Failing closed, and saying why
 *
 * A container's clock is UTC and a family's is not, so this box may read
 * exactly one calendar: the one a parent named in `Whitelist.homeZone` (guard
 * 27). Without it the hub does not know what day it is *here* — and every time
 * rule is about here. It therefore **refuses** a kid who has a bedtime or a
 * budget configured, with [NEEDS_HOME_ZONE], rather than guessing UTC and
 * being thirteen hours wrong for half of every day in Auckland. A kid with no
 * time rules at all is unaffected: there is nothing a day would decide.
 *
 * Refusing is the unusual choice and it is deliberate. The alternative — allow
 * and log — reads as working right up until a bedtime silently stops applying,
 * which is the failure a parent would actually be hurt by; and unlike a device
 * the hub has no honest fallback zone of its own to reach for.
 */
class HubPolicy(
    private val store: HubStore,
    private val usage: HubUsage,
    private val screening: ScreeningStore,
    private val index: ChannelIndex,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Why the answer was what it was.
     *
     * [reason] is a stable machine code and [detail] is a sentence for a
     * parent's log — never handed to a child as it stands, because the words a
     * child reads belong with the page that knows their name.
     *
     * The three numbers are what the decision was *reached with*, and they are
     * null exactly when nothing was resolved: no day when the hub could not
     * name one, no minutes and no budget when no budget applied. That is what
     * makes the fail-closed branch checkable — a refusal that had quietly
     * resolved a day anyway would be a hub reading a calendar it may not.
     */
    data class Decision(
        val allowed: Boolean,
        val reason: String,
        val detail: String,
        val day: String? = null,
        val spentMinutes: Int? = null,
        val budgetMinutes: Int? = null
    )

    companion object {
        const val OK = "ok"
        const val NO_CONFIG = "no-config"
        const val UNKNOWN_VIDEO = "unknown-video"
        const val NOT_FOR_THIS_KID = "not-for-this-kid"
        const val BLOCKED = "blocked"
        const val TOO_SHORT = "too-short"
        const val NOT_SCREENED = "not-screened"

        /**
         * The fail-closed code, and the only one that means "this hub cannot
         * answer" rather than "no". A caller renders it as *set your home
         * timezone*, because that is the single action that fixes it.
         */
        const val NEEDS_HOME_ZONE = "needs-home-zone"
        const val PAUSED = "paused"
        const val WINDOW = "window"
        const val OUT_OF_TIME = "out-of-time"
    }

    /**
     * The whole decision for one kid and one video.
     *
     * [viewer] is the ledger id of whoever is asking — for a browser, the id
     * the watch meter counts its minutes under. It matters only under the
     * default, per-device budget, where it names whose cell to hold against
     * the limit. **A null viewer there falls back to the family's whole
     * total**, which is the strictest reading this box can stand behind: with
     * no way to attribute the minutes, counting none would hand out time the
     * rules never allowed, and inventing time is the one direction a
     * child-safety decision must not fail in. Under a shared budget the viewer
     * is irrelevant — every device counts.
     *
     * The id is never taken from a page: a route derives it from the session
     * it minted, or a browser would simply claim a fresh one each morning.
     */
    fun mayPlay(kidId: String?, videoId: String, viewer: String? = null): Decision {
        val config = runCatching { store.load() }.getOrNull()
            ?: return no(NO_CONFIG, "this hub holds no family config it can read")
        val limits = config.limitsFor(kidId)

        // --- what: the catalogue half, which needs no calendar -----------
        catalogue(config, limits, kidId, videoId)?.let { return it }

        // --- when: the clock half ----------------------------------------
        return timeFor(kidId, viewer)
    }

    /**
     * The clock half of [mayPlay], on its own: pauses, bedtimes, and what is
     * left of today's minutes — for a caller that has no particular video in
     * mind.
     *
     * The browser's home screen needs exactly this. It draws the countdown
     * ("20 minutes left today") before a child has picked anything, and it must
     * be the same answer the play route will give, from the same rules — a page
     * that says *nine minutes* and a hub that refuses at eight is a child being
     * lied to by their own screen.
     *
     * An allowed [Decision] here carries the numbers; a refused one carries the
     * sentence. The catalogue half is deliberately not consulted: a home screen
     * has no video to judge.
     */
    fun timeFor(kidId: String?, viewer: String? = null): Decision {
        val config = runCatching { store.load() }.getOrNull()
            ?: return no(NO_CONFIG, "this hub holds no family config it can read")
        return clock(config, config.limitsFor(kidId), kidId, viewer)
    }

    private fun clock(
        config: Whitelist,
        limits: Limits,
        kidId: String?,
        viewer: String?
    ): Decision {
        // A parent's pause first, and outside the zone gate below on purpose:
        // it is an instant on the wall clock, compared against another
        // instant, and answering it needs no calendar at all. A hub that
        // refused to honour a pause because nobody had set a timezone would be
        // failing OPEN on the strictest rule the app has.
        if (now() < (limits.pausedUntilMillis ?: 0L)) {
            return no(PAUSED, "a parent has paused watching")
        }

        val zone = FamilyDay.zoneOrNull(config.homeZone)
        if (zone == null) {
            if (limits.windows.isNotEmpty() || Budget.configured(limits)) {
                return no(
                    NEEDS_HOME_ZONE,
                    "this kid has a bedtime or a daily budget and nobody has set the " +
                        "family's home timezone, so this hub cannot tell what day it is here"
                )
            }
            // Nothing left that a day would decide.
            return yes("no time rules apply to this kid")
        }

        val day = FamilyDay.of(now(), zone)
        val (dayOfWeek, minuteOfDay) = FamilyDay.clockAt(now(), zone)

        TimeWindows.activeAt(limits.windows, dayOfWeek, minuteOfDay)?.let { w ->
            return no(WINDOW, "${w.label} is on now", day = day)
        }

        // Grants ride the config, so a parent's "20 more minutes" reaches this
        // box the same way it reaches a sleeping television — and by the same
        // date-as-text rule, so two faces in two zones agree which taps are
        // today's without agreeing when today started.
        val bonus = Budget.bonusMs(0L, config.grantsFor(kidId, day))
        val budget = Budget.dayMs(limits, Budget.isWeekend(dayOfWeek), bonus)
            ?: return yes("no daily budget is set", day = day)

        val spent = spentMinutes(kidId, day, limits, viewer)
        val budgetMin = (budget / 60_000L).toInt()
        if (spent * 60_000L >= budget) {
            return no(
                OUT_OF_TIME, "$spent of $budgetMin minutes used today",
                day = day, spent = spent, budgetMin = budgetMin
            )
        }
        return yes(
            "${budgetMin - spent} minutes left today",
            day = day, spent = spent, budgetMin = budgetMin
        )
    }

    /**
     * The rules about *what* — blocks, whose list it is on, how long it is,
     * and whether it has been screened. Null when none of them refuses.
     *
     * Every one is a shared predicate called with what this hub already holds.
     */
    private fun catalogue(
        config: Whitelist,
        limits: Limits,
        kidId: String?,
        videoId: String
    ): Decision? {
        if (config.isBlockedFor(videoId, kidId)) {
            return no(BLOCKED, "a parent has blocked this video")
        }
        // Visible sources first, so a video that is on two lists is judged on
        // the one this kid can actually see. Only if it is nowhere in those do
        // we look wider, and then only to tell "not yours" from "never heard
        // of it" — two answers a parent needs to be able to tell apart.
        val hit = locate(config.sources.filter { it.visibleTo(kidId) }, videoId)
            ?: return if (locate(config.sources, videoId) != null) {
                no(NOT_FOR_THIS_KID, "this channel is not on this kid's list")
            } else {
                no(UNKNOWN_VIDEO, "this hub has never indexed that video")
            }
        val (entry, indexed) = hit
        return rowRefusal(limits, entry, indexed, config.allowedIdsFor(kidId), config, kidId)
    }

    /**
     * The two rules that are about a *row* rather than about the list it is on:
     * how long it is, and whether screening has cleared it. Null when neither
     * refuses.
     *
     * Split out of [catalogue] so [catalogueFor] can apply the identical test to
     * every row of every source without a second spelling of it. The block
     * check stays in [catalogue]: it is keyed by video id against the config,
     * and the browse path answers it in bulk from one set.
     *
     * [allowedOverrides] is passed in rather than read here because
     * `Whitelist.allowedIdsFor` walks the whole config, and doing that once per
     * video would turn a fifty-channel browse into fifty thousand walks.
     */
    private fun rowRefusal(
        limits: Limits,
        entry: WhitelistEntry,
        indexed: ChannelIndex.IndexedVideo,
        allowedOverrides: Set<String>,
        config: Whitelist,
        kidId: String?
    ): Decision? {
        // The same rule the app applies: an unknown (0) duration passes — a
        // live stream, or a row indexed before durations were stored, is not a
        // clip.
        val minSeconds = (limits.minVideoMinutes ?: 0) * 60L
        if (minSeconds > 0 && indexed.durationSeconds in 1 until minSeconds) {
            return no(TOO_SHORT, "shorter than this kid's minimum video length")
        }

        val rules = ScreeningRules(
            config = config.ai,
            allowedOverrides = allowedOverrides,
            // One entry, for the one channel in question: `Screening.isVisible`
            // looks the note up by channel name, and the located source is the
            // only one whose note could apply. Building the whole map would
            // need the resolved names of every source, which this box does not
            // hold — and would answer the same question.
            channelNotes = entry.aiNote?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { mapOf(indexed.channelName to it) }.orEmpty(),
            activeProfileId = kidId
        )
        if (!Screening.isVisible(screening, rules, indexed.toVideo())) {
            return no(NOT_SCREENED, "screening has not cleared this video for this kid")
        }
        return null
    }

    /** One channel a kid may see, and the rows on it that survived every catalogue rule. */
    data class VisibleSource(
        val entry: WhitelistEntry,
        val videos: List<ChannelIndex.IndexedVideo>
    )

    /**
     * Everything [kidId] may browse: their visible channels, each with the rows
     * that would pass [mayPlay]'s catalogue half.
     *
     * **The browse answer and the play answer are the same rules.** A shelf
     * built from a looser filter is a child tapping a card and being told no,
     * which is the worst of both — they saw it, and they cannot have it. So
     * this walks the identical predicates: `visibleTo`, `isBlockedFor`, the
     * minimum length, and `Screening.isVisible`, via [rowRefusal].
     *
     * What it deliberately does **not** apply is the clock. Bedtime and a spent
     * budget stop *playing*, not browsing, and a home screen that emptied
     * itself at seven o'clock would read as broken rather than as bedtime — the
     * page says the sentence instead, from [mayPlay]'s reason when a card is
     * actually pressed.
     */
    fun catalogueFor(kidId: String?): List<VisibleSource> {
        val config = runCatching { store.load() }.getOrNull() ?: return emptyList()
        val limits = config.limitsFor(kidId)
        val allowed = config.allowedIdsFor(kidId)
        return config.sources.filter { it.visibleTo(kidId) }.map { entry ->
            val rows = index.loadSource(entry.id).filter { indexed ->
                val id = indexed.videoId
                !config.isBlockedFor(id, kidId) &&
                    rowRefusal(limits, entry, indexed, allowed, config, kidId) == null
            }
            VisibleSource(entry, rows)
        }
    }

    /** The whitelisted source [videoId] was indexed under, and the row itself. */
    private fun locate(
        sources: List<WhitelistEntry>,
        videoId: String
    ): Pair<WhitelistEntry, ChannelIndex.IndexedVideo>? {
        sources.forEach { src ->
            index.loadSource(src.id).firstOrNull { it.videoId == videoId }?.let { return src to it }
        }
        return null
    }

    /**
     * Minutes counted against this kid's budget: every device under a shared
     * budget, and one viewer's own cell under the default one.
     *
     * `Limits.sharesBudget` and not a test against a value, so a scope this
     * build has never heard of behaves as the family's devices already do
     * (guard 49).
     */
    private fun spentMinutes(kidId: String?, day: String, limits: Limits, viewer: String?): Int =
        if (limits.sharesBudget || viewer == null) usage.minutesFor(kidId, day)
        else usage.minutesFor(kidId, day, viewer)

    private fun no(
        reason: String,
        detail: String,
        day: String? = null,
        spent: Int? = null,
        budgetMin: Int? = null
    ) = Decision(false, reason, detail, day, spent, budgetMin)

    private fun yes(
        detail: String,
        day: String? = null,
        spent: Int? = null,
        budgetMin: Int? = null
    ) = Decision(true, OK, detail, day, spent, budgetMin)
}
