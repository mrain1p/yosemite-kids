package io.yosemitekids.app.ui

// ---------------------------------------------------------------------------
// SWIPE THE VIDEO AWAY — the arithmetic behind the phone's drag-down-to-shrink
// gesture, kept here so a JVM test can state it.
//
// The touch handling itself lives in PlayerActivity.PlayerStage and cannot be
// unit-tested without a device; what CAN be pinned is the half that decides
// things — how far is far enough, how fast counts as a flick, and how big the
// picture is at any point along the way. Those are the numbers a child feels,
// and the numbers somebody will one day "just nudge".
//
// Nothing in here may import androidx: the moment it does, the test needs a
// device and the policy stops being checkable (guard 55 enforces that).
// ---------------------------------------------------------------------------

/**
 * How a downward drag on the video turns into "put it in the little window".
 *
 * Travel and velocity are in **dp** and dp-per-second — the caller divides by
 * the display density first, so the gesture is the same size on a dense phone
 * as on a cheap one, which a raw pixel threshold would not be.
 */
internal object PlayerDismiss {

    /**
     * Travel that means it on its own. Roughly a third of the 16:9 slot on a
     * phone held upright: far enough that scrolling a thumb over the picture
     * does not shrink it, short enough that a six-year-old's hand gets there.
     */
    const val THRESHOLD_DP = 72f

    /** A flick counts sooner — this fast, this far, and it is a dismissal. */
    const val FLICK_DP_PER_SECOND = 800f
    const val FLICK_MIN_DP = 20f

    /** How small the picture gets by the time the threshold is reached. */
    const val MIN_SCALE = 0.72f

    /** And how far it dims, so "going away" reads even at a glance. */
    const val MIN_ALPHA = 0.82f

    /** 0 at rest, 1 at the threshold. An upward drag is not a dismissal. */
    fun progress(dragDp: Float): Float =
        if (dragDp <= 0f) 0f else (dragDp / THRESHOLD_DP).coerceAtMost(1f)

    fun scale(dragDp: Float): Float = 1f - progress(dragDp) * (1f - MIN_SCALE)

    fun alpha(dragDp: Float): Float = 1f - progress(dragDp) * (1f - MIN_ALPHA)

    /**
     * Half the room the shrink frees up, as a fraction of the box — the
     * distance the picture has to travel for its bottom-right corner to stay
     * exactly where it started while the rest of it pulls away. That pinned
     * corner is the whole illusion: the video is being *put somewhere*, not
     * just made smaller in place. Hold [scale]/2 + this at ½ and it holds.
     */
    fun cornerTravelFraction(dragDp: Float): Float =
        progress(dragDp) * (1f - MIN_SCALE) / 2f

    /**
     * Letting go: does the video go to the corner, or spring back?
     *
     * Past the threshold, a finger that is still going down (or has stopped)
     * means it; one already travelling back up does not, which is how a child
     * who changes their mind mid-drag gets to change it. A fast flick counts
     * much sooner, because a flick is a whole gesture in itself and waiting
     * for 72dp of it would feel broken.
     */
    fun shouldDismiss(dragDp: Float, velocityDpPerSecond: Float): Boolean =
        (dragDp >= THRESHOLD_DP && velocityDpPerSecond >= 0f) ||
            (dragDp >= FLICK_MIN_DP && velocityDpPerSecond >= FLICK_DP_PER_SECOND)
}
