package io.yosemitekids.app.ui

import io.yosemitekids.app.data.*

sealed interface Screen {
    data object Home : Screen
    /** Every channel as a grid — the "Show all" behind the home row (phone tabs). */
    data object Channels : Screen
    /** The search page before a query: field, mic, recent searches (phone tabs). */
    data object Search : Screen
    data class ChannelVideos(val source: Source) : Screen
    /**
     * One channel's finished videos, off the main grid. The only two-level
     * screen in the app — back returns to the channel, not home.
     */
    data class WatchedVideos(val source: Source) : Screen
    /** Every playlist a channel has, with its video count — behind the strip's "See all". */
    data class Playlists(val source: Source) : Screen
    /** Random mix across all whitelisted sources. */
    data object Surprise : Screen
    /** The kid's hearted videos ("Favorites" on screen). */
    data object Watchlist : Screen
    /** Videos the kid lined up for another day. */
    data object WatchLater : Screen
    /** Parent-approved videos stored on the device — the offline shelf. */
    data object Downloads : Screen
    /** The kid's lined-up videos for one sitting, in play order. */
    data object Queue : Screen
    /** Everything this kid has watched, across channels, newest first. */
    data object History : Screen
    /**
     * The kid's own tab: their avatar, then Favorites / Watch later / Up
     * next / History / Downloads as rows with "See all" into each shelf.
     */
    data object You : Screen
    /** Results of a whitelist-scoped search. */
    data class SearchResults(val query: String) : Screen
}

/** A video plus its local watch progress (0..1), null if never watched. */
data class VideoItem(val video: Video, val progress: Float?)

/** One row of the You tab: which shelf it previews, and its first few videos. */
data class YouShelf(val screen: Screen, val emoji: String, val title: String, val items: List<VideoItem>)

/** One of the parent-picked playlists on a channel's page: the playlist and its first videos. */
data class PlaylistShelf(val playlist: PlaylistRef, val items: List<VideoItem>)

