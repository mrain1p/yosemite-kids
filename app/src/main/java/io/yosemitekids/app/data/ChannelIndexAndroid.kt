package io.yosemitekids.app.data

import android.content.Context
import java.io.File

/**
 * The app's search index lives under filesDir/search-index.
 *
 * A function named like the constructor it replaces, so every caller in the
 * app reads exactly as before; the class itself is in :crawl now and knows
 * only a File, because the hub keeps its index under /data and has no
 * Context to offer. No disk work here: construction happens on the main
 * thread (activity setup, remember{} in settings) and ChannelIndex defers
 * every read and mkdir to its first file-backed call.
 */
@Suppress("FunctionName")
fun ChannelIndex(context: Context): ChannelIndex =
    ChannelIndex(File(context.filesDir, "search-index"))
