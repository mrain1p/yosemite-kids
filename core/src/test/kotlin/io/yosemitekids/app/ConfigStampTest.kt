package io.yosemitekids.app

import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.SyncMeta
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three-way stamper.
 *
 * The case that matters most here is the one a reader would not think to look
 * for: a co-parent's channel arriving via `POST /config` while a parent has
 * Settings open. It is on disk (`previous`) and absent from what the editor
 * opened with (`base`), and reading that as a deletion would mint a durable,
 * propagating tombstone at the form's next autosave — quietly deleting a
 * channel nobody touched. See [stampedDoesNotTombstoneWhatItNeverSaw].
 */
class ConfigStampTest {

    private val T0 = 1_780_000_000_000L
    private val DAD = "a1b2c3d4"
    private val MUM = "99887766"

    private fun entry(id: String, label: String? = null) = WhitelistEntry(
        id = id,
        url = "https://www.youtube.com/channel/$id",
        label = label,
        kind = SourceKind.CHANNEL
    )

    private fun config(
        sources: List<WhitelistEntry> = emptyList(),
        blocked: Set<String> = emptySet(),
        profiles: List<Profile> = emptyList(),
        limits: Limits = Limits(),
        sync: SyncMeta = SyncMeta.EMPTY,
        autoplay: Boolean = true
    ) = Whitelist(
        sources = sources,
        blockedVideoIds = blocked,
        limits = limits,
        profiles = profiles,
        autoplayNext = autoplay,
        sync = sync
    )

    private fun stamp(
        previous: Whitelist,
        base: Whitelist,
        next: Whitelist,
        now: Long = T0,
        by: String = DAD,
        who: String = "Dad's phone"
    ) = ConfigStamp.stamped(previous, base, next, now, who, by)

    // --- what gets stamped ---------------------------------------------

    @Test
    fun onlyWhatChangedIsStamped() {
        val base = config(sources = listOf(entry("UCaaa")), limits = Limits(sessionMinutes = 45))
        val next = base.copy(limits = Limits(sessionMinutes = 30))

        val sync = stamp(base, base, next).config.sync
        assertEquals(
            "the untouched channel must not be restamped",
            setOf(ConfigStamp.LIM_RULES), sync.at.keys
        )
        assertEquals(T0, sync.at.getValue(ConfigStamp.LIM_RULES))
    }

    @Test
    fun anAddStampsTheChannelAndLogsIt() {
        val base = config()
        val next = config(sources = listOf(entry("UCaaa", "SciShow Kids")))

        val sync = stamp(base, base, next).config.sync
        assertEquals(setOf(ConfigStamp.src("UCaaa")), sync.at.keys)
        assertEquals(listOf("added SciShow Kids"), sync.log.map { it.text })
        assertEquals("Dad's phone", sync.log.first().who)
        assertEquals(DAD, sync.log.first().by)
    }

    @Test
    fun aSettingsToggleStampsTheGroupNotTheChannels() {
        val base = config(sources = listOf(entry("UCaaa")))
        val next = base.copy(autoplayNext = false)

        val sync = stamp(base, base, next).config.sync
        assertEquals(setOf(ConfigStamp.SETTINGS), sync.at.keys)
    }

    @Test
    fun aKidsPinIsItsOwnUnitSeparateFromTheirName() {
        // A credential riding inside an object. Without its own unit, fixing a
        // kid's name on a stale copy silently removes the code a co-parent set
        // an hour ago, and the picker then lets a sibling into that profile.
        val kid = Profile(id = "k1", name = "Leo")
        val base = config(profiles = listOf(kid))
        val next = config(profiles = listOf(kid.copy(pin = "1234")))

        val sync = stamp(base, base, next).config.sync
        assertEquals(setOf(ConfigStamp.kidPin("k1")), sync.at.keys)
    }

    @Test
    fun aKidsRulesWindowsAndPauseAreThreeSeparateUnits() {
        val kid = Profile(id = "k1", name = "Leo", limits = Limits(sessionMinutes = 45))
        val base = config(profiles = listOf(kid))
        val next = config(
            profiles = listOf(kid.copy(limits = Limits(sessionMinutes = 30, pausedUntilMillis = T0)))
        )

        val sync = stamp(base, base, next).config.sync
        assertEquals(
            setOf(ConfigStamp.kidRules("k1"), ConfigStamp.kidPause("k1")),
            sync.at.keys
        )
    }

