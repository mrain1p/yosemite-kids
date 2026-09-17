package io.yosemitekids.app

import io.yosemitekids.app.data.Budget
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.TimeWindow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the household's timezone starts to matter.
 *
 * The hub refuses to play anything for a kid who has a bedtime or a budget
 * while `homeZone` is null, because a container's clock is not a household's
 * and "today" has to mean one thing on all three faces. Nothing in the
 * product ever wrote the field, so the first parent to set either rule turned
 * the browser into a dead end with no switch anywhere to fix it. This is the
 * predicate that says the phone should stamp its own zone now.
 */
class HomeZoneTest {

    private val none = Limits()

    @Test
    fun `a family with no time rule needs no timezone`() {
        assertFalse(Budget.dayMatters(none, emptyList()))
        assertFalse(Budget.dayMatters(none, listOf(none, none)))
    }

    @Test
    fun `a bedtime makes the day matter, wherever it is set`() {
        val bedtime = Limits(
            windows = listOf(TimeWindow(id = "w1", label = "School nights", startMin = 8 * 60, endMin = 19 * 60))
        )
        assertTrue("set for the whole family", Budget.dayMatters(bedtime, emptyList()))
        assertTrue("or for one child", Budget.dayMatters(none, listOf(none, bedtime)))
    }

    @Test
    fun `so does a budget, including a weekend-only one`() {
        // A budget is per-session minutes times a count of sessions, and a day
        // with no count for it has no budget at all - which is why the weekend
        // half has to be asked separately.
        val weekday = Limits(sessionMinutes = 15, weekdaySessions = 3)
        val weekend = Limits(sessionMinutes = 30, weekendSessions = 3)
        assertTrue(Budget.dayMatters(weekday, emptyList()))
        assertTrue(Budget.dayMatters(none, listOf(weekend)))
    }
}
