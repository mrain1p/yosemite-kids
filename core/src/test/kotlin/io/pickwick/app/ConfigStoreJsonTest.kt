package io.pickwick.app

import io.pickwick.app.data.ConfigJson
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import io.pickwick.app.data.WhitelistExporter
import io.pickwick.app.data.WhitelistParser
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
            limits = io.pickwick.app.data.Limits(sessionMinutes = 30, pausedUntilMillis = until)
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

        val ruled = plain.copy(limits = io.pickwick.app.data.Limits(minVideoMinutes = 5))
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
}
