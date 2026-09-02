package io.pickwick.app.data

import android.content.Context

/** The kid-facing sort of the channel row / Channels tab. Parent's [CHANNEL_ORDER_*] plus "latest video". */
const val CHANNEL_ORDER_LATEST = "latest"

/** The kid-facing order of a video list: newest (the feed's own), a fresh shuffle, or by YouTube views. */
const val VIDEO_FILTER_NEW = "new"
const val VIDEO_FILTER_RANDOM = "random"
const val VIDEO_FILTER_POPULAR = "popular"
val VIDEO_FILTERS = listOf(VIDEO_FILTER_NEW, VIDEO_FILTER_RANDOM, VIDEO_FILTER_POPULAR)
val KID_CHANNEL_SORTS = listOf(CHANNEL_ORDER_WATCHED, CHANNEL_ORDER_ALPHA, CHANNEL_ORDER_RANDOM, CHANNEL_ORDER_LATEST)

/**
 * What one kid chose on the chips — how channels are sorted, how the home
 * feed and channel pages are ordered. Device-local and per kid (the store
 * suffix), like their recent searches: a choice, not a rule, so it never
 * travels in the config and the parent's settings stay the defaults a kid
 * who never touches a chip gets. Synchronous prefs: call off-main.
 */
class KidPrefs(context: Context, profileSuffix: String) {
    private val prefs =
        context.applicationContext.getSharedPreferences("kid_prefs$profileSuffix", Context.MODE_PRIVATE)

    /** Null = the parent's channel row order. */
    fun channelSort(): String? = prefs.getString("channel_sort", null)?.takeIf { it in KID_CHANNEL_SORTS }
    fun setChannelSort(sort: String?) = prefs.edit().putString("channel_sort", sort).apply()

    /** Null = newest (the feed's own mix). */
    fun homeFilter(): String? = prefs.getString("home_filter", null)?.takeIf { it in VIDEO_FILTERS }
    fun setHomeFilter(filter: String?) = prefs.edit().putString("home_filter", filter).apply()

    /** Null = the parent's channel page layout. One choice for every channel page. */
    fun channelFilter(): String? = prefs.getString("channel_filter", null)?.takeIf { it in VIDEO_FILTERS }
    fun setChannelFilter(filter: String?) = prefs.edit().putString("channel_filter", filter).apply()
}
