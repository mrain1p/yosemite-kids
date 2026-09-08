package io.yosemitekids.app.data

import org.json.JSONObject

/**
 * How many minutes a kid has spent, per device, per day — a **counter**, and
 * therefore the one thing in this project that deliberately does not live in
 * `config.json`.
 *
 * Every unit of the merged config resolves by *who acted later*: a stamp, and
 * a winner. A counter has no winner; it has a **join**. Two devices that each
 * played twenty minutes did not disagree with each other, and last-writer-wins
 * throws twenty minutes away. That is the deep reason. The shallow one is just
 * as fatal: `SyncDecision.syncAction` takes the `Merge` arm on any `syncHash`
 * difference, so a counter in the config would mean a status, a fetch, a merge
 * and a re-push between every pair of peers plus a hub nudge to every enrolled
 * device, once a minute, for as long as anyone in the house is watching — and
 * `SyncMeta.MAX_LOG` is 30 lines, so a usage line a minute would wipe a
 * family's change history in half an hour. See prohibition 12 in
 * `.claude/skills/yosemite-kids-sync/SKILL.md` and `docs/PLAN-hub-parity.md`
 * D1.
 *
 * So: its own document, its own file, its own lock, outside
 * `ConfigJson.fingerprint` and outside `syncHash`. Guard 44 in
 * `scripts/check.*` is what keeps it there.
 *
 * ```
 * { "v": 1, "cells": { "<kidId>": { "<yyyy-MM-dd>": { "<deviceId>": { "m": 42, "at": 175… } } } } }
 * ```
 *
 * - `kidId` — the 8-hex profile id, or [NO_KID] for a family with no profiles.
 * - `<yyyy-MM-dd>` — the writer's own [FamilyDay], never a peer's.
 * - `deviceId` — the writer's own pairing token, the same identity
 *   `Whitelist.deviceProfiles` is keyed by. Not a hub-issued enrolment token.
 * - `m` — whole **counted** minutes (post-multiplier, the unit
 *   `dailyWatchedMs` is in), rounded down, so the ledger is never ahead of
 *   what the kid actually spent.
 * - `at` — the writer's wall clock, for display only. Never merged on, never
 *   used to order anything.
 *
 * The laws, each stated so it can be a test:
 *
 * - **Ownership** — a device authors only cells bearing its own id. Every
 *   other cell it holds is learned.
 * - **Monotone** — a local write is `max(old, new)`; `m` never decreases.
 * - **Join** — [merge] is per-cell `max`. Two parameters, **no clock** — see
 *   the note on [merge].
 * - **Window** — [trim] is a separate, local operation (D2).
 * - **Forward** — [forward] drops cells dated more than a day ahead of the
 *   reader, so a peer with a fast clock cannot pre-spend a day nobody has
 *   reached.
 * - **Aggregation** — [othersToday] sums cells for devices *other than* the
 *   reader.
 */
object UsageLedger {

    const val VERSION = 1

    /** Days of history a device keeps for itself. See [trim]. */
    const val KEEP_DAYS = 7

    /**
     * The most cells any face will hold or accept from one body.
     *
     * Kids × devices × [KEEP_DAYS]: three kids and five devices is 105
     * numbers. Two thousand is far above any household and is here because
     * this parses from the wire — `LanServer` faces the whole LAN before any
     * token is checked, and anything that allocates from request data is
     * bounded.
     */
    const val MAX_CELLS = 2_000

    /** The kid key a family with no profiles writes under. Never a valid 8-hex id. */
    const val NO_KID = "-"

    /** One device's minutes for one kid on one day. */
    data class Cell(val minutes: Int, val at: Long = 0L)

    /** kidId → day → deviceId → [Cell]. */
    data class Ledger(val cells: Map<String, Map<String, Map<String, Cell>>> = emptyMap()) {
        val isEmpty: Boolean get() = cells.values.all { d -> d.values.all { it.isEmpty() } }

        companion object {
            val EMPTY = Ledger()
        }
    }

