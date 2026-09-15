package io.yosemitekids.app.data

/**
 * The orders a search result list can honestly be put in.
 *
 * "Honestly" is the whole design of this file. The family asked for sort and
 * filter chips on search — "most recent, relevance, etc" — and a chip is only
 * offered when the index can answer it. For a long while that ruled two out:
 * [ChannelIndex.IndexedVideo] carried no upload date and no view count, so a
 * "Most recent" chip would have compiled, drawn, and silently returned the
 * list unchanged — a control that appears to work and does nothing, which is
 * worse than the absence of the control.
 *
 * The index keeps the date now (1.9.0, roadmap §2M — it was always computed
 * by the crawl and dropped on the way to disk), so [RECENT] is real. A row an
 * older build wrote has no date until its source is crawled again, and such
 * a row sorts **last** under "Newest" rather than being hidden: not knowing
 * when a video came out is no reason to keep it from a child. The chip that
 * is still missing is anything popularity-shaped; the count is in the index
 * for the channel page's "Popular first", but a search ranked by views is a
 * popularity contest, and that is the part of YouTube this app leaves out.
 *
 * What is left is real: relevance ([SearchRank], the default and the thing
 * that fixed "scroll past seventy strangers"), newest, duration, which is
 * exactly the question "I have ten minutes, what can I watch", and a
 * shuffle, which needs no data at all.
 *
 * Pure and in `:crawl` beside [SearchRank], for the same reason: the hub
 * answers the same question and must not grow a second opinion.
 */
object SearchOrder {

    /** SearchRank's own order — best match for this child. The default. */
    const val BEST = "best"

    /** Newest upload first. A row with no date yet sorts last, in relevance order. */
    const val RECENT = "recent"

    /** Shortest first. Unknown durations (0) sort last rather than first. */
    const val SHORT = "short"

    /** A seeded shuffle, so the row holds still until it is re-mixed. */
    const val MIX = "mix"

    /** Chip order, left to right. */
    val ALL = listOf(BEST, RECENT, SHORT, MIX)

    /**
     * The chip's words, one spelling for the phone, the television and the
     * hub's reply to the browser. An order nobody has heard of gets its id
     * back rather than a blank chip.
     */
    fun label(order: String): String = when (order) {
        BEST -> "Best match"
        RECENT -> "Newest"
        SHORT -> "Shortest"
        MIX -> "Mix it up"
        else -> order
    }

    /**
     * [items] must already be in relevance order — this reorders what is on
     * screen and never what is queued for screening, so [BEST] is the identity.
     * Both sorts are stable, so ties (and every undated row) keep that order.
     */
    fun <T> order(
        items: List<T>,
        order: String,
        seed: Long,
        // Before `seconds`, so a caller's trailing lambda is still the duration
        // and a caller with no dates need not say so.
        publishedAt: (T) -> Long? = { null },
        seconds: (T) -> Long
    ): List<T> =
        when (order) {
            RECENT -> items.sortedByDescending { publishedAt(it) ?: Long.MIN_VALUE }
            SHORT -> items.sortedBy { val d = seconds(it); if (d > 0) d else Long.MAX_VALUE }
            MIX -> items.shuffled(kotlin.random.Random(seed))
            else -> items
        }
}
