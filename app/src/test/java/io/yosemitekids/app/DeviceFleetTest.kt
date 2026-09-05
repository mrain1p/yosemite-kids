package io.yosemitekids.app

import io.yosemitekids.app.data.PairedDevice
import io.yosemitekids.app.ui.DeviceSync
import io.yosemitekids.app.ui.behind
import io.yosemitekids.app.ui.deviceStatusLine
import io.yosemitekids.app.ui.otherParents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line under each device on Devices & sync, and the two derivations the
 * page makes from a sweep: who is behind, and who the other parents are.
 */
class DeviceFleetTest {

    private val now = 1_000_000_000_000L
    private fun answer(name: String? = "0.12.2-fork", code: Int? = 49) =
        DeviceSync.Reachable("h", 0L, versionName = name, versionCode = code)

    @Test
    fun inSyncCarriesTheVersionAndNothingElse() {
        val (line, amber) = deviceStatusLine(answer(), answer(), now, inSync = true, myVersionCode = 49, now = now)
        assertEquals("In sync · 0.12.2-fork (49)", line)
        assertFalse(amber)
    }

    @Test
    fun outOfSyncIsAmber() {
        val (line, amber) = deviceStatusLine(answer(), answer(), now, inSync = false, myVersionCode = 49, now = now)
        assertEquals("Out of sync · 0.12.2-fork (49)", line)
        assertTrue(amber)
    }

    @Test
    fun offlineKeepsTheLastAnswerAndSaysWhenItWasSeen() {
        // The version outlives the Offline: a switched-off TV is still the
        // build it was twenty minutes ago.
        val (line, amber) = deviceStatusLine(
            DeviceSync.Offline, answer(), now - 20 * 60_000L, inSync = false, myVersionCode = 49, now = now
        )
        assertEquals("Offline · seen 20m ago · 0.12.2-fork (49)", line)
        assertTrue(amber)
    }

    @Test
    fun offlineWithNoAnswerYetIsJustOffline() {
        val (line, _) = deviceStatusLine(DeviceSync.Offline, null, null, inSync = false, myVersionCode = 49, now = now)
        assertEquals("Offline", line)
    }

    @Test
    fun checkingIsNeverAmber() {
        val (line, amber) = deviceStatusLine(null, null, null, inSync = false, myVersionCode = 49, now = now)
        assertEquals("Checking…", line)
        assertFalse(amber)
    }

    @Test
    fun behindMeansAnOlderVersionCodeAndNamesTheUpdate() {
        val old = answer("0.12.1-fork", 47)
        assertTrue(old.behind(myVersionCode = 49))
        val (line, _) = deviceStatusLine(old, old, now, inSync = true, myVersionCode = 49, now = now)
        assertEquals("In sync · 0.12.1-fork (47) · update available", line)
    }

    @Test
    fun anUnknownVersionIsNotBehind() {
        // The hub serves no versionCode. Unknown must not light the amber dot.
        assertFalse(answer(name = null, code = null).behind(myVersionCode = 49))
        val (line, _) = deviceStatusLine(answer(null, null), answer(null, null), now, inSync = true, myVersionCode = 49, now = now)
        assertEquals("In sync", line)
    }

    @Test
    fun otherParentsAreEveryAdminButThisPhoneGroupedAcrossDevices() {
        val tv = PairedDevice("Living Room TV", "10.0.0.2", 8765, "t", id = "tv")
        val tablet = PairedDevice("Tablet", "10.0.0.3", 8765, "t", id = "tab")
        val admins = mapOf(
            "tv" to listOf("Mike's Phone" to "me", "Sarah's Phone" to "sarah"),
            "tab" to listOf("Sarah's Phone" to "sarah", "Mike's Phone" to "me")
        )
        val parents = otherParents(listOf(tv, tablet), admins, myToken = "me")
        assertEquals(1, parents.size)
        assertEquals("Sarah's Phone", parents[0].name)
        assertEquals(listOf(tv, tablet), parents[0].manages)
    }

    @Test
    fun noSweepYetMeansNoParents() {
        val tv = PairedDevice("Living Room TV", "10.0.0.2", 8765, "t", id = "tv")
        assertTrue(otherParents(listOf(tv), emptyMap(), myToken = "me").isEmpty())
    }
}
