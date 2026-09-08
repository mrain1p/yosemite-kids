package io.yosemitekids.app.data

/**
 * How good a search hit is, for a particular child.
 *
 * The index match itself ([ChannelIndex.search]) is a bare "every term appears
 * somewhere in the title or the channel name". That is the right *filter* and a
 * useless *order*: it returns the family's whole back catalogue in whatever
 * order the sources happened to iterate, so a child searching for something
 * they love scrolls past seventy strangers to reach it. The reported case was
 * exactly that — "she loves Mario videos and I have to scroll past 70 to find
 * the ones she likes".
 *
 * So the order is scored, and the score is about *this kid*: what they have
 * hearted, what they have watched, and which channels they actually spend their
 * time in. A six-year-old's search is nearly always a search for something they
 * already know.
 *
 * Pure, and in `:crawl` rather than in the app, because the hub answers the
 * same question for the same children and a second implementation would drift
 * the day it was written. Nothing here reads a clock or a file.
 */
object SearchRank {

    /**
     * What this kid's history says, reduced to the three things ranking needs.
     * Sets and maps of ids, so the caller decides where they come from and this
     * stays testable without a device.
     */
    data class Signals(
        /** Channel name to a monotone "how much this kid watches it" score. */
        val channelAffinity: Map<String, Int> = emptyMap(),
        /** Video urls the kid hearted. */
        val favourites: Set<String> = emptySet(),
        /** Video urls the kid has watched at all, finished or not. */
        val watched: Set<String> = emptySet()
    )

    // The weights are ordered by how strong a statement each signal makes
    // about intent, and spaced so a lower band cannot out-total a higher one
    // by accumulating. A phrase match is a near-certainty; an affinity score
    // is a hint.
    private const val TITLE_PHRASE = 6000
    private const val TITLE_ALL_TERMS = 3000
    private const val TITLE_PREFIX = 1200
    private const val FAVOURITE = 900
    private const val WATCHED = 450
    private const val AFFINITY_CAP = 400

    /**
     * Higher is better. Never negative, so a caller can sort descending and
     * trust that an unranked hit still sorts above nothing.
     *
     * [terms] must already be lowercased and non-empty — the caller has split
     * the query once for every candidate rather than once per candidate.
     */
    fun score(
        title: String,
        channelName: String,
        url: String,
        terms: List<String>,
        query: String,
        signals: Signals = Signals()
    ): Int {
        if (terms.isEmpty()) return 0
        val t = title.lowercase()
        var score = 0

        // The strongest thing a hit can say: the words appear together, in the
        // order they were typed. "mario kart" should not be beaten by a title
        // that happens to contain "mario" and, forty words later, "kart".
        val phrase = query.trim().lowercase()
        if (phrase.isNotEmpty() && phrase in t) score += TITLE_PHRASE

        // The rule the app already had, kept and made explicit: a title hit
        // outranks a hit that only matched the channel's name. Without this a
        // search for a channel's name buries the video actually called that.
        if (terms.all { it in t }) score += TITLE_ALL_TERMS

        // A child typing three letters usually means "the thing that starts
        // like this", which is a different intent from "contains".
        if (terms.any { t.startsWith(it) }) score += TITLE_PREFIX

        // Theirs. Hearted beats merely seen, because hearting was a choice and
        // watching can be an accident of autoplay.
        if (url in signals.favourites) score += FAVOURITE
        if (url in signals.watched) score += WATCHED

        // And the channel they live in. Capped: a favourite channel should lift
        // its videos, not bury a perfect title match from anywhere else.
        val affinity = signals.channelAffinity[channelName] ?: 0
        if (affinity > 0) score += minOf(affinity, AFFINITY_CAP)

        return score
    }

    /**
     * The whole ordering in one call, so no caller re-derives the tie-break.
     *
     * Stable by construction: equal scores keep the order the index produced,
     * which is newest-first within a source. [key] pulls the four fields off
     * whatever the caller is holding, so this works for an indexed row and for
     * a resolved video without either type leaking in here.
     */
    fun <T> rank(items: List<T>, terms: List<String>, query: String, signals: Signals, key: (T) -> Key): List<T> =
        items.sortedByDescending { key(it).let { k -> score(k.title, k.channelName, k.url, terms, query, signals) } }

    /** The four fields ranking needs, however the caller stores them. */
    data class Key(val title: String, val channelName: String, val url: String)

    /** One place to split a query, so the caller cannot split it differently. */
    fun terms(query: String): List<String> =
        query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
