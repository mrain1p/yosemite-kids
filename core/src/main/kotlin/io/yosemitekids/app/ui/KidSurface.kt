package io.yosemitekids.app.ui

/**
 * Every kid-facing surface: which faces draw it, what decides its contents, and
 * where a child reaches it.
 *
 * **This is not documentation.** `scripts/check.ps1` and `scripts/check.sh` read
 * it and fail the build in both directions. Editing this file is therefore the
 * moment the "does the browser get this?" decision gets made, rather than
 * something that can be skipped — which is the whole argument
 * [io.yosemitekids.app.data.SettingsSurface] makes about the parent console,
 * one product surface along.
 *
 * ### The failure it was written for
 *
 * The web player shipped with four of the app's fourteen screens and nothing
 * complained. Not a test, not a guard, not a review: there was simply no list
 * of what a kid-facing product is made of, so "the browser has no History
 * screen" was indistinguishable from "the browser is finished". The owner found
 * it by asking, which is exactly how the `:core` Android-free rule was found
 * before guard 47 existed, and the same lesson applies — a rule stated only in
 * prose is a rule nobody is keeping.
 *
 * ### Keyed on surfaces, not on composables
 *
 * A `Screen` is too coarse and a composable is too fine. Half of what a child
 * uses is a *shelf inside* a screen — Favorites is a row on the You tab, not a
 * page — and `Screen.Watchlist` proves the point from the other end: it exists,
 * it is declared, and it is unreachable, because the tile that opened it has no
 * callers. A catalogue of `Screen`s would have promised the browser a page the
 * app does not actually have.
 *
 * So the unit is the thing a child can reach, whatever shape it is, and
 * [SurfaceKind] says which shape.
 *
 * ### The words live here
 *
 * [title] and [emptyText] are what a child reads, and both faces take them from
 * this file. That is what stops the two describing the same shelf differently —
 * which they already did: "Watch later" had two different empty states written
 * in two composables before this list existed.
 *
 * ### It lives in `:core`
 *
 * Both consumers must read the same list. In `:app` it would be invisible to
 * the hub; in `:hub`, invisible to the app. It sits in the `ui` package beside
 * [HomeSections] for the same reason that file gives: these names were already
 * `io.yosemitekids.app.ui`, and moving them to prove a point about directories
 * would churn every import in `:app`.
 */

/** Which faces draw a surface. */
enum class KidFace {
    /**
     * Phone and television only — usually because it needs a gesture, a remote,
     * or a device store the browser has not got. Requires a [KidSurfaceDef.why].
     */
    APP,

    /**
     * Browser only. Nothing qualifies yet; the value exists so a future
     * install-time affordance has somewhere to live rather than a comment.
     */
    WEB,

    /** The default expectation for anything a child can reach. */
    BOTH
}

/** What shape the surface is. */
enum class SurfaceKind {
    /** A destination a child navigates to: History, a channel page, Surprise. */
    SCREEN,

    /** A row inside one: Favorites on the You tab, Keep watching on the home. */
    SHELF,

    /** The hold menu. */
    DIALOG,

    /** Furniture: the header, the chip strip, the countdown pill. */
    CHROME
}

/**
 * A named number a surface is bounded by.
 *
 * Declared here because guard 61 forbids the browser page capping anything at
 * all, so every cap must be applied server-side by shared code. Naming it is
 * what lets a guard prove no face is holding a second copy — a cap in `:app`
 * that the hub does not read is a browser shelf that is silently a different
 * length, and nobody would report it as a bug.
 */
data class KidCap(val name: String, val value: Int, val why: String)

/**
 * One surface.
 *
 * [rules] is the load-bearing field. It names the `:core` and `:crawl`
 * functions that DECIDE this surface's contents, and guard 62 fails if any of
 * them cannot be found in those modules. A surface cannot therefore claim to
 * share a rule that is still stranded in `:app` — which is precisely the moment
 * the decision to extract it should be forced, rather than the moment somebody
 * copies it into the hub instead.
 */
