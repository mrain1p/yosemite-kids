package io.yosemitekids.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sign-in lockout.
 *
 * Against the old 96-bit hex token a rate limit was close to irrelevant.
 * Against a password a parent chose it is the only thing standing between a
 * patient guesser and the family's whole configuration, so its arithmetic is
 * pinned here rather than assumed.
 */
class HubSessionsTest {

    private val T = 1_780_000_000_000L
    private var clock = T
    private fun sessions() = HubSessions { clock }

    private fun failUntilLocked(s: HubSessions) {
        repeat(HubSessions.MAX_ATTEMPTS) { s.recordFailure() }
    }

    @Test
    fun `attempts are allowed until the tenth failure, then refused`() {
        val s = sessions()
        repeat(HubSessions.MAX_ATTEMPTS - 1) {
            assertTrue("attempt $it must be allowed", s.mayAttempt())
            s.recordFailure()
        }
        assertTrue(s.mayAttempt())
        s.recordFailure()
        assertFalse("the tenth failure closes the door", s.mayAttempt())
        assertTrue(s.retryAfterSeconds() > 0)
    }

    @Test
    fun `the wait doubles for each consecutive lockout, and stops at the cap`() {
        val s = sessions()
        val expected = listOf(15L, 30L, 60L, 120L, 240L, 360L, 360L)
        expected.forEachIndexed { round, minutes ->
            failUntilLocked(s)
            assertFalse(s.mayAttempt())
            val wait = s.retryAfterSeconds()
            assertEquals("round $round", minutes, (wait + 59) / 60)
            // Sit out the sentence; the guesser comes straight back.
            clock += minutes * 60_000L + 1
            assertTrue("round $round must reopen", s.mayAttempt())
        }
    }

    @Test
    fun `a successful sign-in forgets the failures and the escalation`() {
        val s = sessions()
        failUntilLocked(s)
        failUntilLocked(s)   // a second lockout: the next would be an hour
        clock += 31 * 60_000L
        s.open()

        // A family who mistyped twice last week is not still serving the
        // longer sentence today.
        failUntilLocked(s)
        assertEquals(15L, (s.retryAfterSeconds() + 59) / 60)
    }

    @Test
    fun `hammering a refused door does not deepen the sentence`() {
        // The escalation answers a slow grind. A fast one is already being
        // refused at the gate, and must not be able to talk the hub into a
        // six-hour lockout in a few seconds.
        val s = sessions()
        failUntilLocked(s)
        repeat(500) { s.recordFailure() }
        assertEquals(15L, (s.retryAfterSeconds() + 59) / 60)
    }

    @Test
    fun `closeAll ends every session but the caller's`() {
        val s = sessions()
        val mine = s.open()
        val kitchen = s.open()
        val other = s.open()
        assertEquals(3, s.openCount())

        s.closeAll(except = mine)
        assertTrue("the parent changing the password stays signed in", s.valid(mine))
        assertFalse(s.valid(kitchen))
        assertFalse(s.valid(other))
        assertEquals(1, s.openCount())
    }

    @Test
    fun `a session expires on its own`() {
        val s = sessions()
        val id = s.open()
        assertTrue(s.valid(id))
        clock += HubSessions.SESSION_TTL_MS + 1
        assertFalse(s.valid(id))
    }
}
