package io.yosemitekids.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The half of a shared budget that moves: minutes out to the peers, minutes
 * in from them, and the one line that folds what came in into the number the
 * player actually enforces on.
 *
 * `WatchLedgerStore` is the document and `SessionGuard` is the enforcer;
 * neither knew about the other until this file. The ledger, the family day and
 * `GET|POST /usage` all shipped in 1.2.0 with nothing driving them, because
 * nothing in the config yet said a budget was shared — so
 * [SessionGuard.notePeerMinutes] had no caller and a device pushed its cells
 * nowhere. [Limits.budgetScope] is what closed that, and this is where it is
 * read on a device.
 *
 * Three rules shape everything here:
 *
 * - **Nothing happens for a family that has not asked for it.** [sharedKids]
 *   is empty for every household on the default scope, and every entry point
 *   returns on it. A shared budget costs traffic and a file write per sweep;
 *   a family that does not use one pays neither.
 * - **Enforcement stays local and immediate** (`docs/PLAN-hub-parity.md` D4).
 *   Nothing here is on the path of a kid pressing play. The number a player
 *   stops on is `SessionGuard`'s own prefs mirror, refreshed here when a
 *   ledger happens to arrive; a device out of touch with its peers falls back
 *   to its own count and never gives a kid *less* time than it does today.
 * - **A device authors only its own cell**, from its own tally
 *   ([SessionGuard.ownWatchedTodayMin]) and never from the shared total. See
 *   that function for what the other choice costs.
 *
 * Every method touches disk or the network, so every caller is off-main.
 */
object UsageSync {

    /**
     * Which kids on this device count minutes across every screen.
     *
     * Resolved through [Whitelist.limitsFor] and by no second rule, which is
     * narrower than it reads: a kid who exists gets their own `Profile.limits`
     * and inherits nothing from `Whitelist.limits` but a family-wide pause. So
     * this is a per-child decision in any household that has children, and the
     * family scalar is the answer only where there are no profiles — under the
     * null kid the ledger keys as `-`. Do not "fix" that here: a fallback
     * invented in this file would share a budget the settings screen never
     * offered to share.
     */
    fun sharedKids(config: Whitelist): List<String?> =
        if (config.profiles.isEmpty()) {
            if (config.limits.sharesBudget) listOf(null) else emptyList()
        } else {
            config.profiles.map { it.id }.filter { config.limitsFor(it).sharesBudget }
        }

    /** Every kid this device keeps a tally for — shared or not. */
    private fun allKids(config: Whitelist): List<String?> =
        if (config.profiles.isEmpty()) listOf(null) else config.profiles.map { it.id }

    /**
     * The day this device buckets minutes into: the family's, when a parent
     * has named a zone, and this device's own when they have not.
     *
     * A device always has an honest fallback where the hub does not, which is
     * why this is [FamilyDay.zoneOf] and `HubUsage.today()` is
     * `FamilyDay.zoneOrNull`. Nothing here can refuse to name a day.
     */
    fun today(config: Whitelist): String =
        FamilyDay.of(System.currentTimeMillis(), FamilyDay.zoneOf(config.homeZone))

    /**
     * Write this device's own cells for every kid it holds a tally for.
     *
     * Every kid, not just the shared ones and not just the one on screen: a
     * sibling's minutes are as real when their profile is not the active one,
     * and a scope turned on tomorrow wants yesterday's cells to have been
     * authored honestly today.
     */
    fun recordOwn(context: Context, config: Whitelist) {
        val store = WatchLedgerStore(context)
        val day = today(config)
        val ns = ProfileNamespace(context)
        allKids(config).forEach { kid ->
            store.recordOwn(kid, day, SessionGuard(context, ns.suffixFor(kid)).ownWatchedTodayMin())
        }
    }

    /**
     * Fold the peers' minutes for today into each shared kid's enforcer.
     *
     * **This is [SessionGuard.notePeerMinutes]' only caller.** Under any other
     * scope it is not called at all, so the arithmetic a device does is
     * byte-for-byte the arithmetic it did before this existed — asserted by
     * `SessionGuardBudgetTest`, not assumed.
     *
     * Returns what it mirrored, keyed by kid, so a caller can log it; the
     * failure this makes visible is the one that is otherwise invisible, a
     * television quietly enforcing against a total nobody can see.
     */
    fun mirror(context: Context, config: Whitelist): Map<String?, Int> {
        val kids = sharedKids(config)
        if (kids.isEmpty()) return emptyMap()
        val store = WatchLedgerStore(context)
        val day = today(config)
        val ns = ProfileNamespace(context)
        return kids.associateWith { kid ->
            val minutes = store.peerMinutes(kid, day)
            SessionGuard(context, ns.suffixFor(kid)).notePeerMinutes(minutes)
            minutes
        }
    }

    /**
     * Author this device's cells, trade ledgers with [peers], and mirror what
     * came back.
     *
     * Both directions device-initiated (D5): this device pushes what only it
     * knows and pulls what it needs, so no peer — the hub least of all — ever
     * holds a credential on anything. It runs at the end of a config sweep,
     * which `docs/PLAN-hub-parity.md` D7 is explicit about being a *backstop*
     * rather than the mechanism: fifteen minutes is WorkManager's floor, not a
     * number anyone chose, and the moment a stale total actually matters is a
     * kid pressing play. What makes the backstop tolerable is the direction of
     * the error — a device that has heard nothing counts only its own minutes
     * and gives the kid more time, never less.
     *
     * Returns the number of peers that answered.
     */
    suspend fun exchange(
        context: Context,
        config: Whitelist,
        peers: List<PairedDevice>
    ): Int = withContext(Dispatchers.IO) {
        if (sharedKids(config).isEmpty() || peers.isEmpty()) return@withContext 0
        recordOwn(context, config)
        val store = WatchLedgerStore(context)
        val day = today(config)
        var reached = 0
        val mine = store.exportJson()
        peers.forEach { peer ->
            // Push first. A peer that answers the pull and not the push has
            // still learned nothing about this device, and the pull is what
            // this device's own enforcement depends on.
            LanClient.pushUsage(peer, mine)
            val theirs = LanClient.fetchUsage(peer) ?: return@forEach
            if (store.mergeJson(theirs, day)) reached++
        }
        mirror(context, config)
        reached
    }
}
