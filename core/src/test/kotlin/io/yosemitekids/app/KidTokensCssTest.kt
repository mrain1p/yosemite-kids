package io.yosemitekids.app

import io.yosemitekids.app.ui.Argb
import io.yosemitekids.app.ui.KID_DARK
import io.yosemitekids.app.ui.KID_LIGHT
import io.yosemitekids.app.ui.KidType
import io.yosemitekids.app.ui.kidTokenRoles
import io.yosemitekids.app.ui.kidTokensCss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stylesheet the hub serves a browser, checked where it is written.
 *
 * The failure this exists for is not a crash. It is a custom property that
 * quietly is not there: `var(--yk-surface-container)` falls back to nothing,
 * the card is drawn on transparent, and the page looks *almost* right on the
 * one screen nobody photographed.
 */
class KidTokensCssTest {

    private val css = kidTokensCss()

    private fun blockFor(selector: String): String {
        val start = css.indexOf("$selector {")
        assertTrue("$selector has no block in the stylesheet", start >= 0)
        val end = css.indexOf("\n}", start)
        assertTrue("$selector's block is never closed", end > start)
        return css.substring(start, end)
    }

    @Test
    fun `both looks name every role and every token`() {
        for ((selector, scheme) in listOf(":root" to KID_DARK, "[data-yk-theme=\"light\"]" to KID_LIGHT)) {
            val block = blockFor(selector)
            for ((name, argb) in scheme.roles()) {
                assertTrue("$selector is missing --yk-$name", "--yk-$name: " in block)
                assertTrue(
                    "$selector's --yk-$name is not ${Argb.css(argb)}",
                    "--yk-$name: ${Argb.css(argb)};" in block
                )
            }
            for ((name, argb) in kidTokenRoles(scheme.background)) {
                assertTrue(
                    "$selector's --yk-$name is not ${Argb.css(argb)}",
                    "--yk-$name: ${Argb.css(argb)};" in block
                )
            }
        }
    }

    @Test
    fun `the light look overrides the ground and the signals that follow it`() {
        // The whole reason the derived values are shipped rather than the
        // canonical ones: coral does not carry text on paper, so the light
        // block must not simply repeat the dark block's action colour.
        val dark = blockFor(":root")
        val light = blockFor("[data-yk-theme=\"light\"]")
        assertTrue("the light look must restate --yk-action", "--yk-action: " in light)
        val darkAction = Regex("--yk-action: ([^;]+);").find(dark)!!.groupValues[1]
        val lightAction = Regex("--yk-action: ([^;]+);").find(light)!!.groupValues[1]
        assertTrue(
            "the light look ships the same action colour as the dark one ($darkAction) — " +
                "it reads at 3.67:1 on paper and must be darkened",
            darkAction != lightAction
        )
    }

    @Test
    fun `every step of the type scale reaches the browser`() {
        for (t in KidType.all) {
            assertTrue("--yk-font-${t.css}-size is missing", "--yk-font-${t.css}-size: ${t.sizeSp}px;" in css)
            assertTrue("--yk-font-${t.css}-line is missing", "--yk-font-${t.css}-line: ${t.lineHeightSp}px;" in css)
            if (t.weight != null) {
                assertTrue(
                    "--yk-font-${t.css}-weight is missing",
                    "--yk-font-${t.css}-weight: ${t.weight};" in css
                )
            } else {
                assertTrue(
                    "${t.css} takes the font's own weight in the app, so the stylesheet must not name one",
                    "--yk-font-${t.css}-weight" !in css
                )
            }
        }
    }

    @Test
    fun `no property is left empty and every brace is closed`() {
        val empty = css.lines().filter { it.trim().startsWith("--yk-") && it.trim().endsWith(": ;") }
        assertTrue("empty custom properties: $empty", empty.isEmpty())
        assertEquals("unbalanced braces", css.count { it == '{' }, css.count { it == '}' })
        // A comma-decimal locale would emit rgba(0, 0, 0, 0,8), which a browser
        // throws away without a word. Argb.css pins Locale.ROOT; this notices.
        assertTrue("a locale-formatted alpha reached the stylesheet", ", 0,8)" !in css)
    }

    @Test
    fun `the header says not to edit it and where it came from`() {
        assertTrue("GENERATED FILE" in css.take(200))
        assertTrue("DesignTokens.kt" in css)
    }
}
