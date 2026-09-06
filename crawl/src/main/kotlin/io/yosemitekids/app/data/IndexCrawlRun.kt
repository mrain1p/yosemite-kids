package io.yosemitekids.app.data

import kotlinx.coroutines.delay

/**
 * One bounded batch of the crawl: the loop that lived inside the app's
 * WorkManager worker, lifted here so the hub runs the same one. Everything
 * platform-specific stays with the caller: the worker decides whether this
 * device is the master and logs through Android; the hub decides through its
 * own ticker and prints. The loop itself knows an index, a list of sources
 * and a function that fetches one page.
 */
object IndexCrawlRun {

    /**
     * Pages per run. Sized against Android's ~10-minute cap on a single
     * worker execution, NOT the 15-minute period: at CRAWL_DELAY_MS spacing
     * plus fetch time this lands near 5-6 minutes, leaving real headroom
     * before the platform kills the run mid-page. The hub has no such cap
     * but keeps the same batch, so its footprint on YouTube is the phone's.
     *
     * Averaged over a 15-minute period this is ~4 requests a minute: the
     * burst is paced, and on a device it uses the background fetch lane, so
     * the kid's browsing never queues behind it.
     */
    const val PAGES_PER_RUN = 60

    data class Outcome(val pages: Int, val complete: Int, val total: Int, val failures: Int) {
        /**
         * Failed = a source was attempted and threw without yielding a page:
         * the red dot in settings. A run that simply had nothing to do, or
         * that got its pages before a later source failed, is fine.
         */
        val failed: Boolean get() = failures > 0 && pages == 0

        /** The one log line: confirms the run happened, how much it did, how far along the catalog is. */
        val summary: String get() = "index crawl: $pages pages this run, $complete/$total sources complete"
    }

    /**
     * @param crawlOnce fetches one page of [Source] into [index]; true when
     *   there is more to fetch. Throws on a failed fetch.
     * @param onFailure sees each throw; the run counts it and moves on.
     */
    suspend fun run(
        index: ChannelIndex,
        sources: List<Source>,
        crawlOnce: suspend (Source) -> Boolean,
        onFailure: (Throwable) -> Unit = {},
        delayMs: Long = IndexCrawler.CRAWL_DELAY_MS,
        pagesPerRun: Int = PAGES_PER_RUN
    ): Outcome {
        val incomplete = sources.filter { index.state(it.id)?.complete != true }
        if (incomplete.isEmpty()) {
            // Still stamp the diagnostics line: a fully-crawled catalog should
            // read "ran, nothing to do", not "hasn't run since the last page".
            index.recordRun(0, failed = false)
            return Outcome(0, sources.size, sources.size, 0)
        }
        // Round-robin from the first incomplete source. ~17 pages per
        // 500-video channel, so one run finishes a channel and starts the next.
        var pages = 0
        var attempts = 0
        var failures = 0
        for (source in incomplete) {
            while (pages < pagesPerRun) {
                // Spread the budget out instead of firing it as one burst —
                // see CRAWL_DELAY_MS. Paced per fetch ATTEMPT, not per stored
                // page: a parked source's probe returns false without counting
                // a page, and N parked sources would otherwise fire N
                // back-to-back page-1 fetches at the head of every run. First
                // attempt of the run goes immediately.
                if (attempts > 0) delay(delayMs)
                attempts++
                val more = runCatching { crawlOnce(source) }
                    .getOrElse {
                        onFailure(it)
                        failures++
                        false
                    }
                if (!more) break
                pages++
            }
            if (pages >= pagesPerRun) break
        }
        val outcome = Outcome(pages, sources.size - incomplete.size, sources.size, failures)
        index.recordRun(pages, failed = outcome.failed)
        return outcome
    }
}