data class KidSurfaceDef(
    /** Stable id. What the browser page branches on and the hub keys by. */
    val id: String,
    /** The words a child reads. One spelling, both faces. */
    val title: String,
    /**
     * The glyph beside the title, where a surface has one.
     *
     * An emoji rather than a vector, because it is what the app already draws
     * and because it is the one kind of icon a Compose chip and an HTML button
     * render the same without either side shipping an asset.
     */
    val icon: String = "",
    /** What an empty one says. Blank for a surface that cannot be empty. */
    val emptyText: String = "",
    val kind: SurfaceKind,
    val face: KidFace = KidFace.BOTH,
    /** The `Screen` subtype in `:app`, or blank for a shelf inside one. */
    val screen: String = "",
    /** Fully-qualified `:core`/`:crawl` functions that decide the contents. */
    val rules: List<String> = emptyList(),
    /** The kid-origin route or `/home` field that carries it. Blank until it lands. */
    val route: String = "",
    val caps: List<KidCap> = emptyList(),
    /** False for a [KidFace.BOTH] surface the browser does not draw yet. */
    val webReady: Boolean = false,
    /**
     * Why this surface is not simply the same on every face.
     *
     * **Required** when [face] is not BOTH or [webReady] is false, because a
     * gap with no reason beside it cannot be told from an oversight.
     *
     * **Allowed, and encouraged, on a ready surface too** — for a deliberate
     * difference in *how* a face draws it. Those are legitimate: a remote needs
     * focus a finger does not, a browser cannot lock an iPad's screen, a
     * ten-foot card is bigger. What must not happen is a difference nobody
     * wrote down, which is indistinguishable from drift six months later.
     */
    val why: String = "",
    /**
     * Whether the television draws this surface. The phone and the television
     * are one APK and [KidFace.APP] means both, which is exactly how a
     * difference between them went unrecorded for years. False needs [tvWhy];
     * guard 62(f) prints what the television skips beside what the browser
     * lacks.
     */
    val onTv: Boolean = true,
    /**
     * How the television draws this differently, or why it does not draw it.
     * The television keeps its rail and its remote; a surface that adapts to
     * ten feet and a D-pad is consistent, not forked - but the adaptation is
     * a decision, and it is written here rather than in whoever last touched
     * the composable.
     */
    val tvWhy: String = ""
)

object KidSurface {

    /** Rows a shelf previews before "See all". */
    val ROW_PREVIEW = KidCap("ROW_PREVIEW", 12, "a shelf is a glance; See all is the shelf")

    /** The History screen's ceiling. */
    val HISTORY_MAX = KidCap("HISTORY_MAX", 120, "far past what a child scrolls, and a bound on the join")

    /** A saved list's ceiling. */
    val SAVED_MAX = KidCap("SAVED_MAX", 200, "a list a child curates by hand; past this it is a feed")

    /** Rows one You-tab shelf holds once it is unfolded. */
    val YOU_PAGE_MAX = KidCap("YOU_PAGE_MAX", 60, "a shelf unfolded in place, not a second screen")

    /**
     * The You tab's shelves, in the order it draws them.
     *
     * Order lives here rather than in either renderer for the same reason
     * [HOME_SHELVES] does: the parent's shelf editor, when it lands, edits a
     * saved list against this catalogue, and a face holding its own order would
     * simply ignore what they saved.
     *
     * **Downloads is deliberately absent**, and its reason is in
     * [NOT_A_SURFACE] rather than here — a shelf the browser must never have is
     * not a shelf the browser is missing.
     */
    val YOU_SHELVES: List<String> = listOf("favorites", "watch-later", "up-next", "history")

