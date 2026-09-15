package io.yosemitekids.app

import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.ConfigMerge
import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.HomeRow
import io.yosemitekids.app.data.HomeRows
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.SyncAction
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import io.yosemitekids.app.data.syncAction
import io.yosemitekids.app.ui.HOME_SHELVES
import io.yosemitekids.app.ui.HomeSection
import io.yosemitekids.app.ui.HomeShelf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home screen's rows as a config field: the four canonical tests from
 * the sync skill, the editor's one function, and the merge cases the pinned
 * hero already answers for its own shape.
 */
class HomeRowsConfigTest {

    private val T = 1_780_000_000_000L
    private val leo = Profile(id = "k1", name = "Leo")
    private val mia = Profile(id = "k2", name = "Mia")
    private val family = Whitelist(
        sources = listOf(WhitelistEntry("UCaaa", "https://www.youtube.com/channel/UCaaa", "A", SourceKind.CHANNEL)),
        blockedVideoIds = emptySet(),
        profiles = listOf(leo, mia)
    )

    private fun row(id: String, rank: Int, kid: String? = "k1", on: Boolean = true) = HomeRow(kid, id, rank, on)
    private fun doc(w: Whitelist): String = JSONObject(ConfigJson.toJson(w)).put("updatedAt", T).toString(2)
    private fun stamp(previous: Whitelist, base: Whitelist, next: Whitelist, at: Long, by: String = "mum") =
        ConfigStamp.stamped(previous, base, next, at, by, "phone").config
    private fun settle(local: String, incoming: String): String = ConfigMerge.merge(local, incoming).merged ?: local
    private fun sections(w: Whitelist, kid: String?) = w.homeRowsFor(kid).map { it.id to it.enabled }

    // --- the gate between the two releases ------------------------------------

    @Test
    fun `no rows is byte-identical and hash-identical to an empty list, and reads as the default layout`() {
        val never = family
        val emptied = family.copy(homeRows = emptyList())
        assertEquals(doc(never), doc(emptied))
        assertEquals(ConfigJson.fingerprint(never), ConfigJson.fingerprint(emptied))
        assertFalse(doc(never).contains("\"rows\""))
        assertEquals(HOME_SHELVES.map { it to true }, sections(never, "k1"))
        listOf("""{"rows":[]}""", "{}").forEach { home ->
            val parsed = ConfigJson.fromJson(JSONObject(doc(never)).put("home", JSONObject(home)).toString())
            assertTrue(parsed.homeRows.isEmpty())
            assertEquals("[home=$home]", doc(never), doc(parsed))
            assertEquals(
                SyncAction.Nothing,
                syncAction(
                    localHash = ConfigJson.fingerprint(never), localSyncHash = ConfigMerge.syncHash(never.sync), localAt = T,
                    remoteHash = ConfigJson.fingerprint(parsed), remoteSyncHash = ConfigMerge.syncHash(parsed.sync),
                    remoteSyncV = parsed.sync.v, remoteAt = T
                )
            )
        }
    }

    @Test
    fun `an arranged row moves the hash, and so do a reorder and a toggle`() {
        val one = family.copy(homeRows = listOf(row(HomeShelf.HISTORY, 100)))
        val moved = family.copy(homeRows = listOf(row(HomeShelf.HISTORY, 50)))
        val off = family.copy(homeRows = listOf(row(HomeShelf.HISTORY, 100, on = false)))
        assertNotEquals(ConfigJson.fingerprint(family), ConfigJson.fingerprint(one))
        assertNotEquals(ConfigJson.fingerprint(one), ConfigJson.fingerprint(moved))
        assertNotEquals(ConfigJson.fingerprint(one), ConfigJson.fingerprint(off))
        val two = one.copy(homeRows = one.homeRows + row(HomeShelf.CHANNELS, 200))
        assertEquals("memory order is not the hash; ranks are", ConfigJson.fingerprint(two), ConfigJson.fingerprint(two.copy(homeRows = two.homeRows.reversed())))
    }

