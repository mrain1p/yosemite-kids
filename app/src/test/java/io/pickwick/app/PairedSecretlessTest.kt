package io.pickwick.app

import io.pickwick.app.data.PairedDevice
import io.pickwick.app.data.PairingStore
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form of the paired list, and the one-way door in it.
 *
 * `secretless` decides which fingerprint a peer is judged against, so an entry
 * that loses the flag reads as permanently out of sync and an entry that
 * gains it wrongly can hide a real difference. Neither is visible until a
 * parent is staring at a device that will not settle.
 */
class PairedSecretlessTest {

    private fun stored(vararg entries: String) = "[" + entries.joinToString(",") + "]"

    private val legacyHub =
        """{"name":"Pickwick hub","host":"192.168.1.245","port":8765,"token":"t1"}"""
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
        // parent renaming "Pickwick hub" to "The NAS" must not silently put the
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
            """{"name":"Pickwick hub","host":"h","port":1,"token":"t","secretless":false}"""
        assertFalse(PairingStore.parsePaired(stored(explicit)).single().secretless)
    }
}
