package io.yosemitekids.app

import io.yosemitekids.app.data.LimitKind
import io.yosemitekids.app.data.Remaining
import io.yosemitekids.app.ui.COUNTDOWN_WINDOW_MS
import io.yosemitekids.app.ui.CountdownAnchor
import io.yosemitekids.app.ui.PlayClock
import io.yosemitekids.app.ui.countdownRingFraction
import io.yosemitekids.app.ui.countdownShows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's daily countdown between two authoritative reads.
 *
 * The chrome's pill (TimeLeftInterpolationTest) never plays anything; the
 * player does, and the whole difference is the play clock. What is pinned
 * here is that a budget falls only with playback, a window falls regardless,
 * a re-seed after the drain rate changes restates the number rather than
 * arguing with the old anchor, and the ring and the show/hide rule read the
 * number the way the design draws them.
 */
class PlayerCountdownTest {

    private val minute = 60_000L
    private fun budget(min: Long) = Remaining(min * minute, LimitKind.BUDGET)
    private fun window(min: Long) = Remaining(min * minute, LimitKind.WINDOW)

    @Test
    fun aBudgetFallsWithThePlayClockAndHoldsWhilePaused() {
        val anchor = CountdownAnchor(listOf(budget(4)), readAtMs = 1_000, playedAtReadMs = 500)
        // Thirty seconds on, thirty seconds played.
        assertEquals(3 * minute + 30_000, anchor.remainingAt(nowMs = 31_000, playedNowMs = 30_500))
        // Another thirty seconds paused: the play clock did not move, nor does the number.
        assertEquals(3 * minute + 30_000, anchor.remainingAt(nowMs = 61_000, playedNowMs = 30_500))
    }

    @Test
    fun aWindowFallsOnTheWallClockWhetherOrNotAnythingPlays() {
        val anchor = CountdownAnchor(listOf(window(4)), readAtMs = 0, playedAtReadMs = 0)
        assertEquals(3 * minute, anchor.remainingAt(nowMs = minute, playedNowMs = 0))
        assertEquals(3 * minute, anchor.remainingAt(nowMs = minute, playedNowMs = minute))
    }

    @Test
    fun noRuleMeansNoNumber() {
        val anchor = CountdownAnchor(emptyList(), readAtMs = 0, playedAtReadMs = 0)
        assertNull(anchor.remainingAt(nowMs = 5 * minute, playedNowMs = 5 * minute))
    }

    @Test
    fun aReseedAfterTheDrainRateChangesRestatesTheNumber() {
        // Ten budget-minutes at full price, two of them played since the read.
        val watching = CountdownAnchor(listOf(budget(10)), readAtMs = 0, playedAtReadMs = 0)
        assertEquals(8 * minute, watching.remainingAt(nowMs = 2 * minute, playedNowMs = 2 * minute))
        // The screen goes off and the family listens at half rate: the guard now
        // says sixteen real minutes are left. The new anchor carries that read
        // and the play clock's value *at* the re-seed, so nothing already
        // counted is counted twice and the old anchor has no say.
        val listening = CountdownAnchor(listOf(budget(16)), readAtMs = 2 * minute, playedAtReadMs = 2 * minute)
        assertEquals(16 * minute, listening.remainingAt(nowMs = 2 * minute, playedNowMs = 2 * minute))
        assertEquals(15 * minute, listening.remainingAt(nowMs = 3 * minute, playedNowMs = 3 * minute))
    }

    @Test
    fun theRingIsFullAtTheWindowsEdgeAndEmptyAtNothingLeft() {
        assertEquals(1f, countdownRingFraction(COUNTDOWN_WINDOW_MS), 0f)
        assertEquals(0.5f, countdownRingFraction(COUNTDOWN_WINDOW_MS / 2), 0.001f)
        assertEquals(0f, countdownRingFraction(0), 0f)
        // Outside the window it is simply full, and a negative read is empty,
        // never a spinner that has gone round the other way.
        assertEquals(1f, countdownRingFraction(COUNTDOWN_WINDOW_MS * 3), 0f)
        assertEquals(0f, countdownRingFraction(-minute), 0f)
    }

    @Test
    fun theChipShowsInsideFiveMinutesAndNeverWhileListening() {
        assertFalse(countdownShows(null, listening = false))
        assertFalse(countdownShows(6 * minute, listening = false))
        assertTrue(countdownShows(5 * minute, listening = false))
        assertTrue(countdownShows(0, listening = false))
        assertFalse(countdownShows(2 * minute, listening = true))
    }

    @Test
    fun thePlayClockCountsOnlyWhileTheVideoRuns() {
        var now = 0L
        val clock = PlayClock { now }
        clock.setPlaying(true)
        now = 10_000
        assertEquals(10_000, clock.playedMs())
        clock.setPlaying(false)
        now = 25_000
        assertEquals(10_000, clock.playedMs())
        // Saying "playing" twice does not restart the stretch.
        clock.setPlaying(true)
        now = 27_000
        clock.setPlaying(true)
        now = 30_000
        assertEquals(15_000, clock.playedMs())
        // Nor does a second "paused" hand time back.
        clock.setPlaying(false)
        clock.setPlaying(false)
        now = 40_000
        assertEquals(15_000, clock.playedMs())
    }
}
