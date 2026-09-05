package io.yosemitekids.app.data

/**
 * The vocabulary of a kid's chip choices — how channels are sorted and how a
 * video list is ordered.
 *
 * In :core because the config model names these values, so anything that
 * reads a config has to know them. The store that remembers what one kid
 * picked is device-local and stays in the app.
 */

/** The kid-facing sort of the channel row / Channels tab. Parent's [CHANNEL_ORDER_*] plus "latest video". */
const val CHANNEL_ORDER_LATEST = "latest"

/** The kid-facing order of a video list: newest (the feed's own), a fresh shuffle, or by YouTube views. */
const val VIDEO_FILTER_NEW = "new"
const val VIDEO_FILTER_RANDOM = "random"
const val VIDEO_FILTER_POPULAR = "popular"
val VIDEO_FILTERS = listOf(VIDEO_FILTER_NEW, VIDEO_FILTER_RANDOM, VIDEO_FILTER_POPULAR)
val KID_CHANNEL_SORTS = listOf(CHANNEL_ORDER_WATCHED, CHANNEL_ORDER_ALPHA, CHANNEL_ORDER_RANDOM, CHANNEL_ORDER_LATEST)
