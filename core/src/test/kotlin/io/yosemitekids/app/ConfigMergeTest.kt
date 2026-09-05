package io.yosemitekids.app

import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.AiConfig
import io.yosemitekids.app.data.ConfigMerge
import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.SyncMeta
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merge.
 *
 * This is the whole reason the sync work exists, so the matrix is deliberately
 * unkind: three devices, partitions, stale copies, deletes racing edits, and a
 * legacy build in the middle of it. The properties that matter most are the
 * boring-sounding ones — commutative, associative, idempotent — because they
 * are what stop two devices pushing at each other forever.
 */
class ConfigMergeTest {

    private val T = 1_780_000_000_000L

    private fun entry(id: String, label: String? = null) = WhitelistEntry(
        id = id,
        url = "https://www.youtube.com/channel/$id",
        label = label,
        kind = SourceKind.CHANNEL
    )

    /** A config as bytes, with an explicit sync blob so stamps are exact. */
    private fun doc(
        sources: List<WhitelistEntry> = emptyList(),
        blocked: Set<String> = emptySet(),
        profiles: List<Profile> = emptyList(),
        limits: Limits = Limits(),
        ai: AiConfig = AiConfig(),
        autoplay: Boolean = true,
        at: Map<String, Long> = emptyMap(),
        gone: Map<String, Long> = emptyMap(),
        floor: Map<String, Long> = emptyMap(),
        log: List<ConfigMerge.Change> = emptyList()
    ): String = pinUpdatedAt(
        ConfigJson.toJson(
            Whitelist(
                sources = sources,
                blockedVideoIds = blocked,
                limits = limits,
                ai = ai,
                profiles = profiles,
                autoplayNext = autoplay,
                sync = if (at.isEmpty() && gone.isEmpty() && floor.isEmpty() && log.isEmpty()) {
                    SyncMeta.EMPTY
                } else SyncMeta(docAt = (at.values + gone.values + 0L).max(), at = at, gone = gone, floor = floor, log = log)
            )
        ),
        (at.values + gone.values + floor.values + T).max()
    )

    /**
     * ConfigJson.toJson stamps updatedAt from the wall clock, and the merge's
     * sameDoc compares every field of the document including that one. Two
     * fixture documents built either side of a millisecond boundary therefore
     * differ by a field no test here is about, and assertions like "we learn
     * nothing" fail perhaps one run in a hundred.
     *
     * That is worse than it sounds in this suite specifically: it guards the
     * merge, so an intermittent red is indistinguishable from a real
     * regression and gets re-run rather than read. Pinning it makes every
     * document here a function of its arguments alone.
     */
    private fun pinUpdatedAt(json: String, value: Long): String =
        JSONObject(json).put("updatedAt", value).toString(2)

    /** A config with no sync blob at all — what an older build produces. */
    private fun legacyDoc(
        sources: List<WhitelistEntry> = emptyList(),
        blocked: Set<String> = emptySet()
    ): String = ConfigJson.toJson(Whitelist(sources = sources, blockedVideoIds = blocked))

    private fun mergedOf(local: String, incoming: String): Whitelist? =
        ConfigMerge.merge(local, incoming).merged?.let { ConfigJson.fromJson(it) }

    /** The merge result as a config, falling back to the local side when nothing changed. */
    private fun settle(local: String, incoming: String): Whitelist =
        mergedOf(local, incoming) ?: ConfigJson.fromJson(local)

    private fun ids(w: Whitelist) = w.sources.map { it.id }.toSet()

    // --- the bug this exists for ---------------------------------------

    @Test
    fun disjointEditsBothSurvive() {
        // Dad adds a channel; Mum shortens the family sitting length. Today
        // one of these is silently lost, whichever config is serialized last.
        val dad = doc(
            sources = listOf(entry("UCaaa", "SciShow Kids")),
            at = mapOf(ConfigStamp.src("UCaaa") to T + 1)
        )
        val mum = doc(
            limits = Limits(sessionMinutes = 30),
            at = mapOf(ConfigStamp.LIM_RULES to T + 2)
        )

        val out = settle(dad, mum)
        assertEquals(setOf("UCaaa"), ids(out))
        assertEquals(30, out.limits.sessionMinutes)
        assertTrue(ConfigMerge.merge(dad, mum).collisions.isEmpty())
    }

