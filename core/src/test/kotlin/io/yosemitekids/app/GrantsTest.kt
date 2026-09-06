package io.yosemitekids.app

import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.ConfigMerge
import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.Grant
import io.yosemitekids.app.data.Grants
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SyncMeta
import io.yosemitekids.app.data.Whitelist
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Bonus minutes carried in the config (roadmap 2F).
 *
 * A grant used to be a LAN call and nothing else, so a television asleep at
 * the tap never heard of it. Now each tap is a stamped unit of the config,
 * and these pin the three things that make that safe: the pure accounting
 * (which grants count for whom, on which day, once each), the wire shape,
 * and the merge — two phones granting on one day both land, a day that has
 * passed is tombstoned rather than merely dropped, and the merge is a fixed
 * point of itself, which is what a Push button and the fifteen-minute
 * worker actually exercise.
 */
class GrantsTest {

    private val T = 1_780_000_000_000L
    private val leo = Profile(id = "k1", name = "Leo")

    private fun grant(id: String, kid: String? = "k1", date: String = "2026-09-05", minutes: Int = 15) =
        Grant(id = id, kidId = kid, date = date, minutes = minutes, at = T)

    // --- the pure accounting ----------------------------------------------

    @Test
    fun aGrantCountsForItsKidOnItsDayAndForEveryoneWhenUnnamed() {
        val grants = listOf(
            grant("aaaa0001"),
            grant("aaaa0002", kid = null, minutes = 10),
            grant("aaaa0003", kid = "k2"),
            grant("aaaa0004", date = "2026-09-04")
        )
        assertEquals(25, Grants.minutesFor(grants, "k1", "2026-09-05"))
        assertEquals(25, Grants.minutesFor(grants, "k2", "2026-09-05"))
        assertEquals(10, Grants.minutesFor(grants, "nobody", "2026-09-05"))
        assertEquals(0, Grants.minutesFor(grants, "k1", "2026-09-06"))
    }

    @Test
    fun aTapIsCountedOnceHoweverManyWaysItArrives() {
        val known = listOf(grant("aaaa0001"))
        val again = listOf(grant("aaaa0001"), grant("aaaa0002"))
        assertEquals(listOf("aaaa0002"), Grants.unseen(known, again).map { it.id })
    }

    @Test
    fun expiryIsByCalendarDayText() {
        assertTrue(Grants.expired("2026-09-04", "2026-09-05"))
        assertFalse(Grants.expired("2026-09-05", "2026-09-05"))
        assertFalse(Grants.expired("2026-09-06", "2026-09-05"))
        assertEquals("2026-09-05", Grants.dateOf(1_788_609_600_000L, ZoneId.of("UTC")))
    }

    // --- the wire ---------------------------------------------------------

    @Test
    fun grantsSurviveTheJsonRoundTripAndMoveTheFingerprint() {
        val without = Whitelist(sources = emptyList(), blockedVideoIds = emptySet(), profiles = listOf(leo))
        val with = without.copy(grants = listOf(grant("aaaa0001"), grant("aaaa0002", kid = null, minutes = 10)))
        val parsed = ConfigJson.fromJson(ConfigJson.toJson(with))
        assertEquals(with.grants, parsed.grants)
        assertNotEquals(
            "a grant must move the hash, or the reconcile never re-pushes it",
            ConfigJson.fingerprint(without), ConfigJson.fingerprint(with)
        )
        assertFalse("a family that never adds time keeps its bytes", ConfigJson.toJson(without).contains("grants"))
    }

    @Test
    fun aMalformedGrantDropsAloneNotTheConfig() {
        val text = """[{"id":"aaaa0001","date":"2026-09-05","minutes":15,"at":1},
                      {"id":"not-hex!","date":"2026-09-05","minutes":15},
                      {"id":"aaaa0003","date":"05/09/2026","minutes":15},
                      {"id":"aaaa0004","date":"2026-09-05","minutes":0}]"""
        assertEquals(listOf("aaaa0001"), ConfigJson.grantsFromJson(text).map { it.id })
        assertTrue(ConfigJson.grantsFromJson("garbage").isEmpty())
    }

    // --- the stamper ------------------------------------------------------

