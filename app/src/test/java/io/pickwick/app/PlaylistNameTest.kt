package io.pickwick.app

import io.pickwick.app.ui.cleanPlaylistName
import org.junit.Assert.assertEquals
import org.junit.Test

/** Channel stamps come off playlist titles on the channel's own page. */
class PlaylistNameTest {
    @Test
    fun stripsTheChannelStampInAnyCommonSpelling() {
        assertEquals("The World of Insects", cleanPlaylistName("The World of Insects | SciShow Kids", "SciShow Kids"))
        assertEquals("Let's Explore Mars!", cleanPlaylistName("Let's Explore Mars! | Scishow Kids", "SciShow Kids"))
        assertEquals("Songs", cleanPlaylistName("Sesame Street - Songs", "Sesame Street"))
        assertEquals("Songs", cleanPlaylistName("Songs • Sesame Street", "Sesame Street"))
    }

    @Test
    fun leavesOtherTitlesAlone() {
        assertEquals("Science at the Beach! | NGSS Grades 1-3", cleanPlaylistName("Science at the Beach! | NGSS Grades 1-3 | SciShow Kids", "SciShow Kids"))
        assertEquals("SciShow Kids", cleanPlaylistName("SciShow Kids", "SciShow Kids"))
        assertEquals("Plain", cleanPlaylistName(" Plain ", ""))
    }
}
