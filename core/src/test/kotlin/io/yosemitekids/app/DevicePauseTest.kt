package io.yosemitekids.app

import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.PAUSE_UNTIL_RESUMED
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning off one SCREEN.
 *
 * The owner: "we have the ability to freeze or turn off watching for profiles
 * but we should also be able to have it for devces as well." A pause on a kid
 * follows the child wherever they go; this one stays with the television in the
 * lounge, whoever sits down at it, and says nothing about anybody's rules.
 *
 * The thing worth testing is that it is **not a new kind of rule**. It arrives
 * at every enforcement path as a pause, through [Whitelist.limitsFor], which
 * already took the later of the family's and the kid's — so the player, the
 * prefs mirror and the hub learn nothing. A test that only checked the map
 * round-tripped would miss the whole of it.
 */
class DevicePauseTest {

    private val TV = "aa11bb22cc33dd44ee55ff6600112233"
    private val PHONE = "ffeeddccbbaa99887766554433221100"
    private val now = 1_790_000_000_000L
    private val tonight = now + 4 * 3_600_000L
    private val sunday = now + 3 * 24 * 3_600_000L

    private fun entry(id: String) = WhitelistEntry(
        id, "https://www.youtube.com/channel/$id", "Channel $id", SourceKind.CHANNEL
    )
    private val plain = Whitelist(listOf(entry("UCa")), emptySet())
    private val ada = Profile(id = "ada", name = "Ada")

    // --- the resolution, which is the whole feature -----------------------

    @Test
    fun `a frozen screen stops a kid who has no pause of their own`() {
        val config = plain.copy(
            profiles = listOf(ada),
            devicePaused = mapOf(TV to tonight)
        )
        assertEquals(tonight, config.limitsFor("ada", TV).pausedUntilMillis)
        // And not on the other device, which is the point of it being per-device.
        assertNull(config.limitsFor("ada", PHONE).pausedUntilMillis)
    }

    @Test
    fun `the latest of the three pauses wins, whichever one it is`() {
        // limitsFor already took the later of family and kid. A device is a third
        // term in the same max, asserted from each direction because a
        // precedence order between them would pass exactly one of these.
        val deviceLatest = plain.copy(
            limits = Limits(pausedUntilMillis = now + 60_000),
            profiles = listOf(ada.copy(limits = Limits(pausedUntilMillis = now + 120_000))),
            devicePaused = mapOf(TV to sunday)
        )
        assertEquals(sunday, deviceLatest.limitsFor("ada", TV).pausedUntilMillis)

        val kidLatest = plain.copy(
            limits = Limits(pausedUntilMillis = now + 60_000),
            profiles = listOf(ada.copy(limits = Limits(pausedUntilMillis = sunday))),
            devicePaused = mapOf(TV to now + 120_000)
        )
        assertEquals(sunday, kidLatest.limitsFor("ada", TV).pausedUntilMillis)

        val familyLatest = plain.copy(
            limits = Limits(pausedUntilMillis = sunday),
            profiles = listOf(ada.copy(limits = Limits(pausedUntilMillis = now + 60_000))),
            devicePaused = mapOf(TV to now + 120_000)
        )
        assertEquals(sunday, familyLatest.limitsFor("ada", TV).pausedUntilMillis)
    }

    @Test
    fun `a screen can be off until someone turns it back on`() {
        // PAUSE_UNTIL_RESUMED is just an instant, so it needs nothing of its own
        // here — but it must come through the max rather than being clamped by
        // it, and it must still read as open-ended to whatever puts it in words.
        val config = plain.copy(
            profiles = listOf(ada.copy(limits = Limits(pausedUntilMillis = tonight))),
            devicePaused = mapOf(TV to PAUSE_UNTIL_RESUMED)
        )
        val resolved = config.limitsFor("ada", TV)
        assertEquals(PAUSE_UNTIL_RESUMED, resolved.pausedUntilMillis)
        assertTrue(resolved.pausedIndefinitely)
    }

    @Test
    fun `a household with no kids at all can still freeze a screen`() {
        // With no profiles the family's own limits ARE the rules in force, and
        // the early return that used to hand them back untouched would have
        // dropped the device's pause on the floor.
        val config = plain.copy(devicePaused = mapOf(TV to tonight))
        assertEquals(tonight, config.limitsFor(null, TV).pausedUntilMillis)
        assertEquals(tonight, config.limitsFor("nobody", TV).pausedUntilMillis)
        assertNull(config.limitsFor(null, PHONE).pausedUntilMillis)
    }

