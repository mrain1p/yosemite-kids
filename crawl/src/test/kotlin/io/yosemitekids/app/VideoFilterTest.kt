package io.yosemitekids.app

import io.yosemitekids.app.data.VIDEO_FILTER_NEW
import io.yosemitekids.app.data.VIDEO_FILTER_POPULAR
import io.yosemitekids.app.data.Video
import io.yosemitekids.app.ui.VideoItem
import io.yosemitekids.app.ui.filterVideos
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chips over a shelf of videos, and what "New" has to mean.
 *
 * It used to mean "whatever order the list arrived in", which is right for
 * one channel's feed — YouTube hands those over newest-first — and wrong for
 * every shelf built from more than one. The home feed concatenates each
 * channel's cached page, so the first card under *New* was the newest video
 * of whichever channel came first in the family's list: three years old if
 * that channel has not posted since, sitting above something from last week.
 * That is what a parent saw on a phone with seventy-six videos on the shelf.
 */
class VideoFilterTest {

    private val day = 24 * 60 * 60 * 1000L

    private fun v(name: String, agoDays: Long?, views: Long? = null) = VideoItem(
        Video(
            url = "https://youtu.be/$name",
            title = name,
            channelName = "c",
            thumbnailUrl = null,
            durationSeconds = 300,
            viewCount = views,
            publishedAt = agoDays?.let { 1_790_000_000_000L - it * day }
        ),
        progress = null
    )

    private fun titles(items: List<VideoItem>) = items.map { it.video.title }

    @Test
    fun `New is newest first, across channels`() {
        // Two channels' pages concatenated, each newest-first within itself.
        // The old behaviour returned exactly this list back.
        val shelf = listOf(
            v("storybots-3-years", 1100),
            v("storybots-4-years", 1500),
            v("dannygo-3-weeks", 21),
            v("dannygo-2-months", 60)
        )
        assertEquals(
            listOf("dannygo-3-weeks", "dannygo-2-months", "storybots-3-years", "storybots-4-years"),
            titles(filterVideos(shelf, VIDEO_FILTER_NEW, seed = 1L, mixed = true))
        )
    }

    @Test
    fun `a video with no date sorts last, not first`() {
        // The extractor does not always have one, and an undated video is not
        // a new one. Putting a null first would be the same bug wearing a
        // different hat.
        val shelf = listOf(v("undated", null), v("last-week", 7), v("last-year", 365))
        assertEquals(
            listOf("last-week", "last-year", "undated"),
            titles(filterVideos(shelf, VIDEO_FILTER_NEW, seed = 1L, mixed = true))
        )
    }

    @Test
    fun `a feed with no dates at all keeps the order it arrived in`() {
        // Which is what a single channel page relies on: the sort is stable
        // and every key is equal, so nothing moves.
        val shelf = listOf(v("first", null), v("second", null), v("third", null))
        assertEquals(
            listOf("first", "second", "third"),
            titles(filterVideos(shelf, VIDEO_FILTER_NEW, seed = 1L, mixed = true))
        )
    }

    @Test
    fun `one channel's own feed keeps the order YouTube gave it`() {
        // The other half of the rule, and the reason it is a flag rather than
        // one sort for everything. A channel page arrives newest-first already
        // and its dates are patchy, so sorting by date would move every
        // undated video to the end of a list that was right to begin with.
        val feed = listOf(v("newest", 1), v("undated-but-second", null), v("older", 30))
        assertEquals(
            listOf("newest", "undated-but-second", "older"),
            titles(filterVideos(feed, VIDEO_FILTER_NEW, seed = 1L))
        )
    }

    @Test
    fun `Popular is untouched by any of this`() {
        val shelf = listOf(v("quiet", 1, views = 10), v("loud", 400, views = 9_000))
        assertEquals(
            listOf("loud", "quiet"),
            titles(filterVideos(shelf, VIDEO_FILTER_POPULAR, seed = 1L))
        )
    }

    @Test
    fun `an unknown chip changes nothing`() {
        val shelf = listOf(v("a", 5), v("b", 1))
        assertEquals(listOf("a", "b"), titles(filterVideos(shelf, "not-a-filter", seed = 1L)))
    }
}
