package io.yosemitekids.app

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.IndexPull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A device pulling from a hub, with the hub played by a second index on
 * disk: its /index-status is the real statusJson and its /index bodies are
 * the real exports, so the wire format is the one both sides speak.
 */
class IndexPullTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun index(name: String) = ChannelIndex(File(tmp.root, name))

    private fun vids(sourceId: String, vararg ids: String) = ids.map {
        ChannelIndex.IndexedVideo(
            videoId = it, title = "t $it", channelName = "c",
            thumbnailUrl = null, durationSeconds = 60, sourceId = sourceId
        )
    }

    private fun pull(device: ChannelIndex, hub: ChannelIndex, wanted: Set<String>) = runBlocking {
        IndexPull.pull(device, wanted, hub.statusJson()) { hub.exportSourceWithState(it) }
    }

    @Test
    fun `a device with nothing takes what the hub has, and only what the config wants`() {
        val hub = index("hub")
        hub.addVideos("UCa", vids("UCa", "a1", "a2"), complete = true)
        hub.addVideos("UCgone", vids("UCgone", "g1"), complete = true)
        val device = index("device")
        assertEquals(1, pull(device, hub, setOf("UCa")))
        assertEquals(2, device.state("UCa")!!.count)
        assertTrue(device.state("UCa")!!.complete)
        assertNull("a channel this config dropped is not fetched", device.state("UCgone"))
        // Settled: a second pull with nothing new fetches nothing.
        assertEquals(0, pull(device, hub, setOf("UCa")))
    }

    @Test
    fun `a phone that crawled deeper keeps everything it has`() {
        val hub = index("hub")
        hub.addVideos("UCa", vids("UCa", "a1", "a2"), complete = false)
        val device = index("device")
        device.addVideos("UCa", vids("UCa", "a1", "a2", "a3", "a4"), complete = false)
        assertEquals("the hub is behind: nothing to take", 0, pull(device, hub, setOf("UCa")))
        assertEquals(4, device.state("UCa")!!.count)

        // The hub catches up and passes it with a video the phone never saw.
        hub.addVideos("UCa", vids("UCa", "a3", "a4", "a5"), complete = true, append = true)
        assertEquals(1, pull(device, hub, setOf("UCa")))
        val s = device.state("UCa")!!
        assertEquals("union, not replacement", 5, s.count)
        assertTrue(s.complete)
    }

    @Test
    fun `completeness alone is worth fetching`() {
        val hub = index("hub")
        hub.addVideos("UCa", vids("UCa", "a1"), complete = true)
        val device = index("device")
        device.addVideos("UCa", vids("UCa", "a1"), complete = false)
        assertEquals(1, pull(device, hub, setOf("UCa")))
        assertTrue(device.state("UCa")!!.complete)
        // And a hub that later un-completes cannot take the flag back: the
        // union keeps the stronger claim, and the phone's own crawl would
        // clear it on evidence (ChannelIndex.addVideos), not on hearsay.
        hub.addVideos("UCa", emptyList(), complete = false)
        assertEquals(0, pull(device, hub, setOf("UCa")))
        assertTrue(device.state("UCa")!!.complete)
    }

    @Test
    fun `an unreachable hub or a bad body imports nothing`() {
        val device = index("device")
        assertEquals(0, runBlocking { IndexPull.pull(device, setOf("UCa"), null) { "x" } })
        assertEquals(0, runBlocking { IndexPull.pull(device, setOf("UCa"), "not json") { "x" } })
        assertEquals(0, runBlocking {
            IndexPull.pull(device, setOf("UCa"), """{"UCa":{"count":3,"complete":true,"hash":1}}""") { "garbage" }
        })
        assertNull(device.state("UCa"))
        assertFalse(IndexPull.shouldFetch(null, IndexPull.Remote(0, false, 0)))
    }
}
