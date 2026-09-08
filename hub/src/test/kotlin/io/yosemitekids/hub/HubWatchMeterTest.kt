package io.yosemitekids.hub

import io.yosemitekids.app.data.UsageLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Counting minutes for a viewer that can lie.
 *
 * Every device in this project counts its own time and is believed, because
 * the counter lives inside an app a child cannot reach. A browser is not that:
 * a number a page reports is a number a page can choose. So the three
 * properties below are the whole design, and each is a way of cheating that
 * has to fail — spending a minute twice, winding one back, and rotating an
 * identity to get a fresh budget.
 */
class HubWatchMeterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 2026-09-07T09:00:00Z — 2026-09-07 in Auckland, which is the point. */
    private val start = 1_788_771_600_000L
    private var at = start

    private lateinit var dir: File
    private lateinit var usage: HubUsage

    private fun meter(zone: String? = "Pacific/Auckland"): HubWatchMeter {
        dir = tmp.newFolder()
        usage = HubUsage(dir, homeZone = { zone }, now = { at })
        return HubWatchMeter(usage) { at }
    }

    private val kid = "aaaa1111"

    // --- the hub does the timing ----------------------------------------

    @Test
    fun `the first beat plants the mark and credits nothing`() {
        val m = meter()
        assertEquals(0, m.beat("session-a", kid))
        assertEquals("nothing was watched yet", 0, usage.minutesFor(kid, "2026-09-07"))
    }

    @Test
    fun `minutes come from the hub's own clock between beats`() {
        val m = meter()
        m.beat("session-a", kid)
        at += 60_000
        assertEquals(1, m.beat("session-a", kid))
        at += 60_000
        assertEquals(2, m.beat("session-a", kid))
        assertEquals(2, usage.minutesFor(kid, "2026-09-07"))
    }

    @Test
    fun `a replayed beat spends nothing, however many times it arrives`() {
        // The mark moves on every beat, so the same request repeated credits
        // the gap once. A page hammering this cannot buy itself minutes, and
        // — more usefully — cannot be charged for them either.
        val m = meter()
        m.beat("session-a", kid)
        at += 60_000
        assertEquals(1, m.beat("session-a", kid))
        repeat(20) { assertEquals(1, m.beat("session-a", kid)) }
        assertEquals(1, usage.minutesFor(kid, "2026-09-07"))
    }

    @Test
    fun `a long silence credits at most one gap, never the whole of it`() {
        // A closed laptop is the ordinary explanation for a page that stops
        // reporting, and crediting an hour to it would take time from a child
        // who was not watching. The residual — a page that deliberately stops
        // beating and keeps playing — is the media half's problem, not this
        // one's, and it is named in HubWatchMeter's KDoc.
        val m = meter()
        m.beat("session-a", kid)
        at += 60 * 60_000
        assertEquals((HubWatchMeter.MAX_GAP_MS / 60_000L).toInt(), m.beat("session-a", kid))
    }

    @Test
    fun `a clock that steps backwards credits nothing and takes nothing away`() {
        val m = meter()
        m.beat("session-a", kid)
        at += 60_000
        assertEquals(1, m.beat("session-a", kid))
        at -= 30 * 60_000
        assertEquals("a rewound clock must not lower a credited minute", 1, m.beat("session-a", kid))
        assertEquals(1, usage.minutesFor(kid, "2026-09-07"))
    }

    @Test
    fun `the credited figure only ever rises within a day`() {
        val m = meter()
        m.beat("session-a", kid)
        at += 120_000
        assertEquals(2, m.beat("session-a", kid))
        // A second meter for the same browser — the shape a hub restart makes
        // — starts its own accrual, and the ledger's per-cell max means the
        // stored figure cannot fall behind what was already counted.
        val fresh = HubWatchMeter(usage) { at }
        fresh.beat("session-a", kid)
        at += 60_000
        fresh.beat("session-a", kid)
        assertTrue(usage.minutesFor(kid, "2026-09-07") >= 2)
    }

    // --- identity --------------------------------------------------------

    @Test
    fun `a browser's cell is namespaced, so it can never be a device's`() {
        val m = meter()
        val id = m.ledgerId("session-a")
        assertTrue(id, UsageLedger.isWeb(id))
        // And it is derived, not carried: the session string does not appear
        // in the id, so a page that knows another viewer's session still
        // cannot say which cell to look at — or write to one.
        assertFalse(id.contains("session-a"))
        assertEquals("stable for the life of the process", id, m.ledgerId("session-a"))
        assertNotEquals(id, m.ledgerId("session-b"))
        assertNotEquals("a per-process salt, or the id would be guessable", id, meter().ledgerId("session-a"))
    }

    @Test
    fun `switching kid starts a new cell rather than moving minutes`() {
        val m = meter()
        m.beat("session-a", kid)
        at += 60_000
        assertEquals(1, m.beat("session-a", kid))
        // The same browser now watching as a sibling: the accrual restarts, or
        // one child's minutes would land on the other's budget.
        assertEquals(0, m.beat("session-a", "bbbb2222"))
        at += 60_000
        assertEquals(1, m.beat("session-a", "bbbb2222"))
        assertEquals(1, usage.minutesFor(kid, "2026-09-07"))
        assertEquals(1, usage.minutesFor("bbbb2222", "2026-09-07"))
    }

    @Test
    fun `a new day starts a new cell`() {
        val m = meter()
        m.beat("session-a", kid)
        at += 60_000
        assertEquals(1, m.beat("session-a", kid))
        at += 24 * 60 * 60_000
        assertEquals("a fresh day plants a fresh mark", 0, m.beat("session-a", kid))
        assertEquals(1, usage.minutesFor(kid, "2026-09-07"))
        assertEquals(0, usage.minutesFor(kid, "2026-09-08"))
    }

    @Test
    fun `live meters are bounded, because a home network allocates them`() {
        val m = meter()
        repeat(HubWatchMeter.MAX_LIVE + 50) { m.beat("session-$it", kid) }
        assertEquals(HubWatchMeter.MAX_LIVE, m.liveCount())
    }

    // --- the day the hub may not guess ------------------------------------

    @Test
    fun `with no home zone nothing is counted, and the refusal is distinguishable`() {
        // The same answer HubPolicy gives, and the two agree by construction:
        // a kid with rules is not allowed to start, so there are no minutes to
        // file; a kid without them has no budget to file them against. A
        // container that guessed UTC would file a household in Auckland's
        // evening under yesterday, every day, and nothing would throw.
        val m = meter(zone = null)
        assertEquals(-1, m.beat("session-a", kid))
        at += 60_000
        assertEquals(-1, m.beat("session-a", kid))
        assertEquals(0, m.liveCount())
    }

    @Test
    fun `nothing but a browser id can be written through this path`() {
        val m = meter()
        m.beat("session-a", kid)
        at += 60_000
        m.beat("session-a", kid)
        val cells = UsageLedger.parse(usage.exportJson())!!.cells.getValue(kid).getValue("2026-09-07")
        assertEquals(1, cells.size)
        assertTrue(cells.keys.single(), UsageLedger.isWeb(cells.keys.single()))
        // And the direct writer refuses an id outside the namespace, so a
        // caller cannot file a browser's minutes under a device's token.
        assertFalse(usage.recordWeb(kid, "dev-tv", 99))
    }
}
