package io.yosemitekids.hub

import java.security.SecureRandom

/**
 * Browser sessions for the admin GUI, and the throttle that protects them.
 *
 * The admin token is a header on every API call, which is fine for a phone and
 * impossible for a browser: nobody types a header into an address bar. So the
 * GUI trades the token once, at a login form, for a cookie.
 *
 * That trade is the moment the admin token becomes guessable. Before the GUI
 * the only way to present it was programmatically, one call at a time; a login
 * form on the LAN is an invitation to try. Hence the throttle here, which is
 * the same shape as the enrolment-code throttle in HubTokens and for the same
 * reason.
 *
 * Sessions live in memory only. Restarting the hub logs everyone out, which is
 * a small cost for never writing a bearer credential to a volume whose
 * permissions this project has already watched go wrong once.
 */
class HubSessions(
    /** Passed in so tests need no clock. */
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        /** Long enough to be unguessable, short enough to paste in a log line. */
        private const val ID_BYTES = 24

        /** A parent administering a TV is not at the keyboard for hours. */
        internal const val SESSION_TTL_MS = 12 * 60 * 60 * 1000L

        /**
         * Wrong admin secrets tolerated inside a window before refusing.
         *
         * Ten rather than eight because /approve now shares this counter, and
         * the phone's "connect my TVs" fans out one call per television.
         */
        internal const val MAX_ATTEMPTS = 10
        internal const val LOCKOUT_WINDOW_MS = 15 * 60 * 1000L

        /**
         * Consecutive lockouts double the wait, capped here. Against 96 bits
         * of hex a rate limit was irrelevant; against a password a parent
         * chose it is the whole control, so handing back ten fresh guesses
         * every quarter of an hour for ever is not enough. At the cap this is
         * about twelve thousand guesses a year, and a months-long grind
         * visibly stops working.
         *
         * The hub already speaks this idiom: HubCrawl backs off the same way.
         */
        internal const val MAX_LOCKOUT_MS = 6 * 60 * 60 * 1000L
    }

    private val random = SecureRandom()
    private val sessions = LinkedHashMap<String, Long>()      // id -> expires at
    private val failures = ArrayDeque<Long>()                  // when, oldest first

    /** Lockouts entered since the last successful sign-in. Drives [windowMs]. */
    private var lockouts = 0

    /**
     * How long failures are remembered, and therefore how long a lockout
     * lasts: 15 min, then 30, then an hour, doubling to [MAX_LOCKOUT_MS].
     */
    private fun windowMs(): Long =
        if (lockouts <= 0) LOCKOUT_WINDOW_MS
        else minOf(LOCKOUT_WINDOW_MS shl (lockouts - 1).coerceAtMost(20), MAX_LOCKOUT_MS)

    /**
     * Whether a login may even be attempted right now.
     *
     * Deliberately global rather than per-IP. On a LAN an attacker picks their
     * own source address, so per-IP counting buys nothing and costs the one
     * property that matters: that a burst of wrong guesses stops.
     */
    @Synchronized
    fun mayAttempt(): Boolean {
        prune()
        return failures.size < MAX_ATTEMPTS
    }

    @Synchronized
    fun recordFailure() {
        prune()
        // Already locked out: do not deepen it. Otherwise a caller that keeps
        // hammering a refused door would escalate itself to the six-hour cap
        // in seconds, and the escalation is meant to answer a slow grind, not
        // a fast one the gate is already refusing.
        if (failures.size >= MAX_ATTEMPTS) return
        failures.addLast(now())
        if (failures.size >= MAX_ATTEMPTS) lockouts++
    }

    /** Seconds until another attempt is allowed, or 0 when one is allowed now. */
    @Synchronized
    fun retryAfterSeconds(): Long {
        prune()
        if (failures.size < MAX_ATTEMPTS) return 0
        val oldest = failures.firstOrNull() ?: return 0
        return ((oldest + windowMs() - now()) / 1000).coerceAtLeast(1)
    }

    /** A successful login clears the record: the guesser was not the parent. */
    @Synchronized
    fun open(): String {
        // The guesser was not the parent: forget the failures AND the
        // escalation, or a family who mistyped twice last week would still be
        // serving the longer sentence today.
        failures.clear()
        lockouts = 0
        prune()
        val id = ByteArray(ID_BYTES).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        sessions[id] = now() + SESSION_TTL_MS
        return id
    }

    @Synchronized
    fun valid(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        prune()
        val expires = sessions[id] ?: return false
        return expires > now()
    }

    @Synchronized
    fun close(id: String?) {
        if (id != null) sessions.remove(id)
    }

    /**
     * End every session but the caller's own. Used when the password
     * changes: you change it because you think someone else has it, and a
     * twelve-hour session on a browser left open somewhere is exactly what
     * you are trying to end. No disk state is needed — sessions are
     * memory-only and single-process, so there is no epoch to persist.
     */
    @Synchronized
    fun closeAll(except: String?) {
        sessions.keys.retainAll { it == except }
    }

    @Synchronized
    internal fun openCount(): Int {
        prune()
        return sessions.size
    }

    private fun prune() {
        val t = now()
        sessions.entries.removeAll { it.value <= t }
        while (failures.isNotEmpty() && failures.first() + windowMs() <= t) {
            failures.removeFirst()
        }
    }
}
