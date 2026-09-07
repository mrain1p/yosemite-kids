package io.yosemitekids.app

import io.yosemitekids.app.data.BackupFile
import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.ConfigMerge
import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.Pin
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.SyncAction
import io.yosemitekids.app.data.SyncMeta
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import io.yosemitekids.app.data.syncAction
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pinned hero in the config — release one of two: the container, the
 * wire, the stamp, the merge, the tombstone and the backup, with no editor.
 *
 * Two things carry this file. The first test is the gate between the two
 * releases: an empty list must be byte-identical and hash-identical to no
 * list at all, because `ConfigStore.rawJson()` is `toJson(load())` and a
 * build that does not model `home` drops it on the round trip. Any mark an
 * empty list left in the fingerprint would be one that build could never
 * reproduce, `ConfigSync.reconcile` compares exactly those hashes, and the
 * pair would take the Merge arm on every sweep forever — a sync ring that
 * never stops, on the kid's screen, structurally, because the hub is a
 * separate release train. The rest is the merge, proved the way `GrantsTest`
 * and `MergeConvergenceTest` prove theirs: by running it again with the
 * inputs held still, which is what a Push button and a fifteen-minute worker
 * actually do.
 */
class PinsConfigTest {

    private val T = 1_780_000_000_000L
    private val leo = Profile(id = "k1", name = "Leo")
    private val mia = Profile(id = "k2", name = "Mia")

    private fun entry(id: String, label: String? = null) = WhitelistEntry(
        id = id, url = "https://www.youtube.com/channel/$id", label = label, kind = SourceKind.CHANNEL
    )

    private fun pin(src: String, rank: Int, kid: String? = "k1") = Pin(kidId = kid, sourceId = src, rank = rank)

    private val empty = Whitelist(sources = emptyList(), blockedVideoIds = emptySet())

    private fun stamp(previous: Whitelist, base: Whitelist, next: Whitelist, at: Long, by: String = "mum") =
        ConfigStamp.stamped(previous, base, next, at, "$by's phone", by).config

    /**
     * A family as this build writes one: every channel and kid stamped by
     * the save that created it. A unit with no stamp in a stamped document
     * is absent to the merge (`decide` reads `g == 0 -> a > 0`), so a fixture
     * built by hand as a bare `Whitelist(...)` would lose its own channels on
     * first contact and prove nothing about the cards.
     */
    private val family: Whitelist = stamp(
        empty, empty,
        empty.copy(
            sources = listOf(entry("UCaaa", "Bluey"), entry("UCbbb", "Numberblocks"), entry("UCccc")),
            profiles = listOf(leo, mia)
        ),
        T
    )

    /** `toJson` mints updatedAt from the wall clock and the merge compares it; pinned, as every merge test pins it. */
    private fun doc(w: Whitelist): String = JSONObject(ConfigJson.toJson(w)).put("updatedAt", T).toString(2)

    private fun settle(local: String, incoming: String): String = ConfigMerge.merge(local, incoming).merged ?: local

    private fun hashes(json: String): String {
        val w = ConfigJson.fromJson(json)
        return "#${ConfigJson.fingerprint(w)}/${ConfigMerge.syncHash(w.sync)}"
    }

    /** What a phone pushes: `rawJson()` is a full trip through the model, never the merge's own bytes. */
    private fun asPushedByPhone(disk: String): String = doc(ConfigJson.fromJson(disk))

    private fun pinsOf(json: String) = ConfigJson.fromJson(json).pins

    // --- the gate between the two releases --------------------------------