    @Test
    fun twoParentsAddingDifferentChannelsBothLand() {
        val dad = doc(sources = listOf(entry("UCaaa")), at = mapOf(ConfigStamp.src("UCaaa") to T + 1))
        val mum = doc(sources = listOf(entry("UCbbb")), at = mapOf(ConfigStamp.src("UCbbb") to T + 2))
        assertEquals(setOf("UCaaa", "UCbbb"), ids(settle(dad, mum)))
    }

    // --- the properties that stop a push loop --------------------------

    @Test
    fun mergingTwiceEqualsMergingOnce() {
        val a = doc(sources = listOf(entry("UCaaa")), at = mapOf(ConfigStamp.src("UCaaa") to T + 1))
        val b = doc(sources = listOf(entry("UCbbb")), at = mapOf(ConfigStamp.src("UCbbb") to T + 2))
        val once = ConfigMerge.merge(a, b).merged
        assertNotNull(once)
        assertNull("a second merge of the same peer must be a no-op", ConfigMerge.merge(once, b).merged)
    }

    @Test
    fun disjointMergeIsCommutative() {
        val a = doc(sources = listOf(entry("UCaaa")), at = mapOf(ConfigStamp.src("UCaaa") to T + 1))
        val b = doc(sources = listOf(entry("UCbbb")), at = mapOf(ConfigStamp.src("UCbbb") to T + 2))

        val ab = settle(a, b)
        val ba = settle(b, a)
        assertEquals(ConfigJson.fingerprint(ab), ConfigJson.fingerprint(ba))
        assertEquals(ConfigMerge.syncHash(ab.sync), ConfigMerge.syncHash(ba.sync))
    }

    @Test
    fun mergeIsAssociativeOverThreeDevices() {
        val a = doc(sources = listOf(entry("UCaaa")), at = mapOf(ConfigStamp.src("UCaaa") to T + 1))
        val b = doc(sources = listOf(entry("UCbbb")), at = mapOf(ConfigStamp.src("UCbbb") to T + 2))
        val c = doc(
            sources = listOf(entry("UCccc")), blocked = setOf("v1"),
            at = mapOf(ConfigStamp.src("UCccc") to T + 3, ConfigStamp.blk("v1") to T + 3)
        )

        val left = ConfigMerge.merge(ConfigMerge.merge(a, b).merged ?: a, c).merged!!
        val right = ConfigMerge.merge(a, ConfigMerge.merge(b, c).merged ?: b).merged!!
        val l = ConfigJson.fromJson(left)
        val r = ConfigJson.fromJson(right)

        assertEquals(ConfigJson.fingerprint(l), ConfigJson.fingerprint(r))
        assertEquals(ConfigMerge.syncHash(l.sync), ConfigMerge.syncHash(r.sync))
        assertEquals(setOf("UCaaa", "UCbbb", "UCccc"), ids(l))
    }

    @Test
    fun mergeSignatureTakesNoClock() {
        // Structural, not stylistic: idempotence and associativity above hold
        // only because nothing in here can read the time.
        val m = ConfigMerge::class.java.methods.single { it.name == "merge" }
        // local, incoming, and the API key this device holds — which has to
        // be passed in because the local document never carries one.
        assertEquals(3, m.parameterCount)
        m.parameterTypes.forEach {
            assertFalse("merge must take no clock: $it", it == Long::class.java || it.name.contains("Clock"))
        }
    }

    @Test
    fun equalStampsWithDifferentContentConvergeFromBothDirections() {
        // Two devices minted the same millisecond. Whatever is chosen, both
        // must choose the same thing or they push at each other forever.
        val a = doc(sources = listOf(entry("UCaaa", "Name A")), at = mapOf(ConfigStamp.src("UCaaa") to T))
        val b = doc(sources = listOf(entry("UCaaa", "Name B")), at = mapOf(ConfigStamp.src("UCaaa") to T))

        val ab = settle(a, b)
        val ba = settle(b, a)
        assertEquals(ab.sources.single().label, ba.sources.single().label)
    }

