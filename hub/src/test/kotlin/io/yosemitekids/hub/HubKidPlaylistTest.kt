package io.yosemitekids.hub

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.ScreeningStore
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The playlist surface the browser draws from the index: the strip on a
 * channel page, the See-all page and a playlist's own page, each showing a
 * kid exactly the videos the channel page would - a playlist is never a way
 * around a block, and a chip never opens onto less than it promised.
 */
class HubKidPlaylistTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val T = 1_788_771_600_000L
    private val leo = "aaaa1111"
    private val apples = WhitelistEntry("UC1", "https://youtube.com/channel/UC1", "Apples", SourceKind.CHANNEL)
    private val pears = WhitelistEntry("UC2", "https://youtube.com/channel/UC2", "Pears", SourceKind.CHANNEL)

    private fun pl(id: String, name: String, vararg ids: String) =
        ChannelIndex.IndexedPlaylist(id, "https://www.youtube.com/playlist?list=$id", name, null, ids.size.toLong(), ids.toList())

    private lateinit var history: HubKidHistory
    private lateinit var store: HubStore

    private fun home(blocked: Set<String> = emptySet(), picks: List<String> = emptyList()): HubKidHome {
        val dir = tmp.newFolder()
        store = HubStore(dir)
        val index = ChannelIndex(File(dir, "search-index"))
        store.edit("test", T) {
            Whitelist(
                sources = listOf(apples.copy(playlistIds = picks), pears),
                blockedVideoIds = blocked,
                homeZone = "Pacific/Auckland",
                profiles = listOf(Profile(id = leo, name = "Leo"))
            )
        }
        index.addVideos("UC1", (1..4).map { ChannelIndex.IndexedVideo("apple0000$it", "Apple $it", "Apples", "", 600, "UC1") }, complete = true)
        index.addVideos("UC2", listOf(ChannelIndex.IndexedVideo("pear000001", "Pear 1", "Pears", "", 600, "UC2")), complete = true)
        index.savePlaylists(
            "UC1",
            ChannelIndex.PlaylistListing(
                at = T, complete = true,
                playlists = listOf(
                    // Playlist order, not index order; one id the index never saw; one from the sibling channel.
                    pl("PL1", "Songs", "apple00003", "zzzzzzzzzzz", "apple00001", "pear000001"),
                    pl("PL2", "Stories", "apple00002"),
                    pl("PL3", "Nothing here", "zzzzzzzzzzz"),
                    ChannelIndex.IndexedPlaylist("PL4", "", "Not fetched yet", null, 9, videoIds = null)
                )
            )
        )
        val policy = HubPolicy(
            store,
            HubUsage(dir, homeZone = { "Pacific/Auckland" }, now = { T }),
            ScreeningStore(File(dir, "screening.json")),
            index
        ) { T }
        history = HubKidHistory(dir) { T }
        return HubKidHome(policy, store, history, HubSavedLists(dir)) { T }
    }

    private fun titles(arr: JSONArray) = (0 until arr.length()).map { arr.getJSONObject(it).getString("title") }
    private fun names(arr: JSONArray) = (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
    private fun counts(arr: JSONArray) = (0 until arr.length()).map { arr.getJSONObject(it).getInt("count") }

    @Test
    fun `the strip lists the playlists with something to show, counted for this kid`() {
        val page = home().channel(leo, "UC1")!!
        assertEquals(listOf("Songs", "Stories"), names(page.getJSONArray("playlists")))
        assertEquals("only the videos the kid may see are counted, from any of their channels", listOf(3, 1), counts(page.getJSONArray("playlists")))
        assertEquals(2, page.getInt("playlistCount"))
        val all = home().playlists(leo, "UC1")!!
        assertEquals(listOf("Songs", "Stories"), names(all.getJSONArray("playlists")))
        assertNull("a channel that is not the kid's is the same 404", home().playlists(leo, "UC9"))
        assertEquals("no listing yet: an empty strip, not an error", 0, home().channel(leo, "UC2")!!.getJSONArray("playlists").length())
    }

    @Test
    fun `a playlist page is the playlist's order, minus what the kid may not see`() {
        val songs = home().playlist(leo, "PL1")!!
        assertEquals("Songs", songs.getString("name"))
        assertEquals("Apples", songs.getString("channel"))
        assertEquals(listOf("Apple 3", "Apple 1", "Pear 1"), titles(songs.getJSONArray("videos")))
        assertEquals(3, songs.getInt("count"))
        // A block is a block here too: the page and its count both drop it.
        val blocked = home(blocked = setOf("apple00001"))
        assertEquals(listOf("Apple 3", "Pear 1"), titles(blocked.playlist(leo, "PL1")!!.getJSONArray("videos")))
        assertEquals(listOf(2, 1), counts(blocked.channel(leo, "UC1")!!.getJSONArray("playlists")))
        assertNull("empty and unfetched playlists are not pages", home().playlist(leo, "PL3"))
        assertNull(home().playlist(leo, "PL4"))
        assertNull(home().playlist(leo, "PLnope"))
    }

    @Test
    fun `the rows above the grid are the picks first, then the channel's own, trimmed like the phone trims them`() {
        // No picks: the channel's first playlists make the rows, in its order.
        val plain = home().channel(leo, "UC1")!!.getJSONArray("playlistRows")
        assertEquals(listOf("Songs", "Stories"), names(plain))
        assertEquals(listOf("Apple 3", "Apple 1", "Pear 1"), titles(plain.getJSONObject(0).getJSONArray("videos")))
        // A pick goes first; an unknown pick is skipped, not an error.
        val picked = home(picks = listOf("PLnope", "PL2")).channel(leo, "UC1")!!.getJSONArray("playlistRows")
        assertEquals(listOf("Stories", "Songs"), names(picked))
        // A finished video leaves its row; a row with nothing left is absent.
        val h = home()
        history.save(leo, "https://www.youtube.com/watch?v=apple00002", positionMs = 599_000, durationMs = 600_000)
        val rows = h.channel(leo, "UC1")!!.getJSONArray("playlistRows")
        assertEquals("Stories held only the finished one", listOf("Songs"), names(rows))
    }

    @Test
    fun `a row the parent added draws a playlist or a channel on the home, named and trimmed like a shelf`() {
        val h = home()
        val plain = h.home(leo, null)
        assertEquals("no rows added: nothing custom", 0, plain.getJSONObject("custom").length())
        // The parent adds Songs and the Pears channel to Leo's home, on the console or the phone.
        store.edit("test", T) { w ->
            w.copy(homeRows = io.yosemitekids.app.data.HomeRows.withOrder(
                emptyList(), leo,
                listOf(io.yosemitekids.app.ui.HomeSection(io.yosemitekids.app.ui.HomeRowKind.playlistRow("PL1")),
                    io.yosemitekids.app.ui.HomeSection(io.yosemitekids.app.ui.HomeRowKind.channelRow("UC2"))) +
                    io.yosemitekids.app.ui.HOME_SHELVES.map { io.yosemitekids.app.ui.HomeSection(it) }
            ))
        }
        val page = h.home(leo, null)
        val custom = page.getJSONObject("custom")
        assertEquals("Songs", custom.getJSONObject("playlist:PL1").getString("title"))
        assertEquals(listOf("Apple 3", "Apple 1", "Pear 1"), titles(custom.getJSONObject("playlist:PL1").getJSONArray("videos")))
        assertEquals("Pears", custom.getJSONObject("channel:UC2").getString("title"))
        assertEquals(listOf("Pear 1"), titles(custom.getJSONObject("channel:UC2").getJSONArray("videos")))
        // The section list carries the resolved name, so the page draws it without a second lookup.
        val sections = page.getJSONArray("sections")
        assertEquals("Songs", sections.getJSONObject(0).getString("title"))
        assertEquals("Pears", sections.getJSONObject(1).getString("title"))
    }
}
