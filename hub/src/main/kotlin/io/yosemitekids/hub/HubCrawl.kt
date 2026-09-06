package io.yosemitekids.hub

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.Http
import io.yosemitekids.app.data.IndexCrawlRun
import io.yosemitekids.app.data.IndexCrawler
import io.yosemitekids.app.data.Source
import io.yosemitekids.app.data.YouTubeRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The crawl, on the hub: the same IndexCrawlRun the phone's worker runs,
 * same batch, same pacing, on a scheduler thread of its own. Two things a
 * phone does not need, because a NAS has a fixed public IP and runs for
 * years: exponential backoff on consecutive failed runs, so a bot-walled
 * address is not hammered every quarter hour, and a probe HubMaster uses
 * before claiming the slot at all.
 *
 * Every pass, master or not, drops sources the config no longer lists,
 * exactly as the device worker does: the devices pull this index, and a
 * removed channel must not linger in it.
 */
class HubCrawl(
    private val store: HubStore,
    private val index: ChannelIndex,
    /** This hub's self token, the value the master slot holds when it is ours. */
    private val me: String,
    /** One page of one source, true when there is more. The real one is IndexCrawler; tests swap it. */
    private val crawlOnce: suspend (Source) -> Boolean,
    private val dropSource: (String) -> Unit,
    private val pacingMs: Long = IndexCrawler.CRAWL_DELAY_MS,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        /** The device worker's period; the hub keeps its footprint on YouTube identical to a phone's. */
        const val PERIOD_MS = 15 * 60 * 1000L

        /** Six hours: a wall that lasts longer than this is a wall for the day, and a parent will see the red dot. */
        const val MAX_BACKOFF_MS = 6 * 60 * 60 * 1000L

        /**
         * Does YouTube answer from this box at all? A small, cacheable page
         * through the shared, allow-listed client. Any HTTP answer counts:
         * the question is reachability, not whether a crawl would succeed.
         */
        fun probeYouTube(): Boolean = runCatching {
            Http.client.newCall(
                Request.Builder().url("https://www.youtube.com/robots.txt").get().build()
            ).execute().use { it.code in 200..499 }
        }.getOrDefault(false)

        /** The real thing: IndexCrawler over the shared repository. */
        fun real(store: HubStore, index: ChannelIndex, me: String): HubCrawl {
            val crawler = IndexCrawler(YouTubeRepository(), index)
            return HubCrawl(store, index, me, crawlOnce = { crawler.crawlOnce(it) }, dropSource = crawler::dropSource)
        }
    }

    /** Current backoff after consecutive failed runs, or 0. Doubles per failure up to [MAX_BACKOFF_MS]. */
    @Volatile
    var backoffMs: Long = 0L
        private set

    @Volatile
    private var notBefore: Long = 0L

    /** The last pass, in words, for the GUI's Devices page. */
    @Volatile
    var last: String = "not yet run"
        private set

    /** One scheduled pass. Null when nothing was crawled: not master, or backing off. */
    fun runOnce(): IndexCrawlRun.Outcome? {
        val t = now()
        val config = runCatching { store.load() }.getOrNull() ?: run {
            last = "idle: no config yet"
            return null
        }
        // Labels and avatars may be absent; the index needs only id, url and kind.
        val sources = config.sources.map { e ->
            Source(e.id, e.url, e.label ?: e.id, null, e.kind, e.timeMultiplierPercent)
        }
        val wanted = sources.map { it.id }.toSet()
        index.allStates().keys.filter { it !in wanted }.forEach { runCatching { dropSource(it) } }

        if (config.masterDeviceToken != me) {
            last = "idle: not building the index (another peer holds it, or nobody does)"
            return null
        }
        if (t < notBefore) {
            last = "backing off after failed crawls; next try in ${(notBefore - t) / 60_000} min"
            return null
        }
        val outcome = runBlocking {
            IndexCrawlRun.run(
                index, sources, crawlOnce,
                onFailure = { System.err.println("index crawl failed: ${it.message}") },
                delayMs = pacingMs
            )
        }
        if (outcome.failed) {
            backoffMs = if (backoffMs == 0L) PERIOD_MS else minOf(backoffMs * 2, MAX_BACKOFF_MS)
            notBefore = t + backoffMs
            last = "${outcome.summary}; failed, backing off ${backoffMs / 60_000} min"
        } else {
            backoffMs = 0L
            notBefore = 0L
            last = outcome.summary
        }
        println(last)
        return outcome
    }

    // One thread, fixed delay: a batch blocks it for minutes on purpose, and
    // the next must not start until this one has stopped touching YouTube.
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "hub-crawl").apply { isDaemon = true }
    }

    fun start() {
        scheduler.scheduleWithFixedDelay(
            {
                runCatching { runOnce() }.onFailure { System.err.println("crawl pass failed: ${it.message}") }
            },
            // After the first master tick has had its say.
            60_000L, PERIOD_MS, TimeUnit.MILLISECONDS
        )
    }

    fun stop() = scheduler.shutdownNow()
}
