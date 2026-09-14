package io.yosemitekids.app.data

import java.io.File

/**
 * One of the kid's saved video lists — Favorites or Watch later — newest
 * first. Adds and removals are timestamped so devices can merge (latest event
 * per video wins) without removals resurrecting on the next sync.
 *
 * [listName] picks the backing files. Favorites keeps the historical
 * "watchlist" spelling: renaming it would orphan every installed family's
 * saved videos, and the list is only called Favorites on screen.
 *
 * **In `:crawl` rather than `:app`, and taking a folder rather than a
 * `Context`.** A browser is a third face, and the hub has to hold a tablet's
 * favourites the way it already holds its history ([io.yosemitekids.hub] has
 * no business re-implementing tombstone causality). Copying [merge] into the
 * hub would have been two implementations of the one piece of logic in this
 * project whose failure is silent and permanent — a removal that resurrects,
 * or a heart that vanishes, on a schedule nobody can reproduce. `:app` keeps a
 * same-named factory taking a `Context` (`data/AndroidStores.kt`), so every
 * call site on the phone reads exactly as it did.
 */
class SavedListStore(
    private val file: File,
    private val removedFile: File
) {

    data class Entry(val video: Video, val addedAt: Long)

    fun loadEntries(): List<Entry> = synchronized(LOCK) {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 5) return@mapNotNull null
                Entry(
                    Video(p[0], p[1], p[2], p[3].ifEmpty { null }, p[4].toLongOrNull() ?: 0L),
                    // Legacy rows (5 cols) predate sync — treat as ancient adds.
                    p.getOrNull(5)?.toLongOrNull() ?: 1L
                )
            }
        }.getOrDefault(emptyList())
    }

    fun load(): List<Video> = loadEntries().map { it.video }

    fun urls(): Set<String> = loadEntries().map { it.video.url }.toSet()

    /**
     * url → when it was removed (tombstones for merge), including [FLOOR_KEY].
     *
     * The floor rides along on purpose: it is bookkeeping a peer needs as much
     * as the tombstones themselves, and the wire format for these lists is
     * "whatever `removedMap` returns". A build that predates the floor reads it
     * as a tombstone for a video called `#floor`, which matches nothing and
     * costs nothing — which is what makes this safe to ship to a fleet where
     * one television is three versions behind.
     */
    fun removedMap(): Map<String, Long> = synchronized(LOCK) {
        if (!removedFile.exists()) return emptyMap()
        return runCatching {
            removedFile.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 2) null else p[0] to (p[1].toLongOrNull() ?: 0L)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * The watermark under which an add is treated as deleted even though its
     * own tombstone is gone.
     *
     * **Why this exists, and it is a bug this codebase shipped.** Tombstones
     * are capped like everything else, and `saveRemoved` kept the newest
     * [MAX_ROWS]. So a child who un-hearted something, then hearted and
     * un-hearted two hundred more, pushed the first removal off the end — and
     * the next sync with a device that still remembered the *add* brought the
     * video back. A delete undone by bookkeeping, on a schedule nobody could
     * reproduce, which is the exact failure `.claude/skills/yosemite-kids-sync`
     * warns about and which no test here was asking about.
     *
     * The fix is `ConfigMerge`'s, not a new invention: evicting a tombstone
     * raises a floor, and an add older than the floor is still refused. The
     * cost is stated plainly — a peer that has been offline since before the
     * floor was raised can have a genuine old add refused. That is the right
     * direction to fail: a favourite that needs re-hearting is a nuisance, and
     * a removed video reappearing on a child's screen is the thing this whole
     * product exists to prevent.
     */
    fun floor(): Long = removedMap()[FLOOR_KEY] ?: 0L

    fun add(video: Video) = synchronized(LOCK) {
        saveEntries(
            listOf(Entry(video, System.currentTimeMillis())) +
                loadEntries().filter { it.video.url != video.url }
        )
        saveRemoved(removedMap() - video.url - FLOOR_KEY, floor())
    }

    fun remove(videoUrl: String) = synchronized(LOCK) {
        saveEntries(loadEntries().filter { it.video.url != videoUrl })
        saveRemoved(removedMap() + (videoUrl to System.currentTimeMillis()), floor())
    }

    /** Merge another device's list: per video, the latest add/remove event wins. */
    fun merge(incoming: List<Entry>, incomingRemoved: Map<String, Long>) = synchronized(LOCK) {
        val localEntries = loadEntries().associateBy { it.video.url }
        val incomingByUrl = HashMap<String, Entry>(incoming.size)
        incoming.forEach { incomingByUrl.putIfAbsent(it.video.url, it) }
        val localRemoved = removedMap()
        val allUrls = localEntries.keys + localRemoved.keys +
            incomingByUrl.keys + incomingRemoved.keys

        // The higher of the two floors. A peer that has evicted tombstones we
        // still hold knows something we do not: that everything under its floor
        // is deleted. Taking the max is how that knowledge travels, and it is
        // what ConfigMerge does with its namespace floors for the same reason.
        val floor = maxOf(localRemoved[FLOOR_KEY] ?: 0L, incomingRemoved[FLOOR_KEY] ?: 0L)

        val mergedEntries = mutableListOf<Entry>()
        val mergedRemoved = mutableMapOf<String, Long>()
        allUrls.forEach { url ->
            if (url == FLOOR_KEY) return@forEach
            val addTs = maxOf(localEntries[url]?.addedAt ?: 0L,
                incomingByUrl[url]?.addedAt ?: 0L)
            val remTs = maxOf(localRemoved[url] ?: 0L, incomingRemoved[url] ?: 0L)
            // An add at or under the floor is one whose tombstone was evicted:
            // refused, rather than resurrected. See [floor].
            if (addTs > remTs && addTs > 0 && addTs > floor) {
                val video = localEntries[url]?.video ?: incomingByUrl.getValue(url).video
                mergedEntries += Entry(video, addTs)
            } else if (remTs > 0) {
                mergedRemoved[url] = remTs
            }
        }
        saveEntries(mergedEntries.sortedByDescending { it.addedAt })
        saveRemoved(mergedRemoved, floor)
    }

    private fun saveEntries(entries: List<Entry>) {
        runCatching {
            file.writeText(entries.take(MAX_ROWS).joinToString("\n") { e ->
                listOf(
                    e.video.url,
                    e.video.title.tsvCell(),
                    e.video.channelName.tsvCell(),
                    e.video.thumbnailUrl.orEmpty(),
                    e.video.durationSeconds.toString(),
                    e.addedAt.toString()
                ).joinToString("\t")
            })
        }
    }

    /**
     * Write the tombstones, and raise the floor by whatever falls off the end.
     *
     * The eviction is the dangerous half and it is why [floor] exists: the
     * newest [MAX_ROWS] are kept, and the highest timestamp among the *dropped*
     * ones becomes the watermark. Anything a peer still holds an add for, older
     * than that, stays deleted. Exactly `ConfigStamp`'s namespace floors, one
     * store along.
     */
    private fun saveRemoved(removed: Map<String, Long>, knownFloor: Long = 0L) {
        runCatching {
            val real = removed.filterKeys { it != FLOOR_KEY }
            val ordered = real.entries.sortedByDescending { it.value }
            val kept = ordered.take(MAX_ROWS)
            val dropped = ordered.drop(MAX_ROWS)
            val floor = maxOf(
                maxOf(knownFloor, removedMap()[FLOOR_KEY] ?: 0L),
                dropped.maxOfOrNull { it.value } ?: 0L
            )
            val rows = kept.map { "${it.key}\t${it.value}" } +
                if (floor > 0) listOf("$FLOOR_KEY\t$floor") else emptyList()
            removedFile.writeText(rows.joinToString("\n"))
        }
    }

    companion object {
        /** On-disk name of the hearted list (see the class doc). */
        const val FAVORITES = "watchlist"

        /** On-disk name of the queue-for-another-day list. */
        const val WATCH_LATER = "watchlater"

        /**
         * Rows kept, and tombstones kept.
         *
         * Named because the hub holds the same lists for a browser and a cap
         * applied on one face and not the other is a shelf that is silently a
         * different length — the failure `KidSurface`'s cap list exists to
         * make impossible.
         */
        const val MAX_ROWS = 200

        /**
         * The reserved row in the tombstone file that carries the floor.
         *
         * A key no video URL can collide with — every real one starts `http`
         * or `yosemitekids://`. Kept in the same file rather than a third one
         * so the floor travels with the tombstones it belongs to, on a wire
         * format that is simply "whatever `removedMap` returns", and so a
         * device three versions behind reads it as a tombstone for a video
         * that does not exist rather than as a parse error.
         */
        const val FLOOR_KEY = "#floor"

        /** The pair of files one list lives in, given the folder it belongs in. */
        fun filesIn(dir: File, listName: String, profileSuffix: String = ""): Pair<File, File> =
            File(dir, "$listName$profileSuffix.tsv") to
                File(dir, "${listName}_removed$profileSuffix.tsv")

        /** Written from the UI (kid taps) and LAN sync workers over one file —
         *  an unsynchronized read-modify-write would drop one of the two.
         *  Coarse across both lists: the taps are seconds apart, not contended. */
        private val LOCK = Any()
    }
}
