package io.yosemitekids.hub

/**
 * The throttle behind a kid's password, per kid.
 *
 * [HubRate] in front of `/kid/claim` bounds how fast anything on the LAN can
 * knock; this bounds how far one child's password can be guessed at all.
 * A four-character password at ten tries a minute is a sibling's afternoon,
 * so after [MAX_FAILURES] wrong answers for one kid that kid's door closes
 * for [FIRST_LOCK_MS], doubling each time it is hit again, up to
 * [MAX_LOCK_MS] — and a right answer clears the count.
 *
 * Per kid and not per browser or per address: the thing being protected is
 * one child's password, and a guess is a guess whichever tablet it came
 * from. Per kid and not global, so a sibling's guessing never locks a child
 * out of their own shelves.
 *
 * **Never [HubSessions].** That object is the parents' lockout, and a kid
 * route that could reach it is a kid route that can lock a parent out of
 * the console (guard 59). In memory only: a container restart forgives
 * everyone, which is the right answer for a throttle whose unit is minutes.
 */
class HubKidLock(private val now: () -> Long) {

    private class State(var failures: Int = 0, var lockedUntil: Long = 0L, var lockMs: Long = FIRST_LOCK_MS)

    private val lock = Any()
    private val kids = HashMap<String, State>()

    /** Seconds until this kid may try again, or 0 when they may try now. */
    fun retryAfterSeconds(kid: String): Long = synchronized(lock) {
        val s = kids[kid] ?: return 0L
        val left = s.lockedUntil - now()
        if (left <= 0) 0L else (left + 999) / 1000
    }

    /** A wrong answer for [kid]. */
    fun failed(kid: String) = synchronized(lock) {
        val s = kids.getOrPut(kid) { State() }
        s.failures += 1
        if (s.failures >= MAX_FAILURES) {
            s.failures = 0
            s.lockedUntil = now() + s.lockMs
            s.lockMs = (s.lockMs * 2).coerceAtMost(MAX_LOCK_MS)
        }
    }

    /** A right answer: the count and the escalation both forget. */
    fun passed(kid: String) = synchronized(lock) { kids.remove(kid); Unit }

    companion object {
        const val MAX_FAILURES = 5
        const val FIRST_LOCK_MS = 60 * 1000L
        const val MAX_LOCK_MS = 15 * 60 * 1000L
    }
}
