package io.yosemitekids.hub

/**
 * The arithmetic behind `GET /media`, with nothing in it that needs a socket.
 *
 * A browser is the one viewer in this project that cannot be handed a
 * googlevideo URL. It emits RFC-7233 `Range:` headers and nothing else, and
 * that is the form googlevideo was measured throttling to about playback speed
 * on a television (see [io.yosemitekids.app.data.StreamChunker] for the
 * measurement and [HubStream] for a live sample that did not reproduce it).
 * Either way the hub has to carry the bytes, because the rules have to stay
 * askable mid-video. So it does: it reads the browser's header **here**, and
 * asks upstream in the `range=` query form over there. Header in, query out,
 * and the two never meet.
 *
 * Everything in this file is a pure function of a string and a length, so
 * `HubMediaTest` can state the whole contract — a 206 whose `Content-Range`
 * is off by one is a video that will not seek, and that is not something to
 * discover on a tablet.
 */
object HubMedia {

    /**
     * How many videos are in flight, and the refusal when there is no room.
     *
     * A counter with a ceiling and nothing else — but it is the thing standing
     * between one family and a NAS with no threads left, so it is here where a
     * test can hammer it rather than inline in a route where it cannot. [take]
     * decides on one atomic increment and gives the slot back itself when it
     * lost, so two requests arriving on two threads can never both be the
     * [max]th — `HubMediaTest` runs eight at once against a cap of two.
     *
     * Deliberately not a `Semaphore`. A semaphore's natural verb is *wait*,
     * and waiting is the one answer this must never give: a queued stream is a
     * video that does not start and tells the child nothing, where a refusal
     * is a sentence a page can put on screen.
     */
    class Slots(private val max: Int) {
        private val live = java.util.concurrent.atomic.AtomicInteger(0)

        /** True when a slot was taken — the caller then owes exactly one [release]. */
        fun take(): Boolean {
            if (live.incrementAndGet() <= max) return true
            live.decrementAndGet()
            return false
        }

        fun release() { live.decrementAndGet() }

        fun live(): Int = live.get()
    }

    /** A byte span, inclusive at both ends, the way HTTP counts them. */
    data class Answer(
        val start: Long,
        val endInclusive: Long,
        /** True when the caller asked with a `Range:` header: a 206, not a 200. */
        val partial: Boolean
    ) {
        val length: Long get() = endInclusive - start + 1
    }

    /**
     * The most one 206 carries, and why there is a limit at all.
     *
     * **`com.sun.net.httpserver` does not close a fixed-length response that
     * was under-written.** Measured: declare `Content-Length: 1000`, write
     * 100 bytes, close the exchange — the JDK raises `insufficient bytes
     * written to stream` internally, swallows it, and leaves the socket open.
     * The client sits there until its own read timeout. For this route that
     * is not a curiosity: it is what a child would see the moment a parent
     * blocks a video mid-play. A stopped video would look like a frozen one,
     * for as long as the browser felt like waiting.
     *
     * The fix is not to truncate: it is to never need to. One response
     * carries one chunk, so a response either completes or was never started,
     * and the gate is asked once per response — every 2 MB, because a browser
     * simply asks for the next span. That is the same re-check interval
     * [HubStream.pump] does internally, reached without ever leaving a reply
     * half-written.
     *
     * A capped 206 is ordinary HTTP and every player handles it: asking for
     * `bytes=0-` and being given the first two megabytes is what a segmented
     * server does all day.
     */
    const val MAX_RESPONSE_BYTES = io.yosemitekids.app.data.StreamChunker.CHUNK_BYTES

