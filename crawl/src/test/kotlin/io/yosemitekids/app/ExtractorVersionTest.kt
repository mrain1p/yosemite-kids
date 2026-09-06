package io.yosemitekids.app

import io.yosemitekids.app.data.ExtractorVersion
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The extractor version stamp is generated from the version catalog. A
 * persisted crawl cursor is readable only by the extractor version that
 * wrote it, so the stamp must be the real dependency's version, never a
 * literal someone forgot to bump. A release tag or a JitPack commit SHA
 * are the two shapes the catalog allows (see libs.versions.toml).
 */
class ExtractorVersionTest {
    @Test
    fun theStampIsARealReleaseTagOrCommit() {
        val tag = Regex("v[0-9]+[.][0-9]+[.][0-9]+")
        val sha = Regex("[0-9a-f]{7,40}")
        val v = ExtractorVersion.VALUE
        assertTrue(v, tag.matches(v) || sha.matches(v))
    }
}
