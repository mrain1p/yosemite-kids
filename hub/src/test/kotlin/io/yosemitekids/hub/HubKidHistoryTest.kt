package io.yosemitekids.hub

import io.yosemitekids.app.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The browser's device store.
 *
 * Two of these tests are about bounds rather than behaviour, and they are the
 * ones worth having: this is the only file on the box that a request without an
 * admin session can grow, so the cap and the write interval are the difference
 * between a resume position and a NAS writing JSON every few seconds for every
 * tablet in the house.
 */
class HubKidHistoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var at = 1_788_771_600_000L
    private fun store() = HubKidHistory(tmp.root) { at }

    private fun url(id: String) = Video.watchUrl(id)

    @Test
    fun `a position comes back as the fraction the shelves read`() {
        val h = store()
        h.save("leo", url("aaaaaaaaaaa"), positionMs = 150_000, durationMs = 600_000)
        val point = h.pointsFor("leo").getValue(url("aaaaaaaaaaa"))
        assertEquals(0.25f, point.fraction, 0.001f)
        assertEquals(at, point.lastWatchedAt)
        assertFalse(point.isFinished)
    }

    @Test
    fun `finished means finished, with the same grace the app gives`() {
        val h = store()
        // 98% is the app's WatchProgress.isFinished threshold: a video the
        // session guard cut off at 91% must stay resumable, and one abandoned
        // during the credits must not.
        h.save("leo", url("aaaaaaaaaaa"), positionMs = 588_000, durationMs = 600_000)
        assertTrue(h.pointsFor("leo").getValue(url("aaaaaaaaaaa")).isFinished)
        // And a finished video resumes at the start rather than at the credits.
        assertEquals(0L, h.resumeMs("leo", url("aaaaaaaaaaa")))
    }

    @Test
    fun `resume is where they were, in milliseconds`() {
        val h = store()
        h.save("leo", url("aaaaaaaaaaa"), positionMs = 150_000, durationMs = 600_000)
        assertEquals(150_000L, h.resumeMs("leo", url("aaaaaaaaaaa")))
        assertEquals(0L, h.resumeMs("leo", url("bbbbbbbbbbb")))
        assertEquals(0L, h.resumeMs("noa", url("aaaaaaaaaaa")))
    }

    @Test
    fun `two beats inside the write interval write once`() {
        val h = store()
        assertTrue(h.save("leo", url("aaaaaaaaaaa"), 10_000, 600_000))
        at += 5_000
        assertFalse(
            "a beat five seconds later rewrote the file; the page beats every " +
                "twenty and three tablets would be writing this disk constantly",
            h.save("leo", url("aaaaaaaaaaa"), 15_000, 600_000)
        )
        at += HubKidHistory.WRITE_INTERVAL_MS
        assertTrue(h.save("leo", url("aaaaaaaaaaa"), 45_000, 600_000))
        assertEquals(45_000L, h.resumeMs("leo", url("aaaaaaaaaaa")))
    }

    @Test
    fun `finishing is always written, however soon after the last beat`() {
        // The last write is the one that moves a video OUT of Keep watching.
        // Skipped as "too soon", it would sit there showing the credits for
        // ever, which is the one position it must never keep.
        val h = store()
        assertTrue(h.save("leo", url("aaaaaaaaaaa"), 10_000, 600_000))
        at += 1_000
        assertTrue(h.save("leo", url("aaaaaaaaaaa"), 600_000, 600_000))
        assertTrue(h.pointsFor("leo").getValue(url("aaaaaaaaaaa")).isFinished)
    }

    @Test
    fun `a zero-length video is not history`() {
        // <video> reports NaN duration before metadata loads, which reaches the
        // route as 0. Filing that would be a row whose fraction is undefined.
        val h = store()
        assertFalse(h.save("leo", url("aaaaaaaaaaa"), 1_000, 0))
        assertTrue(h.pointsFor("leo").isEmpty())
    }

    @Test
    fun `the cap holds, and it is the oldest watch that goes`() {
        val h = store()
        for (i in 0 until HubKidHistory.MAX_VIDEOS_PER_KID + 5) {
            // Eleven characters, like a real id, and unique per row.
            h.save("leo", url("v%010d".format(i)), 10_000, 600_000)
            at += HubKidHistory.WRITE_INTERVAL_MS
        }
        val kept = h.pointsFor("leo")
        assertEquals(HubKidHistory.MAX_VIDEOS_PER_KID, kept.size)
        assertFalse("the first watch should have been evicted", url("v0000000000") in kept)
        assertTrue("the newest must survive", url("v0000000304") in kept)
    }

    @Test
    fun `clearing one kid leaves the others alone`() {
        val h = store()
        h.save("leo", url("aaaaaaaaaaa"), 10_000, 600_000)
        h.save("noa", url("bbbbbbbbbbb"), 10_000, 600_000)
        h.clear("leo")
        assertTrue(h.pointsFor("leo").isEmpty())
        assertEquals(1, h.pointsFor("noa").size)
    }

    @Test
    fun `it survives a restart, because a family's resume positions have to`() {
        store().save("leo", url("aaaaaaaaaaa"), 150_000, 600_000)
        assertEquals(150_000L, store().resumeMs("leo", url("aaaaaaaaaaa")))
    }

    @Test
    fun `an unreadable file is an empty history, never a crash`() {
        java.io.File(tmp.root, "kid-history.json").writeText("{ this is not json")
        assertTrue(store().pointsFor("leo").isEmpty())
        assertEquals(0L, store().resumeMs("leo", url("aaaaaaaaaaa")))
    }
}
