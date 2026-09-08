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
     * Whether a budget binds on **any** day of the week.
     *
     * "Has this family set a limit at all", asked without knowing what day it
     * is — which is the one question a container can ask honestly before it
     * knows the family's zone. Through [dayMs] rather than by re-testing the
     * fields, so it cannot answer yes to a rule [dayMs] would call incomplete.
     */
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
