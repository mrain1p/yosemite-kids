package io.yosemitekids.app

import io.yosemitekids.app.data.RemoteUpdate
import io.yosemitekids.app.data.Updater
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device half of `POST /check-updates`: what a phone's "Update now" makes
 * a TV do, and what it says back. Every branch runs here over lambdas —
 * `LanServer.handle` needs a Context, so this is the only place the route's
 * decision can be stated.
 */
class RemoteUpdateTest {

    private val newer = Updater.UpdateInfo(6, "1.0.5", "https://example.invalid/yosemite-kids.apk")

    private suspend fun run(
        check: Updater.Check,
        onScreen: () -> Boolean = { true },
        install: suspend (Updater.UpdateInfo) -> Unit = {}
    ) = RemoteUpdate.run({ check }, onScreen, install, installedName = "1.0.4", installedCode = 5)

    @Test
    fun aBuildWithNoManifestUrlSaysOffAndNamesItself() = runBlocking {
        val out = run(Updater.Check.Off)
        assertEquals(RemoteUpdate.OFF, out.status)
        assertEquals("1.0.4", out.versionName)
        assertEquals(5, out.versionCode)
    }

    @Test
    fun upToDateAndFailedNameTheRunningBuild() = runBlocking {
        assertEquals(RemoteUpdate.UP_TO_DATE, run(Updater.Check.UpToDate).status)
        assertEquals("1.0.4", run(Updater.Check.UpToDate).versionName)
        assertEquals(RemoteUpdate.FAILED, run(Updater.Check.Failed("HTTP 503")).status)
        assertEquals(5, run(Updater.Check.Failed("HTTP 503")).versionCode)
    }

    @Test
    fun anOfferedUpdateIsDownloadedAndTheAnswerNamesTheNewBuild() = runBlocking {
        var installed: Updater.UpdateInfo? = null
        val out = run(Updater.Check.Offered(newer), install = { installed = it })
        assertEquals(newer, installed)
        assertEquals(RemoteUpdate.OFFERED, out.status)
        // The build the prompt is for, not the one running: that is what the
        // phone prints in "the install prompt for X is on the TV".
        assertEquals("1.0.5", out.versionName)
        assertEquals(6, out.versionCode)
    }

    @Test
    fun nothingIsDownloadedWhenThePromptCouldNotBeShown() = runBlocking {
        // Android 10+ drops the installer start silently when the app has no
        // window; fetching tens of megabytes first would just waste them.
        var installs = 0
        val out = run(Updater.Check.Offered(newer), onScreen = { false }, install = { installs++ })
        assertEquals(RemoteUpdate.NOT_ON_SCREEN, out.status)
        assertEquals(0, installs)
        assertEquals("1.0.4", out.versionName)
    }

    @Test
    fun anAppBackgroundedDuringTheDownloadIsNotReportedAsOffered() = runBlocking {
        // On screen when the check ran, gone by the time the APK arrived: the
        // prompt was dropped, and "offered" would send a parent to look for it.
        val answers = ArrayDeque(listOf(true, false))
        val out = run(Updater.Check.Offered(newer), onScreen = { answers.removeFirst() })
        assertEquals(RemoteUpdate.NOT_ON_SCREEN, out.status)
    }

    @Test
    fun aFailedDownloadIsFailed() = runBlocking {
        val out = run(Updater.Check.Offered(newer), install = { error("Download failed: HTTP 404") })
        assertEquals(RemoteUpdate.FAILED, out.status)
        assertEquals("1.0.4", out.versionName)
    }

    @Test
    fun oneInFlightPerDevice() = runBlocking {
        val holding = CompletableDeferred<Unit>()
        val first = async {
            RemoteUpdate.run({ holding.await(); Updater.Check.UpToDate }, { true }, {}, "1.0.4", 5)
        }
        // Let the first ask reach its check and park there, holding the slot.
        yield()
        assertFalse(first.isCompleted)
        assertEquals(RemoteUpdate.BUSY, run(Updater.Check.UpToDate).status)
        holding.complete(Unit)
        assertEquals(RemoteUpdate.UP_TO_DATE, first.await().status)
        // The slot is released when the first finishes, however it finished.
        assertEquals(RemoteUpdate.UP_TO_DATE, run(Updater.Check.UpToDate).status)
    }

    @Test
    fun theSlotIsReleasedAfterAFailure() = runBlocking {
        run(Updater.Check.Offered(newer), install = { error("boom") })
        assertEquals(RemoteUpdate.OFFERED, run(Updater.Check.Offered(newer)).status)
    }

    @Test
    fun theWireShapeIsStatusAndTheTwoVersionFields() {
        val json = JSONObject(RemoteUpdate.Outcome(RemoteUpdate.OFFERED, "1.0.5", 6).toJson())
        assertEquals("offered", json.getString("status"))
        assertEquals("1.0.5", json.getString("versionName"))
        assertEquals(6, json.getInt("versionCode"))
        assertEquals(3, json.length())
        // The statuses are the wire contract with LanClient.checkUpdates; a
        // rename on one side must fail here rather than on a parent's phone.
        assertEquals("up-to-date", RemoteUpdate.UP_TO_DATE)
        assertEquals("off", RemoteUpdate.OFF)
        assertEquals("failed", RemoteUpdate.FAILED)
        assertEquals("busy", RemoteUpdate.BUSY)
        assertEquals("not-on-screen", RemoteUpdate.NOT_ON_SCREEN)
        assertTrue(RemoteUpdate.OFFERED != RemoteUpdate.UP_TO_DATE)
        assertNull(json.opt("apkUrl"))
    }
}