data class UiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    /** A whitelist re-fetch is in flight (drives the pull-to-refresh spinner). */
    val refreshing: Boolean = false,
    /**
     * Settings are syncing with a paired device, or the search index is
     * crawling. Drives the ring round the avatar — the app did all of this
     * silently before, so a parent could not tell "it is coming" from "it did
     * not work".
     */
    val syncing: Boolean = false,
    val error: String? = null,
    val channels: List<Source> = emptyList(),
    /**
     * The home's shelves, in order, with their enabled flags — the one list
     * both form factors draw. It is state and not a hard-coded sequence of
     * composables so that the parent's home-screen builder, when it arrives,
     * is a change to this list and not a rewrite of the home.
     */
    val homeSections: List<HomeSection> = homeSections(emptyList()),
    /** The pinned hero's cards, already filtered to what this kid may see. */
    val pinned: List<PinnedItem> = emptyList(),
    val screen: Screen = Screen.Home,
    val videos: List<VideoItem> = emptyList(),
    /**
     * Videos on this screen hidden by AI screening (awaiting a verdict or held
     * for parent review). Lets an all-held source explain itself instead of
     * rendering as inexplicably empty.
     */
    val held: Int = 0,
    /** Partially-watched videos, most recent first (home-screen resume row). */
    val keepWatching: List<VideoItem> = emptyList(),
    /**
     * The home feed: newest videos across every visible channel, interleaved
     * channel by channel (most-watched channels first), finished ones dropped.
     * Built from the per-channel caches, so it paints instantly.
     */
    val feed: List<VideoItem> = emptyList(),
    /** Channel name → avatar URL, for the small avatar on every video card. */
    val channelAvatars: Map<String, String?> = emptyMap(),
    /** The last few videos this kid watched, newest first — the TV home's History row. */
    val recentHistory: List<VideoItem> = emptyList(),
    /**
     * "More like what you watch" — unwatched videos matched against this kid's
     * own history on this device. Empty when the parent turned suggestions off,
     * and for a kid who hasn't watched anything yet.
     */
    val suggested: List<VideoItem> = emptyList(),
    /**
     * The open channel's playlists (its row above the grid), when the parent
     * chose the "By playlist" layout and the channel has any. Empty otherwise.
     */
    val channelPlaylists: List<io.yosemitekids.app.data.PlaylistRef> = emptyList(),
    /** The parent-picked playlists of the open channel, each with its first videos, as rows above the grid. */
    val playlistShelves: List<PlaylistShelf> = emptyList(),
    /** The You tab's rows (empty shelves are left out). */
    val youShelves: List<YouShelf> = emptyList(),
    /** The kid's channel sort in force (CHANNEL_ORDER_*, parent's default until they pick). */
    val channelSort: String = CHANNEL_ORDER_WATCHED,
    /** The kid's order for the home feed (VIDEO_FILTER_*). */
    val homeFilter: String = VIDEO_FILTER_NEW,
    /** The kid's order for channel pages (VIDEO_FILTER_*), the parent's layout as default. */
    val channelFilter: String = VIDEO_FILTER_NEW,
    /** The parent's page size: videos a grid shows before "Show more"; null = all. */
    val pageSize: Int? = null,
    /** The parent's "show when a video came out" switch. */
    val showVideoAge: Boolean = false,
    /** The kid's last few searches, newest first — chips on the search page. */
    val recentSearches: List<String> = emptyList(),
    /** Source ids with uploads the kid hasn't seen yet. */
    val newBadges: Set<String> = emptySet(),
    /**
     * Source id → what its row on the Channels tab says beyond the name: how
     * many videos have landed since the kid last looked, and the newest one,
     * which is what the row's round play button plays and wears.
     *
     * Held in state rather than read at the call site because the answer is
     * that channel's cache file — a fifty-channel list must not do fifty disk
     * reads during composition.
     */
    val channelPreviews: Map<String, ChannelPreview> = emptyMap(),
    /** Video URLs the kid has hearted (drives the hold-menu row label). */
    val watchlisted: Set<String> = emptySet(),
    /** Video URLs saved for another day (drives the hold-menu row label and the Watch later tile). */
    val watchLater: Set<String> = emptySet(),
    /** Video URLs lined up to play next (drives the hold-menu row label and the Up next tile). */
    val queued: Set<String> = emptySet(),
    /** Video URLs requested/approved/fetching — not yet playable offline (⏳). */
    val downloadPending: Set<String> = emptySet(),
    /** Video URLs fully on disk, playable without a network (✅). */
    val downloaded: Set<String> = emptySet(),
    /** Live-screening progress on the search screen; null when nothing is in flight. */
    val searchScreening: SearchScreening? = null,
    /** Transient kid-facing pill (e.g. a save request the deep check refused);
     *  cleared by the ViewModel after a few seconds. */
    val notice: String? = null,
    /**
     * Every rule that could stop playback, at normal drain, each with the
     * clock it counts on — the *authoritative* read behind the header's time
     * pill. Empty when no rule applies, and then there is no pill at all.
     *
     * Not a single number, because the chrome shows this value continuously
     * and re-reading [io.yosemitekids.app.data.SessionGuard] every second
     * would write to disk every second. `rememberTimeLeftMs` ages these
     * between reads instead, which needs to know which of them ticks on the
     * wall clock — see [io.yosemitekids.app.data.LimitKind].
     */
    val remaining: List<Remaining> = emptyList(),
    /** What would stop a play press right now (bedtime, break, budget), or null. */
    val blockReason: String? = null,
    /**
     * The blocked windows the parent set, straight from the store this device
     * enforces from — the row of pills on the You page names them.
     */
    val timeWindows: List<TimeWindow> = emptyList(),
    /** Minutes watched today on this device, and today's allowance (null = no limit). */
    val watchedTodayMin: Int = 0,
    val budgetTodayMin: Int? = null,
    /**
     * No network right now. Live: a callback in `MainActivity` sets it on
     * every connectivity change, so the You page's offline banner appears and
     * disappears with the Wi-Fi rather than reporting how things stood at launch.
     */
    val offline: Boolean = false,
    /** Downloaded video URL → bytes on disk, for the "channel · 142 MB" line. */
    val downloadSizes: Map<String, Long> = emptyMap(),
    /** The download in flight right now: video URL and its fraction (0..1). */
    val downloadProgress: Pair<String, Float>? = null,
    /**
     * The family has channels but every one is hidden from this kid right
     * now (held by screening, or all their videos blocked). Distinct from an
     * empty whitelist: the fix is on the parent's phone, not in adding channels.
     */
    val allHeld: Boolean = false,
    /**
     * This channel's finished videos, held out of [videos] so the unwatched
     * ones are the whole grid. Feeds the "Watched" tile and the screen behind
     * it; empty on every other screen.
     */
    val channelWatched: List<VideoItem> = emptyList(),
    /**
     * Where the channel page lands when the kid comes back out of its Watched
     * shelf, and — being null until the first page has published — the flag
     * that says whether that page has landed at all.
     *
     * It used to place a "Watched" TILE in the grid; the shelf is now a pill
     * in the Videos header, where the design puts it, so the number is only
     * ever 0 or null. Left as a position because the paging pump still asks
     * "has the first set landed" through it.
     */
    val watchedTileAt: Int? = null,
    /**
     * A one-shot instruction to put the grid at this item. The channel and its
     * Watched shelf share one grid, so without it the shelf would open at
     * whatever depth the channel was scrolled to. Cleared once obeyed.
     */
    val scrollTo: Int? = null
)

