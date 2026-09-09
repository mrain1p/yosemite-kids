package io.yosemitekids.hub

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.KidHome
import io.yosemitekids.app.data.SearchRank
import io.yosemitekids.app.data.Video
import io.yosemitekids.app.ui.HOME_SHELVES
import io.yosemitekids.app.ui.HomeShelf
import io.yosemitekids.app.ui.KID_DARK
import io.yosemitekids.app.ui.KidSurface
import io.yosemitekids.app.ui.PinnableSource
import io.yosemitekids.app.ui.homeSections
import io.yosemitekids.app.ui.kidTinted
import io.yosemitekids.app.ui.kidTokenRoles
import io.yosemitekids.app.ui.resolvePins
import org.json.JSONArray
import org.json.JSONObject

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
    private val history: HubKidHistory
) {

    /** A whitelist entry in the shape [resolvePins] joins against. */
    private data class PinnableEntry(override val id: String) : PinnableSource

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

        // Every video this kid may see, once, newest-first within a channel —
        // the list every shelf below is a view of.
        val everything = catalogue.flatMap { it.videos }
        val videos = everything.map { it.toVideo() }

        val out = JSONObject()
        out.put("kid", kidJson(kidId))
        out.put("theme", themeJson(kidId))
        out.put("time", timeJson(kidId, viewer))
        out.put("sections", sectionsJson())

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
            videosJson(KidHome.keepWatching(videos, { watched[it] }) { v, f -> v to f }, watched)
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
                watched
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
                watched
            )
        )

        out.put(
            "history",
            videosJson(
                KidHome.history(watched, videos, KidHome.HISTORY_ROW_MAX) { v, f -> v to f },
                watched
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
        val videos = policy.catalogueFor(kidId).flatMap { it.videos }.map { it.toVideo() }

        val shelves = JSONArray()
        for (id in KidSurface.YOU_SHELVES) {
            val surface = KidSurface.surface(id)
            // Only History has anything behind it on this box today; the three
            // saved lists are declared, empty, and say so. When their store
            // lands they fill in here and nothing about the page changes.
            val rows = when (id) {
                "history" -> KidHome.history(
                    watched, videos, KidSurface.YOU_PAGE_MAX.value
                ) { v, f -> v to f }
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
                    .put("preview", videosJson(rows.take(KidSurface.ROW_PREVIEW.value), watched))
                    .put("videos", videosJson(rows, watched))
            )
        }

        return JSONObject()
            .put("kid", kidJson(kidId))
            .put("theme", themeJson(kidId))
            .put("time", timeJson(kidId, viewer))
            .put("shelves", shelves)
    }

    /** One channel's page: its name, what the parent let through of its description, its videos. */
    fun channel(kidId: String, sourceId: String): JSONObject? {
        val source = policy.catalogueFor(kidId).firstOrNull { it.entry.id == sourceId } ?: return null
        val watched = history.pointsFor(kidId)
        return JSONObject()
            .put("id", source.entry.id)
            .put("name", nameOf(source))
            .put("count", source.videos.size)
            .put(
                "videos",
                videosJson(source.videos.map { it.toVideo() }.map { it to (watched[it.url]?.fraction ?: 0f) }, watched)
            )
    }

    /**
     * Search, ranked by [SearchRank] — the same order the phone's search page
     * puts things in, for the reason that page was reordered: a child looking
     * for Mario should not have to scroll past seventy videos of everything
     * else to find one.
     */
    fun search(kidId: String, query: String): JSONObject {
        val terms = SearchRank.terms(query)
        val catalogue = policy.catalogueFor(kidId)
        val watched = history.pointsFor(kidId)
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
        return JSONObject()
            .put("query", query)
            .put("videos", videosJson(ranked.map { it to (watched[it.url]?.fraction ?: 0f) }, watched))
    }

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
    private fun videosJson(items: List<Pair<Video, Float>>, watched: Map<String, KidHome.WatchPoint>): JSONArray {
        val arr = JSONArray()
        for ((video, progress) in items) {
            val point = watched[video.url]
            arr.put(
                JSONObject()
                    .put("id", video.videoId.orEmpty())
                    .put("title", video.title)
                    .put("channel", video.channelName)
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
        return JSONObject()
            .put("budgetMinutes", budget)
            .put("spentMinutes", spent)
            .put("leftMinutes", (budget - spent).coerceAtLeast(0))
            .put("allowed", verdict.allowed)
            .put("reason", verdict.reason)
    }

    /**
     * The shelves, in order, with their enabled flags.
     *
     * A parent's saved order is not a config field yet — the home-screen editor
     * is on the roadmap — so this asks [homeSections] with nothing saved, which
     * is precisely what the app does today. When the field lands, this line
     * reads it and the browser gets the editor for free, because the ordering
     * rule was never written here.
     */
    private fun sectionsJson(): JSONArray {
        val arr = JSONArray()
        for (section in homeSections(emptyList())) {
            arr.put(
                JSONObject()
                    .put("id", section.id)
                    .put("enabled", section.enabled)
                    .put("title", titleOf(section.id))
            )
        }
        return arr
    }

    private fun titleOf(id: String): String = when (id) {
        HomeShelf.PINNED -> ""
        HomeShelf.CHANNELS -> "Channels"
        HomeShelf.KEEP_WATCHING -> "Keep watching"
        HomeShelf.SUGGESTED -> "More like what you watch"
        HomeShelf.VIDEOS -> "Videos"
        HomeShelf.HISTORY -> "Watched lately"
        // A shelf this build's catalogue knows and this page has no words for.
        // Not an error: HOME_SHELVES is the list, and a face that cannot title
        // one draws it untitled rather than dropping it.
        else -> ""
    }

    internal companion object {
        /** Every shelf id the page must be able to draw, for the guard's benefit. */
        val SHELVES = HOME_SHELVES
    }
}
