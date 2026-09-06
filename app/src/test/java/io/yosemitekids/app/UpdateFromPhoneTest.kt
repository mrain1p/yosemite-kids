package io.yosemitekids.app

import io.yosemitekids.app.data.DeviceKind
import io.yosemitekids.app.data.LanClient
import io.yosemitekids.app.data.RemoteUpdate
import io.yosemitekids.app.data.Updater
import io.yosemitekids.app.ui.DeviceSync
import io.yosemitekids.app.ui.canBeAskedToUpdate
import io.yosemitekids.app.ui.installByHandText
import io.yosemitekids.app.ui.updateOutcomeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone half of "Update now": when the button is offered, and the plain
 * words each answer from the device turns into. The parent reads these on a
 * phone about a screen across the room, so every branch is pinned — the two
 * a device never sends included (unreachable, and a build older than the
 * route answering 404).
 */
class UpdateFromPhoneTest {

    private val tv = "Living room TV"

    private fun answer(status: String, name: String? = "1.0.5", code: Int? = 6) =
        LanClient.UpdateAnswer(status, name, code)

    private fun say(answer: LanClient.UpdateAnswer?, kind: String? = DeviceKind.TV) =
        updateOutcomeText(tv, answer, kind, myVersionName = "1.0.5")

    @Test
    fun offeredSaysWhereThePromptIsAndWhoConfirmsIt() {
        assertEquals(
            "The install prompt for 1.0.5 is on Living room TV. Confirm it there with the remote.",
            say(answer(RemoteUpdate.OFFERED))
        )
    }

    @Test
    fun aTabletHasNoRemote() {
        assertEquals(
            "The install prompt for 1.0.5 is on Living room TV. Confirm it there on its screen.",
            say(answer(RemoteUpdate.OFFERED), kind = DeviceKind.TABLET)
        )
        // Unknown kind (a build that predates the key): don't guess at a remote.
        assertTrue(say(answer(RemoteUpdate.OFFERED), kind = null).endsWith("on its screen."))
    }

    @Test
    fun upToDateExplainsWhyTheRowStillSaysBehind() {
        // The device judged itself against version.json; this phone judged it
        // against itself. When they disagree the release is what is behind.
        val text = say(answer(RemoteUpdate.UP_TO_DATE, name = "1.0.4", code = 5))
        assertTrue(text, text.contains("nothing newer than 1.0.4 published yet"))
        assertTrue(text, text.contains("this phone's 1.0.5 may be ahead of the release"))
    }

    @Test
    fun offIsTheByHandWording() {
        assertEquals(installByHandText(listOf(tv), "1.0.5"), say(answer(RemoteUpdate.OFF)))
        assertEquals(
            "Living room TV is on a build with no update check: install 1.0.5 on it by hand " +
                "once, and every later version arrives on its own.",
            say(answer(RemoteUpdate.OFF))
        )
    }

    @Test
    fun aBuildOlderThanTheRouteIsSentToItsOwnScreenFirst() {
        // A 404 is not "off": from 1.0.3 the device's own settings screen
        // offers the install, and that is a shorter walk than a sideload.
        val text = say(answer(LanClient.NO_ROUTE, name = null, code = null))
        assertTrue(text, text.contains("can't start an update from this phone"))
        assertTrue(text, text.contains("Check for updates offers the install"))
        assertTrue(text, text.contains("install 1.0.5 on it by hand once"))
    }

    @Test
    fun busyNotOnScreenAndFailedSaySoAndNameTheDevice() {
        val busy = say(answer(RemoteUpdate.BUSY))
        assertTrue(busy, busy.startsWith("Living room TV is already fetching an update"))
        val hidden = say(answer(RemoteUpdate.NOT_ON_SCREEN))
        assertTrue(hidden, hidden.startsWith("Open Yosemite Kids on Living room TV first"))
        val failed = say(answer(RemoteUpdate.FAILED))
        assertTrue(failed, failed.startsWith("Living room TV couldn't fetch the update"))
    }

    @Test
    fun unreachableIsNotAnUpdateProblem() {
        assertEquals("Living room TV didn't answer. Is it awake and on this Wi-Fi?", say(null))
    }

    @Test
    fun aStatusFromANewerBuildIsShownRatherThanCrashedOn() {
        val text = say(answer("rebooting"))
        assertTrue(text, text.contains("“rebooting”"))
        assertTrue(text, text.contains("newer build"))
    }

    @Test
    fun updateNowIsOfferedOnlyToABuildThatCanBeAsked() {
        fun device(code: Int?) = DeviceSync.Reachable("h", 0L, versionName = "x", versionCode = code)
        val first = Updater.FIRST_SELF_UPDATING_VERSION_CODE
        assertTrue(device(first).canBeAskedToUpdate(myVersionCode = first + 2))
        assertTrue(device(first + 1).canBeAskedToUpdate(myVersionCode = first + 2))
        // Older than the first self-updating build: no manifest URL, so the
        // ask can only come back "off". The banner names it instead.
        assertFalse(device(first - 1).canBeAskedToUpdate(myVersionCode = first + 2))
        // Not behind, or never said: no button.
        assertFalse(device(first + 2).canBeAskedToUpdate(myVersionCode = first + 2))
        assertFalse(device(null).canBeAskedToUpdate(myVersionCode = first + 2))
    }

    @Test
    fun theByHandLineReadsForOneOrManyAndIsAbsentForNone() {
        assertNull(installByHandText(emptyList(), "1.0.5"))
        assertEquals(
            "Kitchen TV, Tablet are on builds with no update check: install 1.0.5 on them " +
                "by hand once, and every later version arrives on its own.",
            installByHandText(listOf("Kitchen TV", "Tablet"), "1.0.5")
        )
    }
}
