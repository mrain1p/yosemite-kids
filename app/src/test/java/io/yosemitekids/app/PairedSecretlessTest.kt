package io.yosemitekids.app

import io.yosemitekids.app.data.PairedDevice
import io.yosemitekids.app.data.PairingStore
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form of the paired list, and the two one-way doors in it.
 *
 * `secretless` decides which fingerprint a peer is judged against, so an entry
 * that loses the flag reads as permanently out of sync and an entry that
 * gains it wrongly can hide a real difference. Neither is visible until a
 * parent is staring at a device that will not settle.
 *
 * `isHub` answers a different question — what kind of thing is this — and the
 * two were one flag for as long as a hub was the only keyless peer. They stop
 * coinciding the moment a hub holds an API key of its own, which is what the
 * cases at the bottom of this file are about.
 */
class PairedSecretlessTest {

    private fun stored(vararg entries: String) = "[" + entries.joinToString(",") + "]"

    private val legacyHub =
        """{"name":"Yosemite Kids hub","host":"192.168.1.245","port":8765,"token":"t1"}"""
    private val legacyTv =
        """{"name":"Living Room","host":"192.168.1.10","port":8765,"token":"t2"}"""

    @Test
    fun aHubPairedBeforeTheFlagExistedIsStillTreatedAsSecretless() {
        // The upgrade path. Without this the family that already enrolled a hub
        // keeps the bug forever, because nothing would ever re-write the entry.
        val hub = PairingStore.parsePaired(stored(legacyHub)).single()
        assertTrue("a legacy hub entry must migrate to secretless", hub.secretless)
    }

    @Test
    fun aLegacyTvIsNotSweptUpByThatMigration() {
        // The migration keys on the name this phone itself wrote at enrolment.
        // A TV must not acquire the flag: it holds a real API key, and judging
        // it without one would let a revoked key read as in sync.
        val tv = PairingStore.parsePaired(stored(legacyTv)).single()
        assertFalse(tv.secretless)
    }

    @Test
    fun theFlagSurvivesARoundTripAndIsWrittenExplicitly() {
        val hub = PairingStore.parsePaired(stored(legacyHub)).single()
        val json = PairingStore.serializePaired(listOf(hub))

        assertTrue(
            "the flag must be persisted, not re-derived from the name every time",
            JSONArray(json).getJSONObject(0).optBoolean("secretless", false)
        )
        assertEquals(hub, PairingStore.parsePaired(json).single())
    }

    @Test
    fun aRenamedHubKeepsTheFlag() {
        // The migration reads a name, but the flag it produces is stored. A
        // parent renaming "Yosemite Kids hub" to "The NAS" must not silently put the
        // hub back to being judged on the full fingerprint.
        val hub = PairingStore.parsePaired(stored(legacyHub)).single()
        val renamed = hub.copy(name = "The NAS")
        val reloaded = PairingStore.parsePaired(PairingStore.serializePaired(listOf(renamed))).single()

        assertEquals("The NAS", reloaded.name)
        assertTrue("renaming must not clear it", reloaded.secretless)
    }

    @Test
    fun anOrdinaryDeviceIsNotWrittenWithTheFlagAtAll() {
        // Append-only on the wire: an entry that never needed the field keeps
        // the exact shape an older build wrote, so downgrading is not a data
        // loss event.
        val tv = PairedDevice("Living Room", "192.168.1.10", 8765, "t2")
        val o = JSONArray(PairingStore.serializePaired(listOf(tv))).getJSONObject(0)
        assertFalse(o.has("secretless"))
    }

    @Test
    fun anExplicitFalseBeatsTheNameBasedGuess() {
        // Someone who deliberately marks a peer non-secretless — or a future
        // build that stops enrolling hubs this way — must win over the
        // migration's guess, or the guess becomes impossible to escape.
        val explicit =
            """{"name":"Yosemite Kids hub","host":"h","port":1,"token":"t","secretless":false}"""
        assertFalse(PairingStore.parsePaired(stored(explicit)).single().secretless)
    }

    // --- the split: which fingerprint, versus what kind of thing ------------

    @Test
    fun aHubPairedBeforeTheSplitIsStillRecognisedAsAHub() {
        // Both migrations have to fire on the same legacy bytes. Miss this one
        // and the phone stops knowing the NAS is a NAS: rediscovery sweeps the
        // /24 for it, the hub card cannot find it, and POST /leave-hub removes
        // nothing.
        val hub = PairingStore.parsePaired(stored(legacyHub)).single()
        assertTrue("a legacy hub entry must migrate to isHub", hub.isHub)
    }

    @Test
    fun aLegacyTvIsNotSweptUpIntoBeingAHub() {
        assertFalse(PairingStore.parsePaired(stored(legacyTv)).single().isHub)
    }

    @Test
    fun anEntryWrittenWithOnlyTheOldFlagStillMigratesToAHub() {
        // The entry a build between the two shapes wrote: renamed by a parent,
        // so the name cannot carry the migration, and marked only secretless.
        // Back then that word meant both things, so it has to mean both here.
        val renamedHub =
            """{"name":"The NAS","host":"h","port":1,"token":"t","secretless":true}"""
        val hub = PairingStore.parsePaired(stored(renamedHub)).single()
        assertTrue(hub.isHub)
        assertTrue(hub.secretless)
    }

    @Test
    fun aHubThatHoldsAKeyIsStillAHub() {
        // The whole reason for the split. Once the NAS holds an API key it is
        // judged on the full fingerprint like a television — and it is still
        // the hub, so nothing may start sweeping the subnet for it.
        val withKey = PairedDevice(
            PairedDevice.HUB_NAME, "h", 1, "t", secretless = false, isHub = true
        )
        val reloaded =
            PairingStore.parsePaired(PairingStore.serializePaired(listOf(withKey))).single()
        assertTrue(reloaded.isHub)
        assertFalse(reloaded.secretless)
    }

    @Test
    fun anOrdinaryDeviceIsWrittenWithNeitherFlag() {
        val tv = PairedDevice("Living Room", "192.168.1.10", 8765, "t2")
        val o = JSONArray(PairingStore.serializePaired(listOf(tv))).getJSONObject(0)
        assertFalse(o.has("secretless"))
        assertFalse(o.has("isHub"))
    }
}
