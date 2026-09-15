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

    private fun home(blocked: Set<String> = emptySet()): HubKidHome {
        val dir = tmp.newFolder()
        val store = HubStore(dir)
        val index = ChannelIndex(File(dir, "search-index"))
        store.edit("test", T) {
            Whitelist(
                sources = listOf(apples, pears),
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
        return HubKidHome(policy, store, HubKidHistory(dir) { T }, HubSavedLists(dir)) { T }
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
}
