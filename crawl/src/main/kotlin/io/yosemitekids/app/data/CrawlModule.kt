package io.yosemitekids.app.data

/**
 * Marker for the :crawl module while its contents move in (docs/PLAN-crawl.md,
 * steps 2 and 3). Exists so the module has a source set for Gradle, the
 * Dockerfile and the guards to see before the first file is moved; deleted
 * with step 3.
 */
internal object CrawlModule {
    const val NAME = "crawl"
}
