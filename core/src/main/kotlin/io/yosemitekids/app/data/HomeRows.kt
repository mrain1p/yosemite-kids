package io.yosemitekids.app.data

import io.yosemitekids.app.ui.HOME_SHELVES
import io.yosemitekids.app.ui.HomeSection
import io.yosemitekids.app.ui.homeSections

/**
 * One row of a kid's home screen, as the parent arranged it: which shelf,
 * on whose home, at what position, drawn or not.
 *
 * The shelves themselves — what each one is, and the order a fresh install
 * gets — are `HOME_SHELVES` in `:core`'s `ui` package, and every face draws
 * from that catalogue. This is the *saved* arrangement, and it lives in the
 * config because it is the parent's curation and has to reach every device
 * the way a pinned card does. Its shape is the pinned hero's shape for the
 * pinned hero's reasons ([Pin]): one merge unit per row,
 * `home.row|<kid>|<shelf>`, absent when in doubt, the position a value of
 * its own ([rank]) so a reorder is an edit to exactly the rows that moved
 * and two parents arranging different rows both land.
 *
 * An unknown [id] — a shelf this build has not got — is carried and stamped
 * and never drawn: `homeSections` drops it at render time and appends what
 * the saved order has never heard of, which is what lets a family upgrade
 * one device at a time without the home screen losing a row.
 *
 * Every add, move, toggle and reset on either face goes through
 * [HomeRows.withOrder], which is the only place a rank is minted (guard 70).
 */
data class HomeRow(
    /** The kid whose home this is; null is the family's own home, as for [Pin.kidId]. */
    val kidId: String?,
    /** A `HomeShelf` id. */
    val id: String,
    /** Position, lowest first; ties break on the unit key. Spaced by [HomeRows.RANK_STEP]. */
    val rank: Int,
    /** Off is a row the parent removed from this home. Kept rather than deleted so its position survives being switched back on. */
    val enabled: Boolean = true
)

/**
 * The pure half of the home-row editor: the order, the reconcile against the
 * catalogue, and the one function that rewrites a home.
 */
object HomeRows {

    /** Gap between the ranks the editor mints. The parser accepts any integer. */
    const val RANK_STEP = 100

    /** Canonical order: by rank, then by unit key — the order the wire and the merge both write. */
    fun ordered(rows: List<HomeRow>): List<HomeRow> =
        rows.sortedWith(compareBy({ it.rank }, { ConfigStamp.row(it) }))

    /** One home's rows, in the order they are drawn. */
    fun rowsOf(rows: List<HomeRow>, kidId: String?): List<HomeRow> =
        ordered(rows.filter { it.kidId == kidId })

    /**
     * What a face draws for [kidId]: the saved rows reconciled against the
     * catalogue by `homeSections` — unknown shelves dropped, new ones
     * appended and switched on. An empty saved list is the default layout.
     */
    fun sections(rows: List<HomeRow>, kidId: String?, catalogue: List<String> = HOME_SHELVES): List<HomeSection> =
        homeSections(rowsOf(rows, kidId).map { HomeSection(it.id, it.enabled) }, catalogue)

    /**
     * [all] with [kidId]'s home rewritten to exactly [order]: the shelves in
     * the order the parent left them, each on or off.
     *
     * One function for a move, a toggle and a reset, because all three are
     * a new order for one home. Every other kid's rows pass through
     * untouched, ranks included, so arranging Leo's home cannot stamp a row
     * of Mia's.
     *
     * Like `Pins.withRow` it mints as few ranks as it can: rows already in
     * the requested order keep their ranks (the longest such run), and only
     * the rest are re-minted into the gaps. A toggle alone changes no rank.
     * Ids outside [catalogue] are dropped — a face cannot save a shelf this
     * build cannot draw — and an [order] equal to the catalogue default with
     * everything on is stored as **no rows at all**, so "Reset to default"
     * leaves the document exactly as a family that never touched the editor
     * has it (and hashes alike, which `HomeRowsConfigTest` pins).
     */
    fun withOrder(
        all: List<HomeRow>,
        kidId: String?,
        order: List<HomeSection>,
        catalogue: List<String> = HOME_SHELVES
    ): List<HomeRow> {
        val others = all.filterNot { it.kidId == kidId }
        val known = catalogue.toSet()
        val want = order.filter { it.id in known }.distinctBy { it.id }
        val isDefault = want.map { it.id } == catalogue && want.all { it.enabled }
        if (want.isEmpty() || isDefault) return others

        val was = rowsOf(all, kidId).associateBy { it.id }
        val stored = want.map { was[it.id]?.rank }
        // The longest run of stored ranks that already increase in this order
        // keeps them; O(n²) over a handful of rows.
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
        val keep = BooleanArray(want.size)
        var best = -1
        for (i in want.indices) if (run[i] > 0 && (best < 0 || run[i] > run[best])) best = i
        var at = best
        while (at >= 0) { keep[at] = true; at = from[at] }

        // Mint the rest into the gaps between kept neighbours, spaced by
        // RANK_STEP where there is room and halved where there is not.
        val ranks = IntArray(want.size)
        for (i in want.indices) if (keep[i]) ranks[i] = stored[i]!!
        var i = 0
        while (i < want.size) {
            if (keep[i]) { i++; continue }
            var end = i
            while (end < want.size && !keep[end]) end++
            val lower = if (i == 0) null else ranks[i - 1]
            val upper = if (end == want.size) null else ranks[end]
            val count = end - i
            when {
                lower == null && upper == null -> for (k in 0 until count) ranks[i + k] = (k + 1) * RANK_STEP
                lower == null -> for (k in 0 until count) ranks[i + k] = upper!! - (count - k) * RANK_STEP
                upper == null -> for (k in 0 until count) ranks[i + k] = lower + (k + 1) * RANK_STEP
                else -> {
                    val step = (upper - lower) / (count + 1)
                    if (step >= 1) for (k in 0 until count) ranks[i + k] = lower + (k + 1) * step
                    else {
                        // No room: renumber from here on, spacing everything after.
                        for (k in 0 until count) ranks[i + k] = lower + (k + 1) * RANK_STEP
                        var next = ranks[end - 1] + RANK_STEP
                        for (m in end until want.size) { ranks[m] = next; keep[m] = false; next += RANK_STEP }
                    }
                }
            }
            i = end
        }
        return others + want.mapIndexed { idx, s -> HomeRow(kidId, s.id, ranks[idx], s.enabled) }
    }
}
