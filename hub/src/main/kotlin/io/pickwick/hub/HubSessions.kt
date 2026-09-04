package io.pickwick.hub

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

        /** Wrong admin tokens tolerated inside [LOCKOUT_WINDOW_MS] before refusing. */
        internal const val MAX_ATTEMPTS = 8
        internal const val LOCKOUT_WINDOW_MS = 15 * 60 * 1000L
    }

    private val random = SecureRandom()
    private val sessions = LinkedHashMap<String, Long>()      // id -> expires at
    private val failures = ArrayDeque<Long>()                  // when, oldest first

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
        failures.addLast(now())
    }

    /** Seconds until another attempt is allowed, or 0 when one is allowed now. */
    @Synchronized
    fun retryAfterSeconds(): Long {
        prune()
        if (failures.size < MAX_ATTEMPTS) return 0
        val oldest = failures.firstOrNull() ?: return 0
        return ((oldest + LOCKOUT_WINDOW_MS - now()) / 1000).coerceAtLeast(1)
    }

    /** A successful login clears the record: the guesser was not the parent. */
    @Synchronized
    fun open(): String {
        failures.clear()
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

    @Synchronized
    internal fun openCount(): Int {
        prune()
        return sessions.size
    }

    private fun prune() {
        val t = now()
        sessions.entries.removeAll { it.value <= t }
        while (failures.isNotEmpty() && failures.first() + LOCKOUT_WINDOW_MS <= t) {
            failures.removeFirst()
        }
    }
}
