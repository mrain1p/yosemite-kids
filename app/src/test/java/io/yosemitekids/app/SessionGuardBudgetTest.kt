package io.yosemitekids.app

import io.yosemitekids.app.data.BUDGET_SCOPE_SHARED
import io.yosemitekids.app.data.Grant
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SessionGuard
import io.yosemitekids.app.data.UsageSync
import io.yosemitekids.app.data.Whitelist
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
     * With no peers reporting the number is byte-for-byte the one this app has
     * always enforced against.
     */
    @Test
    fun noPeersMeansExactlyTheBehaviourTheAppAlreadyHad() {
        val budget = SessionGuard.budgetMs(limits, weekend = false, bonusMs = 0L)!!
        val own = 59 * 60_000L
        assertEquals(own, SessionGuard.spentMs(own, 0L))
        assert(SessionGuard.spentMs(own, 0L) < budget)
        // …and one minute of a sibling's tablet is what tips it over, once a
        // parent has said the budget is shared.
        assert(SessionGuard.spentMs(own, 60_000L) >= budget)
    }

    /**
     * The one property the whole feature rests on: **under the default scope
     * the arithmetic is unchanged**, whatever is sitting in the mirror.
     *
     * A device keeps mirroring peers' minutes across a scope being turned off
     * and on again, and the mirror is grow-only within a day — so if the gate
     * were on the write rather than the read, a family that tried sharing for
     * an hour and thought better of it would go on paying for it until
     * midnight, on every screen, with nothing to say why.
     */
    @Test
    fun peersCountOnlyWhereAParentSaidTheBudgetIsShared() {
        val mirrored = 20 * 60_000L
        assertEquals(0L, SessionGuard.peerMs(limits, mirrored))
        assertEquals(0L, SessionGuard.peerMs(limits.copy(budgetScope = null), mirrored))
        assertEquals(mirrored, SessionGuard.peerMs(limits.copy(budgetScope = BUDGET_SCOPE_SHARED), mirrored))
        // The reason the field is a string: a mode this build has never heard
        // of behaves exactly as the family's television did yesterday.
        assertEquals(0L, SessionGuard.peerMs(limits.copy(budgetScope = "school-nights"), mirrored))
        // And the two halves compose to the whole of it.
        val own = 30 * 60_000L
        val shared = limits.copy(budgetScope = BUDGET_SCOPE_SHARED)
        assertEquals(own, SessionGuard.spentMs(own, SessionGuard.peerMs(limits, mirrored)))
        assertEquals(own + mirrored, SessionGuard.spentMs(own, SessionGuard.peerMs(shared, mirrored)))
    }

    /**
     * Which kids a device trades minutes for, resolved through the same
     * `Whitelist.limitsFor` every other rule uses — and by **no second rule**.
     *
     * That resolution is narrower than it reads: a kid who exists gets their
     * own `Profile.limits` and nothing is inherited from `Whitelist.limits`
     * except a family-wide pause. So sharing is per child among households
     * that have children, and the family scalar answers only where there are
     * no profiles. Written down here because the obvious "surely the family
     * default falls through" is wrong, and the way to find that out must not
     * be a sibling's television counting against a kid who was never shared.
     */
    @Test
    fun sharedKidsAreResolvedLikeEveryOtherRule() {
        val shared = Limits(budgetScope = BUDGET_SCOPE_SHARED)
        val leo = Profile(id = "k1", name = "Leo", limits = shared)
        val noa = Profile(id = "k2", name = "Noa")
        val family = Whitelist(emptyList(), emptySet(), profiles = listOf(leo, noa))
        assertEquals(listOf<String?>("k1"), UsageSync.sharedKids(family))

        // The family scalar does not reach a kid who set nothing of their own.
        assertEquals(listOf<String?>("k1"), UsageSync.sharedKids(family.copy(limits = shared)))
        // It is the whole answer for a household with no profiles, under the
        // null kid the ledger keys as "-".
        assertEquals(
            listOf<String?>(null),
            UsageSync.sharedKids(Whitelist(emptyList(), emptySet(), limits = shared))
        )
        // Nobody, for every family on the default scope — which is what makes
        // the trade cost nothing for a household that has not asked for it.
        assertEquals(emptyList<String?>(), UsageSync.sharedKids(family.copy(profiles = listOf(noa))))
        assertEquals(emptyList<String?>(), UsageSync.sharedKids(Whitelist(emptyList(), emptySet())))
    }
}
