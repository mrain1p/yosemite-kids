package io.pickwick.app

import io.pickwick.app.data.PairingStore
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pairing-request expiry rule (see PairingStore.pendingRequests). */
class PendingRequestExpiryTest {

    private val t0 = 1_000_000_000_000L
    private val ttl = PairingStore.PENDING_TTL_MS

    @Test
    fun keepsFreshDropsStale() {
        val pending = mapOf("aaa" to "Fresh phone", "bbb" to "Old phone", "ccc" to "Ancient")
        val at = mapOf(
            "aaa" to (t0 - ttl / 2).toString(),
            "bbb" to (t0 - ttl - 1).toString(),
            "ccc" to (t0 - 10 * ttl).toString()
        )
        assertEquals(mapOf("aaa" to "Fresh phone"), PairingStore.prunePending(pending, at, t0))
    }

    @Test
    fun unstampedLegacyEntriesSurvive() {
        val pending = mapOf("aaa" to "Written by an older build")
        assertEquals(pending, PairingStore.prunePending(pending, emptyMap(), t0))
        // A stamp that isn't a number is treated the same as no stamp.
        assertEquals(pending, PairingStore.prunePending(pending, mapOf("aaa" to "soon"), t0))
    }

    @Test
    fun exactlyAtTheBoundaryIsExpired() {
        val pending = mapOf("aaa" to "Borderline")
        val at = mapOf("aaa" to (t0 - ttl).toString())
        assertEquals(emptyMap<String, String>(), PairingStore.prunePending(pending, at, t0))
    }
}
