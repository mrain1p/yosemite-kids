package io.pickwick.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The config reconcile, off the ViewModel so something with no UI can run it.
 *
 * This is the *backstop*, not the delivery mechanism. A parent's edit is
 * pushed the moment it is saved (`Settings.pushAll`, plus two retries), and a
 * grant goes straight out over `POST /grant`. What lands here is the case
 * those miss: a device that was off for the whole minute someone was editing,
 * and — because a hub has no outbound anything — every edit made in the hub's
 * own admin GUI.
 *
 * It converges rather than pushing: [syncAction] compares both fingerprints,
 * and the answer between two sectioned-format peers is only ever "nothing" or
 * "merge". A merge fetches theirs, merges here, and sends the result back, so
 * it does not matter which side edited or in which order.
 */
object ConfigSync {

    /**
     * One sweep at a time, across every caller.
     *
     * This was a `@Volatile var` read-then-written by [MainViewModel] alone,
     * which was fine while the main thread was the only caller. A periodic
     * worker is a second caller on a second thread, and two sweeps overlapping
     * would have both sides pushing a config the other is mid-merge on — so
     * the claim has to be atomic, not merely visible.
     */
    private val inFlight = AtomicBoolean(false)

    /** True while a sweep is running, for the ring around the avatar. */
    val running: Boolean get() = inFlight.get()

    /**
     * Reconcile this device's config with every paired peer.
     *
     * Returns false without doing anything when there is nothing paired or a
     * sweep is already running — the caller uses that to decide whether to
     * show progress, and the worker to decide whether the tick did any work.
     *
     * @param onChanged run when a merge actually changed this device's config,
     *   so a UI caller can redraw. No-op for a background caller.
     * @param onSweeping true once this call owns the sweep, false when it is
     *   done. Raised *after* the claim, never before: a device with nothing
     *   paired, or one whose worker already holds the sweep, must not flash
     *   the ring round the avatar as though something synced.
     */
    suspend fun reconcile(
        store: ConfigStore,
        pairing: PairingStore,
        syncNotices: SyncNotices? = null,
        onConfigApplied: ((Whitelist, Whitelist) -> Unit)? = null,
        mergeLooks: (String) -> Boolean = { false },
        onChanged: () -> Unit = {},
        onSweeping: (Boolean) -> Unit = {}
    ): Boolean {
        val devices = pairing.paired()
        // Init and ON_START both fire this at launch — one reconcile is plenty.
        if (devices.isEmpty()) return false
        if (!inFlight.compareAndSet(false, true)) return false
        onSweeping(true)
        try {
            withContext(Dispatchers.IO) {
                sweep(store, pairing, devices, syncNotices, onConfigApplied, mergeLooks, onChanged)
            }
        } finally {
            // Cleared in a finally for the reason the old flag was: one
            // escaping exception must not latch it and kill sync for good.
            inFlight.set(false)
            onSweeping(false)
        }
        return true
    }

