package io.yosemitekids.app

import io.yosemitekids.app.data.BUDGET_SCOPE_SHARED
import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.TimeWindow
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import io.yosemitekids.app.data.WhitelistExporter
import io.yosemitekids.app.data.WhitelistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screen-time multiplier: serialization, fingerprint, and export behavior. */
class ConfigStoreJsonTest {

    private fun entry(id: String, percent: Int = 100) = WhitelistEntry(
        id, "https://www.youtube.com/channel/$id", "Channel $id",
        SourceKind.CHANNEL, timeMultiplierPercent = percent
    )

    @Test
    fun `multiplier survives a JSON round-trip`() {
        val config = Whitelist(
            sources = listOf(entry("UCa", 50), entry("UCb"), entry("UCc", 0)),
            blockedVideoIds = emptySet()
        )
        val parsed = ConfigJson.fromJson(ConfigJson.toJson(config))
        assertEquals(50, parsed.sources[0].timeMultiplierPercent)
        assertEquals(100, parsed.sources[1].timeMultiplierPercent)
        assertEquals(0, parsed.sources[2].timeMultiplierPercent)
    }

    @Test
    fun `default multiplier is omitted from JSON so old builds parse unchanged`() {
        val json = ConfigJson.toJson(Whitelist(listOf(entry("UCa")), emptySet()))
        assertFalse(json.contains("\"time\""))
    }

