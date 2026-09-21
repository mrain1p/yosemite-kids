package io.yosemitekids.app

import io.yosemitekids.app.data.Budget
import io.yosemitekids.app.data.Grant
import io.yosemitekids.app.data.Limits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Today, as the four numbers a day is made of.
 *
 * This exists because `dayMs` fuses base and bonus in its last expression and
 * every caller throws the split away on the next line — so nothing anywhere
 * could say "40 minutes, and 15 of them a grown-up gave you" without deriving
 * it again. Two faces had already started deriving it again, one of them in
 * JavaScript against the browser's own clock, which is the drift `:core`
 * exists to prevent and which was already shipped.
 *
 * So the cases here are mostly about the ways a fourth copy would have been
 * wrong: the unit it rounds in, the meaning of null, what happens when a
 * grown-up gives more time than the rules allow, and whether a face can get a
 * fraction without dividing for itself.
 */
class BudgetTodayTest {

    private val day = "2026-09-21"
    private val weekday = Calendar.WEDNESDAY
    private val weekend = Calendar.SATURDAY

    private fun rules(session: Int? = 20, weekdays: Int? = 3, weekends: Int? = 6) =
        Limits(sessionMinutes = session, weekdaySessions = weekdays, weekendSessions = weekends)

    private fun grant(minutes: Int, id: String = "g$minutes") =
        Grant(id = id, kidId = "ada", date = day, minutes = minutes, at = 1_780_000_000_000L)

    @Test
    fun `base and bonus are separable, which is the whole reason this exists`() {
        val t = Budget.today(rules(), weekday, 0L, listOf(grant(15)), spentMs = 0L)!!
        assertEquals("three sittings of twenty", 60 * 60_000L, t.baseMs)
        assertEquals(15 * 60_000L, t.bonusMs)
        assertEquals("and the sum is still what dayMs returns", 75 * 60_000L, t.budgetMs)
        assertEquals(
            Budget.dayMs(rules(), weekend = false, bonusMs = 15 * 60_000L),
            t.budgetMs
        )
    }

    @Test
    fun `the weekend switch leaves the faces`() {
        assertEquals(60 * 60_000L, Budget.today(rules(), weekday, 0L, emptyList(), 0L)!!.baseMs)
        assertEquals(120 * 60_000L, Budget.today(rules(), weekend, 0L, emptyList(), 0L)!!.baseMs)
    }

    @Test
    fun `no rule is null, and null is not zero`() {
        // Both halves are required: a session length with no count is a rule
        // the settings screen has not finished collecting, and a zero here
        // would be a limit no parent ever set.
        assertNull(Budget.today(rules(session = null), weekday, 0L, emptyList(), 0L))
        assertNull(Budget.today(rules(weekdays = null), weekday, 0L, emptyList(), 0L))
        assertNull(Budget.today(Limits(), weekday, 0L, listOf(grant(15)), 0L))
    }

    @Test
    fun `a bonus on a day with no rule is still no rule`() {
        // Not "15 minutes today". A grown-up adding time to a child who has no
        // limit has changed nothing, and a bar drawn from a 15-minute day
        // would be the app inventing a restriction.
        assertNull(Budget.today(Limits(sessionMinutes = 20), weekday, 0L, listOf(grant(15)), 0L))
    }

    @Test
    fun `what is left never goes below zero`() {
        val over = Budget.today(rules(), weekday, 0L, emptyList(), spentMs = 90 * 60_000L)!!
        assertEquals(0L, over.leftMs)
        assertEquals("and spent is reported honestly, not clamped to the budget", 90 * 60_000L, over.spentMs)
        assertEquals(1.0, over.spentFraction, 0.0001)
    }

    @Test
    fun `a negative spent cannot buy time`() {
        // A peer's mirror that arrived wrong, or a counter reset mid-day.
        val t = Budget.today(rules(), weekday, 0L, emptyList(), spentMs = -5 * 60_000L)!!
        assertEquals(0L, t.spentMs)
        assertEquals(60 * 60_000L, t.leftMs)
    }

    @Test
    fun `the fractions are computed here, so no face divides`() {
        // A page that divided for itself would round differently from the
        // hub's own cut-off and draw a full bar while the video still played.
        val t = Budget.today(rules(), weekday, 0L, listOf(grant(20)), spentMs = 40 * 60_000L)!!
        assertEquals(80 * 60_000L, t.budgetMs)
        assertEquals(0.5, t.spentFraction, 0.0001)
        assertEquals("a quarter of the day was given", 0.25, t.bonusFraction, 0.0001)
    }

    @Test
    fun `bonus extends the day rather than sitting inside it`() {
        // Two phones can each grant and both land, so base+bonus has no
        // ceiling. The bar is therefore always the whole day, with the added
        // part a share of it — a decision, and it is made once, here.
        val plain = Budget.today(rules(), weekday, 0L, emptyList(), 0L)!!
        val granted = Budget.today(rules(), weekday, 0L, listOf(grant(30, "a"), grant(30, "b")), 0L)!!
        assertTrue(granted.budgetMs > plain.budgetMs)
        assertEquals(plain.baseMs, granted.baseMs)
        assertEquals(60 * 60_000L, granted.bonusMs)
    }

    @Test
    fun `the legacy LAN grant counts as bonus too`() {
        // Guard 16 holds each of the two stores to one reader; a face that
        // read only the config grants would count the LAN minutes into the
        // total and leave them out of the caption, which has shipped once.
        val t = Budget.today(rules(), weekday, legacyBonusMs = 10 * 60_000L, listOf(grant(5)), 0L)!!
        assertEquals(15 * 60_000L, t.bonusMs)
        assertEquals(75 * 60_000L, t.budgetMs)
    }

    @Test
    fun `milliseconds survive, because the floor belongs to the ledger`() {
        // The phone's own half is exact to the second and the hub carries a
        // sub-minute remainder; a minutes-shaped return would round a second
        // time and put the two faces up to two minutes apart for the same
        // child on the same afternoon.
        val t = Budget.today(rules(), weekday, 0L, emptyList(), spentMs = 44 * 60_000L + 30_000L)!!
        assertEquals(15 * 60_000L + 30_000L, t.leftMs)
        assertEquals("15 whole minutes, rounded once, at the edge", 15L, t.leftMs / 60_000L)
    }

    @Test
    fun `a day nobody has watched is a full day`() {
        val t = Budget.today(rules(), weekday, 0L, emptyList(), 0L)!!
        assertEquals(t.budgetMs, t.leftMs)
        assertEquals(0.0, t.spentFraction, 0.0001)
    }
}
