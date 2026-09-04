package io.pickwick.app.data

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
 * The config reconcile on a schedule, so a device that nobody is looking at
 * still converges.
 *
 * Before this, the only sweep lived in `MainViewModel` behind `uiActive`, which
 * meant a TV was current the moment a kid opened Pickwick and never otherwise.
 * That was survivable while a parent's phone was the only thing that edited —
 * a save pushes straight out and retries twice. It stopped being survivable
 * once the hub grew a full settings GUI: **a hub has no outbound anything**, so
 * an edit made in its admin pages is not pushed to anyone, and reaches a TV
 * only when that TV next asks. On a TV nobody had opened, that was never.
 *
 * What this does NOT fix: the device is still not *reachable* while closed.
 * [LanServer] is built by `MainActivity` and dies with the process, so a
 * parent's phone still shows a sleeping TV as unreachable and "Play on TV"
 * still cannot wake it. This closes convergence, not reachability.
 */
class ConfigSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pairing = PairingStore(applicationContext)
        val store = ConfigStore(applicationContext)

        // Never write over a config we could not read. `reconcile` merges and
        // saves, and a degraded store means the file failed to parse — saving
        // then would put the empty read where the real config was and push
        // that emptiness at every paired device.
        if (store.degraded) return Result.success()

        ConfigSync.reconcile(
            store = store,
            pairing = pairing,
            // Parents only: a kid device must never surface that their parents
            // disagreed about their rules. Same rule the ViewModel applies.
            syncNotices = if (pairing.role() != PairingStore.Role.KID) {
                SyncNotices(applicationContext)
            } else null,
            // The same two the LAN server and the ViewModel are given. There is
            // deliberately no fourth copy of these bodies.
            onConfigApplied = { before, after ->
                ConfigSync.applyArrived(applicationContext, before, after)
            },
            mergeLooks = { json -> ConfigSync.adoptLooks(store, pairing, json) }
            // No onChanged: there is no UI to redraw. A config that landed here
            // is on disk, and whatever opens next reads it.
        )
        // Always success. A failure here is a peer being asleep, which is the
        // normal case this worker exists for — retrying with backoff would
        // just double up on the next period.
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "pickwick-config-sync"

        /**
         * 15 minutes is WorkManager's floor for periodic work, and Doze can
         * stretch it further. That is the honest ceiling on how stale a
         * sleeping device can be, and it is a backstop rather than the
         * delivery path: a parent's own edit still goes out immediately.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ConfigSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        // Nothing to reconcile with no network, and the sweep
                        // would burn a subnet scan finding that out.
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
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
