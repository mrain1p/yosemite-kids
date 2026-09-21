package io.yosemitekids.app

import io.yosemitekids.app.data.LanServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic that bounds a whole request, on its own.
 *
 * `soTimeout` bounds one read. It does not bound a request: a caller
 * dribbling one byte every nine seconds resets that clock for ever and holds
 * one of four worker threads on a television that faces the whole LAN before
 * any token is checked. So every blocking read narrows the socket's window to
 * whatever is left of an overall budget.
 *
 * Which puts the entire risk in one expression, and in one direction. A
 * window of **zero** does not mean "give up"; `Socket.setSoTimeout(0)` means
 * *block for ever*. Getting the floor wrong would turn the deadline into its
 * exact opposite at the precise moment it expired — the stall it exists to
 * stop, arriving only after a caller had already stalled for thirty seconds,
 * which is the hardest kind of bug to ever see.
 *
 * The server around this needs a ConfigStore and therefore Android, so this
 * is the half a JVM test can reach. It is also the half worth reaching.
 */
class LanDeadlineTest {

    private val ms = 1_000_000L

    @Test
    fun aFreshRequestWaitsOneReadsWorthOfSilence() {
        val now = 5_000L * ms
        val deadline = now + 30_000L * ms
        assertEquals(10_000, LanServer.readWindowMs(now, deadline))
    }

    @Test
    fun aRequestNearItsDeadlineWaitsOnlyWhatIsLeft() {
        val now = 5_000L * ms
        assertEquals(3_000, LanServer.readWindowMs(now, now + 3_000L * ms))
        assertEquals(1, LanServer.readWindowMs(now, now + 1L * ms))
    }

    @Test
    fun anExpiredRequestNeverWaitsForEver() {
        val now = 5_000L * ms
        // Zero is the trap: setSoTimeout(0) blocks for ever, so the moment the
        // deadline passes is the moment the socket would stop having one.
        assertEquals(1, LanServer.readWindowMs(now, now))
        assertEquals(1, LanServer.readWindowMs(now, now - 1L * ms))
        assertEquals(1, LanServer.readWindowMs(now, now - 600_000L * ms))
    }

    @Test
    fun theWindowOnlyEverShrinks() {
        val start = 5_000L * ms
        val deadline = start + 30_000L * ms
        var last = Int.MAX_VALUE
        for (elapsedMs in 0L..30_000L step 500L) {
            val w = LanServer.readWindowMs(start + elapsedMs * ms, deadline)
            assertTrue("the window grew at ${elapsedMs}ms: $last then $w", w <= last)
            assertTrue("a window of $w would block for ever", w >= 1)
            last = w
        }
    }
}