    // --- the wire ------------------------------------------------------------

    @Test
    fun `rows round-trip in canonical order, the family's kid omitted and on written only when off`() {
        val w = family.copy(homeRows = listOf(row(HomeShelf.VIDEOS, 200, on = false), row(HomeShelf.HISTORY, 100), row(HomeShelf.CHANNELS, 100, kid = null)))
        val json = ConfigJson.toJson(w)
        val expected = listOf(row(HomeShelf.CHANNELS, 100, kid = null), row(HomeShelf.HISTORY, 100), row(HomeShelf.VIDEOS, 200, on = false))
        assertEquals(expected, ConfigJson.fromJson(json).homeRows)
        assertEquals(doc(w), doc(w.copy(homeRows = w.homeRows.reversed())))
        val arr = JSONObject(json).getJSONObject("home").getJSONArray("rows")
        assertFalse(arr.getJSONObject(0).has("kid"))
        assertFalse("on is the default and writes nothing", arr.getJSONObject(1).has("on"))
        assertEquals(false, arr.getJSONObject(2).getBoolean("on"))
        // A row a build cannot read drops alone; the rest survive.
        val damaged = JSONObject(json)
        damaged.getJSONObject("home").getJSONArray("rows").put(JSONObject().put("id", "x|y").put("rank", 1))
        assertEquals(3, ConfigJson.fromJson(damaged.toString()).homeRows.size)
    }

    @Test
    fun `an unknown shelf is carried and not drawn, and a new one the saved order never named is appended on`() {
        val w = family.copy(homeRows = listOf(row("future-shelf", 50), row(HomeShelf.HISTORY, 100), row(HomeShelf.CHANNELS, 200, on = false)))
        assertEquals(3, ConfigJson.fromJson(ConfigJson.toJson(w)).homeRows.size)
        val drawn = sections(w, "k1")
        assertEquals(HomeShelf.HISTORY to true, drawn[0])
        assertEquals(HomeShelf.CHANNELS to false, drawn[1])
        assertTrue(drawn.none { it.first == "future-shelf" })
        assertEquals(HOME_SHELVES.size, drawn.size)
        // A kid with no rows of their own, and a viewer nobody knows, get the family's.
        assertEquals(HOME_SHELVES.map { it to true }, sections(w, "k2"))
        val familyRow = family.copy(homeRows = listOf(row(HomeShelf.HISTORY, 1, kid = null)))
        assertEquals(HomeShelf.HISTORY, sections(familyRow, "nobody")[0].first)
        assertEquals(HomeShelf.HISTORY, sections(familyRow, null)[0].first)
    }

    // --- the editor's one function ----------------------------------------------

