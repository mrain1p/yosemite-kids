package io.yosemitekids.hub

import io.yosemitekids.app.data.Http
import io.yosemitekids.app.data.StreamChunker
import io.yosemitekids.app.data.YouTubeRepository
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The half of `GET /media` that touches the network: resolve a video to one
 * playable URL, then carry its bytes to a browser a chunk at a time.
 *
 * ### Why the hub carries the bytes at all
 *
 * The obvious design is a redirect: resolve the stream, `302` the browser at
 * googlevideo, and leave a family's NAS out of the transfer entirely. It does
 * not work, for two separate reasons, and both are worth writing down because
 * a later round will be tempted again.
 *
 * 1. **Speed.** A plain progressive GET on a `videoplayback` URL is throttled
 *    to roughly playback speed. The form served at link speed is the
 *    `range=<start>-<end>` query parameter with `rn=` numbering — see
 *    [StreamChunker], which exists because the television hit exactly this.
 *    A browser's `<video>` emits RFC-7233 `Range:` headers and cannot be
 *    taught to emit the query form, so a redirected browser gets the slow
 *    path and stalls on every dip.
 *
 *    Said honestly: one sample here did **not** reproduce the throttle — a
 *    muxed URL carrying `ratebypass` answered a 4 MB header range at
 *    16 MB/s, faster than the query form's 8 MB/s in the same run. That is
 *    one URL on one link, against a measured stall on a Chromecast, and the
 *    chunked form is the one this project has already proved. It is also the
 *    form that makes the gate below possible at all, so it stays regardless.
 * 2. **The rules.** A redirect is a one-way door. Once a child's browser holds
 *    that URL, googlevideo will serve it for hours, and nothing a parent does
 *    afterwards can reach it: blocking the video, pausing watching, the budget
 *    running out — all of it becomes advice. Carrying the bytes is what makes
 *    [pump]'s per-chunk gate possible, and that gate is the whole argument for
 *    this class existing.
 *
 * ### The quality ceiling, stated plainly
 *
 * This serves the **muxed progressive** stream, which YouTube caps at around
 * 360p. HD needs separate video-only and audio-only tracks merged at playback,
 * which a plain `<video>` cannot do without MSE or HLS. The app gets HD; a
 * browser gets ~360p until someone builds one of those. A video with no muxed
 * stream at all fails with a reason of its own rather than being served
 * something that will not decode.
 */
