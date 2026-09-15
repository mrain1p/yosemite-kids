package io.yosemitekids.app

import io.yosemitekids.app.data.PlaybackBreaker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One failure is the video's; the second in a row is YouTube's, and the walk stops. */
class PlaybackBreakerTest {

    @Test
    fun oneFailureSkipsAndTwoInARowStop() {
        assertFalse("a single bad video is skipped, as before", PlaybackBreaker.trips(1))
        assertTrue("the second in a row is a refusal pattern, not a video", PlaybackBreaker.trips(2))
        assertTrue(PlaybackBreaker.trips(7))
    }

    @Test
    fun theNumberIsTwoOnPurpose() {
        // Three would mean three refused extractions at full speed before the
        // player noticed; one would turn every dead video into a stop.
        assertTrue(PlaybackBreaker.MAX_CONSECUTIVE == 2)
        assertFalse(PlaybackBreaker.trips(0))
    }
}
