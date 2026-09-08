package io.yosemitekids.app

import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.Pin
import io.yosemitekids.app.data.Pins
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pinned hero's *editor*: the half `PinsConfigTest` deliberately left out
 * because release one had nothing that wrote.
 *
 * Everything here is `Pins.withRow`, and it is in `:core` rather than in
 * `:app` for the reason the sync skill gives about the merge: two faces run
 * this code. The phone's settings form calls it directly and the hub calls it
 * on every `home` patch (`HubWeb.normalisedPins`), so a rule proved only
 * against the Compose screen would be a rule the NAS does not have.
 *
 * The property most of these are circling is **how little it touches**. A
 * card's rank is a value inside its own merge unit, so re-minting one that
 * did not move is not cosmetic: it stamps that unit, and the next merge has
 * two parents' edits to arbitrate where there was one. `RANK_STEP` exists so
 * that an insert has somewhere to land; these tests are what say it is used
 * that way.
 */
class PinsEditorTest {

    private val T = 1_780_000_000_000L
    private val leo = Profile(id = "k1", name = "Leo")
    private val mia = Profile(id = "k2", name = "Mia")

    private fun entry(id: String, kids: Set<String> = emptySet()) = WhitelistEntry(
        id = id,
        url = "https://www.youtube.com/channel/$id",
        label = id,
        kind = SourceKind.CHANNEL,
        profileIds = kids
    )

    private val sources = listOf(entry("UCaaa"), entry("UCbbb"), entry("UCccc"), entry("UCddd"))

    private fun row(pins: List<Pin>, kid: String?) = Pins.rowOf(pins, kid)
    private fun ids(pins: List<Pin>, kid: String?) = row(pins, kid).map { it.sourceId }
    private fun ranks(pins: List<Pin>, kid: String?) = row(pins, kid).associate { it.sourceId to it.rank }

    private fun pin(all: List<Pin>, kid: String?, order: List<String>) =
        Pins.withRow(all, kid, order, sources)

    // --- minting ----------------------------------------------------------

    @Test
    fun theFirstCardTakesTheStepAndTheNextOneTakesAnother() {
        var pins = pin(emptyList(), "k1", listOf("UCaaa"))
        assertEquals(mapOf("UCaaa" to Pins.RANK_STEP), ranks(pins, "k1"))
        pins = pin(pins, "k1", listOf("UCaaa", "UCbbb"))
        assertEquals(
            mapOf("UCaaa" to Pins.RANK_STEP, "UCbbb" to 2 * Pins.RANK_STEP),
            ranks(pins, "k1")
        )
    }

    @Test
    fun aMoveMintsOneRankAndLeavesTheOthersExactlyAsTheyWere() {
        val before = pin(pin(pin(emptyList(), "k1", listOf("UCaaa")), "k1", listOf("UCaaa", "UCbbb")),
            "k1", listOf("UCaaa", "UCbbb", "UCccc"))
        val was = ranks(before, "k1")

        // Third card to the front — one card moved, so one rank may change.
        val after = pin(before, "k1", listOf("UCccc", "UCaaa", "UCbbb"))
        assertEquals(listOf("UCccc", "UCaaa", "UCbbb"), ids(after, "k1"))
        val now = ranks(after, "k1")
        assertEquals("the card that did not move kept its rank", was["UCaaa"], now["UCaaa"])
        assertEquals("the card that did not move kept its rank", was["UCbbb"], now["UCbbb"])
        assertTrue("the moved card landed before both", now.getValue("UCccc") < now.getValue("UCaaa"))
    }

    @Test
    fun aMoveStampsExactlyTheCardThatMoved() {
        // The reason the rank is a value and not an array position, stated
        // where it is actually decided: the stamper mints one unit, so a
        // co-parent's edit to the other two cards still merges cleanly.
        val base = Whitelist(
            sources = sources, blockedVideoIds = emptySet(), profiles = listOf(leo),
            pins = pin(
                pin(pin(emptyList(), "k1", listOf("UCaaa")), "k1", listOf("UCaaa", "UCbbb")),
                "k1", listOf("UCaaa", "UCbbb", "UCccc")
            )
        )
        val next = base.copy(pins = pin(base.pins, "k1", listOf("UCccc", "UCaaa", "UCbbb")))
        val stamped = ConfigStamp.stamped(base, base, next, T, "mum's phone", "mum").config

        val moved = stamped.sync.at.filterKeys { it.startsWith("home.pin|") && it.endsWith("|UCccc") }
        assertEquals("exactly the moved card is stamped", 1, moved.size)
        assertEquals(T, moved.values.single())
        assertEquals(
            "the two cards that stayed put mint nothing",
            0,
            stamped.sync.at.count { (k, v) ->
                k.startsWith("home.pin|") && !k.endsWith("|UCccc") && v == T
            }
        )
    }

