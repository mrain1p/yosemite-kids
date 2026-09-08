package io.yosemitekids.app

import io.yosemitekids.app.data.UsageLedger
import io.yosemitekids.app.data.UsageLedger.Ledger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The join, and the three laws that make it safe to apply again and again.
 *
 * A Push button and a fifteen-minute worker both re-apply a merge to inputs
 * that have not moved, which is why the convergence harness matters more here
 * than a single-shot assertion — the same reason `MergeConvergenceTest` exists
 * for the config.
 */
class UsageLedgerTest {

    private fun ledger(vararg cells: Triple<String, String, Pair<String, Int>>): Ledger {
        var l = Ledger.EMPTY
        cells.forEach { (kid, day, dev) ->
            l = UsageLedger.withOwn(l, kid, day, dev.first, dev.second, at = dev.second.toLong())
        }
        return l
    }

    private fun cell(kid: String, day: String, dev: String, m: Int) = Triple(kid, day, dev to m)

    // --- the laws -------------------------------------------------------

    @Test
    fun `merge is per-cell max`() {
        val a = ledger(cell("k1", "2026-09-05", "tv", 30))
        val b = ledger(cell("k1", "2026-09-05", "tv", 12))
        assertEquals(30, UsageLedger.othersToday(UsageLedger.merge(a, b), "phone", "k1", "2026-09-05"))
        // A lower report never wins, whichever side it arrives from.
        assertEquals(30, UsageLedger.othersToday(UsageLedger.merge(b, a), "phone", "k1", "2026-09-05"))
    }

    @Test
    fun `two devices that each watched do not disagree, they add up`() {
        val tv = ledger(cell("k1", "2026-09-05", "tv", 30))
        val tab = ledger(cell("k1", "2026-09-05", "tab", 15))
        val both = UsageLedger.merge(tv, tab)
        assertEquals(45, UsageLedger.othersToday(both, "phone", "k1", "2026-09-05"))
    }

    @Test
    fun `merge is commutative, idempotent and associative over generated ledgers`() {
        val rnd = Random(20260907)
        val kids = listOf("k1", "k2", "-")
        val days = listOf("2026-09-03", "2026-09-04", "2026-09-05")
        val devs = listOf("tv", "tab", "phone", "hub")
        fun random(): Ledger {
            var l = Ledger.EMPTY
            repeat(rnd.nextInt(1, 12)) {
                l = UsageLedger.withOwn(
                    l, kids.random(rnd), days.random(rnd), devs.random(rnd),
                    rnd.nextInt(0, 200), rnd.nextLong(0, 1_000)
                )
            }
            return l
        }
        repeat(60) {
            val a = random()
            val b = random()
            val c = random()
            val ab = UsageLedger.merge(a, b)
            assertEquals(UsageLedger.toJson(ab), UsageLedger.toJson(UsageLedger.merge(b, a)))
            assertEquals(UsageLedger.toJson(ab), UsageLedger.toJson(UsageLedger.merge(ab, b)))
            assertEquals(
                UsageLedger.toJson(UsageLedger.merge(UsageLedger.merge(a, b), c)),
                UsageLedger.toJson(UsageLedger.merge(a, UsageLedger.merge(b, c)))
            )
        }
    }

    @Test
    fun `repeated application with the inputs held still settles after one round`() {
        // What a Push button and the fifteen-minute worker actually do. A rule
        // that passes a single merge and fails here is not a rule yet.
        val mine = ledger(cell("k1", "2026-09-05", "tv", 30), cell("k2", "2026-09-05", "tv", 5))
        val theirs = ledger(cell("k1", "2026-09-05", "tab", 15), cell("k1", "2026-09-04", "tab", 60))
        var settled = UsageLedger.merge(mine, theirs)
        val first = UsageLedger.toJson(settled)
        repeat(10) {
            settled = UsageLedger.merge(settled, theirs)
            settled = UsageLedger.merge(mine, settled)
            assertEquals("round $it moved the ledger", first, UsageLedger.toJson(settled))
        }
    }

    @Test
    fun `a local write never lowers a cell`() {
        var l = ledger(cell("k1", "2026-09-05", "tv", 30))
        l = UsageLedger.withOwn(l, "k1", "2026-09-05", "tv", 4)
        assertEquals(30, UsageLedger.othersToday(l, "phone", "k1", "2026-09-05"))
    }

    // --- aggregation ----------------------------------------------------

    @Test
    fun `a device never counts its own cell twice`() {
        // The exclusion whose absence silently halves a kid's day: this device
        // reads its own minutes back through a peer and adds them to its own
        // live counter.
        val l = ledger(
            cell("k1", "2026-09-05", "tv", 30),
            cell("k1", "2026-09-05", "tab", 15)
        )
        assertEquals(15, UsageLedger.othersToday(l, "tv", "k1", "2026-09-05"))
        assertEquals(30, UsageLedger.othersToday(l, "tab", "k1", "2026-09-05"))
        assertEquals(45, UsageLedger.othersToday(l, "phone", "k1", "2026-09-05"))
    }

    @Test
    fun `another day contributes nothing`() {
        val l = ledger(
            cell("k1", "2026-09-05", "tv", 30),
            cell("k1", "2026-09-04", "tv", 90)
        )
        assertEquals(30, UsageLedger.othersToday(l, "phone", "k1", "2026-09-05"))
    }