    @Test
    fun `withOrder mints as few ranks as it can, drops what the catalogue lacks, and the default is no rows`() {
        val order = listOf(HomeShelf.HISTORY, HomeShelf.CHANNELS, HomeShelf.PINNED, HomeShelf.KEEP_WATCHING, HomeShelf.SUGGESTED, HomeShelf.VIDEOS)
            .map { HomeSection(it) }
        val first = HomeRows.withOrder(emptyList(), "k1", order)
        assertEquals(order.map { it.id }, HomeRows.rowsOf(first, "k1").map { it.id })
        assertEquals(listOf(100, 200, 300, 400, 500, 600), HomeRows.rowsOf(first, "k1").map { it.rank })

        // Swap the last two: only those two are re-minted.
        val swapped = order.toMutableList().also { val t = it[4]; it[4] = it[5]; it[5] = t }
        val second = HomeRows.withOrder(first, "k1", swapped)
        val unchanged = HomeRows.rowsOf(first, "k1").take(4)
        assertEquals(unchanged, HomeRows.rowsOf(second, "k1").take(4))
        assertEquals(swapped.map { it.id }, HomeRows.rowsOf(second, "k1").map { it.id })

        // A toggle changes no rank.
        val toggled = HomeRows.withOrder(second, "k1", swapped.map { if (it.id == HomeShelf.VIDEOS) it.copy(enabled = false) else it })
        assertEquals(HomeRows.rowsOf(second, "k1").map { it.rank }, HomeRows.rowsOf(toggled, "k1").map { it.rank })
        assertFalse(HomeRows.rowsOf(toggled, "k1").first { it.id == HomeShelf.VIDEOS }.enabled)

        // Mia's rows are untouched by Leo's edits; an unknown id is dropped.
        val withMia = HomeRows.withOrder(toggled, "k2", listOf(HomeSection("made-up"), HomeSection(HomeShelf.VIDEOS)))
        assertEquals(HomeRows.rowsOf(toggled, "k1"), HomeRows.rowsOf(withMia, "k1"))
        assertEquals(listOf(HomeShelf.VIDEOS), HomeRows.rowsOf(withMia, "k2").map { it.id })

        // Reset to the default: the rows disappear rather than being stored.
        val reset = HomeRows.withOrder(withMia, "k1", HOME_SHELVES.map { HomeSection(it) })
        assertTrue(HomeRows.rowsOf(reset, "k1").isEmpty())
        assertEquals(HomeRows.rowsOf(withMia, "k2"), HomeRows.rowsOf(reset, "k2"))
    }

    // --- the merge ----------------------------------------------------------------

    @Test
    fun `two parents arranging different rows both land, and a stale name edit takes nothing away`() {
        val original = stamp(Whitelist(emptyList(), emptySet()), Whitelist(emptyList(), emptySet()), family, T)
        val mum = stamp(original, original, original.copy(homeRows = HomeRows.withOrder(emptyList(), "k1", listOf(HomeSection(HomeShelf.HISTORY), HomeSection(HomeShelf.VIDEOS)))), T + 10, "mum")
        val dad = stamp(original, original, original.copy(homeRows = HomeRows.withOrder(emptyList(), "k2", listOf(HomeSection(HomeShelf.VIDEOS)))), T + 20, "dad")
        val merged = ConfigJson.fromJson(settle(doc(mum), doc(dad)))
        assertEquals(listOf(HomeShelf.HISTORY, HomeShelf.VIDEOS), HomeRows.rowsOf(merged.homeRows, "k1").map { it.id })
        assertEquals(listOf(HomeShelf.VIDEOS), HomeRows.rowsOf(merged.homeRows, "k2").map { it.id })
        assertEquals("both directions agree", ConfigJson.fingerprint(merged), ConfigJson.fingerprint(ConfigJson.fromJson(settle(doc(dad), doc(mum)))))

        // A phone that never saw the rows corrects a name; the rows survive.
        val renamed = stamp(original, original, original.copy(profiles = listOf(leo.copy(name = "Leon"), mia)), T + 30, "dad")
        val after = ConfigJson.fromJson(settle(doc(renamed), doc(mum)))
        assertEquals("Leon", after.profile("k1")!!.name)
        assertEquals(listOf(HomeShelf.HISTORY, HomeShelf.VIDEOS), HomeRows.rowsOf(after.homeRows, "k1").map { it.id })
    }

    @Test
    fun `a removed kid takes their rows with them`() {
        val original = stamp(Whitelist(emptyList(), emptySet()), Whitelist(emptyList(), emptySet()), family, T)
        val arranged = stamp(original, original, original.copy(homeRows = listOf(row(HomeShelf.HISTORY, 100, kid = "k2"))), T + 10)
        val removed = stamp(arranged, arranged, arranged.copy(profiles = listOf(leo)), T + 20)
        assertTrue(removed.homeRows.isEmpty())
        assertTrue(removed.sync.gone.containsKey(ConfigStamp.row("k2", HomeShelf.HISTORY)))
    }
}
