package io.yosemitekids.app

import io.yosemitekids.app.data.SearchRank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering rules, stated as the outcomes a parent would describe rather
 * than as the weights that produce them — so the weights can be retuned
 * without rewriting the tests, and so a retune that breaks an outcome fails.
 */
class SearchRankTest {

    private fun s(
        title: String,
        channel: String = "Some Channel",
        url: String = "u:$title",
        query: String = "mario",
        signals: SearchRank.Signals = SearchRank.Signals()
    ) = SearchRank.score(title, channel, url, SearchRank.terms(query), query, signals)

    @Test fun aTitleHitBeatsAChannelNameOnlyHit() {
        val titleHit = s("Mario Kart tricks", channel = "Some Channel")
        val channelOnly = s("Tricks and tips", channel = "Mario Channel")
        assertTrue("a video called Mario must beat one merely from a Mario channel",
            titleHit > channelOnly)
    }

    @Test fun theWordsTogetherBeatTheWordsApart() {
        val together = s("Mario Kart 8 races", query = "mario kart")
        val apart = s("Mario explains why a kart is fast", query = "mario kart")
        assertTrue("a phrase match must outrank scattered terms", together > apart)
    }

    /**
     * The reported bug, as a test: a child searching for something she loves
     * should not find it below seventy strangers. Hearted and previously
     * watched both count, because "the ones she likes" means both.
     */
    @Test fun theOnesSheLikesComeFirst() {
        val hers = SearchRank.Signals(
            favourites = setOf("u:Mario level 3"),
            watched = setOf("u:Mario level 1")
        )
        val hearted = s("Mario level 3", url = "u:Mario level 3", signals = hers)
        val seenBefore = s("Mario level 1", url = "u:Mario level 1", signals = hers)
        val stranger = s("Mario level 9", url = "u:Mario level 9", signals = hers)
        assertTrue("hearted beats seen-before", hearted > seenBefore)
        assertTrue("seen-before beats a stranger", seenBefore > stranger)
    }

    @Test fun theChannelSheLivesInLiftsItsVideos() {
        val hers = SearchRank.Signals(channelAffinity = mapOf("Her Channel" to 500))
        val fromHers = s("Mario something", channel = "Her Channel", signals = hers)
        val fromElsewhere = s("Mario something", channel = "Other", signals = hers)
        assertTrue(fromHers > fromElsewhere)
    }

    /**
     * The cap earns its place here: without it, a channel the kid has watched
     * for hours would bury a perfect title match from anywhere else, and search
     * would stop being search.
     */
    @Test fun affinityCannotBuryABetterTitleMatch() {
        val hers = SearchRank.Signals(channelAffinity = mapOf("Beloved" to 10_000_000))
        val weakTitleBelovedChannel = s("A video about a plumber", channel = "Beloved", signals = hers)
        val strongTitleAnyChannel = s("Mario Kart", channel = "Nobody", query = "mario kart", signals = hers)
        assertTrue("a real title match must survive any amount of affinity",
            strongTitleAnyChannel > weakTitleBelovedChannel)
    }

    @Test fun scoresAreNeverNegative() {
        assertTrue(s("nothing relevant here", channel = "Nor here") >= 0)
    }

    @Test fun anEmptyQueryRanksNothing() {
        assertEquals(0, SearchRank.score("Mario", "Mario", "u", emptyList(), "", SearchRank.Signals()))
    }

    /** Equal scores must not reshuffle: the index's own order is newest-first. */
    @Test fun rankingIsStableForEqualScores() {
        val items = listOf("Mario A", "Mario B", "Mario C")
        val ranked = SearchRank.rank(items, SearchRank.terms("mario"), "mario", SearchRank.Signals()) {
            SearchRank.Key(it, "C", "u:$it")
        }
        assertEquals(items, ranked)
    }

    @Test fun termsSplitsOnceAndLowercases() {
        assertEquals(listOf("mario", "kart"), SearchRank.terms("  Mario   KART "))
        assertEquals(emptyList<String>(), SearchRank.terms("   "))
    }
}
