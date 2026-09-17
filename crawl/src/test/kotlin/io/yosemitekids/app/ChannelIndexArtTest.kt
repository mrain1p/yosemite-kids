package io.yosemitekids.app

import io.yosemitekids.app.data.ChannelIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The index keeps a channel's picture and banner (1.11.0): written once
 * from the first page of a crawl, kept through every later page, carried on
 * the wire to a device that pulls, and absent - never invented - for a
 * channel indexed before this build.
 */
class ChannelIndexArtTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun row(id: String) = ChannelIndex.IndexedVideo(id, "t", "c", null, 60, "UC1")

    @Test
    fun artIsKeptThroughTheManifestAndEveryLaterPage() {
        val dir = tmp.newFolder()
        val index = ChannelIndex(dir)
        index.addVideos("UC1", listOf(row("aaaaaaaaaaa")), complete = false)
        assertNull("nothing crawled on this build yet: no picture, no guess", index.state("UC1")!!.avatarUrl)
        index.setArt("UC1", "https://yt3.ggpht.com/avatar", "https://yt3.ggpht.com/banner")
        assertEquals("https://yt3.ggpht.com/avatar", ChannelIndex(dir).state("UC1")!!.avatarUrl)
        assertEquals("https://yt3.ggpht.com/banner", ChannelIndex(dir).state("UC1")!!.bannerUrl)
        // A later page rebuilds the state; the art survives it.
        index.addVideos("UC1", listOf(row("bbbbbbbbbbb")), complete = true, append = true)
        assertEquals("https://yt3.ggpht.com/avatar", index.state("UC1")!!.avatarUrl)
        // Half an update keeps the other half.
        index.setArt("UC1", null, "https://yt3.ggpht.com/banner2")
        assertEquals("https://yt3.ggpht.com/avatar", index.state("UC1")!!.avatarUrl)
        assertEquals("https://yt3.ggpht.com/banner2", index.state("UC1")!!.bannerUrl)
    }

    @Test
    fun artRidesTheWireToADeviceThatPulls() {
        val dir = tmp.newFolder()
        val index = ChannelIndex(dir)
        index.addVideos("UC1", listOf(row("aaaaaaaaaaa")), complete = true)
        index.setArt("UC1", "https://yt3.ggpht.com/avatar", null)
        val wire = index.exportSourceWithState("UC1")!!
        val other = ChannelIndex(File(tmp.root, "other"))
        other.importSourceWithState("UC1", wire)
        assertEquals("https://yt3.ggpht.com/avatar", other.state("UC1")!!.avatarUrl)
        assertNull(other.state("UC1")!!.bannerUrl)
    }
}
