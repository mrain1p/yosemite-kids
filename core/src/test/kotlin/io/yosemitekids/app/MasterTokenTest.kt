package io.yosemitekids.app

import io.yosemitekids.app.data.MasterToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterTokenTest {

    private val hub = ".hub0123456789abcdef0123456789ab"
    private val hub2 = ".hubffffffffffffffffffffffffffff"
    private val phoneA = "0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a"
    private val phoneB = "ffffffffffffffffffffffffffffffff"

    @Test
    fun `a hub token is the prefix, nothing else`() {
        assertTrue(MasterToken.isHub(hub))
        assertFalse(MasterToken.isHub(phoneA))
        assertFalse(MasterToken.isHub(null))
        assertFalse(MasterToken.isHub("hub0123456789abcdef0123456789abcd"))
    }

    @Test
    fun `the hub wins a tie against a phone in either order`() {
        assertEquals(hub, MasterToken.preferred(hub, phoneA))
        assertEquals(hub, MasterToken.preferred(phoneA, hub))
        // Even a phone that sorts first.
        assertEquals(hub, MasterToken.preferred(phoneA, hub))
        assertTrue(phoneA < hub || hub < phoneA) // a total order exists; the hub still wins
    }

    @Test
    fun `two phones keep the old rule, and so do two hubs`() {
        assertEquals(phoneA, MasterToken.preferred(phoneA, phoneB))
        assertEquals(phoneA, MasterToken.preferred(phoneB, phoneA))
        assertEquals(hub, MasterToken.preferred(hub, hub2))
        assertEquals(hub, MasterToken.preferred(hub2, hub))
    }

    @Test
    fun `an absent side yields to the other`() {
        assertEquals(phoneA, MasterToken.preferred(null, phoneA))
        assertEquals(phoneA, MasterToken.preferred(phoneA, null))
        assertEquals(null, MasterToken.preferred(null, null))
    }
}
