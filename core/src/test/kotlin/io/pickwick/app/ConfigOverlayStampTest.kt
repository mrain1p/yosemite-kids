package io.pickwick.app

import io.pickwick.app.data.ConfigJson
import io.pickwick.app.data.ConfigMerge
import io.pickwick.app.data.ConfigStamp
import io.pickwick.app.data.Limits
import io.pickwick.app.data.Profile
import io.pickwick.app.data.Whitelist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unit families the stamper was not minting.
 *
 * Three of them — per-kid blocks, per-kid allows, and which kid a device is
 * dedicated to — are decided by the merge on their own unit stamps and were
 * minted by nothing at all. A unit with no stamp and no tombstone is absent
 * (`decide` reads `g == 0L -> a > 0`), so every one of those edits was
 * silently discarded by the first peer that merged it.
 *
 * That was live and shipping. A parent blocking a video for one child watched
 * the block reach nothing; the child could still play it on the television.
 * The whole-family block worked, which is what made it invisible.
 *
 * These tests assert end to end — stamp an edit the way ConfigStore.save does,
 * merge it the way a push does — because the bug was entirely invisible at the
 * level of "does the stamper produce something".
 */
class ConfigOverlayStampTest {

    private val T = 1_780_000_000_000L

    private fun kid(id: String, pin: String? = null) = Profile(
        id = id, name = "Leo", colorArgb = 1, avatar = "x", age = 6, pin = pin, limits = Limits()
    )

    /** A stamped starting document both sides share. */
    private val start: Whitelist = ConfigStamp.stamped(
        Whitelist(emptyList(), emptySet()),
        Whitelist(emptyList(), emptySet()),
        Whitelist(emptyList(), emptySet(), profiles = listOf(kid("k1"), kid("k2"))),
        T, "Mum", "m1"
    ).config

    /** An edit made on one device, as ConfigStore.save would write it. */
    private fun edit(change: (Whitelist) -> Whitelist): Whitelist =
        ConfigStamp.stamped(start, start, change(start), T + 1000, "Mum", "m1").config

    /** That edit pushed to a peer holding [start], as the LAN push does. */
    private fun pushedToPeer(edited: Whitelist): Whitelist {
        val merged = ConfigMerge.merge(ConfigJson.toJson(start), ConfigJson.toJson(edited)).merged
        assertNotNull("the peer should have learned something", merged)
        return ConfigJson.fromJson(merged!!)
    }

    @Test
    fun blockingAVideoForOneKidReachesTheOtherDevice() {
        val edited = edit { it.copy(blockedFor = mapOf("v1" to setOf("k1"))) }
        assertEquals(mapOf("v1" to setOf("k1")), pushedToPeer(edited).blockedFor)
    }

    @Test
    fun twoParentsBlockingTheSameVideoForDifferentKidsBothLand() {
        // Why the unit is per (video, kid) rather than per video: one parent
        // blocking it for Leo must not erase the other blocking it for Ada.
        val mum = edit { it.copy(blockedFor = mapOf("v1" to setOf("k1"))) }
        val dad = edit { it.copy(blockedFor = mapOf("v1" to setOf("k2"))) }

        val merged = ConfigMerge.merge(ConfigJson.toJson(mum), ConfigJson.toJson(dad)).merged!!
        assertEquals(setOf("k1", "k2"), ConfigJson.fromJson(merged).blockedFor["v1"])
    }

    @Test
    fun unblockingForOneKidAlsoReachesTheOtherDevice() {
        // The removal direction needs a tombstone, or the peer's surviving copy
        // simply puts the block back and the parent cannot undo it.
        val blocked = edit { it.copy(blockedFor = mapOf("v1" to setOf("k1"))) }
        val cleared = ConfigStamp.stamped(
            blocked, blocked, blocked.copy(blockedFor = emptyMap()), T + 2000, "Mum", "m1"
        ).config

        val merged = ConfigMerge.merge(ConfigJson.toJson(blocked), ConfigJson.toJson(cleared)).merged!!
        assertTrue(ConfigJson.fromJson(merged).blockedFor.isEmpty())
    }

    @Test
    fun liftingAWholeFamilyBlockReachesTheOtherDevice() {
        // The block families fail closed: presence wins ties and lifting is
        // the act that needs proof. The proof is that the lifting side was
        // holding the block when it removed it — so its add stamp has to
        // survive beside the tombstone.
        //
        // ConfigMergeTest.aDeliberateUnblockWorks asserts exactly that shape,
        // but constructs the document by hand. The stamper never produced it:
        // it dropped the add stamp on every removal, which is right for a
        // channel and precisely wrong here. No unblock ever reached a
        // television, and every test passed.
        val blocked = edit { it.copy(blockedVideoIds = setOf("v1")) }
        val lifted = ConfigStamp.stamped(
            blocked, blocked, blocked.copy(blockedVideoIds = emptySet()),
            T + 2000, "Mum", "m1"
        ).config

        val merged = ConfigMerge.merge(
            ConfigJson.toJson(blocked), ConfigJson.toJson(lifted)
        ).merged!!
        assertTrue(
            "the peer still holding the block must accept the lift",
            ConfigJson.fromJson(merged).blockedVideoIds.isEmpty()
        )
    }

