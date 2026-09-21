package io.yosemitekids.app

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.PlaylistCrawlRun
import io.yosemitekids.app.data.PlaylistRef
import io.yosemitekids.app.data.Source
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Video
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
 * The playlist crawl's rules: a bounded budget that carries an unfinished
 * channel into the next run, a listing refreshed once a day, the Shorts
 * playlist dropped, a channel YouTube says is gone left alone, and a failure
 * counted and stepped over. The hub runs this loop; nothing else does.
 */
class PlaylistCrawlRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val DAY = 86_400_000L
    private fun newIndex() = ChannelIndex(File(tmp.root, "search-index"))
    private fun channel(id: String) = Source(id, "https://www.youtube.com/channel/$id", id, null, SourceKind.CHANNEL)
    private fun ref(id: String, name: String = id) = PlaylistRef(id, "https://www.youtube.com/playlist?list=$id", name, null, 3)
    private fun video(id: String) = Video("https://www.youtube.com/watch?v=$id", id, "c", null, 60)

    @Test
    fun `a channel's playlists and their videos land in the index, Shorts dropped`() = runBlocking {
        val index = newIndex()
        val fetched = mutableListOf<String>()
        val out = PlaylistCrawlRun.run(
            index, listOf(channel("UC1")),
            listPlaylists = { fetched += "list:${it.id}"; listOf(ref("PL1", "Songs"), ref("PLS", "Shorts"), ref("PL2", "Stories")) },
            playlistVideos = { fetched += "pl:${it.id}"; listOf(video("aaaaaaaaaaa"), video("bbbbbbbbbbb"), video("aaaaaaaaaaa")) },
            delayMs = 0, now = { 1_000L }
        )
        assertEquals(listOf("list:UC1", "pl:PL1", "pl:PL2"), fetched)
        assertEquals(3, out.fetches)
        assertEquals(1, out.refreshed)
        val listing = index.loadPlaylists("UC1")!!
        assertTrue(listing.complete)
        assertEquals(1_000L, listing.at)
        assertEquals(listOf("Songs", "Stories"), listing.playlists.map { it.name })
        assertEquals("ids once, in playlist order", listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), listing.playlists[0].videoIds)
        assertEquals("playlists: 3 fetches, 1 channel(s) refreshed", out.summary)
    }

    @Test
    fun `the budget ends a run mid-channel and the next run carries on where it stopped`() = runBlocking {
        val index = newIndex()
        val fetched = mutableListOf<String>()
        val refs = (1..5).map { ref("PL$it") }
        val list: suspend (Source) -> List<PlaylistRef> = { fetched += "list:${it.id}"; refs }
        val videos: suspend (PlaylistRef) -> List<Video> = { fetched += "pl:${it.id}"; listOf(video("v${it.id}0000000")) }
        val first = PlaylistCrawlRun.run(index, listOf(channel("UC1"), channel("UC2")), list, videos, delayMs = 0, fetchesPerRun = 3, now = { 1_000L })
        assertEquals(listOf("list:UC1", "pl:PL1", "pl:PL2"), fetched)
        assertEquals(0, first.refreshed)
        val partial = index.loadPlaylists("UC1")!!
        assertFalse(partial.complete)
        assertEquals("what was fetched is kept", 2, partial.playlists.count { it.videoIds != null })
        // Next run: the unfinished channel first, without listing it again.
        fetched.clear()
        PlaylistCrawlRun.run(index, listOf(channel("UC1"), channel("UC2")), list, videos, delayMs = 0, fetchesPerRun = 4, now = { 2_000L })
        assertEquals(listOf("pl:PL3", "pl:PL4", "pl:PL5", "list:UC2"), fetched)
        assertTrue(index.loadPlaylists("UC1")!!.complete)
        assertEquals("finished on this run, stamped with this run's time", 2_000L, index.loadPlaylists("UC1")!!.at)
    }

    @Test
    fun `a finished listing is left alone for a day, and a gone channel is never asked`() = runBlocking {
        val index = newIndex()
        var t = 1_000L
        val fetched = mutableListOf<String>()
        val list: suspend (Source) -> List<PlaylistRef> = { fetched += "list:${it.id}"; listOf(ref("PL1")) }
        val videos: suspend (PlaylistRef) -> List<Video> = { fetched += "pl:${it.id}"; emptyList() }
        PlaylistCrawlRun.run(index, listOf(channel("UC1")), list, videos, delayMs = 0, now = { t })
        assertEquals(2, fetched.size)
        fetched.clear()
        t += PlaylistCrawlRun.REFRESH_MS - 1
        PlaylistCrawlRun.run(index, listOf(channel("UC1")), list, videos, delayMs = 0, now = { t })
        assertTrue("not yet a day: nothing asked", fetched.isEmpty())
        t += 2
        PlaylistCrawlRun.run(index, listOf(channel("UC1")), list, videos, delayMs = 0, now = { t })
        assertEquals("a day on: listed again", listOf("list:UC1", "pl:PL1"), fetched)
        // Gone from YouTube: its playlists are not worth a fetch either.
        fetched.clear()
        index.markGone("UC2", "The channel was terminated.", t)
        PlaylistCrawlRun.run(index, listOf(channel("UC2")), list, videos, delayMs = 0, now = { t })
        assertTrue(fetched.isEmpty())
        assertNull(index.loadPlaylists("UC2"))
    }

    @Test
    fun `a failed fetch is counted, reported and stepped over`() = runBlocking {
        val index = newIndex()
        val seen = mutableListOf<Throwable>()
        val out = PlaylistCrawlRun.run(
            index, listOf(channel("UC1"), channel("UC2")),
            listPlaylists = { if (it.id == "UC1") throw IllegalStateException("boom") else listOf(ref("PL1")) },
            playlistVideos = { emptyList() },
            onFailure = { seen += it }, delayMs = 0, now = { 1_000L }
        )
        assertEquals(1, out.failures)
        assertEquals("boom", seen.single().message)
        assertNull(index.loadPlaylists("UC1"))
        assertTrue("UC2 was still done", index.loadPlaylists("UC2")!!.complete)
        assertEquals("playlists: 3 fetches, 1 channel(s) refreshed, 1 failed", out.summary)
    }

    @Test
    fun `a playlist YouTube has deleted is parked rather than re-fetched for ever`() = runBlocking {
        val index = newIndex()
        val asked = mutableListOf<String>()
        val gone = org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException(
            "Got error: \"The playlist does not exist.\""
        )
        val out = PlaylistCrawlRun.run(
            index, listOf(channel("UC1")),
            listPlaylists = { listOf(ref("PL1", "Songs"), ref("PLDEAD", "Gone")) },
            playlistVideos = { r ->
                asked += r.id
                if (r.id == "PLDEAD") throw gone else listOf(video("aaaaaaaaaaa"))
            },
            delayMs = 0, now = { 1_000L }
        )

        assertEquals("both were asked once", listOf("PL1", "PLDEAD"), asked)
        assertEquals("and the deleted one is still counted a failure", 1, out.failures)

        // The point: the listing COMPLETES. Before this, videoIds stayed null
        // for the dead playlist, the listing never completed, `due` always
        // held this channel, and the pass asked YouTube for a playlist that
        // does not exist every fifteen minutes until somebody noticed.
        val listing = index.loadPlaylists("UC1")!!
        assertTrue("the channel's listing is finished", listing.complete)
        assertEquals(listOf("aaaaaaaaaaa"), listing.playlists.first { it.id == "PL1" }.videoIds)
        assertEquals(
            "the dead one is answered with nothing, not left unanswered",
            emptyList<String>(), listing.playlists.first { it.id == "PLDEAD" }.videoIds
        )

        // And the next run leaves it alone until the daily refresh is due.
        val again = PlaylistCrawlRun.run(
            index, listOf(channel("UC1")),
            listPlaylists = { throw IllegalStateException("must not re-list") },
            playlistVideos = { throw IllegalStateException("must not re-fetch") },
            delayMs = 0, now = { 2_000L }
        )
        assertEquals(0, again.fetches)
    }

    @Test
    fun `a playlist that merely timed out is tried again on the next run`() = runBlocking {
        val index = newIndex()
        var attempts = 0
        PlaylistCrawlRun.run(
            index, listOf(channel("UC1")),
            listPlaylists = { listOf(ref("PL1", "Songs")) },
            playlistVideos = { attempts++; throw java.io.IOException("timed out") },
            delayMs = 0, now = { 1_000L }
        )
        val listing = index.loadPlaylists("UC1")!!
        assertFalse("a timeout is not an answer, so the listing is unfinished", listing.complete)
        assertNull(listing.playlists.first().videoIds)

        PlaylistCrawlRun.run(
            index, listOf(channel("UC1")),
            listPlaylists = { listOf(ref("PL1", "Songs")) },
            playlistVideos = { attempts++; listOf(video("aaaaaaaaaaa")) },
            delayMs = 0, now = { 2_000L }
        )
        assertEquals("asked again", 2, attempts)
        assertTrue(index.loadPlaylists("UC1")!!.complete)
    }
}