    // --- deletes -------------------------------------------------------

    @Test
    fun aRemovalWritesATombstoneAndDropsTheStamp() {
        val base = config(sources = listOf(entry("UCaaa", "SciShow Kids")))
        val afterAdd = stamp(base, config(), base).config
        assertTrue(afterAdd.sync.at.containsKey(ConfigStamp.src("UCaaa")))

        val gone = stamp(afterAdd, afterAdd, afterAdd.copy(sources = emptyList()), now = T0 + 1000)
            .config.sync

        assertNull(
            "keeping both would let the unit satisfy its own causality check and come back",
            gone.at[ConfigStamp.src("UCaaa")]
        )
        assertEquals(T0 + 1000, gone.gone.getValue(ConfigStamp.src("UCaaa")))
        assertEquals(listOf("removed SciShow Kids"), gone.log.takeLast(1).map { it.text })
    }

    @Test
    fun aReAddKeepsItsTombstoneAndOutstampsIt() {
        // The tombstone is the evidence that this add came *after* the delete.
        // Clearing it — the shape SavedListStore.add uses — would make the
        // re-added channel vanish again on the next sweep, forever.
        val withIt = config(sources = listOf(entry("UCaaa")))
        val afterAdd = stamp(config(), config(), withIt).config
        val afterRemove = stamp(afterAdd, afterAdd, afterAdd.copy(sources = emptyList()), now = T0 + 1000).config
        val afterReAdd = stamp(
            afterRemove, afterRemove, afterRemove.copy(sources = listOf(entry("UCaaa"))),
            now = T0 + 2000
        ).config.sync

        val key = ConfigStamp.src("UCaaa")
        assertEquals(T0 + 2000, afterReAdd.at.getValue(key))
        assertEquals(
            "the tombstone must survive a re-add, not be cleared by it",
            T0 + 1000, afterReAdd.gone.getValue(key)
        )
        assertTrue("and the add must out-stamp it", afterReAdd.at.getValue(key) > afterReAdd.gone.getValue(key))
    }

    @Test
    fun stampedDoesNotTombstoneWhatItNeverSaw() {
        // Mum opened Settings holding one channel. While the form sat open,
        // Dad's push landed a second one on disk. Her next autosave must carry
        // his channel forward untouched — not delete it.
        val base = config(sources = listOf(entry("UCaaa")))
        val previous = config(sources = listOf(entry("UCaaa"), entry("UCbbb", "Dad's add")))
        val next = base.copy(autoplayNext = false)

        val out = stamp(previous, base, next, by = MUM, who = "Mum's phone").config

        assertEquals(
            "the co-parent's channel must survive an unrelated autosave",
            setOf("UCaaa", "UCbbb"), out.sources.map { it.id }.toSet()
        )
        assertFalse(
            "and must not be tombstoned",
            out.sync.gone.containsKey(ConfigStamp.src("UCbbb"))
        )
        assertEquals(setOf(ConfigStamp.SETTINGS), out.sync.at.keys)
    }

    @Test
    fun aBlockThatArrivedUnderTheOpenFormIsAlsoCarried() {
        val base = config(blocked = setOf("v1"))
        val previous = config(blocked = setOf("v1", "v2"))
        val next = base.copy(autoplayNext = false)

        val out = stamp(previous, base, next).config
        assertEquals(setOf("v1", "v2"), out.blockedVideoIds)
        assertFalse(out.sync.gone.containsKey(ConfigStamp.blk("v2")))
    }

    // --- a save that changes nothing, and the save after a carry ----------
    //
    // The form's contract with the stamper: after a save, the form adopts
    // the stamped result — carried units and all — as both its baseline and
    // its own state. These pin what that contract buys. Without it, a unit
    // the stamper carried sat in `base` and not in `next` on the very next
    // save, and the stamper — correctly, from what it was shown — read that
    // as the editor deleting it. A co-parent's channel that landed under an
    // open form was deleted by this phone's second tap.