class HubStream(
    /** Passed in so tests need no clock, like every other hub class. */
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Lazily, because a hub that nobody watches a video on must not build an
     * extractor at boot — and because `HubServerTest` constructs this server
     * without ever calling `Extractor.init()`.
     */
    private val repo: YouTubeRepository by lazy { YouTubeRepository() }

    companion object {
        /**
         * How long a resolved URL is reused.
         *
         * googlevideo signs these for hours, but a browser makes dozens of
         * range requests for one viewing and re-resolving each would put a
         * multi-second extractor round trip in front of every seek. Twenty
         * minutes is well inside the signature's life and short enough that a
         * URL which does go stale is not held for a whole afternoon.
         */
        const val URL_TTL_MS = 20 * 60_000L

        /** Resolved videos held at once. Allocated from request data, so bounded. */
        const val MAX_CACHED = 64

        /** Copy buffer. Big enough that the write syscalls are not the cost. */
        private const val COPY_BYTES = 64 * 1024

        /** The reason codes a caller turns into a body. Stable, like HubPolicy's. */
        const val NO_MUXED = "no-muxed-stream"
        const val NO_LENGTH = "no-stream-length"
        const val AGE_RESTRICTED = "age-restricted"
        const val RESOLVE_FAILED = "resolve-failed"
    }

    /** A muxed stream, and how long it is. */
    data class Resolved(val url: String, val total: Long, val title: String, val at: Long)

    /** Why [resolve] could not produce something a browser can play. */
    class Unplayable(val reason: String, message: String) : Exception(message)

    private val cache = LinkedHashMap<String, Resolved>()
    private val lock = Any()

    /**
     * One muxed stream for [videoId], from cache while it is fresh.
     *
     * `maxHeight = null` is not a shrug: it is what sends `resolvePlayback`
     * straight to its muxed branch. Passing a height would hand back separate
     * video and audio URLs, which is right for ExoPlayer and unplayable in a
     * plain video element — and the failure would be silent, because both
     * halves are perfectly valid media.
     *
     * @throws Unplayable with a reason a route can distinguish.
     */
    fun resolve(videoId: String): Resolved {
        synchronized(lock) {
            cache[videoId]?.let { if (now() - it.at < URL_TTL_MS) return it }
        }
        val playback = try {
            runBlocking {
                repo.resolvePlayback("https://www.youtube.com/watch?v=" + videoId, maxHeight = null)
            }
        } catch (e: Exception) {
            // The extractor states both of these as an IllegalStateException
            // with a sentence in it, so the message is the only thing there is
            // to read. Matched on a stable fragment rather than the whole
            // string, and anything unrecognised stays RESOLVE_FAILED — an
            // unknown failure must not be reported as a known one.
            val m = e.message.orEmpty()
            throw when {
                m.contains("age-restricted") -> Unplayable(AGE_RESTRICTED, m)
                m.contains("No playable stream") -> Unplayable(NO_MUXED, m)
                else -> Unplayable(RESOLVE_FAILED, m.ifEmpty { e.javaClass.simpleName })
            }
        }
        if (playback.audioUrl != null) {
            // Cannot happen with maxHeight = null, and checked anyway: a
            // browser handed a video-only track plays silence, which reads as
            // a broken speaker rather than as a bug here.
            throw Unplayable(NO_MUXED, "resolved to separate video and audio tracks")
        }
        // The whole stream's length. Without it there is no honest
        // Content-Length, no Content-Range and therefore no seeking, so a
        // stream whose length cannot be learned is refused rather than served
        // with a scrub bar that lies.
        //
        // `clen` in the URL when it is there, and a HEAD when it is not — and
        // it often is not. Measured against a real muxed (itag 18) URL: the
        // query carried `ratebypass` and `dur` but no `clen` at all, while a
        // HEAD on the same URL answered `Content-Length: 28523658` and
        // `Accept-Ranges: bytes`. The app's own chunker gives up in exactly
        // this case and falls back to a plain progressive read, which a
        // browser cannot do, so the hub has to ask.
        val total = StreamChunker.clenOf(playback.videoUrl)
            ?: lengthOf(playback.videoUrl)
            ?: throw Unplayable(NO_LENGTH, "neither clen nor a HEAD gave this stream a length")
        val resolved = Resolved(playback.videoUrl, total, playback.title, now())
        synchronized(lock) {
            cache.remove(videoId)
            cache[videoId] = resolved
            while (cache.size > MAX_CACHED) cache.remove(cache.keys.first())
        }
        return resolved
    }

    /**
     * Copy [start]..[endInclusive] of [url] into [out], in
     * [StreamChunker.CHUNK_BYTES] pieces, asking [gate] before every one.
     *
     * **The gate is asked on every chunk, not just the first, and that is the
     * point of proxying.** A parent blocking a video, a pause, or a budget
     * reaching zero has to stop playback that is already running — otherwise
     * the rules only apply to videos a child has not started yet, which is the
     * opposite of what a parent asked for. A redirect could never do this.
     *
     * Returns the number of bytes written, which is short of the span exactly
     * when the gate closed mid-video.
     */
    fun pump(
        out: OutputStream,
        url: String,
        start: Long,
        endInclusive: Long,
        gate: () -> Boolean,
        /**
         * How a chunk is fetched. The default is the shared client, whose host
         * allow-list the hub armed at startup (guard 7). A test passes its own
         * and gets to read the URLs this asked for — which is the only way to
         * state "the browser's header became a `range=` query" as an assertion
         * rather than as a comment.
         */
        fetch: (String) -> InputStream = ::fetchChunk
    ): Long {
        var pos = start
        var rn = 0L
        val buf = ByteArray(COPY_BYTES)
        while (pos <= endInclusive) {
            if (!gate()) return pos - start
            val chunkEnd = minOf(pos + StreamChunker.CHUNK_BYTES - 1, endInclusive)
            // Header in, query out. The browser's own Range header is
            // deliberately NOT forwarded upstream — it is the throttled form,
            // and sending both would be asking one question in two languages.
            // Guard 56 fails the build if one ever appears here.
            val before = pos
            fetch(StreamChunker.chunkUrl(url, pos, chunkEnd, rn++)).use { source ->
                while (pos <= chunkEnd) {
                    val want = minOf(buf.size.toLong(), chunkEnd - pos + 1).toInt()
                    val n = source.read(buf, 0, want)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    pos += n
                }
            }
            // A server that answers a chunk with nothing at all is a dead URL,
            // not a slow one. Without this the loop would re-ask for the same
            // bytes for ever — the same trap the app's read() loop documents.
            if (pos == before) throw IOException("upstream sent no bytes from " + before)
        }
        return pos - start
    }

    /**
     * How long the stream is, asked with a HEAD and nothing else.
     *
     * Deliberately **not** a `Range: bytes=0-0`, which is the other way to
     * learn a length and would answer `Content-Range: bytes 0-0/<total>`. A
     * Range header is the throttled form on these URLs, and the hub having one
     * anywhere is a line the next person would copy into the data path. HEAD
     * asks the same question with no header at all — and guard 56 keeps it
     * that way.
     *
     * Null on anything unexpected: this is a probe, and a failure here means
     * "refuse", never "guess".
     */
    private fun lengthOf(url: String): Long? = runCatching {
        Http.client.newCall(okhttp3.Request.Builder().url(url).head().build()).execute().use {
            if (it.code != 200) null
            else it.header("Content-Length")?.toLongOrNull()?.takeIf { n -> n > 0 }
        }
    }.getOrNull()

    /**
     * One chunk, through the shared client.
     *
     * Closing the returned stream is what releases the connection, which is
     * why [pump] wraps it in `use` and why nothing here holds the response.
     */
    private fun fetchChunk(url: String): InputStream {
        val response = Http.client.newCall(okhttp3.Request.Builder().url(url).build()).execute()
        if (response.code != 200 && response.code != 206) {
            response.close()
            throw IOException("upstream answered " + response.code)
        }
        val body = response.body
        if (body == null) {
            response.close()
            throw IOException("upstream sent no body")
        }
        return body.byteStream()
    }
}
