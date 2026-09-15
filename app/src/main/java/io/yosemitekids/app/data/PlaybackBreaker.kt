package io.yosemitekids.app.data

/**
 * When the player stops walking the queue.
 *
 * A video that fails to resolve is skipped, and the next one resolves — which
 * is right for one bad video and exactly wrong the moment YouTube starts
 * refusing: the player then sprints through the whole lineup at full speed,
 * one refused extraction after another, which is the burst a bot wall is
 * looking for and the fastest way to turn a wobble into a ban for the whole
 * house. The sharpest unmitigated exposure the roadmap had.
 *
 * So: one failure is that video's. Two in a row is YouTube's, and the player
 * stops, shows the card with a different sentence on it, and waits for a
 * finger — "Try again" resets the count, because a person chose to. Nothing
 * else in the app resolves a stream in a loop, so this is the one place the
 * rule has to hold; `PlaybackBreakerTest` pins the number.
 */
object PlaybackBreaker {

    /** Failures in a row, counting the one just seen, that stop the walk. */
    const val MAX_CONSECUTIVE = 2

    /** True when [consecutiveFailures] (including the one just seen) says stop walking the queue. */
    fun trips(consecutiveFailures: Int): Boolean = consecutiveFailures >= MAX_CONSECUTIVE
}
