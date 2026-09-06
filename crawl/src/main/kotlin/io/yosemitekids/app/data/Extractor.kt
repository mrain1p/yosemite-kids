package io.yosemitekids.app.data

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/**
 * The one place NewPipe is initialised.
 *
 * The app, the extractor tests and the hub all crawl the same channels, and
 * localization is part of what they crawl: a hub set to another country would
 * index different "uploads" than the phone shows. One call, so the three can
 * never drift. Safe to call more than once; NewPipe simply takes the newest.
 */
object Extractor {
    fun init() {
        NewPipe.init(OkHttpDownloader(), Localization("en", "US"), ContentCountry("US"))
    }
}
