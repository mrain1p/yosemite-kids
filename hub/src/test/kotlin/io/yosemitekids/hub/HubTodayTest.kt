package io.yosemitekids.hub

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.Grant
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.ScreeningStore
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.ui.KidWords
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The countdown block, and the two ways it used to contradict itself.
 *
 * `timeJson` is the one shape both `/kid/home` and every beat of
 * `/kid/progress` read, and it was made `internal` precisely because a second
 * copy of it "is how a countdown comes to say different things on the same
 * screen a minute apart". It then grew the same fault inside itself:
 * `leftSeconds` had the watch meter's unsettled remainder subtracted from it
 * and the sentence beside it did not, so the two numbers in one payload
 * described two different amounts of time.
 *
 * And it emitted `budgetMinutes` with base and bonus already added together,
 * which is why the parent console ended up deriving the bonus for itself in
 * JavaScript, against the browser's clock rather than the family's home zone.
 */
class HubTodayTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A Wednesday, mid-afternoon, in the family's zone. */
    private val at = 1_788_771_600_000L
    private val ada = "ada00001"
    private val zone = "America/Los_Angeles"

    private fun home(
        limits: Limits,
        grants: List<Grant> = emptyList(),
        meter: HubWatchMeter? = null
    ): HubKidHome {
        val dir = tmp.newFolder()
        val store = HubStore(dir)
        store.edit("test", at) {
            Whitelist(
                emptyList(), emptySet(),
                profiles = listOf(Profile(id = ada, name = "Ada", limits = limits)),
                homeZone = zone,
                grants = grants
            )
        }
        val index = ChannelIndex(File(dir, "search-index"))
        val policy = HubPolicy(
            store,
            HubUsage(dir, homeZone = { runCatching { store.load().homeZone }.getOrNull() }, now = { at }),
            ScreeningStore(File(dir, "screening.json")),
            index
        ) { at }
        return HubKidHome(policy, store, HubKidHistory(dir), HubSavedLists(dir), meter = meter)
    }

    private fun time(h: HubKidHome, viewer: String? = null): JSONObject =
        h.timeJson(ada, viewer) as JSONObject

    private fun rules() = Limits(sessionMinutes = 20, weekdaySessions = 3, weekendSessions = 6)

    @Test
    fun `base and bonus arrive apart`() {
        val t = time(home(rules(), listOf(Grant("a1b2c3d4", ada, "2026-01-06", 15, at))))
        // The grant is for another day, so today is base alone.
        assertEquals(60, t.getInt("baseMinutes"))
        assertEquals(0, t.getInt("bonusMinutes"))
        assertEquals(60, t.getInt("budgetMinutes"))
    }

    @Test
    fun `a grant today is visible as a grant, not folded into the limit`() {
        val today = io.yosemitekids.app.data.FamilyDay.of(at, java.time.ZoneId.of(zone))
        val t = time(home(rules(), listOf(Grant("a1b2c3d4", ada, today, 15, at))))
        assertEquals("the rules did not change", 60, t.getInt("baseMinutes"))
        assertEquals("a grown-up added this", 15, t.getInt("bonusMinutes"))
        assertEquals(75, t.getInt("budgetMinutes"))
        // And the page never has to divide: a quarter of the day was given.
        assertEquals(0.2, t.getDouble("bonusFraction"), 0.0001)
    }

    @Test
    fun `the seconds and the sentence describe the same amount of time`() {
        val t = time(home(rules()))
        val seconds = t.getLong("leftSeconds")
        assertEquals(
            "the pill is KidWords over the very seconds beside it",
            KidWords.timeLeft(seconds), t.getString("say")
        )
        assertEquals(seconds <= KidWords.LOW_SECONDS, t.getBoolean("low"))
    }

    @Test
    fun `a family with no rule gets no countdown at all`() {
        // Null is not "zero minutes left". A child with no limit is not a
        // child who has run out, and a bar drawn from a zero would be this
        // hub inventing a restriction nobody set.
        assertSame(JSONObject.NULL, home(Limits()).timeJson(ada, null))
    }

    @Test
    fun `everything in the block is consistent with everything else`() {
        val t = time(home(rules()))
        assertEquals(
            t.getInt("budgetMinutes"),
            t.getInt("baseMinutes") + t.getInt("bonusMinutes")
        )
        assertEquals(
            t.getInt("leftMinutes"),
            t.getInt("budgetMinutes") - t.getInt("spentMinutes")
        )
        assertEquals(t.getInt("leftMinutes").toLong(), t.getLong("leftSeconds") / 60L)
        assertTrue(t.getDouble("spentFraction") in 0.0..1.0)
    }
}
