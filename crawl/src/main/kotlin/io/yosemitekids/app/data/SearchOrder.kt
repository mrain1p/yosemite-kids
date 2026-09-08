package io.yosemitekids.app.data

/**
 * The orders a search result list can honestly be put in.
 *
 * "Honestly" is the whole design of this file. The family asked for sort and
 * filter chips on search — "most recent, relevance, etc" — and only some of
 * that is answerable from what the app actually knows. [ChannelIndex.IndexedVideo]
 * carries a title, a channel name, a thumbnail, a duration and a source id.
 * It carries **no upload date and no view count**: the crawl never stored
 * either, and `IndexedVideo.toVideo()` therefore hands back a [Video] whose
 * `publishedAt` and `viewCount` are null for every hit.
 *
 * So there is no "Most recent" chip here, and no "Popular" one. Both would
 * compile, both would draw, and both would silently return the list unchanged
 * — a control that appears to work and does nothing, which is worse than the
 * absence of the control. They need a crawl change first (a date and a count
 * per indexed video, and a re-crawl to fill them in); until that lands the
 * gap is a gap. `docs/ROADMAP.md` §2L records it.
 *
 * What is left is real: relevance ([SearchRank], the default and the thing
 * that fixed "scroll past seventy strangers"), duration, which the index does
 * store and which is exactly the question "I have ten minutes, what can I
 * watch", and a shuffle, which needs no data at all.
 *
 * Pure and in `:crawl` beside [SearchRank], for the same reason: the hub
 * answers the same question and must not grow a second opinion.
 */
object SearchOrder {

    /** SearchRank's own order — best match for this child. The default. */
    const val BEST = "best"

    /** Shortest first. Unknown durations (0) sort last rather than first. */
    const val SHORT = "short"

    /** A seeded shuffle, so the row holds still until it is re-mixed. */
    const val MIX = "mix"

    /** Chip order, left to right. */
    val ALL = listOf(BEST, SHORT, MIX)

    /**
     * [items] must already be in relevance order — this reorders what is on
     * screen and never what is queued for screening, so [BEST] is the identity.
     */
    fun <T> order(items: List<T>, order: String, seed: Long, seconds: (T) -> Long): List<T> =
        when (order) {
            SHORT -> items.sortedBy { val d = seconds(it); if (d > 0) d else Long.MAX_VALUE }
            MIX -> items.shuffled(kotlin.random.Random(seed))
            else -> items
        }
}
