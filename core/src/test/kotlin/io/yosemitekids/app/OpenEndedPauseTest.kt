package io.yosemitekids.app

import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.PAUSE_UNTIL_RESUMED
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.ui.KidWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pause with no end.
 *
 * Every pause used to finish at midnight, and `endOfToday`'s comment said why
 * — "an unbounded pause is one a parent forgets". The owner asked for the
 * open-ended one anyway, for the case that is not forgetfulness but a
 * consequence: *no screens until we have talked about this*, which has no
 * hour attached and should not quietly expire overnight.
 *
 * It is an instant like any other pause, so nothing that compares
 * `now < pausedUntilMillis` had to change. What did have to change is
 * anything that puts the pause into WORDS — "see you tomorrow" is a lie about
 * this one, and it is said to a child.
 */
class OpenEndedPauseTest {

    private val now = 1_790_000_000_000L

    @Test
    fun `an open-ended pause is still in force a decade later`() {
        val l = Limits(pausedUntilMillis = PAUSE_UNTIL_RESUMED)
        assertTrue(now < l.pausedUntilMillis!!)
        assertTrue(now + 10L * 365 * 24 * 60 * 60 * 1000 < l.pausedUntilMillis!!)
    }

    @Test
    fun `the question is asked in one place`() {
        assertTrue(Limits(pausedUntilMillis = PAUSE_UNTIL_RESUMED).pausedIndefinitely)
        assertFalse(Limits(pausedUntilMillis = now + 60_000).pausedIndefinitely)
        assertFalse(Limits().pausedIndefinitely)
    }

    @Test
    fun `a child is not promised a tomorrow that nothing knows about`() {
        val open = KidWords.refusal("paused-open")
        val today = KidWords.refusal("paused")
        assertNotEquals(open, today)
        assertFalse("nothing in this sentence may name a time", open.contains("tomorrow"))
        assertTrue("and the one for a pause that DOES end still may", today.contains("tomorrow"))
    }

    @Test
    fun `it survives the config round trip, for the family and for one kid`() {
        // The merge treats a pause as one value, so an open-ended one has to
        // come back as the same long it went in as — a sentinel that got
        // truncated or re-read as a plain large number would lapse silently.
        val before = Whitelist(
            emptyList(), emptySet(),
            limits = Limits(pausedUntilMillis = PAUSE_UNTIL_RESUMED),
            profiles = listOf(
                Profile(id = "ada", name = "Ada", limits = Limits(pausedUntilMillis = PAUSE_UNTIL_RESUMED))
            )
        )
        val after = ConfigJson.fromJson(ConfigJson.toJson(before))
        assertEquals(PAUSE_UNTIL_RESUMED, after.limits.pausedUntilMillis)
        assertTrue(after.limits.pausedIndefinitely)
        assertTrue(after.profiles.single().limits.pausedIndefinitely)
    }

    @Test
    fun `a kid's own open-ended pause beats the family's bounded one`() {
        // limitsFor takes the LATER of the two pauses, so a parent stopping
        // one child indefinitely is not undone by the family pause ending at
        // midnight — and the reverse holds too.
        val config = Whitelist(
            emptyList(), emptySet(),
            limits = Limits(pausedUntilMillis = now + 3_600_000),
            profiles = listOf(
                Profile(id = "ada", name = "Ada", limits = Limits(pausedUntilMillis = PAUSE_UNTIL_RESUMED))
            )
        )
        assertTrue(config.limitsFor("ada").pausedIndefinitely)
    }
}
