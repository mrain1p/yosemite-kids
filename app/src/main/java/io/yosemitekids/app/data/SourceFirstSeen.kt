package io.yosemitekids.app.data

import android.content.Context

/**
 * When each whitelisted source first turned up on **this device**.
 *
 * The reported case: "my daughter asked to add a channel. I did and it took
 * several minutes to show up and I had to refresh and change the page and then
 * I had to search for it." Two halves — the fetching, which [MainViewModel]
 * now does for a newcomer immediately, and *finding* it, which needs an order
 * the app did not have. Nothing in the whitelist records when an entry was
 * added: [WhitelistEntry] has no timestamp, and minting one would be a new
 * config field riding the sectioned merge, which is a much larger change than
 * the question deserves.
 *
 * So this is the cheap honest proxy. It is device-local and says exactly what
 * it knows: the first time this device saw the id. On the phone that added the
 * channel that *is* the add time, to within a refresh. On a television that
 * was asleep it is the arrival time, which is still the right answer to "what
 * is new to me". It never travels, so it can never disagree between devices —
 * it is a different fact on each, on purpose.
 *
 * The first run seeds a baseline: every key already present is stamped 0, so
 * an upgrade does not announce the family's whole whitelist as new.
 * Synchronous prefs — call off-main.
 *
 * **Keyed by URL, never by id.** Resolution canonicalizes `/user/`, `/c/` and
 * `@handle` entries to their `UC…` form, so a store written from
 * `WhitelistEntry.id` and read back from `Source.id` misses every entry a
 * parent pasted as a handle. It fails silently — the sort comes back in list
 * order, looking like it was never wired up — which is exactly how it was
 * first shipped and caught on the emulator rather than by a test.
 * `KidSortFilterTest` now states it.
 */
class SourceFirstSeen(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("source_first_seen", Context.MODE_PRIVATE)

    /**
     * Fold the whitelist's URLs into the record and return the ones that are
     * genuinely new.
     *
     * URLs that have gone are forgotten, so a source a parent removed and added
     * back counts as new again — which is what a parent doing that means.
     */
    fun sync(present: List<String>, now: Long = System.currentTimeMillis()): Set<String> {
        val seeded = prefs.getBoolean(SEEDED, false)
        val known = prefs.all.entries.mapNotNull { (k, v) ->
            if (!k.startsWith(PREFIX)) null else (v as? Long)?.let { k.removePrefix(PREFIX) to it }
        }.toMap()
        val (next, fresh) = fold(known, present, seeded, now)
        if (next != known || !seeded) {
            // clear() then the puts, in one editor: SharedPreferences applies
            // the clear first, so this is a replace and departed URLs go with it.
            val edit = prefs.edit().clear().putBoolean(SEEDED, true)
            next.forEach { (url, at) -> edit.putLong(PREFIX + url, at) }
            edit.apply()
        }
        return fresh
    }

    /** Epoch ms this device first saw [url]; 0 for anything present at seeding. */
    fun addedAt(url: String): Long = prefs.getLong(PREFIX + url, 0L)

    companion object {
        private const val PREFIX = "at_"
        private const val SEEDED = "seeded"

        /**
         * Pure so the seeding rule is a test rather than a hope: the first
         * fold ([seeded] false) claims everything at 0 and reports nothing
         * new, and every later one stamps only URLs it has not met.
         */
        internal fun fold(
            known: Map<String, Long>,
            present: List<String>,
            seeded: Boolean,
            now: Long
        ): Pair<Map<String, Long>, Set<String>> {
            if (!seeded) return present.associateWith { 0L } to emptySet()
            val fresh = present.filterNot { it in known }.toSet()
            val next = present.associateWith { known[it] ?: now }
            return next to fresh
        }
    }
}
