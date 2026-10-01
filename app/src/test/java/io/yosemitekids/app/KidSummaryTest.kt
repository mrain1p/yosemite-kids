package io.yosemitekids.app

import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.TimeWindow
import io.yosemitekids.app.ui.KID_RULE_COUNT
import io.yosemitekids.app.ui.kidSummary
import io.yosemitekids.app.ui.rulesSet
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one-line summary under a kid's name on the Kids page:
 * "Age 7 · 2 of 7 rules set · no profile code".
 */
class KidSummaryTest {

    @Test
    fun countsEveryRuleTheCardSetsAndNothingElse() {
        assertEquals(0, rulesSet(Limits()))
        assertEquals(2, rulesSet(Limits(sessionMinutes = 30, minVideoMinutes = 3)))
        // Every row of the card, so the numerator can reach the denominator.
        // A rule added to the card and not to rulesSet leaves a fully
        // configured kid reading "5 of 7" — an unfinished setup, to the
        // parent who has just finished it.
        assertEquals(
            KID_RULE_COUNT,
            rulesSet(
                Limits(
                    weekdayMinutes = 90, weekendMinutes = 120,
                    sessionMinutes = 30, weekdaySessions = 2, weekendSessions = 3,
                    breakMinutes = 15, minVideoMinutes = 3
                )
            )
        )
        // A bedtime window and today's pause are not rules: a kid with only
        // a window still reads "0 of 7".
        val scheduled = Limits(
            windows = listOf(TimeWindow("w", "Bedtime", 19 * 60, 7 * 60)),
            pausedUntilMillis = Long.MAX_VALUE
        )
        assertEquals(0, rulesSet(scheduled))
    }

    @Test
    fun alwaysSaysAllThreeThings() {
        assertEquals(
            "Age 7 · 2 of 7 rules set · no profile code",
            kidSummary(Profile(id = "a", name = "Amelia", age = 7,
                limits = Limits(sessionMinutes = 30, breakMinutes = 10)))
        )
        // Zero rules is spelled out rather than counted: "0 of 7" reads as an
        // unfinished setup step, unlimited watching is a state.
        assertEquals(
            "No age set · No limits set — unlimited watching · profile code set",
            kidSummary(Profile(id = "b", name = "Ben", pin = "UDLR"))
        )
    }
}
