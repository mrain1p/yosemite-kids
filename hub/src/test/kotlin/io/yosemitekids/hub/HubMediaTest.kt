package io.yosemitekids.hub

import io.yosemitekids.app.data.StreamChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The arithmetic behind the media proxy, stated as assertions.
 *
 * Every one of these is a number a browser reads and never reports. A
 * `Content-Range` off by one is a video that will not seek; a 200 where a 206
 * was asked for is a duration bar that never appears; a `Range:` header
 * forwarded upstream is a stream throttled to playback speed with nothing in
 * any log to say so. None of it throws, and all of it looks like "the tablet
 * is being slow".
 */
class HubMediaTest {

    private val total = 10_000L

    // --- what to answer -------------------------------------------------

    @Test
    fun `no Range header is the whole thing, as a 200`() {
        val a = HubMedia.rangeFor(null, total)!!
        assertEquals(0L, a.start)
        assertEquals(9_999L, a.endInclusive)
        assertEquals(total, a.length)
        assertFalse(a.partial)
    }

    @Test
    fun `a closed range is a 206 over exactly those bytes`() {
        val a = HubMedia.rangeFor("bytes=100-199", total)!!
        assertEquals(100L, a.start)
        assertEquals(199L, a.endInclusive)
        assertEquals(100L, a.length)
        assertTrue(a.partial)
        assertEquals("bytes 100-199/10000", HubMedia.contentRange(a, total))
    }

    @Test
    fun `an open range runs to the end and is still partial`() {
        // What Chrome and Safari actually open a video with.
        val a = HubMedia.rangeFor("bytes=0-", total)!!
        assertEquals(0L, a.start)
        assertEquals(9_999L, a.endInclusive)
        assertTrue("bytes=0- is a 206, not a 200 — the browser asked", a.partial)
        assertEquals("bytes 0-9999/10000", HubMedia.contentRange(a, total))
    }

    @Test
    fun `a suffix range counts back from the end`() {
        val a = HubMedia.rangeFor("bytes=-500", total)!!
        assertEquals(9_500L, a.start)
        assertEquals(9_999L, a.endInclusive)
        assertEquals(500L, a.length)
        // A suffix longer than the file is the whole file, not an error.
        assertEquals(0L, HubMedia.rangeFor("bytes=-99999", total)!!.start)
    }

    @Test
    fun `an end past the last byte is clamped, never echoed back`() {
        // Safari asks for a round number it cannot know the file is shorter
        // than. Echoing it into Content-Range is what breaks the scrub bar.
        val a = HubMedia.rangeFor("bytes=9900-99999", total)!!
        assertEquals(9_999L, a.endInclusive)
        assertEquals("bytes 9900-9999/10000", HubMedia.contentRange(a, total))
    }

    @Test
    fun `a range that cannot be satisfied is a 416, not a guess`() {
        assertNull(HubMedia.rangeFor("bytes=10000-", total))
        assertNull(HubMedia.rangeFor("bytes=20000-30000", total))
        assertNull(HubMedia.rangeFor("bytes=500-499", total))
        assertNull(HubMedia.rangeFor("bytes=-0", total))
        assertEquals("bytes */10000", HubMedia.unsatisfiable(total))
    }

    @Test
    fun `a header this hub cannot read is ignored, per RFC 7233`() {
        // Refusing instead would turn one odd client into a video that never
        // starts; the spec says answer the whole thing.
        for (junk in listOf("cats", "bytes=abc-def", "bytes=", "items=0-1", "bytes=0")) {
            val a = HubMedia.rangeFor(junk, total)
            assertNotNull("$junk should be ignored, not refused", a)
            assertFalse("$junk should answer 200", a!!.partial)
            assertEquals(total, a.length)
        }
    }

    @Test
    fun `only the first span of a multi-range request is answered`() {
        val a = HubMedia.rangeFor("bytes=0-99, 200-299", total)!!
        assertEquals(0L, a.start)
        assertEquals(99L, a.endInclusive)
        assertTrue(a.partial)
    }

    @Test
    fun `a stream of no length is never partially served`() {
        assertNull(HubMedia.rangeFor(null, 0))
        assertNull(HubMedia.rangeFor("bytes=0-", 0))
    }

    // --- the cap, and why a reply is never left half-written -------------

