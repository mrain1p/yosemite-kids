package io.yosemitekids.app

import io.yosemitekids.app.data.SessionGuard
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bedtime pass arithmetic, pure so it can be held without a Context: a
 * grant during a blocked window waives that window, and the parent's stats
 * report the minutes as a plain sum — so the pass has to sum too.
 */
class WindowPassTest {
    private val min = 60_000L

    @Test fun `grants inside a window stack instead of overlapping`() {
        val now = 1_000_000L
        val first = SessionGuard.extendPass(0L, now, 15)
        val second = SessionGuard.extendPass(first, now + 10_000L, 15)
        assertEquals(now + 15 * min, first)
        assertEquals(now + 30 * min, second)
    }

    @Test fun `a lapsed pass counts from now`() {
        val now = 5_000_000L
        assertEquals(now + 15 * min, SessionGuard.extendPass(now - 60 * min, now, 15))
    }
}
