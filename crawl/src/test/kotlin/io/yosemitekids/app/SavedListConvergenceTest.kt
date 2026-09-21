package io.yosemitekids.app

import io.yosemitekids.app.data.SavedListStore
import io.yosemitekids.app.data.Video
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The convergence properties of a saved list, which it has never had a test
 * for — and which now matter twice over, because the hub holds the same lists
 * for a child's browser.
 *
 * `.claude/skills/yosemite-kids-sync` states the rule this file exists to
 * keep: **reconcile code is tested by repeated application with the inputs
 * held still.** A merge that is correct once and wrong the second time is the
 * shape of bug that shows up as "her favourites keep coming back" a fortnight
 * later, on one family's pair of devices, and cannot be reproduced by looking
 * at either of them.
 *
 * Three properties, and they are not the same property:
 *
 * 1. **Idempotent** — merging the same thing twice changes nothing the second
 *    time. This is what "sync ran again" does.
 * 2. **Convergent** — two devices that merge each other end up equal,
 *    whichever order they do it in. This is what a household with a phone and
 *    a television does every day.
 * 3. **Removals stay removed** — the tombstone outlives the entry, so a
 *    delete is never undone by a peer who still remembers the add.
 */
class SavedListConvergenceTest {

    private val dirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        dirs.forEach { it.deleteRecursively() }
    }

    /** One device's store, in a directory of its own. */
    /**
     * One wall clock for the whole household, and the test moves it.
     *
     * These cases turn on "the later event wins", so the ORDER of an add and
     * the removal after it is the subject rather than a detail. This used to
     * be `Thread.sleep(2)`, which on Windows — where the system clock ticks
     * about every 15 ms — could leave both on the same millisecond and make
     * the outcome a coin toss.
     */
    private var clock = 1_000_000L

    private fun device(): SavedListStore {
        val dir = Files.createTempDirectory("saved").toFile().also { dirs += it }
        val (file, removed) = SavedListStore.filesIn(dir, SavedListStore.FAVORITES)
        return SavedListStore(file, removed) { clock }
    }

    private fun video(n: Int) = Video(
        "https://youtube.com/watch?v=vid$n", "Video $n", "A Channel", null, 100
    )

    private fun entry(n: Int, at: Long) = SavedListStore.Entry(video(n), at)

    /** What a device would send a peer, and what it would be judged equal by. */
    private fun SavedListStore.snapshot() =
        loadEntries().map { it.video.url to it.addedAt }.sortedBy { it.first } to
            removedMap().toSortedMap()

    @Test
    fun `merging the same payload twice changes nothing the second time`() {
        val a = device()
        a.add(video(1))
        a.add(video(2))

        val incoming = listOf(entry(3, 5_000L))
        a.merge(incoming, emptyMap())
        val once = a.snapshot()
        // Held still, exactly as the sync skill requires: the same inputs, fed
        // again, because that is what a second sync pass is.
        a.merge(incoming, emptyMap())
        assertEquals("a second identical merge moved the list", once, a.snapshot())
        a.merge(incoming, emptyMap())
        assertEquals("a third identical merge moved the list", once, a.snapshot())
    }

    @Test
    fun `two devices that merge each other end up identical`() {
        val phone = device()
        val tv = device()
        phone.add(video(1))
        tv.add(video(2))

        // Each hands the other what it holds, as the LAN sync does.
        val fromPhone = phone.loadEntries() to phone.removedMap()
        val fromTv = tv.loadEntries() to tv.removedMap()
        phone.merge(fromTv.first, fromTv.second)
        tv.merge(fromPhone.first, fromPhone.second)

        assertEquals("the two devices disagree after one exchange", phone.snapshot(), tv.snapshot())
        assertEquals(2, phone.loadEntries().size)
    }

    @Test
    fun `a removal is not resurrected by a peer that still remembers the add`() {
        val phone = device()
        val tv = device()
        phone.add(video(1))

        // The television learns about it...
        tv.merge(phone.loadEntries(), phone.removedMap())
        assertEquals(1, tv.loadEntries().size)

        // ...then the child un-hearts it on the phone, later.
        clock += 1_000
        phone.remove(video(1).url)

        // The television syncs. Twice, because once is not the test.
        repeat(3) { tv.merge(phone.loadEntries(), phone.removedMap()) }
        assertTrue("the removal was undone by the peer's stale add", tv.loadEntries().isEmpty())

        // And back the other way: the phone must not learn the add again from
        // the television it just corrected.
        repeat(3) { phone.merge(tv.loadEntries(), tv.removedMap()) }
        assertTrue("the removal came back from the device it was pushed to", phone.loadEntries().isEmpty())
    }

    /**
     * The cap and the tombstones, which is where this could go wrong quietly.
     *
     * Both files are truncated to [SavedListStore.MAX_ROWS]. Entries survive
     * that fine — both devices sort by the same key and keep the same window.
     * **Tombstones are the risk**: drop the tombstone for a removed video while
     * a peer still holds the add, and the next merge brings it back. A child
     * with a long history un-hearting something old is exactly that case.
     */
    @Test
    fun `a removal survives a full tombstone file`() {
        val phone = device()
        val tv = device()

        // One video the child hearts and then removes, long ago.
        phone.add(video(0))
        tv.merge(phone.loadEntries(), phone.removedMap())
        clock += 1_000
        phone.remove(video(0).url)

        // Then a cap's worth of newer removals pile up on top of it.
        val flood = (1..SavedListStore.MAX_ROWS).map { entry(it, 10_000L + it) }
        phone.merge(flood, emptyMap())
        // Each removal AFTER the last, which is the whole point: the
        // tombstones are kept newest-first and truncated at MAX_ROWS, so
        // video(0)'s has to be genuinely the oldest to be the one that falls
        // off the end. Freezing the clock here made every flood removal share
        // video(0)'s timestamp, a stable sort then kept video(0) first through
        // all 200 rewrites, and the case passed with the floor deleted
        // entirely - the exact regression it exists to catch.
        flood.forEach { clock += 1; phone.remove(it.video.url) }

        // The television still remembers the original add, and syncs.
        repeat(3) { tv.merge(phone.loadEntries(), phone.removedMap()) }

        val resurrected = tv.loadEntries().any { it.video.url == video(0).url }
        assertTrue(
            "a removed video came back because its tombstone fell off the end of " +
                "the file — the cap must not be able to undo a delete",
            !resurrected
        )
    }
}
