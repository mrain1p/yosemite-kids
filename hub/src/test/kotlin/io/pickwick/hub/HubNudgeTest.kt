package io.pickwick.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The hub's only outbound behaviour.
 *
 * The rule worth stating: it announces a change and nothing else. If this ever
 * grows into "the hub pushes config", a device has to trust the hub as an
 * admin — and the hub is the box on the NAS that is meant to face the internet
 * one day. See [HubNudge] for the argument.
 */
class HubNudgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tokensWith(vararg devices: Triple<String, String?, Int>): HubTokens {
        val t = HubTokens(tmp.newFolder())
        devices.forEach { (name, host, port) ->
            val code = t.startEnrolment(name, 0L)
            val token = t.approve(code, 0L).getOrThrow()
            if (host != null) t.noteSeen(token, host, port, 1L)
        }
        return t
    }

    @Test
    fun aDeviceThatHasNeverSaidWhereItListensIsNotNudgedAtAGuess() {
        // An older build sends no X-Device-Port. Guessing 8765 would nudge
        // whatever else happens to answer on that port at that address.
        val tokens = tokensWith(Triple("Old TV", null, 0))
        val sent = ConcurrentLinkedQueue<String>()
        val nudge = HubNudge(tokens) { h, p -> sent.add("$h:$p"); true }

        nudge.changed()
        Thread.sleep(150)
        nudge.stop()

        assertTrue("nothing should have been sent, got $sent", sent.isEmpty())
    }

    @Test
    fun everyDeviceWithAKnownAddressIsToldOnce() {
        val tokens = tokensWith(
            Triple("Living room", "192.168.1.20", 8765),
            Triple("Kitchen", "192.168.1.21", 8766)
        )
        val latch = CountDownLatch(2)
        val sent = ConcurrentLinkedQueue<String>()
        val nudge = HubNudge(tokens) { h, p -> sent.add("$h:$p"); latch.countDown(); true }

        nudge.changed()
        assertTrue("both devices should be nudged", latch.await(3, TimeUnit.SECONDS))
        nudge.stop()

        assertEquals(setOf("192.168.1.20:8765", "192.168.1.21:8766"), sent.toSet())
    }

    @Test
    fun oneAsleepDeviceDoesNotStopTheOthersBeingTold() {
        // The failure this exists for: tidying channels on the hub while one TV
        // is off must still reach the TV that is on.
        val tokens = tokensWith(
            Triple("Asleep", "192.168.1.20", 8765),
            Triple("Awake", "192.168.1.21", 8765)
        )
        val latch = CountDownLatch(2)
        val reached = ConcurrentLinkedQueue<String>()
        val nudge = HubNudge(tokens) { h, _ ->
            latch.countDown()
            if (h.endsWith(".20")) throw java.io.IOException("host is asleep")
            reached.add(h)
            true
        }

        nudge.changed()
        assertTrue(latch.await(3, TimeUnit.SECONDS))
        nudge.stop()

        assertEquals(listOf("192.168.1.21"), reached.toList())
    }

    @Test
    fun aBurstOfEditsDoesNotBecomeABurstOfRounds() {
        // Removing three channels in the GUI is three commits. Coalescing is
        // why that is not three sweeps on every TV in the house.
        val tokens = tokensWith(Triple("TV", "192.168.1.20", 8765))
        val sends = java.util.concurrent.atomic.AtomicInteger()
        val release = CountDownLatch(1)
        val firstSendStarted = CountDownLatch(1)
        val nudge = HubNudge(tokens) { _, _ ->
            firstSendStarted.countDown()
            // Hold the worker inside the first round so the rest of the burst
            // lands while it is busy, which is the case being tested.
            release.await(3, TimeUnit.SECONDS)
            sends.incrementAndGet()
            true
        }

        nudge.changed()
        assertTrue(firstSendStarted.await(3, TimeUnit.SECONDS))
        repeat(5) { nudge.changed() }
        release.countDown()
        Thread.sleep(400)
        nudge.stop()

        // Six changes, and the five that arrived during the first round
        // collapse into one follow-up rather than five.
        assertEquals(2, sends.get())
    }
}