    @Test
    fun aSaveThatChangesNothingMintsNothing() {
        val opened = config(sources = listOf(entry("UCaaa")), blocked = setOf("v1"))
        val onDisk = stamp(opened, config(), opened).config
        assertTrue(onDisk.sync.at.isNotEmpty())

        // The same document three ways: nothing on disk moved, the editor
        // touched nothing. A Push button does exactly this.
        val again = stamp(onDisk, onDisk, onDisk, now = T0 + 60_000).config

        assertEquals("no stamp may move", onDisk.sync.at, again.sync.at)
        assertEquals("no tombstone may appear", onDisk.sync.gone, again.sync.gone)
        assertEquals("no log line may be written", onDisk.sync.log, again.sync.log)
        assertEquals("docAt is the highest stamp carried, and none was minted", onDisk.sync.docAt, again.sync.docAt)
        assertEquals("and the document itself is unchanged", onDisk, again)
    }

    @Test
    fun aCarriedUnitIsNotTombstonedOnTheFollowingSave() {
        // Save 1: Dad's channel arrived under Mum's open form and is carried.
        val base = config(sources = listOf(entry("UCaaa")))
        val previous = config(sources = listOf(entry("UCaaa"), entry("UCbbb", "Dad's add")))
        val first = stamp(previous, base, base.copy(autoplayNext = false), by = MUM).config
        assertEquals(setOf("UCaaa", "UCbbb"), first.sources.map { it.id }.toSet())

        // Save 2: the form adopted `first` as its baseline AND its state, so
        // base and next both list Dad's channel. Then Mum taps something
        // unrelated.
        val second = stamp(first, first, first.copy(sponsorSkip = false), now = T0 + 1000, by = MUM).config

        assertTrue("the carried channel is still there", second.sources.any { it.id == "UCbbb" })
        assertFalse(
            "a channel nobody removed must not be tombstoned",
            second.sync.gone.containsKey(ConfigStamp.src("UCbbb"))
        )
        assertFalse(
            "and adopting it is not an add either — no stamp is minted for it",
            second.sync.at.containsKey(ConfigStamp.src("UCbbb"))
        )
        assertEquals(setOf(ConfigStamp.SETTINGS), second.sync.at.keys)
    }

    @Test
    fun aSectionTheEditorLeftAloneKeepsWhatDiskHolds() {
        // A co-parent's screening edit merged in under the open form. The
        // form's copy of `ai` is stale, but the editor never touched it, so
        // the disk's copy stands and no AI stamp is minted — every tap used
        // to re-mint it and write "changed screening" with nobody touching
        // screening.
        val opened = config(sources = listOf(entry("UCaaa")))
        val onDisk = opened.copy(ai = opened.ai.copy(rules = "no pranks", rulesVersion = 3))

        val out = stamp(onDisk, opened, opened.copy(autoplayNext = false)).config

        assertEquals("no pranks", out.ai.rules)
        assertEquals(3, out.ai.rulesVersion)
        assertFalse(out.sync.at.containsKey(ConfigStamp.AI))
        assertEquals(listOf("changed app settings"), out.sync.log.map { it.text })
    }

    @Test
    fun anUntouchedSettingsToggleKeepsWhatDiskHolds() {
        // The same rule for the loose scalars. `next.copy` alone wrote the
        // form's copy over a co-parent's merged-in toggle under the
        // co-parent's own stamp, and the next merge broke that tie by string
        // order.
        val opened = config(sources = listOf(entry("UCaaa")))
        val onDisk = opened.copy(sponsorSkip = false, showVideoAge = true)

        // The editor changed the blocked list, not the settings group.
        val out = stamp(onDisk, opened, opened.copy(blockedVideoIds = setOf("v1"))).config

        assertFalse("the co-parent's toggle stands", out.sponsorSkip)
        assertTrue(out.showVideoAge)
        assertFalse(out.sync.at.containsKey(ConfigStamp.SETTINGS))

        // And when the editor did change the group, theirs wins outright.
        val edited = stamp(onDisk, opened, opened.copy(showVideoAge = true)).config
        assertTrue("the editor's group, all of it", edited.sponsorSkip)
        assertTrue(edited.sync.at.containsKey(ConfigStamp.SETTINGS))
    }

    @Test
    fun aKidRemovedByTheEditorTombstonesEveryUnitOfTheirs() {
        val kid = Profile(id = "k1", name = "Leo", pin = "1234")
        val base = config(profiles = listOf(kid))
        val out = stamp(base, base, config()).config.sync

        assertTrue(out.gone.containsKey(ConfigStamp.kid("k1")))
        assertTrue(out.gone.containsKey(ConfigStamp.kidPin("k1")))
        assertTrue(out.gone.containsKey(ConfigStamp.kidRules("k1")))
        assertTrue(out.gone.containsKey(ConfigStamp.kidWindows("k1")))
    }

