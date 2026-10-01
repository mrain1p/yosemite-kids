package io.yosemitekids.app

import io.yosemitekids.app.data.Budget
import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A plain cap on the day, and how it composes with the sittings rule.
 *
 * The product could only say a day as *sittings × length*, which is how a
 * parent paces an afternoon and is not the number they arrive with. The owner,
 * twice, looking at the real card: "I don't see an area for daily limits", then
 * "I still don't see a spot where to **add** a daily limit — I see a line item
 * for daily limit but not an option to set it."
 *
 * The first attempt read the day out of the two session rows, derived. That is
 * the thing being replaced here, so the tests that matter are the ones about
 * the two rules meeting: `min`, both directions, and the three serialisers that
 * carry the new one.
 */
class DailyCapTest {

    private fun entry(id: String) = WhitelistEntry(
        id, "https://www.youtube.com/channel/$id", "Channel $id", SourceKind.CHANNEL
    )
    private val plain = Whitelist(listOf(entry("UCa")), emptySet())
    private fun withKid(w: Whitelist, l: Limits) =
        w.copy(profiles = listOf(Profile(id = "k1", name = "Leo", limits = l)))

    // --- the arithmetic ---------------------------------------------------

    @Test
    fun `a cap on its own is the day, with no session rules at all`() {
        // The whole point: ninety minutes a day, said once, with nothing else
        // set. Before this, dayMs returned null here and the day was unlimited.
        val l = Limits(weekdayMinutes = 90, weekendMinutes = 120)
        assertEquals(90 * 60_000L, Budget.dayMs(l, weekend = false, bonusMs = 0L))
        assertEquals(120 * 60_000L, Budget.dayMs(l, weekend = true, bonusMs = 0L))
    }

    @Test
    fun `with both rules set the day is the tighter one, whichever that is`() {
        // A cap is a ceiling and a second ceiling cannot raise the first, so
        // this is min and not a precedence order. Asserted in both directions
        // because a precedence rule would pass one of them.
        val capTighter = Limits(sessionMinutes = 60, weekdaySessions = 3, weekdayMinutes = 90)
        assertEquals(90 * 60_000L, Budget.dayMs(capTighter, weekend = false, bonusMs = 0L))

        val sittingsTighter = Limits(sessionMinutes = 20, weekdaySessions = 2, weekdayMinutes = 90)
        assertEquals(40 * 60_000L, Budget.dayMs(sittingsTighter, weekend = false, bonusMs = 0L))

        // Equal is equal, and not a special case.
        val same = Limits(sessionMinutes = 30, weekdaySessions = 3, weekdayMinutes = 90)
        assertEquals(90 * 60_000L, Budget.dayMs(same, weekend = false, bonusMs = 0L))
    }

    @Test
    fun `the two days are independent and neither falls back to the other`() {
        // A fallback would be an unwritten rule inside the arithmetic. A parent
        // who caps weekdays and leaves weekends alone has capped weekdays —
        // the screen is where "the same on both" belongs, not here.
        val weekdaysOnly = Limits(weekdayMinutes = 60)
        assertEquals(60 * 60_000L, Budget.dayMs(weekdaysOnly, weekend = false, bonusMs = 0L))
        assertNull(Budget.dayMs(weekdaysOnly, weekend = true, bonusMs = 0L))

        val weekendsOnly = Limits(weekendMinutes = 60)
        assertNull(Budget.dayMs(weekendsOnly, weekend = false, bonusMs = 0L))
        assertEquals(60 * 60_000L, Budget.dayMs(weekendsOnly, weekend = true, bonusMs = 0L))
    }

    @Test
    fun `an unfinished sittings rule is still not a budget, cap or no cap`() {
        // The old contract, kept: a session length with no count is a rule the
        // settings screen has not finished collecting, and guessing the missing
        // half would invent a limit nobody set. With a cap beside it the cap is
        // the whole answer — the half-rule contributes nothing rather than
        // being completed from thin air.
        assertNull(Budget.dayMs(Limits(sessionMinutes = 30), weekend = false, bonusMs = 0L))
        assertEquals(
            45 * 60_000L,
            Budget.dayMs(Limits(sessionMinutes = 30, weekdayMinutes = 45), weekend = false, bonusMs = 0L)
        )
        // And no rule at all is still null, which means NO RULE and never zero.
        assertNull(Budget.dayMs(Limits(), weekend = false, bonusMs = 0L))
    }

