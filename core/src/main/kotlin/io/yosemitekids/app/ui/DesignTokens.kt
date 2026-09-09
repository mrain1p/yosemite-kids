package io.yosemitekids.app.ui

import kotlin.math.pow
import kotlin.math.sign

/**
 * The kid-facing palette and type scale, stated once, as plain numbers.
 *
 * **Why here and not in Theme.kt.** A browser is about to draw the same
 * screens the phone draws, and a colour that exists as `Color(0xFFE0533D)` in
 * a Compose file cannot be read by anything that is not Compose. Retyping it
 * into a stylesheet is how the amber warning came to exist twice at two
 * alphas — the same failure one module up. So the *values* live here, in
 * `:core`, where the app reads them through Compose wrappers and
 * `:hub:generateKidTokensCss` reads them to write the stylesheet. There is no
 * hand-written copy of that stylesheet; guard 48 makes sure there never is.
 *
 * Nothing here is Compose, Android, or a pixel. ARGB ints, packed exactly as
 * `Color(0xAARRGGBB)` packs them, and integer type sizes in sp.
 */

// --- colour arithmetic ------------------------------------------------------

/**
 * The colour maths the palette needs, on plain ARGB ints.
 *
 * A second implementation of what `androidx.compose.ui.graphics` already does,
 * and that is a thing to be uncomfortable about — so it is *pinned*:
 * `KidTokensParityTest` in `:app` asserts these agree with Compose, to the
 * byte, on both grounds the generated stylesheet covers. The app keeps using
 * Compose; this exists so a plain-JVM build step (and, later, the hub) can
 * arrive at the same answer without dragging the Android graphics stack into
 * a container.
 */
object Argb {

