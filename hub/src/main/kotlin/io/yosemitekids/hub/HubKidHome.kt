package io.yosemitekids.hub

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.KidHome
import io.yosemitekids.app.data.CHANNEL_ORDER_ALPHA
import io.yosemitekids.app.data.CHANNEL_ORDER_ALPHA_DESC
import io.yosemitekids.app.data.CHANNEL_ORDER_RANDOM
import io.yosemitekids.app.data.SearchRank
import io.yosemitekids.app.data.Source
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Video
import io.yosemitekids.app.ui.HOME_SHELVES
import io.yosemitekids.app.ui.HomeShelf
import io.yosemitekids.app.ui.KID_DARK
import io.yosemitekids.app.ui.KidSurface
import io.yosemitekids.app.ui.PinnableSource
import io.yosemitekids.app.ui.VideoItem
import io.yosemitekids.app.ui.defaultFilterFor
import io.yosemitekids.app.ui.filterVideos
import io.yosemitekids.app.ui.homeSections
import io.yosemitekids.app.ui.homeShelfTitle
import io.yosemitekids.app.ui.orderChannels
import io.yosemitekids.app.ui.surpriseMix
import io.yosemitekids.app.ui.kidTinted
import io.yosemitekids.app.ui.kidTokenRoles
import io.yosemitekids.app.ui.resolvePins
import org.json.JSONArray
import org.json.JSONObject

/** The channel orders this box can produce: no open counts, no upload dates, so these three. */
/** Playlists on a channel page before "See all"; the page for all of them has no cap. */
private const val STRIP_MAX = 12

private val WEB_SORTS = setOf(
    CHANNEL_ORDER_ALPHA, CHANNEL_ORDER_ALPHA_DESC, CHANNEL_ORDER_RANDOM,
    io.yosemitekids.app.data.CHANNEL_ORDER_LATEST
)

/**
 * What a child's browser is shown — assembled here, decided everywhere else.
 *
 * ### The anti-drift rule this file exists to keep
 *
 * Not one rule about *what goes on the home screen* is written here. The shelf
 * order is [homeSections]; the shelves' contents are [KidHome]; the hero is
 * [resolvePins]; search order is [SearchRank]; what a kid may see at all is
 * [HubPolicy.catalogueFor]; the colours are [kidTinted]. Every one of those is
 * the identical function the Android app calls, in `:core` or `:crawl`.
 *
 * What is left — and it is all this class is — is **shape**: turning those
 * answers into JSON a page can draw. There are `filter`s below, and each one
 * is a *join* rather than a rule — "the rows of the channel that was asked
 * for", "the ones not already watched, for the suggester to score". A `filter`
 * here that decided what a child may see, or a `sortedBy` that decided an
 * order, would be the browser drifting from the television by exactly that
 * much; that belongs in `:core` or `:crawl` with the others.
 *
 * The page below this is held to the stricter version of the same rule, and
 * that one *is* greppable: guard 61 fails if `kid.html` grows a filter, a sort
 * or a cap of its own, or a colour.
 *
 * ### Why the payload is fat and the page is thin
 *
 * One request paints the whole home. The alternative — a call per shelf — is
 * six round trips on a tablet over house wifi before a five-year-old sees
 * anything, and six chances for one of them to fail into a half-drawn page.
 * The lists are capped by the shared rules ([KidHome.FEED_MAX] and friends), so
 * "fat" is bounded at about a hundred rows whatever the family's catalogue
 * looks like.
 */
