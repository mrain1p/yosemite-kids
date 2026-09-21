package io.yosemitekids.app.data

import java.util.Calendar

/**
 * How many milliseconds of watching a kid's rules allow in a day.
 *
 * Three lines of arithmetic, and they live here for the reason
 * `Screening.isVisible` lives in `:crawl`: **two faces now answer the same
 * question.** A device asks it through `SessionGuard`, and the hub asks it of
 * a browser that has no `SessionGuard` at all. A second copy of this would not
 * throw and would not log — it would let a kid watch on for ten minutes in one
 * place after being stopped in another, and what a parent reports is "the
 * limit does not work", with nothing anywhere to say which of the two
 * answered. Guard 50 holds the hub to this one.
 *
 * Deliberately clockless. Whether today is a weekend is a fact about a
 * *calendar*, and the two faces do not have the same one — a device has its
 * own zone and a container has none it may believe (guard 27). So the day of
 * the week arrives as a number and this file reads no clock, exactly as
 * `ConfigMerge` and `UsageLedger` do not.
 */
object Budget {

    /**
     * The day's allowance: sittings × length, plus [bonusMs]. Null when the
     * parent has set no budget — and null means **no rule**, never zero.
     *
     * Both halves are required. A session length with no session count is not
     * a budget of one sitting; it is a rule the settings screen has not
     * finished collecting, and guessing at the missing half would invent a
     * limit a parent never set.
     */
    fun dayMs(l: Limits, weekend: Boolean, bonusMs: Long): Long? {
        val perSession = l.sessionMinutes ?: return null
        val count = (if (weekend) l.weekendSessions else l.weekdaySessions) ?: return null
        return perSession * count * 60_000L + bonusMs
    }

    /**
     * Today's extra time from both places it can come from: the legacy LAN
     * grant (an older phone, a plain number of ms — always 0 on the hub, which
     * has never had one) and the grants the config carries, taken by id.
     *
     * One number, so the settings root, the stats screen, the enforcement
     * paths and the hub cannot disagree about it.
     */
    fun bonusMs(legacyBonusMs: Long, grants: List<Grant>): Long =
        legacyBonusMs + grants.sumOf { it.minutes } * 60_000L

    /**
     * Today, as the four numbers anything drawing a day is made of.
     *
     * Milliseconds throughout, and that is the whole point of the type. The
     * floor where a duration becomes a whole minute is the ledger's — see
     * `UsageLedger`'s cell, "whole **counted** minutes, rounded down" — and
     * every number above it should stay exact. The phone's own half is exact
     * to the second and a minutes-shaped return would destroy it; the hub
     * carries a sub-minute remainder of its own. Rounding is a thing a face
     * does at the moment it writes a label, once, at the edge.
     *
     * ### Why this exists at all
     *
     * [dayMs] fuses base and bonus in its last expression and every caller
     * throws the split away on the next line, so nothing anywhere could say
     * "40 minutes, and 15 of them a grown-up gave you" without deriving it
     * again. Two faces had already started: the hub recomputed base and bonus
     * inside its policy and discarded both, and the parent console had a
     * *JavaScript* copy of [bonusMs] keyed off the browser's own clock. That
     * is the drift this module exists to prevent, and it was already shipped.
     *
     * ### What it will not take
     *
     * No clock, no zone, no `today: String`. The day of the week arrives as a
     * number and the grants arrive already narrowed to this kid and this day
     * by `Whitelist.grantsFor`, because a day key is a clock wearing a
     * different hat — guard 44 names that exact parameter as the one that
     * would sail past the clockless check at the top of the gate.
     *
     * No ledger either. Whose minutes count is [Limits.sharesBudget]'s
     * question and it is answered in one place; a ledger here would need a
     * device identity and a second answer to it.
     *
     * And no pause. Whether a child may watch *right now* is an enforcement
     * decision made against an instant, with three other candidates beside it
     * (the sitting cap, a bedtime window, a parent's pause), and the faces
     * already take the minimum. This is the arithmetic of the day, which is
     * true whether or not anyone is watching.
     */
    data class Today(
        /** Sittings × length. Never null here: [today] returns null for "no rule". */
        val baseMs: Long,
        /** Grants, plus the legacy LAN bonus. */
        val bonusMs: Long,
        /** Counted and post-multiplier, as the ledger keeps it. */
        val spentMs: Long
    ) {
        /** What the day allows in all — exactly what [dayMs] returns. */
        val budgetMs: Long get() = baseMs + bonusMs

        /** What is left of it, never below zero. */
        val leftMs: Long get() = (budgetMs - spentMs).coerceAtLeast(0L)

        /**
         * How much of the day is gone, 0.0..1.0.
         *
         * Here and not in a face, because a fraction is arithmetic: a page
         * that divided for itself would round differently from the hub's own
         * cut-off and draw a full bar while the video still played.
         */
        val spentFraction: Double
            get() = if (budgetMs <= 0L) 0.0 else (spentMs.toDouble() / budgetMs).coerceIn(0.0, 1.0)

        /**
         * How much of the day a grown-up added, 0.0..1.0 of the whole.
         *
         * Bonus extends the scale rather than sitting inside it — two phones
         * can each grant and both land, so base+bonus has no ceiling. A bar
         * drawn from these two fractions is therefore always the whole day,
         * with the added part visible as a share of it.
         */
        val bonusFraction: Double
            get() = if (budgetMs <= 0L) 0.0 else (bonusMs.toDouble() / budgetMs).coerceIn(0.0, 1.0)
    }

