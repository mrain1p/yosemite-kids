package io.yosemitekids.app

import io.yosemitekids.app.data.FamilyDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/**
 * The day, and the ratchet on it.
 *
 * `rolloverIfNewDay` is the shipped bug these tests pin: it rolled on **any**
 * difference, so a clock stepped backwards a day zeroed the tally and handed
 * out a second budget. The fix is that a device's own day only ever moves
 * forward, which is also what makes a shared count possible at all.
 */
class FamilyDayTest {

    private val zone = ZoneId.of("Pacific/Auckland")

    @Test
    fun `a day is the wire form a grant already carries`() {
        // 2026-09-05T12:00:00Z
        assertEquals("2026-09-05", FamilyDay.of(1_788_609_600_000L, ZoneId.of("UTC")))
    }

    @Test
    fun `two zones can be on different days at the same instant`() {
        val instant = 1_788_609_600_000L // 2026-09-05T12:00Z = 2026-09-06 in Auckland
        assertNotEquals(
            FamilyDay.of(instant, ZoneId.of("UTC")),
            FamilyDay.of(instant, zone)
        )
        assertEquals("2026-09-06", FamilyDay.of(instant, zone))
    }

    @Test
    fun `an unknown or blank zone falls back rather than throwing`() {
        val utc = ZoneId.of("UTC")
        assertEquals(utc, FamilyDay.zoneOf(null, utc))
        assertEquals(utc, FamilyDay.zoneOf("", utc))
        assertEquals(utc, FamilyDay.zoneOf("   ", utc))
        // A typo, or a tzdb entry this JVM predates. A television must not go
        // off the air over a string a parent typed on a phone.
        assertEquals(utc, FamilyDay.zoneOf("Pacific/Atlantis", utc))
        assertEquals(zone, FamilyDay.zoneOf("Pacific/Auckland", utc))
    }

    @Test
    fun `a clock wound back a day does not roll the day`() {
        val yesterday = "20260905"
        // This is the shipped bug: `previous != today` was true here, and the
        // day's counters were cleared for a day that had already been spent.
        assertEquals(yesterday, FamilyDay.rollover(yesterday, "20260904"))
        assertEquals(yesterday, FamilyDay.rollover(yesterday, "20250101"))
    }

    @Test
    fun `a clock that has genuinely moved on rolls, once`() {
        assertEquals("20260906", FamilyDay.rollover("20260905", "20260906"))
        // A year forward is still one roll: the day is a bucket key, not a count.
        assertEquals("20270905", FamilyDay.rollover("20260905", "20270905"))
    }

    @Test
    fun `a first-ever run has no previous day and takes the clock's`() {
        assertEquals("20260905", FamilyDay.rollover(null, "20260905"))
    }

    @Test
    fun `the compact spelling is the one the prefs keys have always used`() {
        assertEquals("20260905", FamilyDay.compact("2026-09-05"))
        // And it sorts in calendar order, which is what makes rollover a max.
        assertEquals("20260906", FamilyDay.rollover("20260905", "20260906"))
    }

    @Test
    fun `the two spellings must never be compared with each other`() {
        // Documented rather than merely believed: '-' sorts below a digit, so
        // a dashed candidate loses to a compact previous for ever and the day
        // would stop rolling. This assertion is why compact() exists at all.
        assert("2026-09-07" < "20260907")
    }

    @Test
    fun `day numbers bound a date without counting anything`() {
        assertEquals(0L, FamilyDay.dayNumber("1970-01-01"))
        assertEquals(1L, FamilyDay.dayNumber("1970-01-02"))
        assertNull(FamilyDay.dayNumber("not a day"))
        assertNull(FamilyDay.dayNumber("20260905"))
        assertEquals(1L, FamilyDay.daysAfter("2026-09-06", "2026-09-05"))
        assertEquals(-1L, FamilyDay.daysAfter("2026-09-04", "2026-09-05"))
        assertNull(FamilyDay.daysAfter("rubbish", "2026-09-05"))
    }
}
