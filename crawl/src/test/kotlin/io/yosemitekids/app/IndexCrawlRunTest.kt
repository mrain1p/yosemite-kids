package io.yosemitekids.app

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.IndexCrawlRun
import io.yosemitekids.app.data.Source
import io.yosemitekids.app.data.SourceKind
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
 * The loop the worker and the hub share. Its rules are the worker's old
 * ones, pinned here now that two callers depend on them: a bounded batch,
 * round-robin from the first incomplete source, a failure counted and
 * stepped over, and the run stamped either way so the diagnostics line in
 * settings never reads "hasn't run" after a run that had nothing to do.
 */
class IndexCrawlRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newIndex() = ChannelIndex(File(tmp.root, "search-index"))

    private fun source(id: String) =
        Source(id, "https://www.youtube.com/@$id", id, null, SourceKind.values().first())

    private fun vids(sourceId: String, vararg ids: String) = ids.map {
        ChannelIndex.IndexedVideo(
            videoId = it, title = "t $it", channelName = "c",
            thumbnailUrl = null, durationSeconds = 60, sourceId = sourceId
        )
    }

    @Test
    fun `a complete source is never fetched, and the empty run is still stamped`() = runBlocking {
        val index = newIndex()
        index.addVideos("a", vids("a", "a1"), complete = true)
        var calls = 0
        val outcome = IndexCrawlRun.run(
            index, listOf(source("a")), crawlOnce = { calls++; true }, delayMs = 0
        )
        assertEquals(0, calls)
        assertEquals(0, outcome.pages)
        assertEquals(1, outcome.complete)
        assertFalse(outcome.failed)
        val run = index.lastRunInfo()!!
        assertEquals(0, run.pages)
        assertFalse(run.failed)
    }

    @Test
    fun `the batch is capped and round-robins from the first incomplete source`() = runBlocking {
        val index = newIndex()
        val order = mutableListOf<String>()
        // "a" has three pages then reports no more; "b" is endless. With a
        // cap of five: a a a, a's "no more", then b b until the cap.
        val outcome = IndexCrawlRun.run(
            index, listOf(source("a"), source("b")),
            crawlOnce = { s ->
                order += s.id
                if (s.id == "a") order.count { it == "a" } < 4 else true
            },
            delayMs = 0, pagesPerRun = 5
        )
        assertEquals(listOf("a", "a", "a", "a", "b", "b"), order)
        assertEquals(5, outcome.pages)
        assertEquals(0, outcome.complete)
        assertEquals(2, outcome.total)
        assertFalse(outcome.failed)
        assertEquals("index crawl: 5 pages this run, 0/2 sources complete", outcome.summary)
        assertEquals(5, index.lastRunInfo()!!.pages)
    }

    @Test
    fun `a throwing source is counted, reported and stepped over`() = runBlocking {
        val index = newIndex()
        val seen = mutableListOf<Throwable>()
        val outcome = IndexCrawlRun.run(
            index, listOf(source("a"), source("b")),
            crawlOnce = { s -> if (s.id == "a") throw IllegalStateException("boom") else false },
            onFailure = { seen += it },
            delayMs = 0
        )
        assertEquals(1, outcome.failures)
        assertEquals("boom", seen.single().message)
        // "b" was still attempted: the failure did not end the run.
        assertEquals(0, outcome.pages)
    }

    @Test
    fun `failed means attempted and nothing fetched, so one good page clears the red dot`() = runBlocking {
        val index = newIndex()
        val allFail = IndexCrawlRun.run(
            index, listOf(source("a")), crawlOnce = { throw IllegalStateException() }, delayMs = 0
        )
        assertTrue(allFail.failed)
        assertTrue(index.lastRunInfo()!!.failed)

        var first = true
        val onePage = IndexCrawlRun.run(
            index, listOf(source("a"), source("b")),
            crawlOnce = { s ->
                if (s.id == "a") throw IllegalStateException()
                first.also { first = false }
            },
            delayMs = 0
        )
        assertEquals(1, onePage.pages)
        assertEquals(1, onePage.failures)
        assertFalse(onePage.failed)
        assertFalse(index.lastRunInfo()!!.failed)
    }

    @Test
    fun `a source YouTube says is gone is marked, is not a failure, and is left alone for a day`() = runBlocking {
        val index = newIndex()
        val gone = mutableListOf<String>()
        val failed = mutableListOf<Throwable>()
        var t = 1_000_000L
        val outcome = IndexCrawlRun.run(
            index, listOf(source("dead"), source("b")),
            crawlOnce = { s ->
                if (s.id == "dead") throw org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException("Got error: \"The playlist does not exist.\"")
                else false
            },
            onFailure = { failed += it }, onGone = { s, why -> gone += "${s.id}: $why" },
            delayMs = 0, now = { t }
        )
        assertEquals(listOf("dead: The playlist does not exist."), gone)
        assertTrue("YouTube's verdict is not this box's failure", failed.isEmpty())
        assertEquals(0, outcome.failures)
        assertEquals(1, outcome.gone)
        assertFalse("one dead channel must not back the whole crawl off", outcome.failed)
        assertEquals("index crawl: 0 pages this run, 0/2 sources complete, 1 gone from YouTube", outcome.summary)
        assertEquals("The playlist does not exist.", index.state("dead")!!.gone)
        assertEquals(1_000_000L, index.state("dead")!!.goneAt)

        // The next run does not even ask for it...
        val asked = mutableListOf<String>()
        t += 60 * 60_000L
        IndexCrawlRun.run(index, listOf(source("dead"), source("b")), crawlOnce = { asked += it.id; false }, delayMs = 0, now = { t })
        assertEquals(listOf("b"), asked)
        // ...until a day has passed, and a page arriving clears the mark.
        t += IndexCrawlRun.GONE_RETRY_MS
        asked.clear()
        IndexCrawlRun.run(
            index, listOf(source("dead"), source("b")),
            crawlOnce = { s -> asked += s.id; if (s.id == "dead") index.addVideos("dead", vids("dead", "d1"), complete = true); false },
            delayMs = 0, now = { t }
        )
        assertEquals(listOf("dead", "b"), asked)
        assertNull("a page arrived: the channel is back", index.state("dead")!!.gone)
        // And the mark survives the manifest round trip, in YouTube's words.
        index.markGone("b", "This channel was terminated.", t)
        assertEquals("This channel was terminated.", ChannelIndex(File(tmp.root, "search-index")).state("b")!!.gone)
        // A wrapped cause is still YouTube's verdict; anything else is not.
        assertEquals("gone", IndexCrawlRun.goneReason(RuntimeException("x", org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException("gone"))))
        assertNull(IndexCrawlRun.goneReason(java.io.IOException("timed out")))
    }
}
