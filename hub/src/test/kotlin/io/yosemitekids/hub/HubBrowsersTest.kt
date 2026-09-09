package io.yosemitekids.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The claim code, on its own, with no socket.
 *
 * Everything a credential store has to get right is a value question, so it is
 * asked here rather than through HTTP: single use, expiry, the try limit, and
 * the one that is really about a child — that whose rules apply is decided
 * when the parent mints, and cannot be changed by anything the browser does
 * afterwards.
 */
class HubBrowsersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val T = 1_780_000_000_000L
    private val ADA = "abc12345"
    private val BEN = "def67890"

    private fun store() = HubBrowsers(tmp.newFolder())

    @Test
    fun `a code is redeemed once and never again`() {
        val b = store()
        val code = b.mint(ADA, T)!!
        assertEquals(ADA, b.claim(code, T).getOrThrow().kid)
        assertTrue(b.claim(code, T).isFailure)
    }

    @Test
    fun `the code carries the kid the parent chose`() {
        // The whole point of binding at mint time. Nothing the browser sends
        // afterwards names a child — there is no field for it on the wire and
        // no parser for one (guard 60).
        val b = store()
        val forBen = b.mint(BEN, T)!!
        val browser = b.claim(forBen, T).getOrThrow()
        assertEquals(BEN, browser.kid)
        assertEquals(BEN, b.resolve(browser.token, T)!!.kid)
    }

    @Test
    fun `a code expires`() {
        val b = store()
        val code = b.mint(ADA, T)!!
        assertTrue(b.claim(code, T + HubBrowsers.CODE_TTL_MS).isFailure)
    }

    @Test
    fun `wrong guesses burn every live code`() {
        // Same rule HubTokens.approve follows: per-code tries would hand an
        // attacker five guesses per outstanding code.
        val b = store()
        val a = b.mint(ADA, T)!!
        val c = b.mint(BEN, T)!!
        repeat(HubBrowsers.MAX_TRIES) { b.claim("ZZZZZZ", T) }
        assertTrue(b.claim(a, T).isFailure)
        assertTrue(b.claim(c, T).isFailure)
        assertTrue(b.pending(T).isEmpty())
    }

    @Test
    fun `a refusal says which`() {
        val b = store()
        b.mint(ADA, T)
        val unknown = b.claim("ZZZZZZ", T).exceptionOrNull() as ClaimRefused
        assertEquals(HubBrowsers.Refusal.UNKNOWN_CODE, unknown.reason)
        repeat(HubBrowsers.MAX_TRIES - 2) { b.claim("ZZZZZZ", T) }
        // The last try burns the outstanding code, and the caller is told that
        // rather than being told the code was wrong again.
        val burned = b.claim("ZZZZZZ", T).exceptionOrNull() as ClaimRefused
        assertEquals(HubBrowsers.Refusal.TOO_MANY_TRIES, burned.reason)
    }

    @Test
    fun `the pending queue is capped and refuses rather than evicting`() {
        // Eviction would silently invalidate a code a parent is reading onto a
        // tablet at that moment.
        val b = store()
        val first = b.mint(ADA, T)!!
        repeat(HubBrowsers.MAX_PENDING - 1) { assertNotNull(b.mint(ADA, T)) }
        assertNull(b.mint(ADA, T))
        assertTrue(b.claim(first, T).isSuccess)
    }

    @Test
    fun `expired codes do not hold the queue closed`() {
        val b = store()
        repeat(HubBrowsers.MAX_PENDING) { b.mint(ADA, T) }
        assertNull(b.mint(ADA, T))
        assertNotNull(b.mint(ADA, T + HubBrowsers.CODE_TTL_MS))
    }

    @Test
    fun `a cookie nobody minted resolves to nothing`() {
        val b = store()
        b.claim(b.mint(ADA, T)!!, T).getOrThrow()
        assertNull(b.resolve("0".repeat(32), T))
        assertNull(b.resolve("", T))
        assertNull(b.resolve(null, T))
    }

    @Test
    fun `a claim lapses eventually, and not before`() {
        val b = store()
        val token = b.claim(b.mint(ADA, T)!!, T).getOrThrow().token
        assertNotNull(b.resolve(token, T + HubBrowsers.CLAIM_TTL_MS - 1))
        assertNull(b.resolve(token, T + HubBrowsers.CLAIM_TTL_MS))
    }

    @Test
    fun `revoke cuts one browser off and leaves the rest`() {
        val b = store()
        val ada = b.claim(b.mint(ADA, T)!!, T).getOrThrow()
        val ben = b.claim(b.mint(BEN, T)!!, T).getOrThrow()
        assertTrue(b.revoke(ada.token.take(8)))
        assertNull(b.resolve(ada.token, T))
        assertNotNull(b.resolve(ben.token, T))
        // A reference nobody holds is a false, not an exception and not a
        // silent success the page would render as "removed".
        assertFalse(b.revoke("nosuchre"))
    }

    @Test
    fun `every browser of a kid can go at once`() {
        val b = store()
        val one = b.claim(b.mint(ADA, T)!!, T).getOrThrow()
        val two = b.claim(b.mint(ADA, T)!!, T).getOrThrow()
        val other = b.claim(b.mint(BEN, T)!!, T).getOrThrow()
        assertEquals(2, b.revokeFor(ADA))
        assertNull(b.resolve(one.token, T))
        assertNull(b.resolve(two.token, T))
        assertNotNull(b.resolve(other.token, T))
    }

    @Test
    fun `two codes are never the same code`() {
        val b = store()
        val seen = HashSet<String>()
        repeat(HubBrowsers.MAX_PENDING) { seen.add(b.mint(ADA, T)!!) }
        assertEquals(HubBrowsers.MAX_PENDING, seen.size)
    }

    @Test
    fun `codes carry no vowels and no look-alikes`() {
        // The same alphabet a device's enrolment code uses, and the same one
        // object — a second string with the confusable characters "taken out"
        // is how an O comes back (guard 57).
        val b = store()
        val code = b.mint(ADA, T)!!
        assertEquals(HubBrowsers.CODE_LENGTH, code.length)
        code.forEach { assertTrue("$it is not in the code alphabet", it in HubTokens.CODE_ALPHABET) }
    }

    @Test
    fun `a claim survives a restart`() {
        // It is on the volume for this reason: a docker pull must not send
        // every child back to a parent for a fresh code.
        val dir = tmp.newFolder()
        val token = HubBrowsers(dir).let { it.claim(it.mint(ADA, T)!!, T).getOrThrow().token }
        assertEquals(ADA, HubBrowsers(dir).resolve(token, T)!!.kid)
    }

    @Test
    fun `last seen is written at most once an hour`() {
        val b = store()
        val token = b.claim(b.mint(ADA, T)!!, T).getOrThrow().token
        b.noteSeen(token, T)
        val first = b.resolve(token, T)!!.lastSeenAt
        assertEquals(T, first)
        b.noteSeen(token, T + 60_000)
        assertEquals(first, b.resolve(token, T)!!.lastSeenAt)
        b.noteSeen(token, T + HubBrowsers.SEEN_WRITE_INTERVAL_MS)
        assertNotEquals(first, b.resolve(token, T)!!.lastSeenAt)
    }
}
