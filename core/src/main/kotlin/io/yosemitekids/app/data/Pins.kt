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
 * (`HOME_PINS_MAX`), never the parser's: a document from a newer build that
 * allows four must round-trip through this one intact.
 *
 * Nothing sets this yet. This build carries, stamps, merges and backs the
 * list up; the editor is the next release, so that every install on this
 * one holds an empty list and no existing config's hash moves — the
 * fingerprint tail appears only once a card is pinned (`ConfigJson`).
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

/** The pure half of the pinned hero. */
object Pins {

    /** Gap between the ranks the editor mints. The parser accepts any integer. */
    const val RANK_STEP = 100

    /**
     * Canonical order: by rank, then by unit key. `ConfigJson.toJson` writes
     * the array in this order and the merge rebuilds it in this order, so two
     * devices holding the same cards write the same bytes — and never by the
     * position a card arrived in, which the merge does not preserve.
     */
    fun ordered(pins: List<Pin>): List<Pin> =
        pins.sortedWith(compareBy({ it.rank }, { ConfigStamp.pin(it) }))
}
