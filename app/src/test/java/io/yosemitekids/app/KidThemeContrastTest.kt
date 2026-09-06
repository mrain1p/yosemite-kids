package io.yosemitekids.app

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import io.yosemitekids.app.data.PROFILE_COLORS
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.ui.KID_THEMES
import io.yosemitekids.app.ui.THEME_COLOR
import io.yosemitekids.app.ui.kidColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The kid's looks have to stay legible in every combination a kid can
 * actually produce, which is three themes times eight swatches, not the one
 * dark screenshot a restyle gets reviewed against. Before this existed the
 * app shipped white-on-pale-teal at 1.45:1 in the light theme and nobody
 * noticed, because nobody was looking at light.
 *
 * Pure maths on the scheme's own colours — no Android, no device — so a
 * palette edit that dims text below the floor fails at `gradlew test`
 * rather than on someone's TV.
 */
class KidThemeContrastTest {

    /** WCAG 2.1 relative luminance. Compose's channels are already 0..1 sRGB. */
    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /** Every (theme, swatch) a kid can select. Non-colour themes ignore the swatch. */
    private fun everyLook(): List<Pair<String, ColorScheme>> =
        KID_THEMES.flatMap { theme ->
            if (theme == THEME_COLOR) {
                PROFILE_COLORS.map { argb ->
                    "$theme/${java.lang.Long.toHexString(argb)}" to
                        kidColorScheme(Profile(id = "t", name = "T", colorArgb = argb), theme)
                }
            } else {
                listOf(theme to kidColorScheme(null, theme))
            }
        }

    private fun check(floor: Double, what: String, pick: (ColorScheme) -> Pair<Color, Color>) {
        val bad = everyLook().mapNotNull { (label, s) ->
            val (fg, bg) = pick(s)
            val ratio = contrast(fg, bg)
            if (ratio < floor) "$label: $what is %.2f:1".format(ratio) else null
        }
        assertTrue(
            "below the $floor:1 floor —\n" + bad.joinToString("\n"),
            bad.isEmpty()
        )
    }

    // 4.5:1 is WCAG AA for body text. These are the pairs a kid actually reads:
    // a title on the page, a title on a card, and the meta line under it.
    @Test fun bodyTextClearsAaEverywhere() {
        check(4.5, "onBackground on background") { it.onBackground to it.background }
        check(4.5, "onSurface on surface") { it.onSurface to it.surface }
        check(4.5, "onSurfaceVariant on surface") { it.onSurfaceVariant to it.surface }
        check(4.5, "onSurface on the card step") { it.onSurface to it.surfaceContainerHighest }
        check(4.5, "onSurfaceVariant on the card step") {
            it.onSurfaceVariant to it.surfaceContainerHighest
        }
        check(4.5, "onSecondaryContainer on secondaryContainer") {
            it.onSecondaryContainer to it.secondaryContainer
        }
        check(4.5, "onPrimary on primary") { it.onPrimary to it.primary }
        check(4.5, "onPrimaryContainer on primaryContainer") {
            it.onPrimaryContainer to it.primaryContainer
        }
    }

    // 3:1 is the WCAG floor for a control boundary. The focus ring is drawn in
    // `primary` and is the only thing telling a kid where the remote is
    // pointing, so it is genuinely a control boundary and is held to that bar
    // against both grounds it can land on.
    @Test fun theFocusRingIsFindableFromTheSofa() {
        check(3.0, "the focus ring (primary) on background") { it.primary to it.background }
        check(3.0, "the focus ring (primary) on the card step") {
            it.primary to it.surfaceContainerHighest
        }
    }

    /**
     * `outline` is deliberately NOT held to 3:1. It is a hairline, not a
     * control boundary: a card is identified by its surface step (asserted in
     * [cardsSeparateFromTheGroundTheySitOn]), and the border only tidies the
     * edge. Holding it to 3:1 would mean bright grey rules around everything,
     * which is a different design from the one this app has.
     *
     * It still has to be *visible*, though — an outline that matches its
     * surface is a border someone thought they drew.
     */
    @Test fun hairlinesAreVisibleWithoutShouting() {
        check(1.25, "outline on surface") { it.outline to it.surface }
    }

