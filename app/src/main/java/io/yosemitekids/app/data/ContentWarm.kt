package io.yosemitekids.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Refreshing the video caches with the app closed.
 *
 * Every device fetches for itself — `MainViewModel.warmCaches` walks each
 * channel and saves its first page — but the only triggers were app launch,
 * returning to the foreground, opening a channel, and a five-minute poll that
 * runs *only while the app is on Home*. So the real cause of a stale home was
 * never the fetching: it was that the app had to be running to do any. A TV
 * that had been off showed last week's videos until someone opened Yosemite Kids and
 * left it sitting on Home long enough to catch up.
 *
 * This is the same walk, off the ViewModel, so a worker can do it on a
 * schedule. Deliberately narrower than the foreground version in two ways:
 *
 * **It does not screen.** The foreground warm hands new videos to the AI. Doing
 * that unattended would spend a parent's API balance on a timer, with no one
 * watching and no screen to show for it — and the failure mode of a bug there
 * is a bill. Caching is free and is the slow half anyway; screening still runs
 * when the app opens, against a cache that is already full.
 *
 * **It does not harvest the search index.** That is the master device's job
 * ([IndexCrawlWorker]) and adding a second writer to it here would mean
 * electing a master for a cache that is per-device by design.
 */
object ContentWarm {

    /**
     * Channels per run.
     *
     * Background fetches are serialised behind a single permit in
     * [YouTubeRepository], so this is twelve requests one after another — a
     * couple of minutes, well inside a worker's window, and slow enough that a
     * family with fifty channels never looks like a scraper. The stalest are
     * picked first, so successive runs cover everything without bookkeeping.
     */
    internal const val PER_RUN = 12

    /**
     * Which channels to refresh: the stalest first.
     *
     * Ordering by cache age rather than rotating an index means a channel that
     * failed last time is retried first, a newly added one is fetched
     * immediately (age is [Long.MAX_VALUE] with no file), and nothing needs a
     * counter that could drift out of step with the channel list.
     *
     * Pure so it can be tested without a Context, a network or a clock.
     */
    internal fun stalest(
        sources: List<Source>,
        ageMillis: (Source) -> Long,
        limit: Int = PER_RUN
    ): List<Source> = sources
        .sortedByDescending { ageMillis(it) }
        .take(limit.coerceAtLeast(0))

    /**
     * Refresh up to [PER_RUN] channels into the video cache. Returns how many
     * actually produced videos.
     *
     * Sources come from [SourceCache], which holds them already resolved, so
     * this costs one request per channel rather than two. Intersected with the
     * live config: a channel a parent removed must not keep being fetched.
     */
    suspend fun run(
        context: Context,
        yt: YouTubeRepository = YouTubeRepository(),
        now: Long = System.currentTimeMillis()
    ): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val config = runCatching { ConfigStore(app).load() }.getOrNull() ?: return@withContext 0
        val wanted = config.sources.map { it.id }.toSet()
        if (wanted.isEmpty()) return@withContext 0

        val known = SourceCache(app).load().filter { it.id in wanted }
        if (known.isEmpty()) return@withContext 0

        val cache = VideoCache(app)
        val dir = File(app.filesDir, "video_cache")
        val picked = stalest(known, { s ->
            val f = File(dir, "${s.id}.tsv")
            // Never fetched wins outright: a channel added since the last run
            // has nothing cached and is the one a kid is most likely to open.
            if (f.isFile) now - f.lastModified() else Long.MAX_VALUE
        })

        var warmed = 0
        for (source in picked) {
            val videos = runCatching { yt.uploadsPage(source, background = true).videos }
                .getOrElse { e ->
                    // One channel failing must not end the run — a deleted or
                    // region-blocked channel would otherwise stop every channel
                    // after it from ever refreshing, permanently.
                    android.util.Log.w("YosemiteKids", "warm ${source.id} failed", e)
                    emptyList()
                }
            if (videos.isEmpty()) continue
            cache.save(source.id, videos)
            warmed++
        }
        // Home reads through a memo keyed on each file's (mtime, length); the
        // save above moves both, so nothing needs invalidating here.
        android.util.Log.i("YosemiteKids", "content warm: refreshed $warmed of ${picked.size}")
        warmed
    }
}