    @Test
    fun anUnpinLeavesTheSurvivorsAlone() {
        val before = pin(
            pin(pin(emptyList(), "k1", listOf("UCaaa")), "k1", listOf("UCaaa", "UCbbb")),
            "k1", listOf("UCaaa", "UCbbb", "UCccc")
        )
        val was = ranks(before, "k1")
        val after = pin(before, "k1", listOf("UCaaa", "UCccc"))
        assertEquals(listOf("UCaaa", "UCccc"), ids(after, "k1"))
        assertEquals(was.filterKeys { it != "UCbbb" }, ranks(after, "k1"))
    }

    @Test
    fun rewritingARowToTheOrderItAlreadyHasMintsNothing() {
        // Idempotence, which is not decoration: the phone runs this on every
        // save of the settings form and the hub on every `home` patch. A
        // version that churned ranks would stamp three units every time a
        // parent changed the page size.
        val once = pin(
            pin(pin(emptyList(), "k1", listOf("UCaaa")), "k1", listOf("UCaaa", "UCbbb")),
            "k1", listOf("UCaaa", "UCbbb", "UCccc")
        )
        val twice = pin(once, "k1", ids(once, "k1"))
        assertEquals(once.sortedBy { it.sourceId }, twice.sortedBy { it.sourceId })
    }

    @Test
    fun aGapTooNarrowToLandInRenumbersTheRowRatherThanLosingTheOrder() {
        // Ranks a newer build (or a long enough series of moves) can leave
        // behind. There is nowhere between 100 and 101 to put a card, so the
        // row is renumbered — the one case where every rank moves, and the
        // order still has to come out right.
        val tight = listOf(
            Pin("k1", "UCaaa", 100),
            Pin("k1", "UCbbb", 101)
        )
        val after = pin(tight, "k1", listOf("UCccc", "UCaaa", "UCbbb"))
        assertEquals(listOf("UCccc", "UCaaa", "UCbbb"), ids(after, "k1"))
    }

    // --- one row at a time ------------------------------------------------

    @Test
    fun editingOneKidsRowDoesNotTouchAnother() {
        var pins = pin(emptyList(), "k1", listOf("UCaaa"))
        pins = pin(pins, "k2", listOf("UCbbb", "UCccc"))
        val mias = ranks(pins, "k2")

        pins = pin(pins, "k1", listOf("UCddd", "UCaaa"))
        assertEquals("Mia's cards are untouched, ranks included", mias, ranks(pins, "k2"))
        assertEquals(listOf("UCddd", "UCaaa"), ids(pins, "k1"))
    }

    @Test
    fun theFamilysOwnRowIsAKeyLikeAnyOther() {
        // Null is the household with no profiles, and the row a device where
        // nobody has been picked resolves to (Whitelist.pinsFor).
        var pins = pin(emptyList(), null, listOf("UCaaa"))
        pins = pin(pins, "k1", listOf("UCbbb"))
        assertEquals(listOf("UCaaa"), ids(pins, null))
        assertEquals(listOf("UCbbb"), ids(pins, "k1"))
    }

    @Test
    fun emptyingARowRemovesItAndNothingElse() {
        var pins = pin(emptyList(), "k1", listOf("UCaaa"))
        pins = pin(pins, "k2", listOf("UCbbb"))
        pins = pin(pins, "k1", emptyList())
        assertEquals(emptyList<String>(), ids(pins, "k1"))
        assertEquals(listOf("UCbbb"), ids(pins, "k2"))
    }

    // --- the cap ----------------------------------------------------------

    @Test
    fun aRowStopsGrowingAtTheCap() {
        val full = pin(emptyList(), "k1", listOf("UCaaa", "UCbbb", "UCccc"))
        assertEquals(Pins.MAX, full.size)
        assertTrue(Pins.atCap(Pins.rowOf(full, "k1")))
        val over = pin(full, "k1", listOf("UCaaa", "UCbbb", "UCccc", "UCddd"))
        assertEquals(listOf("UCaaa", "UCbbb", "UCccc"), ids(over, "k1"))
    }

