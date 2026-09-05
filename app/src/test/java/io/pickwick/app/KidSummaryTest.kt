package io.pickwick.app

import io.pickwick.app.data.Limits
import io.pickwick.app.data.Profile
import io.pickwick.app.data.TimeWindow
import io.pickwick.app.ui.KID_RULE_COUNT
import io.pickwick.app.ui.kidSummary
import io.pickwick.app.ui.rulesSet
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one-line summary under a kid's name on the Kids page:
 * "Age 7 · 2 of 5 rules set · no profile code".
 */
class KidSummaryTest {

    @Test
    fun countsTheFiveRulesAndNothingElse() {
        assertEquals(0, rulesSet(Limits()))
        assertEquals(2, rulesSet(Limits(sessionMinutes = 30, minVideoMinutes = 3)))
        assertEquals(
            KID_RULE_COUNT,
            rulesSet(
                Limits(
                    sessionMinutes = 30, weekdaySessions = 2, weekendSessions = 3,
                    breakMinutes = 15, minVideoMinutes = 3
                )
            )
        )
        // A bedtime window and today's pause are not rules: the denominator
        // is fixed at five, so a kid with only a window still reads "0 of 5".
        val scheduled = Limits(
            windows = listOf(TimeWindow("w", "Bedtime", 19 * 60, 7 * 60)),
            pausedUntilMillis = Long.MAX_VALUE
        )
        assertEquals(0, rulesSet(scheduled))
    }

    @Test
    fun alwaysSaysAllThreeThings() {
        assertEquals(
            "Age 7 · 2 of 5 rules set · no profile code",
            kidSummary(Profile(id = "a", name = "Amelia", age = 7,
                limits = Limits(sessionMinutes = 30, breakMinutes = 10)))
        )
        assertEquals(
            "No age set · 0 of 5 rules set · profile code set",
            kidSummary(Profile(id = "b", name = "Ben", pin = "UDLR"))
        )
    }
}
