package io.yosemitekids.app

import io.yosemitekids.app.ui.PlayerDismiss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The swipe that puts the video in the little window.
 *
 * The touch plumbing needs a finger and a device; these are the decisions it
 * makes once it has one — when a drag counts, when it does not, and how the
 * picture is drawn on the way there. Pinned because they are exactly the
 * numbers that get "just nudged": a threshold halved makes the player shrink
 * whenever a thumb rests on it, and a child who cannot keep a video on screen
 * stops using the app.
 */
class PlayerDismissTest {

    @Test
    fun aShortDragIsNotADismissal() {
        // A thumb that wandered 30dp down the picture and stopped.
        assertFalse(PlayerDismiss.shouldDismiss(dragDp = 30f, velocityDpPerSecond = 0f))
        // Even leaving fast: a flick has to be a real flick, not a twitch.
        assertFalse(PlayerDismiss.shouldDismiss(dragDp = 12f, velocityDpPerSecond = 2_000f))
    }

    @Test
    fun pastTheThresholdCountsWhenTheFingerIsStillGoingDown() {
        assertTrue(PlayerDismiss.shouldDismiss(PlayerDismiss.THRESHOLD_DP, 0f))
        assertTrue(PlayerDismiss.shouldDismiss(PlayerDismiss.THRESHOLD_DP + 40f, 300f))
    }

    @Test
    fun changingYourMindMidDragSpringsBack() {
        // Dragged well past the line, then yanked back up before letting go.
        assertFalse(PlayerDismiss.shouldDismiss(PlayerDismiss.THRESHOLD_DP + 40f, -600f))
    }

    @Test
    fun aFlickCountsLongBeforeTheThreshold() {
        val short = PlayerDismiss.FLICK_MIN_DP + 1f
        assertTrue(short < PlayerDismiss.THRESHOLD_DP)
        assertTrue(PlayerDismiss.shouldDismiss(short, PlayerDismiss.FLICK_DP_PER_SECOND))
        assertFalse(PlayerDismiss.shouldDismiss(short, PlayerDismiss.FLICK_DP_PER_SECOND - 1f))
    }

    @Test
    fun draggingUpIsNeverADismissalAndNeverMovesThePicture() {
        assertFalse(PlayerDismiss.shouldDismiss(-200f, 0f))
        assertFalse(PlayerDismiss.shouldDismiss(-200f, 5_000f))
        assertEquals(0f, PlayerDismiss.progress(-200f), 0f)
        assertEquals(1f, PlayerDismiss.scale(-200f), 0f)
        assertEquals(1f, PlayerDismiss.alpha(-200f), 0f)
    }

    @Test
    fun theProgressRunsFromNothingToTheThresholdAndStopsThere() {
        assertEquals(0f, PlayerDismiss.progress(0f), 0f)
        assertEquals(0.5f, PlayerDismiss.progress(PlayerDismiss.THRESHOLD_DP / 2f), 1e-4f)
        assertEquals(1f, PlayerDismiss.progress(PlayerDismiss.THRESHOLD_DP), 1e-4f)
        // Dragged to the bottom of the screen and beyond: the picture has
        // nowhere further to go, so it stops rather than inverting.
        assertEquals(1f, PlayerDismiss.progress(4_000f), 0f)
        assertEquals(PlayerDismiss.MIN_SCALE, PlayerDismiss.scale(4_000f), 1e-4f)
        assertTrue(PlayerDismiss.scale(4_000f) > 0f)
    }

    @Test
    fun thePictureIsPinnedByItsBottomRightCornerAllTheWayDown() {
        // Half the shrunken picture plus its travel is always half the box:
        // one corner stays put while the rest of it pulls away. Break this
        // and the video drifts off centre instead of tucking into the corner.
        var dp = 0f
        while (dp <= PlayerDismiss.THRESHOLD_DP * 2f) {
            val half = PlayerDismiss.scale(dp) / 2f + PlayerDismiss.cornerTravelFraction(dp)
            assertEquals("at ${dp}dp", 0.5f, half, 1e-5f)
            dp += 3f
        }
    }

    @Test
    fun theVideoStaysVisibleAndReadableTheWholeWayDown() {
        // No moment where it has vanished before the system window takes over.
        assertTrue(PlayerDismiss.MIN_SCALE in 0.5f..0.95f)
        assertTrue(PlayerDismiss.MIN_ALPHA in 0.5f..1f)
    }
}
