package io.yosemitekids.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The roles Material has no name for.
 *
 * Material 3 covers surfaces, text and one accent well, and says nothing about
 * the four things this app has to signal over and over: *press this*, *your
 * time is nearly up*, *you already watched this*, *this is here without a
 * network*. Those were being written as hex literals at the point of use,
 * which is why a restyle costs a month and why the amber warning colour
 * existed twice in two files at two different alphas.
 *
 * These are deliberately NOT derived from the kid's chosen colour. The kid's
 * colour owns the ground and their avatar; the signals stay fixed, because a
 * child who picks amber would otherwise have one hue meaning both "this is the
 * button" and "your time is nearly up" — and that is the one distinction in
 * the app that must never be ambiguous.
 */
data class KidTokens(
    /** The single action colour: play buttons, focus rings, active chips, links. */
    val action: Color,
    /** What sits on top of [action] — a glyph or a short label, never body text. */
    val onAction: Color,
    /** Time limits, blocked windows, the daily countdown. Never an action. */
    val timeWarning: Color,
    /** Finished. Also downloads that completed, which is the same "it's done". */
    val watched: Color,
    /** Offline, and the things that still work without a network. */
    val offline: Color,
    /** Over artwork: the wash that makes white text legible on any thumbnail. */
    val artworkScrim: Color,
    /** Text and glyphs drawn on artwork or on that scrim. */
    val onArtwork: Color
)

// The canonical hues. Each is stated once, at the value it takes on a dark
// ground, and then moved by [legibleOn] for whatever ground it actually lands
// on — which is what makes the light theme work without a second table to
// keep in sync.
private val ACTION = Color(0xFFE0533D)
private val TIME_WARNING = Color(0xFFD8A13A)
private val WATCHED = Color(0xFF47B877)
private val OFFLINE = Color(0xFF4A8B8D)

/**
 * Push [fg] away from [bg] until the pair clears [min]:1.
 *
 * The mirror of [legible], which moves the background instead. Used here so a
 * single canonical hue can serve both looks: coral reads at 4.85:1 on the dark
 * ground as drawn, and only 3.67:1 on paper, so on the light theme it is
 * darkened until it carries text.
 */
internal fun legibleOn(fg: Color, bg: Color, min: Float = 4.5f): Color {
    if (ratio(fg, bg) >= min) return fg
    val away = if (bg.luminance() > 0.5f) Color.Black else Color.White
    var t = 0.05f
    var out = fg
    while (t <= 1f) {
        out = androidx.compose.ui.graphics.lerp(fg, away, t)
        if (ratio(out, bg) >= min) return out
        t += 0.05f
    }
    return out
}

/**
 * The tokens for a given ground.
 *
 * Pure, and takes the background rather than a theme name, so it works for the
 * three looks and for the per-kid tinted grounds without knowing they exist —
 * and so a JVM test can assert every combination without a device.
 */
fun kidTokensFor(background: Color): KidTokens = KidTokens(
    // 4.5:1 because these carry text: "See all" is a link, and the section
    // count beside it is read, not just seen.
    action = legibleOn(ACTION, background),
    // 3:1 is the right bar here and not a concession: what sits on the action
    // colour is a play triangle or a one-word label at button size, which WCAG
    // treats as large text. Near-black rather than white because it is the
    // better of the two on coral (4.46:1 against 3.83:1) and because it is
    // what the design draws.
    onAction = readableOn(ACTION),
    timeWarning = legibleOn(TIME_WARNING, background),
    watched = legibleOn(WATCHED, background),
    offline = legibleOn(OFFLINE, background),
    // Not theme-dependent: a thumbnail is a photograph in both looks, and a
    // light scrim over a bright frame would fail exactly where it is needed.
    artworkScrim = Color(0xCC000000),
    onArtwork = Color.White
)

/**
 * The tokens for the look the kid is currently in.
 *
 * Reads the ground out of the ambient scheme, so it follows Dark, Light and
 * every per-kid tint without a provider to forget to install.
 */
val kidTokens: KidTokens
    @Composable get() {
        val bg = MaterialTheme.colorScheme.background
        return remember(bg) { kidTokensFor(bg) }
    }
