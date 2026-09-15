package io.yosemitekids.app

import io.yosemitekids.app.data.PlaybackCache
import io.yosemitekids.app.data.YouTubeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The app's stream cache: the rules that make it safe rather than merely
 * fast. Keyed on the ceiling too (or the quality picker becomes a no-op),
 * forgotten whole on a failure (or a stale URL walks the queue), and bounded.
 */
class PlaybackCacheTest {

    private fun pb(title: String) = YouTubeRepository.Playback(title, "https://v/$title", null)
    private val url = "https://www.youtube.com/watch?v=aaaaaaaaaaa"

    @Test
    fun aHitInsideTheTtlIsTheSameObjectAndAMissAfterItIsNothing() {
        val cache = PlaybackCache(ttlMs = 1_000)
        val p = pb("a")
        cache.put(url, 720, p, now = 0)
        assertSame(p, cache.get(url, 720, now = 999))
        assertNull("the URLs are past their keep", cache.get(url, 720, now = 1_000))
        assertEquals("an expired entry is dropped, not kept around", 0, cache.size)
    }

    @Test
    fun theCeilingIsPartOfTheKey() {
        val cache = PlaybackCache()
        cache.put(url, 720, pb("hd"), now = 0)
        assertNull("a different ceiling is a different resolve: the quality picker must reach YouTube", cache.get(url, 360, now = 1))
        assertNull(cache.get(url, null, now = 1))
        assertEquals("hd", cache.get(url, 720, now = 1)!!.title)
    }

    @Test
    fun forgettingAUrlDropsEveryCeilingOfIt() {
        val cache = PlaybackCache()
        cache.put(url, 720, pb("hd"), now = 0)
        cache.put(url, 360, pb("sd"), now = 0)
        cache.put("https://www.youtube.com/watch?v=bbbbbbbbbbb", 720, pb("other"), now = 0)
        cache.forget(url)
        assertNull(cache.get(url, 720, now = 1))
        assertNull(cache.get(url, 360, now = 1))
        assertEquals("only the failed video is forgotten", "other", cache.get("https://www.youtube.com/watch?v=bbbbbbbbbbb", 720, now = 1)!!.title)
    }

    @Test
    fun theCapEvictsTheLeastRecentlyAskedFor() {
        val cache = PlaybackCache(maxEntries = 2)
        cache.put("u1", null, pb("1"), now = 0)
        cache.put("u2", null, pb("2"), now = 0)
        // u1 was asked for more recently than u2 by the time u3 arrives.
        cache.get("u1", null, now = 1)
        cache.put("u3", null, pb("3"), now = 2)
        assertNull("u2 was the one nobody asked for", cache.get("u2", null, now = 3))
        assertEquals("1", cache.get("u1", null, now = 3)!!.title)
        assertEquals("3", cache.get("u3", null, now = 3)!!.title)
    }
}