    // --- deletes --------------------------------------------------------

    @Test
    fun aTombstoneBeatsAnOlderAdd() {
        val stillHasIt = doc(
            sources = listOf(entry("UCaaa")),
            at = mapOf(ConfigStamp.src("UCaaa") to T + 1)
        )
        val deletedIt = doc(gone = mapOf(ConfigStamp.src("UCaaa") to T + 2))

        val out = settle(stillHasIt, deletedIt)
        assertTrue("a channel a parent removed must not come back", ids(out).isEmpty())
        assertEquals(T + 2, out.sync.gone[ConfigStamp.src("UCaaa")])
    }

    @Test
    fun aSettledDeleteStaysEnforceableAgainstAStaleCopy() {
        // The shape a real hub log showed: a delete lands, then a push in
        // which NOBODY lists the unit any more, then the stale copy again.
        // Step two used to drop the tombstone (no loop visits a unit no side
        // lists), so step three re-added the channel as if it were new, the
        // parent deleted it again, and the hub flipped between two hashes.
        val stale = doc(
            sources = listOf(entry("UCaaa")),
            at = mapOf(ConfigStamp.src("UCaaa") to T + 1)
        )
        val deleted = doc(gone = mapOf(ConfigStamp.src("UCaaa") to T + 2))
        val afterDelete = settleJson(stale, deleted)

        // A document that lists UCaaa on neither side and knows nothing new.
        val unrelated = doc(
            sources = listOf(entry("UCbbb")),
            at = mapOf(ConfigStamp.src("UCbbb") to T + 3)
        )
        val afterUnrelated = settleJson(afterDelete, unrelated)
        val kept = ConfigJson.fromJson(afterUnrelated)
        assertEquals(
            "a settled tombstone must survive a merge that never mentions its subject",
            T + 2, kept.sync.gone[ConfigStamp.src("UCaaa")]
        )
        // And merging the same peer again changes nothing: the merge is a
        // fixed point of itself, blob included.
        assertNull(mergedOf(afterUnrelated, unrelated))

        val resurrected = settle(afterUnrelated, stale)
        assertEquals(
            "a stale copy that never saw the delete must not bring the channel back",
            setOf("UCbbb"), ids(resurrected)
        )
    }

    @Test
    fun aRelabelOnACopyThatNeverSawTheDeleteDoesNotResurrect() {
        // Delete wins over edit: removing a channel is a considered safety
        // call, and a label edit must not quietly undo it.
        val relabelled = doc(
            sources = listOf(entry("UCaaa", "Renamed")),
            at = mapOf(ConfigStamp.src("UCaaa") to T + 5)
        )
        val deleted = doc(
            gone = mapOf(ConfigStamp.src("UCaaa") to T + 2),
            at = mapOf(ConfigStamp.SETTINGS to T + 2)
        )
        // The relabel is *newer* but its author never saw the tombstone, so it
        // is a stale copy talking rather than a deliberate reversal.
        assertTrue(ids(settle(relabelled, deleted)).isEmpty())
        assertTrue(ids(settle(deleted, relabelled)).isEmpty())
    }

    @Test
    fun aDeliberateReAddBeatsATombstone() {
        // The re-adder carries the tombstone, which is the proof the add came
        // after the delete.
        val readded = doc(
            sources = listOf(entry("UCaaa")),
            at = mapOf(ConfigStamp.src("UCaaa") to T + 3),
            gone = mapOf(ConfigStamp.src("UCaaa") to T + 2)
        )
        val holder = doc(gone = mapOf(ConfigStamp.src("UCaaa") to T + 2))

        assertEquals(setOf("UCaaa"), ids(settle(readded, holder)))
        assertEquals(setOf("UCaaa"), ids(settle(holder, readded)))
    }

    @Test
    fun aReAddIsIdempotentUnderRepeatedMerges() {
        // The regression that made this rule necessary: a re-added channel
        // reappearing and vanishing on a five-minute cycle, forever.
        val readded = doc(
            sources = listOf(entry("UCaaa")),
            at = mapOf(ConfigStamp.src("UCaaa") to T + 3),
            gone = mapOf(ConfigStamp.src("UCaaa") to T + 2)
        )
        val holder = doc(gone = mapOf(ConfigStamp.src("UCaaa") to T + 2))

        var cur = readded
        repeat(4) {
            cur = ConfigMerge.merge(cur, holder).merged ?: cur
            assertEquals("it must not flicker", setOf("UCaaa"), ids(ConfigJson.fromJson(cur)))
        }
    }

