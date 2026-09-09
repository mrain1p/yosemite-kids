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
    val why: String = ""
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
            webReady = true
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
            webReady = true
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
            webReady = true
        ),
        KidSurfaceDef(
            id = "favorites",
            title = "Favorites",
            icon = "❤️",
            emptyText = "Nothing here yet. Hold a video and pick Add to Favorites.",
            kind = SurfaceKind.SHELF,
            screen = "Watchlist",
            caps = listOf(SAVED_MAX),
            webReady = false,
            why = "R3. Blocked on SavedListStore moving to :crawl - it is Android-only today, and " +
                "copying its merge into the hub would be two implementations of tombstone " +
                "causality, which is the failure the sync skill exists to warn about."
        ),
        KidSurfaceDef(
            id = "watch-later",
            title = "Watch later",
            icon = "🕒",
            emptyText = "Nothing saved for later. Hold a video and pick Add to Watch later.",
            kind = SurfaceKind.SHELF,
            screen = "WatchLater",
            caps = listOf(SAVED_MAX),
            webReady = false,
            why = "R3. Same store as favorites, same extraction."
        ),
        KidSurfaceDef(
            id = "up-next",
            title = "Up next",
            icon = "📚",
            emptyText = "Nothing lined up. Hold a video and pick Add to Up next.",
            kind = SurfaceKind.SHELF,
            screen = "Queue",
            webReady = false,
            why = "R3. Blocked on QueueStore moving to :crawl."
        ),
        KidSurfaceDef(
            id = "hold-menu",
            title = "What would you like to do?",
            kind = SurfaceKind.DIALOG,
            webReady = false,
            why = "R3. The entry point for all three saved lists - without it they can be shown " +
                "and never filled, which is worse than their absence."
        ),
        KidSurfaceDef(
            id = "channels",
            title = "Channels",
            kind = SurfaceKind.SCREEN,
            screen = "Channels",
            rules = listOf("orderChannels"),
            webReady = false,
            why = "R5. Blocked on orderChannels moving to :crawl; the browser draws the home rail " +
                "today, which is the glance, but not the grid behind it."
        ),
        KidSurfaceDef(
            id = "search-page",
            title = "Search",
            kind = SurfaceKind.SCREEN,
            screen = "Search",
            webReady = false,
            why = "R5. The page BEFORE a query - recent searches and the control row. The browser " +
                "searches as you type and so has never had one."
        ),
        KidSurfaceDef(
            id = "surprise",
            title = "Surprise me",
            kind = SurfaceKind.SCREEN,
            screen = "Surprise",
            webReady = false,
            why = "R5. Blocked on the mix being seeded - :app calls bare shuffled(), which cannot " +
                "be reproduced on another face by definition."
        ),
        KidSurfaceDef(
            id = "playlists",
            title = "Playlists",
            kind = SurfaceKind.SCREEN,
            screen = "Playlists",
            webReady = false,
            why = "R5. Needs playlists[] on /channel, which the hub does not index yet."
        ),
        KidSurfaceDef(
            id = "watched-videos",
            title = "Watched",
            kind = SurfaceKind.SCREEN,
            screen = "WatchedVideos",
            rules = listOf("KidHome.FINISHED_FRACTION"),
            webReady = false,
            why = "R5. A channel's finished videos - the app's only two-level screen."
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
}
