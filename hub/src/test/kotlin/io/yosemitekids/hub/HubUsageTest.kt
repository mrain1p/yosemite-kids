package io.yosemitekids.hub

import io.yosemitekids.app.data.UsageLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The hub's half of the ledger: what it keeps, what it refuses, and the one
 * calendar it is allowed to read.
 */
class HubUsageTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** 2026-09-05T12:00:00Z — 2026-09-06 in Auckland, which is the point. */
    private val clock = 1_788_609_600_000L

    private fun usage(zone: String? = null, at: Long = clock, dir: File = folder.root) =
        HubUsage(dir, homeZone = { zone }, now = { at })

    private fun body(vararg cells: Triple<String, String, Pair<String, Int>>): String {
        var l = UsageLedger.Ledger.EMPTY
        cells.forEach { (kid, day, dev) ->
            l = UsageLedger.withOwn(l, kid, day, dev.first, dev.second)
        }
        return UsageLedger.toJson(l)
    }

    private fun cell(kid: String, day: String, dev: String, m: Int) = Triple(kid, day, dev to m)

    @Test
    fun `two devices reporting add up, and a later report never lowers one`() {
        val u = usage()
        assertTrue(u.merge(body(cell("k1", "2026-09-05", "tv", 30)), "tv") > 0)
        assertTrue(u.merge(body(cell("k1", "2026-09-05", "tab", 15)), "tab") > 0)
        assertEquals(45, u.minutesFor("k1", "2026-09-05"))
        u.merge(body(cell("k1", "2026-09-05", "tv", 2)), "tv")
        assertEquals(45, u.minutesFor("k1", "2026-09-05"))
    }

    @Test
    fun `a writer may only raise its own cells`() {
        val u = usage()
        // The tablet reports a huge number for the television. The hub keeps
        // the tablet's own cell and nothing else — every enrolled device can
        // reach this box directly, so no relay through it is needed.
        u.merge(
            body(cell("k1", "2026-09-05", "tab", 10), cell("k1", "2026-09-05", "tv", 9_999)),
            "tab"
        )
        assertEquals(10, u.minutesFor("k1", "2026-09-05"))
    }

    @Test
    fun `a body with no author is refused`() {
        val u = usage()
        assertEquals(-1, u.merge(body(cell("k1", "2026-09-05", "tv", 30)), null))
        assertEquals(-1, u.merge(body(cell("k1", "2026-09-05", "tv", 30)), "  "))
        assertEquals(0, u.minutesFor("k1", "2026-09-05"))
    }

    @Test
    fun `a body that will not parse is refused rather than half-merged`() {
        val u = usage()
        u.merge(body(cell("k1", "2026-09-05", "tv", 30)), "tv")
        assertEquals(-1, u.merge("not json at all", "tv"))
        assertEquals(30, u.minutesFor("k1", "2026-09-05"))
    }

    @Test
    fun `an oversized ledger is refused, not truncated`() {
        var big = UsageLedger.Ledger.EMPTY
        repeat(UsageLedger.MAX_CELLS + 1) {
            big = UsageLedger.withOwn(big, "k1", "2026-09-05", "tv", 1)
            big = UsageLedger.withOwn(big, "k$it", "2026-09-05", "tv", 1)
        }
        assertEquals(-1, usage().merge(UsageLedger.toJson(big), "tv"))
    }

    // --- the one calendar this container may read -------------------------

    @Test
    fun `with no home zone the hub does not know what day it is`() {
        assertNull(usage().today())
        assertNull(usage("Pacific/Atlantis").today())
        assertNull(usage("").today())
    }

    @Test
    fun `with a home zone the day is the family's, not the container's`() {
        // A container in UTC and a family in Auckland disagree for thirteen
        // hours of every day. The hub takes the family's answer.
        assertEquals("2026-09-06", usage("Pacific/Auckland").today())
        assertEquals("2026-09-05", usage("Europe/London").today())
    }

    @Test
    fun `a home zone windows the ledger, and its absence still bounds it`() {
        val zoned = usage("Europe/London") // today = 2026-09-05
        zoned.merge(
            body(
                cell("k1", "2026-08-01", "tv", 10),
                cell("k1", "2026-09-05", "tv", 20),
                cell("k1", "2026-09-30", "tv", 999)
            ),
            "tv"
        )
        val kept = UsageLedger.parse(zoned.exportJson())!!.cells.getValue("k1").keys
        // A week back, a day forward: the stale day and the fast clock both go.
        assertEquals(setOf("2026-09-05"), kept)

        // With no zone the hub windows nothing by day — it cannot name one —
        // but it never guesses UTC either; it caps by cell count instead.
        // Its own directory: two HubUsage over one folder are one file.
        val blind = usage(dir = File(folder.root, "blind"))
        blind.merge(
            body(cell("k1", "2026-08-01", "tv", 10), cell("k1", "2026-09-30", "tv", 20)),
            "tv"
        )
        assertEquals(2, UsageLedger.count(UsageLedger.parse(blind.exportJson())!!))
    }

    // --- the config is not touched ---------------------------------------

    @Test
    fun `watch traffic writes usage json and nothing else`() {
        val store = HubStore(folder.root)
        store.edit(who = "test", now = clock) { it.copy(homeZone = "Europe/London") }
        val before = File(folder.root, "config.json").readText()
        val versionsBefore = File(folder.root, "versions").listFiles()?.size ?: 0

        val u = usage()
        repeat(20) { u.merge(body(cell("k1", "2026-09-05", "tv", it)), "tv") }

        assertTrue(File(folder.root, "usage.json").exists())
        // The config's bytes, its version ring and its change log are exactly
        // where they were: a counter is not curation, and a minute of viewing
        // must not move a fingerprint, nudge a fleet, or push a family's
        // change history out of a thirty-line log.
        assertEquals(before, File(folder.root, "config.json").readText())
        assertEquals(versionsBefore, File(folder.root, "versions").listFiles()?.size ?: 0)
        assertFalse(before.contains("usage"))
    }

    @Test
    fun `the ledger survives a restart and is served as it was stored`() {
        usage().merge(body(cell("k1", "2026-09-05", "tv", 30)), "tv")
        val reopened = usage()
        assertNotNull(UsageLedger.parse(reopened.exportJson()))
        assertEquals(30, reopened.minutesFor("k1", "2026-09-05"))
    }
}
