package io.yosemitekids.app

import io.yosemitekids.app.ui.relativeAge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The "3 days ago" line under a title (Theme.relativeAge). */
class RelativeAgeTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    @Test
    fun wordsForEachScale() {
        assertEquals("today", relativeAge(now - 3_600_000L, now))
        assertEquals("yesterday", relativeAge(now - day, now))
        assertEquals("3 days ago", relativeAge(now - 3 * day, now))
        assertEquals("1 week ago", relativeAge(now - 8 * day, now))
        assertEquals("3 weeks ago", relativeAge(now - 22 * day, now))
        assertEquals("2 months ago", relativeAge(now - 65 * day, now))
        assertEquals("1 year ago", relativeAge(now - 400 * day, now))
    }

    @Test
    fun unknownAndFutureDatesSayNothing() {
        assertNull(relativeAge(null, now))
        assertNull(relativeAge(now + day, now))
    }
}