    @Test
    fun anEmptyListIsByteIdenticalAndHashIdenticalToNoListAtAll() {
        val never = family
        val emptied = family.copy(pins = emptyList())
        assertEquals(doc(never), doc(emptied))
        assertEquals(ConfigJson.fingerprint(never), ConfigJson.fingerprint(emptied))
        assertFalse("an empty list must write no container", doc(never).contains("\"home\""))
        // A container a newer build wrote present-but-empty reads back, and
        // writes back, as no container — the shape an older build's round
        // trip produces, and the one it must hash alike.
        listOf("""{"pins":[]}""", "{}").forEach { home ->
            val parsed = ConfigJson.fromJson(JSONObject(doc(never)).put("home", JSONObject(home)).toString())
            assertTrue(parsed.pins.isEmpty())
            assertEquals("[home=$home] bytes", doc(never), doc(parsed))
            assertEquals("[home=$home] hash", ConfigJson.fingerprint(never), ConfigJson.fingerprint(parsed))
            // And the reconcile's own verdict on the pair: nothing to do.
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
    fun aPinnedCardMovesTheHashAndAReorderMovesItAgain() {
        // Append-only-when-set, at the tail, like every field since: a set
        // list must move the hash or the offline reconcile — which only
        // re-pushes on a mismatch — would never carry a card to the TV, and
        // a reorder is a change to what the kid sees.
        val one = family.copy(pins = listOf(pin("UCaaa", 100)))
        val two = one.copy(pins = one.pins + pin("UCbbb", 200))
        val moved = one.copy(pins = listOf(pin("UCaaa", 300)))
        assertNotEquals(ConfigJson.fingerprint(family), ConfigJson.fingerprint(one))
        assertNotEquals(ConfigJson.fingerprint(one), ConfigJson.fingerprint(two))
        assertNotEquals(ConfigJson.fingerprint(one), ConfigJson.fingerprint(moved))
        // The list's order in memory is not part of the hash; the ranks are.
        assertEquals(ConfigJson.fingerprint(two), ConfigJson.fingerprint(two.copy(pins = two.pins.reversed())))
    }

    // --- the wire -----------------------------------------------------------

    @Test
    fun cardsRoundTripInCanonicalOrderWithTheFamilyKidOmitted() {
        val w = family.copy(pins = listOf(pin("UCbbb", 200), pin("UCaaa", 100), pin("UCccc", 100, kid = null)))
        val json = ConfigJson.toJson(w)
        // (rank, unit key): the family's card sorts before Leo's at the same
        // rank because "*" sorts before a hex id — a fixed order, not a
        // meaningful one, and the same on every device.
        val expected = listOf(pin("UCccc", 100, kid = null), pin("UCaaa", 100), pin("UCbbb", 200))
        assertEquals(expected, ConfigJson.fromJson(json).pins)
        assertEquals("the wire order is canonical however the list arrived", doc(w), doc(w.copy(pins = w.pins.reversed())))
        val arr = JSONObject(json).getJSONObject("home").getJSONArray("pins")
        assertFalse("the family's card carries no kid", arr.getJSONObject(0).has("kid"))
        assertEquals("k1", arr.getJSONObject(1).getString("kid"))
        assertEquals("UCaaa", arr.getJSONObject(1).getString("src"))
        assertEquals(100, arr.getJSONObject(1).getInt("rank"))
    }

    @Test
    fun aMalformedCardDropsAloneAndNoCapIsEnforcedAtParseTime() {
        val text = """[{"src":"UCaaa","rank":100},
                      {"kid":"k1","src":"UCbbb","rank":200,"future":true},
                      {"src":"UC|bad","rank":100},
                      {"kid":"*","src":"UCccc","rank":100},
                      {"src":"UCddd"},
                      {"kid":"k;1","src":"UCeee","rank":1},
                      {"src":"UCfff","rank":"first"}]"""
        assertEquals(listOf(pin("UCaaa", 100, kid = null), pin("UCbbb", 200)), ConfigJson.pinsFromJson(text))
        assertTrue(ConfigJson.pinsFromJson("garbage").isEmpty())
        assertTrue(ConfigJson.pinsFromJson(null).isEmpty())
        // The two-or-three is the editor's rule and the renderer's, never the
        // parser's: four cards from a newer build all survive.
        val four = (1..4).joinToString(",", "[", "]") { """{"src":"UC$it","rank":${it * 100}}""" }
        assertEquals(4, ConfigJson.pinsFromJson(four).size)
        // Two spellings of one key collapse to the first, as duplicate channels do.
        val twice = """{"entries":[],"limits":{},"home":{"pins":[{"src":"UCaaa","rank":100},{"src":"UCaaa","rank":900}]}}"""
        assertEquals(listOf(pin("UCaaa", 100, kid = null)), ConfigJson.fromJson(twice).pins)
    }

    @Test
    fun pinsForResolvesExactlyAsLimitsForDoes() {
        val w = family.copy(
            pins = listOf(pin("UCbbb", 200), pin("UCaaa", 100), pin("UCccc", 100, kid = "k2"), pin("UCccc", 100, kid = null))
        )
        // A kid who exists gets their own row, in rank order, and nothing else.
        assertEquals(listOf("UCaaa", "UCbbb"), w.pinsFor("k1").map { it.sourceId })
        assertEquals(listOf("UCccc"), w.pinsFor("k2").map { it.sourceId })
        // A profile id that resolves to nobody gets the family's own row —
        // no profiles at all, or a device dedicated to a kid since removed —
        // by the one rule limitsFor already uses, and no second one.
        assertEquals(listOf("UCccc"), w.pinsFor(null).map { it.sourceId })
        assertEquals(listOf("UCccc"), w.pinsFor("gone").map { it.sourceId })
        assertEquals(listOf("UCccc"), w.copy(profiles = emptyList()).pinsFor(null).map { it.sourceId })
        assertEquals(w.limits, w.limitsFor("gone"))
    }

    // --- the stamper --------------------------------------------------------

    @Test
    fun pinningMintsAUnitMovingTouchesItAndUnpinningTombstonesIt() {
        val key = ConfigStamp.pin("k1", "UCaaa")
        val s1 = stamp(family, family, family.copy(pins = listOf(pin("UCaaa", 100))), T + 1)
        assertEquals(T + 1, s1.sync.at[key])
        assertTrue(s1.sync.log.any { it.code == "home.pin.add" && it.text == "pinned Bluey for Leo" })
        // A move touches the card and nothing else.
        val s2 = stamp(s1, s1, s1.copy(pins = listOf(pin("UCaaa", 300))), T + 2)
        assertEquals(T + 2, s2.sync.at[key])
        assertEquals(s1.sync.at - key, s2.sync.at - key)
        assertTrue(s2.sync.log.any { it.code == "home.pin.move" && it.text == "moved Bluey for Leo" })
        // A save that changes nothing mints nothing, docAt included.
        assertEquals(s2.sync, stamp(s2, s2, s2, T + 3).sync)
        // Unpinning: the stamp goes and a tombstone stands — the fail-absent
        // shape, like a channel; keeping the stamp would let the card satisfy
        // its own causality check on the next merge and come straight back.
        val s4 = stamp(s2, s2, s2.copy(pins = emptyList()), T + 4)
        assertNull(s4.sync.at[key])
        assertEquals(T + 4, s4.sync.gone[key])
        assertTrue(s4.sync.log.any { it.code == "home.pin.remove" && it.text == "unpinned Bluey for Leo" })
        // The family's own row reads without a kid.
        val fam = stamp(family, family, family.copy(pins = listOf(pin("UCccc", 100, kid = null))), T + 5)
        assertTrue(fam.sync.log.any { it.text == "pinned UCccc" })
    }

    @Test
    fun removingASourceOrAKidTombstonesTheirCardsInTheSameSave() {
        val s = stamp(
            family, family,
            family.copy(pins = listOf(pin("UCaaa", 100), pin("UCbbb", 200, kid = "k2"), pin("UCccc", 300, kid = null))),
            T + 1
        )
        val s2 = stamp(s, s, s.copy(sources = s.sources.filterNot { it.id == "UCaaa" }), T + 2)
        assertEquals(listOf("UCbbb", "UCccc"), s2.pins.map { it.sourceId })
        assertEquals(T + 2, s2.sync.gone[ConfigStamp.pin("k1", "UCaaa")])
        assertNull(s2.sync.at[ConfigStamp.pin("k1", "UCaaa")])
        val s3 = stamp(s2, s2, s2.copy(profiles = listOf(leo)), T + 3)
        assertEquals(listOf("UCccc"), s3.pins.map { it.sourceId })
        assertEquals(T + 3, s3.sync.gone[ConfigStamp.pin("k2", "UCbbb")])
        // The family's card belongs to nobody who can be removed.
        assertEquals(T + 1, s3.sync.at[ConfigStamp.pin(null, "UCccc")])
    }

    @Test
    fun aCoParentsCardArrivingUnderTheOpenFormIsCarriedNotTombstoned() {
        // Dad's card landed via POST /config while Mum had Settings open on
        // `family`; her save must carry it — unstamped, untombstoned.
        val key = ConfigStamp.pin("k1", "UCaaa")
        val landed = stamp(family, family, family.copy(pins = listOf(pin("UCaaa", 100))), T + 1, by = "dad")
        val out = ConfigStamp.stamped(
            landed, family, family.copy(limits = Limits(sessionMinutes = 30)), T + 2, "Mum's phone", "mum"
        ).config
        assertEquals(listOf(pin("UCaaa", 100)), out.pins)
        assertEquals(T + 1, out.sync.at[key])
        assertNull(out.sync.gone[key])
    }

    // --- the merge ----------------------------------------------------------

    @Test
    fun twoPhonesPinDifferentCardsAndBothSurviveHoweverOftenTheyMeet() {
        val mum = doc(stamp(family, family, family.copy(pins = listOf(pin("UCaaa", 100))), T + 1, by = "mum"))
        val dad = doc(stamp(family, family, family.copy(pins = listOf(pin("UCbbb", 100))), T + 2, by = "dad"))
        val ab = settle(mum, dad)
        val ba = settle(dad, mum)
        assertEquals(listOf(pin("UCaaa", 100), pin("UCbbb", 100)), pinsOf(ab))
        assertEquals("both orders hash alike", hashes(ab), hashes(ba))
        assertTrue(ConfigMerge.merge(mum, dad).collisions.isEmpty())
        // Held still, six times, from either side: what a Push button does.
        listOf("mum" to mum, "dad" to dad).forEach { (name, peer) ->
            var cur = ab
            repeat(6) {
                cur = settle(cur, peer)
                assertEquals("kept moving under an unchanged push from $name", hashes(ab), hashes(cur))
            }
            assertNull("a second merge of $name's copy must write nothing", ConfigMerge.merge(ab, peer).merged)
        }
        // And the round trip a phone and a hub actually run.
        val h1 = settle(mum, dad)
        val p1 = asPushedByPhone(settle(dad, h1))
        val h2 = settle(h1, p1)
        val p2 = asPushedByPhone(settle(p1, h2))
        val h3 = settle(h2, p2)
        assertEquals(hashes(h1), hashes(h2))
        assertEquals(hashes(h2), hashes(h3))
        assertEquals(hashes(h3), hashes(p2))
    }

    @Test
    fun twoPhonesMoveTheSameCardAndTheLaterStampWinsSilently() {
        val start = stamp(family, family, family.copy(pins = listOf(pin("UCaaa", 100), pin("UCbbb", 200))), T + 1)
        // Mum drags Bluey to the end; Dad, a moment later, drags it to the front.
        val mum = doc(stamp(start, start, start.copy(pins = listOf(pin("UCaaa", 300), pin("UCbbb", 200))), T + 2, by = "mum"))
        val dad = doc(stamp(start, start, start.copy(pins = listOf(pin("UCaaa", 50), pin("UCbbb", 200))), T + 3, by = "dad"))
        // Last reorder wins per card, silently: Dad's later stamp puts Bluey
        // first on every device and Mum is not asked. Deliberate. The
        // alternative — one whole-list value — trades silent reordering for
        // loud loss: it is exactly as silent about which stamp won, and it
        // loses every card the loser touched rather than the one both moved,
        // with nothing to put it back. Only the contested card is contested;
        // Numberblocks, which neither moved, keeps its original stamp.
        val ab = settle(mum, dad)
        val ba = settle(dad, mum)
        assertEquals(listOf(pin("UCaaa", 50), pin("UCbbb", 200)), pinsOf(ab))
        assertEquals(hashes(ab), hashes(ba))
        assertEquals(T + 3, ConfigJson.fromJson(ab).sync.at[ConfigStamp.pin("k1", "UCaaa")])
        assertEquals(T + 1, ConfigJson.fromJson(ab).sync.at[ConfigStamp.pin("k1", "UCbbb")])
        // The loser is at least told, in the collision record, even though
        // the row is not one of the units a tap can put back.
        val lost = ConfigMerge.merge(mum, dad).collisions
        assertEquals(listOf(ConfigStamp.pin("k1", "UCaaa")), lost.map { it.unit })
        assertEquals("home.pin", lost.single().code)
        assertTrue(ConfigMerge.merge(dad, mum).collisions.isEmpty())
        // Settled from either side.
        assertNull(ConfigMerge.merge(ab, dad).merged)
        assertNull(ConfigMerge.merge(ab, mum).merged)
    }

    @Test
    fun aDeletedSourceTombstonesItsCardAndAStalePeerCannotBringItBack() {
        val pinned = stamp(family, family, family.copy(pins = listOf(pin("UCaaa", 100), pin("UCbbb", 200))), T + 1)
        val stale = doc(pinned)   // Dad's phone, in a drawer
        val removed = doc(stamp(pinned, pinned, pinned.copy(sources = pinned.sources.filterNot { it.id == "UCaaa" }), T + 2))

        var cur = removed
        val seen = ArrayList<String>()
        repeat(6) {
            cur = settle(cur, stale)
            seen += hashes(cur)
        }
        assertEquals("the stale push must change nothing, however often it lands: $seen", 1, seen.distinct().size)
        assertEquals(hashes(removed), seen.first())
        val w = ConfigJson.fromJson(cur)
        assertEquals(listOf("UCbbb", "UCccc"), w.sources.map { it.id })
        assertEquals(listOf(pin("UCbbb", 200)), w.pins)
        assertEquals(T + 2, w.sync.gone[ConfigStamp.pin("k1", "UCaaa")])
        // The stale side, merging the tombstone in, lands on the same document.
        val onStale = settle(stale, removed)
        assertEquals(hashes(cur), hashes(onStale))
        assertNull(ConfigMerge.merge(onStale, removed).merged)
        // A deliberate re-add of the channel later does not resurrect the
        // card: its own tombstone stands until a parent pins it again.
        val back = ConfigJson.fromJson(cur)
        val readded = doc(stamp(back, back, back.copy(sources = pinned.sources), T + 3))
        val again = settle(readded, stale)
        assertEquals(listOf("UCaaa", "UCbbb", "UCccc"), ConfigJson.fromJson(again).sources.map { it.id }.sorted())
        assertEquals(listOf(pin("UCbbb", 200)), pinsOf(again))
    }

    @Test
    fun aCardPinnedWhileItsSourceWasDeletedElsewhereIsScrubbedAndSettles() {
        // Neither side saw the other: Mum deleted Bluey, Dad pinned it. The
        // stamper on Dad's phone had nothing to couple to, so no tombstone
        // exists for the card; the merge refuses it because the merged
        // document no longer lists its source, and refuses its stamp with it
        // — the first cut kept the stamp, and the next merge dropped that
        // stamp as belonging to nothing, so the hub's hash moved twice for
        // one push. The refusal is a function of the content, so it is the
        // same refusal on every merge, from either side.
        val mum = doc(stamp(family, family, family.copy(sources = family.sources.filterNot { it.id == "UCaaa" }), T + 1, by = "mum"))
        val dad = doc(stamp(family, family, family.copy(pins = listOf(pin("UCaaa", 100))), T + 2, by = "dad"))
        val ab = settle(mum, dad)
        val ba = settle(dad, mum)
        assertEquals(hashes(ab), hashes(ba))
        assertTrue(pinsOf(ab).isEmpty())
        assertEquals(listOf("UCbbb", "UCccc"), ConfigJson.fromJson(ab).sources.map { it.id })
        var cur = ab
        repeat(6) {
            cur = settle(cur, dad)
            assertEquals(hashes(ab), hashes(cur))
        }
        assertNull(ConfigMerge.merge(ab, dad).merged)
        // Dad's phone, pulling, arrives at the same document and stops.
        val onDad = asPushedByPhone(settle(dad, ab))
        assertEquals(hashes(ab), hashes(onDad))
        assertNull(ConfigMerge.merge(ab, onDad).merged)
    }

    @Test
    fun whatThisBuildDoesNotModelSurvivesTheMerge() {
        // A card from a newer build carries a field this one has no name
        // for, and `home` carries a sibling of `pins`. Both cross a merge on
        // this build untouched: the loop picks whole JSON objects, and `home`
        // is rebuilt from the local document's copy the way unknown roots
        // are. (A model round trip cannot keep them — no field's can — which
        // is what the two-release rule is for.)
        val withPin = doc(stamp(family, family, family.copy(pins = listOf(pin("UCaaa", 100))), T + 1))
        val newer = JSONObject(withPin).also { root ->
            val home = root.getJSONObject("home")
            home.getJSONArray("pins").getJSONObject(0).put("style", "wide")
            home.put("shelves", JSONArray().put("pinned"))
        }.toString(2)
        val peer = doc(stamp(family, family, family.copy(pins = listOf(pin("UCbbb", 200))), T + 2, by = "dad"))
        val merged = settle(newer, peer)
        val home = JSONObject(merged).getJSONObject("home")
        assertEquals("pinned", home.getJSONArray("shelves").getString(0))
        val cards = home.getJSONArray("pins")
        assertEquals(2, cards.length())
        assertEquals("wide", cards.getJSONObject(0).getString("style"))
        assertEquals("UCbbb", cards.getJSONObject(1).getString("src"))
        // And the merge settles on the newer shape.
        assertNull(ConfigMerge.merge(merged, peer).merged)
    }

    @Test
    fun aLegacyShapedDocumentKeepsItsCards() {
        // No sync block at all — a hand-written or restored document. Its
        // cards get positional stamps like its channels, so they survive
        // first contact instead of reading as absent.
        val legacy = ConfigJson.toJson(family.copy(sync = SyncMeta.EMPTY, pins = listOf(pin("UCaaa", 100))))
        val peer = doc(stamp(family, family, family.copy(pins = listOf(pin("UCbbb", 200))), T + 1, by = "dad"))
        assertEquals(listOf(pin("UCaaa", 100), pin("UCbbb", 200)), pinsOf(settle(legacy, peer)))
        assertEquals(listOf(pin("UCaaa", 100), pin("UCbbb", 200)), pinsOf(settle(peer, legacy)))
    }

    @Test
    fun describeNamesWhatChangedInTheRow() {
        val a = family.copy(pins = listOf(pin("UCaaa", 100), pin("UCbbb", 200)))
        val b = family.copy(pins = listOf(pin("UCaaa", 300), pin("UCccc", 200, kid = null)))
        assertEquals(
            listOf("pins UCccc", "unpins Numberblocks for Leo", "reorders the pinned row"),
            ConfigMerge.describe(a, b).filter { it.code == "home.pin" }.map { it.text }
        )
        assertTrue(ConfigMerge.identical(a, a))
        assertFalse(
            "a reorder alone is a change",
            ConfigMerge.identical(a, a.copy(pins = listOf(pin("UCaaa", 100), pin("UCbbb", 50))))
        )
    }

    // --- the backup ---------------------------------------------------------

    @Test
    fun aBackupCarriesTheCardsAndTheirTombstones() {
        // The envelope wraps the document verbatim, sync block included, so a
        // card travels and so does the tombstone that says one was removed —
        // and `Backup.restore` carries the device's tombstones across on top.
        val pinned = stamp(family, family, family.copy(pins = listOf(pin("UCaaa", 100), pin("UCbbb", 200))), T + 1)
        val trimmed = stamp(pinned, pinned, pinned.copy(pins = listOf(pin("UCaaa", 100))), T + 2)
        val file = BackupFile.wrap(ConfigJson.toJson(trimmed, includeSecrets = false), at = T + 3, app = "test")
        val restored = ConfigJson.fromJson(BackupFile.configIn(file)!!)
        assertEquals(listOf(pin("UCaaa", 100)), restored.pins)
        assertEquals(T + 1, restored.sync.at[ConfigStamp.pin("k1", "UCaaa")])
        assertEquals(T + 2, restored.sync.gone[ConfigStamp.pin("k1", "UCbbb")])
    }
}
