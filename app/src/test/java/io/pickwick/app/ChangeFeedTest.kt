package io.pickwick.app

import io.pickwick.app.data.ConfigMerge
import io.pickwick.app.ui.changeAge
import io.pickwick.app.ui.latestChangeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How the Recent-changes feed reads.
 *
 * Worth pinning because the granularity is the whole point: the app's existing
 * `relativeAge` is built for video ages and answers "today" for anything
 * inside a day, which is useless when the question is whether a co-parent's
 * edit landed ten minutes ago or last Tuesday.
 */
class ChangeFeedTest {

    private val NOW = 1_780_000_000_000L
    private fun mins(n: Long) = NOW - n * 60_000L

    private fun change(
        text: String = "added SciShow Kids",
        who: String = "Dad's phone",
        shownAt: Long = NOW,
        at: Long = NOW
    ) = ConfigMerge.Change(
        code = "src.add", text = text, id = "x", at = at, shownAt = shownAt,
        by = "a1b2c3d4", who = who
    )

    @Test
    fun ageIsReportedAtTheGranularityAParentCaresAbout() {
        assertEquals("just now", changeAge(NOW, NOW))
        assertEquals("just now", changeAge(mins(0), NOW))
        assertEquals("1m ago", changeAge(mins(1), NOW))
        assertEquals("59m ago", changeAge(mins(59), NOW))
        assertEquals("1h ago", changeAge(mins(60), NOW))
        assertEquals("23h ago", changeAge(mins(23 * 60), NOW))
        assertEquals("1d ago", changeAge(mins(24 * 60), NOW))
        assertEquals("6d ago", changeAge(mins(6 * 24 * 60), NOW))
        assertEquals("1w ago", changeAge(mins(7 * 24 * 60), NOW))
    }

    @Test
    fun aFutureStampReadsAsJustNowRatherThanANegativeAge() {
        // It means the minting device's clock is ahead. That is the clock
        // notice's problem to report, not something to spell out in a row.
        assertEquals("just now", changeAge(NOW + 60_000, NOW))
    }

    @Test
    fun noStampMeansNoLine() {
        assertNull(changeAge(0L, NOW))
        assertNull(changeAge(-1L, NOW))
    }

    @Test
    fun theSettingsRowReadsAsOneSentence() {
        assertEquals(
            "Dad's phone added SciShow Kids · 2h ago",
            latestChangeLine(listOf(change(shownAt = mins(120))), NOW)
        )
    }

    @Test
    fun theRowShowsTheNewestChangeNotTheOldest() {
        val log = listOf(
            change(text = "added A", shownAt = mins(600)),
            change(text = "added B", shownAt = mins(5))
        )
        assertEquals("Dad's phone added B · 5m ago", latestChangeLine(log, NOW))
    }

    @Test
    fun anEmptyLogHasNothingToSay() {
        assertNull(latestChangeLine(emptyList(), NOW))
    }

    @Test
    fun aChangeWithNoDeviceNameStillReads() {
        // Attribution can be missing — a config written by an older build, or
        // by the master claim, which is not a parent's action. The line must
        // degrade to the change itself rather than to a stray separator.
        assertEquals(
            "changed app settings · 1h ago",
            latestChangeLine(listOf(change(text = "changed app settings", who = "", shownAt = mins(60))), NOW)
        )
    }

    @Test
    fun theDisplayedTimeIsTheRawClockNotTheForcedStamp() {
        // `at` is minted monotonically so a device with a wrong clock can still
        // win an edit; showing it would present a parent with a time that never
        // happened. `shownAt` is what the feed reads.
        val forced = change(shownAt = mins(30), at = NOW + 9_999_999)
        assertEquals("Dad's phone added SciShow Kids · 30m ago", latestChangeLine(listOf(forced), NOW))
    }
}
