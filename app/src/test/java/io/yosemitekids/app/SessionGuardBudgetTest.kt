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

    /**
     * The shared budget's whole arithmetic: one summand. Peers add to this
     * device's own minutes and to nothing else — sittings, break locks,
     * blocked windows and pause stay device-local, because a sitting is a
     * stretch in front of one screen and only the *budget* was asked to be
     * shared.
     */
    @Test
    fun spentIsThisDevicesMinutesPlusWhatPeersReported() {
        assertEquals(30 * 60_000L, SessionGuard.spentMs(30 * 60_000L, 0L))
        assertEquals(45 * 60_000L, SessionGuard.spentMs(30 * 60_000L, 15 * 60_000L))
    }

    /**
     * With no peers reporting — every family today, because the switch that
     * shares a budget is not built — the number is byte-for-byte the one this
     * app has always enforced against.
     */
    @Test
    fun noPeersMeansExactlyTheBehaviourTheAppAlreadyHad() {
        val budget = SessionGuard.budgetMs(limits, weekend = false, bonusMs = 0L)!!
        val own = 59 * 60_000L
        assertEquals(own, SessionGuard.spentMs(own, 0L))
        assert(SessionGuard.spentMs(own, 0L) < budget)
        // …and one minute of a sibling's tablet is what tips it over, once the
        // policy that feeds the mirror exists.
        assert(SessionGuard.spentMs(own, 60_000L) >= budget)
    }
}