    val surfaces: List<KidSurfaceDef> = listOf(
        KidSurfaceDef(
            id = "home",
            title = "Home",
            kind = SurfaceKind.SCREEN,
            screen = "Home",
            rules = listOf("homeSections", "resolvePins", "KidHome.keepWatching", "KidHome.history", "KidHome.suggestions", "KidHome.interleave"),
            route = "/home",
            webReady = true,
            tvWhy = "Same shelves, same order (homeSections), drawn as a LazyColumn with the feed " +
                "three across beside the rail, where the phone draws one LazyVerticalGrid and the " +
                "hero is a static row rather than a carousel: a D-pad walks rows, a thumb scrolls a " +
                "grid. HomeShelves.kt, GridPage vs ColumnPage."
        ),
        KidSurfaceDef(
            id = "channel",
            title = "Channel",
            kind = SurfaceKind.SCREEN,
            screen = "ChannelVideos",
            rules = listOf("SafeText.forKids"),
            route = "/channel",
            webReady = true
        ),
        KidSurfaceDef(
            id = "search-results",
            title = "Search",
            emptyText = "Nothing found. Try another word.",
            kind = SurfaceKind.SCREEN,
            screen = "SearchResults",
            rules = listOf("SearchRank.rank", "SearchRank.terms"),
            route = "/search",
            webReady = true
        ),
        KidSurfaceDef(
            id = "player",
            title = "Now playing",
            kind = SurfaceKind.SCREEN,
            rules = listOf("KidHome.FINISHED_FRACTION"),
            route = "/media",
            webReady = true,
            why = "HD is two tracks merged at playback. The app merges them in ExoPlayer; the browser " +
                "plays the manifest /kid/dash writes (HubDash) through dash.js, every segment through " +
                "/kid/media and its per-chunk gate, and falls back to the muxed 360p stream on any error. " +
                "One video, three faces, one gate.",
            tvWhy = "The stage only: no portrait scaffold, no picture-in-picture, no gestures, and " +
                "the transport is the remote's keys (PlayerActivity.onKeyDown) with a state glyph " +
                "in place of the phone's overlay. The end card, the countdown and every refusal " +
                "read the same (KidWords)."
        ),

        // --- declared, not yet drawn in a browser -------------------------

        KidSurfaceDef(
            id = "history",
            title = "History",
            icon = "🕘",
            emptyText = "Nothing watched yet. Whatever you watch shows up here.",
            kind = SurfaceKind.SCREEN,
            screen = "History",
            rules = listOf("KidHome.history"),
            route = "/you",
            caps = listOf(HISTORY_MAX, YOU_PAGE_MAX),
            webReady = true,
            why = "A DELIBERATE DIFFERENCE, recorded rather than hidden. On the phone this is a " +
                "SCREEN: the You tab's See all opens Screen.History and back returns. In the " +
                "browser it unfolds in place inside the You tab instead. Same rows, same cap, " +
                "same order - a different navigation, because a second page for a row a child " +
                "reached in one tap is a back button a five-year-old has to find, and a browser " +
                "back button already means something else to them (it leaves the app)."
        ),
        KidSurfaceDef(
            id = "you",
            title = "You",
            kind = SurfaceKind.SCREEN,
            screen = "You",
            rules = listOf("KidHome.history"),
            route = "/you",
            caps = listOf(ROW_PREVIEW, YOU_PAGE_MAX),
            webReady = true,
            tvWhy = "Reached from the rail (TvNavRail.RailStop) rather than a bottom tab; the " +
                "shelves and their order are YOU_SHELVES on every face."
        ),
        KidSurfaceDef(
            id = "favorites",
            title = "Favorites",
            icon = "❤️",
            emptyText = "Nothing here yet. Hold a video and pick Add to Favorites.",
            kind = SurfaceKind.SHELF,
            screen = "Watchlist",
            rules = listOf("SavedListStore.merge"),
            route = "/list",
            caps = listOf(SAVED_MAX),
            webReady = true,
            why = "A DIVERGENCE, recorded rather than hidden. A phone's favourites reach the " +
                "television because both run WatchSync; a browser's are hub-local, like its " +
                "history, because the browser has no such worker and the hub will not pretend " +
                "it does. Families expect favourites to follow the child, so this is a real gap " +
                "and not a design. Closing it means moving /watchstate off HubServer.DEVICE_ONLY " +
                "so the hub implements it with guard 22 watching - NOT a bespoke kid-origin " +
                "write, which would satisfy every existing guard while quietly creating the split."
        ),
        KidSurfaceDef(
            id = "watch-later",
            title = "Watch later",
            icon = "🕒",
            emptyText = "Nothing saved for later. Hold a video and pick Add to Watch later.",
            kind = SurfaceKind.SHELF,
            screen = "WatchLater",
            rules = listOf("SavedListStore.merge"),
            route = "/list",
            caps = listOf(SAVED_MAX),
            webReady = true,
            why = "Hub-local like favorites, and for the same reason — see that entry."
        ),
        KidSurfaceDef(
            id = "up-next",
            title = "Up next",
            icon = "📚",
            emptyText = "Nothing lined up. Hold a video and pick Add to Up next.",
            kind = SurfaceKind.SHELF,
            screen = "Queue",
            rules = listOf("QueueStore.moved"),
            route = "/list",
            webReady = true,
            why = "Device-local on BOTH faces, and here that is a design rather than a gap: a " +
                "queue is tonight, on this screen. The phone has never synced it either."
        ),
        KidSurfaceDef(
            id = "hold-menu",
            title = "What would you like to do?",
            kind = SurfaceKind.DIALOG,
            route = "/list",
            webReady = true,
            tvWhy = "Opened by holding OK on the remote (FocusHighlight's hold helper) where a " +
                "finger long-presses; the rows and their words are the same."
        ),
        KidSurfaceDef(
            id = "channel-hold-menu",
            title = "Favourite channel?",
            kind = SurfaceKind.DIALOG,
            route = "/list",
            rules = listOf("orderChannels"),
            webReady = true,
            why = "One row, the heart: a favourite channel floats to the front of every channel " +
                "order through orderChannels in :crawl, and that is all it does. The list is the " +
                "phone's SavedListStore.CHANNELS and the hub's per-kid copy of the same store.",
            tvWhy = "Opened by holding OK on a channel tile (dpadLongPress) where a finger long-presses."
        ),
        KidSurfaceDef(
            id = "channels",
            title = "Channels",
            kind = SurfaceKind.SCREEN,
            screen = "Channels",
            rules = listOf("orderChannels"),
            route = "/channels",
            webReady = true,
            why = "Two of the app's six sorts are absent and that is deliberate: Most watched needs open counts the hub does not keep, and Just added needs the phone's first-seen ledger. Latest video arrived with 1.9.0, when the index started keeping publishedAt (roadmap 2M). SearchOrder set the precedent - a chip that sorts by an all-equal key is worse than no chip.",
            tvWhy = "A rail stop rather than a bottom tab, and a grid of large tiles for ten feet " +
                "(TvChannelsGrid) where the phone lists rounded tiles with shelves " +
                "(PhoneChannelsList). Same channels, same orderChannels."
        ),
        KidSurfaceDef(
            id = "search-page",
            title = "Search",
            kind = SurfaceKind.SCREEN,
            screen = "Search",
            route = "/search",
            rules = listOf("RecentSearches", "SearchOrder"),
            webReady = true,
            why = "The page BEFORE a query: recent searches and the order chips. The phone keeps a " +
                "kid's recents on the device (SearchHistoryStore) and the browser's live on the " +
                "hub per kid (HubKidSearches), both through RecentSearches in :core; the chips are " +
                "SearchOrder.label on every face. The browser searches as you type, so a search " +
                "joins the recents only when Enter or a chip says the child meant it.",
            tvWhy = "A rail stop with the system's on-screen keyboard; the phone has the mic. Same " +
                "recents, same SearchOrder."
        ),
        KidSurfaceDef(
            id = "surprise",
            title = "Surprise me",
            kind = SurfaceKind.SCREEN,
            screen = "Surprise",
            route = "/surprise",
            rules = listOf("surpriseMix"),
            webReady = true,
            why = "Seeded on both faces now. :app called a bare shuffled(), which cannot agree between two faces by construction."
        ),
        KidSurfaceDef(
            id = "playlists",
            title = "Playlists",
            kind = SurfaceKind.SCREEN,
            screen = "Playlists",
            route = "/playlists",
            rules = listOf("PlaylistCrawlRun"),
            webReady = true,
            why = "The phone lists a channel's playlists live when its page opens; the hub carries no " +
                "live extraction on the kid's path, so the crawl indexes them (PlaylistCrawlRun: the " +
                "first twenty, the first page of each, refreshed daily) and the strip, See all and a " +
                "playlist's page draw from the index. A kid sees exactly the rows the channel page " +
                "would, and the parent-picked rows above the grid (playlistShelves) are the same " +
                "three rules - picks first, Shorts dropped, finished dropped - answered by the hub."
        ),
        KidSurfaceDef(
            id = "watched-videos",
            title = "Watched",
            kind = SurfaceKind.SCREEN,
            screen = "WatchedVideos",
            rules = listOf("KidHome.FINISHED_FRACTION", "orderByWatched"),
            route = "/channel",
            webReady = true,
            why = "A channel's finished videos - the app's only two-level screen. The browser asks " +
                "/channel?watched=1 and the hub answers with the phone's own orderByWatched; what " +
                "counts as finished is the hub's verdict per row, never the page's."
        )
    )