/**
 * Unwatched first, finished last — the split behind the channel grid.
 *
 * [wasFinished] is deliberately a snapshot taken when the channel opened, not
 * a live lookup: a video the kid finishes and comes straight back from must
 * stay under their thumb, dimmed where they left it, and move on the next
 * visit. Half-watched videos count as unwatched — they're something to carry
 * on with, which is what the home screen's "Keep watching" row assumes too.
 */
internal fun splitWatched(
    items: List<VideoItem>,
    wasFinished: (VideoItem) -> Boolean
): Pair<List<VideoItem>, List<VideoItem>> =
    items.partition { !wasFinished(it) }

/**
 * The feed's shuffle-free mix: one from each list in turn (list order =
 * most-watched channel first), then the seconds, and so on, deduped by key
 * and capped. A kid sees every channel on the first screen instead of one
 * channel's whole page — pure, so the ViewModel's feed is unit-testable.
 */
internal fun <T> interleave(lists: List<List<T>>, max: Int, key: (T) -> Any): List<T> {
    val out = ArrayList<T>()
    val seen = HashSet<Any>()
    var depth = 0
    while (out.size < max && lists.any { it.size > depth }) {
        for (list in lists) {
            if (out.size >= max) break
            list.getOrNull(depth)?.let { if (seen.add(key(it))) out += it }
        }
        depth++
    }
    return out
}

/**
 * The History shelf: every video with a watch timestamp, newest first, joined
 * to whatever metadata the caches hold (first match wins — the same video
 * can sit in two sources' caches). Anything the caches have since forgotten
 * drops out: without a title or poster there is nothing to show. Pure, so
 * the fifty-channel case is a unit test rather than a surprise.
 */
internal fun historyItems(
    history: Map<String, WatchProgress>,
    known: List<Video>,
    limit: Int
): List<VideoItem> {
    val byUrl = HashMap<String, Video>(known.size)
    for (v in known) byUrl.putIfAbsent(v.url, v)
    return history.entries.asSequence()
        .filter { (url, p) -> p.lastWatchedAt > 0 && url in byUrl }
        .sortedByDescending { it.value.lastWatchedAt }
        .take(limit)
        .map { (url, p) -> VideoItem(byUrl.getValue(url), p.fraction) }
        .toList()
}

/** "Popular first": by YouTube view count, unknown counts last, ties keep upload order. */
internal fun orderByPopularity(items: List<VideoItem>): List<VideoItem> =
    items.sortedByDescending { it.video.viewCount ?: -1L }

/** Watched videos newest-watched first, for the History tile and shelf. */
internal fun orderByWatched(items: List<VideoItem>, watchedAt: (String) -> Long): List<VideoItem> =
    items.sortedByDescending { watchedAt(it.video.url) }

/**
 * The channel row / Channels tab in the order the kid (or, by default, the
 * parent) asked for. Most watched = most opened here; A to Z; a shuffle that
 * holds still for the whole visit ([seed] — a row that reorders under the
 * kid's thumb is a bug, not a surprise); latest video = the channel whose
 * newest upload is newest, channels with no dated upload last. Every sort is
 * stable, so ties keep the whitelist order.
 */
internal fun orderChannels(
    channels: List<Source>,
    sort: String,
    opens: (String) -> Int,
    latestUpload: (String) -> Long?,
    seed: Long
): List<Source> = when (sort) {
    CHANNEL_ORDER_ALPHA -> channels.sortedBy { it.name.lowercase() }
    CHANNEL_ORDER_RANDOM -> channels.shuffled(kotlin.random.Random(seed))
    CHANNEL_ORDER_LATEST -> channels.sortedByDescending { latestUpload(it.id) ?: Long.MIN_VALUE }
    else -> channels.sortedByDescending { opens(it.id) }
}

/**
 * A video list in the order the kid's chip asks for: newest keeps the list's
 * own order (feeds arrive newest-first), random is a seeded shuffle that
 * holds until the next refresh, popular is [orderByPopularity].
 */
internal fun filterVideos(items: List<VideoItem>, filter: String?, seed: Long): List<VideoItem> =
    when (filter) {
        VIDEO_FILTER_RANDOM -> items.shuffled(kotlin.random.Random(seed))
        VIDEO_FILTER_POPULAR -> orderByPopularity(items)
        else -> items
    }