    // --- the clock -----------------------------------------------------

    @Test
    fun stampNeverGoesBackwardsFromDocAt() {
        // A TV that boots after a power cut with a 2019 clock otherwise loses
        // every unit it touches until someone notices the date.
        val previous = config(sync = SyncMeta(docAt = T0 + 60_000))
        val base = previous
        val next = previous.copy(autoplayNext = false)

        val out = stamp(previous, base, next, now = T0 - 999_999_999L).config.sync
        assertEquals(T0 + 60_001, out.at.getValue(ConfigStamp.SETTINGS))
        assertEquals(T0 + 60_001, out.docAt)
    }

    @Test
    fun aFutureDocAtRecordsAClockNoticeAndStillMintsHigher() {
        val previous = config(
            sync = SyncMeta(docAt = T0 + ConfigStamp.CLOCK_SKEW_MAX_MS + 60_000)
        )
        val next = previous.copy(autoplayNext = false)

        val result = stamp(previous, previous, next, now = T0)
        assertTrue("a wildly future document must be reported", result.clockLooksWrong)
        assertTrue(
            "and the parent must still be able to win their edit",
            result.config.sync.at.getValue(ConfigStamp.SETTINGS) > previous.sync.docAt
        )
    }

    @Test
    fun anOrdinaryClockRaisesNoNotice() {
        val previous = config(sync = SyncMeta(docAt = T0 - 1000))
        val result = stamp(previous, previous, previous.copy(autoplayNext = false))
        assertFalse(result.clockLooksWrong)
    }

    @Test
    fun theLogShowsTheRawClockEvenWhenTheStampIsForcedForward() {
        // shownAt exists so a forced-monotonic stamp is never rendered to a
        // parent as a fictional time.
        val previous = config(sync = SyncMeta(docAt = T0 + 60_000))
        val out = stamp(previous, previous, previous.copy(autoplayNext = false), now = T0)
            .config.sync.log.last()
        assertEquals(T0 + 60_001, out.at)
        assertTrue("the displayed time is the minting device's own", out.shownAt >= out.at)
    }

    // --- the log -------------------------------------------------------

    @Test
    fun aBurstCoalescesIntoOneLogLine() {
        var c = config()
        // Three channel adds from one phone inside the coalesce window.
        c = stamp(c, c, c.copy(sources = listOf(entry("UCa", "A"))), now = T0).config
        c = stamp(c, c, c.copy(sources = c.sources + entry("UCb", "B")), now = T0 + 1000).config
        c = stamp(c, c, c.copy(sources = c.sources + entry("UCc", "C")), now = T0 + 2000).config

        assertEquals(1, c.sync.log.size)
        assertEquals("added C", c.sync.log.first().text)
    }

    @Test
    fun anEditPastTheWindowStartsANewLine() {
        var c = config()
        c = stamp(c, c, c.copy(sources = listOf(entry("UCa", "A"))), now = T0).config
        c = stamp(
            c, c, c.copy(sources = c.sources + entry("UCb", "B")),
            now = T0 + ConfigStamp.LOG_COALESCE_MS + 1
        ).config

        assertEquals(listOf("added A", "added B"), c.sync.log.map { it.text })
    }

    @Test
    fun twoDevicesWithTheSameModelNameDoNotCoalesce() {
        // Both parents carry a Pixel 7 Pro, so `who` is identical. Coalescing
        // on the display name would have Mum's line silently replace Dad's,
        // destroying the attribution the log exists to provide.
        var c = config()
        c = stamp(
            c, c, c.copy(sources = listOf(entry("UCa", "A"))),
            now = T0, by = DAD, who = "Pixel 7 Pro"
        ).config
        c = stamp(
            c, c, c.copy(sources = c.sources + entry("UCb", "B")),
            now = T0 + 1000, by = MUM, who = "Pixel 7 Pro"
        ).config

        assertEquals(listOf("added A", "added B"), c.sync.log.map { it.text })
        assertEquals(listOf(DAD, MUM), c.sync.log.map { it.by })
    }

