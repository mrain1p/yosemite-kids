package io.yosemitekids.app.data

import kotlinx.coroutines.delay
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException

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

    /**
     * How long a source YouTube called gone is left alone before one more
     * try. A day: "does not exist" and "terminated" are not the flaky kind of
     * error, and a channel that comes back after a takedown comes back in
     * days, not minutes. Until then it costs the run nothing.
     */
    const val GONE_RETRY_MS = 24 * 60 * 60_000L

    data class Outcome(val pages: Int, val complete: Int, val total: Int, val failures: Int, val gone: Int = 0) {
        /**
         * Failed = a source was attempted and threw without yielding a page:
         * the red dot in settings. A run that simply had nothing to do, or
         * that got its pages before a later source failed, is fine — and so
         * is a run whose only news was a source YouTube says is gone. That
         * one used to count as a failure, and with every other channel
         * complete it was the ONLY thing the run ever did: one dead channel
         * failed every crawl and backed the hub off for hours at a time.
         */
        val failed: Boolean get() = failures > 0 && pages == 0

        /** The one log line: confirms the run happened, how much it did, how far along the catalog is. */
        val summary: String
            get() = "index crawl: $pages pages this run, $complete/$total sources complete" +
                (if (gone > 0) ", $gone gone from YouTube" else "")
    }

    /**
     * @param crawlOnce fetches one page of [Source] into [index]; true when
     *   there is more to fetch. Throws on a failed fetch.
     * @param onFailure sees each throw; the run counts it and moves on.
     * @param onGone sees each source YouTube refused outright, with the
     *   reason in YouTube's words; the index remembers it ([ChannelIndex.markGone]).
     */
    suspend fun run(
        index: ChannelIndex,
        sources: List<Source>,
        crawlOnce: suspend (Source) -> Boolean,
        onFailure: (Throwable) -> Unit = {},
        delayMs: Long = IndexCrawler.CRAWL_DELAY_MS,
        pagesPerRun: Int = PAGES_PER_RUN,
        onGone: (Source, String) -> Unit = { _, _ -> },
        now: () -> Long = System::currentTimeMillis
    ): Outcome {
        val t = now()
        val incomplete = sources.filter { s ->
            val state = index.state(s.id)
            state?.complete != true && (state?.gone == null || t - state.goneAt >= GONE_RETRY_MS)
        }
        if (incomplete.isEmpty()) {
            // Still stamp the diagnostics line: a fully-crawled catalog should
            // read "ran, nothing to do", not "hasn't run since the last page".
            index.recordRun(0, failed = false)
            val goneNow = sources.count { index.state(it.id)?.gone != null }
            return Outcome(0, sources.count { index.state(it.id)?.complete == true }, sources.size, 0, goneNow)
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
                    .getOrElse { e ->
                        val why = goneReason(e)
                        if (why != null) {
                            index.markGone(source.id, why, now())
                            onGone(source, why)
                        } else {
                            onFailure(e)
                            failures++
                        }
                        false
                    }
                if (!more) break
                pages++
            }
            if (pages >= pagesPerRun) break
        }
        // Counted from the index, not by subtraction: a source that was
        // attempted and turned out gone would otherwise be taken off twice.
        val gone = sources.count { index.state(it.id)?.gone != null }
        val complete = sources.count { index.state(it.id)?.complete == true }
        val outcome = Outcome(pages, complete, sources.size, failures, gone)
        index.recordRun(pages, failed = outcome.failed)
        return outcome
    }

    /**
     * YouTube's own verdict on a source, or null for anything that might be
     * this box's fault (a timeout, a bot wall, a parse error). The extractor
     * says "content not available" for a deleted, private or terminated
     * channel and for a playlist that no longer exists, and
     * `YouTubeRepository.retryDelaysFor` already treats it as permanent;
     * this is the same judgement, one level up, so the run does not carry
     * it as a failure. The message is YouTube's wording with the extractor's
     * "Got error:" wrapper and quotes stripped: what the console shows.
     */
    internal fun goneReason(e: Throwable): String? {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is ContentNotAvailableException) {
                val raw = cause.message.orEmpty()
                return raw.removePrefix("Got error:").trim().trim('"').ifEmpty { "not available on YouTube" }
            }
            cause = cause.cause
        }
        return null
    }
}