/** The parent's channel page layout as the kid's default filter (the playlist layout keeps newest). */
internal fun defaultFilterFor(channelLayout: String): String =
    if (channelLayout == CHANNEL_LAYOUT_POPULAR) VIDEO_FILTER_POPULAR else VIDEO_FILTER_NEW

/**
 * Search hits handed to the AI screener in the current window. [done]/[total]
 * drives the progress bar; results append to the grid as verdicts land.
 * [beyondWindow] = matches past the screened window, screened as the kid scrolls.
 */
data class SearchScreening(val total: Int, val done: Int, val beyondWindow: Int)

// --- Suggestions ------------------------------------------------------------

/**
 * Words that carry no signal about what a video is *about*. Kept small and
 * hand-picked rather than a real stoplist: the titles here are kids' YouTube,
 * where "for kids", "episode" and "full" appear on everything and would
 * otherwise dominate every overlap score.
 */
private val SUGGEST_STOPWORDS = setOf(
    "the", "and", "for", "you", "your", "with", "from", "this", "that", "what",
    "how", "why", "who", "all", "new", "not", "but", "can", "his", "her", "its",
    "our", "out", "one", "two", "get", "got", "are", "was", "were", "has", "had",
    "kids", "kid", "children", "child", "video", "videos", "episode", "episodes",
    "full", "part", "official", "watch", "more", "best", "top", "compilation",
    "season", "vs", "ft", "feat", "live", "let", "lets", "make", "made", "day"
)

/** Anything that isn't a letter or a digit splits words — Unicode-aware, so
 *  a title in any script the family watches still tokenizes. */
private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

/**
 * Title words worth matching on: letters and digits only, lowercased, three
 * characters or more, stopwords dropped. A set, so a title that says "dinosaur"
 * four times counts once.
 */
internal fun titleKeywords(title: String): Set<String> =
    title.lowercase()
        .split(NON_WORD)
        .filter { it.length >= 3 && it !in SUGGEST_STOPWORDS }
        .toSet()

/**
 * "More like what you watch": unwatched videos scored by how much their titles
 * overlap the titles the kid actually watched, most recent watches weighted
 * highest, plus a nudge for channels they come back to.
 *
 * Deliberately local and explainable — no model, no network, no view counts.
 * Everything it knows comes from this kid's own history on this device, which
 * is also why it is pure: the interesting cases (a kid with one watch, a kid
 * whose whole history is one channel) are unit tests rather than a guess.
 *
 * [watchedTitles] must be newest-first. [perChannelCap] stops a single
 * prolific channel from owning the row — the point is to widen what they see,
 * not to rebuild the channel page they already have.
 */
internal fun suggestionsFor(
    watchedTitles: List<String>,
    candidates: List<VideoItem>,
    channelAffinity: Map<String, Int>,
    limit: Int,
    perChannelCap: Int = 2
): List<VideoItem> {
    if (watchedTitles.isEmpty() || candidates.isEmpty()) return emptyList()

    // Recency weight: the last thing they watched says more about what they
    // want next than the fiftieth thing back, but old watches still count.
    val watchedSets = watchedTitles.take(SUGGEST_HISTORY_DEPTH)
        .mapIndexed { i, t -> titleKeywords(t) to 1.0 / (1.0 + i * 0.2) }
        .filter { it.first.isNotEmpty() }
    if (watchedSets.isEmpty()) return emptyList()

    val maxAffinity = channelAffinity.values.maxOrNull()?.takeIf { it > 0 } ?: 1

    val scored = candidates.mapNotNull { item ->
        val words = titleKeywords(item.video.title)
        if (words.isEmpty()) return@mapNotNull null
        var score = 0.0
        for ((watched, weight) in watchedSets) {
            val shared = words.count { it in watched }
            if (shared > 0) score += shared * weight
        }
        if (score <= 0.0) return@mapNotNull null
        // A channel they return to is worth a thumb on the scale, but never
        // enough to promote an unrelated video over a genuine title match.
        val affinity = (channelAffinity[item.video.channelName] ?: 0).toDouble() / maxAffinity
        item to score + affinity * 0.5
    }.sortedByDescending { it.second }

    val perChannel = HashMap<String, Int>()
    val out = ArrayList<VideoItem>(limit)
    for ((item, _) in scored) {
        if (out.size >= limit) break
        val seen = perChannel.getOrDefault(item.video.channelName, 0)
        if (seen >= perChannelCap) continue
        perChannel[item.video.channelName] = seen + 1
        out += item
    }
    return out
}

/** How far back suggestions look. Beyond this the recency weight is noise anyway. */
internal const val SUGGEST_HISTORY_DEPTH = 40
