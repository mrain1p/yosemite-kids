package io.yosemitekids.app

import io.yosemitekids.app.data.SearchOrder
import io.yosemitekids.app.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The search screen's chips, and the one order they deliberately do not offer. */
class SearchOrderTest {

    private fun v(id: String, seconds: Long) =
        Video("https://www.youtube.com/watch?v=$id", id, "c", null, seconds)

    private val hits = listOf(v("aaaaaaaaaaa", 600), v("bbbbbbbbbbb", 0), v("ccccccccccc", 90))

    @Test
    fun bestMatchIsTheListAsRanked() {
        assertEquals(hits, SearchOrder.order(hits, SearchOrder.BEST, 1L) { it.durationSeconds })
        // An order nobody has heard of falls back to relevance rather than to
        // an empty screen — a chip from a newer build must not blank the page.
        assertEquals(hits, SearchOrder.order(hits, "whatever", 1L) { it.durationSeconds })
    }

    @Test
    fun shortestFirstPutsUnknownDurationsLast() {
        assertEquals(
            listOf("ccccccccccc", "aaaaaaaaaaa", "bbbbbbbbbbb"),
            SearchOrder.order(hits, SearchOrder.SHORT, 1L) { it.durationSeconds }.map { it.title }
        )
    }

    @Test
    fun newestPutsUndatedRowsLastInTheirRelevanceOrder() {
        // An index row from before 1.9.0 has no date. It sorts last, still in
        // relevance order, and is never dropped: not knowing when a video came
        // out is no reason to keep it from a child.
        val dated = mapOf("aaaaaaaaaaa" to 100L, "ccccccccccc" to 300L)
        assertEquals(
            listOf("ccccccccccc", "aaaaaaaaaaa", "bbbbbbbbbbb"),
            SearchOrder.order(hits, SearchOrder.RECENT, 1L, publishedAt = { dated[it.title] }) { it.durationSeconds }.map { it.title }
        )
        // Two undated rows keep the order they arrived in.
        assertEquals(
            listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"),
            SearchOrder.order(hits, SearchOrder.RECENT, 1L, publishedAt = { null }) { it.durationSeconds }.map { it.title }
        )
    }

    @Test
    fun mixHoldsStillForOneSeedAndMovesForAnother() {
        val once = SearchOrder.order(hits, SearchOrder.MIX, 42L) { it.durationSeconds }
        assertEquals(once, SearchOrder.order(hits, SearchOrder.MIX, 42L) { it.durationSeconds })
        assertEquals(hits.toSet(), once.toSet())
        val orders = (1L..40L).map { SearchOrder.order(hits, SearchOrder.MIX, it) { v -> v.durationSeconds } }
        assertNotEquals(1, orders.toSet().size)
    }

    /**
     * The reason "Most recent" and "Popular" are not chips. If this ever
     * fails, the index has grown the fields and both chips become buildable —
     * which is exactly the moment to add them.
     */
    @Test
    fun theIndexStillCarriesNeitherADateNorAViewCount() {
        val fromIndex = io.yosemitekids.app.data.ChannelIndex.IndexedVideo(
            videoId = "aaaaaaaaaaa", title = "t", channelName = "c",
            thumbnailUrl = null, durationSeconds = 90, sourceId = "UCx"
        ).toVideo()
        assertNull("an indexed hit has no upload date to sort by", fromIndex.publishedAt)
        assertNull("an indexed hit has no view count to sort by", fromIndex.viewCount)
    }
}