class HubKidHome(
    private val policy: HubPolicy,
    private val store: HubStore,
    private val history: HubKidHistory,
    private val lists: HubSavedLists,
    private val searches: HubKidSearches? = null,
    private val now: () -> Long = System::currentTimeMillis
) {

    /**
     * One video as the family's own index holds it, **if this child may see
     * it** — null otherwise.
     *
     * The gate `/list` writes behind. It walks the same catalogue
     * `HubPolicy.mayPlay` does, so "may be saved" and "may be played" answer
     * the same question: a blocked video, a sibling's channel, or a video this
     * hub has never indexed is refused either way. The row comes from the
     * index rather than from the request, which is what stops a saved shelf
     * becoming a place to write text a child then reads.
     */
    fun rowFor(kidId: String, videoId: String): Video? =
        policy.catalogueFor(kidId)
            .asSequence()
            .flatMap { it.videos.asSequence() }
            .firstOrNull { it.videoId == videoId }
            ?.toVideo()

    /**
     * The head of this kid's Up next, as a row the page can play — or null.
     *
     * Intersected with the catalogue like every other shelf: a video queued
     * last night and blocked by a parent this morning is not what plays next.
     * The queue is in **play order**, so "the head" is genuinely the next one
     * rather than the newest.
     */
    fun nextInQueue(kidId: String): JSONObject? {
        val maySee = policy.catalogueFor(kidId).flatMap { it.videos }.map { it.toVideo() }
            .associateBy { it.url }
        val head = lists.videos(kidId, HubSavedLists.Which.QUEUE)
            .firstOrNull { it.url in maySee } ?: return null
        val watched = history.pointsFor(kidId)
        return videosJson(listOf(head to (watched[head.url]?.fraction ?: 0f)), watched, savedFor(kidId))
            .optJSONObject(0)
    }

    /** A whitelist entry in the shape [resolvePins] joins against. */
    private data class PinnableEntry(override val id: String) : PinnableSource

    /**
     * Which of a kid's lists hold which urls — read **once** per payload.
     *
     * A home screen is about a hundred cards and three lists. Asking the store
     * per card would be three hundred file reads to answer a question three
     * reads can: on a NAS that is the difference between a page that paints
     * and a page that thinks about it.
     */
    private data class Saved(
        val favorites: Set<String>,
        val watchLater: Set<String>,
        val queue: Set<String>
    ) {
        companion object {
            /** For payloads that draw no hold menu, so no card claims membership. */
            val NONE = Saved(emptySet(), emptySet(), emptySet())
        }
    }

    private fun savedFor(kidId: String) = Saved(
        favorites = lists.urls(kidId, HubSavedLists.Which.FAVORITES),
        watchLater = lists.urls(kidId, HubSavedLists.Which.WATCH_LATER),
        queue = lists.urls(kidId, HubSavedLists.Which.QUEUE)
    )

    /**
     * The whole home screen for one kid.
     *
     * [viewer] is the ledger id the caller derived from its own credential —
     * never from a request — and reaches [HubPolicy.timeFor] for the countdown.
     */
    fun home(kidId: String, viewer: String?): JSONObject {
        val config = runCatching { store.load() }.getOrNull()
        val catalogue = policy.catalogueFor(kidId)
        val watched = history.pointsFor(kidId)
        val saved = savedFor(kidId)

        // Every video this kid may see, once, newest-first within a channel —
        // the list every shelf below is a view of.
        val everything = catalogue.flatMap { it.videos }
        val videos = everything.map { it.toVideo() }

        val out = JSONObject()
        out.put("kid", kidJson(kidId))
        out.put("theme", themeJson(kidId))
        out.put("time", timeJson(kidId, viewer))
        out.put("sections", sectionsJson(kidId))

        // --- the hero ---------------------------------------------------
        val pinnedIds = config?.pinsFor(kidId)?.map { it.sourceId }.orEmpty()
        val visible = catalogue.map { PinnableEntry(it.entry.id) }
        val byId = catalogue.associateBy { it.entry.id }
        val pins = JSONArray()
        for (item in resolvePins(
            pinned = pinnedIds,
            visible = visible,
            // Nothing here knows what the kid has already seen of a channel —
            // the "new since you looked" badge is a per-device fact and the
            // browser is a device with no such memory yet. Passing the count it
            // does have keeps the line honest ("12 videos") rather than
            // inventing a freshness it cannot know.
            videoCount = { byId[it.id]?.videos?.size ?: 0 }
        )) {
            val source = byId[item.source.id] ?: continue
            pins.put(
                JSONObject()
                    .put("id", source.entry.id)
                    .put("name", nameOf(source))
                    .put("meta", item.meta)
                    .put("thumb", source.videos.firstOrNull()?.thumbnailUrl.orEmpty())
            )
        }
        out.put("pinned", pins)

        // --- the channels rail ------------------------------------------
        val channels = JSONArray()
        for (source in catalogue) {
            channels.put(
                JSONObject()
                    .put("id", source.entry.id)
                    .put("name", nameOf(source))
                    .put("count", source.videos.size)
                    // The hub indexes videos, not channel avatars, so a channel
                    // wears its newest video. That is also what the app's
                    // Channels row draws on its play button, so the two faces
                    // agree by accident of the same absence rather than by
                    // design — see the roadmap's note about indexing avatars.
                    .put("thumb", source.videos.firstOrNull()?.thumbnailUrl.orEmpty())
            )
        }
        out.put("channels", channels)

        // --- the shelves ------------------------------------------------
        out.put(
            "keepWatching",
            videosJson(KidHome.keepWatching(videos, { watched[it] }) { v, f -> v to f }, watched, saved)
        )

        out.put(
            "videos",
            videosJson(
                KidHome.interleave(
                    catalogue.map { it.videos.map { row -> row.toVideo() }.take(KidHome.FEED_PER_CHANNEL) },
                    KidHome.FEED_MAX
                ) { it.url }
                    // A finished video leaves the feed, exactly as it does on
                    // the phone; a half-watched one keeps its bar.
                    .mapNotNull { v ->
                        val point = watched[v.url]
                        if (point?.isFinished == true) null else v to (point?.fraction ?: 0f)
                    },
                watched,
                saved
            )
        )

        val recent = watched.entries.sortedByDescending { it.value.lastWatchedAt }
        val byUrl = videos.associateBy { it.url }
        out.put(
            "suggested",
            videosJson(
                KidHome.suggestions(
                    watchedTitles = recent.mapNotNull { byUrl[it.key]?.title },
                    candidates = videos.filter { it.url !in watched }.distinctBy { it.url },
                    video = { it },
                    channelAffinity = recent.mapNotNull { byUrl[it.key]?.channelName }
                        .groupingBy { it }.eachCount(),
                    limit = KidHome.SUGGEST_ROW_MAX
                ).map { it to 0f },
                watched,
                saved
            )
        )

        out.put(
            "history",
            videosJson(
                KidHome.history(watched, videos, KidHome.HISTORY_ROW_MAX) { v, f -> v to f },
                watched,
                saved
            )
        )
        return out
    }

    /**
     * The You tab: this kid's own shelves, in `:core`'s order, each already
     * capped and already filtered to what they may see.
     *
     * **Every shelf is declared even when it is empty**, and that is the app's
     * shape rather than an oversight: the page has one form, and an empty row
     * says what would fill it. A shelf that appeared only once it had something
     * in it would leave a child with no way to learn the gesture that fills it.
     *
     * The words come from [KidSurface], so this hub cannot describe a shelf
     * differently from the phone — which the two Compose screens managed to do
     * to "Watch later" before the manifest existed.
     */
    fun you(kidId: String, viewer: String?): JSONObject {
        val watched = history.pointsFor(kidId)
        val saved = savedFor(kidId)
        val videos = policy.catalogueFor(kidId).flatMap { it.videos }.map { it.toVideo() }

        val shelves = JSONArray()
        for (id in KidSurface.YOU_SHELVES) {
            val surface = KidSurface.surface(id)
            // Each shelf from its own store, and every one of them intersected
            // with what this kid may see RIGHT NOW. A video hearted last month
            // and blocked by a parent yesterday must not still be on her
            // Favorites shelf: the catalogue is re-consulted on every read, so
            // a block takes a shelf away the moment it is set rather than the
            // next time something is written.
            val maySee = videos.mapTo(HashSet()) { it.url }
            val rows = when (id) {
                "history" -> KidHome.history(
                    watched, videos, KidSurface.YOU_PAGE_MAX.value
                ) { v, f -> v to f }
                "favorites" -> savedRows(kidId, HubSavedLists.Which.FAVORITES, maySee, watched)
                "watch-later" -> savedRows(kidId, HubSavedLists.Which.WATCH_LATER, maySee, watched)
                "up-next" -> savedRows(kidId, HubSavedLists.Which.QUEUE, maySee, watched)
                else -> emptyList()
            }
            // Both lists, and the cap applied HERE. The page may not slice
            // (guard 61), so "the first twelve" has to arrive already decided —
            // which is also what keeps the browser's glance the same length as
            // the phone's rather than whatever a stylesheet happened to fit.
            shelves.put(
                JSONObject()
                    .put("id", surface.id)
                    .put("title", surface.title)
                    .put("icon", surface.icon)
                    .put("emptyText", surface.emptyText)
                    .put("count", rows.size)
                    .put("preview", videosJson(rows.take(KidSurface.ROW_PREVIEW.value), watched, saved))
                    .put("videos", videosJson(rows, watched, saved))
            )
        }

        return JSONObject()
            .put("kid", kidJson(kidId))
            .put("theme", themeJson(kidId))
            .put("time", timeJson(kidId, viewer))
            .put("shelves", shelves)
    }

    /**
     * One saved list, filtered to what this kid may see and capped by the
     * shared rule.
     *
     * The [maySee] intersection is the load-bearing line. A saved list is the
     * one surface whose contents a *child* chose, so it is the one that can
     * still be holding a video a parent has since blocked, a channel they have
     * since taken off her list, or a row screening has since pulled. Filtering
     * on read rather than on write means a parent's block empties the shelf
     * immediately, instead of the next time she happens to heart something.
     */
    private fun savedRows(
        kidId: String,
        which: HubSavedLists.Which,
        maySee: Set<String>,
        watched: Map<String, KidHome.WatchPoint>
    ): List<Pair<Video, Float>> =
        lists.videos(kidId, which)
            .filter { it.url in maySee }
            .take(KidSurface.YOU_PAGE_MAX.value)
            .map { it to (watched[it.url]?.fraction ?: 0f) }

    /**
     * Every channel this kid may see, in the order they asked for.
     *
     * The order is [orderChannels] from `:crawl` — the app's own function, so
     * "A to Z" means the same thing on a television and a tablet, and Random
     * is the *same* random when both are given the same seed.
     *
     * Two of the app's six sorts are honestly absent, and `KidSurface`
     * records why rather than drawing controls that do nothing: Most watched
     * needs per-channel open counts the hub does not keep, and Just added
     * needs the phone's first-seen ledger. Latest video arrived with 1.9.0,
     * when the index started keeping `publishedAt` (roadmap §2M) - read here
     * the way the phone reads its cache, the newest ten rows of each channel.
     */
    fun channels(kidId: String, sort: String?, seed: Long): JSONObject {
        val catalogue = policy.catalogueFor(kidId)
        // No sort asked for is the parent's "Channel row order" - the same
        // family default the phone and the television open on (honouredBy in
        // SettingsSurface). Where the parent chose an order this box cannot
        // produce - Most watched needs open counts - A to Z is the honest
        // fallback, and the reply says
        // which order it actually used so the chips agree with the grid.
        val familyOrder = runCatching { store.load().channelOrder }.getOrDefault("")
        val effective = sort?.takeIf { it.isNotBlank() }
            ?: familyOrder.takeIf { it in WEB_SORTS }
            ?: CHANNEL_ORDER_ALPHA
        val sources = catalogue.map { source ->
            Source(
                id = source.entry.id,
                url = source.entry.url,
                name = nameOf(source),
                avatarUrl = null,
                kind = source.entry.kind
            )
        }
        val byId = catalogue.associateBy { it.entry.id }
        val ordered = orderChannels(
            channels = sources,
            sort = effective,
            // Open counts are not kept on this box, so Most watched is not
            // offered here; zero makes it a stable no-op if asked for anyway.
            opens = { 0 },
            latestUpload = { id -> byId[id]?.videos?.take(10)?.mapNotNull { it.publishedAt }?.maxOrNull() },
            seed = seed
        )
        val arr = JSONArray()
        for (source in ordered) {
            val row = byId[source.id] ?: continue
            arr.put(
                JSONObject()
                    .put("id", source.id)
                    .put("name", source.name)
                    .put("count", row.videos.size)
                    .put("thumb", row.videos.firstOrNull()?.thumbnailUrl.orEmpty())
            )
        }
        return JSONObject()
            .put("sort", effective)
            .put("seed", seed)
            .put("channels", arr)
            .put("sorts", JSONArray().put(sortJson(CHANNEL_ORDER_ALPHA, "A to Z"))
                .put(sortJson(CHANNEL_ORDER_ALPHA_DESC, "Z to A"))
                .put(sortJson(CHANNEL_ORDER_RANDOM, "Random"))
                .put(sortJson(io.yosemitekids.app.data.CHANNEL_ORDER_LATEST, "Latest video")))
    }

    private fun sortJson(id: String, label: String) =
        JSONObject().put("id", id).put("label", label)

    /**
     * One page of a grid: the parent's "Videos before Show more" (`pageSize`)
     * applied here, because the page may cap nothing (guard 61). Null means
     * the family never set one and the whole list goes, as on the phone.
     * The reply's `from`, `more` and `total` are what the page draws the
     * button from; it asks for the next page with `from=` and appends.
     */
    private fun page(items: List<Pair<Video, Float>>, from: Int): Triple<List<Pair<Video, Float>>, Boolean, Int> {
        val size = runCatching { store.load().pageSize }.getOrNull()
        val start = from.coerceIn(0, items.size)
        val slice = if (size == null) items.drop(start) else items.drop(start).take(size)
        return Triple(slice, start + slice.size < items.size, items.size)
    }

    /**
     * A random mix across every channel this kid may see — [surpriseMix].
     *
     * Seeded, which is the whole reason that function exists: the phone called
     * a bare `shuffled()`, and a shuffle with no seed is the one ordering two
     * faces cannot agree on. The page sends the seed back on a reload so a
     * child's mix holds still for the sitting.
     */
    fun surprise(kidId: String, seed: Long): JSONObject {
        val watched = history.pointsFor(kidId)
        val saved = savedFor(kidId)
        val pool = policy.catalogueFor(kidId)
            .filter { it.entry.kind == SourceKind.CHANNEL }
            .flatMap { it.videos }
            .map { it.toVideo() }
        val mix = surpriseMix(pool, seed)
            // A finished video leaves a Surprise for the same reason it leaves
            // the feed: the point is something new.
            .filter { watched[it.url]?.isFinished != true }
        return JSONObject()
            .put("seed", seed)
            .put("videos", videosJson(mix.map { it to (watched[it.url]?.fraction ?: 0f) }, watched, saved))
    }

    /**
     * One channel's page: its name, what the parent let through of its
     * description, its videos — in the order the parent's "Channel page
     * layout" asks for, one `pageSize` at a time.
     *
     * The order is [defaultFilterFor] over [filterVideos], the same two
     * functions the phone's channel page opens with, so "Popular first" is
     * the television's order to the row: the index keeps the view count
     * since 1.9.0 (roadmap §2M). The page size is honoured the same way,
     * here and on search; guard 69 holds this file to reading what the
     * manifest says the browser honours.
     */
    fun channel(kidId: String, sourceId: String, from: Int = 0, onlyWatched: Boolean = false): JSONObject? {
        val catalogue = policy.catalogueFor(kidId)
        val source = catalogue.firstOrNull { it.entry.id == sourceId } ?: return null
        val watched = history.pointsFor(kidId)
        val saved = savedFor(kidId)
        val layout = runCatching { store.load().channelLayout }.getOrDefault("")
        // The strip: the channel's playlists the crawl has indexed, each with
        // how many of its videos THIS kid may see - the same rows the grid is
        // drawn from, so a chip never opens onto less than it promised.
        val playlists = playlistsFor(catalogue, sourceId)
        val items = source.videos.map { it.toVideo() }.map { VideoItem(it, watched[it.url]?.fraction) }
        // The channel's finished videos - the phone's Watched screen, its only
        // two-level one - newest-watched first through the phone's own
        // orderByWatched. "Finished" is the hub's verdict per row, never the
        // page's (guard 61).
        val finished = items.filter { watched[it.video.url]?.isFinished == true }
        // Seeded from the channel, so "random" would hold still for a sitting;
        // the two layouts a parent can pick never reach that branch.
        val ordered = (
            if (onlyWatched) io.yosemitekids.app.ui.orderByWatched(finished) { url -> watched[url]?.lastWatchedAt ?: 0L }
            else filterVideos(items, defaultFilterFor(layout), sourceId.hashCode().toLong())
        ).map { it.video to (it.progress ?: 0f) }
        val (slice, more, total) = page(ordered, from)
        return JSONObject()
            .put("id", source.entry.id)
            .put("name", nameOf(source))
            .put("count", total)
            .put("from", from.coerceIn(0, total))
            .put("more", more)
            .put("watched", onlyWatched)
            .put("watchedCount", finished.size)
            .put("playlistCount", playlists.size)
            .put("playlists", playlistsJson(playlists.take(STRIP_MAX)))
            .put("videos", videosJson(slice, watched, saved))
    }

    /** A playlist with the rows of it this kid may see, in playlist order. */
    private class VisiblePlaylist(val playlist: ChannelIndex.IndexedPlaylist, val rows: List<ChannelIndex.IndexedVideo>)

    /**
     * The channel's indexed playlists that hold at least one video this kid
     * may see, in the channel's own order. Videos are matched across the
     * whole catalogue, not just this channel: a playlist may hold a sibling
     * channel's video, and the rule is the same as everywhere else - if it is
     * on a shelf the kid can browse, it is in the playlist too.
     */
    private fun playlistsFor(catalogue: List<HubPolicy.VisibleSource>, sourceId: String): List<VisiblePlaylist> {
        val listing = policy.index.loadPlaylists(sourceId) ?: return emptyList()
        val rowsById = catalogue.flatMap { it.videos }.associateBy { it.videoId }
        return listing.playlists.mapNotNull { p ->
            val rows = p.videoIds?.mapNotNull { rowsById[it] } ?: return@mapNotNull null
            if (rows.isEmpty()) null else VisiblePlaylist(p, rows)
        }
    }

    private fun playlistsJson(playlists: List<VisiblePlaylist>): JSONArray {
        val arr = JSONArray()
        playlists.forEach { v ->
            arr.put(
                JSONObject()
                    .put("id", v.playlist.id)
                    .put("name", v.playlist.name)
                    .put("thumb", v.playlist.thumbnailUrl ?: v.rows.first().thumbnailUrl.orEmpty())
                    .put("count", v.rows.size)
            )
        }
        return arr
    }

    /** Every playlist of one channel, for the See-all page; null when the channel is not this kid's. */
    fun playlists(kidId: String, sourceId: String): JSONObject? {
        val catalogue = policy.catalogueFor(kidId)
        val source = catalogue.firstOrNull { it.entry.id == sourceId } ?: return null
        val playlists = playlistsFor(catalogue, sourceId)
        return JSONObject()
            .put("id", source.entry.id)
            .put("name", nameOf(source))
            .put("count", playlists.size)
            .put("playlists", playlistsJson(playlists))
    }

    /**
     * One playlist as a page: its videos this kid may see, in playlist order,
     * paged like a channel. Found by walking the kid's own channels, so a
     * playlist of a channel they may not see is the same 404 as one that does
     * not exist - the rule every kid route keeps.
     */
    fun playlist(kidId: String, playlistId: String, from: Int = 0): JSONObject? {
        val catalogue = policy.catalogueFor(kidId)
        val (source, found) = catalogue.firstNotNullOfOrNull { src ->
            playlistsFor(catalogue, src.entry.id).firstOrNull { it.playlist.id == playlistId }?.let { src to it }
        } ?: return null
        val watched = history.pointsFor(kidId)
        val saved = savedFor(kidId)
        val ordered = found.rows.map { it.toVideo() }.map { it to (watched[it.url]?.fraction ?: 0f) }
        val (slice, more, total) = page(ordered, from)
        return JSONObject()
            .put("id", found.playlist.id)
            .put("name", found.playlist.name)
            .put("channel", nameOf(source))
            .put("channelId", source.entry.id)
            .put("count", total)
            .put("from", from.coerceIn(0, total))
            .put("more", more)
            .put("videos", videosJson(slice, watched, saved))
    }

    /**
     * Search, ranked by [SearchRank] — the same order the phone's search page
     * puts things in, for the reason that page was reordered: a child looking
     * for Mario should not have to scroll past seventy videos of everything
     * else to find one.
     */
    fun search(
        kidId: String,
        query: String,
        from: Int = 0,
        order: String = io.yosemitekids.app.data.SearchOrder.BEST,
        remember: Boolean = false
    ): JSONObject {
        val terms = SearchRank.terms(query)
        // Remembered only when the page says the child meant it (Enter, a chip),
        // never on the keystrokes a search-as-you-type page sends.
        if (remember && terms.isNotEmpty() && from == 0) searches?.add(kidId, query)
        val catalogue = policy.catalogueFor(kidId)
        val watched = history.pointsFor(kidId)
        val saved = savedFor(kidId)
        val videos = catalogue.flatMap { it.videos }.map { it.toVideo() }.distinctBy { it.url }
        val hits = if (terms.isEmpty()) emptyList() else videos.filter { v ->
            val hay = (v.title + " " + v.channelName).lowercase()
            terms.all { it in hay }
        }
        val signals = SearchRank.Signals(
            channelAffinity = watched.entries.sortedByDescending { it.value.lastWatchedAt }
                .mapNotNull { e -> videos.firstOrNull { it.url == e.key }?.channelName }
                .groupingBy { it }.eachCount(),
            watched = watched.keys
        )
        val ranked = SearchRank.rank(hits, terms, query, signals) {
            SearchRank.Key(it.title, it.channelName, it.url)
        }
        // The kid's chip, through the phone's own SearchOrder. Seeded from the
        // query so "Mix it up" holds still from one page to the next.
        val effective = order.takeIf { it in io.yosemitekids.app.data.SearchOrder.ALL }
            ?: io.yosemitekids.app.data.SearchOrder.BEST
        val arranged = io.yosemitekids.app.data.SearchOrder.order(
            ranked, effective, query.lowercase().hashCode().toLong(), publishedAt = { it.publishedAt }
        ) { it.durationSeconds }
        // Paged like a channel: the same "Videos before Show more" the phone's
        // grid honours on its search results.
        val (slice, more, total) = page(arranged.map { it to (watched[it.url]?.fraction ?: 0f) }, from)
        return JSONObject()
            .put("query", query)
            .put("count", total)
            .put("from", from.coerceIn(0, total))
            .put("more", more)
            .put("order", effective)
            .put("orders", JSONArray().also { arr ->
                io.yosemitekids.app.data.SearchOrder.ALL.forEach { arr.put(sortJson(it, io.yosemitekids.app.data.SearchOrder.label(it))) }
            })
            .put("recent", JSONArray(searches?.recent(kidId) ?: emptyList<String>()))
            .put("videos", videosJson(slice, watched, saved))
    }

    /** The × on a recent-search chip: one term gone, the list that is left. */
    fun forgetSearch(kidId: String, query: String): List<String> = searches?.remove(kidId, query) ?: emptyList()

    fun clearSearches(kidId: String): List<String> = searches?.clear(kidId) ?: emptyList()

    // --- shape ----------------------------------------------------------

    private fun nameOf(source: HubPolicy.VisibleSource): String =
        source.entry.label?.takeIf { it.isNotBlank() }
            ?: source.videos.firstOrNull()?.channelName
            ?: source.entry.url

    /**
     * One row on a shelf, as the page needs it.
     *
     * [finished] and [resumeMs] are carried rather than derived in the browser,
     * and that is the point: "past 98% counts as done" and "start from where
     * they were" are rules, and the page is not allowed to hold one
     * ([KidHome.FINISHED_FRACTION], guard 61). It used to spell 0.98 and 0.02
     * itself, which made it the eighth and ninth copy of a threshold.
     */
    private fun videosJson(
        items: List<Pair<Video, Float>>,
        watched: Map<String, KidHome.WatchPoint>,
        saved: Saved = Saved.NONE
    ): JSONArray {
        val arr = JSONArray()
        // "Channel · 3 days ago" is composed HERE, with the phone's own metaLine
        // and relativeAge from :core, so the page draws it verbatim (guard 61)
        // and the parent's switch is read once per payload, not once per card.
        val showAge = runCatching { store.load().showVideoAge }.getOrDefault(false)
        for ((video, progress) in items) {
            val point = watched[video.url]
            arr.put(
                JSONObject()
                    .put("id", video.videoId.orEmpty())
                    .put("title", video.title)
                    .put("channel", video.channelName)
                    .put(
                        "meta",
                        io.yosemitekids.app.ui.metaLine(
                            video.channelName,
                            if (showAge) io.yosemitekids.app.ui.relativeAge(video.publishedAt, now()) else null
                        )
                    )
                    .put("thumb", video.thumbnailUrl.orEmpty())
                    .put("seconds", video.durationSeconds)
                    .put("progress", progress)
                    .put("finished", point?.isFinished ?: false)
                    // Where to start. Zero for a video never watched, and zero
                    // for a finished one — starting a finished video four
                    // seconds from its end is the resume nobody wants.
                    .put(
                        "resumeMs",
                        if (point == null || point.isFinished) 0L
                        else (point.fraction.toDouble() * video.durationSeconds * 1000).toLong()
                    )
                    // Which of the kid's lists this row is already in, so the
                    // hold menu can offer "Remove from Favorites" rather than
                    // adding a second copy. Computed once per payload from
                    // three sets, not once per card from three file reads.
                    .put("fav", video.url in saved.favorites)
                    .put("later", video.url in saved.watchLater)
                    .put("queued", video.url in saved.queue)
            )
        }
        return arr
    }

    private fun kidJson(kidId: String): JSONObject {
        val profile = runCatching { store.load().profile(kidId) }.getOrNull()
        return JSONObject()
            .put("id", kidId)
            .put("name", profile?.name.orEmpty())
            .put("avatar", profile?.avatar.orEmpty())
    }

    /**
     * This kid's own colours, as CSS custom properties the page sets on `<html>`
     * over the generated stylesheet.
     *
     * The "My colour" theme tints the ground toward the colour the child picked,
     * and no build-time stylesheet can enumerate a runtime choice — `KidTokensCss`
     * says exactly this in its own KDoc, and names [kidTinted] as the way to do
     * it without a second table. This is that call. The four signal hues are then
     * re-derived for the tinted ground with [kidTokenRoles], because a coral that
     * carried text on the neutral dark does not necessarily carry it on a pink
     * one, and the whole point of that function is to move it until it does.
     */
    private fun themeJson(kidId: String): JSONObject {
        val profile = runCatching { store.load().profile(kidId) }.getOrNull()
            ?: return JSONObject()
        val tinted = kidTinted(KID_DARK, profile.colorArgb.toInt())
        val out = JSONObject()
        for ((role, argb) in tinted.roles()) out.put("--yk-$role", io.yosemitekids.app.ui.Argb.css(argb))
        for ((role, argb) in kidTokenRoles(tinted.background)) {
            out.put("--yk-$role", io.yosemitekids.app.ui.Argb.css(argb))
        }
        return out
    }

    /**
     * The countdown, or null when this kid has no daily budget.
     *
     * Straight from [HubPolicy.timeFor] — the same numbers the play route will
     * refuse on, so the page cannot promise minutes the hub will not honour.
     */
    private fun timeJson(kidId: String, viewer: String?): Any {
        val verdict = policy.timeFor(kidId, viewer)
        val budget = verdict.budgetMinutes ?: return JSONObject.NULL
        val spent = verdict.spentMinutes ?: 0
        val left = (budget - spent).coerceAtLeast(0)
        return JSONObject()
            .put("budgetMinutes", budget)
            .put("spentMinutes", spent)
            .put("leftMinutes", left)
            // The sentence, from :core, so the tablet and the television count
            // down in the same words. Minutes rather than seconds, and that is
            // honest rather than lazy: UsageLedger counts in whole minutes, so
            // a seconds countdown here would be this page inventing precision
            // the box does not have. Making it real means interpolating from
            // HubWatchMeter's accrual — roadmap, not a one-line fudge.
            .put("say", io.yosemitekids.app.ui.KidWords.timeLeft(left * 60L))
            .put("low", left * 60L <= io.yosemitekids.app.ui.KidWords.LOW_SECONDS)
            .put("allowed", verdict.allowed)
            .put("reason", verdict.reason)
    }

    /**
     * The shelves, in order, with their enabled flags: the parent's
     * arrangement of this kid's home (`Whitelist.homeRowsFor`, the same
     * call the phone makes) reconciled against this build's catalogue by
     * [homeSections] in `:core`. The ordering rule was never written here,
     * which is why the row editor reached the browser the day the field did.
     */
    private fun sectionsJson(kidId: String): JSONArray {
        val arr = JSONArray()
        val saved = runCatching { store.load().homeRowsFor(kidId) }.getOrDefault(homeSections(emptyList()))
        for (section in saved) {
            arr.put(
                JSONObject()
                    .put("id", section.id)
                    .put("enabled", section.enabled)
                    .put("title", titleOf(section.id))
            )
        }
        return arr
    }

    /** One spelling for every face: `homeShelfTitle` in `:core`, never words of this page's own. */
    private fun titleOf(id: String): String = homeShelfTitle(id)

    internal companion object {
        /** Every shelf id the page must be able to draw, for the guard's benefit. */
        val SHELVES = HOME_SHELVES
    }
}
