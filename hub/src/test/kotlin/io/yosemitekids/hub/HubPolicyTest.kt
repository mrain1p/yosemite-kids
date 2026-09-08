package io.yosemitekids.hub

import io.yosemitekids.app.data.AiConfig
import io.yosemitekids.app.data.AiScreener
import io.yosemitekids.app.data.BUDGET_SCOPE_SHARED
import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.Grant
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.ScreeningStore
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.TimeWindow
import io.yosemitekids.app.data.UsageLedger
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The one answer this box gives about whether a child may play something.
 *
 * Every branch is here, and the fail-closed one twice over: with no
 * `Whitelist.homeZone` the hub does not know what day it is *here*, so a kid
 * with a bedtime or a budget is **refused with a reason a parent can act on**
 * rather than quietly allowed. Those cases assert two things — the refusal,
 * and that nothing was resolved: no day, no minutes, no budget. A refusal that
 * had a day on it would be a container reading its own calendar, which is the
 * whole of guard 27 and the difference between right and thirteen hours wrong
 * for a family in Auckland.
 */
class HubPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * 2026-09-07T09:00:00Z. In Pacific/Auckland that is 21:00 on Monday the
     * 7th; in Europe/London, 10:00 on the same day. Both spellings are used
     * below, because "which day is it" and "is it bedtime" have to come from
     * the family's zone and not the box's.
     */
    private val clock = 1_788_771_600_000L

    private val ai = AiConfig(enabled = true, model = "m", rules = "no scary", rulesVersion = 3)

    private val leoId = "aaaa1111"
    private val noaId = "bbbb2222"

    private val kids = WhitelistEntry("UC1", "https://youtube.com/channel/UC1", "SciShow Kids", SourceKind.CHANNEL)
    private val teen = WhitelistEntry(
        "UC2", "https://youtube.com/channel/UC2", "Older Sibling TV", SourceKind.CHANNEL,
        profileIds = setOf(noaId)
    )

    private lateinit var dir: File
    private lateinit var store: HubStore
    private lateinit var index: ChannelIndex
    private lateinit var screening: ScreeningStore
    private var at = clock

    private fun setUp(
        limits: Limits = Limits(),
        homeZone: String? = "Pacific/Auckland",
        ai: AiConfig = AiConfig(),
        blocked: Set<String> = emptySet(),
        blockedFor: Map<String, Set<String>> = emptyMap(),
        allowedFor: Map<String, Set<String>> = emptyMap(),
        grants: List<Grant> = emptyList(),
        sources: List<WhitelistEntry> = listOf(kids, teen)
    ): HubPolicy {
        dir = tmp.newFolder()
        store = HubStore(dir)
        index = ChannelIndex(File(dir, "search-index"))
        screening = ScreeningStore(File(dir, "screening.json"))
        store.edit("test", at) {
            Whitelist(
                sources = sources,
                blockedVideoIds = blocked,
                blockedFor = blockedFor,
                allowedFor = allowedFor,
                ai = ai,
                homeZone = homeZone,
                grants = grants,
                profiles = listOf(
                    Profile(id = leoId, name = "Leo", limits = limits),
                    Profile(id = noaId, name = "Noa")
                )
            )
        }
        index.addVideos(
            "UC1",
            listOf(
                video("vvvvvvvvvv1", "SciShow Kids", 600),
                video("vvvvvvvvvv2", "SciShow Kids", 45)
            ),
            complete = true
        )
        index.addVideos("UC2", listOf(video("vvvvvvvvvv3", "Older Sibling TV", 600)), complete = true)
        return policy()
    }

    private fun policy() = HubPolicy(
        store,
        HubUsage(dir, homeZone = { runCatching { store.load().homeZone }.getOrNull() }, now = { at }),
        screening,
        index
    ) { at }

    private fun usage() =
        HubUsage(dir, homeZone = { runCatching { store.load().homeZone }.getOrNull() }, now = { at })

    private fun video(id: String, channel: String, seconds: Long) =
        ChannelIndex.IndexedVideo(id, "A video", channel, null, seconds, if (channel == "SciShow Kids") "UC1" else "UC2")

    /** Minutes a device reports for Leo today, straight into the hub's ledger. */
    private fun spend(deviceId: String, minutes: Int, kid: String? = leoId, day: String = "2026-09-07") {
        val body = UsageLedger.toJson(
            UsageLedger.withOwn(UsageLedger.Ledger.EMPTY, kid, day, deviceId, minutes)
        )
        assertTrue("the ledger refused the fixture", usage().merge(body, deviceId) >= 0)
    }

    // --- the catalogue half ---------------------------------------------

    @Test
    fun `a kid with no rules at all may play what is on their list`() {
        val d = setUp().mayPlay(leoId, "vvvvvvvvvv1")
        assertTrue(d.detail, d.allowed)
        assertEquals(HubPolicy.OK, d.reason)
    }

    @Test
    fun `a video nobody has indexed is refused, and said to be unknown`() {
        val d = setUp().mayPlay(leoId, "nosuchvideo")
        assertFalse(d.allowed)
        assertEquals(HubPolicy.UNKNOWN_VIDEO, d.reason)
    }

    @Test
    fun `a channel that is not on this kid's list is refused as theirs, not as unknown`() {
        // The distinction a parent actually needs: "you have not added this"
        // and "this is your sister's" are different problems with different
        // fixes, and one code for both sends them looking in the wrong place.
        val p = setUp()
        assertEquals(HubPolicy.NOT_FOR_THIS_KID, p.mayPlay(leoId, "vvvvvvvvvv3").reason)
        assertTrue(p.mayPlay(noaId, "vvvvvvvvvv3").allowed)
    }

    @Test
    fun `a blocked video is refused, family-wide and per kid`() {
        assertEquals(
            HubPolicy.BLOCKED,
            setUp(blocked = setOf("vvvvvvvvvv1")).mayPlay(leoId, "vvvvvvvvvv1").reason
        )
        val perKid = setUp(blockedFor = mapOf("vvvvvvvvvv1" to setOf(leoId)))
        assertEquals(HubPolicy.BLOCKED, perKid.mayPlay(leoId, "vvvvvvvvvv1").reason)
        assertTrue("a sibling's block must not reach Noa", perKid.mayPlay(noaId, "vvvvvvvvvv1").allowed)
    }

    @Test
    fun `a video under the minimum length is refused, and an unknown length is not`() {
        val p = setUp(limits = Limits(minVideoMinutes = 5))
        assertEquals(HubPolicy.TOO_SHORT, p.mayPlay(leoId, "vvvvvvvvvv2").reason)
        assertTrue(p.mayPlay(leoId, "vvvvvvvvvv1").allowed)
    }

    @Test
    fun `with screening on, an unscreened video is hidden and a parent override is not`() {
        assertEquals(
            HubPolicy.NOT_SCREENED,
            setUp(ai = ai).mayPlay(leoId, "vvvvvvvvvv1").reason
        )
        // The parent's own allow-override, per kid, reaches this box through
        // the same Whitelist.allowedIdsFor the app uses.
        val overridden = setUp(ai = ai, allowedFor = mapOf("vvvvvvvvvv1" to setOf(leoId)))
        assertTrue(overridden.mayPlay(leoId, "vvvvvvvvvv1").allowed)
        assertEquals(HubPolicy.NOT_SCREENED, overridden.mayPlay(noaId, "vvvvvvvvvv1").reason)
    }

    private fun allow(rulesVersion: Int) = mapOf(
        "vvvvvvvvvv1" to ScreeningStore.Entry(
            verdict = AiScreener.Verdict.ALLOW, reason = "fine", title = "A video",
            channel = "SciShow Kids", thumb = null, rulesVersion = rulesVersion, at = at
        )
    )

    @Test
    fun `a current-rules ALLOW verdict lets the video through`() {
        val p = setUp(ai = ai)
        screening.putAll(allow(rulesVersion = 3))
        assertTrue(p.mayPlay(leoId, "vvvvvvvvvv1").allowed)
    }

    @Test
    fun `a verdict from an older rules version is not a verdict`() {
        // Its own test rather than a second half of the one above: the two
        // entries differ only in one digit, so overwriting in place is a file
        // of the same length written in the same millisecond — and
        // ScreeningStore's cache is keyed on exactly that.
        val p = setUp(ai = ai)
        screening.putAll(allow(rulesVersion = 2))
        assertEquals(HubPolicy.NOT_SCREENED, p.mayPlay(leoId, "vvvvvvvvvv1").reason)
    }

    // --- the clock half --------------------------------------------------

    @Test
    fun `a parent's pause is honoured with no home zone at all`() {
        // Deliberately outside the zone gate: a pause is an instant compared
        // against an instant and needs no calendar, so a hub that refused to
        // honour one for want of a timezone would be failing OPEN on the
        // strictest rule the app has.
        val d = setUp(limits = Limits(pausedUntilMillis = clock + 60_000), homeZone = null)
            .mayPlay(leoId, "vvvvvvvvvv1")
        assertFalse(d.allowed)
        assertEquals(HubPolicy.PAUSED, d.reason)
        assertNull("a pause is not a day", d.day)
    }

    @Test
    fun `a bedtime is evaluated in the family's zone, not the container's`() {
        // 21:00–07:00. The container is on 09:00 UTC; Auckland is 21:00 and
        // inside the window, London is 10:00 and nowhere near it. A hub that
        // read its own clock would let a child in Auckland watch through
        // bedtime every night and nothing would throw.
        val bedtime = Limits(
            windows = listOf(TimeWindow(id = "b", label = "Bedtime", startMin = 21 * 60, endMin = 7 * 60))
        )
        val nz = setUp(limits = bedtime, homeZone = "Pacific/Auckland").mayPlay(leoId, "vvvvvvvvvv1")
        assertFalse(nz.allowed)
        assertEquals(HubPolicy.WINDOW, nz.reason)
        assertEquals("2026-09-07", nz.day)
        assertTrue(setUp(limits = bedtime, homeZone = "Europe/London").mayPlay(leoId, "vvvvvvvvvv1").allowed)
    }

    @Test
    fun `a kid out of minutes is refused, with the numbers it was judged on`() {
        // 2 sittings of 30 = 60 minutes. Auckland's day is 2026-09-07.
        val p = setUp(limits = Limits(sessionMinutes = 30, weekdaySessions = 2, weekendSessions = 2))
        spend("dev-tv", 59)
        val nearly = p.mayPlay(leoId, "vvvvvvvvvv1")
        assertTrue(nearly.detail, nearly.allowed)
        assertEquals(59, nearly.spentMinutes)
        assertEquals(60, nearly.budgetMinutes)

        spend("dev-tv", 60)
        val out = p.mayPlay(leoId, "vvvvvvvvvv1")
        assertFalse(out.allowed)
        assertEquals(HubPolicy.OUT_OF_TIME, out.reason)
        assertEquals(60, out.spentMinutes)
        assertEquals("2026-09-07", out.day)
    }

    @Test
    fun `a parent's grant for today raises the budget here too`() {
        val p = setUp(
            limits = Limits(sessionMinutes = 30, weekdaySessions = 2, weekendSessions = 2),
            // Eight hex, because that is the shape Profile.newId mints and the
            // shape ConfigJson validates on the way back in. A toy id like
            // "g1" round-trips to nothing: grantsFromJson drops a malformed
            // entry on purpose — one bad grant from a newer build must not
            // cost a family its whole config — so the grant simply is not
            // there when it is read back, and the budget silently stays at
            // its unbonused value. Which is what this test caught.
            grants = listOf(Grant(id = "a1b2c3d4", kidId = leoId, date = "2026-09-07", minutes = 20, at = clock))
        )
        spend("dev-tv", 65)
        val d = p.mayPlay(leoId, "vvvvvvvvvv1")
        assertTrue(d.detail, d.allowed)
        assertEquals(80, d.budgetMinutes)
    }

    @Test
    fun `under the default scope a browser is judged on browsers' minutes, not the television's`() {
        val p = setUp(limits = Limits(sessionMinutes = 30, weekdaySessions = 2, weekendSessions = 2))
        spend("dev-tv", 90)
        val web = UsageLedger.WEB_PREFIX + "abc123abc123"
        val d = p.mayPlay(leoId, "vvvvvvvvvv1", viewer = web)
        assertTrue("the TV's minutes are not this viewer's under a per-device budget", d.allowed)
        assertEquals(0, d.spentMinutes)

        // …and a rotated browser id buys nothing, because browsers count as a
        // group. This is the hole a per-device budget would otherwise have:
        // a fresh identity every morning is a fresh budget every morning.
        spend(web, 60)
        val rotated = p.mayPlay(leoId, "vvvvvvvvvv1", viewer = UsageLedger.WEB_PREFIX + "999999999999")
        assertFalse(rotated.allowed)
        assertEquals(HubPolicy.OUT_OF_TIME, rotated.reason)
    }

    @Test
    fun `under a shared budget every device counts, browser included`() {
        val p = setUp(
            limits = Limits(
                sessionMinutes = 30, weekdaySessions = 2, weekendSessions = 2,
                budgetScope = BUDGET_SCOPE_SHARED
            )
        )
        spend("dev-tv", 60)
        val d = p.mayPlay(leoId, "vvvvvvvvvv1", viewer = UsageLedger.WEB_PREFIX + "abc123abc123")
        assertFalse(d.allowed)
        assertEquals(HubPolicy.OUT_OF_TIME, d.reason)
        assertEquals(60, d.spentMinutes)
    }

    @Test
    fun `a scope this build does not know behaves as per-device`() {
        val p = setUp(
            limits = Limits(
                sessionMinutes = 30, weekdaySessions = 2, weekendSessions = 2,
                budgetScope = "school-nights"
            )
        )
        spend("dev-tv", 90)
        assertTrue(p.mayPlay(leoId, "vvvvvvvvvv1", viewer = UsageLedger.WEB_PREFIX + "abc").allowed)
    }

    @Test
    fun `with no viewer named, a per-device budget is judged on the family's whole total`() {
        // The strictest reading this box can stand behind. Counting nothing
        // would hand out time the rules never allowed, and inventing time is
        // the one direction this must not fail in.
        val p = setUp(limits = Limits(sessionMinutes = 30, weekdaySessions = 2, weekendSessions = 2))
        spend("dev-tv", 60)
        val d = p.mayPlay(leoId, "vvvvvvvvvv1")
        assertFalse(d.allowed)
        assertEquals(HubPolicy.OUT_OF_TIME, d.reason)
    }

    // --- failing closed, and saying why ----------------------------------

    @Test
    fun `no home zone and a bedtime is a refusal that names the fix, and resolves nothing`() {
        val d = setUp(
            limits = Limits(
                windows = listOf(TimeWindow(id = "b", label = "Bedtime", startMin = 21 * 60, endMin = 7 * 60))
            ),
            homeZone = null
        ).mayPlay(leoId, "vvvvvvvvvv1")
        assertFalse(d.allowed)
        assertEquals(HubPolicy.NEEDS_HOME_ZONE, d.reason)
        assertTrue("the refusal must name the fix", d.detail.contains("timezone"))
        assertNull("a day was resolved anyway", d.day)
        assertNull("minutes were resolved anyway", d.spentMinutes)
        assertNull("a budget was resolved anyway", d.budgetMinutes)
    }

    @Test
    fun `no home zone and a budget is the same refusal, and resolves nothing`() {
        val d = setUp(
            limits = Limits(sessionMinutes = 30, weekdaySessions = 2, weekendSessions = 2),
            homeZone = null
        ).mayPlay(leoId, "vvvvvvvvvv1")
        assertEquals(HubPolicy.NEEDS_HOME_ZONE, d.reason)
        assertNull(d.day)
        assertNull(d.spentMinutes)
        assertNull(d.budgetMinutes)
    }

    @Test
    fun `a zone the JVM cannot resolve is no zone at all`() {
        // A typo on a phone must not become a calendar this box believes.
        val d = setUp(
            limits = Limits(sessionMinutes = 30, weekdaySessions = 2, weekendSessions = 2),
            homeZone = "Pacific/Aukland"
        ).mayPlay(leoId, "vvvvvvvvvv1")
        assertEquals(HubPolicy.NEEDS_HOME_ZONE, d.reason)
        assertNull(d.day)
    }

    @Test
    fun `a kid with no time rules is allowed with no home zone, because nothing needs a day`() {
        val d = setUp(homeZone = null).mayPlay(leoId, "vvvvvvvvvv1")
        assertTrue(d.detail, d.allowed)
        assertNull("nothing may resolve a day this hub cannot name", d.day)
        assertNull(d.spentMinutes)
    }

    @Test
    fun `an incomplete rule is not a budget, so it does not force a home zone`() {
        // A session length with no session count is a rule the settings screen
        // never finished collecting. Treating it as a budget would refuse a
        // family that has set nothing enforceable.
        val d = setUp(limits = Limits(sessionMinutes = 30), homeZone = null)
            .mayPlay(leoId, "vvvvvvvvvv1")
        assertTrue(d.detail, d.allowed)
    }

    @Test
    fun `every reason code is its own`() {
        // Two branches sharing a code is a caller that cannot tell them apart,
        // and the one that matters is NEEDS_HOME_ZONE: it is the only code
        // meaning "this hub cannot answer" rather than "no".
        val codes = listOf(
            HubPolicy.OK, HubPolicy.NO_CONFIG, HubPolicy.UNKNOWN_VIDEO, HubPolicy.NOT_FOR_THIS_KID,
            HubPolicy.BLOCKED, HubPolicy.TOO_SHORT, HubPolicy.NOT_SCREENED,
            HubPolicy.NEEDS_HOME_ZONE, HubPolicy.PAUSED, HubPolicy.WINDOW, HubPolicy.OUT_OF_TIME
        )
        assertEquals(codes.size, codes.toSet().size)
    }
}
