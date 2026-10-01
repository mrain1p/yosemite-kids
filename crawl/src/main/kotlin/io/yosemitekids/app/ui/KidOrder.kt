package io.yosemitekids.app.ui

import io.yosemitekids.app.data.CHANNEL_LAYOUT_POPULAR
import io.yosemitekids.app.data.CHANNEL_ORDER_ADDED
import io.yosemitekids.app.data.CHANNEL_ORDER_ALPHA
import io.yosemitekids.app.data.CHANNEL_ORDER_ALPHA_DESC
import io.yosemitekids.app.data.CHANNEL_ORDER_LATEST
import io.yosemitekids.app.data.CHANNEL_ORDER_RANDOM
import io.yosemitekids.app.data.Source
import io.yosemitekids.app.data.VIDEO_FILTER_NEW
import io.yosemitekids.app.data.VIDEO_FILTER_POPULAR
import io.yosemitekids.app.data.VIDEO_FILTER_RANDOM
import io.yosemitekids.app.data.Video

/**
 * What order things come in — the sorts behind every chip a kid can press.
 *
 * **In `:crawl` because the browser has the same chips.** These are typed on
 * [Source] and [Video], which live here, so `:core` cannot see them; this is
 * the same move `KidHome` made one level up, for the same reason. Left in
 * `:app` the hub would have had to re-derive "A to Z" and "Random" — and
 * *random* is the one that cannot be re-derived at all, because a shuffle only
 * agrees between two faces if both are given the same seed.
 *
 * The package is `io.yosemitekids.app.ui`, matching where these lived, so the
 * move cost `:app` not one import — the same trick `HomeSections` used when it
 * went to `:core`.
 */

/** A video plus its local watch progress (0..1), null if never watched. */
data class VideoItem(val video: Video, val progress: Float?)

/**
 * Newest upload first; anything the extractor had no date for, last.
 *
 * Generic over what is being sorted because the two faces hold the mix in
 * different shapes — the phone a [VideoItem], the hub a video paired with its
 * watch fraction — and the ORDER is the thing that must not differ.
 *
 * A missing date sorts last rather than first: an undated video is not a new
 * one. Last also means a list where nothing is dated comes back untouched,
 * because the sort is stable and every key is equal.
 */
fun <T> byNewest(items: List<T>, publishedAt: (T) -> Long?): List<T> =
    items.sortedByDescending { publishedAt(it) ?: Long.MIN_VALUE }

/** "Popular first": by YouTube view count, unknown counts last, ties keep upload order. */
fun orderByPopularity(items: List<VideoItem>): List<VideoItem> =
    items.sortedByDescending { it.video.viewCount ?: -1L }

/** Watched videos newest-watched first, for the History tile and shelf. */
fun orderByWatched(items: List<VideoItem>, watchedAt: (String) -> Long): List<VideoItem> =
    items.sortedByDescending { watchedAt(it.video.url) }

/**
 * The channel row / Channels tab in the order the kid (or, by default, the
 * parent) asked for. Most watched = most opened here; A to Z and the same
 * alphabet backwards; a shuffle that holds still for the whole visit ([seed]
 * — a row that reorders under the kid's thumb is a bug, not a surprise);
 * latest video = the channel whose newest upload is newest, channels with no
 * dated upload last; just added = newest arrival first, by [addedAt]. Every
 * sort is stable, so ties keep the whitelist order — which is also insertion
 * order, so channels this device has always known (all [addedAt] 0) fall back
 * to the order the parent's list is in rather than to nothing.
 *
 * [addedAt] is keyed by **URL** where the other two are keyed by id, and that
 * asymmetry is the whole trap. `opens` and `latestUpload` are written under
 * the *resolved* id, so an id join is right for them. What a source was
 * called when it entered the whitelist is not: resolution canonicalizes
 * `/user/`, `/c/` and `@handle` entries to `UC…` form, so a store filled from
 * `WhitelistEntry.id` and read back by `Source.id` misses every entry a
 * parent pasted as a handle — which is most of them. It does not throw and it
 * does not log; the sort simply comes back in list order and looks like it
 * was never wired up. `MainViewModel.refresh` says the same thing about its
 * own joins, and this was still got wrong once.
 */
