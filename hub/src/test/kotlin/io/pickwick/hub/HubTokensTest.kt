package io.pickwick.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The enrolment code.
 *
 * This is the only credential between a stranger and a family's whole
 * configuration, and it is the one part of the hub that will eventually face
 * the open internet. So it is tested for what it must *refuse*, not for what
 * it allows.
 */
class HubTokensTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val T = 1_780_000_000_000L

    private fun tokens() = HubTokens(tmp.newFolder())

    @Test
    fun anApprovedCodeYieldsAWorkingToken() {
        val t = tokens()
        val code = t.startEnrolment("Living Room TV", T)
        val token = t.approve(code, T).getOrThrow()

        assertTrue(t.isEnrolled(token))
        assertEquals("Living Room TV", t.nameOf(token))
    }

    @Test
    fun aCodeWorksOnlyOnce() {
        // Otherwise a code read off a TV screen — by anyone who walked past —
        // stays valid for as long as it is remembered.
        val t = tokens()
        val code = t.startEnrolment("TV", T)
        t.approve(code, T).getOrThrow()

        assertTrue("a spent code must not enrol a second device", t.approve(code, T).isFailure)
        assertEquals(1, t.devices().size)
    }

    @Test
    fun aCodeExpires() {
        val t = tokens()
        val code = t.startEnrolment("TV", T)
        val late = T + HubTokens.CODE_TTL_MS + 1

        assertTrue(t.approve(code, late).isFailure)
        assertTrue(t.devices().isEmpty())
    }

    @Test
    fun anExpiredCodeIsNotEvenListedAsPending() {
        val t = tokens()
        t.startEnrolment("TV", T)
        assertEquals(1, t.pending(T).size)
        assertTrue(t.pending(T + HubTokens.CODE_TTL_MS + 1).isEmpty())
    }

    @Test
    fun guessingBurnsTheCode() {
        // The limit is what makes an eight-character code enough. Without it,
        // an attacker on the network simply enumerates.
        val t = tokens()
        val code = t.startEnrolment("TV", T)

        repeat(HubTokens.MAX_TRIES) { t.approve("WRONGWRO", T) }

        assertTrue("the real code must be dead after too many guesses", t.approve(code, T).isFailure)
        assertTrue(t.devices().isEmpty())
    }

    @Test
    fun aWrongGuessCountsAgainstEveryOutstandingCode() {
        // Per-code counting would hand an attacker MAX_TRIES guesses for every
        // enrolment left open, and opening enrolments is unauthenticated.
        val t = tokens()
        val a = t.startEnrolment("TV", T)
        val b = t.startEnrolment("Tablet", T)

        repeat(HubTokens.MAX_TRIES) { t.approve("NOPENOPE", T) }

        assertTrue(t.approve(a, T).isFailure)
        assertTrue(t.approve(b, T).isFailure)
    }

    @Test
    fun theRefusalSaysWhichKindItWas() {
        val t = tokens()
        t.startEnrolment("TV", T)
        val e = t.approve("BADCODE1", T).exceptionOrNull() as EnrolmentRefused
        assertEquals(HubTokens.Refusal.UNKNOWN_CODE, e.reason)
    }

    @Test
    fun codesAvoidCharactersPeopleMistype() {
        // Someone reads this off a TV across a room and types it on a phone.
        // O/0 and I/1/L are the whole reason a code gets typed twice.
        val t = tokens()
        repeat(40) {
            val code = t.startEnrolment("TV", T)
            assertFalse("code '$code' contains a look-alike character", code.any { it in "O0I1L" })
        }
    }

    @Test
    fun codesAreNotPredictable() {
        val t = tokens()
        val seen = (1..50).map { t.startEnrolment("TV", T) }.toSet()
        assertEquals("every code must be distinct", 50, seen.size)
    }

    @Test
    fun revokingRemovesAccess() {
        val t = tokens()
        val token = t.approve(t.startEnrolment("TV", T), T).getOrThrow()
        t.revoke(token)
        assertFalse(t.isEnrolled(token))
    }

    @Test
    fun anEmptyOrUnknownTokenIsNeverEnrolled() {
        val t = tokens()
        t.approve(t.startEnrolment("TV", T), T).getOrThrow()

        assertFalse(t.isEnrolled(null))
        assertFalse(t.isEnrolled(""))
        assertFalse(t.isEnrolled("   "))
        assertFalse(t.isEnrolled("0".repeat(32)))
    }

    @Test
    fun devicesSurviveARestart() {
        // Same folder, new instance — a container restart must not unpair the
        // house.
        val dir = tmp.newFolder()
        val token = HubTokens(dir).let { it.approve(it.startEnrolment("TV", T), T).getOrThrow() }
        assertTrue(HubTokens(dir).isEnrolled(token))
    }

    @Test
    fun abandonedEnrolmentsDoNotAccumulate() {
        val t = tokens()
        repeat(5) { t.startEnrolment("TV", T) }
        // A later attempt prunes what has expired rather than letting the file
        // grow forever from codes nobody ever typed.
        t.startEnrolment("TV", T + HubTokens.CODE_TTL_MS + 1)
        assertEquals(1, t.pending(T + HubTokens.CODE_TTL_MS + 1).size)
    }

    @Test
    fun twoTokensAreNeverTheSame() {
        val t = tokens()
        val a = t.approve(t.startEnrolment("A", T), T).getOrThrow()
        val b = t.approve(t.startEnrolment("B", T), T).getOrThrow()
        assertNotEquals(a, b)
    }
}
