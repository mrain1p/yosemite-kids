package io.yosemitekids.hub

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.PROFILE_COLORS
import io.yosemitekids.app.data.Pin
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.ScreeningStore
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Video
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import io.yosemitekids.app.ui.HOME_SHELVES
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a child's browser is shown.
 *
 * The load-bearing test here is [the browse filter is the play filter]: every
 * video that reaches a shelf is one the play route would allow, and every one
 * it would refuse on catalogue grounds is on no shelf at all. That equivalence
 * is the whole reason `HubKidHome` calls `HubPolicy.catalogueFor` instead of
 * assembling a list of its own — a shelf built from a looser filter is a child
 * tapping a card and being told no, which is the worst of both: they saw it,
 * and they cannot have it.
 *
 * Everything else here is the same claim in smaller pieces — a sibling's
 * channel, a blocked video, a clip under the minimum length — plus the two
 * things the payload carries that no other test would notice were missing: the
 * kid's own colours, and a countdown that agrees with the policy.
 */
class HubKidHomeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 2026-09-07T09:00:00Z — 21:00 Monday in Auckland, as HubPolicyTest uses. */
    private val clock = 1_788_771_600_000L

    private val leo = "aaaa1111"
    private val noa = "bbbb2222"

    private val science = WhitelistEntry("UC1", "https://youtube.com/channel/UC1", "SciShow Kids", SourceKind.CHANNEL)
    private val siblings = WhitelistEntry(
        "UC2", "https://youtube.com/channel/UC2", "Older Sibling TV", SourceKind.CHANNEL,
        profileIds = setOf(noa)
    )

    private lateinit var dir: File
    private lateinit var store: HubStore
    private lateinit var index: ChannelIndex
    private lateinit var history: HubKidHistory
    private lateinit var policy: HubPolicy
    private var at = clock

    private fun setUp(
        limits: Limits = Limits(),
        blocked: Set<String> = emptySet(),
        pins: List<Pin> = emptyList(),
        colour: Long = PROFILE_COLORS.first()
    ): HubKidHome {
        dir = tmp.newFolder()
        store = HubStore(dir)
        index = ChannelIndex(File(dir, "search-index"))
        history = HubKidHistory(dir) { at }
        store.edit("test", at) {
            Whitelist(
                sources = listOf(science, siblings),
                blockedVideoIds = blocked,
                homeZone = "Pacific/Auckland",
                pins = pins,
                profiles = listOf(
                    Profile(id = leo, name = "Leo", colorArgb = colour, limits = limits),
                    Profile(id = noa, name = "Noa")
                )
            )
        }
        index.addVideos(
            "UC1",
            listOf(
                video("mariokart01", "SciShow Kids", 600, "Mario Kart with dinosaurs"),
                video("volcanoes01", "SciShow Kids", 600, "How volcanoes work"),
                video("tinyclip001", "SciShow Kids", 20, "A very short clip")
            ),
            complete = true
        )
        index.addVideos(
            "UC2",
            listOf(video("teenstuff01", "Older Sibling TV", 600, "Not for Leo")),
            complete = true
        )
        policy = HubPolicy(
            store,
            HubUsage(dir, homeZone = { runCatching { store.load().homeZone }.getOrNull() }, now = { at }),
            ScreeningStore(File(dir, "screening.json")),
            index
        ) { at }
        return HubKidHome(policy, store, history)
    }

    private fun video(id: String, channel: String, seconds: Long, title: String) =
        ChannelIndex.IndexedVideo(
            id, title, channel, "https://i.ytimg.com/vi/$id/hq.jpg", seconds,
            if (channel == "SciShow Kids") "UC1" else "UC2"
        )

    // --- reading the payload ---------------------------------------------

    private fun ids(arr: JSONArray): List<String> =
        (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }

    /** Every video id on any shelf of this home, however it got there. */
    private fun everythingOn(home: JSONObject): Set<String> {
        val out = HashSet<String>()
        for (shelf in listOf("keepWatching", "suggested", "videos", "history")) {
            out += ids(home.getJSONArray(shelf))
        }
        return out
    }

    private fun watch(id: String, positionMs: Long, durationMs: Long, kid: String = leo) {
        history.save(kid, Video.watchUrl(id), positionMs, durationMs)
    }

    // --- the claim this file exists for ----------------------------------

    @Test
    fun `the browse filter is the play filter`() {
        val home = setUp(limits = Limits(minVideoMinutes = 1)).home(leo, null)
        val shown = everythingOn(home)
        // Everything the family's index holds, from both channels.
        val everyVideo = listOf("mariokart01", "volcanoes01", "tinyclip001", "teenstuff01")
        for (id in everyVideo) {
            val allowed = policy.mayPlay(leo, id).allowed
            assertEquals(
                "$id: the home ${if (id in shown) "shows" else "hides"} it and " +
                    "mayPlay ${if (allowed) "allows" else "refuses"} it. A shelf and " +
                    "the play route disagreeing is a child tapping a card and being told no.",
                allowed,
                id in shown
            )
        }
    }

    @Test
    fun `a sibling's channel is on no rail and no shelf`() {
        val home = setUp().home(leo, null)
        assertFalse("Noa's channel is a channel Leo may not see", "UC2" in ids(home.getJSONArray("channels")))
        assertFalse("nor its videos", "teenstuff01" in everythingOn(home))
        // And asking for it directly is the same 404 a missing channel gets.
        assertEquals(null, setUp().channel(leo, "UC2"))
    }

    @Test
    fun `a blocked video leaves every shelf at once`() {
        val home = setUp(blocked = setOf("volcanoes01")).home(leo, null)
        assertFalse("volcanoes01" in everythingOn(home))
        assertTrue("the rest of the channel stays", "mariokart01" in everythingOn(home))
    }

    @Test
    fun `a clip under the kid's minimum length is not offered`() {
        val home = setUp(limits = Limits(minVideoMinutes = 1)).home(leo, null)
        assertFalse("tinyclip001" in everythingOn(home))
    }

    // --- the shelves ------------------------------------------------------

    @Test
    fun `every shelf the shared catalogue knows is named in the payload`() {
        // The page draws by id. A shelf in HOME_SHELVES that the payload never
        // mentions is a row that silently does not exist in a browser.
        val sections = setUp().home(leo, null).getJSONArray("sections")
        val named = ids(sections)
        assertEquals(
            "the payload's shelves are :core's catalogue, in :core's order",
            HOME_SHELVES, named
        )
    }

    @Test
    fun `a part-watched video is on Keep watching and a finished one is not`() {
        val home = setUp()
        watch("mariokart01", positionMs = 120_000, durationMs = 600_000)
        watch("volcanoes01", positionMs = 599_000, durationMs = 600_000)
        val payload = home.home(leo, null)
        assertEquals(listOf("mariokart01"), ids(payload.getJSONArray("keepWatching")))
        // Both are history, though: watched is watched.
        assertTrue("volcanoes01" in ids(payload.getJSONArray("history")))
    }

    @Test
    fun `a finished video leaves the feed and a part-watched one keeps its bar`() {
        val home = setUp()
        watch("volcanoes01", positionMs = 599_000, durationMs = 600_000)
        watch("mariokart01", positionMs = 120_000, durationMs = 600_000)
        val feed = home.home(leo, null).getJSONArray("videos")
        assertFalse("a finished video is not still in the feed", "volcanoes01" in ids(feed))
        val mario = (0 until feed.length()).map { feed.getJSONObject(it) }
            .first { it.getString("id") == "mariokart01" }
        assertEquals(0.2, mario.getDouble("progress"), 0.01)
    }

    @Test
    fun `one browser's history is not another kid's`() {
        val home = setUp()
        watch("mariokart01", positionMs = 120_000, durationMs = 600_000, kid = noa)
        assertEquals(0, home.home(leo, null).getJSONArray("keepWatching").length())
    }

    @Test
    fun `the hero is the parent's pins, and only ones this kid can see`() {
        val home = setUp(
            pins = listOf(
                Pin(kidId = leo, sourceId = "UC1", rank = 10),
                // Noa's channel, pinned onto Leo's home by a config that should
                // never produce one. resolvePins joins against what this kid may
                // see, so it drops rather than becoming the biggest card on a
                // five-year-old's screen.
                Pin(kidId = leo, sourceId = "UC2", rank = 20)
            )
        ).home(leo, null)
        assertEquals(listOf("UC1"), ids(home.getJSONArray("pinned")))
    }

    // --- search ------------------------------------------------------------

    @Test
    fun `search finds what the kid asked for, and nothing off their list`() {
        val home = setUp()
        val hits = ids(home.search(leo, "mario").getJSONArray("videos"))
        assertEquals(listOf("mariokart01"), hits)
        // Their sibling's channel is not searchable either, however it is spelt.
        assertEquals(0, home.search(leo, "Not for Leo").getJSONArray("videos").length())
    }

    @Test
    fun `an empty query answers with nothing rather than everything`() {
        // A blank search box must not dump the whole catalogue as if it were a
        // result: the home screen is where everything lives.
        assertEquals(0, setUp().search(leo, "  ").getJSONArray("videos").length())
    }

    // --- what the payload carries that nothing else would notice ----------

    @Test
    fun `the kid's own colours ride along, and they are the app's`() {
        val colour = PROFILE_COLORS[2]
        val theme = setUp(colour = colour).home(leo, null).getJSONObject("theme")
        val expected = io.yosemitekids.app.ui.kidTinted(io.yosemitekids.app.ui.KID_DARK, colour.toInt())
        assertEquals(
            "the ground the browser paints is the ground kidTinted computes",
            io.yosemitekids.app.ui.Argb.css(expected.background),
            theme.getString("--yk-background")
        )
        // And the signal hues are re-derived FOR that ground, not copied from
        // the neutral one: a coral that carries text on the dark grey does not
        // necessarily carry it on a pink.
        assertNotNull(theme.getString("--yk-action"))
        assertTrue(
            "the action colour is legible on the kid's own ground",
            io.yosemitekids.app.ui.Argb.ratio(
                io.yosemitekids.app.ui.kidTokenRoles(expected.background).toMap().getValue("action"),
                expected.background
            ) >= 4.5
        )
    }

    @Test
    fun `the countdown is null without a budget and the policy's numbers with one`() {
        assertEquals(JSONObject.NULL, setUp().home(leo, null).get("time"))

        // A day's budget is sessions times their length, as everywhere else.
        // 2026-09-07 is a Monday, so the weekday count is the one that applies.
        val home = setUp(limits = Limits(sessionMinutes = 30, weekdaySessions = 2, weekendSessions = 2))
        val time = home.home(leo, null).getJSONObject("time")
        assertEquals(60, time.getInt("budgetMinutes"))
        assertEquals(60, time.getInt("leftMinutes"))
        // The same numbers the play route would refuse on — not a second count.
        val verdict = policy.timeFor(leo, null)
        assertEquals(verdict.budgetMinutes, time.getInt("budgetMinutes"))
        assertEquals(verdict.spentMinutes ?: 0, time.getInt("spentMinutes"))
    }

    @Test
    fun `a channel wears a video it may actually show`() {
        // The tile art is the newest video that survived every filter. A
        // blocked video becoming the picture on a channel's card would be the
        // one thing a parent removed, drawn as the biggest thing on the row.
        val home = setUp(blocked = setOf("mariokart01")).home(leo, null)
        val channel = (0 until home.getJSONArray("channels").length())
            .map { home.getJSONArray("channels").getJSONObject(it) }
            .first { it.getString("id") == "UC1" }
        assertFalse(
            "the blocked video is still the channel's poster",
            channel.getString("thumb").contains("mariokart01")
        )
    }
}
