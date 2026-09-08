package io.yosemitekids.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One spelling of "what day is it", for everything that buckets by day.
 *
 * Three things in this codebase already put a value in a day-shaped bucket —
 * a grant's [Grant.date], `SessionGuard`'s daily tally, the digest's per-day
 * channel totals — and a fourth ([UsageLedger]) is a *shared* bucket that two
 * devices have to agree on. Each one had its own `SimpleDateFormat` or its own
 * `LocalDate`, which is fine until two of them disagree: then a kid's minutes
 * land in one bucket and are read out of another, and nothing throws. The
 * symptom is a budget that resets at the wrong hour, or a grant that stops
 * counting at teatime, for some households, some of the time.
 *
 * So the day is minted here and nowhere else in `:core`, and guard 43 in
 * `scripts/check.*` holds it there. `TimeWindows` still uses
 * `java.util.Calendar`, deliberately: a window is a stretch of *clock* on a
 * *day of the week*, not a calendar day, and it buckets nothing.
 *
 * Two spellings of the same day exist and both are needed:
 *
 * - `yyyy-MM-dd` ([of]) — what a [Grant] carries on the wire and what
 *   [UsageLedger] keys cells by. Text, compared as text.
 * - `yyyyMMdd` ([compact]) — what `SessionGuard`'s `day` and `history` prefs
 *   have held since before any of this, and what `DigestStore` keys rows by.
 *   Kept exactly as it was so no install needs a prefs migration.
 *
 * Both sort lexicographically in calendar order, which is what makes
 * [rollover] a plain `max` — but only within one spelling. Never compare
 * across the two: `"2026-09-07" < "20260907"` because `'-'` sorts below a
 * digit, so a compact previous day would win against every dashed candidate
 * for ever and the day would stop rolling.
 */
object FamilyDay {

    /** The calendar day [epochMillis] falls on in [zone], as `yyyy-MM-dd`. */
    fun of(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

    /**
     * The moment [epochMillis] falls on in [zone], in the pair [TimeWindows]
     * works in: [java.util.Calendar]'s day of the week (Sunday = 1) and the
     * minute of the day.
     *
     * A window is a stretch of *clock* on a *day of the week* and buckets
     * nothing, which is why `TimeWindows` takes those two numbers and reads no
     * calendar. Somebody has to produce them, though, and until the hub had to
     * evaluate a bedtime the only caller was a device using its own
     * `Calendar.getInstance()`. A container may not do that — its clock is UTC
     * and the family's is not (guard 27) — so the conversion is minted here,
     * beside [of], from a zone the family named. That also keeps every name
     * for a calendar out of `:hub` entirely: the hub passes a zone it got from
     * [zoneOrNull] and gets two integers back.
     */
    fun clockAt(epochMillis: Long, zone: ZoneId): Pair<Int, Int> {
        val t = Instant.ofEpochMilli(epochMillis).atZone(zone)
        // java.time counts Monday as 1 and Sunday as 7; Calendar counts Sunday
        // as 1. Rotating by one is the whole conversion, and getting it wrong
        // moves every bedtime by a day without anything throwing.
        return (t.dayOfWeek.value % 7 + 1) to (t.hour * 60 + t.minute)
    }

    /**
     * The zone named by [id], or [fallback] when it is null, blank, or not a
     * zone this JVM knows.
     *
     * Lenient on purpose, and in the same direction as everything else that
     * parses the config: an unknown zone id — a typo, or a tzdb entry a
     * device's Android build predates — must cost the family nothing worse
     * than the behaviour they already had, which is each device using its own
     * zone. Throwing here would take a television off the air over a string a
     * parent typed on a phone.
     */
    fun zoneOf(id: String?, fallback: ZoneId = ZoneId.systemDefault()): ZoneId =
        zoneOrNull(id) ?: fallback

    /**
     * The zone named by [id], or **null** when the family has named none this
     * JVM can resolve.
     *
     * The hub's form of [zoneOf]. A container has no honest fallback — its own
     * locale is UTC and the family's is not — so where a device shrugs and
     * uses its own zone, the hub must be able to say "I do not know what day
     * it is here" and decline to window anything by day. That is guard 27's
     * whole subject: the box may read the calendar the *family* named, and no
     * other.
     */
    fun zoneOrNull(id: String?): ZoneId? =
        id?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    /**
     * The later of [previous] and [candidate] — the day a device writes under
     * next.
     *
     * The day never moves backwards on a device that has already used one.
     * `SessionGuard.rolloverIfNewDay` rolled on **any** difference
     * (`previous != today`), so winding a clock back a day zeroed
     * `dailyWatchedMs` and handed out a whole second budget, today. That is a
     * shipped bug on its own, and it becomes a much worse one the moment a
     * budget is shared: a device that walked its own day backwards would start
     * authoring cells under yesterday and read today's aggregate as empty.
     *
     * Only this device's *own* day ratchets. Nothing here adopts a peer's —
     * `max(localDay, seenDay)` propagated through a merge would let one
     * television with a wrong clock walk the whole household's day forward,
     * irreversibly, which is exactly the design `docs/PLAN-hub-parity.md` D1
     * rejects.
     *
     * Both arguments must be in the same spelling; see this object's KDoc.
     */
    fun rollover(previous: String?, candidate: String): String =
        if (previous == null || candidate > previous) candidate else previous

    /** `yyyy-MM-dd` in the `yyyyMMdd` spelling the prefs keys have always used. */
    fun compact(day: String): String = day.replace("-", "")

    /**
     * [day] as a count of days since the epoch, or null when it is not a day
     * in [of]'s form.
     *
     * For bounding how far a date sits from some clock, and for nothing else.
     * Nothing counts, expires or merges by this number — a grant counts on the
     * day it *names*, compared as text, so two devices in different zones
     * agree on which taps are today's without agreeing on when today started.
     *
     * It exists because the hub takes a grant's date from the parent's browser
     * (a container runs UTC and the family does not) and has to be able to
     * refuse a date nowhere near its own while reading no calendar of its own
     * — see `HubWeb.grant` and guard 27.
     */
    fun dayNumber(day: String): Long? =
        runCatching { LocalDate.parse(day).toEpochDay() }.getOrNull()

    /**
     * How many days [day] sits after [reference] — negative when it is before,
     * null when either is not a day.
     */
    fun daysAfter(day: String, reference: String): Long? {
        val a = dayNumber(day) ?: return null
        val b = dayNumber(reference) ?: return null
        return a - b
    }
}
