package io.yosemitekids.app

import io.yosemitekids.app.ui.interleave
import org.junit.Assert.assertEquals
import org.junit.Test

/** The home feed's mix rule (see HomeState.interleave). */
class FeedInterleaveTest {

    @Test
    fun oneFromEachChannelInTurn() {
        val a = listOf("a1", "a2", "a3")
        val b = listOf("b1", "b2")
        val c = listOf("c1")
        assertEquals(
            listOf("a1", "b1", "c1", "a2", "b2", "a3"),
            interleave(listOf(a, b, c), max = 100) { it }
        )
    }

    @Test
    fun capsAtMax() {
        val lists = listOf(listOf("a1", "a2", "a3"), listOf("b1", "b2", "b3"))
        assertEquals(listOf("a1", "b1", "a2"), interleave(lists, max = 3) { it })
    }

    @Test
    fun dedupesByKeyAcrossLists() {
        // The same video indexed under two sources appears once.
        val lists = listOf(listOf("x", "a2"), listOf("x", "b2"))
        assertEquals(listOf("x", "a2", "b2"), interleave(lists, max = 10) { it })
    }

    @Test
    fun emptyInputs() {
        assertEquals(emptyList<String>(), interleave<String>(emptyList(), 10) { it })
        assertEquals(emptyList<String>(), interleave<String>(listOf(emptyList()), 10) { it })
    }
}