    /**
     * `Screen` subtypes with no browser equivalent, and the reason.
     *
     * A refusal with no reason beside it is indistinguishable from an omission,
     * which is the whole argument `SettingsSurface.NOT_A_CONTROL` makes.
     */
    val NOT_A_SURFACE: Map<String, String> = mapOf(
        "Downloads" to
            "Out of scope in every form. A cached video keeps playing after a parent blocks it, " +
            "which is the exact failure /media's per-chunk mayPlay gate and no-store exist to " +
            "prevent. The app already hides this shelf when empty, so a browser that never has " +
            "one is consistent with the app rather than a special case."
    )

    private val byId = surfaces.associateBy { it.id }

    /**
     * One surface by id, or a loud failure.
     *
     * Throws rather than returning null on purpose: the caller is about to draw
     * something, and a blank title on a child's screen is the failure this whole
     * file exists to make impossible. Guard 62 checks every call site's id
     * against this list, so the throw is a backstop and not the mechanism.
     */
    fun surface(id: String): KidSurfaceDef =
        byId[id] ?: error("no kid surface called \"$id\" — see KidSurface")

    /** Surfaces a browser should have and does not. What the gate names on every run. */
    fun outstandingOnWeb(): List<KidSurfaceDef> =
        surfaces.filter { it.face == KidFace.BOTH && !it.webReady }

    /** What the browser draws today. */
    fun forWeb(): List<KidSurfaceDef> =
        surfaces.filter { it.face != KidFace.APP && it.webReady }

    /** What the television draws. Everything the app draws, less what [KidSurfaceDef.onTv] rules out. */
    fun forTv(): List<KidSurfaceDef> =
        surfaces.filter { it.face != KidFace.WEB && it.onTv }
}
