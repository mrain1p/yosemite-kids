package io.pickwick.app

import io.pickwick.app.data.ContentWarm
import io.pickwick.app.data.Source
import io.pickwick.app.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which channels a background run refreshes.
 *
 * The order is the whole design: a run is capped so a family with fifty
 * channels never looks like a scraper, which means the cap has to be spent on
 * the channels that need it most, and successive runs have to cover everything
 * without a counter that could drift out of step with the channel list.
 */
class ContentWarmTest {

    private fun source(id: String) =
        Source(id, "https://youtube.com/$id", id, null, SourceKind.CHANNEL, 100)

    private fun ages(vararg pairs: Pair<String, Long>): (Source) -> Long {
        val map = pairs.toMap()
        return { s -> map[s.id] ?: 0L }
    }

    @Test
    fun theStalestChannelsGoFirst() {
        val all = listOf(source("a"), source("b"), source("c"))
        val picked = ContentWarm.stalest(
            all, ages("a" to 1_000L, "b" to 90_000L, "c" to 40_000L), limit = 2
        )
        assertEquals(listOf("b", "c"), picked.map { it.id })
    }

    @Test
    fun aChannelWithNothingCachedIsFetchedBeforeAnyRefresh() {
        // The caller passes Long.MAX_VALUE for a channel with no cache file. A
        // just-added channel is the one a kid is most likely to open and the
        // one guaranteed to show nothing, so it must outrank every stale
        // refresh rather than waiting its turn.
        val all = listOf(source("old"), source("brandNew"))
        val picked = ContentWarm.stalest(
            all, ages("old" to 999_999L, "brandNew" to Long.MAX_VALUE), limit = 1
        )
        assertEquals(listOf("brandNew"), picked.map { it.id })
    }

    @Test
    fun aChannelThatFailedIsRetriedFirstNextRun() {
        // A failed fetch writes nothing, so that channel's cache keeps ageing
        // while everything else is refreshed to zero. Ordering by age is what
        // makes the retry automatic — there is no failure list to maintain.
        val all = listOf(source("ok1"), source("failed"), source("ok2"))
        val afterARun = ages("ok1" to 0L, "failed" to 600_000L, "ok2" to 0L)
        assertEquals(listOf("failed"), ContentWarm.stalest(all, afterARun, limit = 1).map { it.id })
    }

    @Test
    fun everyChannelIsCoveredByEnoughRuns() {
        // Twelve channels, a cap of five: simulate three runs, resetting the
        // age of whatever each run refreshed. Nothing may be left untouched —
        // a starvation bug here shows as one channel permanently stale, which
        // in a real family reads as "that channel never gets new videos".
        val all = (1..12).map { source("c$it") }
        val age = all.associate { it.id to (it.id.drop(1).toLong() * 1_000L) }.toMutableMap()
        repeat(3) { run ->
            val picked = ContentWarm.stalest(all, { s -> age[s.id] ?: 0L }, limit = 5)
            picked.forEach { age[it.id] = 0L }
            // Everything not refreshed gets older, which is what promotes it.
            all.filter { it !in picked }.forEach { age[it.id] = (age[it.id] ?: 0L) + 100_000L }
        }
        val neverWarmed = all.filter { (age[it.id] ?: 0L) > 250_000L }
        assertTrue("these were never refreshed: ${neverWarmed.map { it.id }}", neverWarmed.isEmpty())
    }

    @Test
    fun theCapIsHonouredAndNeverNegative() {
        val all = (1..30).map { source("c$it") }
        assertEquals(ContentWarm.PER_RUN, ContentWarm.stalest(all, { 0L }).size)
        assertTrue(ContentWarm.stalest(all, { 0L }, limit = -1).isEmpty())
        assertEquals(3, ContentWarm.stalest(all.take(3), { 0L }, limit = 99).size)
    }
}
