package io.yosemitekids.app

import io.yosemitekids.app.data.BackupFile
import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one envelope both faces write and both faces read.
 *
 * A backup is for the day the device that made it is gone, so "the phone can
 * read the hub's file and the hub can read the phone's" is the feature, not a
 * nicety. The interesting half is what [BackupFile.configIn] must *refuse*:
 * an empty config parses perfectly and means "no channels, no kids, no
 * rules", so anything that reads as a config and is not one would restore as
 * an instruction to wipe the family.
 */
class BackupFileTest {

    private val config = ConfigJson.toJson(
        Whitelist(
            listOf(WhitelistEntry("UC1", "https://youtube.com/channel/UC1", "SciShow Kids", SourceKind.CHANNEL)),
            emptySet()
        )
    )

    @Test
    fun aWrappedFileCarriesTheDocumentBackUnchanged() {
        val file = BackupFile.wrap(config, at = 1_780_000_000_000L, app = "hub")
        val root = JSONObject(file)
        assertEquals(BackupFile.KIND, root.getString("kind"))
        assertEquals(BackupFile.SCHEMA, root.getInt("schema"))
        assertEquals(1_780_000_000_000L, root.getLong("exportedAt"))

        val inside = BackupFile.configIn(file) ?: error("the envelope did not read back")
        assertEquals(
            "the settings must survive the round trip byte for byte",
            ConfigJson.fingerprint(ConfigJson.fromJson(config)),
            ConfigJson.fingerprint(ConfigJson.fromJson(inside))
        )
    }

    @Test
    fun aPhoneWrittenEnvelopeIsRead() {
        // What `Backup.export` writes, including the fields the hub does not
        // model: they ride along and are ignored rather than refused.
        val phone = JSONObject()
            .put("kind", BackupFile.KIND)
            .put("schema", 1)
            .put("exportedAt", 1L)
            .put("app", "1.0.6")
            .put("config", JSONObject(config))
            .put("watchState", JSONObject().put("history", JSONObject()))
            .put("verdicts", JSONObject())
            .toString()
        assertNotNull(BackupFile.configIn(phone))
    }

    @Test
    fun aFileFromBeforeTheRenameStillReads() {
        // Moving a family to the new package id is "back up on the old app,
        // restore here", and that file says pickwick-backup.
        val old = JSONObject()
            .put("kind", BackupFile.LEGACY_KIND)
            .put("schema", 1)
            .put("config", JSONObject(config))
            .toString()
        assertNotNull(BackupFile.configIn(old))
    }

    @Test
    fun aBareConfigDocumentIsAccepted() {
        // `GET /config` and the hub's own config.json are this shape, and a
        // parent who saved one is holding a backup whether they meant to or
        // not.
        assertNotNull(BackupFile.configIn(config))
    }

    @Test
    fun aFileFromANewerBuildIsRefusedRatherThanHalfRestored() {
        val future = JSONObject()
            .put("kind", BackupFile.KIND)
            .put("schema", BackupFile.SCHEMA + 1)
            .put("config", JSONObject(config))
            .toString()
        assertNull(BackupFile.configIn(future))
    }

    @Test
    fun somethingThatIsNotABackupIsRefused() {
        // The one that matters. An empty object parses as a valid config
        // meaning "no channels, no kids, no rules" — so accepting it would
        // turn any stray JSON file into a button that empties the family's
        // settings, with a confirmation dialog that said "restore".
        listOf(
            "",
            "not json at all",
            "{}",
            "[]",
            JSONObject().put("kind", "something-else").put("config", JSONObject(config)).toString(),
            JSONObject().put("kind", BackupFile.KIND).toString(),
            JSONObject().put("entries", org.json.JSONArray()).toString()
        ).forEach {
            assertNull("\"${it.take(40)}\" was read as a backup", BackupFile.configIn(it))
        }
    }
}
