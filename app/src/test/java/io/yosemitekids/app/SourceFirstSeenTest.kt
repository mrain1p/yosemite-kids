package io.yosemitekids.app

import io.yosemitekids.app.data.SourceFirstSeen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "what is new to this device" record behind the Just added chip. */
class SourceFirstSeenTest {

    @Test
    fun theFirstFoldSeedsAndAnnouncesNothing() {
        val (next, fresh) = SourceFirstSeen.fold(emptyMap(), listOf("a", "b"), seeded = false, now = 99L)
        assertTrue("an upgrade must not call the whole whitelist new", fresh.isEmpty())
        assertEquals(mapOf("a" to 0L, "b" to 0L), next)
    }

    @Test
    fun aLaterArrivalIsStampedAndReported() {
        val (next, fresh) = SourceFirstSeen.fold(
            mapOf("a" to 0L, "b" to 0L), listOf("a", "b", "c"), seeded = true, now = 500L
        )
        assertEquals(setOf("c"), fresh)
        assertEquals(mapOf("a" to 0L, "b" to 0L, "c" to 500L), next)
    }

    @Test
    fun aKnownIdKeepsItsFirstStamp() {
        val (next, fresh) = SourceFirstSeen.fold(
            mapOf("c" to 500L), listOf("c"), seeded = true, now = 900L
        )
        assertTrue(fresh.isEmpty())
        assertEquals(mapOf("c" to 500L), next)
    }

    @Test
    fun aRemovedSourceIsForgottenAndCountsAsNewIfItComesBack() {
        val (afterRemoval, _) = SourceFirstSeen.fold(
            mapOf("a" to 0L, "c" to 500L), listOf("a"), seeded = true, now = 900L
        )
        assertEquals(mapOf("a" to 0L), afterRemoval)
        val (next, fresh) = SourceFirstSeen.fold(afterRemoval, listOf("a", "c"), seeded = true, now = 1_200L)
        assertEquals(setOf("c"), fresh)
        assertEquals(1_200L, next["c"])
    }
}
