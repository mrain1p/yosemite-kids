package io.yosemitekids.app.data

import android.content.Context

/**
 * The Android half of the two stores that moved to `:crawl`.
 *
 * [SavedListStore] and [QueueStore] used to take a `Context` and build their
 * own paths under `filesDir`. They now take files, because the hub holds the
 * same lists for a child's browser and `:crawl` has no Android in it — see
 * `SavedListStore`'s KDoc for why copying its merge into the hub was not an
 * option.
 *
 * These are **functions named like the constructors they replace**, which is
 * legal in Kotlin and deliberate: every call site on the phone —
 * `SavedListStore(context, profileSuffix)` — reads exactly as it did, so the
 * move cost the app no churn at all and no reviewer has to hold two spellings
 * in their head. The only thing that changed is where the rules live.
 */

@Suppress("FunctionName")
fun SavedListStore(
    context: Context,
    profileSuffix: String = "",
    listName: String = SavedListStore.FAVORITES
): SavedListStore {
    val (file, removed) = SavedListStore.filesIn(context.filesDir, listName, profileSuffix)
    return SavedListStore(file, removed)
}

@Suppress("FunctionName")
fun QueueStore(context: Context, profileSuffix: String = ""): QueueStore =
    QueueStore(QueueStore.fileIn(context.filesDir, profileSuffix))
