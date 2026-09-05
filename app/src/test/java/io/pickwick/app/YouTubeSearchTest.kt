package io.pickwick.app

import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.WhitelistEntry
import io.pickwick.app.ui.YouTubeHit
import io.pickwick.app.ui.channelHitMeta
import io.pickwick.app.ui.hitCountLine
import io.pickwick.app.ui.isAdded
import io.pickwick.app.ui.playlistHitMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Add from YouTube" page's meta lines, count line and already-added check. */
class YouTubeSearchTest {

    @Test
    fun channelMetaShowsOnlyWhatYouTubeGave() {
        assertEquals(
            "Channel · 1.2M subscribers · science for kids",
            channelHitMeta(1_200_000, "science for kids")
        )
        // A hidden count comes back as 0 or -1 from the extractor: never "0 subscribers".
        assertEquals("Channel · science for kids", channelHitMeta(0, " science for kids "))
        assertEquals("Channel", channelHitMeta(-1, null))
        assertEquals("Channel · 912.0K subscribers", channelHitMeta(912_000, "   "))
    }

    @Test
    fun playlistMetaNamesTheUploader() {
        assertEquals("Playlist · 84 videos · by Operation Ouch", playlistHitMeta(84, "Operation Ouch"))
        assertEquals("Playlist · 1 video", playlistHitMeta(1, null))
        assertEquals("Playlist", playlistHitMeta(0, ""))
    }

    @Test
    fun countLineOnlyMentionsAddedWhenSomeAre() {
        assertEquals("8 results · 2 already added", hitCountLine(8, 2))
        assertEquals("1 result", hitCountLine(1, 0))
    }

    @Test
    fun addedMatchesByIdOrUrl() {
        val entry = WhitelistEntry("UC1", "https://www.youtube.com/channel/UC1", "X", SourceKind.CHANNEL)
        val hit = YouTubeHit(entry, "X", entry.url, "Channel")
        assertTrue(isAdded(hit, listOf(entry.copy(label = "renamed"))))
        assertTrue(isAdded(hit, listOf(entry.copy(id = "@x"))))
        assertFalse(isAdded(hit, listOf(entry.copy(id = "UC2", url = "https://www.youtube.com/channel/UC2"))))
    }
}
