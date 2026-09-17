package io.yosemitekids.app

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.writeAtomically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The index survives the two ways it was losing a family's channels: a second
 * instance writing over the first's manifest, and a half-written file read as
 * an empty channel and then written back as one.
 *
 * Both were silent. A source that reads as never-crawled looks exactly like a
 * source the crawl has not reached yet, and the crawl's cursor kept pointing
 * past the pages that were gone - so the back catalogue never came back.
 */
class IndexDurabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun video(id: String) = ChannelIndex.IndexedVideo(
        videoId = id, title = "Video $id", channelName = "Test",
        thumbnailUrl = null, durationSeconds = 60, sourceId = "UC1"
    )

    @Test
    fun `a second instance over the same directory does not erase the first's work`() {
        val dir = tmp.newFolder("index")
        val worker = ChannelIndex(dir)
        val request = ChannelIndex(dir)

        // The worker holds its instance for the length of a run; a LAN request
        // builds its own and writes a different source in the middle of it.
        worker.addVideos("UC1", listOf(video("a"), video("b")), complete = true)
        request.importSource(
            "UC2", "[]",
            ChannelIndex.SourceState(count = 0, newestVideoId = null, complete = true)
        )
        worker.addVideos("UC1", listOf(video("c")), append = true)

        val onDisk = ChannelIndex(dir).allStates()
        assertTrue("the pushed source survives the worker's later write", "UC2" in onDisk)
        assertEquals("and the worker's own count is right", 3, onDisk["UC1"]?.count)
    }

    @Test
    fun `a torn source file is kept aside and re-crawled, never written back as a stump`() {
        val dir = tmp.newFolder("index")
        val index = ChannelIndex(dir)
        index.addVideos("UC1", (1..5).map { video("v$it") }, complete = true)
        index.saveCursor("UC1", """{"page":9}""")
        assertEquals(5, index.loadSource("UC1").size)

        // A power cut mid-write used to leave this, and the next crawl page
        // read it as an empty channel and saved itself over the top.
        File(dir, "UC1.json").writeText("""[{"id":"v1","t":"Vid""")

        ChannelIndex(dir).addVideos("UC1", listOf(video("v6")), append = true)

        assertTrue("the unreadable copy is kept", File(dir, "UC1.json.corrupt").exists())
        assertEquals(
            "the cursor is dropped, so the crawl starts again at page one",
            null, ChannelIndex(dir).loadCursor("UC1")
        )
        val rebuilt = ChannelIndex(dir).loadSource("UC1")
        assertEquals("and what arrived is kept rather than thrown away", 1, rebuilt.size)
        assertEquals("v6", rebuilt.first().videoId)
    }

    @Test
    fun `an atomic write replaces the whole document and leaves no temp behind`() {
        val f = File(tmp.newFolder("store"), "deep/config.json")
        f.writeAtomically("""{"a":1}""")
        assertEquals("""{"a":1}""", f.readText())
        f.writeAtomically("""{"b":2}""")
        assertEquals("never a mixture of the two", """{"b":2}""", f.readText())
        assertFalse(File(f.parentFile, f.name + ".tmp").exists())
    }
}
