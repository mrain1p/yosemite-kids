package io.yosemitekids.app

import androidx.compose.ui.unit.dp
import io.yosemitekids.app.data.Video
import io.yosemitekids.app.ui.ChannelPreview
import io.yosemitekids.app.ui.FormFactor
import io.yosemitekids.app.ui.TV_CHANNEL_COLUMNS
import io.yosemitekids.app.ui.TV_PAGE_GUTTER
import io.yosemitekids.app.ui.TV_SAFE_GUTTER
import io.yosemitekids.app.ui.VideoItem
import io.yosemitekids.app.ui.channelMetrics
import io.yosemitekids.app.ui.channelPageMeta
import io.yosemitekids.app.ui.favoriteOf
import io.yosemitekids.app.ui.newCountLabel
import io.yosemitekids.app.ui.newestVideo
import io.yosemitekids.app.ui.tvUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Channels tab and the channel page as values.
 *
 * Everything here is a rule the two screens were given and nothing was
 * watching: a ten-foot gutter that a television will crop if it shrinks, a
 * conversion from the handoff's review pixels that is easy to apply to some
 * numbers and forget on others, and three little strings that decide what a
 * five-year-old presses.
 */
class ChannelsScreenTest {

    private fun video(id: String, published: Long? = null) = Video(
        url = "https://youtu.be/$id",
        title = "Video $id",
        channelName = "A channel",
        thumbnailUrl = null,
        durationSeconds = 300,
        publishedAt = published
    )

    private fun item(id: String, published: Long? = null) = VideoItem(video(id, published), null)

    // --- the ten-foot geometry ---------------------------------------------

    @Test
    fun `the television's page gutter clears the 5 percent safe area`() {
        // Televisions overscan, and a family whose set crops the first column
        // has no way to tell anyone. The margin is decided once, in
        // FormFactor.kt, and this is the floor it may never go under.
        assertTrue(
            "TV_PAGE_GUTTER ($TV_PAGE_GUTTER) is inside the safe area ($TV_SAFE_GUTTER)",
            TV_PAGE_GUTTER.value >= TV_SAFE_GUTTER.value
        )
    }

    @Test
    fun `a design unit is three quarters of a dp on the ten-foot layout`() {
        // The handoff's TV frames are drawn 1280 wide and a 1080p Android
        // television is 960 dp wide. Its "×1.5 at 1080p" column is 720p review
        // pixels and misleads — this is the conversion that column is not.
        assertEquals(160.5.dp, tvUnits(214f))
        assertEquals(118.5.dp, tvUnits(158f))
        assertEquals(81.dp, tvUnits(108f))
    }

    @Test
    fun `every television measurement goes through that conversion`() {
        val tv = channelMetrics(FormFactor.Tv)
        assertEquals(tvUnits(158f), tv.art)
        assertEquals(tvUnits(214f), tv.pinnedWidth)
        assertEquals(tvUnits(120f), tv.pinnedHeight)
        assertEquals(tvUnits(108f), tv.pageArt)
        assertEquals(tvUnits(104f), tv.actionCard)
    }

    @Test
    fun `the phone keeps the design's own pixels, and its two targets stay apart`() {
        val phone = channelMetrics(FormFactor.Phone)
        assertEquals(68.dp, phone.art)
        assertEquals(108.dp, phone.pinnedWidth)
        assertEquals(72.dp, phone.pinnedHeight)
        assertEquals(76.dp, phone.pageArt)
        assertEquals(88.dp, phone.actionCard)
        // The round button and the region beside it go to different screens.
        // Any closer and a thumb aimed at one lands on the other.
        assertTrue(phone.rowPlay.value >= 44f)
        assertEquals(20.dp, phone.rowTargetGap)
    }

    @Test
    fun `the ten-foot grid is four across`() {
        assertEquals(4, TV_CHANNEL_COLUMNS)
    }

    // --- what a channel row says -------------------------------------------

    @Test
    fun `the newest video is the newest dated one`() {
        val newest = newestVideo(
            listOf(video("a", 100L), video("c", 900L), video("b", 500L))
        )
        assertEquals("https://youtu.be/c", newest?.url)
    }

    @Test
    fun `with no dates at all the cache's own order stands`() {
        // Cache files arrive newest-first, which is the assumption the NEW
        // badge already makes; a list of nulls must not reorder itself.
        assertEquals("https://youtu.be/a", newestVideo(listOf(video("a"), video("b")))?.url)
    }

    @Test
    fun `an undated video never outranks a dated one`() {
        assertEquals(
            "https://youtu.be/b",
            newestVideo(listOf(video("a"), video("b", 10L)))?.url
        )
    }

    @Test
    fun `an empty channel has no newest video`() {
        assertNull(newestVideo(emptyList()))
        assertNull(ChannelPreview(newCount = 0, latest = null).latest)
    }

    @Test
    fun `a channel with nothing new says nothing`() {
        // Never "0 NEW": the flag is there to be noticed, and a row that
        // always carries one stops being a signal.
        assertEquals("", newCountLabel(0))
        assertEquals("", newCountLabel(-1))
        assertEquals("1 NEW", newCountLabel(1))
        assertEquals("12 NEW", newCountLabel(12))
    }

    // --- the channel page's mono line --------------------------------------

    @Test
    fun `the channel page counts what it has and skips what it hasn't`() {
        assertEquals("30+ videos · 29 playlists", channelPageMeta(30, 29, more = true))
        assertEquals("30 videos", channelPageMeta(30, 0, more = false))
        assertEquals("29 playlists", channelPageMeta(0, 29, more = false))
        // A cache that hasn't landed says nothing rather than "0 videos" —
        // and never opens on a dangling separator.
        assertEquals("", channelPageMeta(0, 0, more = false))
        assertEquals("1 video · 1 playlist", channelPageMeta(1, 1, more = false))
    }

    // --- the Favorite action card ------------------------------------------

    @Test
    fun `the favorite card plays the first hearted video the page is showing`() {
        val items = listOf(item("a"), item("b"), item("c"))
        val chosen = favoriteOf(items, setOf("https://youtu.be/b", "https://youtu.be/c"))
        assertEquals("https://youtu.be/b", chosen?.video?.url)
    }

    @Test
    fun `no favorite here means no card at all`() {
        // The card collapses rather than sitting there inert: a five-year-old
        // will press it, and a control that does nothing is worse than one
        // that is not there.
        assertNull(favoriteOf(listOf(item("a")), setOf("https://youtu.be/zzz")))
        assertNull(favoriteOf(emptyList(), setOf("https://youtu.be/a")))
    }
}
