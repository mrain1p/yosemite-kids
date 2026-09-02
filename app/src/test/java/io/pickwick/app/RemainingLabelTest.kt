package io.pickwick.app

import io.pickwick.app.ui.formatClock
import io.pickwick.app.ui.remainingLabel
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

    @Test
    fun clockFormats() {
        assertEquals("0:05", formatClock(5))
        assertEquals("9:59", formatClock(599))
        assertEquals("1:00:00", formatClock(3600))
        assertEquals("0:00", formatClock(-3))
    }
}
