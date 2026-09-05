package io.yosemitekids.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Keeps the video caches current while the app is closed. See [ContentWarm].
 */
class ContentWarmWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        ContentWarm.run(applicationContext)
        // Always success. A channel that failed is simply the stalest next
        // time, so retrying with backoff would only duplicate what the next
        // period does anyway — and a retry storm against YouTube is the one
        // outcome that could break playback for the whole family.
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "yosemite-kids-content-warm"

        /**
         * Six hours, not fifteen minutes.
         *
         * The kid-facing goal is "the newest videos are already there when the
         * TV is turned on", and children's channels do not upload more often
         * than daily — four passes a day is ample. The cost of being wrong in
         * the other direction is real: every device warms its own cache (they
         * are per-device by design), so a three-device family at the 15-minute
         * floor would be several hundred requests an hour at YouTube from one
         * household, and being throttled breaks playback rather than freshness.
         */
        private const val PERIOD_HOURS = 6L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ContentWarmWorker>(
                PERIOD_HOURS, TimeUnit.HOURS
            ).setConstraints(
                Constraints.Builder()
                    // UNMETERED, unlike the config sync: this fetches channel
                    // listings for every whitelisted channel, and doing that on
                    // a parent's cellular plan to keep a phone's home fresh is
                    // not a trade they agreed to. A TV is on wifi always.
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    // Freshness is never worth the last of someone's battery.
                    .setRequiresBatteryNotLow(true)
                    .build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // KEEP: rescheduling on every launch must not reset the period,
                // or a device opened often would never reach its first run.
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
