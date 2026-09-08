package io.yosemitekids.app

import io.yosemitekids.app.data.SafeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule is short and absolute: nothing a child can read off a channel's
 * description may be a way to reach something a parent did not allow. So the
 * assertions here are mostly negative — what must NOT survive — and the
 * positive ones only check that a real blurb is still worth showing.
 */
class SafeTextTest {

    /** Anything that would let a child (or an adult they hand the phone to) leave. */
    private fun assertNoRouteOut(text: String?) {
        val t = text.orEmpty()
        assertFalse(t, t.contains("http", ignoreCase = true))
        assertFalse(t, t.contains("www.", ignoreCase = true))
        assertFalse(t, t.contains("@"))
        assertFalse(t, Regex("""[A-Za-z0-9][A-Za-z0-9-]+\.[A-Za-z]{2,6}\b""").containsMatchIn(t))
    }

    @Test
    fun blankAndAbsentAreNothing() {
        assertNull(SafeText.forKids(null))
        assertNull(SafeText.forKids(""))
        assertNull(SafeText.forKids("   \n\n  "))
    }

    @Test
    fun linksGo() {
        val out = SafeText.forKids(
            "Fun songs for toddlers. Watch more at https://example.com/kids?ref=yt and " +
                "http://mirror.example.co.uk/a/b."
        )
        assertNoRouteOut(out)
        assertTrue(out!!, out.startsWith("Fun songs for toddlers."))
    }

    @Test
    fun bareDomainsGo() {
        assertNoRouteOut(SafeText.forKids("Buy the plushies at superkidstoys.shop"))
        assertNoRouteOut(SafeText.forKids("Games: coolmathgames.com/play/1"))
        // A suffix nobody put on a list is exactly the case a list would miss.
        assertNoRouteOut(SafeText.forKids("See us on example.zw and example.quest"))
    }

    @Test
    fun handlesAndEmailAddressesGo() {
        assertNoRouteOut(SafeText.forKids("Follow @SuperKidsTV and @kids_official for more"))
        assertNoRouteOut(SafeText.forKids("Business: hello@studio-kids.co.uk"))
        // The e-mail must go whole: stripping only the name would leave a
        // perfectly usable domain behind.
        assertNoRouteOut(SafeText.forKids("write to hi@studio.com today"))
    }

    @Test
    fun wwwWithoutASchemeStillGoes() {
        assertNoRouteOut(SafeText.forKids("more at www.kidsplace.tv/videos"))
    }

    @Test
    fun ordinaryProseSurvives() {
        val out = SafeText.forKids(
            "We make gentle stories for children aged 2-5.\n" +
                "New episodes every Tuesday at 4 p.m.\n" +
                "Made by two parents, e.g. us."
        )
        assertEquals(
            "We make gentle stories for children aged 2-5.\n" +
                "New episodes every Tuesday at 4 p.m.\n" +
                "Made by two parents, e.g. us.",
            out
        )
    }

    @Test
    fun aDescriptionThatWasOnlyLinksIsNoDescription() {
        assertNull(
            SafeText.forKids(
                "https://example.com\n" +
                    "www.example.com\n" +
                    "@handle\n" +
                    "mail@example.com\n" +
                    "-----"
            )
        )
    }

    @Test
    fun theLineLeftBehindDoesNotEndInADanglingColon() {
        val out = SafeText.forKids("Songs for little ones.\nSubscribe here: https://example.com/sub")
        assertEquals("Songs for little ones.\nSubscribe here", out)
    }

    @Test
    fun aLinkListLeavesNoRunOfBlankLines() {
        val out = SafeText.forKids(
            "Our channel.\n\nhttps://a.example\n\nhttps://b.example\n\nThanks for watching!"
        )
        assertEquals("Our channel.\n\nThanks for watching!", out)
    }

    @Test
    fun aVeryLongBlurbIsCutAtAWord() {
        val long = ("word ".repeat(600)).trim()
        val out = SafeText.forKids(long)!!
        assertTrue(out.length <= SafeText.MAX + 1)
        assertTrue(out.endsWith("word…"))
    }

    @Test
    fun clampLeavesShortTextAlone() {
        assertEquals("short", SafeText.clamp("short"))
    }
}
