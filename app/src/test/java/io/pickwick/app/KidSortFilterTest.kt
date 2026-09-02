package io.pickwick.app

import io.pickwick.app.data.CHANNEL_ORDER_ALPHA
import io.pickwick.app.data.CHANNEL_ORDER_LATEST
import io.pickwick.app.data.CHANNEL_ORDER_RANDOM
import io.pickwick.app.data.CHANNEL_ORDER_WATCHED
import io.pickwick.app.data.CHANNEL_LAYOUT_NEWEST
import io.pickwick.app.data.CHANNEL_LAYOUT_POPULAR
import io.pickwick.app.data.Source
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.VIDEO_FILTER_NEW
import io.pickwick.app.data.VIDEO_FILTER_POPULAR
import io.pickwick.app.data.VIDEO_FILTER_RANDOM
import io.pickwick.app.data.Video
import io.pickwick.app.data.VideoCache
import io.pickwick.app.ui.VideoItem
import io.pickwick.app.ui.defaultFilterFor
import io.pickwick.app.ui.filterVideos
import io.pickwick.app.ui.orderChannels
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
