package io.yosemitekids.app

import io.yosemitekids.app.data.LimitKind
import io.yosemitekids.app.data.Remaining
import io.yosemitekids.app.data.interpolateRemainingMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The number in the app's chrome between two authoritative reads.
 *
 * This is the whole of what makes "N min left" live without asking
 * `SessionGuard` — which writes to disk — every second, so it is pinned here
 * rather than on a device: the interesting cases are a kid sitting paused
 * while bedtime closes in, and the moment one rule overtakes another.
 */
class TimeLeftInterpolationTest {

    private val minute = 60_000L

    private fun budget(min: Long) = Remaining(min * minute, LimitKind.BUDGET)
    private fun window(min: Long) = Remaining(min * minute, LimitKind.WINDOW)
    private fun sitting(min: Long) = Remaining(min * minute, LimitKind.SITTING)

    @Test
    fun noRuleAtAllMeansNoNumberAndSoNoPill() {
        // A FREE channel with no windows: SessionGuard offers no candidates,
        // and the chrome must draw nothing rather than a zero or a dash.
        assertNull(interpolateRemainingMs(emptyList(), sinceMs = 0))
        assertNull(interpolateRemainingMs(emptyList(), sinceMs = 10 * minute))
    }

    @Test
    fun aBudgetHoldsStillWhileNothingIsPlaying() {
        // The budget has already been divided by the drain rate: it falls only
        // as playback spends it. Ten minutes of browsing spends none of it.
        val reads = listOf(budget(20))
        assertEquals(20 * minute, interpolateRemainingMs(reads, sinceMs = 10 * minute))
    }

    @Test
    fun aBudgetFallsWithWhatWasActuallyPlayed() {
        val reads = listOf(budget(20))
        assertEquals(
            17 * minute,
            interpolateRemainingMs(reads, sinceMs = 10 * minute, playedMs = 3 * minute)
        )
        // Spent past the end: floored, never negative.
        assertEquals(
            0L,
            interpolateRemainingMs(reads, sinceMs = 30 * minute, playedMs = 25 * minute)
        )
    }

    @Test
    fun aWindowFallsOnTheWallClockWhetherOrNotAnythingPlays() {
        // Bedtime is the case "hold the value while paused" gets wrong, and it
        // is exactly when a kid is most likely to be paused.
        val reads = listOf(window(20))
        assertEquals(10 * minute, interpolateRemainingMs(reads, sinceMs = 10 * minute))
        assertEquals(
            10 * minute,
            interpolateRemainingMs(reads, sinceMs = 10 * minute, playedMs = 10 * minute)
        )
    }

    @Test
    fun theSittingCapCountsOnPlaybackLikeTheBudget() {
        val reads = listOf(sitting(15))
        assertEquals(15 * minute, interpolateRemainingMs(reads, sinceMs = 9 * minute))
        assertEquals(
            13 * minute,
            interpolateRemainingMs(reads, sinceMs = 9 * minute, playedMs = 2 * minute)
        )
    }

    @Test
    fun theBindingRuleChangesHandsMidSession() {
        // Twelve budget-minutes and bedtime thirteen minutes away: the budget
        // binds. Nothing plays, so five minutes later bedtime is eight away
        // and the budget is still twelve — the window has taken over. Ageing
        // only the candidate that bound at read time would still say twelve.
        val reads = listOf(budget(12), window(13))
        assertEquals(12 * minute, interpolateRemainingMs(reads, sinceMs = 0))
        assertEquals(12 * minute, interpolateRemainingMs(reads, sinceMs = 1 * minute))
        assertEquals(8 * minute, interpolateRemainingMs(reads, sinceMs = 5 * minute))
        assertEquals(3 * minute, interpolateRemainingMs(reads, sinceMs = 10 * minute))
    }

    @Test
    fun theOtherDirectionToo() {
        // Bedtime is far off and the budget nearly spent: playing hands the
        // binding rule the other way, from the window to the budget.
        val reads = listOf(budget(9), window(40))
        assertEquals(9 * minute, interpolateRemainingMs(reads, sinceMs = 0))
        // Ten minutes of solid playing: the budget is gone first.
        assertEquals(
            0L,
            interpolateRemainingMs(reads, sinceMs = 10 * minute, playedMs = 10 * minute)
        )
    }

    @Test
    fun itNeverGoesUpAndNeverExceedsTheReadItCameFrom() {
        val reads = listOf(budget(12), window(13), sitting(15))
        val anchor = interpolateRemainingMs(reads, sinceMs = 0)!!
        var previous = Long.MAX_VALUE
        // A second at a time for a quarter of an hour, playing two seconds in
        // every three — the shape of a kid watching with pauses.
        for (second in 0..900) {
            val since = second * 1_000L
            val played = since * 2 / 3
            val value = interpolateRemainingMs(reads, since, played)!!
            assertTrue("went up at ${second}s: $previous -> $value", value <= previous)
            assertTrue("exceeded the read at ${second}s: $value > $anchor", value <= anchor)
            assertTrue("went negative at ${second}s", value >= 0)
            previous = value
        }
    }

    @Test
    fun aClockThatStepsBackwardsHandsNoTimeBack() {
        // elapsedRealtime cannot, but the arithmetic is total anyway: a
        // negative interval and a played span longer than the interval both
        // clamp rather than crediting minutes.
        val reads = listOf(window(20), budget(20))
        assertEquals(20 * minute, interpolateRemainingMs(reads, sinceMs = -5 * minute))
        assertEquals(
            15 * minute,
            interpolateRemainingMs(reads, sinceMs = 5 * minute, playedMs = 99 * minute)
        )
    }

    @Test
    fun everyKindSaysWhichClockItRunsOn() {
        // The classification is what the interpolation branches on; getting
        // one wrong freezes a number that should be falling.
        assertTrue(LimitKind.WINDOW.onWallClock)
        assertTrue(LimitKind.PAUSED.onWallClock)
        assertTrue(!LimitKind.BUDGET.onWallClock)
        assertTrue(!LimitKind.SITTING.onWallClock)
    }

    @Test
    fun aParentPauseIsZeroAndStaysZero() {
        val reads = listOf(Remaining(0, LimitKind.PAUSED))
        assertEquals(0L, interpolateRemainingMs(reads, sinceMs = 0))
        assertEquals(0L, interpolateRemainingMs(reads, sinceMs = 60 * minute))
    }
}
