package io.yosemitekids.app

import io.yosemitekids.app.data.Video
import io.yosemitekids.app.ui.VideoItem
import io.yosemitekids.app.ui.suggestionsFor
import io.yosemitekids.app.ui.titleKeywords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "More like what you watch" (see HomeState.suggestionsFor). The interesting
 * cases are the small ones — a kid with nothing watched, a kid whose whole
 * history is one channel — so they live here rather than in a guess.
 */
class SuggestionsTest {

    private fun video(title: String, channel: String = "Ch", id: String = title) =
        Video(
            url = "https://youtu.be/$id",
            title = title,
            channelName = channel,
            thumbnailUrl = null,
            durationSeconds = 600
        )

    private fun item(title: String, channel: String = "Ch") = VideoItem(video(title, channel), null)

    @Test
    fun keywordsDropStopwordsShortWordsAndPunctuation() {
        assertEquals(
            setOf("dinosaur", "song", "tyrannosaurus"),
            titleKeywords("The Dinosaur Song! for kids - Tyrannosaurus")
        )
    }

    @Test
    fun keywordsCountEachWordOnce() {
        assertEquals(setOf("shark", "baby"), titleKeywords("Shark shark SHARK baby"))
    }

    @Test
    fun nothingWatchedMeansNoSuggestions() {
        assertTrue(
            suggestionsFor(
                watchedTitles = emptyList(),
                candidates = listOf(item("Dinosaur facts")),
                channelAffinity = emptyMap(),
                limit = 10
            ).isEmpty()
        )
    }

    @Test
    fun noCandidatesMeansNoSuggestions() {
        assertTrue(
            suggestionsFor(
                watchedTitles = listOf("Dinosaur song"),
                candidates = emptyList(),
                channelAffinity = emptyMap(),
                limit = 10
            ).isEmpty()
        )
    }

    @Test
    fun unrelatedTitlesAreNotSuggested() {
        val out = suggestionsFor(
            watchedTitles = listOf("Dinosaur song"),
            candidates = listOf(item("Knitting a scarf"), item("Tax return basics")),
            channelAffinity = emptyMap(),
            limit = 10
        )
        assertTrue("nothing overlaps, so nothing should surface", out.isEmpty())
    }

    @Test
    fun overlappingTitlesRankAboveWeakerOnes() {
        val out = suggestionsFor(
            watchedTitles = listOf("Dinosaur song for toddlers"),
            candidates = listOf(
                item("Counting to ten"),
                item("Dinosaur song number two", channel = "B"),
                item("Toddlers at the zoo", channel = "C")
            ),
            channelAffinity = emptyMap(),
            limit = 10
        )
        assertEquals("Dinosaur song number two", out.first().video.title)
        assertEquals(2, out.size)
        assertFalse(out.any { it.video.title == "Counting to ten" })
    }

    @Test
    fun theMostRecentWatchWeighsMost() {
        // Both candidates match exactly one watch; the one matching the newest
        // watch (index 0) has to win.
        val out = suggestionsFor(
            watchedTitles = listOf("Volcano eruption", "Penguin colony"),
            candidates = listOf(item("Penguin chicks", "B"), item("Volcano lava flow", "C")),
            channelAffinity = emptyMap(),
            limit = 10
        )
        assertEquals("Volcano lava flow", out.first().video.title)
    }

    @Test
    fun oneChannelCannotOwnTheRow() {
        val hoggy = (1..6).map { item("Dinosaur episode $it", channel = "Hoggy") }
        val other = listOf(item("Dinosaur friends", channel = "Other"))
        val out = suggestionsFor(
            watchedTitles = listOf("Dinosaur time"),
            candidates = hoggy + other,
            channelAffinity = emptyMap(),
            limit = 10,
            perChannelCap = 2
        )
        assertEquals(2, out.count { it.video.channelName == "Hoggy" })
        assertTrue(out.any { it.video.channelName == "Other" })
    }

    @Test
    fun affinityBreaksTiesButNeverBeatsARealMatch() {
        val out = suggestionsFor(
            watchedTitles = listOf("Rocket launch"),
            candidates = listOf(
                // One shared word, from a channel they never watch.
                item("Rocket engines explained", channel = "Cold"),
                // No shared word at all, from their favourite channel.
                item("Baking bread", channel = "Warm")
            ),
            channelAffinity = mapOf("Warm" to 50, "Cold" to 0),
            limit = 10
        )
        assertEquals(listOf("Rocket engines explained"), out.map { it.video.title })
    }

    @Test
    fun limitIsRespected() {
        val out = suggestionsFor(
            watchedTitles = listOf("Train journey"),
            candidates = (1..20).map { item("Train number $it", channel = "Ch$it") },
            channelAffinity = emptyMap(),
            limit = 5
        )
        assertEquals(5, out.size)
    }

    @Test
    fun titlesWithOnlyStopwordsAreIgnoredOnBothSides() {
        val out = suggestionsFor(
            watchedTitles = listOf("The Full Episode"),
            candidates = listOf(item("For Kids - Full Video")),
            channelAffinity = emptyMap(),
            limit = 10
        )
        assertTrue("all-stopword titles carry no signal", out.isEmpty())
    }
}