    @Test
    fun aReAddSurvivesAThirdPartyThatSawNeitherAct() {
        val readded = doc(
            sources = listOf(entry("UCaaa")),
            at = mapOf(ConfigStamp.src("UCaaa") to T + 3),
            gone = mapOf(ConfigStamp.src("UCaaa") to T + 2)
        )
        val holder = doc(gone = mapOf(ConfigStamp.src("UCaaa") to T + 2))
        val bystander = doc(sources = listOf(entry("UCzzz")), at = mapOf(ConfigStamp.src("UCzzz") to T))

        // Either order. `merged` is legitimately null on the last step of the
        // second chain — by then there is nothing left to learn — so fall back
        // to the accumulated document rather than asserting a write happened.
        val viaHolder = settleJson(settleJson(readded, holder), bystander)
        val viaBystander = settleJson(settleJson(readded, bystander), holder)
        assertEquals(setOf("UCaaa", "UCzzz"), ids(ConfigJson.fromJson(viaHolder)))
        assertEquals(setOf("UCaaa", "UCzzz"), ids(ConfigJson.fromJson(viaBystander)))
    }

    @Test
    fun absenceAloneNeverDeletes() {
        val many = doc(
            sources = listOf(entry("UCaaa"), entry("UCbbb"), entry("UCccc")),
            at = mapOf(
                ConfigStamp.src("UCaaa") to T + 1,
                ConfigStamp.src("UCbbb") to T + 1,
                ConfigStamp.src("UCccc") to T + 1
            )
        )
        // Newer, and holding only one of them — but with no tombstones, so it
        // is asserting nothing about the other two.
        val one = doc(sources = listOf(entry("UCaaa")), at = mapOf(ConfigStamp.src("UCaaa") to T + 9))

        assertEquals(setOf("UCaaa", "UCbbb", "UCccc"), ids(settle(many, one)))
    }

    @Test
    fun removingAKidTombstonesEveryUnitOfTheirsAndScrubsReferences() {
        val kid = Profile(id = "k1", name = "Leo")
        val hasKid = doc(
            sources = listOf(entry("UCaaa").copy(profileIds = setOf("k1"))),
            profiles = listOf(kid),
            at = mapOf(ConfigStamp.src("UCaaa") to T, ConfigStamp.kid("k1") to T)
        )
        val kidGone = doc(
            sources = listOf(entry("UCaaa").copy(profileIds = setOf("k1"))),
            at = mapOf(ConfigStamp.src("UCaaa") to T),
            gone = mapOf(ConfigStamp.kid("k1") to T + 5)
        )

        val out = settle(hasKid, kidGone)
        assertTrue(out.profiles.isEmpty())
        // The entry must be HIDDEN, not widened to everyone. An empty list
        // means "visible to all", so failing open here would show a channel
        // restricted to a removed teenager to the six-year-old.
        assertEquals(setOf(ConfigMerge.PROFILE_NONE), out.sources.single().profileIds)
    }

    // --- permissions bias ----------------------------------------------

    @Test
    fun aBlockHoldsAgainstAnUnblockFromAPhoneThatNeverSawIt() {
        val blocked = doc(blocked = setOf("v1"), at = mapOf(ConfigStamp.blk("v1") to T + 1))
        // Newer, but never saw the block: a stale copy, not a decision.
        val unblocked = doc(gone = mapOf(ConfigStamp.blk("v1") to T + 5))

        assertEquals(setOf("v1"), settle(blocked, unblocked).blockedVideoIds)
        assertEquals(setOf("v1"), settle(unblocked, blocked).blockedVideoIds)
    }

