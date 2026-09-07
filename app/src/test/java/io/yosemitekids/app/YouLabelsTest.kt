package io.yosemitekids.app

import io.yosemitekids.app.ui.blockedWindowsLabel
import io.yosemitekids.app.ui.clockLabel
import io.yosemitekids.app.ui.formatBytes
import io.yosemitekids.app.ui.offlineLine
import io.yosemitekids.app.ui.windowRangeLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the kid's own page says in words — the mono labels on the
 * blocked-windows card, the offline banner and a download's size.
 *
 * These are read by a five-year-old, which is why "0 downloads still play" is
 * a bug and not a rounding of the sentence.
 */
class YouLabelsTest {

    @Test
    fun clocksFollowTheHouseholdsConvention() {
        assertEquals("20:00", clockLabel(20 * 60, use24h = true))
        assertEquals("8:00pm", clockLabel(20 * 60, use24h = false))
        assertEquals("8:30am", clockLabel(8 * 60 + 30, use24h = false))
        assertEquals("12:05am", clockLabel(5, use24h = false))
        assertEquals("0:05", clockLabel(5, use24h = true))
        assertEquals("12:00pm", clockLabel(12 * 60, use24h = false))
    }

    @Test
    fun aWindowThatCrossesMidnightNamesOnlyItsStart() {
        // Bedtime 20:00–07:00: the pill says when the television stops, which
        // is the half of it a child is asking about.
        assertEquals("8:00pm", windowRangeLabel(20 * 60, 7 * 60, use24h = false))
        assertEquals(
            "8:30am–3:30pm",
            windowRangeLabel(8 * 60 + 30, 15 * 60 + 30, use24h = false)
        )
        assertEquals("8:30–15:30", windowRangeLabel(8 * 60 + 30, 15 * 60 + 30, use24h = true))
    }

    @Test
    fun theCardCountsMinutesOnlyWhenThereIsABudgetToCountThemAgainst() {
        assertEquals(
            "VIDEOS ARE OFF · 40 OF 60 MINUTES USED",
            blockedWindowsLabel(watchedMin = 40, budgetMin = 60)
        )
        assertEquals("VIDEOS ARE OFF", blockedWindowsLabel(watchedMin = 40, budgetMin = null))
    }

    @Test
    fun theOfflineLineCountsHonestly() {
        assertEquals("3 downloads still play.", offlineLine(3))
        assertEquals("1 download still plays.", offlineLine(1))
        assertEquals("Nothing saved to watch without Wi-Fi.", offlineLine(0))
    }

    @Test
    fun sizesReadTheWayAPhonesStorageScreenDoes() {
        assertEquals("142 MB", formatBytes(142_000_000))
        assertEquals("1.5 GB", formatBytes(1_500_000_000))
        assertEquals("800 kB", formatBytes(800_000))
    }
}
