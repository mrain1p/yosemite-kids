package io.pickwick.app

import io.pickwick.app.data.ConfigJson
import io.pickwick.app.data.AiConfig
import io.pickwick.app.data.ConfigMerge
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.SyncMeta
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `sync` blob's on-the-wire contract.
 *
 * Two properties here are load-bearing far beyond their size. The fingerprint
 * must not see the blob, or an upgraded phone computes a hash an un-upgraded
 * TV can never produce and the pair mismatches forever. And a blob this build
 * cannot read must cost the family its bookkeeping, never its channels.
 */
class ConfigSyncFormatTest {

    private fun entry(id: String, label: String? = null) = WhitelistEntry(
        id = id,
        url = "https://www.youtube.com/channel/$id",
        label = label,
        kind = SourceKind.CHANNEL
    )

    private val populated = SyncMeta(
        docAt = 1780000020000L,
        at = mapOf("src|UCaaa" to 1780000001000L, "settings" to 1780000002000L),
        gone = mapOf("src|UCxyz" to 1780000003000L),
        floor = mapOf("src" to 1780000000000L),
        log = listOf(
            ConfigMerge.Change(
                code = "src.add", text = "added SciShow Kids", id = "9f3a1c02",
                at = 1780000001000L, shownAt = 1780000001000L, by = "a1b2c3d4", who = "Dad's phone"
            )
        )
    )

    private fun config(sync: SyncMeta = SyncMeta.EMPTY) = Whitelist(
        sources = listOf(entry("UCaaa", "SciShow Kids")),
        blockedVideoIds = setOf("v1"),
        sync = sync
    )

    @Test
    fun fingerprintIgnoresTheSyncBlobEntirely() {
        // The single most important assertion in this file. Around fifteen
        // existing tests assert "a config that never uses feature X keeps its
        // pre-X hash"; more importantly, hashing the blob would have an
        // upgraded phone compute a value an un-upgraded TV can never produce,
        // so after the first channel deletion the two mismatch forever and the
        // reconcile stops pushing to it.
        assertEquals(
            ConfigJson.fingerprint(config()),
            ConfigJson.fingerprint(config(populated))
        )
    }

    @Test
    fun aConfigWithNoSyncWritesNoSyncKey() {
        // A family that upgrades and never edits must write a byte-identical
        // file, so nothing re-pushes across the whole fleet at upgrade.
        val json = ConfigJson.toJson(config())
        assertFalse(json.contains("\"sync\""))
        assertTrue(JSONObject(json).isNull("sync"))
    }

    @Test
    fun syncSurvivesTheJsonRoundTrip() {
        val back = ConfigJson.fromJson(ConfigJson.toJson(config(populated)))
        assertEquals(populated.at, back.sync.at)
        assertEquals(populated.gone, back.sync.gone)
        assertEquals(populated.floor, back.sync.floor)
        assertEquals(populated.docAt, back.sync.docAt)
        assertEquals(1, back.sync.log.size)
        assertEquals("added SciShow Kids", back.sync.log.first().text)
        assertEquals("Dad's phone", back.sync.log.first().who)
        assertEquals("a1b2c3d4", back.sync.log.first().by)
    }

    @Test
    fun stripSecretsLeavesTheSyncBlobAlone() {
        val withKey = config(populated).copy(ai = AiConfig(model = "m", apiKey = "sk-secret"))
        val stripped = ConfigJson.stripSecrets(ConfigJson.toJson(withKey))

        assertFalse("the key must go", stripped.contains("sk-secret"))
        val back = ConfigJson.fromJson(stripped)
        assertEquals("the bookkeeping must stay", populated.at, back.sync.at)
        assertEquals(populated.gone, back.sync.gone)
    }

    @Test
    fun aMalformedSyncBlobStillLoadsEveryChannel() {
        val root = JSONObject(ConfigJson.toJson(config(populated)))
        // A newer build's shape this parser cannot swallow.
        root.put("sync", JSONObject().put("v", SyncMeta.VERSION).put("at", "not an object"))

        val back = ConfigJson.fromJson(root.toString())
        assertEquals(
            "channels must survive a blob we cannot read",
            listOf("UCaaa"), back.sources.map { it.id }
        )
        assertEquals(setOf("v1"), back.blockedVideoIds)
        assertTrue("and the unreadable bookkeeping is simply absent", back.sync.at.isEmpty())
    }

    @Test
    fun anUnknownSyncVersionReadsAsNoSyncBlock() {
        val root = JSONObject(ConfigJson.toJson(config(populated)))
        root.getJSONObject("sync").put("v", SyncMeta.VERSION + 7)

        val back = ConfigJson.fromJson(root.toString())
        assertEquals(listOf("UCaaa"), back.sources.map { it.id })
        assertTrue(
            "a version we do not understand must read as absent, not as an error",
            back.sync.isEmpty
        )
    }

    @Test
    fun aGarbageLogEntryDoesNotCostTheRestOfTheLog() {
        val root = JSONObject(ConfigJson.toJson(config(populated)))
        val log = root.getJSONObject("sync").getJSONArray("log")
        log.put("this is not an object")

        val back = ConfigJson.fromJson(root.toString())
        assertEquals(1, back.sync.log.size)
        assertEquals("added SciShow Kids", back.sync.log.first().text)
    }

    @Test
    fun syncHashIgnoresKeyInsertionOrder() {
        // Android's JSONObject is LinkedHashMap-backed and insertion-ordered,
        // so hashing its toString() would have two devices holding identical
        // maps hash differently and push at each other forever. The JVM's
        // org.json uses a plain HashMap, so only a test written this way — two
        // maps built in opposite orders — can pin the property at all.
        val forwards = SyncMeta(
            at = linkedMapOf("src|UCaaa" to 1L, "src|UCbbb" to 2L, "settings" to 3L)
        )
        val backwards = SyncMeta(
            at = linkedMapOf("settings" to 3L, "src|UCbbb" to 2L, "src|UCaaa" to 1L)
        )
        assertEquals(ConfigMerge.syncHash(forwards), ConfigMerge.syncHash(backwards))
    }

    @Test
    fun syncHashIgnoresTheLogAndDocAt() {
        val quiet = SyncMeta(at = mapOf("settings" to 1L))
        val chatty = quiet.copy(
            docAt = 99L,
            log = listOf(ConfigMerge.Change("settings", "changed autoplay", id = "x", at = 1L))
        )
        assertEquals(
            "a log line is not state; two devices with different tails are still in sync",
            ConfigMerge.syncHash(quiet), ConfigMerge.syncHash(chatty)
        )
    }

    @Test
    fun syncHashSeesTombstones() {
        // The other half of the point: same config, but one side knows about a
        // deletion. That must read as out of sync, or the deletion never
        // travels — the reconcile short-circuits on hash equality.
        val plain = SyncMeta(at = mapOf("settings" to 1L))
        val knowsADeletion = plain.copy(gone = mapOf("src|UCxyz" to 5L))
        assertNotEquals(ConfigMerge.syncHash(plain), ConfigMerge.syncHash(knowsADeletion))
    }

    @Test
    fun syncHashSeesTheFloor() {
        val plain = SyncMeta(at = mapOf("settings" to 1L))
        assertNotEquals(
            ConfigMerge.syncHash(plain),
            ConfigMerge.syncHash(plain.copy(floor = mapOf("src" to 5L)))
        )
    }

    @Test
    fun anEmptyBlobIsEmptyWhateverItsVersionField() {
        assertTrue(SyncMeta.EMPTY.isEmpty)
        assertFalse(populated.isEmpty)
    }
}
