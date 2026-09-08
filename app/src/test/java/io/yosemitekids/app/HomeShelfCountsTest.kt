package io.yosemitekids.app

import io.yosemitekids.app.data.Source
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Video
import io.yosemitekids.app.ui.HomeShelf
import io.yosemitekids.app.ui.UiState
import io.yosemitekids.app.ui.VideoItem
import io.yosemitekids.app.ui.homeShelfCounts
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The half of the shelf model that reads Compose state, and therefore stays
 * in `:app`. The rest of it — the catalogue, the saved order, the pins and
 * the opening focus — is covered in `core/src/test/HomeSectionsTest.kt`,
 * where it also covers the browser home.
 */
class HomeShelfCountsTest {

    private fun source(id: String) =
        Source(id, "https://youtube.com/$id", id, null, SourceKind.CHANNEL)

    private fun item(id: String) = VideoItem(
        Video("https://youtu.be/$id", "Video $id", "A channel", null, 100), null
    )

    @Test
    fun `counts come from the state each shelf actually draws`() {
        val state = UiState(
            channels = listOf(source("a"), source("b")),
            keepWatching = listOf(item("k1")),
            suggested = emptyList(),
            feed = listOf(item("f1"), item("f2"), item("f3")),
            recentHistory = listOf(item("h1"))
        )
        val counts = homeShelfCounts(state)
        assertEquals(0, counts[HomeShelf.PINNED])
        assertEquals(2, counts[HomeShelf.CHANNELS])
        assertEquals(1, counts[HomeShelf.KEEP_WATCHING])
        assertEquals(0, counts[HomeShelf.SUGGESTED])
        assertEquals(3, counts[HomeShelf.VIDEOS])
        assertEquals(1, counts[HomeShelf.HISTORY])
    }
}
