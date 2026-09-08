package io.yosemitekids.app.data

import android.content.Context
import java.io.File

/**
 * This device's copy of the family's watch ledger.
 *
 * **Its own file, its own lock, and deliberately not `config.json`.** A
 * counter is not curation: every unit of the merged config resolves by who
 * acted later, and a counter has no winner, it has a join. The practical half
 * of the same argument is that `SyncDecision.syncAction` takes the `Merge` arm
 * on any `syncHash` difference, so a minute-by-minute counter in the config
 * would mean a status, a fetch, a merge and a re-push between every pair of
 * peers plus a hub nudge to every enrolled device, once a minute, for as long
 * as anyone in the house is watching — and it would push a family's change
 * history out of a thirty-line log in half an hour. Guard 44 in
 * `scripts/check.*` keeps this file out of the merge. See prohibition 12 in
 * `.claude/skills/yosemite-kids-sync/SKILL.md`.
 *
 * What lives here is learned as well as authored: this device's own cells, and
 * whatever peers have reported. [UsageLedger] holds the laws — grow-only
 * cells, per-cell `max`, and a window applied only to what this device stores
 * for itself.
 *
 * Every method touches the disk, so every caller is off-main
 * (`Dispatchers.IO`, or a `LanServer` worker thread).
 */
class WatchLedgerStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "usage.json")
    private val deviceId = PairingStore(context.applicationContext).deviceToken()

    /**
     * The day this device last authored a cell under, so its own day can
     * ratchet forward and never back — the same rule `SessionGuard`'s
     * rollover follows, for the same reason. A device whose clock stepped
     * backwards would otherwise start writing under yesterday and read today's
     * aggregate as empty.
     */
    private val dayFile = File(context.applicationContext.filesDir, "usage-day.txt")

    companion object {
        /**
         * One lock for the file, held across read-join-write.
         *
         * `LanServer` answers on a small pool, so two peers can post at the
         * same instant, and the app's own writer is a third thread. An
         * unlocked read-modify-write would lose one of them — the very bug a
         * join exists to prevent, one layer down. Static because two
         * `WatchLedgerStore` instances are the same file.
         */
        private val FILE_LOCK = Any()
    }

    /** The ledger this device holds, in the wire form a peer speaks. */
    fun exportJson(): String = synchronized(FILE_LOCK) { UsageLedger.toJson(load()) }

    /**
     * Raise this device's own cell for [kidId] to [minutes], under this
     * device's own [day].
     *
     * Never lowers it, and never adopts a peer's day: the day comes from the
     * caller's own [FamilyDay], ratcheted forward through [FamilyDay.rollover].
     * `max(localDay, seenDay)` propagated through a merge would make the day
     * boundary a contagious ratchet — one television with a wrong clock walks
     * the whole household forward and hands out a budget no parent action
     * reverses. See `docs/PLAN-hub-parity.md` D3.
     */
    fun recordOwn(kidId: String?, day: String, minutes: Int, at: Long = System.currentTimeMillis()) {
        synchronized(FILE_LOCK) {
            val today = FamilyDay.rollover(storedDay(), day)
            runCatching { dayFile.writeText(today) }
            write(
                // Trimmed here and only here: the window is what this device
                // keeps for itself, never what it merges and never what it
                // serves (D2).
                UsageLedger.trim(
                    UsageLedger.withOwn(load(), kidId, today, deviceId, minutes, at),
                    today
                )
            )
        }
    }

    /**
     * Join a peer's ledger into this one. False for a body this device will
     * not vouch for, so the caller answers 400 rather than half-merging.
     *
     * A device accepts foreign cells, where the hub keeps only the caller's
     * own. That asymmetry is deliberate: a parent's phone relaying the
     * television's minutes to the tablet is the only path a family with no hub
     * has, and that caller already holds a token that can rewrite the whole
     * config — so the ledger's blast radius is strictly smaller than what it
     * could already do. `max` only goes up either way: a bad actor can cost a
     * kid minutes, never grant them, and the correction downward is a grant.
     */
    fun mergeJson(body: String?, today: String): Boolean {
        val incoming = UsageLedger.parse(body) ?: return false
        synchronized(FILE_LOCK) {
            // Read-side only: a peer whose clock is a year fast must not be
            // able to pre-spend a day nobody has reached. One day of slack,
            // because two devices either side of midnight in different zones
            // are both telling the truth.
            write(UsageLedger.cap(UsageLedger.merge(load(), UsageLedger.forward(incoming, today))))
        }
        return true
    }

    /** Minutes devices *other than this one* report for [kidId] on [day]. */
    fun peerMinutes(kidId: String?, day: String): Int = synchronized(FILE_LOCK) {
        UsageLedger.othersToday(load(), deviceId, kidId, day)
    }

    private fun storedDay(): String? =
        runCatching { dayFile.readText().trim().ifEmpty { null } }.getOrNull()

    private fun load(): UsageLedger.Ledger =
        runCatching { UsageLedger.parse(file.readText()) }.getOrNull() ?: UsageLedger.Ledger.EMPTY

    private fun write(ledger: UsageLedger.Ledger) {
        val text = UsageLedger.toJson(ledger)
        val tmp = File(file.parentFile, file.name + ".tmp")
        runCatching {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                // Some filesystems refuse to rename over an existing file.
                file.delete()
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                    tmp.delete()
                }
            }
        }
    }
}
