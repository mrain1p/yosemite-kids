package io.yosemitekids.app.data

/**
 * Resolved streams, kept for a while, so a replay, a quality change back to
 * a ceiling already resolved, or "Up next" to a video just watched does not
 * pay a full `StreamInfo` extraction again.
 *
 * Every extraction is a request YouTube's bot detection watches and a
 * multi-second wait in front of a child; the hub has cached its own since
 * 1.4.0 (`HubStream`, twenty minutes, sixty-four entries) and the app never
 * did. Same numbers here, for the same reasons: googlevideo URLs are good
 * for hours, twenty minutes is well inside that, and sixty-four is more
 * videos than a sitting holds.
 *
 * **Keyed on the page URL AND the ceiling**, never the id alone. The quality
 * picker re-resolves at a new `maxHeight`; a cache that answered by id would
 * hand back the old streams and turn the picker into a control that does
 * nothing. [forget] takes the URL alone, every ceiling: a playback failure
 * says the video's URLs are bad, not one height of them.
 *
 * Pure and in `:crawl` beside `YouTubeRepository`, which owns the one
 * instance; the clock is a parameter so the test is one.
 */
class PlaybackCache(
    private val ttlMs: Long = TTL_MS,
    private val maxEntries: Int = MAX_ENTRIES
) {
    private data class Key(val url: String, val maxHeight: Int?)
    private class Entry(val playback: YouTubeRepository.Playback, val at: Long)

    // Access-ordered, so the entry evicted at the cap is the one least
    // recently asked for, not the one resolved first.
    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    private val lock = Any()

    /** The streams resolved for [url] at [maxHeight] less than [ttlMs] ago, or null. */
    fun get(url: String, maxHeight: Int?, now: Long): YouTubeRepository.Playback? = synchronized(lock) {
        val key = Key(url, maxHeight)
        val e = entries[key] ?: return null
        if (now - e.at >= ttlMs) {
            entries.remove(key)
            return null
        }
        e.playback
    }

    fun put(url: String, maxHeight: Int?, playback: YouTubeRepository.Playback, now: Long) = synchronized(lock) {
        entries[Key(url, maxHeight)] = Entry(playback, now)
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    /**
     * Drop every ceiling of [url]. Called when playback of it fails: a stale
     * URL would otherwise be a video that cannot play, handed back on every
     * retry, and `onPlaybackFailed` would walk it through the queue.
     */
    fun forget(url: String) = synchronized(lock) {
        entries.keys.removeAll { it.url == url }
    }

    fun clear() = synchronized(lock) { entries.clear() }

    val size: Int get() = synchronized(lock) { entries.size }

    companion object {
        const val TTL_MS = 20 * 60_000L
        const val MAX_ENTRIES = 64
    }
}
