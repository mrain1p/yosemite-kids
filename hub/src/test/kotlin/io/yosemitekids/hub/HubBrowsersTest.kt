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
        assertEquals(BEN, b.watching(browser.token, T)!!.browser.kid)
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
        assertNull(b.watching("0".repeat(32), T))
        assertNull(b.watching("", T))
        assertNull(b.watching(null, T))
    }

    @Test
    fun `a claim lapses eventually, and not before`() {
        // A fresh claim for each probe, because asking the question changes
        // the answer: [HubBrowsers.watching] is what a real request calls, and
        // a real request is exactly what the window slides from. Probing at
        // TTL-1 through it renews the claim, so a second probe at TTL on the
        // same row would be asking about a browser that had just been used.
        store().let { b ->
            val token = b.claim(b.mint(ADA, T)!!, T).getOrThrow().token
            assertNotNull(b.watching(token, T + HubBrowsers.CLAIM_TTL_MS - 1))
        }
        store().let { b ->
            val token = b.claim(b.mint(ADA, T)!!, T).getOrThrow().token
            assertNull(b.watching(token, T + HubBrowsers.CLAIM_TTL_MS))
        }
    }

    /**
     * The tablet a child actually uses.
     *
     * CLAIM_TTL_MS's own KDoc has said "without being used or renewed" since
     * the day it was written, and `lastSeenAt` has been recorded since the
     * sighting write was — but both places that judged a claim compared
     * `now - claimedAt`. So the iPad a child picked up every single day
     * stopped working exactly six months after it was set up, mid afternoon,
     * showing "Ask a grown-up" with no reason on any screen; and the one left
     * in a drawer the same week expired at the same moment, which is the only
     * half that was behaving.
     *
     * Driven through [HubBrowsers.watching], which is the only path a child's
     * tablet takes. An earlier version of this test drove `resolve` and
     * `noteSeen` instead — a pair with no production caller left — so it
     * stayed green against a `watching` that still compared `claimedAt`.
     */
    @Test
    fun `a claim a child keeps using does not lapse`() {
        // Five months in, they watch something. That is the renewal.
        val used = T + 150L * 24 * 60 * 60 * 1000L
        store().let { b ->
            val token = b.claim(b.mint(ADA, T)!!, T).getOrThrow().token
            assertTrue("watching records the sighting", b.watching(token, used)!!.renewed)
            assertNotNull(
                "six months after the claim, but one month after they last watched",
                b.watching(token, T + HubBrowsers.CLAIM_TTL_MS + 1)
            )
        }
        // And it does lapse, six months after that last use — on a claim
        // nothing has probed in between, for the reason above.
        store().let { b ->
            val token = b.claim(b.mint(ADA, T)!!, T).getOrThrow().token
            b.watching(token, used)
            assertNull(
                "six months after they last watched",
                b.watching(token, used + HubBrowsers.CLAIM_TTL_MS)
            )
        }
    }

    @Test
    fun `a claim nobody ever used lapses from when it was claimed`() {
        // lastSeenAt is 0 until a request arrives, and 0 is not "1970".
        // One claim per probe, for the reason above.
        store().let { b ->
            val token = b.claim(b.mint(ADA, T)!!, T).getOrThrow().token
            assertNotNull(b.watching(token, T + HubBrowsers.CLAIM_TTL_MS - 1))
        }
        store().let { b ->
            val token = b.claim(b.mint(ADA, T)!!, T).getOrThrow().token
            assertNull(b.watching(token, T + HubBrowsers.CLAIM_TTL_MS))
        }
    }


    @Test
    fun `revoke cuts one browser off and leaves the rest`() {
        val b = store()
        val ada = b.claim(b.mint(ADA, T)!!, T).getOrThrow()
        val ben = b.claim(b.mint(BEN, T)!!, T).getOrThrow()
        assertTrue(b.revoke(ada.token.take(8)))
        assertNull(b.watching(ada.token, T))
        assertNotNull(b.watching(ben.token, T))
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
        assertNull(b.watching(one.token, T))
        assertNull(b.watching(two.token, T))
        assertNotNull(b.watching(other.token, T))
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
        assertEquals(ADA, HubBrowsers(dir).watching(token, T)!!.browser.kid)
    }

    /**
     * The write throttle, and the cookie renewal that rides on it.
     *
     * [HubBrowsers.watching] runs on every request a child's tablet makes,
     * including one per two megabytes of video, so it must write browsers.json
     * about once an hour and not once a chunk. `renewed` is the same signal
     * the kid server re-dates the cookie from, so this pins both: a Set-Cookie
     * on every response would be as wrong as a disk write on every response.
     */
    @Test
    fun `last seen is written at most once an hour, and that is when the cookie is renewed`() {
        val b = store()
        val token = b.claim(b.mint(ADA, T)!!, T).getOrThrow().token

        assertTrue("the first sighting is always recorded", b.watching(token, T)!!.renewed)
        assertEquals(T, b.watching(token, T)!!.browser.lastSeenAt)

        assertFalse("a minute later is not an hour later", b.watching(token, T + 60_000)!!.renewed)
        assertEquals(T, b.watching(token, T + 60_000)!!.browser.lastSeenAt)

        val later = T + HubBrowsers.SEEN_WRITE_INTERVAL_MS
        assertTrue(b.watching(token, later)!!.renewed)
        assertNotEquals(T, b.watching(token, later)!!.browser.lastSeenAt)
    }
}