    private suspend fun sweep(
        store: ConfigStore,
        pairing: PairingStore,
        devices: List<PairedDevice>,
        syncNotices: SyncNotices?,
        onConfigApplied: ((Whitelist, Whitelist) -> Unit)?,
        mergeLooks: (String) -> Boolean,
        onChanged: () -> Unit
    ) {
        // Master election: the first parent device to run with the slot
        // empty claims it; only the master builds the search index (its
        // crawl is rate-limit-expensive, so the family pays it once).
        // Kid devices never claim it, no matter how empty the field is.
        // Two co-parents CAN race this and both claim — harmless: the
        // MS: term in the config hash makes the copies differ, the
        // newest-wins reconcile below converges them, and the loser's
        // next worker run sees it isn't master and stops crawling.
        val isParent = pairing.role() != PairingStore.Role.KID
        val loaded = store.load()
        // Not while the store is degraded: `save` here re-serializes
        // the whole config, so claiming master on a config that only
        // looks master-less because the file failed to parse would
        // write the empty read over the real file, and the sweep below
        // would then push that emptiness to every paired device.
        if (loaded.masterDeviceToken == null && isParent && !store.degraded) {
            val me = pairing.deviceToken()
            // Locked read-modify-write: `loaded` is already stale by
            // the time we get here, and handing a stale config to the
            // stamper reads a merged-in channel as a fresh add — which
            // clears its tombstone and resurrects a deleted channel.
            // No log line: claiming the index is not a parent's action.
            store.update { it.copy(masterDeviceToken = me) }
            android.util.Log.i("Pickwick", "claimed master role (indexing)")
        }
        // A kid may have restyled themselves on a device. Adopt that
        // first, so the hash compared below already carries the new
        // look and the push that follows delivers it everywhere —
        // otherwise the device's overlaid hash would read as "differs
        // but newer" forever. Only parents adopt; a kid device's
        // pending looks are for its phone to collect.
        if (isParent) {
            devices.forEach { d ->
                LanClient.looks(d)?.let { json ->
                    if (mergeLooks(json)) {
                        android.util.Log.i("Pickwick", "config sync: adopted a new look from ${d.name}")
                    }
                }
            }
        }
        devices.forEach { stored ->
            var device = stored
            var status = LanClient.fullStatus(device)
            if (status == null) {
                // Not answering at the stored address — likely a DHCP
                // re-lease while the TV sat off, or server port drift.
                // Sweep the LAN for our device and re-point the entry.
                val moved = LanClient.rediscover(device)
                if (moved == null) {
                    android.util.Log.i("Pickwick", "config sync: ${device.name} unreachable")
                    return@forEach
                }
                pairing.replacePaired(device, moved)
                android.util.Log.i(
                    "Pickwick",
                    "config sync: ${device.name} moved " +
                        "${device.host}:${device.port} -> ${moved.host}:${moved.port}"
                )
                device = moved
                status = LanClient.fullStatus(device) ?: return@forEach
            } else if (device.id == null && status.deviceToken != null) {
                // Legacy entry (paired before identities were stored):
                // learn who this is, so a future re-discovery can prove
                // it found the same device and not a sibling's tablet.
                device = device.copy(id = status.deviceToken)
                pairing.replacePaired(stored, device)
            }
            // Read per iteration, not hoisted above the loop. With two
            // TVs, merging the first one lands a co-parent's channel —
            // and comparing the second against the pre-merge hash
            // would take the do-nothing arm and leave it stale, while
            // the devices list cheerfully reported it in sync.
            // Without the API key for a peer that holds none. A hub
            // strips secrets before writing and cannot put them back,
            // so its hash is permanently the keyless one — and judged
            // against the full form this took the merge arm on every
            // sweep forever, fetching and re-merging a config that had
            // never changed. Everything else is judged on the full
            // form, so a rotated key still forces a push to a TV.
            val localHash =
                ConfigJson.fingerprint(store.load(), includeSecrets = !device.secretless)
            val localSyncHash = store.syncHash()
            val localAt = store.updatedAt()
            val remoteHash = status.hash
            when (
                syncAction(
                    localHash = localHash,
                    localSyncHash = localSyncHash,
                    localAt = localAt,
                    remoteHash = remoteHash,
                    remoteSyncHash = status.syncHash,
                    remoteSyncV = status.syncV,
                    remoteAt = status.updatedAt
                )
            ) {
                SyncAction.Nothing -> {}

                // The rendezvous. Their copy comes here, both sides are
                // merged, and the result goes back — so a co-parent's
                // edit reaches this phone without anyone pressing
                // anything, which is the whole point of the milestone.
                SyncAction.Merge -> {
                    val theirs = LanClient.fetchConfig(device)
                    if (theirs == null) {
                        // A peer that advertises the sync format and
                        // then serves nothing is almost always a hub
                        // with an empty volume: it answers 404 until
                        // its first write. Bailing here left it empty
                        // forever, because a hub never initiates and
                        // a TV has no Push button — only a parent
                        // opening settings and saving would break the
                        // deadlock. Seeding it is the obvious repair
                        // and costs nothing when the peer is simply
                        // unreachable, since the push fails too.
                        val seeded = LanClient.pushConfig(device, store.rawJson())
                        android.util.Log.i(
                            "Pickwick",
                            "config sync: ${device.name} advertised sync but served no config " +
                                "→ seeded it ${if (seeded) "accepted" else "REJECTED"}"
                        )
                        return@forEach
                    }
                    val outcome = store.mergeIncoming(theirs, device.name)
                    if (outcome == null) {
                        android.util.Log.w(
                            "Pickwick",
                            "config sync: ${device.name} sent a config we couldn't read"
                        )
                        return@forEach
                    }
                    if (outcome.changed) {
                        ConfigEvents.onConfigChanged?.invoke()
                        // Exactly what an inbound push does. Without
                        // it this device never clears its kid's
                        // pending restyle, so its hash differs from
                        // the peer's forever and this arm runs again
                        // every five minutes — and the kid is never
                        // told their rules moved.
                        onConfigApplied?.invoke(outcome.before, outcome.after)
                        onChanged()
                    }
                    // Recorded, never raised. A parent finds out when
                    // they next open Settings; a background sweep does
                    // not get to interrupt them. Parents only — a kid
                    // must not learn that their parents disagreed
                    // about their bedtime.
                    if (isParent && outcome.collisions.isNotEmpty()) {
                        syncNotices?.record(
                            outcome.collisions.map {
                                SyncNotices.describe(it, device.name)
                            }
                        )
                    }
                    if (outcome.changed || outcome.peerBehind) {
                        val ok = LanClient.pushConfig(device, store.rawJson())
                        android.util.Log.i(
                            "Pickwick",
                            "config sync: merged with ${device.name} " +
                                "(learned=${outcome.changed} theyLagged=${outcome.peerBehind}) " +
                                "→ pushed back ${if (ok) "accepted" else "REJECTED"}"
                        )
                    }
                }

                SyncAction.PushWhole -> {
                    val ok = LanClient.pushConfig(device, store.rawJson())
                    android.util.Log.i(
                        "Pickwick",
                        "config sync: pushed #$localHash to ${device.name} " +
                            "(had #$remoteHash, pre-merge build) → " +
                            if (ok) "accepted" else "REJECTED"
                    )
                }

                SyncAction.LeaveForParent -> android.util.Log.i(
                    "Pickwick",
                    "config sync: ${device.name} differs (#$remoteHash) and is on a " +
                        "pre-merge build whose copy claims to be newer — leaving for Push/Pull"
                )
            }
        }
    }

