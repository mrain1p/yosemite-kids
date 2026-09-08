package io.yosemitekids.app.data

/**
 * One card of the home screen's pinned hero: a source, on one kid's home, at
 * a position in the row.
 *
 * In the config rather than in a device's prefs because it is the parent's
 * curation and has to reach every device the way a channel does. Its merge
 * unit is `home.pin|<kid>|<source>` — one per card, absent when in doubt
 * like a channel — because the row is the worst-converging shape in the
 * whole document: two or three items, ordered, and edited by two parents.
 * One ordered-list value would resolve by the later stamp and quietly throw
 * away the other parent's evening, with nothing to put it back
 * (`SyncNotices.RESTORABLE` is limits only). Array position as the order
 * would be rewritten by the merge, which re-sorts every array it touches by
 * stamp — so the order is a value of its own, [rank], and a reorder is an
 * edit to exactly the cards that moved. What that costs: two parents moving
 * the *same* card resolve by whose stamp is later, per card, silently. See
 * `PinsConfigTest`.
 *
 * The two-or-three cap is the editor's rule and the renderer's
 * ([Pins.MAX]), never the parser's: a document from a newer build that
 * allows four must round-trip through this one intact.
 *
 * The editor landed in the release after the container. Every add, move and
 * remove on either face goes through [Pins.withRow], which is the only place
 * a rank is minted — see there for why that is one function and not two.
 */
data class Pin(
    /**
     * The kid whose home the card is on; null is the family's own home —
     * the row a household with no profiles sees, resolved by
     * [Whitelist.pinsFor] exactly as [Whitelist.limitsFor] resolves the
     * family's rules. Absent on the wire, as a grant's `kid` is for everyone.
     */
    val kidId: String?,
    /** A [WhitelistEntry.id], channel or playlist. A card outlives neither its source nor its kid. */
    val sourceId: String,
    /**
     * Position in the row, lowest first; ties break on the unit key so every
     * device draws one order. The editor spaces new ranks by [Pins.RANK_STEP]
     * so an insert between two cards needs no renumbering of the rest —
     * which would touch units nobody meant to edit.
     */
    val rank: Int
)

/**
 * The pure half of the pinned hero: the order, the cap, what a face may
 * offer, and the one function that rewrites a row.
 *
 * All of it lives here — not on the phone, not in the hub's browser —
 * because two faces edit this row and the arithmetic is the part that goes
 * wrong quietly. A second implementation would not fail; it would mint
 * ranks the other face never spaces, and the symptom is a home screen whose
 * order differs between the television and the NAS. Guard 42 holds both
 * faces to this file.
 */
object Pins {

    /** Gap between the ranks the editor mints. The parser accepts any integer. */
    const val RANK_STEP = 100

    /**
     * How many cards one row may hold. The design draws two or three.
     *
     * The editor's ceiling and the renderer's (`HOME_PINS_MAX` is this), and
     * deliberately not the parser's: `ConfigJson.pinsFromJson` counts
     * nothing, so a document from a newer build that allows four round-trips
     * through this one intact. [withRow] enforces it the same way — it
     * refuses to *grow* a row past this, and never truncates one that
     * arrived longer.
     */
    const val MAX = 3

    /**
     * Canonical order: by rank, then by unit key. `ConfigJson.toJson` writes
     * the array in this order and the merge rebuilds it in this order, so two
     * devices holding the same cards write the same bytes — and never by the
     * position a card arrived in, which the merge does not preserve.
     */
    fun ordered(pins: List<Pin>): List<Pin> =
        pins.sortedWith(compareBy({ it.rank }, { ConfigStamp.pin(it) }))

    /**
     * One row, in the order it is drawn. [Whitelist.pinsFor] resolves which
     * row a *viewer* gets; this takes the row by its key, which is what an
     * editor picking a kid needs.
     */
    fun rowOf(pins: List<Pin>, kidId: String?): List<Pin> =
        ordered(pins.filter { it.kidId == kidId })

    /** Whether an editor may still offer to pin something on this row. */
    fun atCap(row: List<Pin>): Boolean = row.size >= MAX

    /**
     * The sources an editor may offer for [kidId]'s row.
     *
     * The same `visibleTo` predicate the home itself applies, and that is the
     * whole point of it being here: `resolvePins` fails closed at render
     * time, so a card naming a channel restricted to an older sibling simply
     * does not draw — which reads to a parent as a pin that did not stick.
     * Refusing to create it in the first place is the honest half of the same
     * rule. [withRow] applies this filter itself, so a face that forgets it
     * still cannot store one.
     *
     * A null [kidId] is the family's own row, and `visibleTo(null)` is true
     * for everything — which is right rather than lax: a viewer with no kid
     * picked sees every source on the home screen too, so this offers exactly
     * what that home would draw.
     */
    fun candidates(sources: List<WhitelistEntry>, kidId: String?): List<WhitelistEntry> =
        sources.filter { it.visibleTo(kidId) }

