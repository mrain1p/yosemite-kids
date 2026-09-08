package io.yosemitekids.hub

/**
 * A sliding count window, for the one route that faces the LAN with no
 * credential to check at all.
 *
 * `HubSessions` already throttles the admin secret; this is the same shape for
 * requests that present nothing. Kept separate on purpose: sharing the
 * sessions counter would let anyone on the network lock a parent out of their
 * own hub by hammering `/enrol`, which is the failure the throttle is supposed
 * to prevent rather than cause.
 *
 * Global rather than per-IP, for the reason [HubSessions.mayAttempt] gives: on
 * a LAN the caller picks their own source address, so per-IP counting buys
 * nothing and costs the property that matters — that a burst stops.
 *
 * The clock is a parameter, like everywhere else in this codebase, so a test
 * can drive it without sleeping.
 */
internal class HubRate(private val max: Int, private val windowMs: Long) {

    private val hits = ArrayDeque<Long>()

    /** Records an attempt and says whether it is allowed. */
    @Synchronized
    fun allow(now: Long): Boolean {
        prune(now)
        if (hits.size >= max) return false
        hits.addLast(now)
        return true
    }

    /** Seconds until the next attempt is allowed, or 0 when one is allowed now. */
    @Synchronized
    fun retryAfterSeconds(now: Long): Long {
        prune(now)
        if (hits.size < max) return 0
        val oldest = hits.firstOrNull() ?: return 0
        return ((oldest + windowMs - now) / 1000).coerceAtLeast(1)
    }

    private fun prune(now: Long) {
        while (hits.isNotEmpty() && hits.first() + windowMs <= now) hits.removeFirst()
    }
}