    @Test
    fun aRowThatArrivedLongerThanTheCapIsReorderedRatherThanTrimmed() {
        // The cap is the editor's rule and the renderer's, never the
        // parser's: a document from a build that allows four must survive
        // this one. An older build reordering that row must not be the thing
        // that silently drops the fourth card for the whole household.
        val four = listOf(
            Pin("k1", "UCaaa", 100), Pin("k1", "UCbbb", 200),
            Pin("k1", "UCccc", 300), Pin("k1", "UCddd", 400)
        )
        val after = pin(four, "k1", listOf("UCddd", "UCaaa", "UCbbb", "UCccc"))
        assertEquals(listOf("UCddd", "UCaaa", "UCbbb", "UCccc"), ids(after, "k1"))
        // …and it still cannot grow from there.
        assertEquals(4, Pins.rowOf(after, "k1").size)
    }

    @Test
    fun theParserCountsNothing() {
        // The other half of the same rule, asserted from the wire: whatever
        // the editor refuses to create, `fromJson` still has to carry.
        val json = ConfigJson.toJson(
            Whitelist(
                sources = sources, blockedVideoIds = emptySet(), profiles = listOf(leo),
                pins = listOf(
                    Pin("k1", "UCaaa", 100), Pin("k1", "UCbbb", 200),
                    Pin("k1", "UCccc", 300), Pin("k1", "UCddd", 400)
                )
            )
        )
        assertEquals(4, ConfigJson.fromJson(json).pins.size)
    }

    // --- fail closed ------------------------------------------------------

    @Test
    fun aChannelRestrictedToASiblingIsNeitherOfferedNorPinnable() {
        // `resolvePins` fails closed at draw time, so a card like this would
        // save, sync, and never appear — which reads to a parent as a pin
        // that did not stick. It is refused at the moment of pinning too, so
        // that no face has to remember to filter its own list.
        val restricted = listOf(entry("UCaaa"), entry("UCsis", kids = setOf(mia.id)))
        assertEquals(
            listOf("UCaaa"),
            Pins.candidates(restricted, leo.id).map { it.id }
        )
        val pins = Pins.withRow(emptyList(), leo.id, listOf("UCsis", "UCaaa"), restricted)
        assertEquals(listOf("UCaaa"), ids(pins, leo.id))

        // And it is fine for the sibling it belongs to.
        assertEquals(
            listOf("UCaaa", "UCsis"),
            Pins.candidates(restricted, mia.id).map { it.id }
        )
    }

    @Test
    fun aSourceTheFamilyDoesNotHaveCannotBePinned() {
        val pins = Pins.withRow(emptyList(), "k1", listOf("UCgone"), sources)
        assertTrue(pins.isEmpty())
    }

    @Test
    fun aCardWhoseChannelWasRestrictedGoesTheNextTimeThatRowIsEdited() {
        // Not on an unrelated save — the settings form passes `pins` straight
        // through — but the moment this row is rewritten, the card that can
        // never draw stops being carried.
        val before = Pins.withRow(emptyList(), "k1", listOf("UCaaa", "UCbbb"), sources)
        val restricted = sources.map { if (it.id == "UCbbb") it.copy(profileIds = setOf("k2")) else it }
        val after = Pins.withRow(before, "k1", listOf("UCaaa", "UCbbb"), restricted)
        assertEquals(listOf("UCaaa"), ids(after, "k1"))
    }

    @Test
    fun theFamilyRowSeesWhatADeviceWithNobodyPickedSees() {
        // visibleTo(null) is true for everything, and that is the home
        // screen's own answer for a viewer who has not picked a kid — so the
        // family row offers exactly what that home would draw, no more.
        val restricted = listOf(entry("UCaaa"), entry("UCsis", kids = setOf(mia.id)))
        assertEquals(
            listOf("UCaaa", "UCsis"),
            Pins.candidates(restricted, null).map { it.id }
        )
    }

    // --- the ordering the wire and the merge both rely on -----------------

    @Test
    fun theCanonicalOrderIsTheRankAndThenTheKeyNeverThePositionEdited() {
        val pins = pin(emptyList(), "k1", listOf("UCbbb", "UCaaa"))
        assertEquals(listOf("UCbbb", "UCaaa"), ids(pins, "k1"))
        // Shuffled in the list, identical once ordered: the merge does not
        // preserve array position and neither does anything downstream.
        assertEquals(
            Pins.ordered(pins).map { ConfigStamp.pin(it) },
            Pins.ordered(pins.reversed()).map { ConfigStamp.pin(it) }
        )
    }

    @Test
    fun atCapIsTheOnlyThingAFaceHasToAskBeforeOfferingToPin() {
        assertFalse(Pins.atCap(emptyList()))
        assertFalse(Pins.atCap(Pins.rowOf(pin(emptyList(), "k1", listOf("UCaaa", "UCbbb")), "k1")))
        assertTrue(Pins.atCap(Pins.rowOf(pin(emptyList(), "k1", listOf("UCaaa", "UCbbb", "UCccc")), "k1")))
    }
}
