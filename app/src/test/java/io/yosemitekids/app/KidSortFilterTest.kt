package io.yosemitekids.app

import io.yosemitekids.app.data.CHANNEL_ORDER_ADDED
import io.yosemitekids.app.data.CHANNEL_ORDER_ALPHA
import io.yosemitekids.app.data.CHANNEL_ORDER_ALPHA_DESC
import io.yosemitekids.app.data.CHANNEL_ORDER_LATEST
import io.yosemitekids.app.data.CHANNEL_ORDER_RANDOM
import io.yosemitekids.app.data.CHANNEL_ORDER_WATCHED
import io.yosemitekids.app.data.CHANNEL_LAYOUT_NEWEST
import io.yosemitekids.app.data.CHANNEL_LAYOUT_POPULAR
import io.yosemitekids.app.data.Source
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.VIDEO_FILTER_NEW
import io.yosemitekids.app.data.VIDEO_FILTER_POPULAR
import io.yosemitekids.app.data.VIDEO_FILTER_RANDOM
import io.yosemitekids.app.data.Video
import io.yosemitekids.app.data.VideoCache
import io.yosemitekids.app.ui.VideoItem
import io.yosemitekids.app.ui.defaultFilterFor
import io.yosemitekids.app.ui.filterVideos
import io.yosemitekids.app.ui.orderChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The kid-facing sort and filter chips (HomeState.orderChannels / filterVideos). */
class KidSortFilterTest {

    private fun src(id: String, name: String) =
        Source(id, "https://youtube.com/channel/$id", name, null, SourceKind.CHANNEL)

    private val channels = listOf(src("UCb", "Bluey"), src("UCa", "Arthur"), src("UCc", "Curious George"))
    private val opens = mapOf("UCb" to 1, "UCa" to 5, "UCc" to 3)
    private val latest = mapOf("UCb" to 300L, "UCa" to 100L)

    @Test
    fun mostWatchedIsMostOpened() {
        assertEquals(
            listOf("UCa", "UCc", "UCb"),
            orderChannels(channels, CHANNEL_ORDER_WATCHED, { opens[it] ?: 0 }, { latest[it] }, 1L).map { it.id }
        )
    }

    @Test
    fun alphabeticalIgnoresCase() {
        assertEquals(
            listOf("Arthur", "Bluey", "Curious George"),
            orderChannels(channels, CHANNEL_ORDER_ALPHA, { 0 }, { null }, 1L).map { it.name }
        )
    }

    @Test
    fun reverseAlphabeticalIsTheAlphabetBackwards() {
        assertEquals(
            listOf("Curious George", "Bluey", "Arthur"),
            orderChannels(channels, CHANNEL_ORDER_ALPHA_DESC, { 0 }, { null }, 1L).map { it.name }
        )
        // Not merely "the A-to-Z list reversed by accident of stability": the
        // two must be exact mirrors of each other for a distinct name set.
        assertEquals(
            orderChannels(channels, CHANNEL_ORDER_ALPHA, { 0 }, { null }, 1L).reversed(),
            orderChannels(channels, CHANNEL_ORDER_ALPHA_DESC, { 0 }, { null }, 1L)
        )
    }

    @Test
    fun justAddedLeadsWithTheNewestArrivalAndKeepsListOrderForTheRest() {
        // Only UCc has ever been stamped; the other two predate the store and
        // sort 0, which must leave them in the whitelist's own order.
        //
        // Keyed by URL, and that is the point of the test rather than an
        // incidental detail. Resolution canonicalizes /user/, /c/ and @handle
        // entries to their UC… form, so a store written from the whitelist's
        // id and read back from the resolved Source's id misses every channel
        // a parent pasted as a handle — silently, with the sort coming back in
        // list order looking like it was never wired up. Seeding this map by
        // id is exactly that bug, and it is how it first shipped.
        val added = mapOf(src("UCc", "Curious George").url to 5_000L)
        assertEquals(
            listOf("UCc", "UCb", "UCa"),
            orderChannels(channels, CHANNEL_ORDER_ADDED, { 0 }, { null }, 1L) { added[it] ?: 0L }
                .map { it.id }
        )
        // With nothing stamped at all the order is exactly the list's.
        assertEquals(
            channels.map { it.id },
            orderChannels(channels, CHANNEL_ORDER_ADDED, { 0 }, { null }, 1L).map { it.id }
        )
    }

    @Test
    fun latestVideoFirstAndUndatedLast() {
        assertEquals(
            listOf("UCb", "UCa", "UCc"),
            orderChannels(channels, CHANNEL_ORDER_LATEST, { 0 }, { latest[it] }, 1L).map { it.id }
        )
    }

    @Test
    fun randomHoldsStillForOneSeedAndMovesForAnother() {
        val once = orderChannels(channels, CHANNEL_ORDER_RANDOM, { 0 }, { null }, 42L)
        assertEquals(once, orderChannels(channels, CHANNEL_ORDER_RANDOM, { 0 }, { null }, 42L))
        assertEquals(channels.toSet(), once.toSet())
        // Some seed produces a different order (three items have six orders).
        val orders = (1L..40L).map { orderChannels(channels, CHANNEL_ORDER_RANDOM, { 0 }, { null }, it) }.toSet()
        assertNotEquals(1, orders.size)
    }

    private fun item(url: String, views: Long?) =
        VideoItem(Video(url, url, "c", null, 60, viewCount = views), null)

    @Test
    fun newKeepsTheFeedOrderAndPopularSortsByViews() {
        val items = listOf(item("a", 10), item("b", null), item("c", 99))
        assertEquals(listOf("a", "b", "c"), filterVideos(items, VIDEO_FILTER_NEW, 1L).map { it.video.url })
        assertEquals(listOf("a", "b", "c"), filterVideos(items, null, 1L).map { it.video.url })
        assertEquals(listOf("c", "a", "b"), filterVideos(items, VIDEO_FILTER_POPULAR, 1L).map { it.video.url })
    }

    @Test
    fun randomIsSeeded() {
        val items = (1..8).map { item("v$it", null) }
        assertEquals(filterVideos(items, VIDEO_FILTER_RANDOM, 7L), filterVideos(items, VIDEO_FILTER_RANDOM, 7L))
        assertEquals(items.toSet(), filterVideos(items, VIDEO_FILTER_RANDOM, 7L).toSet())
    }

    @Test
    fun parentsLayoutIsTheDefaultFilter() {
        assertEquals(VIDEO_FILTER_POPULAR, defaultFilterFor(CHANNEL_LAYOUT_POPULAR))
        assertEquals(VIDEO_FILTER_NEW, defaultFilterFor(CHANNEL_LAYOUT_NEWEST))
    }

    @Test
    fun cacheRowsCarryThePublishedDateAndReadOldRowsWithout() {
        val dated = Video("u", "t", "c", null, 30, viewCount = 5, publishedAt = 1_700_000_000_000L)
        val back = VideoCache.parseRow(VideoCache.formatRow(dated))
        assertEquals(dated, back)
        // A six-cell row from the previous build.
        assertNull(VideoCache.parseRow("u\tt\tc\t\t30\t5")!!.publishedAt)
        assertEquals(5L, VideoCache.parseRow("u\tt\tc\t\t30\t5")!!.viewCount)
    }
}
