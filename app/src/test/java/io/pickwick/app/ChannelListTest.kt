package io.pickwick.app

import io.pickwick.app.data.Profile
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.WhitelistEntry
import io.pickwick.app.ui.SourceFilter
import io.pickwick.app.ui.SourceSort
import io.pickwick.app.ui.filterSources
import io.pickwick.app.ui.sourceAudience
import io.pickwick.app.ui.sourceMetaTail
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Channels & playlists tabs, search and sort, and each row's meta line. */
class ChannelListTest {

    private val amelia = Profile("a1", "Amelia")
    private val ben = Profile("b2", "Ben")
    private val both = listOf(amelia, ben)

    private fun channel(id: String, name: String, kids: Set<String> = emptySet()) =
        WhitelistEntry(id, "https://www.youtube.com/channel/$id", name, SourceKind.CHANNEL, profileIds = kids)

    private fun playlist(id: String, name: String) =
        WhitelistEntry(id, "https://www.youtube.com/playlist?list=$id", name, SourceKind.PLAYLIST)

    private val all = listOf(
        channel("UC1", "SciShow Kids"),
        channel("UC2", "Crash Course Kids", setOf("a1")),
        playlist("PL3", "Bedtime stories"),
        channel("UC4", "Wild Kratts", setOf("gone")),
        channel("UC5", "Nat Geo Kids", setOf("a1", "b2"))
    )

    private fun ids(
        filter: SourceFilter,
        query: String = "",
        sort: SourceSort = SourceSort.RECENT,
        newIds: Set<String> = emptySet(),
        profiles: List<Profile> = both
    ) = filterSources(all, filter, query, sort, newIds, profiles) { it.label!! }.map { it.id }

    @Test
    fun recentlyAddedIsTheListReversed() {
        assertEquals(listOf("UC5", "UC4", "PL3", "UC2", "UC1"), ids(SourceFilter.All))
    }

    @Test
    fun aToZSortsByNameIgnoringCase() {
        assertEquals(listOf("PL3", "UC2", "UC5", "UC1", "UC4"), ids(SourceFilter.All, sort = SourceSort.ALPHA))
    }

    @Test
    fun kindTabs() {
        assertEquals(listOf("UC5", "UC4", "UC2", "UC1"), ids(SourceFilter.Channels))
        assertEquals(listOf("PL3"), ids(SourceFilter.Playlists))
    }

    @Test
    fun newTabIsTheSessionsAdditions() {
        assertEquals(listOf("UC2"), ids(SourceFilter.New, newIds = setOf("UC2")))
    }

    @Test
    fun kidTabIncludesSharedSources() {
        // Empty profileIds means everyone, so Amelia sees the shared ones too.
        assertEquals(listOf("UC5", "PL3", "UC2", "UC1"), ids(SourceFilter.Kid("a1")))
        assertEquals(listOf("UC5", "PL3", "UC1"), ids(SourceFilter.Kid("b2")))
    }

    @Test
    fun everyoneIsSharedOrAllKidsNamed() {
        assertEquals(listOf("UC5", "PL3", "UC1"), ids(SourceFilter.Everyone))
    }

    @Test
    fun nobodyIsReservedForKidsWhoNoLongerExist() {
        assertEquals(listOf("UC4"), ids(SourceFilter.Nobody))
        // Only an assignment can strand a source; the shared default never does.
        assertEquals(emptyList<String>(), ids(SourceFilter.Nobody, profiles = listOf(amelia, ben, Profile("gone", "Cara"))))
    }

    @Test
    fun searchMatchesNameOrIdCaseInsensitively() {
        assertEquals(listOf("UC5", "UC2", "UC1"), ids(SourceFilter.All, query = "kids"))
        assertEquals(listOf("PL3"), ids(SourceFilter.All, query = "pl3"))
        assertEquals(emptyList<String>(), ids(SourceFilter.Playlists, query = "kids"))
    }

    @Test
    fun audienceReadsAsTheRowSpellsIt() {
        assertEquals("Everyone", sourceAudience(channel("x", "X"), both))
        assertEquals("Amelia", sourceAudience(channel("x", "X"), listOf(amelia)))
        assertEquals("Amelia", sourceAudience(channel("x", "X", setOf("a1")), both))
        assertEquals("Everyone", sourceAudience(channel("x", "X", setOf("a1", "b2")), both))
        assertEquals("Nobody", sourceAudience(channel("x", "X", setOf("gone")), both))
        // A one-kid family names the kid even when the id set is stale-plus-live.
        assertEquals("Amelia", sourceAudience(channel("x", "X", setOf("a1", "gone")), listOf(amelia)))
    }

    @Test
    fun justAddedWinsTheMetaLine() {
        assertEquals("just added", sourceMetaTail(channel("x", "X", setOf("a1")), both, isNew = true))
        assertEquals("Amelia", sourceMetaTail(channel("x", "X", setOf("a1")), both, isNew = false))
    }
}