    @Test
    fun differentKindsOfEditDoNotCoalesceEither() {
        var c = config()
        c = stamp(c, c, c.copy(sources = listOf(entry("UCa", "A"))), now = T0).config
        c = stamp(c, c, c.copy(autoplayNext = false), now = T0 + 1000).config
        assertEquals(listOf("added A", "changed app settings"), c.sync.log.map { it.text })
    }

    @Test
    fun theLogIsCapped() {
        var c = config()
        // Alternating devices so nothing coalesces, spaced past the window.
        repeat(SyncMeta.MAX_LOG + 10) { i ->
            c = stamp(
                c, c, c.copy(sources = c.sources + entry("UC$i", "Ch$i")),
                now = T0 + (i + 1L) * (ConfigStamp.LOG_COALESCE_MS + 1),
                by = if (i % 2 == 0) DAD else MUM
            ).config
        }
        assertEquals(SyncMeta.MAX_LOG, c.sync.log.size)
        assertEquals("added Ch${SyncMeta.MAX_LOG + 9}", c.sync.log.last().text)
    }

    @Test
    fun aLogLineCarriesNoValueOnlyADescription() {
        // The Change record has no value field at all, which is a stronger
        // guarantee than filtering credentials out of one: there is nowhere for
        // an API key or a PIN to sit.
        val fields = io.yosemitekids.app.data.ConfigMerge.Change::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("value", ignoreCase = true) })
        assertFalse(fields.any { it.contains("key", ignoreCase = true) })
    }

    // --- pruning -------------------------------------------------------

    @Test
    fun pruningRaisesTheFloorByWhateverItDrops() {
        val gone = (1..SyncMeta.MAX_TOMBSTONES + 5).associate {
            ConfigStamp.src("UC$it") to T0 + it
        }
        val pruned = ConfigStamp.prune(SyncMeta(gone = gone))

        assertEquals(SyncMeta.MAX_TOMBSTONES, pruned.gone.size)
        assertEquals(
            "the newest tombstone dropped becomes the floor, so its subject still cannot return",
            T0 + 5, pruned.floor.getValue("src")
        )
        assertTrue("and the newest survive", pruned.gone.containsKey(ConfigStamp.src("UC${SyncMeta.MAX_TOMBSTONES + 5}")))
    }

    @Test
    fun pruningIsANoOpUnderTheCap() {
        val sync = SyncMeta(gone = mapOf(ConfigStamp.src("UCa") to T0))
        assertEquals(sync, ConfigStamp.prune(sync))
    }

    @Test
    fun aFloorOnlyEverRises() {
        val gone = (1..SyncMeta.MAX_TOMBSTONES + 2).associate { ConfigStamp.src("UC$it") to T0 + it }
        val pruned = ConfigStamp.prune(
            SyncMeta(gone = gone, floor = mapOf("src" to T0 + 9_999_999))
        )
        assertEquals(T0 + 9_999_999, pruned.floor.getValue("src"))
    }

    // --- refresh ---------------------------------------------------------

    @Test
    fun aRefreshMovesAStampWithoutAChange() {
        val base = config(
            sources = listOf(entry("UCaaa")),
            sync = SyncMeta(docAt = T0, at = mapOf(ConfigStamp.MASTER to T0, ConfigStamp.src("UCaaa") to T0))
        ).copy(masterDeviceToken = MUM)
        val out = ConfigStamp.stamped(base, base, base, T0 + 10, "hub", DAD, refresh = setOf(ConfigStamp.MASTER)).config
        assertEquals(T0 + 10, out.sync.at.getValue(ConfigStamp.MASTER))
        assertEquals("the untouched channel keeps its stamp", T0, out.sync.at.getValue(ConfigStamp.src("UCaaa")))
        assertEquals("docAt follows the mint", T0 + 10, out.sync.docAt)
        assertEquals("the value itself is unchanged", MUM, out.masterDeviceToken)
        assertEquals(base.sources, out.sources)
    }

    @Test
    fun withoutARefreshAnUnchangedDocumentIsLeftAlone() {
        val base = config(sync = SyncMeta(docAt = T0, at = mapOf(ConfigStamp.MASTER to T0))).copy(masterDeviceToken = MUM)
        val out = ConfigStamp.stamped(base, base, base, T0 + 10, "hub", DAD).config
        assertEquals(T0, out.sync.at.getValue(ConfigStamp.MASTER))
        assertEquals(T0, out.sync.docAt)
    }
}