    /**
     * What to answer a caller whose `Range:` header was [header], for a stream
     * of [total] bytes, carrying at most [maxSpan] bytes. Null means
     * **unsatisfiable** — a 416, with [unsatisfiable] as the `Content-Range`.
     *
     * Three outcomes and not two, because the middle one is the one that
     * breaks quietly. A header this hub cannot parse is *ignored* and answered
     * whole, which is what RFC 7233 §3.1 requires; refusing it instead would
     * turn one odd client into a video that never starts. A header it can
     * parse but cannot satisfy is a 416, because answering a wrong span with a
     * 206 is how a player ends up decoding the middle of a file as a header.
     *
     * [maxSpan] shortens a 206 and never a 200: a 200 means *the whole
     * entity*, so a capped one would be a lie about what was sent, and the
     * cap is a serving decision rather than a fact about the resource.
     */
    fun rangeFor(header: String?, total: Long, maxSpan: Long = MAX_RESPONSE_BYTES): Answer? {
        if (total <= 0) return null
        val whole = Answer(0, total - 1, partial = false)
        val spec = header?.trim()?.takeIf { it.isNotEmpty() } ?: return whole
        if (!spec.startsWith("bytes=", ignoreCase = true)) return whole
        // Only the first range of a multi-range request is honoured. A
        // multipart/byteranges body is legal and no browser asks a <video>
        // for one; answering the first span is the reading every client
        // handles, and it stays a plain 206.
        val first = spec.substring(6).substringBefore(',').trim()
        val dash = first.indexOf('-')
        if (dash < 0) return whole
        val fromText = first.substring(0, dash).trim()
        val toText = first.substring(dash + 1).trim()
        if (fromText.isEmpty()) {
            // A suffix range: the last N bytes. Capped from the FRONT of the
            // span, so the bytes handed back are the ones asked for first.
            val n = toText.toLongOrNull() ?: return whole
            if (n <= 0) return null
            val start = (total - n).coerceAtLeast(0)
            return Answer(start, capped(start, total - 1, maxSpan), partial = true)
        }
        val start = fromText.toLongOrNull() ?: return whole
        if (start < 0 || start >= total) return null
        val end = if (toText.isEmpty()) total - 1
        else (toText.toLongOrNull() ?: return whole).coerceAtMost(total - 1)
        if (end < start) return null
        return Answer(start, capped(start, end, maxSpan), partial = true)
    }

    private fun capped(start: Long, end: Long, maxSpan: Long): Long =
        if (maxSpan <= 0) end else minOf(end, start + maxSpan - 1)

    /** The `Content-Range` for a 206. */
    fun contentRange(answer: Answer, total: Long): String =
        "bytes ${answer.start}-${answer.endInclusive}/$total"

    /** The `Content-Range` for a 416, which names only the length. */
    fun unsatisfiable(total: Long): String = "bytes */$total"

    /**
     * What to call the bytes.
     *
     * googlevideo puts the real type in the URL's own `mime=` parameter, and
     * it is worth reading rather than assuming: a muxed stream is normally
     * `video/mp4` but a 3gp or webm fallback exists, and a browser handed the
     * wrong type refuses to decode a file it could have played. Anything that
     * is not a plain `video/...` token is ignored — the value comes off a URL,
     * and it is about to become a response header.
     */
    fun contentTypeFor(url: String, fallback: String = "video/mp4"): String {
        val q = url.indexOf('?')
        if (q < 0) return fallback
        val raw = url.substring(q + 1).split('&')
            .firstOrNull { it.substringBefore('=') == "mime" }
            ?.substringAfter('=', "")
            ?: return fallback
        val decoded = raw.replace("%2F", "/").replace("%2f", "/")
        return if (MIME.matches(decoded)) decoded else fallback
    }

    private val MIME = Regex("video/[A-Za-z0-9][A-Za-z0-9.+-]{0,62}")

    /**
     * `v=` — a YouTube id and nothing else, because it names a fetch.
     *
     * The only thing a media URL is allowed to say. There **was** a `kid=`
     * parameter here, read into `HubPolicy.mayPlay` as the child whose rules
     * applied, and it is gone: whose rules apply is a property of the
     * credential the browser presents, bound when a parent minted the claim
     * code (`HubBrowsers`). A child who could name the kid could name their
     * older sibling, and watch on their bedtime, their budget and their block
     * list. Guard 60 fails the build if a parser for it comes back.
     */
    fun videoIdIn(query: String?): String? =
        VIDEO.find(query.orEmpty())?.groupValues?.get(1)

    private val VIDEO = Regex("(?:^|&)v=([A-Za-z0-9_-]{11})(?:&|$)")
}
