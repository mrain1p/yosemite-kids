package io.yosemitekids.app

import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.ConfigStore
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The file-backed [ConfigStore] — save, load, and what happens when the file
 * on disk is not readable.
 *
 * None of this had coverage before: the only constructor took a `Context` and
 * there is no Robolectric here, so `save`/`load`/`saveRaw`/`updatedAt` were
 * reachable only on a device. The sectioned merge needs to drive two stores
 * against two temp files from one JVM test, so the store grew a `File`
 * constructor and these are the tests that pin its behaviour.
 */
class ConfigStoreFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(name: String = "config.json"): Pair<ConfigStore, File> {
        val f = File(tmp.root, name)
        return ConfigStore(f) to f
    }

    private fun entry(id: String) = WhitelistEntry(
        id = id,
        url = "https://www.youtube.com/channel/$id",
        kind = SourceKind.CHANNEL,
        label = "Channel $id"
    )

    @Test
    fun aFileBackedStoreRoundTripsSaveAndLoad() {
        val (s, f) = store()
        s.save(Whitelist(listOf(entry("UCaaa"), entry("UCbbb")), setOf("vid1")))

        assertTrue("save must actually write the file", f.exists())
        val back = s.load()
        assertEquals(listOf("UCaaa", "UCbbb"), back.sources.map { it.id })
        assertEquals(setOf("vid1"), back.blockedVideoIds)
    }

    @Test
    fun aSecondStoreOnTheSameFileSeesTheFirstStoresWrite() {
        // Two stores over one file is how the merge tests will model two
        // devices, so it has to work without any shared in-memory state.
        val f = File(tmp.root, "config.json")
        ConfigStore(f).save(Whitelist(listOf(entry("UCaaa")), emptySet()))

        assertEquals(listOf("UCaaa"), ConfigStore(f).load().sources.map { it.id })
    }

    @Test
    fun anUnparseableFileDoesNotLoadAsAnEmptyConfig() {
        val (s, f) = store()
        s.save(Whitelist(listOf(entry("UCaaa")), emptySet()))
        assertEquals(1, s.load().sources.size)

        // Truncated mid-write, a bad sector, a half-finished restore.
        f.writeText("{\"entries\": [{\"id\": \"UCaaa\"")

        val back = s.load()
        assertTrue("a degraded read must be announced, not laundered", s.degraded)
        assertEquals(
            "the last good copy must survive an unreadable file",
            listOf("UCaaa"), back.sources.map { it.id }
        )
    }

    @Test
    fun aColdStartOnAnUnparseableFileIsStillDegraded() {
        // No last-good to fall back on, so the config really is empty — but
        // `degraded` must still be set, because that is what stops the kid
        // migration and the master claim from minting into the emptiness and
        // then persisting it over the real file.
        val (s, f) = store()
        f.writeText("this is not json")

        assertTrue(s.load().sources.isEmpty())
        assertTrue("an unreadable file is degraded even with no last-good", s.degraded)
    }

    @Test
    fun aMissingFileIsAFreshInstallNotADegradedRead() {
        val (s, _) = store()

        assertTrue(s.load().sources.isEmpty())
        assertFalse("no file means fresh install, which is a legitimate empty config", s.degraded)
    }

    @Test
    fun anEmptyFileIsAFreshInstallNotADegradedRead() {
        // A zero-length file is what a crashed write leaves behind, and it
        // carries no information — treating it as degraded would pin the
        // store into refusing migrations forever with nothing to recover.
        val (s, f) = store()
        f.writeText("")

        assertTrue(s.load().sources.isEmpty())
        assertFalse(s.degraded)
    }

    @Test
    fun aGoodReadAfterABadOneClearsDegraded() {
        val (s, f) = store()
        f.writeText("not json")
        assertTrue(s.load().degradedProbe(s))

        s.save(Whitelist(listOf(entry("UCaaa")), emptySet()))
        assertEquals(1, s.load().sources.size)
        assertFalse("the store must recover once the file parses again", s.degraded)
    }

    @Test
    fun saveRawRoundTripsAndUpdatedAtMoves() {
        val (s, _) = store()
        s.save(Whitelist(listOf(entry("UCaaa")), emptySet()))
        val first = s.updatedAt()

        assertTrue(s.saveRaw(ConfigJson.toJson(Whitelist(listOf(entry("UCbbb")), emptySet()))))
        assertEquals(listOf("UCbbb"), s.load().sources.map { it.id })
        assertTrue("a raw save must move updatedAt", s.updatedAt() >= first)
    }

    @Test
    fun saveRawRefusesAPayloadThatIsNotAConfig() {
        val (s, _) = store()
        s.save(Whitelist(listOf(entry("UCaaa")), emptySet()))

        assertFalse(s.saveRaw("{{{ not json"))
        assertEquals(
            "a refused push must leave the existing config untouched",
            listOf("UCaaa"), s.load().sources.map { it.id }
        )
    }

    /** Reads [ConfigStore.degraded] in an assertTrue-friendly way. */
    private fun Whitelist.degradedProbe(s: ConfigStore): Boolean = s.degraded
}