    @Test
    fun aTapMintsItsOwnUnitAndALogLineAndExpiryTombstonesIt() {
        val base = Whitelist(sources = emptyList(), blockedVideoIds = emptySet(), profiles = listOf(leo))
        val tapped = base.copy(grants = listOf(grant("aaaa0001")))
        val stamped = ConfigStamp.stamped(base, base, tapped, T + 1, "Mum's phone", "99887766").config
        assertEquals(T + 1, stamped.sync.at[ConfigStamp.grant("aaaa0001")])
        assertTrue(stamped.sync.log.any { it.text.contains("15 extra minutes") })

        // The next day's first save, on a phone: the grant is gone and
        // tombstoned, never merely dropped — absence never deletes, and a peer
        // still listing it would put it straight back.
        val next = ConfigStamp.stamped(stamped, stamped, stamped, T + 2, "Mum's phone", "99887766", today = "2026-09-06").config
        assertTrue(next.grants.isEmpty())
        assertEquals(T + 2, next.sync.gone[ConfigStamp.grant("aaaa0001")])

        // The hub passes no day and judges nothing.
        val hub = ConfigStamp.stamped(stamped, stamped, stamped, T + 2, "hub", "hub", today = null).config
        assertEquals(1, hub.grants.size)
    }

    // --- the merge --------------------------------------------------------

    private fun doc(w: Whitelist): String = JSONObject(ConfigJson.toJson(w)).put("updatedAt", T).toString(2)

    private fun stampedDoc(base: Whitelist, next: Whitelist, at: Long, by: String): String =
        doc(ConfigStamp.stamped(base, base, next, at, by, by).config)

    @Test
    fun twoPhonesGrantingOnTheSameDayBothLandAndTheKidGetsTheSum() {
        val base = Whitelist(sources = emptyList(), blockedVideoIds = emptySet(), profiles = listOf(leo))
        val mum = stampedDoc(base, base.copy(grants = listOf(grant("aaaa0001", minutes = 15))), T + 1, "mum")
        val dad = stampedDoc(base, base.copy(grants = listOf(grant("bbbb0001", minutes = 10))), T + 2, "dad")

        val merged = ConfigJson.fromJson(ConfigMerge.merge(mum, dad).merged!!)
        assertEquals(setOf("aaaa0001", "bbbb0001"), merged.grants.map { it.id }.toSet())
        assertEquals(25, Grants.minutesFor(merged.grants, "k1", "2026-09-05"))

        // And in the other direction the same document, bytes aside.
        val other = ConfigJson.fromJson(ConfigMerge.merge(dad, mum).merged!!)
        assertEquals(ConfigJson.fingerprint(merged), ConfigJson.fingerprint(other))
    }

    @Test
    fun anExpiredGrantStaysGoneAgainstAStaleCopyAndTheMergeSettles() {
        val base = Whitelist(sources = emptyList(), blockedVideoIds = emptySet(), profiles = listOf(leo))
        val tapped = ConfigStamp.stamped(base, base, base.copy(grants = listOf(grant("aaaa0001"))), T + 1, "mum", "mum").config
        val stale = doc(tapped)
        // The phone's next-day save tombstones it.
        val expired = doc(ConfigStamp.stamped(tapped, tapped, tapped, T + 2, "mum", "mum", today = "2026-09-06").config)

        val out = ConfigMerge.merge(expired, stale)
        assertTrue("a stale copy that never saw the day end must not bring the minutes back",
            ConfigJson.fromJson(out.merged ?: expired).grants.isEmpty())
        val settled = out.merged ?: expired
        assertNull("merging the same stale peer again changes nothing", ConfigMerge.merge(settled, stale).merged)
        assertEquals(T + 2, ConfigJson.fromJson(settled).sync.gone[ConfigStamp.grant("aaaa0001")])
    }

    @Test
    fun theStaleCopyItselfConvergesWhenItMergesTheTombstoneBack() {
        val base = Whitelist(sources = emptyList(), blockedVideoIds = emptySet(), profiles = listOf(leo))
        val tapped = ConfigStamp.stamped(base, base, base.copy(grants = listOf(grant("aaaa0001"))), T + 1, "mum", "mum").config
        val stale = doc(tapped)
        val expired = doc(ConfigStamp.stamped(tapped, tapped, tapped, T + 2, "mum", "mum", today = "2026-09-06").config)

        val onStale = ConfigMerge.merge(stale, expired).merged!!
        assertTrue(ConfigJson.fromJson(onStale).grants.isEmpty())
        assertNull(ConfigMerge.merge(onStale, expired).merged)
        assertEquals(
            ConfigJson.fingerprint(ConfigJson.fromJson(onStale)),
            ConfigJson.fingerprint(ConfigJson.fromJson(ConfigMerge.merge(expired, stale).merged ?: expired))
        )
    }
}