    /**
     * The four signal colours have to carry text on every ground a kid can
     * produce — including the light theme, where the canonical coral starts at
     * 3.67:1 and has to be darkened, and including the eight tinted grounds.
     * This is the assertion that lets one canonical hue serve all three looks
     * instead of a second table that drifts.
     */
    @Test fun signalColoursCarryTextOnEveryGround() {
        val bad = everyLook().flatMap { (label, s) ->
            val t = io.yosemitekids.app.ui.kidTokensFor(s.background)
            listOf(
                "action" to t.action,
                "timeWarning" to t.timeWarning,
                "watched" to t.watched,
                "offline" to t.offline
            ).mapNotNull { (name, c) ->
                val r = contrast(c, s.background)
                if (r < 4.5) "$label: $name is %.2f:1".format(r) else null
            }
        }
        assertTrue("signal colours below 4.5:1 —\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /**
     * A glyph on the action colour is large text, so 3:1 — but it still has to
     * clear it, and it is fixed rather than derived, so one assertion covers
     * every look at once.
     */
    @Test fun theActionColourCarriesItsOwnGlyph() {
        val t = io.yosemitekids.app.ui.kidTokensFor(Color(0xFF141218))
        val r = contrast(t.onAction, t.action)
        assertTrue("onAction on action is %.2f:1, under 3:1".format(r), r >= 3.0)
    }

    /**
     * Perceptual distance in Oklab, where roughly 0.02 is the smallest
     * difference an eye catches and 0.10 is plainly a different colour.
     *
     * Deliberately not WCAG contrast: that is a luminance ratio, so two hues
     * darkened to the same lightness score 1.03:1 and look "converged" when
     * they are still obviously red and yellow. Asking the wrong question here
     * produced exactly that false alarm.
     */
    private fun perceptualDistance(a: Color, b: Color): Double {
        val space = androidx.compose.ui.graphics.colorspace.ColorSpaces.Oklab
        val x = a.convert(space)
        val y = b.convert(space)
        val dl = (x.red - y.red).toDouble()
        val da = (x.green - y.green).toDouble()
        val db = (x.blue - y.blue).toDouble()
        return Math.sqrt(dl * dl + da * da + db * db)
    }

    /**
     * The signals have to be told apart from each other, not just from the
     * page. "This is the button" and "your time is nearly up" in the same
     * colour is the specific failure that ruled out deriving the action colour
     * from the kid's own — a kid can pick amber, and coral and amber are
     * already neighbours before either gets darkened for the light theme.
     */
    @Test fun actionAndTimeWarningNeverConverge() {
        val muddy = everyLook().mapNotNull { (label, s) ->
            val t = io.yosemitekids.app.ui.kidTokensFor(s.background)
            val d = perceptualDistance(t.action, t.timeWarning)
            if (d < 0.06) "$label: action vs timeWarning is only %.3f apart".format(d) else null
        }
        assertTrue(
            "the action colour and the time warning have converged —\n" + muddy.joinToString("\n"),
            muddy.isEmpty()
        )
    }

    /**
     * The card steps have to actually step. If a surface and the card on it
     * land within a hair of each other the cards vanish, which is exactly what
     * a careless palette edit does and exactly what no assertion caught before.
     */
    @Test fun cardsSeparateFromTheGroundTheySitOn() {
        val flat = everyLook().mapNotNull { (label, s) ->
            val d = Math.abs(luminance(s.surfaceContainerHighest) - luminance(s.surface))
            if (d < 0.004) "$label: card and ground differ by %.4f".format(d) else null
        }
        assertTrue("cards vanish into the page —\n" + flat.joinToString("\n"), flat.isEmpty())
    }
}
