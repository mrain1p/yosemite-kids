package io.yosemitekids.hub

import io.yosemitekids.app.data.FamilyDay
import io.yosemitekids.app.data.UsageLedger
import java.io.File

/**
 * The hub's copy of the family's watch ledger — minutes spent, per kid, per
 * day, per device.
 *
 * **Its own file, its own lock, and deliberately nothing to do with
 * [HubStore].** A counter is not curation. Config commits, the fingerprint,
 * `sync.log` and `HubVersions`' five-slot restore ring must be entirely
 * unaffected by watch traffic: a minute of viewing is not a parent's decision,
 * it must not move `syncHash`, it must not nudge every enrolled device, and it
 * must not push a family's change history out of a thirty-line log. Guard 44
 * in `scripts/check.*` holds the two apart. See `docs/PLAN-hub-parity.md` D1
 * and prohibition 12 in the sync skill.
 *
 * The hub is a relay here exactly as it is for the config: it stores, joins
 * and serves. It decides nothing. It cannot stop a television, and nothing in
 * this file tries to.
 *
 * **Writer-owns-cell is enforced for real on this side.** A device accepts a
 * peer's cells, because a parent's phone relaying the television's minutes to
 * the tablet is the only path a hubless family has. The hub does not need
 * that relay — every enrolled device can reach it directly — so it keeps only
 * the cells the caller authored, from the `X-Device-Id` the caller proved a
 * token with. In either direction `max` only ever goes up, so the worst a
 * hostile writer can do is cost a kid minutes, never grant them, and a
 * parent's correction downward is a **grant**, which merges, appears in the
 * change feed and reaches a sleeping device.
 */
class HubUsage(
    dataDir: File,
    /**
     * The family's `Whitelist.homeZone`, read fresh on every call so a parent
     * setting one takes effect without restarting the container. Null — or a
     * zone this JVM cannot resolve — means the hub does not know what day it
     * is here, and windows nothing by day; see [today].
     */
    private val homeZone: () -> String? = { null },
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private val file = File(dataDir, "usage.json")

    /**
     * One lock across read, join and write, for the same reason [HubStore]
     * holds one: the server answers on a fixed pool of four threads, and two
     * devices can report at the same instant. An unlocked read-modify-write
     * would lose one of them — which is the exact bug a join exists to
     * prevent, reintroduced a layer down.
     */
    private val lock = Any()

    init {
        dataDir.mkdirs()
    }

    /**
     * The family's day, or null when they have not named a zone this hub can
     * resolve.
     *
     * The one calendar this container is allowed to read, and it is the
     * family's, not its own: guard 27 keeps every name for the container's own
     * calendar out of this module for ever, and permits a zone only where it
     * came out of the config. `FamilyDay.zoneOrNull` is what makes "I do not
     * know what day it is here" expressible — a container that guessed UTC
     * would trim a household in Auckland a day early, every day, and nothing
     * would throw.
     */
    fun today(): String? =
        FamilyDay.zoneOrNull(homeZone())?.let { FamilyDay.of(now(), it) }

    /** The whole ledger this hub holds, in the wire form a device speaks. */
    fun exportJson(): String = synchronized(lock) { UsageLedger.toJson(load()) }

    /**
     * Join what [writer] reports into what this hub holds.
     *
     * Returns the number of cells stored afterwards, or -1 for a body this hub
     * will not vouch for — a caller answers 400 on that rather than a
     * half-merge. A missing or unrecognised [writer] is refused outright: a
     * cell with no author cannot be checked against one.
     */
    fun merge(body: String?, writer: String?): Int {
        val id = writer?.trim().orEmpty()
        if (id.isEmpty()) return -1
        val incoming = UsageLedger.parse(body) ?: return -1
        // Theirs, and only theirs. Everything else in the body is either a
        // relay this box does not need or a claim about a device that can
        // speak for itself.
        val owned = UsageLedger.ownedBy(incoming, id)
        return synchronized(lock) {
            val merged = bound(UsageLedger.merge(load(), owned))
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(UsageLedger.toJson(merged))
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) {
                    file.writeText(UsageLedger.toJson(merged))
                    tmp.delete()
                }
            }
            UsageLedger.count(merged)
        }
    }

    /** Minutes every device reports for [kidId] on [day] — for the hub's own pages. */
    fun minutesFor(kidId: String?, day: String): Int = synchronized(lock) {
        // "" is never a device id, so nothing is excluded: the hub is not a
        // watcher and holds no cell of its own to double-count.
        UsageLedger.othersToday(load(), "", kidId, day)
    }

    private fun load(): UsageLedger.Ledger =
        runCatching { UsageLedger.parse(file.readText()) }.getOrNull() ?: UsageLedger.Ledger.EMPTY

    /**
     * What this box keeps.
     *
     * With a home zone, the family's own window — a week back, a day forward.
     * Without one the hub cannot name a day, so it falls back to a plain cell
     * cap, dropping whole days oldest-first. Never a guess at the calendar:
     * the fallback is arithmetic on data the devices sent, which is the same
     * answer on every container in every locale.
     */
    private fun bound(ledger: UsageLedger.Ledger): UsageLedger.Ledger {
        val day = today() ?: return UsageLedger.cap(ledger)
        return UsageLedger.cap(
            UsageLedger.trim(UsageLedger.forward(ledger, day), day)
        )
    }
}