    @Test
    fun `naming no device resolves exactly as it did before this existed`() {
        // The hub's path. A browser is not a paired device and has no token, so
        // the hub resolves with none — and must get the family-and-kid answer
        // untouched, freezes in the document or not.
        val config = plain.copy(
            limits = Limits(sessionMinutes = 30),
            profiles = listOf(ada.copy(limits = Limits(sessionMinutes = 20))),
            devicePaused = mapOf(TV to PAUSE_UNTIL_RESUMED, PHONE to sunday)
        )
        assertNull(config.limitsFor("ada").pausedUntilMillis)
        assertEquals(20, config.limitsFor("ada").sessionMinutes)
        assertNull(config.limitsFor(null).pausedUntilMillis)
    }

    @Test
    fun `nothing paused hands back the very rules the caller would have had`() {
        // Identity, not just equality: the common path must not start copying a
        // Limits on every read because a map happens to exist.
        val config = plain.copy(
            limits = Limits(sessionMinutes = 30),
            profiles = listOf(ada.copy(limits = Limits(sessionMinutes = 20))),
            devicePaused = mapOf(TV to tonight)
        )
        assertTrue(config.limitsFor("ada", PHONE) === config.profiles[0].limits)
        assertTrue(config.limitsFor(null, PHONE) === config.limits)
    }

    // --- the four canonical tests (sync skill §4) -------------------------

    @Test
    fun `a frozen screen survives a JSON round-trip`() {
        val before = plain.copy(devicePaused = mapOf(TV to tonight, PHONE to PAUSE_UNTIL_RESUMED))
        val after = ConfigJson.fromJson(ConfigJson.toJson(before))
        assertEquals(tonight, after.devicePaused[TV])
        // The sentinel is a long like any other and must not be truncated or
        // re-read as a plain large number, or an open-ended freeze lapses.
        assertEquals(PAUSE_UNTIL_RESUMED, after.devicePaused[PHONE])
        assertEquals(2, after.devicePaused.size)
    }

    @Test
    fun `no frozen screen is omitted from JSON, byte for byte as before`() {
        val json = ConfigJson.toJson(plain)
        assertFalse(json.contains("devicePaused"))
        fun bytes(w: Whitelist) =
            ConfigJson.toJson(w).replace(Regex("\"updatedAt\": \\d+"), "\"updatedAt\": 0")
        assertEquals(bytes(plain), bytes(plain.copy(devicePaused = emptyMap())))
        assertTrue(ConfigJson.fromJson(json).devicePaused.isEmpty())
    }

    @Test
    fun `configs with no frozen screen keep their pre-feature fingerprint`() {
        val base = plain.copy(
            limits = Limits(sessionMinutes = 30),
            deviceProfiles = mapOf(TV to "ada")
        )
        assertEquals(
            ConfigJson.fingerprint(base),
            ConfigJson.fingerprint(base.copy(devicePaused = emptyMap()))
        )
    }

    @Test
    fun `freezing a screen moves the fingerprint so the reconcile delivers it`() {
        val base = plain.copy(deviceProfiles = mapOf(TV to "ada"))
        assertNotEquals(
            ConfigJson.fingerprint(base),
            ConfigJson.fingerprint(base.copy(devicePaused = mapOf(TV to tonight)))
        )
        // Two devices are two terms: freezing the phone must not hash the same
        // as freezing the television, or one of the two never arrives.
        assertNotEquals(
            ConfigJson.fingerprint(base.copy(devicePaused = mapOf(TV to tonight))),
            ConfigJson.fingerprint(base.copy(devicePaused = mapOf(PHONE to tonight)))
        )
        // And so are two instants on one device: "until midnight" and "until I
        // turn it back on" are different answers and the TV has to hear which.
        assertNotEquals(
            ConfigJson.fingerprint(base.copy(devicePaused = mapOf(TV to tonight))),
            ConfigJson.fingerprint(base.copy(devicePaused = mapOf(TV to PAUSE_UNTIL_RESUMED)))
        )
    }

    // --- the unit it rides ------------------------------------------------

    @Test
    fun `the unit is its own, and is not the device's kid assignment`() {
        // kid and kid.pause are two units for exactly this reason: one parent
        // reassigning the lounge TV to another child and one parent turning it
        // off for the evening are different edits, and on one unit the later
        // stamp would silently discard the other.
        assertNotEquals(ConfigStamp.dev(TV), ConfigStamp.devPause(TV))
        assertEquals("dev.pause|$TV", ConfigStamp.devPause(TV))
    }
}
