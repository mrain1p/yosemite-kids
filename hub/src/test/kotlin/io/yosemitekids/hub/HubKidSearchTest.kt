package io.yosemitekids.hub

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.ScreeningStore
import io.yosemitekids.app.data.SearchOrder
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The two kid surfaces that reached the browser in 1.9.0, both answered by
 * the hub with the phone's own functions: the search page's order chips and
 * recents (SearchOrder, RecentSearches) and a channel's Watched list
 * (orderByWatched, KidHome.FINISHED_FRACTION). The page draws what it is
 * handed; this proves what it is handed.
 */
class HubKidSearchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var t = 1_788_771_600_000L
    private val DAY = 86_400_000L
    private val leo = "aaaa1111"
    private val apples = WhitelistEntry("UC1", "https://youtube.com/channel/UC1", "Apples", SourceKind.CHANNEL)

    private lateinit var store: HubStore
    private lateinit var history: HubKidHistory
    private lateinit var searches: HubKidSearches

    private fun url(id: String) = "https://www.youtube.com/watch?v=$id"

    private fun home(): HubKidHome {
        val dir = tmp.newFolder()
        store = HubStore(dir)
        val index = ChannelIndex(File(dir, "search-index"))
        history = HubKidHistory(dir) { t }
        searches = HubKidSearches(dir)
        store.edit("test", t) {
            Whitelist(
                sources = listOf(apples),
                blockedVideoIds = emptySet(),
                homeZone = "Pacific/Auckland",
                profiles = listOf(Profile(id = leo, name = "Leo"))
            )
        }
        // Three apples: a long old one, a short new one, an undated middle one.
        index.addVideos(
            "UC1",
            listOf(
                ChannelIndex.IndexedVideo("apple000001", "Apple pie", "Apples", "", 900, "UC1", publishedAt = t - 30 * DAY),
                ChannelIndex.IndexedVideo("apple000002", "Apple juice", "Apples", "", 120, "UC1", publishedAt = t - DAY),
                ChannelIndex.IndexedVideo("apple000003", "Apple tree", "Apples", "", 300, "UC1")
            ),
            complete = true
        )
        val policy = HubPolicy(
            store,
            HubUsage(dir, homeZone = { "Pacific/Auckland" }, now = { t }),
            ScreeningStore(File(dir, "screening.json")),
            index
        ) { t }
        return HubKidHome(policy, store, history, HubSavedLists(dir), searches) { t }
    }

    private fun titles(arr: JSONArray): List<String> = (0 until arr.length()).map { arr.getJSONObject(it).getString("title") }
    private fun strings(arr: JSONArray): List<String> = (0 until arr.length()).map { arr.getString(it) }

    @Test
    fun `the order chips are the phone's, in the hub's words, and the hub does the ordering`() {
        val h = home()
        val best = h.search(leo, "apple")
        assertEquals(SearchOrder.BEST, best.getString("order"))
        val chips = best.getJSONArray("orders").let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).getString("id") to arr.getJSONObject(it).getString("label") } }
        assertEquals(SearchOrder.ALL.map { it to SearchOrder.label(it) }, chips)

        val newest = h.search(leo, "apple", order = SearchOrder.RECENT)
        assertEquals("newest first, the undated one last", listOf("Apple juice", "Apple pie", "Apple tree"), titles(newest.getJSONArray("videos")))
        val shortest = h.search(leo, "apple", order = SearchOrder.SHORT)
        assertEquals(listOf("Apple juice", "Apple tree", "Apple pie"), titles(shortest.getJSONArray("videos")))
        // A chip from a newer build falls back to relevance, never to a blank page.
        assertEquals(SearchOrder.BEST, h.search(leo, "apple", order = "loudest").getString("order"))
        assertEquals(3, h.search(leo, "apple", order = "loudest").getJSONArray("videos").length())
    }

    @Test
    fun `a search joins the recents only when the child meant it, and the x forgets one`() {
        val h = home()
        // Keystrokes: not remembered.
        h.search(leo, "a")
        h.search(leo, "ap", remember = false)
        assertEquals(emptyList<String>(), strings(h.search(leo, "").getJSONArray("recent")))
        // Enter: remembered, once, newest first, and page two of it changes nothing.
        h.search(leo, "apple", remember = true)
        h.search(leo, "Apple", remember = true, from = 10)
        h.search(leo, "juice", remember = true)
        assertEquals(listOf("juice", "apple"), strings(h.search(leo, "").getJSONArray("recent")))
        assertEquals("a search that finds nothing is still one the child meant", listOf("dragons", "juice", "apple"),
            strings(h.search(leo, "dragons", remember = true).getJSONArray("recent")))
        assertEquals(listOf("dragons", "apple"), h.forgetSearch(leo, "Juice"))
        assertEquals(emptyList<String>(), h.clearSearches(leo))
        // Another kid's list is their own.
        assertEquals(emptyList<String>(), strings(h.search("bbbb2222", "").getJSONArray("recent")))
    }

    @Test
    fun `a channel's Watched list is its finished videos, newest-watched first, and the page carries the count`() {
        val h = home()
        val before = h.channel(leo, "UC1")!!
        assertEquals(0, before.getInt("watchedCount"))
        assertFalse(before.getBoolean("watched"))
        // Pie finished yesterday, juice finished today, tree half-watched.
        t -= DAY
        history.save(leo, url("apple000001"), positionMs = 895_000, durationMs = 900_000)
        t += DAY
        history.save(leo, url("apple000002"), positionMs = 119_000, durationMs = 120_000)
        history.save(leo, url("apple000003"), positionMs = 150_000, durationMs = 300_000)
        val page = h.channel(leo, "UC1")!!
        assertEquals(2, page.getInt("watchedCount"))
        assertEquals("the ordinary page still lists every video", 3, page.getJSONArray("videos").length())
        val watched = h.channel(leo, "UC1", onlyWatched = true)!!
        assertTrue(watched.getBoolean("watched"))
        assertEquals(listOf("Apple juice", "Apple pie"), titles(watched.getJSONArray("videos")))
        assertEquals(2, watched.getInt("count"))
    }
}
