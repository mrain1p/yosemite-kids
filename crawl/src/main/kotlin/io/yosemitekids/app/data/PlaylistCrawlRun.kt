package io.yosemitekids.app.data

import kotlinx.coroutines.delay

/**
 * One bounded batch of the playlist crawl: which of a channel's own playlists
 * exist, and which videos each holds, kept in the index beside the channel's
 * videos so the hub can draw a browser's playlist strip and playlist page
 * without asking YouTube while a child waits.
 *
 * The phone never needed this — it lists a channel's playlists live when the
 * page opens (`ChannelPlaylistsCache`, one listing per channel per day) and
 * opens a playlist as a feed of its own. A browser cannot: the hub carries no
 * live extraction on the kid's path, and the page may not decide anything
 * (guard 61), so what the strip shows has to be in the index first. This is
 * the crawler work roadmap 8C.4 said came before the surface.
 *
 * Paced like [IndexCrawlRun]: one fetch at a time, [IndexCrawler.CRAWL_DELAY_MS]
 * apart, a fixed budget per run, and only ever on the background lane. A
 * channel's listing costs one fetch plus one per playlist kept; a listing is
 * refreshed once a day ([REFRESH_MS]) and a run that runs out of budget
 * mid-channel saves what it has and carries on next time, so a family with
 * fifty channels is indexed over a few runs rather than one long one. Every
 * playlist keeps every id YouTube listed on its first page; whose videos a
 * kid may see is decided where the page is answered, against the same index
 * rows the channel page is drawn from.
 */
object PlaylistCrawlRun {

    /** A channel's playlist listing is asked for again after this long. */
    const val REFRESH_MS = 24 * 60 * 60_000L

    /**
     * Playlists kept per channel, in the order YouTube lists them (the
     * channel's own). The phone's strip shows thirty; a browser's shows the
     * same and every one costs a fetch, so the crawl keeps the first twenty
     * and the page says so with a count.
     */
    const val PLAYLISTS_PER_SOURCE = 20

    /** Fetches per run: a listing or a playlist page each. Twelve at four seconds apart is under a minute. */
    const val FETCHES_PER_RUN = 12

    /** A channel's "Shorts" playlist is the one collection a kid's shelf never wants (the phone drops it too). */
    fun wanted(ref: PlaylistRef): Boolean = !ref.name.contains("shorts", ignoreCase = true)

    data class Outcome(val fetches: Int, val refreshed: Int, val failures: Int) {
        val summary: String get() = "playlists: $fetches fetches, $refreshed channel(s) refreshed" +
            (if (failures > 0) ", $failures failed" else "")
    }

    /**
     * @param listPlaylists a channel's playlists, one fetch.
     * @param playlistVideos the first page of one playlist, one fetch.
     */
    suspend fun run(
        index: ChannelIndex,
        sources: List<Source>,
        listPlaylists: suspend (Source) -> List<PlaylistRef>,
        playlistVideos: suspend (PlaylistRef) -> List<Video>,
        onFailure: (Throwable) -> Unit = {},
        delayMs: Long = IndexCrawler.CRAWL_DELAY_MS,
        fetchesPerRun: Int = FETCHES_PER_RUN,
        now: () -> Long = System::currentTimeMillis
    ): Outcome {
        val t = now()
        // Unfinished listings first (a run that ran out mid-channel), then the
        // stalest; a channel YouTube says is gone is left alone like its videos.
        val due = sources
            .filter { it.kind == SourceKind.CHANNEL && index.state(it.id)?.gone == null }
            .map { it to index.loadPlaylists(it.id) }
            .filter { (_, listing) -> listing == null || !listing.complete || t - listing.at >= REFRESH_MS }
            .sortedWith(compareBy({ it.second?.complete != false }, { it.second?.at ?: 0L }))
        var fetches = 0
        var refreshed = 0
        var failures = 0
        suspend fun <T> fetch(block: suspend () -> T): T? {
            if (fetches > 0) delay(delayMs)
            fetches++
            return runCatching { block() }.getOrElse { onFailure(it); failures++; null }
        }
        for ((source, held) in due) {
            if (fetches >= fetchesPerRun) break
            var listing = held?.takeIf { !it.complete }
            if (listing == null) {
                val refs = fetch { listPlaylists(source) } ?: continue
                listing = ChannelIndex.PlaylistListing(
                    at = t,
                    complete = false,
                    playlists = refs.filter(::wanted).distinctBy { it.id }.take(PLAYLISTS_PER_SOURCE).map {
                        ChannelIndex.IndexedPlaylist(it.id, it.url, it.name, it.thumbnailUrl, it.videoCount, videoIds = null)
                    }
                )
                index.savePlaylists(source.id, listing)
            }
            var playlists = listing.playlists
            for (i in playlists.indices) {
                if (playlists[i].videoIds != null) continue
                if (fetches >= fetchesPerRun) break
                val ref = playlists[i].let { PlaylistRef(it.id, it.url, it.name, it.thumbnailUrl, it.videoCount) }
                val videos = fetch { playlistVideos(ref) } ?: continue
                playlists = playlists.toMutableList().also { list ->
                    list[i] = list[i].copy(videoIds = videos.mapNotNull { it.videoId }.distinct())
                }
                index.savePlaylists(source.id, listing.copy(playlists = playlists))
            }
            if (playlists.all { it.videoIds != null }) {
                index.savePlaylists(source.id, ChannelIndex.PlaylistListing(at = t, complete = true, playlists = playlists))
                refreshed++
            }
        }
        return Outcome(fetches, refreshed, failures)
    }
}