    /**
     * [Today] for one kid, from what a face already holds.
     *
     * @param limits the resolved rules — `Whitelist.limitsFor(kidId)`.
     * @param dayOfWeek `Calendar`'s convention, Sunday = 1, as
     *   `FamilyDay.clockAt` produces it. Taken rather than a Boolean so the
     *   last weekend test leaves the faces too.
     * @param legacyBonusMs the old LAN grant's prefs value; 0 on the hub,
     *   which has never had one.
     * @param grantsToday already narrowed to this kid and this day.
     * @param spentMs summed by whoever owns the counter, because each face
     *   owns a different one: the phone adds a live sub-minute tally to a
     *   peers' mirror, the hub adds its meter's unsettled remainder to the
     *   ledger's whole minutes.
     * @return null when there is no budget at all, which is **not** zero: a
     *   family with no rule draws no bar, rather than an empty one.
     */
    fun today(
        limits: Limits,
        dayOfWeek: Int,
        legacyBonusMs: Long,
        grantsToday: List<Grant>,
        spentMs: Long
    ): Today? {
        val bonus = bonusMs(legacyBonusMs, grantsToday)
        val budget = dayMs(limits, isWeekend(dayOfWeek), bonus) ?: return null
        return Today(baseMs = budget - bonus, bonusMs = bonus, spentMs = spentMs.coerceAtLeast(0L))
    }

    /**
     * Whether a budget binds on **any** day of the week.
     *
     * "Has this family set a limit at all", asked without knowing what day it
     * is — which is the one question a container can ask honestly before it
     * knows the family's zone. Through [dayMs] rather than by re-testing the
     * fields, so it cannot answer yes to a rule [dayMs] would call incomplete.
     */
    /**
     * Whether any rule in this family depends on what day it is here.
     *
     * A bedtime window and a daily budget both do: "today" has to mean one
     * thing on the television, the phone and the hub, or a child's evening
     * ends at a different moment on each. That is what `Whitelist.homeZone`
     * is for - and until a rule of this kind exists there is nothing for it
     * to decide, which is why it is filled in at the moment one appears
     * rather than offered as a switch nobody would go looking for
     * (SettingsSurface's own note on the field).
     *
     * The hub fails closed without it (HubPolicy.NEEDS_HOME_ZONE) because a
     * container's clock is not a household's; a phone knows its own zone, so
     * the phone is what stamps it.
     */
    fun dayMatters(family: Limits, perKid: List<Limits>): Boolean =
        (listOf(family) + perKid).any { it.windows.isNotEmpty() || configured(it) }

    fun configured(l: Limits): Boolean =
        dayMs(l, weekend = false, bonusMs = 0L) != null || dayMs(l, weekend = true, bonusMs = 0L) != null

    /**
     * Whether [dayOfWeek] — [Calendar]'s 1..7, Sunday = 1, which is the
     * convention `TimeWindows` already works in — falls at the weekend.
     *
     * A parameter rather than a reading, so the hub can answer it for the
     * family's zone and a test can answer it for any day at all.
     */
    fun isWeekend(dayOfWeek: Int): Boolean =
        dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
}
