package io.yosemitekids.hub

import io.yosemitekids.app.data.CHANNEL_LAYOUT_POPULAR
import io.yosemitekids.app.data.CHANNEL_ORDER_ALPHA
import io.yosemitekids.app.data.CHANNEL_ORDER_ALPHA_DESC
import io.yosemitekids.app.data.CHANNEL_ORDER_WATCHED
import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.ScreeningStore
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
 * The parent's listing settings, honoured by the browser: "Channel row
 * order" as the Channels grid's default, "Channel page layout" as a
 * channel's order, and "Videos before Show more" as the page size on a
 * channel and on search — each through the same `:crawl` function the phone
 * uses, never a second rule. `SettingsSurface` declares them honoured by the
 * web; guard 69 holds the hub to reading them; this proves what it reads.
 */
class HubKidListingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val T = 1_788_771_600_000L
    private val leo = "aaaa1111"
    private val apples = WhitelistEntry("UC1", "https://youtube.com/channel/UC1", "Apples", SourceKind.CHANNEL)
    private val zebras = WhitelistEntry("UC2", "https://youtube.com/channel/UC2", "Zebras", SourceKind.CHANNEL)
    private val mangoes = WhitelistEntry("UC3", "https://youtube.com/channel/UC3", "Mangoes", SourceKind.CHANNEL)

    private lateinit var store: HubStore

    private fun home(config: (Whitelist) -> Whitelist = { it }): HubKidHome {
        val dir = tmp.newFolder()
        store = HubStore(dir)
        val index = ChannelIndex(File(dir, "search-index"))
        store.edit("test", T) {
            config(
                Whitelist(
                    sources = listOf(apples, zebras, mangoes),
                    blockedVideoIds = emptySet(),
                    homeZone = "Pacific/Auckland",
                    profiles = listOf(Profile(id = leo, name = "Leo"))
                )
            )
        }
        index.addVideos(
            "UC1",
            (1..12).map { i -> ChannelIndex.IndexedVideo("apple%05d".format(i), "Apple $i", "Apples", "", 600, "UC1") },
            complete = true
        )
        index.addVideos("UC2", listOf(ChannelIndex.IndexedVideo("zebra000001", "Zebra 1", "Zebras", "", 600, "UC2")), complete = true)
        index.addVideos("UC3", listOf(ChannelIndex.IndexedVideo("mango000001", "Mango 1", "Mangoes", "", 600, "UC3")), complete = true)
        val policy = HubPolicy(
            store,
            HubUsage(dir, homeZone = { "Pacific/Auckland" }, now = { T }),
            ScreeningStore(File(dir, "screening.json")),
            index
        ) { T }
        return HubKidHome(policy, store, HubKidHistory(dir) { T }, HubSavedLists(dir))
    }

    private fun names(arr: JSONArray): List<String> = (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
    private fun titles(arr: JSONArray): List<String> = (0 until arr.length()).map { arr.getJSONObject(it).getString("title") }

    @Test
    fun `the channels grid opens on the parent's row order, and the reply says which order it used`() {
        val zToA = home { it.copy(channelOrder = CHANNEL_ORDER_ALPHA_DESC) }.channels(leo, sort = null, seed = 1L)
        assertEquals(listOf("Zebras", "Mangoes", "Apples"), names(zToA.getJSONArray("channels")))
        assertEquals(CHANNEL_ORDER_ALPHA_DESC, zToA.getString("sort"))

        // An order this box cannot produce falls back to A to Z, honestly.
        val watched = home { it.copy(channelOrder = CHANNEL_ORDER_WATCHED) }.channels(leo, sort = null, seed = 1L)
        assertEquals(listOf("Apples", "Mangoes", "Zebras"), names(watched.getJSONArray("channels")))
        assertEquals(CHANNEL_ORDER_ALPHA, watched.getString("sort"))

        // A chip the child pressed still wins over the family default.
        val pressed = home { it.copy(channelOrder = CHANNEL_ORDER_ALPHA_DESC) }.channels(leo, sort = CHANNEL_ORDER_ALPHA, seed = 1L)
        assertEquals(listOf("Apples", "Mangoes", "Zebras"), names(pressed.getJSONArray("channels")))
    }

    @Test
    fun `a channel page goes through the phone's layout functions, and says honestly what it cannot order by`() {
        val newest = home().channel(leo, "UC1")!!
        assertEquals("index order is newest first", (1..12).map { "Apple $it" }, titles(newest.getJSONArray("videos")))

        // The index keeps no view counts, so "Popular first" is the same order
        // here - and SettingsSurface says so (honourWhy) rather than claiming
        // the browser honours it. This pins the honest half: nothing breaks,
        // nothing is invented.
        val popular = home { it.copy(channelLayout = CHANNEL_LAYOUT_POPULAR) }.channel(leo, "UC1")!!
        assertEquals((1..12).map { "Apple $it" }, titles(popular.getJSONArray("videos")))
    }

    @Test
    fun `a channel page and a search are paged by the parent's page size, and the page asks for the next`() {
        // 10 is one of PAGE_SIZES, which is what the config accepts.
        val paged = home { it.copy(pageSize = 10) }
        val first = paged.channel(leo, "UC1")!!
        assertEquals(10, first.getJSONArray("videos").length())
        assertEquals(12, first.getInt("count"))
        assertEquals(0, first.getInt("from"))
        assertTrue(first.getBoolean("more"))

        val second = paged.channel(leo, "UC1", from = 10)!!
        assertEquals(listOf("Apple 11", "Apple 12"), titles(second.getJSONArray("videos")))
        assertFalse(second.getBoolean("more"))

        val past = paged.channel(leo, "UC1", from = 99)!!
        assertEquals(0, past.getJSONArray("videos").length())
        assertFalse(past.getBoolean("more"))

        val search = paged.search(leo, "apple")
        assertEquals(10, search.getJSONArray("videos").length())
        assertTrue(search.getBoolean("more"))
        assertEquals(12, search.getInt("count"))

        // No page size set: the whole list, as on the phone.
        val whole = home().channel(leo, "UC1")!!
        assertEquals(12, whole.getJSONArray("videos").length())
        assertFalse(whole.getBoolean("more"))
    }
}