    @Test
    fun aDeliberateUnblockWorks() {
        // The unblocker's copy carries the block, so this is a real reversal —
        // a parent responding to "can I watch this?" ten minutes later.
        val blocked = doc(blocked = setOf("v1"), at = mapOf(ConfigStamp.blk("v1") to T + 1))
        val unblocked = doc(
            at = mapOf(ConfigStamp.blk("v1") to T + 1),
            gone = mapOf(ConfigStamp.blk("v1") to T + 5)
        )
        assertTrue(settle(blocked, unblocked).blockedVideoIds.isEmpty())
        assertTrue(settle(unblocked, blocked).blockedVideoIds.isEmpty())
    }

    @Test
    fun twoDifferentBlocksBothApply() {
        val a = doc(blocked = setOf("v1"), at = mapOf(ConfigStamp.blk("v1") to T + 1))
        val b = doc(blocked = setOf("v2"), at = mapOf(ConfigStamp.blk("v2") to T + 2))
        assertEquals(setOf("v1", "v2"), settle(a, b).blockedVideoIds)
    }

    // --- legacy peers ---------------------------------------------------

    @Test
    fun aLegacyPushDeletesNothing() {
        // An old build restamps updatedAt at serialization time, so its
        // document always claims to be brand new. If absence were evidence, a
        // phone out of a drawer would wipe the family's whole setup.
        val current = doc(
            sources = listOf(entry("UCaaa"), entry("UCbbb")),
            blocked = setOf("v1"),
            at = mapOf(
                ConfigStamp.src("UCaaa") to T, ConfigStamp.src("UCbbb") to T,
                ConfigStamp.blk("v1") to T
            )
        )
        val old = legacyDoc(sources = listOf(entry("UCaaa")))

        val out = settle(current, old)
        assertEquals(setOf("UCaaa", "UCbbb"), ids(out))
        assertEquals(setOf("v1"), out.blockedVideoIds)
        assertTrue("no tombstone may be derived from a legacy document", out.sync.gone.isEmpty())
    }

    @Test
    fun anEmptyLegacyDocumentCannotWipeAnything() {
        val current = doc(
            sources = listOf(entry("UCaaa")),
            at = mapOf(ConfigStamp.src("UCaaa") to T)
        )
        assertEquals(setOf("UCaaa"), ids(settle(current, legacyDoc())))
    }

    @Test
    fun aLegacyDocumentStillContributesWhatItHolds() {
        val current = doc(sources = listOf(entry("UCaaa")), at = mapOf(ConfigStamp.src("UCaaa") to T))
        val old = legacyDoc(sources = listOf(entry("UCaaa"), entry("UCnew")))
        assertEquals(setOf("UCaaa", "UCnew"), ids(settle(current, old)))
    }

    @Test
    fun twoLegacyDocumentsMergeByUnionAndKeepFileOrder() {
        val a = legacyDoc(sources = listOf(entry("UCaaa"), entry("UCbbb")))
        val b = legacyDoc(sources = listOf(entry("UCaaa"), entry("UCccc")))
        val out = settle(a, b)
        assertEquals(setOf("UCaaa", "UCbbb", "UCccc"), ids(out))
    }

    // --- secrets --------------------------------------------------------

    @Test
    fun theMergedDocumentNeverContainsAnApiKey() {
        val a = doc(ai = AiConfig(model = "m", apiKey = "sk-local-secret"), at = mapOf(ConfigStamp.AI to T))
        val b = doc(
            ai = AiConfig(model = "m2", apiKey = "sk-remote-secret"),
            at = mapOf(ConfigStamp.AI to T + 1)
        )

        val result = ConfigMerge.merge(a, b)
        val merged = result.merged
        assertNotNull(merged)
        assertFalse(merged!!.contains("sk-local-secret"))
        assertFalse(merged.contains("sk-remote-secret"))
        assertEquals("the key travels out of band", "sk-remote-secret", result.apiKey)
    }

    @Test
    fun aKeylessPeerDoesNotWipeOurKey() {
        // Backups strip the key, and a legacy device may never have had one.
        val a = doc(ai = AiConfig(model = "m", apiKey = "sk-ours"), at = mapOf(ConfigStamp.AI to T))
        val b = doc(ai = AiConfig(model = "m"), at = mapOf(ConfigStamp.AI to T + 1))
        assertEquals("sk-ours", ConfigMerge.merge(a, b).apiKey)
    }

