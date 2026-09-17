package io.yosemitekids.app.ui

import android.content.Context
import io.yosemitekids.app.data.*
import kotlinx.coroutines.launch

/**
 * The one place the LAN server is wired to this device's stores.
 *
 * It lived inline in `MainActivity.onCreate` until 1.10.0, which meant the
 * server could only ever be born from a screen. A television's server now
 * outlives its screen (`LanService`), and a service restarted by the system
 * after a kill has no activity to borrow the wiring from, so it moved here:
 * every closure below needs only the application context and the stores.
 * Nothing about what the server does changed - this is the same hundred
 * lines, called from two places instead of one.
 */
internal fun buildLanServer(
    appContext: Context,
    configStore: ConfigStore,
    pairingStore: PairingStore,
    profileNs: ProfileNamespace
): LanServer {
    // The sync worker is a third caller and has no Activity, so the bodies
    // live in ConfigSync and these are the local names for them.
    fun kidHere(f: Whitelist): String? = ConfigSync.kidHere(appContext, f)
    val applyConfig: (Whitelist, Whitelist) -> Unit = { before, after ->
        ConfigSync.applyArrived(appContext, before, after)
    }
    return LanServer(
        configStore,
        grantHandler = { minutes, profileId, grant ->
            // Route the grant to the named kid's guard; unnamed grants
            // (older admin phones) land on whoever this device shows.
            // An explicit id stays unvalidated on purpose — a grant can
            // arrive moments before the config push that introduces its
            // (new) kid.
            val here = kidHere(ConfigStore(appContext).load())
            val target = profileId ?: here
            val guard = SessionGuard(appContext, profileNs.suffixFor(target))
            // A tap the config also carries is counted by id, so the
            // config landing later adds nothing; a legacy call has no
            // id and is applied as it always was.
            val fresh = if (grant != null) guard.applyGrant(grant)
            else { guard.grantExtraMinutes(minutes); true }
            // Only the kid the minutes belong to hears about them —
            // a grant aimed at their sibling is not their news.
            if (fresh && target == here) KidNotices.post(KidNotices.grant(minutes))
        },
        pairingStore,
        statsProvider = { profileId -> io.yosemitekids.app.data.Stats.build(appContext, profileId) },
        watchStateProvider = { WatchSync.exportJson(appContext) },
        watchStateMerger = { json ->
            // mergeJson is false for garbage AND for "nothing new";
            // a parse check tells the two apart for the 400.
            val wellFormed = runCatching { org.json.JSONObject(json) }.isSuccess
            if (wellFormed && WatchSync.mergeJson(appContext, json)) {
                // Refresh keep-watching / hearts on the TV right away.
                ConfigEvents.onConfigChanged?.invoke()
            }
            wellFormed
        },
        looksProvider = { io.yosemitekids.app.data.ProfileLooks(appContext).exportJson() },
        verdictsProvider = {
            io.yosemitekids.app.data.ScreeningStore(appContext)
                .exportJson(ConfigStore(appContext).load().ai.rulesVersion)
        },
        verdictsMerger = { json ->
            val imported = io.yosemitekids.app.data.ScreeningStore(appContext)
                .importJson(json, ConfigStore(appContext).load().ai.rulesVersion)
            // Imported ALLOWs reveal videos this device hadn't screened yet.
            if (imported > 0) ConfigEvents.onConfigChanged?.invoke()
            imported >= 0
        },
        // The watch ledger. Served on demand rather than on a timer:
        // this device's own minutes live in SessionGuard's prefs and
        // are authoritative there, so the honest moment to author a
        // cell is the moment somebody asks. No scheduler, no wakelock,
        // and a peer that never asks costs nothing. Both lambdas run
        // on a LanServer worker thread, which is where the file I/O
        // belongs.
        usageProvider = {
            val config = ConfigStore(appContext).load()
            // Every kid's own tally, not just the one on screen: a
            // sibling's minutes are as real when their profile is not
            // the active one, and a peer asking now is the only chance
            // to say so. UsageSync.recordOwn authors each cell from
            // that kid's OWN minutes — never the shared total, which
            // the peer would read back and add to its own counter.
            io.yosemitekids.app.data.UsageSync.recordOwn(appContext, config)
            io.yosemitekids.app.data.WatchLedgerStore(appContext).exportJson()
        },
        usageMerger = { json ->
            val config = ConfigStore(appContext).load()
            val merged = io.yosemitekids.app.data.WatchLedgerStore(appContext).mergeJson(
                json,
                io.yosemitekids.app.data.UsageSync.today(config)
            )
            // A peer's minutes have just landed, which for a shared
            // budget is the freshest this device will ever be about
            // them. Folding them in here rather than at the next read
            // keeps enforcement off the network entirely: the player
            // asks SessionGuard, and SessionGuard asks a prefs mirror.
            if (merged) io.yosemitekids.app.data.UsageSync.mirror(appContext, config)
            merged
        },
        indexStatusProvider = {
            io.yosemitekids.app.data.ChannelIndex(appContext).statusJson()
        },
        indexSourceProvider = { sourceId ->
            io.yosemitekids.app.data.ChannelIndex(appContext).exportSourceWithState(sourceId)
        },
        indexMerger = { sourceId, body ->
            io.yosemitekids.app.data.ChannelIndex(appContext).importSourceWithState(sourceId, body)
        },
        onConfigApplied = applyConfig,
        // A hub (or a co-parent's phone) says its copy moved. Run the
        // ordinary reconcile now instead of at the next tick — off the
        // server's own threads, so the caller is not held while this
        // device sweeps every peer it has.
        onSyncRequested = {
            io.yosemitekids.app.data.LanPushScope.scope.launch {
                io.yosemitekids.app.data.ConfigSync.reconcile(
                    configStore, pairingStore,
                    onConfigApplied = applyConfig,
                    mergeLooks = { json ->
                        io.yosemitekids.app.data.ConfigSync.adoptLooks(
                            configStore, pairingStore, json
                        )
                    },
                    index = io.yosemitekids.app.data.ChannelIndex(appContext),
                    context = appContext
                )
            }
        },
        deviceKind = { io.yosemitekids.app.data.DeviceKind.of(appContext) },
        // "Update now" from a paired phone: check, download, and put
        // the installer prompt on this screen. Whoever holds the
        // remote confirms it; the phone only gets to ask.
        onUpdateRequested = {
            io.yosemitekids.app.data.RemoteUpdate.handleBlocking(appContext)
        }
    )
}