    private fun eotf(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun oetf(c: Double) = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055

    fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF
    fun red(argb: Int): Int = (argb ushr 16) and 0xFF
    fun green(argb: Int): Int = (argb ushr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    /** WCAG 2.1 relative luminance, 0..1. */
    fun luminance(argb: Int): Double =
        0.2126 * eotf(red(argb) / 255.0) +
            0.7152 * eotf(green(argb) / 255.0) +
            0.0722 * eotf(blue(argb) / 255.0)

    /** WCAG contrast between two opaque colours, 1:1 (identical) to 21:1. */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * Blend two colours the way Compose's `lerp(Color, Color, Float)` does:
     * in Oklab, not in sRGB and not in linear light.
     *
     * This is not a detail. A straight sRGB blend of coral toward black is a
     * visibly different colour from Compose's at the same fraction, and the
     * whole point of generating the stylesheet is that the browser gets the
     * shade the app draws rather than one that is nearly it.
     */
    fun mix(a: Int, b: Int, t: Double): Int {
        val x = toOklab(a)
        val y = toOklab(b)
        return fromOklab(
            x[0] + (y[0] - x[0]) * t,
            x[1] + (y[1] - x[1]) * t,
            x[2] + (y[2] - x[2]) * t
        )
    }

    private fun toOklab(argb: Int): DoubleArray {
        val r = eotf(red(argb) / 255.0)
        val g = eotf(green(argb) / 255.0)
        val b = eotf(blue(argb) / 255.0)
        val l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
        val m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
        val s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
        val lc = cbrt(l)
        val mc = cbrt(m)
        val sc = cbrt(s)
        return doubleArrayOf(
            0.2104542553 * lc + 0.7936177850 * mc - 0.0040720468 * sc,
            1.9779984951 * lc - 2.4285922050 * mc + 0.4505937099 * sc,
            0.0259040371 * lc + 0.7827717662 * mc - 0.8086757660 * sc
        )
    }

    private fun cbrt(v: Double) = sign(v) * kotlin.math.abs(v).pow(1.0 / 3.0)

    private fun fromOklab(lStar: Double, aStar: Double, bStar: Double): Int {
        val lc = lStar + 0.3963377774 * aStar + 0.2158037573 * bStar
        val mc = lStar - 0.1055613458 * aStar - 0.0638541728 * bStar
        val sc = lStar - 0.0894841775 * aStar - 1.2914855480 * bStar
        val l = lc * lc * lc
        val m = mc * mc * mc
        val s = sc * sc * sc
        val r = oetf(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s)
        val g = oetf(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s)
        val b = oetf(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)
        fun q(v: Double) = Math.round(v.coerceIn(0.0, 1.0) * 255.0).toInt()
        return (0xFF shl 24) or (q(r) shl 16) or (q(g) shl 8) or q(b)
    }

    /** Near-black or white, whichever can actually be read on [bg]. */
    fun readableOn(bg: Int): Int =
        if (ratio(KidHues.INK, bg) >= ratio(WHITE, bg)) KidHues.INK else WHITE

    /**
     * Push [fg] away from [bg] until the pair clears [min]:1.
     *
     * The same walk `KidTokens.legibleOn` does in the app, in the same 0.05
     * steps, so a canonical hue lands on the same shade on both faces.
     */
    fun legibleOn(fg: Int, bg: Int, min: Double = 4.5): Int {
        if (ratio(fg, bg) >= min) return fg
        val away = if (luminance(bg) > 0.5) BLACK else WHITE
        var t = 0.05f
        var out = fg
        while (t <= 1f) {
            out = mix(fg, away, t.toDouble())
            if (ratio(out, bg) >= min) return out
            t += 0.05f
        }
        return out
    }

    /**
     * Push [bg] away from [fg] until the pair clears [min]:1 — the same walk as
     * [legibleOn], in the other direction.
     *
     * Both exist because the two situations are genuinely different. A signal
     * hue landing on a fixed ground must move itself ([legibleOn]); a *fill*
     * the kid chose, carrying a label picked to suit it, has to move the fill —
     * moving the label would only ever pick the other of black and white, which
     * [readableOn] already rejected.
     *
     * The step is 0.06 rather than [legibleOn]'s 0.05 because that is what the
     * app has walked since the chip colours were fixed, and the two answers
     * differ by a shade at some tints. Held to Compose byte for byte by
     * `KidTokensParityTest`.
     */
    fun legibleGround(bg: Int, fg: Int, min: Double = 4.5): Int {
        if (ratio(fg, bg) >= min) return bg
        val away = if (luminance(fg) > 0.5) BLACK else WHITE
        var t = 0.06
        var out = bg
        while (t <= 1.0) {
            out = mix(bg, away, t)
            if (ratio(fg, out) >= min) return out
            t += 0.06
        }
        return out
    }

    val WHITE = 0xFFFFFFFF.toInt()
    val BLACK = 0xFF000000.toInt()
    const val TRANSPARENT = 0

    /**
     * `#rrggbb`, or `rgba(...)` when the colour is not opaque.
     *
     * `Locale.ROOT` and not the default: a build run on a machine set to a
     * comma-decimal locale would otherwise emit `rgba(0, 0, 0, 0,8)`, which a
     * browser discards silently — the scrim over every thumbnail would simply
     * not be there, on that one person's build.
     */
    fun css(argb: Int): String {
        val a = alpha(argb)
        if (a == 0xFF) {
            return String.format(java.util.Locale.ROOT, "#%02x%02x%02x", red(argb), green(argb), blue(argb))
        }
        val alphaText = String.format(java.util.Locale.ROOT, "%.3f", a / 255.0)
            .trimEnd('0').trimEnd('.')
        return "rgba(${red(argb)}, ${green(argb)}, ${blue(argb)}, $alphaText)"
    }
}

// --- the signal colours -----------------------------------------------------

/**
 * The canonical hues behind [KidTokens], each stated once at the value it
 * takes on a dark ground and moved by [Argb.legibleOn] for whatever ground it
 * actually lands on. Deliberately not derived from the kid's chosen colour —
 * see the note on `KidTokens` in `:app`.
 */
object KidHues {
    /** The single action colour: play buttons, focus rings, active chips, links. */
    val ACTION = 0xFFE0533D.toInt()
    /** Time limits, blocked windows, the daily countdown. Never an action. */
    val TIME_WARNING = 0xFFD8A13A.toInt()
    /** Finished. Also downloads that completed, which is the same "it's done". */
    val WATCHED = 0xFF47B877.toInt()
    /** Offline, and the things that still work without a network. */
    val OFFLINE = 0xFF4A8B8D.toInt()
    /** Over artwork: the wash that makes white text legible on any thumbnail. */
    val ARTWORK_SCRIM = 0xCC000000.toInt()
    /** Text and glyphs drawn on artwork or on that scrim. */
    val ON_ARTWORK = 0xFFFFFFFF.toInt()
    /** Near-black. What sits on a light fill when white would not read. */
    val INK = 0xFF1B1B1B.toInt()
}

/**
 * The four fixed marks that are not part of either look.
 *
 * The brand teal is the one thing every look keeps; the progress red and the
 * sponsor green are borrowed conventions a viewer already knows and must not
 * be re-hued by a restyle.
 */
object KidBrand {
    val TEAL = 0xFF00695C.toInt()
    val ON_TEAL = 0xFFFFFFFF.toInt()
    /** Watched/played progress — YouTube's convention, deliberately not the teal. */
    val WATCHED_PROGRESS = 0xFFFF0000.toInt()

    /**
     * The unplayed remainder of that bar: 40% white over the poster.
     *
     * Here, and not a literal in `WatchedProgressBar`, because the browser had
     * no way to know it. Left unexported, the page reached for the nearest role
     * it could see — `artwork-scrim`, which is 80% **black** — so the same bar
     * was pale over the thumbnail on a television and dark on a tablet. That is
     * the exact failure `KidGeometry`'s KDoc describes, in a colour rather than
     * a length, and the only fix is for the value to have a name.
     *
     * White rather than a theme role on purpose: it sits on a photograph, which
     * is neither light nor dark, and a scrim that followed the look would fail
     * on exactly the frames it exists for.
     */
    val WATCHED_TRACK = 0x66FFFFFF
    /** SponsorBlock-marked stretches on the player scrubber. */
    val SPONSOR_SEGMENT = 0xFF00C853.toInt()
}

/**
 * The tokens a given ground produces: the signal colours moved until they
 * carry text on it, plus the two that never move.
 *
 * Ordered, and named the way the stylesheet names them, so the generator has
 * no table of its own to keep in step.
 */
fun kidTokenRoles(background: Int): List<Pair<String, Int>> = listOf(
    // 4.5:1 because these carry text: "See all" is a link, and the section
    // count beside it is read, not just seen.
    "action" to Argb.legibleOn(KidHues.ACTION, background),
    // 3:1 is the right bar for what sits ON the action colour — a play
    // triangle or a one-word label at button size, which WCAG treats as large
    // text. Near-black rather than white because it is the better of the two
    // on coral, and because it is what the design draws.
    "on-action" to Argb.readableOn(KidHues.ACTION),
    "time-warning" to Argb.legibleOn(KidHues.TIME_WARNING, background),
    "watched" to Argb.legibleOn(KidHues.WATCHED, background),
    "offline" to Argb.legibleOn(KidHues.OFFLINE, background),
    // Not theme-dependent: a thumbnail is a photograph in both looks, and a
    // light scrim over a bright frame would fail exactly where it is needed.
    "artwork-scrim" to KidHues.ARTWORK_SCRIM,
    "on-artwork" to KidHues.ON_ARTWORK
)

// --- the two looks ----------------------------------------------------------

/**
 * One look's Material roles, as ARGB ints.
 *
 * [roles] is the whole of it in order, and guard 48(d) counts the two against
 * each other: a role added to the class and not to the list would simply be
 * missing from the browser, silently, which is the failure this file exists
 * to stop.
 */
data class KidScheme(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    /** Dark names one; the light look leaves Material's baseline alone. */
    val tertiary: Int?,
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceContainerLowest: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
    val surfaceTint: Int
) {
    fun roles(): List<Pair<String, Int>> = listOfNotNull(
        "primary" to primary,
        "on-primary" to onPrimary,
        "primary-container" to primaryContainer,
        "on-primary-container" to onPrimaryContainer,
        "secondary" to secondary,
        "on-secondary" to onSecondary,
        "secondary-container" to secondaryContainer,
        "on-secondary-container" to onSecondaryContainer,
        tertiary?.let { "tertiary" to it },
        "background" to background,
        "on-background" to onBackground,
        "surface" to surface,
        "on-surface" to onSurface,
        "surface-container-lowest" to surfaceContainerLowest,
        "surface-container-low" to surfaceContainerLow,
        "surface-container" to surfaceContainer,
        "surface-container-high" to surfaceContainerHigh,
        "surface-container-highest" to surfaceContainerHighest,
        "surface-variant" to surfaceVariant,
        "on-surface-variant" to onSurfaceVariant,
        "outline" to outline,
        "outline-variant" to outlineVariant,
        "surface-tint" to surfaceTint
    )
}

/**
 * Yosemite Kids brand: the logo's dark teal (#00695C), lightened to the tones
 * a dark theme needs — Material wants `primary` legible *on* the background,
 * so the logo colour itself only appears as a container/fill, never as text.
 *
 * The ground is Material's own dark baseline, kept to the byte: it is what
 * families are looking at today and it is not the thing that needed fixing.
 * The steps above it are ours rather than Material's, which is what lets the
 * kid's colour tint every card, chip and tab pill instead of only the page.
 *
 * `outline` is a card or field border and `outlineVariant` the divider
 * between rows inside one — deliberately darker, so a card reads as a single
 * block. `surfaceTint` is transparent because this app uses it purely as the
 * carrier for the kid's own colour, and transparent means "no wash".
 */
val KID_DARK = KidScheme(
    primary = 0xFF4DB6AC.toInt(),
    onPrimary = 0xFF00352F.toInt(),
    primaryContainer = 0xFF00695C.toInt(),
    onPrimaryContainer = 0xFFB2DFDB.toInt(),
    secondary = 0xFFB0CCC7.toInt(),
    onSecondary = 0xFF1C3531.toInt(),
    secondaryContainer = 0xFF334B47.toInt(),
    onSecondaryContainer = 0xFFCCE8E3.toInt(),
    tertiary = 0xFFA5C8E4.toInt(),
    background = 0xFF141218.toInt(),
    onBackground = 0xFFE6E0E9.toInt(),
    surface = 0xFF141218.toInt(),
    onSurface = 0xFFE6E0E9.toInt(),
    surfaceContainerLowest = 0xFF0E0D11.toInt(),
    surfaceContainerLow = 0xFF1A1820.toInt(),
    surfaceContainer = 0xFF1E1C25.toInt(),
    surfaceContainerHigh = 0xFF26232D.toInt(),
    surfaceContainerHighest = 0xFF2E2A36.toInt(),
    surfaceVariant = 0xFF2E2A36.toInt(),
    onSurfaceVariant = 0xFFB4AEBD.toInt(),
    outline = 0xFF38333F.toInt(),
    outlineVariant = 0xFF272430.toInt(),
    surfaceTint = Argb.TRANSPARENT
)

/**
 * The daylight version of the same brand: paper surfaces, the teal kept for
 * accents, and the same named steps going the other way — paper, then
 * progressively less of it.
 */
val KID_LIGHT = KidScheme(
    primary = 0xFF00695C.toInt(),
    onPrimary = Argb.WHITE,
    primaryContainer = 0xFFB2DFDB.toInt(),
    onPrimaryContainer = 0xFF00201C.toInt(),
    secondary = 0xFF4A635F.toInt(),
    onSecondary = Argb.WHITE,
    secondaryContainer = 0xFFCCE8E3.toInt(),
    onSecondaryContainer = 0xFF06201C.toInt(),
    tertiary = null,
    background = 0xFFFAFAFA.toInt(),
    onBackground = 0xFF191C1B.toInt(),
    surface = 0xFFFAFAFA.toInt(),
    onSurface = 0xFF191C1B.toInt(),
    surfaceContainerLowest = 0xFFFFFFFF.toInt(),
    surfaceContainerLow = 0xFFF3F5F4.toInt(),
    surfaceContainer = 0xFFEDF0EF.toInt(),
    surfaceContainerHigh = 0xFFE7EBEA.toInt(),
    surfaceContainerHighest = 0xFFE1E6E5.toInt(),
    surfaceVariant = 0xFFDAE5E1.toInt(),
    onSurfaceVariant = 0xFF3F4947.toInt(),
    outline = 0xFF6F7B78.toInt(),
    outlineVariant = 0xFFBEC9C6.toInt(),
    surfaceTint = Argb.TRANSPARENT
)

// --- geometry ---------------------------------------------------------------

/**
 * The shapes and distances a card is made of — the second half of "one table",
 * and the half that was missing.
 *
 * ### Why this exists, stated as the failure it was found by
 *
 * The palette and the type scale have been shared since `KidTokensCss` was
 * written, and the browser still did not look like the app. Every geometry
 * number was an `:app` literal with no token behind it and an independent
 * literal in `kid.html`, so the two agreed only in the sense that nobody had
 * put the screens side by side. When somebody did, on the day the web player
 * shipped, they had already diverged in six places at once: the card corner was
 * 14dp against 16px, the poster 12dp against a card clipped flush, the duration
 * badge 4dp against 6px, and the progress track — most visibly — 40% WHITE in
 * the app against 80% BLACK in the browser.
 *
 * None of that failed a test, and none of it could: both faces were using legal
 * tokens for the colours and no token at all for the shapes. Guard 62 now
 * refuses a bare length in either face's card code, and a `KidGeometryParityTest`
 * pins these numbers to the Compose `dp` constants the way `KidTokensParityTest`
 * pins the palette.
 *
 * ### The units
 *
 * Plain `Int`s, read as **dp on Android and px in a browser**, which is the same
 * identity `KidType` already relies on: a dp at a density of 1 is a CSS pixel,
 * and both faces scale from there. A number here is therefore a promise about
 * apparent size, not about a device pixel.
 *
 * ### What belongs here, and what does not
 *
 * A number belongs here when **both faces draw the same object with it** — a
 * card corner, a badge inset, the gap in a rail. It does not belong here when
 * it is one platform's own: the 44dp minimum tap target is Android's guideline
 * and iOS's is 44pt by coincidence rather than by sharing, and the TV's larger
 * variants are a form-factor decision `FormFactor` already owns. Those stay
 * where they are, and this file stays the things that must match.
 */
object KidGeometry {

    /** The card's own corner. The column, not the poster inside it. */
    const val CARD_RADIUS = 14

    /** The poster's corner, deliberately tighter than the card's. */
    const val POSTER_RADIUS = 12

    /** Below the card, between it and the next row. */
    const val CARD_BOTTOM_PAD = 6

    /** The duration badge: its corner, how far off the poster's edge it sits, and its padding. */
    const val BADGE_RADIUS = 4
    const val BADGE_INSET = 6
    const val BADGE_PAD_X = 5
    const val BADGE_PAD_Y = 2

    /** The watched bar across the bottom of a poster. */
    const val PROGRESS_HEIGHT = 4

    /**
     * How far back a finished card sits.
     *
     * Far enough that "seen it" reads at a glance, near enough that it is still
     * browsable — **kids rewatch**, which is why this is a dim and not a
     * removal. Expressed in percent because a CSS custom property cannot be a
     * bare float and a guard that greps for `0.48` should find one spelling.
     */
    const val WATCHED_DIM_PERCENT = 48

    /** The title row's offset from the poster, and its side inset. */
    const val META_TOP = 8
    const val META_SIDE = 2

    /** The channel face beside a title. Drawn size; the tap target around it is the platform's. */
    const val AVATAR_SIZE = 34

    /** Between a card's title and the channel name under it. */
    const val SUB_GAP = 2

    /** The meta line's own corner — it is a tap target for the channel. */
    const val META_RADIUS = 6

    /** A horizontally-scrolling shelf: the gap between cards, and a card's width. */
    const val RAIL_GAP = 10
    const val RAIL_CARD_WIDTH = 200

    /**
     * The pinned hero — the biggest thing on the home screen.
     *
     * The **phone** figures, deliberately. The hero is one of the few surfaces
     * whose metrics genuinely differ by form factor (the television's card is
     * 348dp wide against the phone's 258dp), and a browser on an iPad is a
     * phone-shaped face rather than a ten-foot one. Taking the phone's numbers
     * is a decision, not a default: the TV's live in `HomeMetrics`, which is
     * where form-factor variance belongs.
     */
    const val HERO_GAP = 15
    const val HERO_WIDTH = 258

    /**
     * The video grid. The vertical gap is deliberately half the horizontal one:
     * cards carry their titles underneath, so the visual gap between rows is
     * already larger than the number says.
     */
    const val GRID_MIN_WIDTH = 170
    const val GRID_GAP_X = 12
    const val GRID_GAP_Y = 6

    /** The page's own side margin. */
    const val PAGE_GUTTER = 16

    /**
     * Every one of them, named the way the stylesheet names them, so the
     * generator has no table of its own to keep in step — the same contract
     * [kidTokenRoles] has for colour.
     */
    fun roles(): List<Pair<String, Int>> = listOf(
        "card-radius" to CARD_RADIUS,
        "poster-radius" to POSTER_RADIUS,
        "card-bottom-pad" to CARD_BOTTOM_PAD,
        "badge-radius" to BADGE_RADIUS,
        "badge-inset" to BADGE_INSET,
        "badge-pad-x" to BADGE_PAD_X,
        "badge-pad-y" to BADGE_PAD_Y,
        "progress-height" to PROGRESS_HEIGHT,
        "meta-top" to META_TOP,
        "meta-side" to META_SIDE,
        "sub-gap" to SUB_GAP,
        "meta-radius" to META_RADIUS,
        "avatar-size" to AVATAR_SIZE,
        "rail-gap" to RAIL_GAP,
        "rail-card-width" to RAIL_CARD_WIDTH,
        "hero-gap" to HERO_GAP,
        "hero-width" to HERO_WIDTH,
        "grid-min-width" to GRID_MIN_WIDTH,
        "grid-gap-x" to GRID_GAP_X,
        "grid-gap-y" to GRID_GAP_Y,
        "page-gutter" to PAGE_GUTTER
    )
}

// --- "My colour" ------------------------------------------------------------

/**
 * A kid's own colour, poured into a look — the whole of the "My colour" theme,
 * in one pure function over ARGB ints.
 *
 * **It lives here because the browser needs it too.** `KidTokensCss` says as
 * much in its own KDoc: the generated stylesheet can carry the two brand looks
 * because they are constants, but the per-kid ground is chosen at runtime and
 * no build-time table can enumerate it. The hub therefore computes a kid's
 * tokens for that kid and sends them as inline custom properties — and the one
 * thing it must not do is re-derive the blend, because a second implementation
 * of "7% toward their colour" is two rooms that are almost the same shade.
 *
 * `Theme.kidColorScheme` calls this and converts the answer to Compose
 * `Color`s; `KidTokensParityTest` holds the two together byte for byte, which
 * is what makes "the browser shows what the app shows" a checked claim rather
 * than an intention.
 *
 * Every fraction below is the one the app has always used, and the comments
 * are the reasons they are those fractions rather than larger ones.
 */
fun kidTinted(base: KidScheme, tint: Int): KidScheme {
    val light = Argb.mix(tint, Argb.WHITE, 0.30)
    val deep = Argb.mix(tint, Argb.BLACK, 0.50)
    // The ground moves toward their colour, but only just — a few percent. A
    // kid who picks hot pink wants a room that feels pink, not a hot-pink wall
    // behind white text: past about 10% the thumbnails start fighting the
    // background and every card needs its own outline to stay readable.
    fun ground(c: Int) = Argb.mix(c, tint, 0.07)
    // Cards carry a touch more of the kid's colour than the page behind them,
    // which is what keeps them legible as separate objects once the ground is
    // tinted — the alternative is outlining every card, which the wash was
    // chosen to avoid.
    fun card(c: Int) = Argb.mix(c, tint, 0.10)
    // The bottom tab's selected pill and the settings chips are drawn from
    // this. Left on the brand teal they were the one green thing on an
    // otherwise pink page. Its label picks whichever of black and white reads
    // better on the blend, and the blend then moves until that label clears
    // 4.5:1 — a kid may pick pale yellow as readily as navy, and amber landed
    // at 3.39:1 before the walk was doing this.
    val secondaryRaw = Argb.mix(base.secondaryContainer, tint, 0.55)
    val onSecondary = Argb.readableOn(secondaryRaw)
    return base.copy(
        primary = light,
        onPrimary = KidHues.INK,
        primaryContainer = deep,
        onPrimaryContainer = Argb.WHITE,
        background = ground(base.background),
        surface = ground(base.surface),
        // Cards sit on the tinted ground; left neutral they read as grey
        // patches on a coloured page. This is every step a card, chip, sheet or
        // tab pill can be drawn from, so tinting only surfaceVariant left all
        // three grey and the wash looking like a mistake.
        surfaceVariant = card(base.surfaceVariant),
        surfaceContainerLowest = card(base.surfaceContainerLowest),
        surfaceContainerLow = card(base.surfaceContainerLow),
        surfaceContainer = card(base.surfaceContainer),
        surfaceContainerHigh = card(base.surfaceContainerHigh),
        surfaceContainerHighest = card(base.surfaceContainerHighest),
        // The borders travel with the surfaces they outline, or a tinted card
        // ends up ringed in grey.
        outline = card(base.outline),
        outlineVariant = card(base.outlineVariant),
        secondaryContainer = Argb.legibleGround(secondaryRaw, onSecondary),
        onSecondaryContainer = onSecondary,
        // Carries the kid's colour to the backdrop wash. Transparent on the two
        // brand looks, which is how the wash knows to stay off.
        surfaceTint = tint
    )
}

// --- the type scale ---------------------------------------------------------

/**
 * One step of the type scale. [weight] is null where the style takes the
 * font's own — the three body styles do, and writing 400 there would be a
 * different TextStyle from the one the app ships.
 */
data class TypeStyle(
    /** The CSS spelling: `--yk-font-title-large-size` and friends. */
    val css: String,
    val sizeSp: Int,
    val lineHeightSp: Int,
    val weight: Int?
)

/**
 * One quiet type scale for every kid-facing screen. Material's defaults are
 * tuned for dense productivity apps; YouTube's shape is a single bold line
 * (the video title) with everything else a step or two quieter. Page titles
 * are titleLarge, sections titleMedium, tile titles titleSmall, captions
 * bodySmall — and nothing else on a screen competes with them.
 *
 * [all] is what the stylesheet is written from; guard 48(d) counts the styles
 * declared here against the ones it lists.
 */
object KidType {
    val headlineSmall = TypeStyle("headline-small", 24, 30, 700)
    val titleLarge = TypeStyle("title-large", 22, 28, 700)
    val titleMedium = TypeStyle("title-medium", 17, 22, 600)
    val titleSmall = TypeStyle("title-small", 15, 20, 500)
    val bodyLarge = TypeStyle("body-large", 16, 22, null)
    val bodyMedium = TypeStyle("body-medium", 15, 20, null)
    val bodySmall = TypeStyle("body-small", 13, 17, null)
    val labelLarge = TypeStyle("label-large", 14, 18, 500)
    val labelMedium = TypeStyle("label-medium", 13, 16, 500)
    val labelSmall = TypeStyle("label-small", 11, 14, 500)

    val all: List<TypeStyle> = listOf(
        headlineSmall, titleLarge, titleMedium, titleSmall,
        bodyLarge, bodyMedium, bodySmall,
        labelLarge, labelMedium, labelSmall
    )
}