    @Test
    fun `a 206 carries at most one chunk, and says so in Content-Range`() {
        // The JDK's HTTP server does NOT close a fixed-length response that
        // was under-written — the client waits for its own read timeout. So
        // this route never under-writes: one reply, one chunk, one gate check,
        // and the browser asks for the next span. A Content-Range that still
        // claimed the whole file would be the same hang wearing a hat.
        val big = 100L * 1024 * 1024
        val a = HubMedia.rangeFor("bytes=0-", big)!!
        assertEquals(0L, a.start)
        assertEquals(HubMedia.MAX_RESPONSE_BYTES, a.length)
        assertEquals(HubMedia.MAX_RESPONSE_BYTES - 1, a.endInclusive)
        assertEquals("bytes 0-${HubMedia.MAX_RESPONSE_BYTES - 1}/$big", HubMedia.contentRange(a, big))
        // And the next span starts where the last one ended.
        val b = HubMedia.rangeFor("bytes=${HubMedia.MAX_RESPONSE_BYTES}-", big)!!
        assertEquals(HubMedia.MAX_RESPONSE_BYTES, b.start)
        assertEquals(HubMedia.MAX_RESPONSE_BYTES, b.length)
    }

    @Test
    fun `the cap shortens a 206 and never a 200`() {
        // A 200 means "the whole entity". A capped one would be a lie about
        // what was sent, so the cap is only ever applied to a partial reply.
        val big = 100L * 1024 * 1024
        val whole = HubMedia.rangeFor(null, big)!!
        assertFalse(whole.partial)
        assertEquals(big, whole.length)
        // A short suffix is under the cap and comes back untouched.
        assertEquals(500L, HubMedia.rangeFor("bytes=-500", big)!!.length)
        // A long one is capped from the front of the span it asked for.
        val suffix = HubMedia.rangeFor("bytes=-${8 * 1024 * 1024}", big)!!
        assertEquals(big - 8 * 1024 * 1024, suffix.start)
        assertEquals(HubMedia.MAX_RESPONSE_BYTES, suffix.length)
    }

    // --- what to call it ------------------------------------------------

    @Test
    fun `the content type comes off the stream URL when it is a video type`() {
        assertEquals(
            "video/mp4",
            HubMedia.contentTypeFor("https://r.googlevideo.com/videoplayback?mime=video%2Fmp4&clen=1")
        )
        assertEquals(
            "video/webm",
            HubMedia.contentTypeFor("https://r.googlevideo.com/videoplayback?mime=video%2Fwebm")
        )
        // Absent, or something that is not video: fall back rather than put an
        // arbitrary string off a URL into a response header.
        assertEquals("video/mp4", HubMedia.contentTypeFor("https://r.googlevideo.com/videoplayback"))
        assertEquals("video/mp4", HubMedia.contentTypeFor("https://h/v?mime=text%2Fhtml"))
        assertEquals("video/mp4", HubMedia.contentTypeFor("https://h/v?mime=video%2F<script>"))
    }

    // --- what came off the query ----------------------------------------

