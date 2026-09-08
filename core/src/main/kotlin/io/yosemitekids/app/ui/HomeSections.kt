package io.yosemitekids.app.ui

import io.yosemitekids.app.data.Pins

/**
 * The home screen's shelves — the model, not the drawing.
 *
 * **In `:core` because a second face is about to render it.** The phone, the
 * television and the browser have three different renderers and exactly one
 * answer to "which shelves, in what order, with what in them": add a shelf
 * here and every face gets it, rather than a web home that quietly lacks the
 * row a parent just turned on. Nothing in this file knows what a pixel is,
 * which is what lets it live beside the merge rules the hub already shares.
 *
 * The `ui` package inside `:core` is deliberate: these names were already
 * `io.yosemitekids.app.ui` and the move must not churn every import in `:app`
 * to prove a point about directory layout.
 */

/**
 * The home screen's shelves, named.
 *
 * **Strings, deliberately, and never an enum ordinal.** These ids are what a
 * parent's saved order will be written as the moment the home-screen editor
 * exists, and an ordinal renumbers itself the first time a shelf is inserted
 * in the middle: a family's saved order would silently become a *different*
 * order after an update. That is the kind of bug nobody reports, because it
 * looks like they mis-remembered what they set.
 */
object HomeShelf {
    /** The parent's pinned hero — two or three channels or playlists. */
    const val PINNED = "pinned"
    const val CHANNELS = "channels"
    const val KEEP_WATCHING = "keep-watching"
    /** "More like what you watch" — the app's only suggestion surface. */
    const val SUGGESTED = "suggested"
    /** The feed, with the merged control row above it. */
    const val VIDEOS = "videos"
    /** "Watched lately". */
    const val HISTORY = "history"
}

/** One shelf's place on the home: which shelf, and whether it is drawn at all. */
data class HomeSection(val id: String, val enabled: Boolean = true)

/**
 * Every shelf this build knows how to draw, in the order a fresh install gets
 * them. The handoff's order, plus the history rail the ten-foot home already
 * had; both form factors now draw the same list.
 */
val HOME_SHELVES: List<String> = listOf(
    HomeShelf.PINNED,
    HomeShelf.CHANNELS,
    HomeShelf.KEEP_WATCHING,
    HomeShelf.SUGGESTED,
    HomeShelf.VIDEOS,
    HomeShelf.HISTORY
)

/**
 * The order and enabled flags this build will actually draw, given whatever a
 * parent saved.
 *
 * Two things it has to survive, and both are *updates* rather than edits. A
 * saved order naming a shelf this build no longer has must not leave a hole,
 * so unknown ids drop. A shelf this build has and the saved order has never
 * heard of must not be invisible until the parent goes and re-saves, so new
 * ids are appended in catalogue order and switched on. What the saved order
 * *does* name keeps its say, which is the whole point of saving it.
 */
fun homeSections(
    saved: List<HomeSection>,
    catalogue: List<String> = HOME_SHELVES
): List<HomeSection> {
    val known = catalogue.toSet()
    val kept = saved.filter { it.id in known }.distinctBy { it.id }
    val named = kept.mapTo(HashSet()) { it.id }
    return kept + catalogue.filterNot { it in named }.map { HomeSection(it) }
}

/**
 * The design draws two or three hero cards; three is the ceiling.
 *
 * One number, from `:core`, because the editor's cap and the renderer's have
 * to be the same one: a fourth card the editor allowed and the home refused
 * would be a pin that saved, synced, and never appeared. Guard 42 holds this
 * line to [Pins.MAX].
 */
const val HOME_PINS_MAX = Pins.MAX

/**
 * The only thing the hero needs of a thing it can pin: an id to match against.
 *
 * An interface rather than the concrete `Source`, because `Source` carries the
 * extractor with it and `:core` must stay the pure rules — and because the
 * browser's row will be built from whatever the hub hands the page, which is
 * not going to be the same class the phone draws.
 */
interface PinnableSource {
    val id: String
}

/** One hero card: which source it opens, and the mono line under its name. */
data class PinnedItem<out S : PinnableSource>(val source: S, val meta: String)

/**
 * The hero's mono line: what is new here, or how much there is. Never a
 * bare "0 videos" — a channel whose cache has not landed yet says nothing
 * rather than saying it is empty.
 */
fun pinMeta(newCount: Int, videoCount: Int): String = when {
    newCount > 0 -> "$newCount new video${if (newCount == 1) "" else "s"}"
    videoCount > 0 -> "$videoCount video${if (videoCount == 1) "" else "s"}"
    else -> ""
}

/**
 * The hero's cards: the parent's pinned ids, in the parent's order, resolved
 * against the sources this kid can see *right now* — and nothing else.
 *
 * **It fails closed, and that is the whole job.** [visible] is the list the
 * home already draws, which has been through both filters that matter: the
 * per-kid `visibleTo` check, and `MainViewModel.visibleSources`, which drops a
 * source whose every cached video is parent-blocked or held by AI screening.
 * An id that is not in that list is dropped without comment. Resolving a pin
 * any other way — from the whitelist, from a cache, from a name — would put a
 * channel restricted to an older sibling on a five-year-old's home screen as
 * the single biggest thing on it.
 *
 * That argument does not become less true in a browser, which is why this is
 * the function the web home has to call rather than one it re-derives from a
 * JSON payload.
 */
fun <S : PinnableSource> resolvePins(
    pinned: List<String>,
    visible: List<S>,
    newCount: (S) -> Int = { 0 },
    videoCount: (S) -> Int = { 0 },
    max: Int = HOME_PINS_MAX
): List<PinnedItem<S>> {
    if (pinned.isEmpty() || visible.isEmpty()) return emptyList()
    val byId = visible.associateBy { it.id }
    return pinned.asSequence()
        .distinct()
        .mapNotNull { byId[it] }
        .take(max)
        .map { PinnedItem(it, pinMeta(newCount(it), videoCount(it))) }
        .toList()
}

/**
 * Which shelf the television's opening focus belongs to: the first one that is
 * enabled *and* actually has something focusable in it.
 *
 * An empty shelf draws nothing, so a focus request aimed at it lands nowhere —
 * and "nowhere" on a TV means a remote that does not appear to work at all.
 */
fun firstFocusableShelf(
    sections: List<HomeSection>,
    counts: Map<String, Int>
): String? = sections.firstOrNull { it.enabled && (counts[it.id] ?: 0) > 0 }?.id
