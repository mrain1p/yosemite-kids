package io.yosemitekids.app

import io.yosemitekids.app.data.Video
import io.yosemitekids.app.data.VideoCache
import io.yosemitekids.app.data.WatchProgress
import io.yosemitekids.app.ui.VideoItem
import io.yosemitekids.app.ui.historyItems
import io.yosemitekids.app.ui.interleave
import io.yosemitekids.app.ui.orderByPopularity
import io.yosemitekids.app.ui.orderByWatched
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun video(id: String, channel: String = "c", views: Long? = null) = Video(
    url = "https://www.youtube.com/watch?v=$id",
    title = "Video $id",
    channelName = channel,
    thumbnailUrl = null,
    durationSeconds = 60,
    viewCount = views
)

/** The History shelf join, the channel-page orderings, and the cache row format. */
class HistoryAndLayoutTest {

    @Test
    fun historyIsNewestFirstAndDropsUnknownVideos() {
        val known = listOf(video("a"), video("b"), video("c"))
        val history = mapOf(
            known[0].url to WatchProgress(10, 100, lastWatchedAt = 1_000),
            known[1].url to WatchProgress(100, 100, lastWatchedAt = 3_000),
            "https://www.youtube.com/watch?v=gone" to WatchProgress(5, 100, lastWatchedAt = 9_000),
            known[2].url to WatchProgress(50, 100, lastWatchedAt = 0) // never stamped: not history
        )
        val items = historyItems(history, known, limit = 10)
        assertEquals(listOf("b", "a"), items.map { it.video.url.substringAfter("v=") })
        assertEquals(1f, items[0].progress)
        assertEquals(0.1f, items[1].progress!!, 0.001f)
    }

    @Test
    fun historyFirstMetadataWinsAndLimitApplies() {
        val known = listOf(video("a", channel = "first"), video("a", channel = "second"), video("b"))
        val history = mapOf(
            known[0].url to WatchProgress(1, 100, lastWatchedAt = 2),
            known[2].url to WatchProgress(1, 100, lastWatchedAt = 1)
        )
        val items = historyItems(history, known, limit = 1)
        assertEquals(1, items.size)
        assertEquals("first", items[0].video.channelName)
    }

    @Test
    fun popularFirstPutsUnknownCountsLastAndKeepsOrderOnTies() {
        val items = listOf(
            VideoItem(video("a", views = 10), null),
            VideoItem(video("b", views = null), null),
            VideoItem(video("c", views = 500), null),
            VideoItem(video("d", views = 10), null)
        )
        assertEquals(listOf("c", "a", "d", "b"), orderByPopularity(items).map { it.video.url.substringAfter("v=") })
    }

    @Test
    fun watchedOrderIsNewestWatchFirst() {
        val stamps = mapOf("a" to 5L, "b" to 9L, "c" to 1L)
        val items = listOf("a", "b", "c").map { VideoItem(video(it), 1f) }
        assertEquals(
            listOf("b", "a", "c"),
            orderByWatched(items) { stamps[it.substringAfter("v=")] ?: 0L }.map { it.video.url.substringAfter("v=") }
        )
    }

    @Test
    fun cacheRowRoundTripsAndReadsOldFiveCellRows() {
        val v = video("x", channel = "Tab\tChannel", views = 1234)
        val back = VideoCache.parseRow(VideoCache.formatRow(v))!!
        assertEquals(v.url, back.url)
        assertEquals("Tab Channel", back.channelName)
        assertEquals(1234L, back.viewCount)
        val old = VideoCache.parseRow("https://www.youtube.com/watch?v=y\tTitle\tChan\t\t90")!!
        assertNull(old.viewCount)
        assertEquals(90L, old.durationSeconds)
        assertNull(VideoCache.parseRow("too\tshort"))
    }

    /**
     * A fifty-channel family with deep caches: everything the home screen
     * computes per refresh has to stay in the tens of milliseconds. Generous
     * bound (CI machines vary), but a quadratic regression would blow it.
     */
    @Test
    fun largeLibraryStaysFast() {
        val channels = (1..50).map { c -> (1..500).map { i -> video("c${c}v$i", channel = "Channel $c", views = (i * 7L) % 1000) } }
        val all = channels.flatten()
        val history = all.filterIndexed { i, _ -> i % 9 == 0 }
            .associate { it.url to WatchProgress(30, 60, lastWatchedAt = it.url.hashCode().toLong()) }
        val started = System.nanoTime()
        val feed = interleave(channels.map { it.take(12) }, 80) { it.url }
        val hist = historyItems(history, all, 120)
        val popular = orderByPopularity(channels[0].map { VideoItem(it, null) })
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(80, feed.size)
        assertEquals(120, hist.size)
        assertEquals(500, popular.size)
        assertTrue("took ${elapsedMs}ms", elapsedMs < 1_500)
    }
}