    @Test
    fun `the video id is a YouTube id or nothing`() {
        assertEquals("dQw4w9WgXcQ", HubMedia.videoIdIn("v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", HubMedia.videoIdIn("kid=abc&v=dQw4w9WgXcQ&x=1"))
        assertNull(HubMedia.videoIdIn("v=short"))
        assertNull(HubMedia.videoIdIn("v=../../etc/passwd"))
        assertNull(HubMedia.videoIdIn(null))
        assertEquals("abc12", HubMedia.kidIn("v=dQw4w9WgXcQ&kid=abc12"))
        assertNull(HubMedia.kidIn("v=dQw4w9WgXcQ"))
    }

    // --- how many at once -----------------------------------------------

    @Test
    fun `slots hand out exactly the cap and no more`() {
        val slots = HubMedia.Slots(3)
        assertTrue(slots.take())
        assertTrue(slots.take())
        assertTrue(slots.take())
        assertFalse("a fourth stream must be refused, not queued", slots.take())
        assertEquals(3, slots.live())
        slots.release()
        assertTrue("releasing one lets the next in", slots.take())
    }

    @Test
    fun `a refused take leaks nothing`() {
        val slots = HubMedia.Slots(1)
        assertTrue(slots.take())
        repeat(10) { assertFalse(slots.take()) }
        slots.release()
        assertEquals(0, slots.live())
        assertTrue(slots.take())
    }

    @Test
    fun `two threads cannot both be the last slot`() {
        val slots = HubMedia.Slots(2)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val taken = java.util.concurrent.atomic.AtomicInteger(0)
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                start.await()
                if (slots.take()) taken.incrementAndGet()
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals("the cap is a cap under contention too", 2, taken.get())
        assertEquals(2, slots.live())
    }

    // --- header in, query out -------------------------------------------

    @Test
    fun `the browser's Range becomes StreamChunker's range query, chunk by chunk`() {
        // The whole reason this route exists. A `Range:` header on an
        // un-parameterised googlevideo URL is served at playback speed; the
        // `range=` query with `rn=` is served at link speed. The translation
        // happens here and the header is never passed on.
        val asked = mutableListOf<String>()
        val out = ByteArrayOutputStream()
        val span = StreamChunker.CHUNK_BYTES * 2 + 100
        val written = HubStream().pump(
            out, "https://r.googlevideo.com/videoplayback?clen=99&itag=18",
            start = 0, endInclusive = span - 1,
            gate = { true },
            fetch = { url -> asked += url; bytesFor(url) }
        )
        assertEquals(span, written)
        assertEquals(span.toInt(), out.size())
        assertEquals(3, asked.size)
        assertTrue(asked[0].endsWith("&range=0-${StreamChunker.CHUNK_BYTES - 1}&rn=0"))
        assertTrue(
            asked[1].endsWith(
                "&range=${StreamChunker.CHUNK_BYTES}-${2 * StreamChunker.CHUNK_BYTES - 1}&rn=1"
            )
        )
        // The last chunk stops at the span, never past it: a request beyond
        // `clen` is what earns a 416 from googlevideo.
        assertTrue(asked[2].endsWith("&range=${2 * StreamChunker.CHUNK_BYTES}-${span - 1}&rn=2"))
        asked.forEach {
            assertTrue("every chunk keeps the original query", it.contains("itag=18"))
        }
    }

    @Test
    fun `a range starting mid-stream asks upstream from there`() {
        val asked = mutableListOf<String>()
        HubStream().pump(
            ByteArrayOutputStream(), "https://r.googlevideo.com/videoplayback?clen=9",
            start = 5_000, endInclusive = 5_999,
            gate = { true },
            fetch = { url -> asked += url; bytesFor(url) }
        )
        assertEquals(listOf("range=5000-5999", "rn=0"), asked.single().split('?')[1].split('&').drop(1))
    }

    // --- the gate, on every chunk ---------------------------------------

    @Test
    fun `the gate is asked before every chunk and stops the video mid-play`() {
        // A parent blocking a video, a pause, or a budget hitting zero has to
        // reach a stream that is ALREADY RUNNING. This is the assertion that
        // says so: the third chunk is never fetched.
        val asked = mutableListOf<String>()
        var asks = 0
        val out = ByteArrayOutputStream()
        val span = StreamChunker.CHUNK_BYTES * 4
        val written = HubStream().pump(
            out, "https://r.googlevideo.com/videoplayback?clen=1",
            start = 0, endInclusive = span - 1,
            gate = { ++asks <= 2 },
            fetch = { url -> asked += url; bytesFor(url) }
        )
        assertEquals("two chunks fetched, then refused", 2, asked.size)
        assertEquals(3, asks)
        assertEquals(StreamChunker.CHUNK_BYTES * 2, written)
        assertEquals((StreamChunker.CHUNK_BYTES * 2).toInt(), out.size())
    }

    @Test
    fun `a gate that is shut from the start sends nothing at all`() {
        val out = ByteArrayOutputStream()
        val written = HubStream().pump(
            out, "https://h/v?clen=1", start = 0, endInclusive = 999,
            gate = { false },
            fetch = { throw AssertionError("must not fetch when the rules say no") }
        )
        assertEquals(0L, written)
        assertEquals(0, out.size())
    }

    @Test
    fun `an upstream that answers a chunk with nothing is a dead URL, not a retry`() {
        // Without this the loop re-asks for the same bytes for ever — the
        // trap the app's own read() loop documents.
        var calls = 0
        val thrown = runCatching {
            HubStream().pump(
                ByteArrayOutputStream(), "https://h/v?clen=1", start = 0, endInclusive = 999,
                gate = { true },
                fetch = { calls++; ByteArrayInputStream(ByteArray(0)) }
            )
        }.exceptionOrNull()
        assertTrue(thrown is java.io.IOException)
        assertEquals(1, calls)
    }

    @Test
    fun `a short chunk is resumed from where it actually stopped`() {
        // googlevideo closes a chunk early often enough that the television's
        // data source has a loop for it. Same rule here.
        val asked = mutableListOf<String>()
        var first = true
        val out = ByteArrayOutputStream()
        HubStream().pump(
            out, "https://h/v?clen=1", start = 0, endInclusive = 999,
            gate = { true },
            fetch = { url ->
                asked += url
                if (first) { first = false; ByteArrayInputStream(ByteArray(400)) }
                else ByteArrayInputStream(ByteArray(600))
            }
        )
        assertEquals(2, asked.size)
        assertTrue(asked[1].contains("range=400-999"))
        assertEquals(1000, out.size())
    }

    /** As many bytes as the URL's own `range=` asked for. */
    private fun bytesFor(url: String): InputStream {
        val span = url.substringAfter("range=").substringBefore('&').split('-')
        val n = span[1].toLong() - span[0].toLong() + 1
        return ByteArrayInputStream(ByteArray(n.toInt()))
    }
}