    /**
     * [all] with [kidId]'s row rewritten to exactly [order] — the source ids
     * a parent has just left in the order they left them.
     *
     * One function for add, move and remove, because all three are the same
     * edit: a new membership and a new order for one row. Every other kid's
     * cards pass through untouched, ranks included, so editing Leo's hero
     * cannot stamp a single unit of Mia's.
     *
     * **It mints as few ranks as it can**, which is the reason [Pin.rank] is
     * a value rather than an array position. The cards whose ranks are
     * already in the requested order keep them — the longest such run, found
     * by an ordinary longest-increasing-subsequence walk — and only the rest
     * are re-minted, into the gap [RANK_STEP] left for them. So moving the
     * third card to the front touches one unit and stamps one unit; a
     * whole-row renumber would stamp all three and hand the merge two
     * parents' edits to arbitrate where there was one.
     *
     * Two rules it enforces so that no face has to be trusted with them:
     *
     * - **The cap.** A row may not grow past [MAX]. It may stay longer than
     *   [MAX] if it arrived that way, because a document from a newer build
     *   that allows four must not lose its fourth card to an older build's
     *   reorder.
     * - **Fail closed.** An id [candidates] would not offer is dropped, so
     *   restricting a channel to one sibling cannot leave the other's home
     *   screen holding a card that will never draw.
     *
     * Ranks are read from [all] and never from the caller, which is what lets
     * the hub take a browser's array positions as the parent's order and mint
     * the ranks here (`HubWeb.applyPatch`).
     */
    fun withRow(
        all: List<Pin>,
        kidId: String?,
        order: List<String>,
        sources: List<WhitelistEntry>
    ): List<Pin> {
        val others = all.filterNot { it.kidId == kidId }
        val was = rowOf(all, kidId).associateBy { it.sourceId }
        val offerable = candidates(sources, kidId).mapTo(HashSet()) { it.id }
        // Grow to MAX; stay at whatever a newer build already wrote.
        val ceiling = maxOf(MAX, was.size)
        val want = order.filter { it in offerable }.distinct().take(ceiling)
        if (want.isEmpty()) return others

        // Which of the wanted cards may keep the rank it already has: the
        // longest run whose stored ranks already increase in this order.
        // O(n²) over a row of three.
        val stored = want.map { was[it]?.rank }
        val run = IntArray(want.size) { if (stored[it] == null) 0 else 1 }
        val from = IntArray(want.size) { -1 }
        for (i in want.indices) {
            val here = stored[i] ?: continue
            for (j in 0 until i) {
                val there = stored[j] ?: continue
                if (there < here && run[j] + 1 > run[i]) {
                    run[i] = run[j] + 1
                    from[i] = j
                }
            }
        }
        val keep = HashSet<Int>()
        var tail = -1
        for (i in want.indices) if (run[i] > 0 && (tail < 0 || run[i] > run[tail])) tail = i
        while (tail >= 0) { keep += tail; tail = from[tail] }

        val ranks = arrayOfNulls<Int>(want.size)
        keep.forEach { ranks[it] = stored[it] }

        // Mint the rest into the gap each one lands in. A gap too narrow to
        // hold one — three moves into the same 100 will do it eventually —
        // renumbers the row rather than writing a rank that would re-sort it.
        var renumber = false
        for (i in want.indices) {
            if (ranks[i] != null) continue
            val lo = if (i > 0) ranks[i - 1] else null
            val hi = (i + 1 until want.size).firstNotNullOfOrNull { ranks[it] }
            val minted = when {
                hi == null -> (lo ?: 0) + RANK_STEP
                lo == null -> if (hi > RANK_STEP) hi - RANK_STEP else hi - 1
                else -> lo + (hi - lo) / 2
            }
            // Also catches the overflow every arm above can reach on ranks a
            // newer build wrote at the ends of Int.
            if ((lo != null && minted <= lo) || (hi != null && minted >= hi)) {
                renumber = true
                break
            }
            ranks[i] = minted
        }

        return others + want.mapIndexed { i, id ->
            Pin(kidId, id, if (renumber) (i + 1) * RANK_STEP else ranks[i]!!)
        }
    }
}
