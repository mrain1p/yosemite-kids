package io.pickwick.app

import io.pickwick.app.data.SweepBackoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule created by putting the config reconcile on a background worker.
 *
 * Before the worker, a fruitless subnet sweep cost five minutes of quiet and
 * only happened while a parent had the app open. After it, the same sweep runs
 * unattended on TV hardware every tick, for as long as a device stays off — so
 * "waits longer each time it finds nothing" stopped being a nicety and became
 * something worth asserting.
 */
class SweepBackoffTest {

    @Test
    fun theFirstSweepIsNotDelayedBeyondTheOriginalFiveMinutes() {
        // A device that has just gone missing is the case the sweep was built
        // for — a TV rebooting, a router restarting. Backoff must not make the
        // common case slower than it was before there was any backoff.
        assertEquals(SweepBackoff.BASE_MS, SweepBackoff.cooldownMs(0))
        assertEquals(SweepBackoff.BASE_MS, SweepBackoff.cooldownMs(-1))
    }

    @Test
    fun eachFruitlessSweepDoublesTheWait() {
        assertEquals(SweepBackoff.BASE_MS * 2, SweepBackoff.cooldownMs(1))
        assertEquals(SweepBackoff.BASE_MS * 4, SweepBackoff.cooldownMs(2))
        assertEquals(SweepBackoff.BASE_MS * 8, SweepBackoff.cooldownMs(3))
    }

    @Test
    fun theWaitIsCappedSoAMovedDeviceIsStillFoundTheNextDay() {
        assertEquals(SweepBackoff.MAX_MS, SweepBackoff.cooldownMs(30))
        // Four sweeps a day at the cap, not one a week.
        assertTrue("cap should stay within a day", SweepBackoff.MAX_MS <= 24 * 60 * 60_000L)
    }

    @Test
    fun aVeryLongAbsenceNeverWrapsBackToAShortWait() {
        // The bug this guards: `1L shl 64` is a no-op on the JVM because the
        // shift count is taken mod 64, so without clamping, a device missed 59
        // times would quietly drop back to five minutes and start scanning the
        // subnet every tick again — the exact behaviour being removed.
        var previous = 0L
        for (misses in 0..200) {
            val wait = SweepBackoff.cooldownMs(misses)
            assertTrue("misses=$misses produced a non-positive wait: $wait", wait > 0)
            assertTrue("misses=$misses went backwards: $wait after $previous", wait >= previous)
            assertTrue("misses=$misses exceeded the cap", wait <= SweepBackoff.MAX_MS)
            previous = wait
        }
    }
}