    @Test
    fun `configs without multipliers keep their pre-multiplier fingerprint shape`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        val halved = Whitelist(listOf(entry("UCa", 50)), emptySet())
        // Same entries at default rate hash identically…
        assertEquals(
            ConfigJson.fingerprint(plain),
            ConfigJson.fingerprint(Whitelist(listOf(entry("UCa", 100)), emptySet()))
        )
        // …but a changed rate must change the fingerprint (it changes behavior).
        assertNotEquals(ConfigJson.fingerprint(plain), ConfigJson.fingerprint(halved))
    }

    @Test
    fun `parent pause survives a JSON round-trip and clears back to null`() {
        val until = 1_785_800_000_000L
        val paused = Whitelist(
            listOf(entry("UCa")), emptySet(),
            limits = io.yosemitekids.app.data.Limits(sessionMinutes = 30, pausedUntilMillis = until)
        )
        val parsed = ConfigJson.fromJson(ConfigJson.toJson(paused))
        assertEquals(until, parsed.limits.pausedUntilMillis)
        assertEquals(30, parsed.limits.sessionMinutes)

        // Resume writes null — the field must vanish from JSON, not linger as 0.
        val resumed = ConfigJson.fromJson(
            ConfigJson.toJson(paused.copy(limits = paused.limits.copy(pausedUntilMillis = null)))
        )
        assertEquals(null, resumed.limits.pausedUntilMillis)
    }

    @Test
    fun `minimum video length round-trips, is omitted when unset, and moves the fingerprint`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        assertFalse(ConfigJson.toJson(plain).contains("minVideoMinutes"))
        assertEquals(null, ConfigJson.fromJson(ConfigJson.toJson(plain)).limits.minVideoMinutes)

        val ruled = plain.copy(limits = io.yosemitekids.app.data.Limits(minVideoMinutes = 5))
        assertEquals(5, ConfigJson.fromJson(ConfigJson.toJson(ruled)).limits.minVideoMinutes)
        assertNotEquals(ConfigJson.fingerprint(plain), ConfigJson.fingerprint(ruled))
    }

    @Test
    fun `pause changes the fingerprint so offline reconcile delivers it`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        val paused = plain.copy(
            limits = plain.limits.copy(pausedUntilMillis = 1_785_800_000_000L)
        )
        // Unpaused configs keep their pre-pause hash shape…
        assertEquals(
            ConfigJson.fingerprint(plain),
            ConfigJson.fingerprint(plain.copy(limits = plain.limits.copy(pausedUntilMillis = null)))
        )
        // …but pausing must change it (syncConfigState re-pushes on mismatch).
        assertNotEquals(ConfigJson.fingerprint(plain), ConfigJson.fingerprint(paused))
    }

    @Test
    fun `sponsor skip is on by default and only serialized when off`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        // Absent from JSON when on, so pre-flag builds parse unchanged…
        assertFalse(ConfigJson.toJson(plain).contains("sponsorSkip"))
        assertTrue(ConfigJson.fromJson(ConfigJson.toJson(plain)).sponsorSkip)
        // …and an off switch survives the round trip.
        val off = ConfigJson.fromJson(ConfigJson.toJson(plain.copy(sponsorSkip = false)))
        assertFalse(off.sponsorSkip)
    }

    @Test
    fun `turning sponsor skip off changes the fingerprint, leaving it on does not`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        // Untouched configs keep their pre-flag hash shape…
        assertEquals(
            ConfigJson.fingerprint(plain),
            ConfigJson.fingerprint(plain.copy(sponsorSkip = true))
        )
        // …but the off switch must reach devices via the offline reconcile.
        assertNotEquals(
            ConfigJson.fingerprint(plain),
            ConfigJson.fingerprint(plain.copy(sponsorSkip = false))
        )
    }

    @Test
    fun `listening rate is off by default and only serialized when set`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        // Absent from JSON when off, so pre-listen builds parse unchanged…
        assertFalse(ConfigJson.toJson(plain).contains("\"listen\""))
        assertEquals(null, ConfigJson.fromJson(ConfigJson.toJson(plain)).listenPercent)
        // …a set rate survives the round trip, FREE (0) included…
        assertEquals(
            50,
            ConfigJson.fromJson(ConfigJson.toJson(plain.copy(listenPercent = 50))).listenPercent
        )
        assertEquals(
            0,
            ConfigJson.fromJson(ConfigJson.toJson(plain.copy(listenPercent = 0))).listenPercent
        )
        // …and switching back to Off vanishes from JSON rather than lingering.
        val cleared = plain.copy(listenPercent = 50).copy(listenPercent = null)
        assertEquals(null, ConfigJson.fromJson(ConfigJson.toJson(cleared)).listenPercent)
    }

    @Test
    fun `setting a listening rate changes the fingerprint, leaving it off does not`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        // Untouched configs keep their pre-listen hash shape…
        assertEquals(
            ConfigJson.fingerprint(plain),
            ConfigJson.fingerprint(plain.copy(listenPercent = null))
        )
        // …but a rate change must reach devices via the offline reconcile.
        assertNotEquals(
            ConfigJson.fingerprint(plain),
            ConfigJson.fingerprint(plain.copy(listenPercent = 50))
        )
        // FREE is a real setting, not "unset" — it must hash differently too.
        assertNotEquals(
            ConfigJson.fingerprint(plain),
            ConfigJson.fingerprint(plain.copy(listenPercent = 0))
        )
    }

    @Test
    fun `exported listening comment does not corrupt re-import`() {
        val exported = WhitelistExporter.toText(
            Whitelist(listOf(entry("UC4a-Gbdw7vOaccHmFo40b9g")), emptySet(), listenPercent = 25)
        )
        assertTrue(exported.contains("Listening"))
        val reparsed = WhitelistParser.parse(exported)
        assertEquals(1, reparsed.sources.size)
        // Files carry links only — the listening rate is UI/sync-managed.
        assertEquals(null, reparsed.listenPercent)
    }

    @Test
    fun `exported multiplier comment does not corrupt re-import`() {
        val exported = WhitelistExporter.toText(
            Whitelist(listOf(entry("UC4a-Gbdw7vOaccHmFo40b9g", 25)), emptySet())
        )
        assertTrue(exported.contains("# screen time 25%"))
        val reparsed = WhitelistParser.parse(exported)
        assertEquals(1, reparsed.sources.size)
        assertEquals("UC4a-Gbdw7vOaccHmFo40b9g", reparsed.sources[0].id)
        // Files carry links only — the multiplier itself is UI/sync-managed.
        assertEquals(100, reparsed.sources[0].timeMultiplierPercent)
    }

    // --- homeZone: the four canonical tests ------------------------------
    //
    // A field a family never touches must cost them nothing: the same bytes on
    // disk, the same fingerprint on the wire, and therefore no fleet-wide
    // re-push at upgrade. Asserted here rather than promised in a comment.

    @Test
    fun `a home zone survives a JSON round-trip and clears back to null`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        assertEquals(
            "Pacific/Auckland",
            ConfigJson.fromJson(ConfigJson.toJson(plain.copy(homeZone = "Pacific/Auckland"))).homeZone
        )
        val cleared = plain.copy(homeZone = "Pacific/Auckland").copy(homeZone = null)
        assertEquals(null, ConfigJson.fromJson(ConfigJson.toJson(cleared)).homeZone)
        // A blank is not a zone, and must not become one on the round trip.
        assertEquals(null, ConfigJson.fromJson(ConfigJson.toJson(plain.copy(homeZone = ""))).homeZone)
    }

    @Test
    fun `no home zone is omitted from JSON, byte for byte as before the field`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        val json = ConfigJson.toJson(plain)
        assertFalse(json.contains("homeZone"))
        assertEquals(null, ConfigJson.fromJson(json).homeZone)
        // Explicitly: a document written by this build and one written by the
        // build before it are the same bytes for a family that never sets one.
        // `updatedAt` is stamped at serialization time and is the one field
        // that legitimately moves between two calls (see PLAN-sync's "two
        // clocks"), so it is normalised out rather than the comparison being
        // weakened to a substring.
        fun bytes(w: Whitelist) =
            ConfigJson.toJson(w).replace(Regex("\"updatedAt\": \\d+"), "\"updatedAt\": 0")
        assertEquals(bytes(plain), bytes(plain.copy(homeZone = null)))
    }

    @Test
    fun `configs with no home zone keep their pre-homeZone fingerprint`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        assertEquals(
            ConfigJson.fingerprint(plain),
            ConfigJson.fingerprint(plain.copy(homeZone = null))
        )
    }

    @Test
    fun `setting a home zone moves the fingerprint so the reconcile delivers it`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        assertNotEquals(
            ConfigJson.fingerprint(plain),
            ConfigJson.fingerprint(plain.copy(homeZone = "Pacific/Auckland"))
        )
        // And two zones are two hashes: the offline reconcile only re-pushes
        // on a mismatch, so a changed zone that did not move the hash would
        // never reach the television that has to bucket its minutes by it.
        assertNotEquals(
            ConfigJson.fingerprint(plain.copy(homeZone = "Pacific/Auckland")),
            ConfigJson.fingerprint(plain.copy(homeZone = "Europe/London"))
        )
    }

    // --- budgetScope: the four canonical tests ---------------------------
    //
    // Same contract as homeZone above, and asserted rather than promised for
    // the same reason: this one rides a *limits* scalar, so it has to be
    // invisible on both the family's rules and every kid's, or a household
    // that never shares a budget is re-pushed to the whole fleet at upgrade.

    private fun withKid(w: Whitelist, l: Limits) =
        w.copy(profiles = listOf(Profile(id = "k1", name = "Leo", limits = l)))

    @Test
    fun `a budget scope survives a JSON round-trip, on the family and on a kid`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        val shared = Limits(sessionMinutes = 30, budgetScope = BUDGET_SCOPE_SHARED)
        assertEquals(
            BUDGET_SCOPE_SHARED,
            ConfigJson.fromJson(ConfigJson.toJson(plain.copy(limits = shared))).limits.budgetScope
        )
        assertEquals(
            BUDGET_SCOPE_SHARED,
            ConfigJson.fromJson(ConfigJson.toJson(withKid(plain, shared)))
                .profiles[0].limits.budgetScope
        )
        // Clearing it back to per-device round-trips as null, and a blank is
        // not a scope: it must not come back as one.
        assertEquals(
            null,
            ConfigJson.fromJson(ConfigJson.toJson(plain.copy(limits = shared.copy(budgetScope = null))))
                .limits.budgetScope
        )
        assertEquals(
            null,
            ConfigJson.fromJson(ConfigJson.toJson(plain.copy(limits = shared.copy(budgetScope = ""))))
                .limits.budgetScope
        )
    }

    @Test
    fun `a scope this build does not know is carried, never coerced`() {
        // The whole reason the field is a string. A newer build sets a third
        // mode; this one must hand it back byte-for-byte on the next push, and
        // must behave as it always did in the meantime. Rewriting it to a
        // value this build recognises would push a parent's choice back out of
        // the family from the first device to parse it.
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
        val future = plain.copy(limits = Limits(budgetScope = "school-nights"))
        val parsed = ConfigJson.fromJson(ConfigJson.toJson(future))
        assertEquals("school-nights", parsed.limits.budgetScope)
        assertFalse(parsed.limits.sharesBudget)
        assertTrue(Limits(budgetScope = BUDGET_SCOPE_SHARED).sharesBudget)
        assertFalse(Limits().sharesBudget)
    }

    @Test
    fun `no budget scope is omitted from JSON, byte for byte as before the field`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
            .copy(limits = Limits(sessionMinutes = 30, weekdaySessions = 2))
        val json = ConfigJson.toJson(plain)
        assertFalse(json.contains("budgetScope"))
        assertEquals(null, ConfigJson.fromJson(json).limits.budgetScope)
        fun bytes(w: Whitelist) =
            ConfigJson.toJson(w).replace(Regex("\"updatedAt\": \\d+"), "\"updatedAt\": 0")
        assertEquals(bytes(plain), bytes(plain.copy(limits = plain.limits.copy(budgetScope = null))))
        // And on a kid, which is the copy a family actually sets rules on.
        val kid = withKid(plain, Limits(sessionMinutes = 20))
        assertFalse(ConfigJson.toJson(kid).contains("budgetScope"))
        assertEquals(bytes(kid), bytes(withKid(plain, Limits(sessionMinutes = 20, budgetScope = null))))
    }

    @Test
    fun `configs with no budget scope keep their pre-budgetScope fingerprint`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
            .copy(limits = Limits(sessionMinutes = 30, weekdaySessions = 2, minVideoMinutes = 4))
        assertEquals(
            ConfigJson.fingerprint(plain),
            ConfigJson.fingerprint(plain.copy(limits = plain.limits.copy(budgetScope = null)))
        )
        // A blank hashes as nothing too, or the fingerprint would disagree
        // with the JSON, which reads a blank back as null.
        assertEquals(
            ConfigJson.fingerprint(plain),
            ConfigJson.fingerprint(plain.copy(limits = plain.limits.copy(budgetScope = "")))
        )
        val kid = withKid(plain, Limits(sessionMinutes = 20))
        assertEquals(
            ConfigJson.fingerprint(kid),
            ConfigJson.fingerprint(withKid(plain, Limits(sessionMinutes = 20, budgetScope = null)))
        )
    }

    @Test
    fun `setting a budget scope moves the fingerprint so the reconcile delivers it`() {
        val plain = Whitelist(listOf(entry("UCa")), emptySet())
            .copy(limits = Limits(sessionMinutes = 30, weekdaySessions = 2))
        assertNotEquals(
            ConfigJson.fingerprint(plain),
            ConfigJson.fingerprint(plain.copy(limits = plain.limits.copy(budgetScope = BUDGET_SCOPE_SHARED)))
        )
        // Two scopes are two hashes, unknown ones included: the offline
        // reconcile only re-pushes on a mismatch, so a change this build
        // cannot interpret still has to reach the peer that can.
        assertNotEquals(
            ConfigJson.fingerprint(plain.copy(limits = plain.limits.copy(budgetScope = BUDGET_SCOPE_SHARED))),
            ConfigJson.fingerprint(plain.copy(limits = plain.limits.copy(budgetScope = "school-nights")))
        )
        // And a kid's own scope moves that kid's part of the hash — windows or
        // no windows, because limitsCanon takes two routes and both carry it.
        val kid = withKid(plain, Limits(sessionMinutes = 20))
        assertNotEquals(
            ConfigJson.fingerprint(kid),
            ConfigJson.fingerprint(withKid(plain, Limits(sessionMinutes = 20, budgetScope = BUDGET_SCOPE_SHARED)))
        )
        val windowed = Limits(
            sessionMinutes = 20,
            windows = listOf(TimeWindow(id = "w", label = "Bedtime", startMin = 1200, endMin = 420))
        )
        assertNotEquals(
            ConfigJson.fingerprint(withKid(plain, windowed)),
            ConfigJson.fingerprint(withKid(plain, windowed.copy(budgetScope = BUDGET_SCOPE_SHARED)))
        )
    }
}
