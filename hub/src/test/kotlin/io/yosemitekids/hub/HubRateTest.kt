package io.yosemitekids.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window in front of the one route that checks no credential at all.
 *
 * Small enough to read, which is the point: this is what stands between the
 * LAN and an unbounded write to `devices.json`, so it is tested on its own
 * rather than only through the socket.
 */
class HubRateTest {

    private val T = 1_780_000_000_000L

    @Test
    fun aBurstStops() {
        val r = HubRate(max = 3, windowMs = 1000)
        repeat(3) { assertTrue("attempt $it", r.allow(T)) }
        assertFalse(r.allow(T))
    }

    @Test
    fun theWindowSlides() {
        val r = HubRate(max = 2, windowMs = 1000)
        assertTrue(r.allow(T))
        assertTrue(r.allow(T + 500))
        assertFalse(r.allow(T + 600))
        // The first attempt has aged out; the second has not.
        assertTrue(r.allow(T + 1000))
        assertFalse(r.allow(T + 1000))
    }

    @Test
    fun aRefusalNamesARealWait() {
        val r = HubRate(max = 1, windowMs = 60_000)
        assertTrue(r.allow(T))
        assertFalse(r.allow(T + 1000))
        // Never zero while the door is shut: a caller told to come back in
        // no time at all comes back immediately and is refused again.
        assertEquals(59L, r.retryAfterSeconds(T + 1000))
        assertTrue(r.retryAfterSeconds(T + 59_999) >= 1)
        assertEquals("nothing to wait for once it has slid", 0L, r.retryAfterSeconds(T + 60_000))
    }

    @Test
    fun aRefusedAttemptDoesNotLengthenTheWait() {
        // Unlike the sign-in lockout, which escalates on purpose. This one
        // guards a route a legitimate device uses, and a caller hammering it
        // must not be able to push a real enrolment further away.
        val r = HubRate(max = 1, windowMs = 10_000)
        assertTrue(r.allow(T))
        repeat(50) { assertFalse(r.allow(T + 1)) }
        assertTrue(r.allow(T + 10_000))
    }
}
