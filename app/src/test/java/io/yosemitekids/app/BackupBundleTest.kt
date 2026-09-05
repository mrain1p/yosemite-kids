package io.yosemitekids.app

import io.yosemitekids.app.data.Backup
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup file's shape check and summary — the parts that don't need a
 * device. Restore itself goes through ConfigStore/WatchSync/ScreeningStore,
 * each covered by its own store tests.
 */
class BackupBundleTest {

    private fun bundle(
        kind: String = "yosemite-kids-backup",
        schema: Int = Backup.SCHEMA,
        withConfig: Boolean = true
    ): String = JSONObject().apply {
        put("kind", kind)
        put("schema", schema)
        put("exportedAt", 1_756_771_200_000L)
        if (withConfig) put("config", JSONObject("""
            {"entries":[{"id":"@a","url":"https://www.youtube.com/@a","kind":"CHANNEL"},
                        {"id":"@b","url":"https://www.youtube.com/@b","kind":"CHANNEL"}],
             "profiles":[{"id":"a1b2c3d4","name":"Emma"}]}
        """.trimIndent()))
        put("verdicts", JSONObject().put("vid1", JSONObject().put("v", "ALLOW")))
    }.toString()

    @Test
    fun inspectSummarisesAValidBundle() {
        val s = Backup.inspect(bundle()).getOrThrow()
        assertEquals(2, s.channels)
        assertEquals(1, s.kids)
        assertEquals(1, s.verdicts)
        assertEquals(1_756_771_200_000L, s.exportedAt)
    }

    @Test
    fun rejectsSomethingThatIsNotABackup() {
        val r = Backup.inspect("""{"entries":[]}""")
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("isn't a Yosemite Kids backup"))
        assertTrue(Backup.inspect("not json").isFailure)
        assertTrue(Backup.inspect(bundle(kind = "other")).isFailure)
    }

    @Test
    fun rejectsANewerSchemaWithAnUpdateHint() {
        val r = Backup.inspect(bundle(schema = Backup.SCHEMA + 1))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("newer Yosemite Kids"))
    }

    @Test
    fun rejectsABundleWithoutSettings() {
        val r = Backup.inspect(bundle(withConfig = false))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("no settings"))
    }

    @Test
    fun acceptsABackupWrittenByThePickwickBuild() {
        // Moving a family to the new package id restores a file the old app
        // wrote; its kind must stay readable for as long as those files exist.
        val s = Backup.inspect(bundle(kind = "pickwick-backup")).getOrThrow()
        assertEquals(2, s.channels)
        assertEquals(1, s.kids)
    }
}