    @Test
    fun changingTheJudgingInputsMovesTheRulesVersion() {
        // Cached verdicts are keyed on this. Keeping one side's number would
        // hand devices rules they have never screened under a version they
        // already have answers for — and those answers gate what a child sees.
        val a = doc(
            ai = AiConfig(model = "m", rules = "no scary stuff", rulesVersion = 4),
            at = mapOf(ConfigStamp.AI to T)
        )
        val b = doc(
            ai = AiConfig(model = "m", rules = "no scary stuff at all", rulesVersion = 4),
            at = mapOf(ConfigStamp.AI to T + 1)
        )
        assertEquals(5, settle(a, b).ai.rulesVersion)
    }

    @Test
    fun anUnchangedAiBlockDoesNotBumpTheRulesVersion() {
        val ai = AiConfig(model = "m", rules = "same", rulesVersion = 4)
        val a = doc(ai = ai, at = mapOf(ConfigStamp.AI to T))
        val b = doc(ai = ai, at = mapOf(ConfigStamp.AI to T + 1))
        assertEquals(4, settle(a, b).ai.rulesVersion)
    }

    // --- master election ------------------------------------------------

    @Test
    fun masterConvergesOnATieRatherThanFlipping() {
        // "Keep the local one" is not commutative, so two co-parents who both
        // claimed would never converge — and would both keep running the
        // rate-limit-expensive crawl.
        val a = ConfigJson.toJson(
            Whitelist(emptyList(), emptySet(), masterDeviceToken = "bbbb", sync = SyncMeta(at = mapOf(ConfigStamp.MASTER to T)))
        )
        val b = ConfigJson.toJson(
            Whitelist(emptyList(), emptySet(), masterDeviceToken = "aaaa", sync = SyncMeta(at = mapOf(ConfigStamp.MASTER to T)))
        )
        assertEquals("aaaa", settle(a, b).masterDeviceToken)
        assertEquals("aaaa", settle(b, a).masterDeviceToken)
    }

    // --- results --------------------------------------------------------

    @Test
    fun nothingNewMeansNoWrite() {
        val a = doc(sources = listOf(entry("UCaaa")), at = mapOf(ConfigStamp.src("UCaaa") to T))
        assertNull("an identical peer must not cause a write", ConfigMerge.merge(a, a).merged)
    }

    @Test
    fun aPeerMissingSomethingWeHoldIsReportedAsBehind() {
        val ours = doc(
            sources = listOf(entry("UCaaa"), entry("UCbbb")),
            at = mapOf(ConfigStamp.src("UCaaa") to T, ConfigStamp.src("UCbbb") to T)
        )
        val theirs = doc(sources = listOf(entry("UCaaa")), at = mapOf(ConfigStamp.src("UCaaa") to T))
        val r = ConfigMerge.merge(ours, theirs)
        assertNull("we learn nothing", r.merged)
        assertTrue("but they are missing a channel, so push back", r.peerBehind)
    }

    @Test
    fun aCollisionIsReportedWhenBothEditedAndWeLost() {
        val mine = doc(limits = Limits(sessionMinutes = 45), at = mapOf(ConfigStamp.LIM_RULES to T + 1))
        val theirs = doc(limits = Limits(sessionMinutes = 30), at = mapOf(ConfigStamp.LIM_RULES to T + 2))

        val r = ConfigMerge.merge(mine, theirs)
        assertEquals(1, r.collisions.size)
        assertEquals(ConfigStamp.LIM_RULES, r.collisions.single().unit)
        assertEquals(30, settle(mine, theirs).limits.sessionMinutes)
    }

    @Test
    fun adoptingSomethingWeNeverTouchedIsNotACollision() {
        val mine = doc(sources = listOf(entry("UCaaa")), at = mapOf(ConfigStamp.src("UCaaa") to T))
        val theirs = doc(
            sources = listOf(entry("UCaaa"), entry("UCbbb")),
            at = mapOf(ConfigStamp.src("UCaaa") to T, ConfigStamp.src("UCbbb") to T + 5)
        )
        assertTrue(ConfigMerge.merge(mine, theirs).collisions.isEmpty())
    }