    @Test
    fun aBlockStillHoldsAgainstADeviceThatNeverSawIt() {
        // The other half of the polarity, which the fix must not break: a
        // device that never held the block cannot lift it, because it has no
        // add stamp to offer as proof. Keeping this honest is what stops the
        // fix from simply making every removal win.
        val blocked = edit { it.copy(blockedVideoIds = setOf("v1")) }
        val neverSawIt = ConfigStamp.stamped(
            start, start, start.copy(blockedVideoIds = emptySet()), T + 5000, "Dad", "d1"
        ).config

        val merged = ConfigMerge.merge(
            ConfigJson.toJson(blocked), ConfigJson.toJson(neverSawIt)
        ).merged
        val after = merged?.let { ConfigJson.fromJson(it).blockedVideoIds } ?: blocked.blockedVideoIds
        assertEquals(setOf("v1"), after)
    }

    @Test
    fun allowingAVideoForOneKidReachesTheOtherDevice() {
        val edited = edit { it.copy(allowedFor = mapOf("v1" to setOf("k1"))) }
        assertEquals(mapOf("v1" to setOf("k1")), pushedToPeer(edited).allowedFor)
    }

    @Test
    fun dedicatingADeviceToAKidReachesTheOtherDevice() {
        // Without this the television stays on the who's-watching screen, or
        // shows a sibling's profile and their limits.
        val edited = edit { it.copy(deviceProfiles = mapOf("tokB" to "k1")) }
        assertEquals(mapOf("tokB" to "k1"), pushedToPeer(edited).deviceProfiles)
    }

    @Test
    fun reassigningADeviceToAnotherKidWins() {
        // A later reassignment must beat the earlier one rather than resolving
        // on a lexicographic tie-break between two equally-stamped kid ids.
        val first = edit { it.copy(deviceProfiles = mapOf("tokB" to "k1")) }
        val second = ConfigStamp.stamped(
            first, first, first.copy(deviceProfiles = mapOf("tokB" to "k2")), T + 2000, "Mum", "m1"
        ).config

        val merged = ConfigMerge.merge(ConfigJson.toJson(first), ConfigJson.toJson(second)).merged!!
        assertEquals(mapOf("tokB" to "k2"), ConfigJson.fromJson(merged).deviceProfiles)
    }

    @Test
    fun aReAddedKidKeepsTheirPin() {
        // Removing a kid tombstones all six of their units. Re-adding only the
        // kid left the other five tombstoned and unstamped, so the merge
        // stripped them back out of a kid who had just been restored — and a
        // kid without their PIN is a kid whose sibling can open their profile.
        val withPin = ConfigStamp.stamped(
            start, start,
            start.copy(profiles = listOf(kid("k1", pin = "UDLR"), kid("k2"))),
            T + 500, "Mum", "m1"
        ).config
        val deleted = ConfigStamp.stamped(
            withPin, withPin, withPin.copy(profiles = listOf(kid("k2"))), T + 1000, "Mum", "m1"
        ).config
        val restored = ConfigStamp.stamped(
            deleted, deleted,
            deleted.copy(profiles = listOf(kid("k1", pin = "UDLR"), kid("k2"))),
            T + 2000, "Mum", "m1"
        ).config

        // Against a peer that still remembers the deletion.
        val merged = ConfigMerge.merge(ConfigJson.toJson(deleted), ConfigJson.toJson(restored)).merged!!
        val back = ConfigJson.fromJson(merged).profiles.firstOrNull { it.id == "k1" }

        assertNotNull("the kid must come back", back)
        assertEquals("and with the PIN that protects their profile", "UDLR", back!!.pin)
    }

    @Test
    fun everyUnitFamilyTheMergeDecidesIsOneTheStamperCanMint() {
        // The shape of the whole bug: the merge resolved families nothing was
        // minting. Rather than re-listing them by hand, assert that an edit
        // touching each of them produces at least one stamp in that namespace.
        fun namespacesFor(change: (Whitelist) -> Whitelist): Set<String> =
            edit(change).sync.at.keys.map { ConfigStamp.namespace(it) }.toSet()

        assertTrue("blk", "blk" in namespacesFor { it.copy(blockedVideoIds = setOf("v1")) })
        assertTrue("allow", "allow" in namespacesFor { it.copy(aiAllowedVideoIds = setOf("v1")) })
        assertTrue("for", "for" in namespacesFor { it.copy(blockedFor = mapOf("v1" to setOf("k1"))) })
        assertTrue("afor", "afor" in namespacesFor { it.copy(allowedFor = mapOf("v1" to setOf("k1"))) })
        assertTrue("dev", "dev" in namespacesFor { it.copy(deviceProfiles = mapOf("t" to "k1")) })
    }
}
