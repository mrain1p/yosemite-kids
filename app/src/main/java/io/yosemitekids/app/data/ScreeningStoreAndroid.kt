package io.yosemitekids.app.data

import android.content.Context
import java.io.File

/**
 * The device's AI verdicts live at filesDir/screening.json.
 *
 * A function named like the constructor it replaces, so every caller reads
 * exactly as before; the class itself is in :crawl now and knows only a File,
 * because the hub holds these verdicts too and has no Context to offer. Same
 * split, and for the same reason, as [ChannelIndex] in ChannelIndexAndroid.kt.
 */
@Suppress("FunctionName")
fun ScreeningStore(context: Context): ScreeningStore =
    ScreeningStore(File(context.filesDir, "screening.json"))