    /**
     * The join: per-cell `max` on the minutes, and on `at` so a display
     * timestamp settles too.
     *
     * **Exactly two parameters, and that is load-bearing.** The obvious next
     * step is `merge(a, b, today)` with the window applied inside, "held to
     * the same law as `ConfigMerge`" — but `ConfigMerge` takes no `now` at
     * all, which is prohibition 2 in the sync skill, and a `today: String`
     * parameter sails straight through the clock grep at the top of
     * `scripts/check.*`: that check would report green on precisely the
     * property it exists to protect. Worse, the laws below hold only for a
     * *fixed* `today`, which is never the case across two devices whose
     * windows differ — that is the sync skill's own "a tombstone TTL: devices
     * on different clocks prune different sets and push at each other for
     * ever". Guard 44 pins this signature; [trim] is where a window belongs.
     *
     * Commutative, idempotent and associative by construction. A missing key
     * is zero, never a delete: cells are grow-only and there are no
     * tombstones, so a long-disconnected peer reintroducing a day this device
     * had trimmed costs a few bytes and is inert — readers only ever sum
     * today, and the next local write trims it again.
     */
    fun merge(a: Ledger, b: Ledger): Ledger {
        if (a.cells.isEmpty()) return b
        if (b.cells.isEmpty()) return a
        val out = LinkedHashMap<String, Map<String, Map<String, Cell>>>()
        (a.cells.keys + b.cells.keys).sorted().forEach { kid ->
            val da = a.cells[kid].orEmpty()
            val db = b.cells[kid].orEmpty()
            val days = LinkedHashMap<String, Map<String, Cell>>()
            (da.keys + db.keys).sorted().forEach { day ->
                val va = da[day].orEmpty()
                val vb = db[day].orEmpty()
                val devs = LinkedHashMap<String, Cell>()
                (va.keys + vb.keys).sorted().forEach { dev ->
                    val ca = va[dev]
                    val cb = vb[dev]
                    devs[dev] = when {
                        ca == null -> cb!!
                        cb == null -> ca
                        else -> Cell(maxOf(ca.minutes, cb.minutes), maxOf(ca.at, cb.at))
                    }
                }
                days[day] = devs
            }
            out[kid] = days
        }
        return Ledger(out)
    }

    /**
     * This device's own cell, raised to [minutes] and never lowered.
     *
     * The caller is asserting a fact about itself, so [deviceId] must be its
     * own. Nothing here checks that — it cannot — but [ownedBy] is how the
     * hub enforces it for a body arriving over the wire.
     */
    fun withOwn(
        ledger: Ledger,
        kidId: String?,
        day: String,
        deviceId: String,
        minutes: Int,
        at: Long = 0L
    ): Ledger = merge(
        ledger,
        Ledger(mapOf(kidOf(kidId) to mapOf(day to mapOf(deviceId to Cell(minutes.coerceAtLeast(0), at)))))
    )

    /**
     * The window, applied **only** to what a device stores for itself, at
     * write time — never to what it merges and never to what it serves (D2).
     *
     * Drops days more than [keepDays] behind [today] and anything more than a
     * day ahead of it. `today` is this device's own [FamilyDay]; two devices
     * with different windows simply hold different amounts of history, which
     * costs nothing because every reader sums one day.
     */
    fun trim(ledger: Ledger, today: String, keepDays: Int = KEEP_DAYS): Ledger =
        filterDays(ledger) { day ->
            val away = FamilyDay.daysAfter(day, today) ?: return@filterDays false
            away in -keepDays.toLong()..1L
        }

    /**
     * What a reader may count: everything up to one day ahead of its own
     * [today].
     *
     * A device with a clock a year fast would otherwise author cells the whole
     * household adds to a budget nobody has reached — the "pre-spend" hole.
     * One day of slack rather than none, because two devices either side of
     * midnight in different zones are both telling the truth.
     */
    fun forward(ledger: Ledger, today: String): Ledger =
        filterDays(ledger) { day ->
            val away = FamilyDay.daysAfter(day, today) ?: return@filterDays false
            away <= 1L
        }

    /** Only the cells [deviceId] authored — the hub's writer-owns-cell rule. */
    fun ownedBy(ledger: Ledger, deviceId: String): Ledger = Ledger(
        ledger.cells.mapValues { (_, days) ->
            days.mapValues { (_, devs) -> devs.filterKeys { it == deviceId } }
                .filterValues { it.isNotEmpty() }
        }.filterValues { it.isNotEmpty() }
    )

