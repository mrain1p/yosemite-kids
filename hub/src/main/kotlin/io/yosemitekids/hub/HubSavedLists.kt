package io.yosemitekids.hub

import io.yosemitekids.app.data.QueueStore
import io.yosemitekids.app.data.SavedListStore
import io.yosemitekids.app.data.Video
import java.io.File

/**
 * A browser's Favorites, Watch later and Up next.
 *
 * ### Not a new store
 *
 * Every rule here is `SavedListStore` and `QueueStore` from `:crawl` — the
 * same classes the phone uses, including the tombstone merge and its floor.
 * This class is a *locator*: which folder a given child's lists live in. The
 * alternative was a hub-shaped copy of the merge, and that merge is the one
 * piece of logic in this project whose failure is silent, permanent and
 * unreproducible. There is now exactly one of it, and a convergence test in
 * `:crawl` holds it.
 *
 * ### Hub-local, like the browser's history
 *
 * Kept under the hub's data directory and **not** pushed into the family
 * config, for the reason [HubKidHistory] gives: a browser is a device, and a
 * device's own lists are its own. A phone's favourites sync to a television
 * because both run `WatchSync`; the browser has no such worker and the hub is
 * not going to pretend it does.
 *
 * That is a real parity gap and it is written down rather than hidden —
 * families expect favourites to follow the child, and `KidSurface` records the
 * difference. Closing it means moving `/watchstate` off `HubServer.DEVICE_ONLY`
 * so the hub implements it properly, with guard 22 and the LAN-auth guard
 * watching; it does **not** mean a bespoke kid-origin write that satisfies
 * every existing guard while quietly creating the split.
 *
 * ### The kid id is a directory name
 *
 * Profile ids are eight hex characters (`Profile.newId`), so they cannot
 * traverse — but this never takes one from a request anyway. Every caller
 * derives it from the claim cookie, which is the same rule `/media` follows
 * and guard 60 enforces.
 */
class HubSavedLists(dataDir: File) {

    private val root = File(dataDir, "kid-lists").apply { mkdirs() }

    /**
     * The three lists a child can put a video into.
     *
     * The wire names ARE the `KidSurface` ids, so the manifest, the hub and
     * the page share one vocabulary. They did not at first - this enum said
     * `watchlater` where the manifest said `watch-later` - and guard 62 caught
     * it, which is the whole reason that clause reads the page for the id.
     */
    enum class Which(val wire: String) {
        FAVORITES("favorites"),
        WATCH_LATER("watch-later"),
        QUEUE("up-next"),
        /** Favourite channels: rows shaped like a video whose url is the channel's (SavedListStore.CHANNELS). */
        CHANNELS("channels");

        companion object {
            fun of(wire: String?): Which? = entries.firstOrNull { it.wire == wire }
        }
    }

    private fun dirFor(kid: String): File = File(root, kid).apply { mkdirs() }

    private fun saved(kid: String, which: Which): SavedListStore {
        val name = when (which) {
            Which.FAVORITES -> SavedListStore.FAVORITES
            Which.WATCH_LATER -> SavedListStore.WATCH_LATER
            Which.CHANNELS -> SavedListStore.CHANNELS
            Which.QUEUE -> error("the queue is a QueueStore; see queue()")
        }
        val (file, removed) = SavedListStore.filesIn(dirFor(kid), name)
        return SavedListStore(file, removed)
    }

    private fun queue(kid: String): QueueStore =
        QueueStore(QueueStore.fileIn(dirFor(kid)))

    /** The urls in one list, for marking cards the page already drew. */
    fun urls(kid: String, which: Which): Set<String> = when (which) {
        Which.QUEUE -> queue(kid).urls()
        else -> saved(kid, which).urls()
    }

    /** Everything in one list, newest first — Up next in play order. */
    fun videos(kid: String, which: Which): List<Video> = when (which) {
        Which.QUEUE -> queue(kid).load()
        else -> saved(kid, which).load()
    }

    /**
     * Put a video in a list, or take it out. Returns whether it is in the list
     * afterwards.
     *
     * The answer is read back from the store rather than assumed from the
     * request, because the queue can **refuse**: it is capped, and an add at
     * the cap returns false. A page that flipped its own heart on the tap
     * would show a video as queued that is not.
     */
    fun set(kid: String, which: Which, video: Video, on: Boolean): Boolean {
        when (which) {
            Which.QUEUE -> {
                val q = queue(kid)
                if (on) q.add(video) else q.remove(video.url)
            }
            else -> {
                val s = saved(kid, which)
                if (on) s.add(video) else s.remove(video.url)
            }
        }
        return video.url in urls(kid, which)
    }

    /** Forget one child's lists — what the console's "clear" will call. */
    fun clear(kid: String) {
        dirFor(kid).deleteRecursively()
    }
}