    @Test
    fun `a family with no profiles writes under one key`() {
        val l = UsageLedger.withOwn(Ledger.EMPTY, null, "2026-09-05", "tv", 20)
        assertEquals(20, UsageLedger.othersToday(l, "phone", null, "2026-09-05"))
        assertEquals(20, UsageLedger.othersToday(l, "phone", UsageLedger.NO_KID, "2026-09-05"))
        // …and another kid's total is not it.
        assertEquals(0, UsageLedger.othersToday(l, "phone", "k1", "2026-09-05"))
    }

    // --- the window, which is local and separate -------------------------

    @Test
    fun `trim keeps a week behind and a day ahead`() {
        var l = Ledger.EMPTY
        listOf(
            "2026-08-20", "2026-08-29", "2026-09-05", "2026-09-06", "2026-09-08"
        ).forEach { l = UsageLedger.withOwn(l, "k1", it, "tv", 10) }
        val kept = UsageLedger.trim(l, "2026-09-05").cells.getValue("k1").keys
        assertEquals(setOf("2026-08-29", "2026-09-05", "2026-09-06"), kept)
    }

    @Test
    fun `trim is not applied by merge, so a peer may reintroduce a trimmed day`() {
        // Grow-only cells with no tombstones: the reintroduced day is inert,
        // because every reader sums one day, and the next local write trims it
        // again. The alternative — a window inside merge — is a tombstone TTL,
        // and two devices with different windows would push at each other for
        // ever.
        val mine = UsageLedger.trim(
            UsageLedger.withOwn(Ledger.EMPTY, "k1", "2026-08-20", "tv", 10), "2026-09-05"
        )
        assertTrue(mine.isEmpty)
        val stale = UsageLedger.withOwn(Ledger.EMPTY, "k1", "2026-08-20", "tab", 10)
        val merged = UsageLedger.merge(mine, stale)
        assertEquals(10, UsageLedger.othersToday(merged, "tv", "k1", "2026-08-20"))
        assertEquals(0, UsageLedger.othersToday(merged, "tv", "k1", "2026-09-05"))
    }

    @Test
    fun `a fast clock cannot pre-spend a day nobody has reached`() {
        val l = ledger(
            cell("k1", "2026-09-05", "tv", 30),
            cell("k1", "2026-09-06", "tv", 30),
            cell("k1", "2026-09-07", "tv", 999),
            cell("k1", "2027-01-01", "tv", 999)
        )
        val days = UsageLedger.forward(l, "2026-09-05").cells.getValue("k1").keys
        // One day of slack: two devices either side of midnight in different
        // zones are both telling the truth.
        assertEquals(setOf("2026-09-05", "2026-09-06"), days)
    }

    @Test
    fun `the hub keeps only the cells their writer authored`() {
        val body = ledger(
            cell("k1", "2026-09-05", "tv", 30),
            cell("k1", "2026-09-05", "tab", 999)
        )
        val owned = UsageLedger.ownedBy(body, "tv")
        assertEquals(30, UsageLedger.othersToday(owned, "phone", "k1", "2026-09-05"))
        assertEquals(1, UsageLedger.count(owned))
    }

    @Test
    fun `cap drops whole days, newest first kept`() {
        var l = Ledger.EMPTY
        listOf("2026-09-01", "2026-09-02", "2026-09-03").forEach {
            l = UsageLedger.withOwn(l, "k1", it, "tv", 10)
        }
        assertEquals(3, UsageLedger.count(l))
        val capped = UsageLedger.cap(l, 2)
        assertEquals(setOf("2026-09-03", "2026-09-02"), capped.cells.getValue("k1").keys)
    }

    // --- the wire -------------------------------------------------------

    @Test
    fun `a ledger round-trips`() {
        val l = ledger(
            cell("k1", "2026-09-05", "tv", 30),
            cell("k2", "2026-09-04", "tab", 15)
        )
        val back = UsageLedger.parse(UsageLedger.toJson(l))
        assertNotNull(back)
        assertEquals(UsageLedger.toJson(l), UsageLedger.toJson(back!!))
    }

    @Test
    fun `the wire form is canonical whatever order the cells were written in`() {
        val one = ledger(
            cell("k2", "2026-09-05", "tv", 30),
            cell("k1", "2026-09-04", "tab", 15)
        )
        val other = ledger(
            cell("k1", "2026-09-04", "tab", 15),
            cell("k2", "2026-09-05", "tv", 30)
        )
        assertEquals(UsageLedger.toJson(one), UsageLedger.toJson(other))
    }

    @Test
    fun `garbage parses to nothing and an oversized body is refused outright`() {
        assertEquals(Ledger.EMPTY, UsageLedger.parse(null))
        assertEquals(Ledger.EMPTY, UsageLedger.parse(""))
        assertNull(UsageLedger.parse("not json"))
        assertEquals(Ledger.EMPTY, UsageLedger.parse("""{"v":1}"""))

        var big = Ledger.EMPTY
        repeat(UsageLedger.MAX_CELLS + 1) {
            big = UsageLedger.withOwn(big, "k1", "2026-09-05", "dev$it", 1)
        }
        // Refused, not truncated, so a caller can answer 400 rather than
        // half-merging a body it cannot vouch for.
        assertNull(UsageLedger.parse(UsageLedger.toJson(big)))
    }

    @Test
    fun `a negative minute count cannot be written or parsed in`() {
        val l = UsageLedger.withOwn(Ledger.EMPTY, "k1", "2026-09-05", "tv", -50)
        assertEquals(0, UsageLedger.othersToday(l, "phone", "k1", "2026-09-05"))
        val hostile = """{"v":1,"cells":{"k1":{"2026-09-05":{"tv":{"m":-50}}}}}"""
        assertEquals(0, UsageLedger.othersToday(UsageLedger.parse(hostile)!!, "p", "k1", "2026-09-05"))
    }
}