    // --- what every arrival path has to do ---------------------------------
    //
    // These were lambdas built in MainActivity and handed to BOTH the LAN
    // server and the ViewModel, under a comment reading "one lambda, both
    // callers, so they cannot drift". A background worker is a third caller
    // with no Activity to build them in, so they move here rather than being
    // written out a second time and drifting exactly as that comment feared.

    /**
     * Whose device this is: its dedicated assignment, else the last pick that
     * still names a real kid.
     *
     * A remembered pick can outlive its kid (deleted on the phone, the picker
     * not used since) and [ProfileNamespace.suffixFor] would auto-register an
     * orphan namespace, so it is validated against the config it came with.
     */
    fun kidHere(context: Context, config: Whitelist): String? =
        if (config.profiles.isEmpty()) null
        else config.deviceProfiles[PairingStore(context).deviceToken()]
            ?: ActiveProfileStore(context).activeId()?.takeIf { config.profile(it) != null }

    /**
     * What must happen whenever a config lands, however it arrived.
     *
     * Both arrival paths run this — an inbound push to the LAN server, and a
     * merge this device started itself. Hanging it off the inbound path alone
     * was harmless only while phones were the only things that swept: a device
     * that merged on its own never cleared its kid's pending restyle, so its
     * hash stayed different from the peer's forever and it re-merged every
     * five minutes, and its kid never saw the pill that says a rule moved.
     */
    fun applyArrived(context: Context, before: Whitelist, after: Whitelist) {
        // The phone adopted (or overrode) a look this device's kid chose: the
        // pending copy has done its job.
        ProfileLooks(context).ack(after)
        // The pill is the only thing that says a rule moved; otherwise the kid
        // finds out by hitting it. Judged on this device's own kid, so a change
        // to their sibling's rules stays quiet here.
        val kid = kidHere(context, after)
        val fresh = after.limitsFor(kid)
        KidNotices.configChange(
            before.limitsFor(kid), fresh,
            SessionGuard(context, ProfileNamespace(context).suffixFor(kid)).remainingTodayMin(fresh)
        )?.let { KidNotices.post(it) }
    }

    /**
     * Adopt a peer's pending kid looks into this device's config. True when the
     * config changed — the sweep then pushes it.
     */
    fun adoptLooks(store: ConfigStore, pairing: PairingStore, json: String): Boolean {
        // update{} rather than load-then-save: the read and the write must be
        // one locked step. A co-parent's push landing in between would look to
        // the stamper like this device adding whatever it brought, which clears
        // that unit's tombstone and can resurrect a channel a parent deleted.
        var adopted = false
        store.update(who = pairing.myName(), by = pairing.by()) { c ->
            ProfileLooks.mergeInto(c, json)?.also { adopted = true } ?: c
        }
        // The header avatar on this device follows too.
        if (adopted) ConfigEvents.onConfigChanged?.invoke()
        return adopted
    }
}