fun orderChannels(
    channels: List<Source>,
    sort: String,
    opens: (String) -> Int,
    latestUpload: (String) -> Long?,
    seed: Long,
    addedAt: (String) -> Long = { 0L },
    /** The kid's favourite channels, by url: first under every sort, in that sort's own order among themselves. */
    favourites: Set<String> = emptySet()
): List<Source> {
    val ordered = when (sort) {
        CHANNEL_ORDER_ALPHA -> channels.sortedBy { it.name.lowercase() }
        CHANNEL_ORDER_ALPHA_DESC -> channels.sortedByDescending { it.name.lowercase() }
        CHANNEL_ORDER_RANDOM -> channels.shuffled(kotlin.random.Random(seed))
        CHANNEL_ORDER_LATEST -> channels.sortedByDescending { latestUpload(it.id) ?: Long.MIN_VALUE }
        CHANNEL_ORDER_ADDED -> channels.sortedByDescending { addedAt(it.url) }
        else -> channels.sortedByDescending { opens(it.id) }
    }
    // A stable partition: the hearts float up, the chip still decides the
    // order inside each half, and a kid with no favourites sees no change.
    return if (favourites.isEmpty()) ordered else ordered.sortedBy { if (it.url in favourites) 0 else 1 }
}

/**
 * A video list in the order the kid's chip asks for: random is a seeded
 * shuffle that holds until the next refresh, popular is [orderByPopularity],
 * and *New* depends on where the list came from — see [mixed].
 *
 * @param mixed whether [items] came from more than one channel.
 *
 *   **This is the whole subtlety, and getting it wrong is visible either
 *   way.** One channel's feed arrives from YouTube newest-first and its dates
 *   are patchy, so arrival order is the better answer and sorting by date
 *   would shuffle every undated video to the end of a list that was already
 *   right. A HOME feed has no such order: it interleaves one page from each
 *   channel, so the first card under *New* was the newest video of whichever
 *   channel came first — three years old if that channel has not posted
 *   since, sitting above something from last week. That is what a parent saw
 *   on a phone with seventy-six videos on the shelf, and it is why the flag
 *   exists rather than one rule for both.
 */
fun filterVideos(
    items: List<VideoItem>,
    filter: String?,
    seed: Long,
    mixed: Boolean = false
): List<VideoItem> =
    when (filter) {
        VIDEO_FILTER_RANDOM -> items.shuffled(kotlin.random.Random(seed))
        VIDEO_FILTER_POPULAR -> orderByPopularity(items)
        VIDEO_FILTER_NEW -> if (mixed) byNewest(items) { it.video.publishedAt } else items
        else -> items
    }

/** The parent's channel page layout as the kid's default filter (the playlist layout keeps newest). */
fun defaultFilterFor(channelLayout: String): String =
    if (channelLayout == CHANNEL_LAYOUT_POPULAR) VIDEO_FILTER_POPULAR else VIDEO_FILTER_NEW

/**
 * The Surprise mix: every video a kid may see, shuffled — **seeded**.
 *
 * `MainViewModel` called bare `shuffled()`, which is the one ordering in the
 * product that cannot be shared by construction: two faces asked for "a
 * surprise" would produce two different surprises, and a child who reloaded
 * would get a third. A seed makes it a shuffle that holds still for a sitting
 * and is the same on the television and the tablet, which is what the chip
 * already promises everywhere else ([orderChannels]'s random, [filterVideos]'s).
 *
 * Deduplicated by url first: a video on two of a family's channels should not
 * get two chances to come up.
 */
fun surpriseMix(videos: List<Video>, seed: Long, max: Int = SURPRISE_MAX): List<Video> =
    videos.distinctBy { it.url }.shuffled(kotlin.random.Random(seed)).take(max)

/** How many videos a Surprise draws. A mix, not a second feed. */
const val SURPRISE_MAX = 60
