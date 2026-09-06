package io.yosemitekids.hub

import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.MasterElection.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The election as the hub runs it: a real store on disk, real token
 * bookkeeping, a clock and a probe the test holds. MasterElectionTest owns
 * the rules; this owns what a tick does with them.
 */
class HubMasterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val T = 1_780_000_000_000L
    private val H = 60 * 60 * 1000L
    private val PHONE = "0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a"
    private var clock = T
    private var youtubeAnswers = true
    private lateinit var store: HubStore
    private lateinit var tokens: HubTokens

    @Before
    fun setUp() {
        val dir = tmp.newFolder("hub")
        store = HubStore(dir)
        tokens = HubTokens(dir)
    }

    private fun master() = HubMaster(store, tokens, probe = { youtubeAnswers }) { clock }

    /** A device joins and pulls the index: what arms the hub. */
    private fun armed() {
        val token = tokens.approve(tokens.startEnrolment("TV", clock), clock).getOrThrow()
        tokens.notePull(token, clock)
    }

    private fun holder() = runCatching { store.load().masterDeviceToken }.getOrNull()
    private fun stamp() = runCatching { store.load().sync.at[ConfigStamp.MASTER] }.getOrNull()

    @Test
    fun `an armed hub claims a vacant slot and stamps it now`() {
        armed()
        assertEquals(Decision.CLAIM, master().tick())
        assertEquals(tokens.selfToken(), holder())
        assertEquals(T, stamp())
    }

    @Test
    fun `a hub nobody pulls from claims nothing`() {
        val m = master()
        assertEquals(Decision.NOTHING, m.tick())
        assertNull(holder())
        assertTrue(m.last, m.last.contains("no device has pulled"))
    }

    @Test
    fun `no claim while YouTube does not answer from here`() {
        armed()
        youtubeAnswers = false
        val m = master()
        assertEquals(Decision.NOTHING, m.tick())
        assertNull("the phone must keep crawling", holder())
        assertTrue(m.last, m.last.contains("unreachable"))
    }

    @Test
    fun `an armed hub takes the slot from a phone, then heartbeats every six hours`() {
        armed()
        store.edit("phone", T) { it.copy(masterDeviceToken = PHONE) }
        assertEquals(T, stamp())

        clock = T + 1 * H
        val m = master()
        assertEquals(Decision.CLAIM, m.tick())
        assertEquals(tokens.selfToken(), holder())
        assertEquals(T + 1 * H, stamp())

        clock = T + 2 * H
        assertEquals(Decision.NOTHING, m.tick())
        assertEquals("too soon to heartbeat", T + 1 * H, stamp())

        clock = T + 7 * H
        assertEquals(Decision.HEARTBEAT, m.tick())
        assertEquals(T + 7 * H, stamp())
        assertEquals(tokens.selfToken(), holder())
    }

    @Test
    fun `a master nobody pulls from any more lets its slot lapse`() {
        armed()
        assertEquals(Decision.CLAIM, master().tick())
        // A day and more with no pull: unarmed, so no heartbeat, so the stamp
        // ages and a phone's election reads the slot as vacant.
        clock = T + 25 * H
        val m = master()
        assertEquals(Decision.NOTHING, m.tick())
        assertEquals(T, stamp())
        assertTrue(m.last, m.last.contains("letting a phone take over"))
    }
}
