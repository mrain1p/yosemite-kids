package io.yosemitekids.app

import io.yosemitekids.app.ui.formatClock
import io.yosemitekids.app.ui.remainingLabel
import io.yosemitekids.app.ui.remainingShort
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kid-facing time strings: the header chip, the player chip, the clocks. */
class RemainingLabelTest {

    @Test
    fun minutesUnderAnHour() {
        assertEquals("12 min left", remainingLabel(12 * 60_000L))
        assertEquals("1 min left", remainingLabel(90_000L))
        assertEquals("59 min left", remainingLabel(59 * 60_000L + 59_000L))
    }

    @Test
    fun underAMinuteNeverSaysZero() {
        assertEquals("less than a minute left", remainingLabel(59_000L))
        assertEquals("less than a minute left", remainingLabel(0L))
    }

    @Test
    fun hoursSplitOut() {
        assertEquals("1 h 0 min left", remainingLabel(60 * 60_000L))
        assertEquals("2 h 5 min left", remainingLabel(125 * 60_000L))
    }

    /**
     * The top bar's spelling of the same number. It is a *second* spelling of
     * one value, which is exactly the kind of thing that drifts — so it is
     * pinned here beside the long form, including the two edges the pill can
     * actually reach: no zero, and no bare "1h" swallowing five minutes.
     */
    @Test
    fun shortFormFitsATopBar() {
        assertEquals("20m", remainingShort(20 * 60_000L))
        assertEquals("1m", remainingShort(90_000L))
        assertEquals("59m", remainingShort(59 * 60_000L + 59_000L))
        assertEquals("1h 0m", remainingShort(60 * 60_000L))
        assertEquals("2h 5m", remainingShort(125 * 60_000L))
    }

    @Test
    fun shortFormNeverSaysZero() {
        assertEquals("<1m", remainingShort(59_000L))
        assertEquals("<1m", remainingShort(0L))
    }

    @Test
    fun clockFormats() {
        assertEquals("0:05", formatClock(5))
        assertEquals("9:59", formatClock(599))
        assertEquals("1:00:00", formatClock(3600))
        assertEquals("0:00", formatClock(-3))
    }
}