    @Test
    fun `bonus minutes extend the capped day exactly as they extend a sittings day`() {
        // Added after the min, not inside it: a grant is a parent's deliberate
        // extra on top of whichever rule is biting, so it must not be eaten by
        // the other rule's ceiling.
        val l = Limits(sessionMinutes = 60, weekdaySessions = 3, weekdayMinutes = 90)
        assertEquals(105 * 60_000L, Budget.dayMs(l, weekend = false, bonusMs = 15 * 60_000L))
    }

    // --- the four canonical tests (docs: sync skill §4) --------------------

    @Test
    fun `a daily cap survives a JSON round-trip, on the family and on a kid`() {
        val capped = Limits(weekdayMinutes = 90, weekendMinutes = 120)
        val family = ConfigJson.fromJson(ConfigJson.toJson(plain.copy(limits = capped))).limits
        assertEquals(90, family.weekdayMinutes)
        assertEquals(120, family.weekendMinutes)
        val kid = ConfigJson.fromJson(ConfigJson.toJson(withKid(plain, capped))).profiles[0].limits
        assertEquals(90, kid.weekdayMinutes)
        assertEquals(120, kid.weekendMinutes)
        // Cleared back off round-trips as null, not as zero.
        val cleared = ConfigJson.fromJson(
            ConfigJson.toJson(plain.copy(limits = capped.copy(weekdayMinutes = null)))
        ).limits
        assertNull(cleared.weekdayMinutes)
        assertEquals(120, cleared.weekendMinutes)
    }

    @Test
    fun `no daily cap is omitted from JSON, byte for byte as before the field`() {
        val base = plain.copy(limits = Limits(sessionMinutes = 30, weekdaySessions = 2))
        val json = ConfigJson.toJson(base)
        assertFalse(json.contains("weekdayMinutes"))
        assertFalse(json.contains("weekendMinutes"))
        fun bytes(w: Whitelist) =
            ConfigJson.toJson(w).replace(Regex("\"updatedAt\": \\d+"), "\"updatedAt\": 0")
        assertEquals(
            bytes(base),
            bytes(base.copy(limits = base.limits.copy(weekdayMinutes = null, weekendMinutes = null)))
        )
        // And on a kid, which is the copy a family actually sets rules on.
        val kid = withKid(plain, Limits(sessionMinutes = 20))
        assertFalse(ConfigJson.toJson(kid).contains("weekdayMinutes"))
        assertEquals(bytes(kid), bytes(withKid(plain, Limits(sessionMinutes = 20, weekdayMinutes = null))))
    }

    @Test
    fun `configs with no daily cap keep their pre-cap fingerprint`() {
        // The upgrade test. A family that never sets one must hash exactly as
        // it did, or the offline reconcile re-pushes the whole document to the
        // whole fleet the first time any device parses the new build.
        val base = plain.copy(
            limits = Limits(sessionMinutes = 30, weekdaySessions = 2, minVideoMinutes = 4)
        )
        assertEquals(
            ConfigJson.fingerprint(base),
            ConfigJson.fingerprint(base.copy(limits = base.limits.copy(weekdayMinutes = null)))
        )
        val kid = withKid(base, Limits(sessionMinutes = 20))
        assertEquals(
            ConfigJson.fingerprint(kid),
            ConfigJson.fingerprint(withKid(base, Limits(sessionMinutes = 20, weekendMinutes = null)))
        )
    }

    @Test
    fun `setting a daily cap moves the fingerprint so the reconcile delivers it`() {
        val base = plain.copy(limits = Limits(sessionMinutes = 30, weekdaySessions = 2))
        assertNotEquals(
            ConfigJson.fingerprint(base),
            ConfigJson.fingerprint(base.copy(limits = base.limits.copy(weekdayMinutes = 90)))
        )
        // The two days are separate terms: capping weekends must not hash the
        // same as capping weekdays, or one of the two never reaches a TV.
        assertNotEquals(
            ConfigJson.fingerprint(base.copy(limits = base.limits.copy(weekdayMinutes = 90))),
            ConfigJson.fingerprint(base.copy(limits = base.limits.copy(weekendMinutes = 90)))
        )
        // And a kid's own cap moves that kid's part of the hash, with windows
        // and without — limitsCanon takes two routes and both carry the tail.
        val scheduled = Limits(
            sessionMinutes = 20,
            windows = listOf(
                io.yosemitekids.app.data.TimeWindow("w", "Bedtime", 19 * 60, 7 * 60)
            )
        )
        assertNotEquals(
            ConfigJson.fingerprint(withKid(base, scheduled)),
            ConfigJson.fingerprint(withKid(base, scheduled.copy(weekdayMinutes = 45)))
        )
    }
}
