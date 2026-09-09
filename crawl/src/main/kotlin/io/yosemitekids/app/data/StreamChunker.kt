package io.yosemitekids.app.data

/**
 * The range arithmetic googlevideo actually serves at speed, in one place for
 * everything that fetches a stream.
 *
 * A progressive GET at a `videoplayback` URL is throttled to roughly playback
 * speed. The form the official clients use — and the form that comes back at
 * link speed — is the `range=<start>-<end>` **query parameter** with `rn=`
 * numbering the requests. A `Range:` *header* on an un-parameterised URL is
 * what gets throttled, and the difference is not small: measured on a
 * Chromecast over a 433 Mbps link, a ~2:40 video stalled at the start and
 * crept ahead at ~1.5x realtime unwrapped, and was fully buffered 19 seconds
 * in when chunked.
 *
 * It lives in `:crawl` rather than in `:app` because two things now fetch
 * these bytes: the television's `ChunkedStreamDataSource`, and the hub, which
 * carries them to a browser that can only ever emit RFC-7233 `Range:` headers
 * and cannot be taught the query form. Those two must translate a position
 * into a URL the same way — a second copy would drift, and the symptom would
 * be a stall on one face and not the other, with two implementations to argue
 * about. Guard 56 in `scripts/check.*` fails if a second one appears.
 *
 * Pure string plumbing, deliberately off `android.net.Uri`, so a plain JVM
 * unit test can exercise the arithmetic without a device.
 */
object StreamChunker {
    /** How much one request asks for. Small enough that the server's
     * per-connection throttle never gets a long window to bite; large enough
     * that request overhead stays negligible at video bitrates. */
    const val CHUNK_BYTES = 2L * 1024 * 1024

    fun isGoogleVideo(host: String?): Boolean =
        host != null && (host == "googlevideo.com" || host.endsWith(".googlevideo.com"))

    /** Total stream size the extractor URL already carries (`clen`), or null. */
    fun clenOf(url: String): Long? =
        queryValue(url, "clen")?.toLongOrNull()?.takeIf { it > 0 }

    /**
     * Rewrites the stream URL to fetch exactly [start]..[endInclusive] via the
     * `range` query parameter (the form the servers serve at full speed —
     * a `Range` header on an un-parameterised URL is what gets throttled),
     * with `rn` numbering requests the way the official clients do.
     */
    fun chunkUrl(url: String, start: Long, endInclusive: Long, rn: Long): String {
        val q = url.indexOf('?')
        if (q < 0) return "$url?range=$start-$endInclusive&rn=$rn"
        val kept = url.substring(q + 1).split('&').filter {
            val key = it.substringBefore('=')
            key != "range" && key != "rn"
        }
        val params = (kept + listOf("range=$start-$endInclusive", "rn=$rn"))
            .joinToString("&")
        return url.substring(0, q + 1) + params
    }

    private fun queryValue(url: String, key: String): String? {
        val q = url.indexOf('?')
        if (q < 0) return null
        return url.substring(q + 1).split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotEmpty() }
    }
}
