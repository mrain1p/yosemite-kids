package io.yosemitekids.app

import io.yosemitekids.app.data.ChannelPlaylistsCache
import io.yosemitekids.app.data.PlaylistRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure halves of the playlist listing cache: row format and id parsing. */
class ChannelPlaylistsCacheTest {

    private val ref = PlaylistRef(
        id = "PLabc_DEF-123",
        url = "https://www.youtube.com/playlist?list=PLabc_DEF-123",
        name = "Season 1",
        thumbnailUrl = "https://i.ytimg.com/vi/x/hqdefault.jpg",
        videoCount = 42
    )

    @Test
    fun rowRoundTrips() {
        assertEquals(ref, ChannelPlaylistsCache.parseRow(ChannelPlaylistsCache.formatRow(ref)))
    }

    @Test
    fun tabsAndNewlinesInNamesCannotBreakTheRow() {
        val messy = ref.copy(name = "Tab\there\nnewline")
        val back = ChannelPlaylistsCache.parseRow(ChannelPlaylistsCache.formatRow(messy))
        assertEquals("Tab here newline", back?.name)
        assertEquals(messy.id, back?.id)
    }

    @Test
    fun missingThumbnailAndCountReadBackAsUnknown() {
        val bare = ref.copy(thumbnailUrl = null, videoCount = -1)
        assertEquals(bare, ChannelPlaylistsCache.parseRow(ChannelPlaylistsCache.formatRow(bare)))
    }

    @Test
    fun shortOrBlankRowsAreDropped() {
        assertNull(ChannelPlaylistsCache.parseRow(""))
        assertNull(ChannelPlaylistsCache.parseRow("PL1\turl\tname"))
        assertNull(ChannelPlaylistsCache.parseRow("\turl\tname\t\t3"))
    }

    @Test
    fun playlistIdFromEverySpelling() {
        assertEquals("PLabc", ChannelPlaylistsCache.playlistIdFrom("https://www.youtube.com/playlist?list=PLabc"))
        assertEquals("PLabc", ChannelPlaylistsCache.playlistIdFrom("https://youtube.com/watch?v=x&list=PLabc&index=2"))
        assertEquals("OLAK5uy_k", ChannelPlaylistsCache.playlistIdFrom("https://m.youtube.com/playlist?list=OLAK5uy_k"))
        assertNull(ChannelPlaylistsCache.playlistIdFrom("https://www.youtube.com/@handle/playlists"))
    }
}
