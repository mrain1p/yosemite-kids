package io.pickwick.app

import io.pickwick.app.data.LanServer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `POST /sync-now` is the only unauthenticated endpoint on a device that makes
 * it *do* something, so what it refuses is worth stating.
 *
 * It is unauthenticated by necessity: a hub holds no credential on a device and
 * deliberately never gets one, because the hub is the box meant to face the
 * internet one day and must not be able to command the TVs. It may only ask
 * them to check in — which is safe precisely because the nudge carries no data
 * and the device then authenticates its own sync as usual.
 */
class SyncNudgeGateTest {

    private fun verdict(
        hasOrigin: Boolean = false,
        contentType: String = "application/json",
        since: Long = LanServer.SYNC_NUDGE_MIN_GAP_MS * 2
    ) = LanServer.nudgeVerdict(hasOrigin, contentType, since)

    @Test
    fun aHubsNudgeIsHonoured() {
        assertEquals(200, verdict())
        assertEquals(200, verdict(contentType = "application/json; charset=utf-8"))
    }

    @Test
    fun aPageInABrowserOnThisLanIsRefused() {
        // The drive-by: a cross-site POST needs no preflight, so without this a
        // page anyone on the LAN opened could put every TV into a sweep loop.
        // A browser always sends Origin, and cannot send JSON cross-site
        // without a preflight — so either signal alone is enough to refuse.
        assertEquals(403, verdict(hasOrigin = true))
        assertEquals(403, verdict(contentType = "text/plain"))
        assertEquals(403, verdict(contentType = ""))
        assertEquals(403, verdict(contentType = "application/x-www-form-urlencoded"))
    }

    @Test
    fun originIsRefusedEvenWhenEverythingElseLooksRight() {
        assertEquals(403, verdict(hasOrigin = true, contentType = "application/json"))
    }

    @Test
    fun onePerTenSecondsPerCaller() {
        assertEquals(429, verdict(since = 0))
        assertEquals(429, verdict(since = LanServer.SYNC_NUDGE_MIN_GAP_MS - 1))
        assertEquals(200, verdict(since = LanServer.SYNC_NUDGE_MIN_GAP_MS))
    }

    @Test
    fun aCallerNeverSeenBeforeIsNotTreatedAsRepeating() {
        // The route passes a very negative "since" for an unknown caller. Using
        // 0L for "never" would make every first nudge a 429 — the hub's very
        // first announcement, silently dropped.
        assertEquals(200, verdict(since = Long.MAX_VALUE / 2))
    }
}