    /**
     * Minutes other devices report for [kidId] on [day].
     *
     * The `!= meId` exclusion is the one line whose absence silently halves a
     * kid's day: this device reads its own cell back through a peer and adds
     * it to its own live counter. It has its own test rather than being an
     * implementation detail.
     */
    fun othersToday(ledger: Ledger, meId: String, kidId: String?, day: String): Int =
        ledger.cells[kidOf(kidId)]?.get(day)
            ?.filterKeys { it != meId }
            ?.values?.sumOf { it.minutes }
            ?: 0

    /** How many cells [ledger] holds — the bound [MAX_CELLS] applies to. */
    fun count(ledger: Ledger): Int =
        ledger.cells.values.sumOf { days -> days.values.sumOf { it.size } }

    /**
     * Drop whole days, oldest first, until at most [max] cells remain.
     *
     * The bound a face applies when it cannot tell what day it is — the hub
     * before a family sets `homeZone`. By day string rather than by arrival,
     * so two faces given the same ledger keep the same half of it.
     */
    fun cap(ledger: Ledger, max: Int = MAX_CELLS): Ledger {
        if (count(ledger) <= max) return ledger
        val days = ledger.cells.values.flatMap { it.keys }.distinct().sortedDescending()
        val keep = LinkedHashSet<String>()
        var running = 0
        for (day in days) {
            val size = ledger.cells.values.sumOf { it[day]?.size ?: 0 }
            if (running + size > max) break
            keep += day
            running += size
        }
        return filterDays(ledger) { it in keep }
    }

    /**
     * Lenient, and bounded. A ledger that will not parse is an empty ledger:
     * the worst a garbled body can cost is a kid's shared total being low for
     * one round, which fails in the direction that gives them time rather than
     * taking it. Refuses outright past [MAX_CELLS] rather than truncating, so
     * a caller can answer 400 instead of half-merging.
     */
    fun parse(json: String?): Ledger? {
        if (json.isNullOrBlank()) return Ledger.EMPTY
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val cells = root.optJSONObject("cells") ?: return Ledger.EMPTY
        val out = LinkedHashMap<String, Map<String, Map<String, Cell>>>()
        var seen = 0
        cells.keys().forEach { kid ->
            val days = cells.optJSONObject(kid) ?: return@forEach
            val byDay = LinkedHashMap<String, Map<String, Cell>>()
            days.keys().forEach { day ->
                val devs = days.optJSONObject(day) ?: return@forEach
                val byDev = LinkedHashMap<String, Cell>()
                devs.keys().forEach { dev ->
                    val c = devs.optJSONObject(dev) ?: return@forEach
                    seen++
                    if (seen > MAX_CELLS) return null
                    byDev[dev] = Cell(c.optInt("m", 0).coerceAtLeast(0), c.optLong("at", 0L))
                }
                if (byDev.isNotEmpty()) byDay[day] = byDev
            }
            if (byDay.isNotEmpty()) out[kid] = byDay
        }
        return Ledger(out)
    }

    /**
     * Canonical: keys sorted at every level, so two faces holding the same
     * cells write the same bytes. Nothing hashes this today, and that is
     * exactly why it is worth fixing now — the first thing that compares two
     * ledgers by string would otherwise inherit `JSONObject`'s
     * platform-dependent iteration order (a `HashMap` on the JVM, a
     * `LinkedHashMap` on Android), which no test in this repo would catch.
     */
    fun toJson(ledger: Ledger): String {
        val cells = JSONObject()
        ledger.cells.keys.sorted().forEach { kid ->
            val days = JSONObject()
            ledger.cells.getValue(kid).keys.sorted().forEach { day ->
                val devs = JSONObject()
                ledger.cells.getValue(kid).getValue(day).let { row ->
                    row.keys.sorted().forEach { dev ->
                        val c = row.getValue(dev)
                        devs.put(dev, JSONObject().put("m", c.minutes).put("at", c.at))
                    }
                }
                if (devs.length() > 0) days.put(day, devs)
            }
            if (days.length() > 0) cells.put(kid, days)
        }
        return JSONObject().put("v", VERSION).put("cells", cells).toString()
    }

    private fun kidOf(kidId: String?): String = kidId?.takeIf { it.isNotBlank() } ?: NO_KID

    private fun filterDays(ledger: Ledger, keep: (String) -> Boolean): Ledger = Ledger(
        ledger.cells.mapValues { (_, days) -> days.filterKeys(keep) }
            .filterValues { it.isNotEmpty() }
    )
}
