package io.yosemitekids.app

import io.yosemitekids.app.data.Grant
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.SessionGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The day's budget with the config's grants in it — the pure half of
 * SessionGuard, so a grant that arrived by config rather than by LAN call
 * is held against the budget without a Context.
 */
class SessionGuardBudgetTest {

    private val limits = Limits(sessionMinutes = 30, weekdaySessions = 2, weekendSessions = 3)

    private fun grant(id: String, minutes: Int) =
        Grant(id = id, kidId = "k1", date = "2026-09-05", minutes = minutes, at = 0L)

    @Test
    fun theBudgetIsSittingsTimesLengthPlusEveryBonus() {
        assertEquals(60 * 60_000L, SessionGuard.budgetMs(limits, weekend = false, bonusMs = 0L))
        assertEquals(90 * 60_000L, SessionGuard.budgetMs(limits, weekend = true, bonusMs = 0L))
        assertEquals(75 * 60_000L, SessionGuard.budgetMs(limits, weekend = false, bonusMs = 15 * 60_000L))
    }

    @Test
    fun noLimitMeansNoBudgetWhateverWasGranted() {
        assertNull(SessionGuard.budgetMs(Limits(), weekend = false, bonusMs = 15 * 60_000L))
        assertNull(SessionGuard.budgetMs(Limits(sessionMinutes = 30), weekend = false, bonusMs = 0L))
    }

    @Test
    fun theLegacyLanBonusAndTheConfigsGrantsAreOneNumber() {
        val grants = listOf(grant("aaaa0001", 15), grant("aaaa0002", 10))
        assertEquals(26 * 60_000L, SessionGuard.bonusMs(60_000L, grants))
        assertEquals(0L, SessionGuard.bonusMs(0L, emptyList()))
    }
}