    @Test
    fun garbageIsRefusedWithoutTouchingAnything() {
        val mine = doc(sources = listOf(entry("UCaaa")), at = mapOf(ConfigStamp.src("UCaaa") to T))
        listOf("{{{", "", "not json at all").forEach { junk ->
            val r = ConfigMerge.merge(mine, junk)
            assertNull("junk must never produce a write: '$junk'", r.merged)
            assertFalse(r.peerBehind)
        }
    }

    @Test
    fun aFreshInstallAdoptsThePeerWholesale() {
        val theirs = doc(
            sources = listOf(entry("UCaaa")),
            at = mapOf(ConfigStamp.src("UCaaa") to T)
        )
        assertEquals(setOf("UCaaa"), ids(ConfigJson.fromJson(ConfigMerge.merge(null, theirs).merged!!)))
    }

    @Test
    fun anUnreadableLocalFileAdoptsThePeerRatherThanSpreadingEmptiness() {
        val theirs = doc(sources = listOf(entry("UCaaa")), at = mapOf(ConfigStamp.src("UCaaa") to T))
        assertEquals(
            setOf("UCaaa"),
            ids(ConfigJson.fromJson(ConfigMerge.merge("{ truncated", theirs).merged!!))
        )
    }

    // --- the log --------------------------------------------------------

    @Test
    fun logsUnionByIdAndTheCoParentsLinesAreReportedAsLearned() {
        val mineLine = ConfigMerge.Change("src.add", "added A", id = "aaaa", at = T, shownAt = T, by = "d1", who = "Dad")
        val theirLine = ConfigMerge.Change("src.add", "added B", id = "bbbb", at = T + 1, shownAt = T + 1, by = "d2", who = "Mum")
        val mine = doc(at = mapOf(ConfigStamp.SETTINGS to T), log = listOf(mineLine))
        val theirs = doc(at = mapOf(ConfigStamp.SETTINGS to T + 1), log = listOf(theirLine))

        val r = ConfigMerge.merge(mine, theirs)
        assertEquals(listOf("added B"), r.learned.map { it.text })
        assertEquals(
            listOf("added A", "added B"),
            ConfigJson.fromJson(r.merged!!).sync.log.map { it.text }
        )
    }

    @Test
    fun theLogDoesNotDuplicateALineBothSidesAlreadyHave() {
        val shared = ConfigMerge.Change("src.add", "added A", id = "aaaa", at = T, shownAt = T, by = "d1", who = "Dad")
        val mine = doc(at = mapOf(ConfigStamp.src("UCaaa") to T), log = listOf(shared))
        val theirs = doc(
            sources = listOf(entry("UCaaa")),
            at = mapOf(ConfigStamp.src("UCaaa") to T + 1), log = listOf(shared)
        )
        assertEquals(1, ConfigJson.fromJson(settleJson(mine, theirs)).sync.log.size)
    }

    private fun settleJson(local: String, incoming: String): String =
        ConfigMerge.merge(local, incoming).merged ?: local

    // --- the floor ------------------------------------------------------

    @Test
    fun aRowBelowTheFloorIsNotAdmitted() {
        // The tombstone was evicted by the cap, but the floor it left behind
        // still refuses its subject.
        val stale = doc(sources = listOf(entry("UCold")), at = mapOf(ConfigStamp.src("UCold") to T + 1))
        val pruned = doc(
            at = mapOf(ConfigStamp.SETTINGS to T + 9),
            floor = mapOf("src" to T + 5)
        )
        assertTrue(ids(settle(stale, pruned)).isEmpty())
    }

    @Test
    fun anUnstampedRowIsNeverRefusedByTheFloor() {
        // at == 0 is "no evidence either way", which a floor is not entitled
        // to read as a delete. Without this exemption a restored backup, or a
        // freshly upgraded device, loses everything on first contact with a
        // peer that has ever evicted a tombstone.
        val restored = legacyDoc(sources = listOf(entry("UCold")))
        val pruned = doc(at = mapOf(ConfigStamp.SETTINGS to T + 9), floor = mapOf("src" to T + 5))
        assertEquals(setOf("UCold"), ids(settle(restored, pruned)))
    }
}
