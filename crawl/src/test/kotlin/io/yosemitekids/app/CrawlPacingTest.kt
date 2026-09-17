package io.yosemitekids.app

import io.yosemitekids.app.data.IndexCrawlRun
import io.yosemitekids.app.data.IndexCrawler
import io.yosemitekids.app.data.PlaylistCrawlRun
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anti-ban numbers, pinned.
 *
 * Four seconds between fetches and sixty pages a run ARE the mitigation
 * against YouTube walling the family's address; the comments beside them
 * said so and enforced nothing, and every crawl test passes `delayMs = 0`
 * to run fast, so a real value of zero would have passed the suite. These
 * hold the floor and the ceilings; guard 72 holds the hub and the worker to
 * running with them rather than overriding them.
 */
class CrawlPacingTest {

    @Test
    fun fetchesAreAtLeastFourSecondsApart() {
        assertTrue("CRAWL_DELAY_MS is the pause YouTube sees between pages", IndexCrawler.CRAWL_DELAY_MS >= 4_000L)
    }

    @Test
    fun aRunIsBounded() {
        assertTrue("sixty pages a run: an Android worker's ten minutes, and a footprint that reads as browsing", IndexCrawlRun.PAGES_PER_RUN in 1..60)
        assertTrue("the playlist pass is a small budget on top, not a second crawl", PlaylistCrawlRun.FETCHES_PER_RUN in 1..12)
        assertTrue(PlaylistCrawlRun.PLAYLISTS_PER_SOURCE in 1..30)
        assertTrue("a listing is asked for at most once a day", PlaylistCrawlRun.REFRESH_MS >= 24 * 60 * 60_000L)
    }
}
