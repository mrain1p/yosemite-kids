package io.yosemitekids.app

import androidx.compose.ui.unit.dp
import io.yosemitekids.app.ui.CARD_TITLE_LINES
import io.yosemitekids.app.ui.cardTitleHeight
import io.yosemitekids.app.ui.metaLine
import io.yosemitekids.app.ui.titleLinesIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules the shared card geometry created, stated as values so they
 * fail at `gradlew test` rather than on a family's TV.
 *
 * Both are the kind of thing that only shows up in one configuration nobody
 * reviews: a meta line with a dangling separator needs a parent to have left
 * "show when a video came out" off (the default) *and* a cache row with no
 * channel name; a collapsed title box needs a household that turned the
 * system font size up.
 */
class CardGeometryTest {

    // ---- the meta line ----------------------------------------------------

    /**
     * `showVideoAge` defaults to off, so "channel alone" is the common case,
     * not the edge one. The separator has to leave with the half it separates.
     */
    @Test
    fun ageOffLeavesTheChannelAlone() {
        assertEquals("SciShow Kids", metaLine("SciShow Kids", null))
        assertEquals("SciShow Kids · today", metaLine("SciShow Kids", "today"))
    }

    @Test
    fun neverADanglingSeparator() {
        val channels = listOf("SciShow Kids", "", "   ")
        val ages = listOf<String?>(null, "today", "", "   ")
        for (c in channels) for (a in ages) {
            val line = metaLine(c, a)
            assertFalse("leading separator for ($c, $a): '$line'", line.startsWith("·"))
            assertFalse("trailing separator for ($c, $a): '$line'", line.endsWith("·"))
            assertFalse("doubled separator for ($c, $a): '$line'", line.contains("· ·"))
            assertEquals("untrimmed for ($c, $a): '$line'", line.trim(), line)
        }
    }

    @Test
    fun bothHalvesMissingIsAnEmptyLine() {
        assertEquals("", metaLine("", null))
        assertEquals("", metaLine("   ", "   "))
        // One half present is that half, with nothing attached to it.
        assertEquals("today", metaLine("  ", "today"))
    }

    // ---- the title box ----------------------------------------------------

    /**
     * The box is dp, sized from the type at the default scale; the line count
     * inside it is what gives way when a family scales their fonts up. The
     * failure this rules out is a second line sliced in half by the box, or a
     * rail of cards that no longer line up.
     */
    @Test
    fun twoLinesAtTheDefaultFontScale() {
        val box = cardTitleHeight(20f)
        assertEquals(40f, box.value, 0.001f)
        assertEquals(CARD_TITLE_LINES, titleLinesIn(box, 20.dp))
    }

    @Test
    fun aLargeFontScaleTruncatesRatherThanClips() {
        val box = cardTitleHeight(20f)
        // 1.5×: two lines no longer fit, so the card draws one and ellipsises.
        assertEquals(1, titleLinesIn(box, 30.dp))
        // 1.15×: still two, because 46 dp of text in a 40 dp box is a sliced
        // second line — this is exactly the case the floor has to catch.
        assertEquals(1, titleLinesIn(box, 23.dp))
    }

    @Test
    fun neverZeroLinesAndNeverMoreThanTheBox() {
        val box = cardTitleHeight(20f)
        // A font scale big enough that one line already overflows still draws
        // one: no line at all is a card with a blank where its title was.
        assertEquals(1, titleLinesIn(box, 80.dp))
        // And a tiny line height does not turn a two-line card into a
        // paragraph — the box is the contract.
        assertEquals(CARD_TITLE_LINES, titleLinesIn(box, 4.dp))
        // Degenerate input (an unspecified line height reaching here) falls
        // back to the design's line count rather than dividing by zero.
        assertEquals(CARD_TITLE_LINES, titleLinesIn(box, 0.dp))
    }

    /** Floating point must not turn 40/20 into 1.9999997 and cost a line. */
    @Test
    fun exactFitsAreNotLostToRounding() {
        for (lh in listOf(16f, 17f, 18f, 19f, 20f, 22f, 26f)) {
            assertTrue(
                "line height $lh lost a line at the default scale",
                titleLinesIn(cardTitleHeight(lh), lh.dp) == CARD_TITLE_LINES
            )
        }
    }
}
