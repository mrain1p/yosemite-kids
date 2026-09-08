package io.yosemitekids.hub

import io.yosemitekids.app.data.UsageLedger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Minutes for a viewer that has no `SessionGuard`: a browser.
 *
 * Every other watcher in this project counts its own time. A television and a
 * tablet run `SessionGuard`, which owns a tally in preferences, is written by
 * the player's own tick, and cannot be reached from outside the app. A page in
 * a child's browser has none of that — and, more to the point, **nothing it
 * could be given could be trusted back.** A number the page reports is a
 * number the page can choose.
 *
 * So the box counts, and the page's only move is to say "I am still here".
 * Three properties follow, and each is the answer to a way of cheating:
 *
 * - **The client sends no time.** [beat] takes who is watching and nothing
 *   else; the elapsed figure is this hub's own clock between two calls it
 *   observed. Guard 51 pins that signature, because a `minutes: Int` parameter
 *   added here in good faith is the whole defence gone.
 * - **Minutes cannot be spent twice.** A beat credits the gap since the
 *   previous beat and then moves the mark, so replaying a request credits
 *   nothing; and the ledger cell is raised by `max`, so a stale write cannot
 *   lower it either.
 * - **Nothing can be rewound.** The credited figure only rises within a day,
 *   the day comes from `Whitelist.homeZone` and never from the caller, and the
 *   cell id is derived here from a per-process salt — a page cannot name
 *   another viewer's cell, let alone a paired device's, because every id it
 *   can reach begins with [UsageLedger.WEB_PREFIX] and device tokens do not.
 *
 * **The hole this cannot close on its own**, stated plainly: a page that stops
 * beating stops being counted. Nothing here can tell "the child closed the
 * laptop" from "the child blocked the heartbeat", and it deliberately assumes
 * the first — crediting an hour to a browser that vanished would take time
 * from a child who was not watching. The answer is the other half of the web
 * player, which is a later round: media stops being served when the beats
 * stop, and the session id comes from the hub's own authenticated session
 * rather than from anything the page hands over. Until that exists this is a
 * counting primitive with no route, on purpose.
 *
 * In memory only, like [HubSessions] and for the same reason. A restart
 * forgets the mark and mints a new salt, so a browser's minutes land in a new
 * cell — which still counts, because browsers are summed as a group
 * ([HubUsage.minutesFor]). Nothing about a watching session is worth writing
 * to a volume a family backs up.
 */
class HubWatchMeter(
    private val usage: HubUsage,
    /** Passed in so tests need no clock, like every other hub class. */
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        /**
         * The most one beat may credit.
         *
         * A page reporting every minute loses nothing. One that goes quiet for
         * an hour and comes back is credited two minutes, not an hour: the hub
         * saw no evidence of the other fifty-eight, and a laptop lid is the
         * ordinary explanation. It errs toward counting less, which is the
         * direction that hands a child time rather than taking it — the only
         * direction a *measurement* may fail in, since taking time away
         * wrongly is the failure a parent cannot debug.
         */
        const val MAX_GAP_MS = 2 * 60_000L

        /**
         * Live meters kept before the least recently seen is dropped.
         *
         * These are allocated from request data by a box facing a home
         * network, so they are bounded, exactly as `LanServer`'s reads are. A
         * dropped meter costs its holder the fraction of a minute it had not
         * yet credited — never a credited minute, which is in the ledger.
         */
        const val MAX_LIVE = 200

        /** Cell ids are this many hex characters after the prefix. */
        private const val ID_HEX = 12
    }

    private class Meter(var day: String, var kid: String?) {
        var lastBeatAt = 0L
        var accruedMs = 0L
        var creditedMinutes = 0
    }

    /**
     * Per process, never persisted, and never derived from anything a caller
     * supplies. It is what makes a cell id unguessable and unforgeable: a page
     * that knows another viewer's session string still cannot compute the id
     * its minutes are filed under, and no id it can reach collides with a
     * device's pairing token.
     */
    private val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** Insertion-ordered so the oldest is the one evicted at [MAX_LIVE]. */
    private val meters = LinkedHashMap<String, Meter>()

    private val lock = Any()

    /**
     * The ledger cell [clientKey]'s minutes are filed under.
     *
     * Stable for as long as this process lives, so a browser that reloads
     * keeps its cell and its minutes. Public because the caller that decides
     * whether a kid may play needs to name the same viewer this one counts —
     * `HubPolicy.mayPlay(kid, video, viewer = meter.ledgerId(key))`.
     */
    fun ledgerId(clientKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(clientKey.toByteArray(Charsets.UTF_8))
        return UsageLedger.WEB_PREFIX +
            digest.digest().take(ID_HEX / 2).joinToString("") { "%02x".format(it) }
    }

    /**
     * "Still watching." Credits the time this hub has observed passing since
     * this browser's previous beat and returns the whole minutes it has been
     * charged for [kidId] today — or **-1** when this hub cannot name a day.
     *
     * -1 is the same refusal `HubPolicy` makes without `Whitelist.homeZone`,
     * and the two agree by construction: a kid with rules is not allowed to
     * start, so there are no minutes to file; a kid with no rules has no
     * budget for the minutes to count against.
     *
     * The first beat of a session credits nothing — it plants the mark. That
     * is the honest reading: the hub has watched no time pass yet.
     */
    fun beat(clientKey: String, kidId: String?): Int {
        val day = usage.today() ?: return -1
        val id = ledgerId(clientKey)
        val credited: Int
        synchronized(lock) {
            var m = meters[id]
            // A new day, or the same browser now watching as a different kid,
            // is a different cell: the accrual restarts rather than carrying
            // one child's minutes onto another's budget.
            if (m == null || m.day != day || m.kid != kidId) {
                m = Meter(day, kidId).also { it.lastBeatAt = now() }
                meters.remove(id)
                meters[id] = m
                while (meters.size > MAX_LIVE) {
                    meters.remove(meters.keys.first())
                }
                return 0
            }
            // Clamped at both ends. A clock that stepped backwards must credit
            // nothing rather than a negative; a gap longer than MAX_GAP_MS is
            // time this hub did not see pass.
            val elapsed = (now() - m.lastBeatAt).coerceIn(0L, MAX_GAP_MS)
            m.lastBeatAt = now()
            m.accruedMs += elapsed
            // Rounded down, so the ledger is never ahead of what was actually
            // watched — the same rule a device's cell follows.
            val whole = (m.accruedMs / 60_000L).toInt()
            if (whole > m.creditedMinutes) m.creditedMinutes = whole
            credited = m.creditedMinutes
        }
        // Outside this class's lock: HubUsage holds its own, and taking one
        // inside the other is how two locks become a deadlock.
        if (credited > 0) usage.recordWeb(kidId, id, credited)
        return credited
    }

    /** Live meters, for a test and for a health line. Never a promise about who. */
    fun liveCount(): Int = synchronized(lock) { meters.size }
}
