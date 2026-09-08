package io.yosemitekids.app.ui

/**
 * What is left of the home's shelf model on Android.
 *
 * The model itself — [HomeShelf], [HOME_SHELVES], [homeSections],
 * [resolvePins], [pinMeta], [firstFocusableShelf], [HOME_PINS_MAX] — moved to
 * `:core` so the browser renders from the same list the phone does; same
 * package, so nothing in `:app` had to change its imports. Only this one
 * function stayed, because it reads [UiState], which is Compose state and
 * belongs to the Android face. Guard 47 keeps the rest from growing back here.
 *
 * The file is named for what is in it and not `HomeSections.kt` for a reason
 * that costs an afternoon to rediscover: two files of the same name in the
 * same package, in two modules, compile to the same `HomeSectionsKt` class,
 * and the one that loses the classpath race takes its functions with it. The
 * failure is a `NoSuchMethodError` at runtime, from a build that compiled.
 */

/**
 * How many items each shelf has to show. Drives three things that must agree:
 * the mono count beside a shelf's title, whether the shelf is drawn at all,
 * and where the television's opening focus goes. They disagreed when each was
 * computed at its own call site — a shelf could carry a count of zero.
 */
internal fun homeShelfCounts(state: UiState): Map<String, Int> = mapOf(
    HomeShelf.PINNED to state.pinned.size,
    HomeShelf.CHANNELS to state.channels.size,
    HomeShelf.KEEP_WATCHING to state.keepWatching.size,
    HomeShelf.SUGGESTED to state.suggested.size,
    HomeShelf.VIDEOS to state.feed.size,
    HomeShelf.HISTORY to state.recentHistory.size
)
