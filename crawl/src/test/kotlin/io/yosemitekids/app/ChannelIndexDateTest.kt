package io.yosemitekids.app

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.ChannelIndex.IndexedVideo
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The index keeps the upload date and the view count (roadmap §2M).
 *
 * Both were computed by every crawl and dropped on the way to disk, which is
 * the whole reason the browser could not say "3 days ago" or order a channel
 * by "Popular first" while the television could. These pin the three
 * properties the change rests on: an old five-key file still parses (no
 * index version bump, no forced re-crawl), a row written with the two fields
 * reads them back and hands them to [IndexedVideo.toVideo], and a row the
 * index already holds LEARNS them from the next crawl rather than waiting
 * for a re-crawl of the back catalogue.
 */
class ChannelIndexDateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val T = 1_788_771_600_000L
    private val DAY = 86_400_000L

    private fun row(id: String, views: Long? = null, at: Long? = null) =
        IndexedVideo(id, "Video $id", "Chan", null, 600, "UC1", viewCount = views, publishedAt = at)

    @Test
    fun aFiveKeyFileFromAnOlderBuildStillParsesAndCarriesNoDate() {
        val dir = tmp.newFolder()
        File(dir, "UC1.json").writeText(
            JSONArray().put(
                org.json.JSONObject().put("id", "aaaaaaaaaaa").put("t", "Old").put("c", "Chan").put("d", 600)
            ).toString()
        )
        val rows = ChannelIndex(dir).loadSource("UC1")
        assertEquals(1, rows.size)
        assertNull("no date was stored, so none is invented", rows[0].publishedAt)
        assertNull(rows[0].viewCount)
        val video = rows[0].toVideo()
        assertNull("toVideo keeps the null: the feed's own order and no age line", video.publishedAt)
    }

    @Test
    fun aRowRoundTripsItsDateAndCountIntoTheVideoTheFacesDraw() {
        val dir = tmp.newFolder()
        ChannelIndex(dir).addVideos("UC1", listOf(row("aaaaaaaaaaa", views = 1234, at = T - 3 * DAY)), complete = true)
        val back = ChannelIndex(dir).loadSource("UC1").single()
        assertEquals(1234L, back.viewCount)
        assertEquals(T - 3 * DAY, back.publishedAt)
        assertEquals(T - 3 * DAY, back.toVideo().publishedAt)
        assertEquals(1234L, back.toVideo().viewCount)
    }

    @Test
    fun aKnownRowLearnsTheDateOnceAndTheCountEveryCrawl() {
        val dir = tmp.newFolder()
        val index = ChannelIndex(dir)
        // Written by a build that kept neither.
        index.addVideos("UC1", listOf(row("aaaaaaaaaaa"), row("bbbbbbbbbbb")), complete = true)
        // The next delta crawl re-reads page 1 and knows both.
        index.addVideos("UC1", listOf(row("aaaaaaaaaaa", views = 10, at = T - DAY)), complete = true)
        var rows = index.loadSource("UC1")
        assertEquals("the order is untouched: nothing was new", listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), rows.map { it.videoId })
        assertEquals(T - DAY, rows[0].publishedAt)
        assertEquals(10L, rows[0].viewCount)
        assertNull("a row this crawl did not see stays as it was", rows[1].publishedAt)
        // A later crawl: the count moves, the date does not.
        index.addVideos("UC1", listOf(row("aaaaaaaaaaa", views = 25, at = T - 2 * DAY)), complete = true)
        rows = index.loadSource("UC1")
        assertEquals(25L, rows[0].viewCount)
        assertEquals("the first date stays: a date does not change, a re-parse of it might", T - DAY, rows[0].publishedAt)
        // And a crawl that knows nothing new about a known row writes nothing.
        // Back-date the file rather than sleep. A rewrite moves the stamp to
        // now, and a stamp still in 1970 cannot have been rewritten — where
        // `sleep(5)` proved nothing at all on a filesystem that rounds mtime
        // to the second, which is most of them.
        val file = File(dir, "UC1.json")
        val backdated = 1_000_000L
        file.setLastModified(backdated)
        index.addVideos("UC1", listOf(row("aaaaaaaaaaa", views = 25, at = T - DAY)))
        assertEquals("nothing learned, nothing written", backdated, file.lastModified())
        assertFalse(index.loadSource("UC1").isEmpty())
    }
}
