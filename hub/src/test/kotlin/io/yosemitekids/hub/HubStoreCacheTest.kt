package io.yosemitekids.hub

import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import io.yosemitekids.app.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The family document is read far more often than it changes.
 *
 * The play gate runs before **every two megabytes** a kid's browser fetches —
 * that is what makes a parent's pause land mid-video rather than at the next
 * episode — and each run read the file off the disk and parsed the whole
 * thing. A hundred-megabyte episode was fifty full parses, on a Celeron that
 * was also pumping the video, and the console's poll adds two more every
 * twenty seconds.
 *
 * Caching that is only safe if the cache can be wrong in no direction that
 * matters, which is what these four cases are: it must not go stale after a
 * write this process made, after a write something else made, or after a
 * restore — and it must actually be a cache, which `assertSame` is the only
 * honest way to say.
 */
class HubStoreCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_780_000_000_000L

    private fun store(): HubStore = HubStore(tmp.newFolder("hub"))

    private fun withChannel(id: String) = Whitelist(
        listOf(WhitelistEntry(id, "https://www.youtube.com/channel/$id", id, SourceKind.CHANNEL)),
        emptySet()
    )

    @Test
    fun readingTwiceParsesOnce() {
        val store = store()
        store.edit("test", now) { withChannel("UCone") }
        assertSame("the second read must not re-parse the file", store.load(), store.load())
    }

    @Test
    fun aWriteThroughThisStoreIsSeenByTheNextRead() {
        val store = store()
        store.edit("test", now) { withChannel("UCone") }
        val first = store.load()
        store.edit("test", now + 1000) { withChannel("UCtwo") }
        val second = store.load()
        assertNotSame(first, second)
        assertEquals("UCtwo", second.sources.single().id)
    }

    /**
     * The one thing the (mtime, length) stamp cannot do for itself.
     *
     * mtime granularity is a whole second on plenty of filesystems, so two
     * writes of the same length inside one tick are indistinguishable by the
     * stamp alone — a parent toggling one boolean twice is exactly that. That
     * is why commit() clears the cache explicitly, and this is the case that
     * fails if the clear is deleted: without it, load() keeps serving the
     * pre-write document to the play gate, the console poll and the crawl.
     */
    @Test
    fun aSecondWriteInsideOneMtimeTickIsStillSeen() {
        val dir = tmp.newFolder("hub4")
        val store = HubStore(dir)
        val file = File(dir, "config.json")

        store.edit("test", now) { withChannel("UCone") }
        assertEquals("UCone", store.load().sources.single().id)
        val stamp = file.lastModified()

        // Same length, and forced back to the same mtime: the stamp cannot
        // tell these two documents apart, so only the explicit clear can.
        store.edit("test", now + 1000) { withChannel("UCtwo") }
        file.setLastModified(stamp)

        assertEquals("UCtwo", store.load().sources.single().id)
    }

    @Test
    fun aFileReplacedUnderneathIsSeenByTheNextRead() {
        val dir = tmp.newFolder("hub2")
        val store = HubStore(dir)
        store.edit("test", now) { withChannel("UCone") }
        assertEquals("UCone", store.load().sources.single().id)

        // A restore, a hand-edit on the box, a volume rolled back: nothing
        // here went through commit(), so only the file's own stamp says so.
        val file = File(dir, "config.json")
        file.writeText(ConfigJson.toJson(withChannel("UCthree-and-longer")))
        file.setLastModified(file.lastModified() + 5_000L)

        assertEquals("UCthree-and-longer", store.load().sources.single().id)
    }

    @Test
    fun aDamagedFileIsStillReportedAsDamagedAfterAGoodRead() {
        val dir = tmp.newFolder("hub3")
        val store = HubStore(dir)
        store.edit("test", now) { withChannel("UCone") }
        assertTrue("a readable config is not degraded", !store.degraded())

        val file = File(dir, "config.json")
        file.writeText("{ this is not json")
        file.setLastModified(file.lastModified() + 5_000L)

        assertTrue("a cache from before the damage must not say the file is fine", store.degraded())
    }
}
