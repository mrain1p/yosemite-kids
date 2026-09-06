package io.yosemitekids.hub

import java.io.File
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import io.yosemitekids.app.data.MasterToken

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

    // --- identity and arming ---------------------------------------------

    @Test
    fun theSelfTokenIsMintedOnceAndLooksLikeAHub() {
        val dir = tmp.newFolder()
        val a = HubTokens(dir).selfToken()
        assertEquals(32, a.length)
        assertTrue(MasterToken.isHub(a))
        assertTrue(a.drop(MasterToken.HUB_PREFIX.length).all { it in "0123456789abcdef" })
        assertEquals("the same hub keeps its identity across restarts", a, HubTokens(dir).selfToken())
        assertNotEquals("two hubs differ", a, tokens().selfToken())
    }

    @Test
    fun aPullIsRememberedPerEnrolledDeviceAndWrittenSparingly() {
        val t = tokens()
        val token = t.approve(t.startEnrolment("TV", T), T).getOrThrow()
        assertFalse(t.armed(T))
        t.notePull("not-enrolled", T)
        assertFalse("an unknown token arms nothing", t.armed(T))
        t.notePull(token, T)
        assertTrue(t.armed(T))
        assertEquals(T, t.devices().single().pulledAt)
        // Within the hour the file is left alone.
        t.notePull(token, T + 10 * 60 * 1000L)
        assertEquals(T, t.devices().single().pulledAt)
        t.notePull(token, T + HubTokens.PULL_WRITE_INTERVAL_MS)
        assertEquals(T + HubTokens.PULL_WRITE_INTERVAL_MS, t.devices().single().pulledAt)
    }

    // --- the admin password ----------------------------------------------

    @Test
    fun `a password and the recovery token both open the door, and nothing else does`() {
        val t = tokens()
        assertFalse(t.hasPassword())
        val recovery = t.adminToken(null)

        // Before one is set, only the token works — an existing household
        // must keep running untouched.
        assertEquals(HubTokens.Secret.RECOVERY, t.verifyAdminSecret(recovery, null))
        assertEquals(HubTokens.Secret.NO, t.verifyAdminSecret("a good password", null))

        val rotated = t.setPassword(recovery, "a good password", T, null).getOrThrow()
        assertTrue(t.hasPassword())
        assertEquals(HubTokens.Secret.PASSWORD, t.verifyAdminSecret("a good password", null))
        assertEquals(HubTokens.Secret.NO, t.verifyAdminSecret("a good passwerd", null))
        assertEquals(HubTokens.Secret.NO, t.verifyAdminSecret("", null))
        assertEquals(HubTokens.Secret.NO, t.verifyAdminSecret(null, null))

        // The first set rotates the recovery token and hands it back once:
        // the old one is in a container log that docker logs replays, so
        // leaving it live would make the password decoration.
        assertNotNull(rotated)
        assertNotEquals(recovery, rotated)
        assertEquals(HubTokens.Secret.NO, t.verifyAdminSecret(recovery, null))
        assertEquals(HubTokens.Secret.RECOVERY, t.verifyAdminSecret(rotated, null))
    }

    @Test
    fun `the plaintext password is nowhere in the file`() {
        val dir = tmp.newFolder()
        val t = HubTokens(dir)
        t.setPassword(t.adminToken(null), "unmistakable-plaintext", T, null).getOrThrow()
        val text = File(dir, "devices.json").readText()
        assertFalse(text, text.contains("unmistakable-plaintext"))
        assertTrue("but the record itself must be there", text.contains("PBKDF2WithHmacSHA256"))
    }

    @Test
    fun `changing the password leaves every enrolled device alone`() {
        // The property a future "simplification" would break by deriving
        // device tokens from the admin secret. An assertion, not a comment.
        val t = tokens()
        val tv = t.approve(t.startEnrolment("Living Room TV", T), T).getOrThrow()
        val phone = t.approve(t.startEnrolment("Dad's phone", T), T).getOrThrow()
        t.setPassword(t.adminToken(null), "the first password", T, null).getOrThrow()
        t.setPassword("the first password", "the second password", T, null).getOrThrow()

        assertTrue(t.isEnrolled(tv))
        assertTrue(t.isEnrolled(phone))
        assertEquals("Living Room TV", t.nameOf(tv))
        assertEquals("Dad's phone", t.nameOf(phone))
    }

    @Test
    fun `a later change rotates nothing, and the token can be rotated on demand`() {
        val t = tokens()
        val first = t.setPassword(t.adminToken(null), "the first password", T, null).getOrThrow()
        // Silently invalidating a token a parent wrote down is itself a lockout.
        val second = t.setPassword("the first password", "the second password", T, null).getOrThrow()
        assertNull(second)
        assertEquals(HubTokens.Secret.RECOVERY, t.verifyAdminSecret(first, null))

        val rotated = t.rotateRecoveryToken()
        assertNotEquals(first, rotated)
        assertEquals(HubTokens.Secret.NO, t.verifyAdminSecret(first, null))
        assertEquals(HubTokens.Secret.RECOVERY, t.verifyAdminSecret(rotated, null))
    }

    @Test
    fun `a change needs the current secret, and a weak new one is refused`() {
        val t = tokens()
        t.setPassword(t.adminToken(null), "the first password", T, null).getOrThrow()
        assertTrue(t.setPassword("not the password", "a new password", T, null).isFailure)
        assertTrue(t.setPassword("short", "a new password", T, null).isFailure)
        assertTrue("too short to store", t.setPassword("the first password", "abc", T, null).isFailure)
        // None of that changed anything.
        assertEquals(HubTokens.Secret.PASSWORD, t.verifyAdminSecret("the first password", null))
    }

    @Test
    fun `while the password path is locked out the recovery token still works`() {
        // What stops an attacker who only wants the family locked out from
        // failing ten times every window: 96 bits gain nothing from a rate
        // limit, so the token is exempt and the password is not.
        val t = tokens()
        val recovery = t.setPassword(t.adminToken(null), "a good password", T, null).getOrThrow()!!
        assertEquals(
            HubTokens.Secret.NO,
            t.verifyAdminSecret("a good password", null, allowPassword = false)
        )
        assertEquals(
            HubTokens.Secret.RECOVERY,
            t.verifyAdminSecret(recovery, null, allowPassword = false)
        )
    }

    @Test
    fun `the environment token overrides the stored one and is never locked out`() {
        // A parent with the compose file is never locked out of their own box.
        val t = tokens()
        val stored = t.adminToken(null)
        t.setPassword(stored, "a good password", T, null).getOrThrow()
        assertEquals(HubTokens.Secret.RECOVERY, t.verifyAdminSecret("from-the-compose-file", "from-the-compose-file"))
        assertEquals(
            HubTokens.Secret.RECOVERY,
            t.verifyAdminSecret("from-the-compose-file", "from-the-compose-file", allowPassword = false)
        )
        // And the stored one is not also accepted while the environment names another.
        assertEquals(HubTokens.Secret.NO, t.verifyAdminSecret(stored, "from-the-compose-file"))
    }
}
