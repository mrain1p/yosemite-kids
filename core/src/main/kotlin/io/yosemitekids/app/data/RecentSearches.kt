package io.yosemitekids.app.data

/**
 * The kid's last few searches, as a list: the rules, with no store attached.
 *
 * A child who cannot type well repeats the same handful of searches, so one
 * tap on "dinosaurs" beats spelling it every time. The phone keeps its list
 * in `SearchHistoryStore` (per kid, device-local) and the hub keeps one per
 * kid for the browser (`HubKidSearches`); both take the list they hold,
 * hand it here, and store what comes back, so "newest first, no repeats,
 * eight at most, and the × forgets exactly one" is one set of rules on
 * every face. Search history is the single thing a kid may delete on their
 * own - everything else they collect is cleared by a parent - so [remove]
 * has to be real, not a clear-all in disguise.
 */
object RecentSearches {

    /** Chips on a search page, not a history: a child reads eight, not eighty. */
    const val MAX = 8

    /** [query] moved (or added) to the front, matched without regard to case; the tail trimmed to [MAX]. */
    fun add(recent: List<String>, query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return recent
        return (listOf(q) + recent.filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
    }

    /** [query] gone, however it was capitalised; the rest untouched. */
    fun remove(recent: List<String>, query: String): List<String> {
        val q = query.trim()
        return recent.filterNot { it.equals(q, ignoreCase = true) }
    }
}
