package io.yosemitekids.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Background channel-index crawl, so the catalog fills in without the app
 * being open. WorkManager guarantees execution across process death and Doze;
 * each run does a bounded batch of pages and reschedules, keeping the per-run
 * network cost small and well under YouTube's throttling threshold.
 *
 * Only the master device does useful work here — every other device returns
 * success immediately (the master pushes the index to them over the LAN).
 */
class IndexCrawlWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pairingStore = PairingStore(applicationContext)
        val config = ConfigStore(applicationContext).load()
        val me = pairingStore.deviceToken()

        val index = ChannelIndex(applicationContext)
        val crawler = IndexCrawler(YouTubeRepository(), index)

        // Resolve the current whitelist into Sources (labels/avatars may be
        // absent — the index only needs id/url/kind, so a bare Source is fine).
        val sources = config.sources.map { e ->
            Source(e.id, e.url, e.label ?: e.id, null, e.kind, e.timeMultiplierPercent)
        }
        // Drop sources the whitelist no longer lists. On EVERY device, before
        // the master gate: pushes never propagate deletions, so a kid device
        // that skipped this would keep a removed channel's index forever.
        val wanted = sources.map { it.id }.toSet()
        index.allStates().keys.filter { it !in wanted }.forEach { crawler.dropSource(it) }

        // Master-only past here: a kid device or co-parent must never crawl.
        if (config.masterDeviceToken != me) return Result.success()

        // The loop itself is IndexCrawlRun, in :crawl, because the hub runs
        // the same one. This worker only decides whether to run it and where
        // its log lines go — logcat, the "is it stuck?" answer without a
        // debugger.
        val outcome = IndexCrawlRun.run(
            index, sources,
            crawlOnce = { crawler.crawlOnce(it) },
            onFailure = { android.util.Log.w("YosemiteKids", "index crawl failed", it) }
        )
        android.util.Log.i("YosemiteKids", outcome.summary)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "channel-index-crawl"

        /** Running now, or waiting for [nextRunAt] (null = not scheduled). */
        data class RunSchedule(val running: Boolean, val nextRunAt: Long?)

        /**
         * Live schedule for the diagnostics row. A flow, not a one-shot read:
         * a crawl runs 5-6 minutes of every 15, so a snapshot taken while
         * expanded goes stale within the visit. WorkManager also reports
         * nextScheduleTimeMillis as Long.MAX_VALUE for every non-ENQUEUED
         * state — RUNNING included — so the time alone can't distinguish
         * "crawling right now" from "never scheduled"; the state can.
         */
        fun runSchedule(context: Context): Flow<RunSchedule> =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(WORK_NAME)
                .map { infos ->
                    val info = infos.firstOrNull()
                    RunSchedule(
                        running = info?.state == WorkInfo.State.RUNNING,
                        nextRunAt = info?.nextScheduleTimeMillis
                            ?.takeIf { it > 0 && it != Long.MAX_VALUE }
                    )
                }

        /** 15 minutes is WorkManager's minimum periodic interval. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<IndexCrawlWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP: rescheduling on every launch must not reset the period.
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
