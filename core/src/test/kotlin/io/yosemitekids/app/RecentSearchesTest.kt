package io.yosemitekids.app

import io.yosemitekids.app.data.RecentSearches
import org.junit.Assert.assertEquals
import org.junit.Test

/** Newest first, no repeats, eight at most, and the × forgets exactly one - on every face. */
class RecentSearchesTest {

    @Test
    fun aRepeatMovesToTheFrontRegardlessOfCase() {
        val once = RecentSearches.add(emptyList(), "dinosaurs")
        val twice = RecentSearches.add(once, "trains")
        assertEquals(listOf("trains", "dinosaurs"), twice)
        assertEquals("a repeat is one chip, at the front, spelled the new way", listOf("Dinosaurs", "trains"), RecentSearches.add(twice, "  Dinosaurs "))
    }

    @Test
    fun blankIsIgnoredAndTheListIsCapped() {
        assertEquals(listOf("a"), RecentSearches.add(listOf("a"), "   "))
        var list = emptyList<String>()
        (1..12).forEach { list = RecentSearches.add(list, "q$it") }
        assertEquals(RecentSearches.MAX, list.size)
        assertEquals("q12", list.first())
        assertEquals("q5", list.last())
    }

    @Test
    fun removeForgetsExactlyOne() {
        val list = listOf("trains", "Dinosaurs", "cats")
        assertEquals(listOf("trains", "cats"), RecentSearches.remove(list, "dinosaurs"))
        assertEquals("a term that is not there changes nothing", list, RecentSearches.remove(list, "dogs"))
    }
}
