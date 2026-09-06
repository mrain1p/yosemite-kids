package io.yosemitekids.hub

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.Source
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The scheduler around IndexCrawlRun, which IndexCrawlRunTest owns. What is
 * pinned here is the hub's own: the master gate, the drop pass that runs
 * regardless, and the backoff that keeps a walled NAS address quiet.
 */
class HubCrawlTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val T = 1_780_000_000_000L
    private val ME = ".hub0123456789abcdef0123456789ab"
    private val PHONE = "0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a"
    private var clock = T
    private lateinit var store: HubStore
    private lateinit var index: ChannelIndex

    @Before
    fun setUp() {
        val dir = tmp.newFolder("hub")
        store = HubStore(dir)
        index = ChannelIndex(File(dir, "search-index"))
    }

    private fun entry(id: String) = WhitelistEntry(
        id = id, url = "https://www.youtube.com/channel/$id", label = null, kind = SourceKind.CHANNEL
    )

    private fun config(master: String?, vararg ids: String) {
        store.edit("test", T) {
            Whitelist(sources = ids.map(::entry), blockedVideoIds = emptySet(), masterDeviceToken = master)
        }
    }

    private fun video(sourceId: String) = ChannelIndex.IndexedVideo(
        videoId = "v-$sourceId", title = "t", channelName = "c",
        thumbnailUrl = null, durationSeconds = 60, sourceId = sourceId
    )

    private fun crawl(
        dropped: MutableList<String> = mutableListOf(),
        crawlOnce: suspend (Source) -> Boolean
    ) = HubCrawl(
        store, index, ME, crawlOnce,
        dropSource = { dropped += it; index.dropSource(it) },
        pacingMs = 0
    ) { clock }

    @Test
    fun `only the master crawls`() {
        config(PHONE, "UCa")
        var calls = 0
        val c = crawl { calls++; false }
        assertNull(c.runOnce())
        assertEquals(0, calls)
        assertTrue(c.last, c.last.contains("not building"))
    }

    @Test
    fun `the master crawls a bounded batch and stamps the run`() {
        config(ME, "UCa", "UCb")
        var calls = 0
        val out = crawl { calls++; calls < 5 }.runOnce()!!
        assertEquals(4, out.pages)
        assertFalse(out.failed)
        assertEquals(4, index.lastRunInfo()!!.pages)
    }

    @Test
    fun `a channel the config dropped leaves the index on every pass, master or not`() {
        config(PHONE, "UCa")
        index.addVideos("UCzzz", listOf(video("UCzzz")), complete = true)
        val dropped = mutableListOf<String>()
        crawl(dropped) { false }.runOnce()
        assertEquals(listOf("UCzzz"), dropped)
        assertNull(index.state("UCzzz"))
    }

    @Test
    fun `failed runs back off, doubling to a cap, and one good run resets`() {
        config(ME, "UCa")
        var wall = true
        val c = crawl { if (wall) throw IllegalStateException("429") else false }

        assertTrue(c.runOnce()!!.failed)
        assertEquals(HubCrawl.PERIOD_MS, c.backoffMs)
        clock = T + 60_000L
        assertNull("inside the backoff nothing is fetched", c.runOnce())
        assertTrue(c.last, c.last.contains("backing off"))

        clock = T + HubCrawl.PERIOD_MS
        assertTrue(c.runOnce()!!.failed)
        assertEquals(2 * HubCrawl.PERIOD_MS, c.backoffMs)

        repeat(12) { clock += HubCrawl.MAX_BACKOFF_MS; c.runOnce() }
        assertEquals(HubCrawl.MAX_BACKOFF_MS, c.backoffMs)

        wall = false
        clock += HubCrawl.MAX_BACKOFF_MS
        val ok = c.runOnce()!!
        assertFalse(ok.failed)
        assertEquals(0L, c.backoffMs)
    }
}
