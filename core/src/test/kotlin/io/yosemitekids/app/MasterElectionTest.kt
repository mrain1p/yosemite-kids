package io.yosemitekids.app

import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.MasterElection
import io.yosemitekids.app.data.MasterElection.Decision
import io.yosemitekids.app.data.SyncMeta
import io.yosemitekids.app.data.Whitelist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The election as a table. Every row is one peer looking at one document
 * at one moment; the clock is an argument, so a "day later" is a number.
 */
class MasterElectionTest {

    private val T = 1_780_000_000_000L
    private val H = 60 * 60 * 1000L
    private val hub = ".hub0123456789abcdef0123456789ab"
    private val hub2 = ".hubffffffffffffffffffffffffffff"
    private val phone = "0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a"
    private val other = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"

    private fun held(by: String?, at: Long?) = Whitelist(
        sources = emptyList(), blockedVideoIds = emptySet(),
        masterDeviceToken = by,
        sync = if (at == null) SyncMeta.EMPTY
        else SyncMeta(docAt = at, at = mapOf(ConfigStamp.MASTER to at))
    )

    @Test
    fun `an empty slot is claimed by a phone and by an armed hub, not by an unarmed one`() {
        val empty = held(null, null)
        assertEquals(Decision.CLAIM, MasterElection.decide(empty, phone, isHub = false, now = T))
        assertEquals(Decision.CLAIM, MasterElection.decide(empty, hub, isHub = true, now = T, armed = true))
        assertEquals(Decision.NOTHING, MasterElection.decide(empty, hub, isHub = true, now = T, armed = false))
    }

    @Test
    fun `a live phone keeps its slot against another phone but yields to an armed hub`() {
        val live = held(phone, T - 1 * H)
        assertEquals(Decision.NOTHING, MasterElection.decide(live, other, isHub = false, now = T))
        assertEquals(Decision.CLAIM, MasterElection.decide(live, hub, isHub = true, now = T))
        assertEquals(Decision.NOTHING, MasterElection.decide(live, hub, isHub = true, now = T, armed = false))
    }

    @Test
    fun `a live hub is left alone by phones and by other hubs`() {
        val live = held(hub, T - 1 * H)
        assertEquals(Decision.NOTHING, MasterElection.decide(live, phone, isHub = false, now = T))
        assertEquals(Decision.NOTHING, MasterElection.decide(live, hub2, isHub = true, now = T))
    }

    @Test
    fun `a stamp a day old is vacant for everyone`() {
        val stale = held(hub, T - 25 * H)
        assertTrue(MasterElection.vacant(stale, T))
        assertEquals(Decision.CLAIM, MasterElection.decide(stale, phone, isHub = false, now = T))
        assertEquals(Decision.CLAIM, MasterElection.decide(stale, hub2, isHub = true, now = T))
        // 24 h exactly is still held: four missed heartbeats, not "almost four".
        assertFalse(MasterElection.vacant(held(hub, T - 24 * H), T))
    }

    @Test
    fun `the holder heartbeats every six hours and otherwise does nothing`() {
        assertEquals(Decision.NOTHING, MasterElection.decide(held(phone, T - 5 * H), phone, isHub = false, now = T))
        assertEquals(Decision.HEARTBEAT, MasterElection.decide(held(phone, T - 6 * H), phone, isHub = false, now = T))
        // A legacy claim with no stamp at all heartbeats first, which is what
        // stops the rest of the fleet from reading it as vacant.
        assertEquals(Decision.HEARTBEAT, MasterElection.decide(held(phone, null), phone, isHub = false, now = T))
        // Even a stale holder heartbeats rather than re-claims: the token is
        // already its own, only the stamp needs saying again.
        assertEquals(Decision.HEARTBEAT, MasterElection.decide(held(hub, T - 30 * H), hub, isHub = true, now = T))
    }

    @Test
    fun `a hub nobody pulls from lets its own slot age out`() {
        val mine = held(hub, T - 6 * H)
        assertEquals(Decision.HEARTBEAT, MasterElection.decide(mine, hub, isHub = true, now = T, armed = true))
        assertEquals(Decision.NOTHING, MasterElection.decide(mine, hub, isHub = true, now = T, armed = false))
    }
}
