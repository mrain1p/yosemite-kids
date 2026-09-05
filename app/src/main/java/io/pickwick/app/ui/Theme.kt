package io.pickwick.app.ui

import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Pickwick brand: the logo's dark teal (#00695C), lightened to the tones a
 * dark theme needs — Material wants `primary` legible *on* the background, so
 * the logo colour itself only appears as a container/fill, never as text.
 */
val PickwickDarkColors = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    onPrimary = Color(0xFF00352F),
    primaryContainer = Color(0xFF00695C),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFB0CCC7),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E3),
    tertiary = Color(0xFFA5C8E4),
    // Not Material's "elevation tint" here: this app uses surfaceTint purely
    // as the carrier for the kid's own colour, and transparent means "no
    // wash". See [kidColorScheme] and [Modifier.kidBackdrop].
    surfaceTint = Color.Transparent
)

/** The three looks a kid can pick, in the order the hub shows them. */
const val THEME_DARK = "dark"
const val THEME_LIGHT = "light"
const val THEME_COLOR = "color"
val KID_THEMES = listOf(THEME_DARK, THEME_LIGHT, THEME_COLOR)

fun themeLabel(theme: String): String = when (theme) {
    THEME_LIGHT -> "Light"
    THEME_COLOR -> "My colour"
    else -> "Dark"
}

/** The daylight version of the same brand: paper surfaces, the teal kept for accents. */
val PickwickLightColors = androidx.compose.material3.lightColorScheme(
    primary = Color(0xFF00695C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB2DFDB),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E3),
    onSecondaryContainer = Color(0xFF06201C),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4947),
    outlineVariant = Color(0xFFBEC9C6),
    surfaceTint = Color.Transparent
)

/**
 * The scheme a kid is actually looking at: their pick, tinted with their own
 * colour when they chose "My colour". The tint only ever moves the accents —
 * primary, its container and the focus ring — so a restyle never costs
 * legibility, and the app still reads as Pickwick from across the room.
 */
fun kidColorScheme(
    profile: io.pickwick.app.data.Profile?,
    theme: String = THEME_DARK
): androidx.compose.material3.ColorScheme {
    val base = if (theme == THEME_LIGHT) PickwickLightColors else PickwickDarkColors
    // Dark and Light are the brand's own colours; only "My colour" borrows
    // the kid's. Their avatar keeps its colour either way — that is theirs.
    if (theme != THEME_COLOR) return base
    profile ?: return base
    val tint = Color(profile.colorArgb)
    val light = androidx.compose.ui.graphics.lerp(tint, Color.White, 0.30f)
    val deep = androidx.compose.ui.graphics.lerp(tint, Color.Black, 0.50f)
    // The ground moves toward their colour, but only just — a few percent.
    // A kid who picks hot pink wants a room that feels pink, not a hot-pink
    // wall behind white text: past about 10% the thumbnails start fighting
    // the background and every card needs its own outline to stay readable.
    // The rest of the effect is the wash in [kidBackdrop], which is a
    // gradient and therefore reads as light rather than as paint.
    fun ground(c: Color) = androidx.compose.ui.graphics.lerp(c, tint, 0.07f)
    val secondary = androidx.compose.ui.graphics.lerp(base.secondaryContainer, tint, 0.55f)
    return base.copy(
        primary = light,
        onPrimary = Color(0xFF1B1B1B),
        primaryContainer = deep,
        onPrimaryContainer = Color.White,
        background = ground(base.background),
        surface = ground(base.surface),
        // Cards sit on the tinted ground; left neutral they read as grey
        // patches on a coloured page.
        surfaceVariant = androidx.compose.ui.graphics.lerp(base.surfaceVariant, tint, 0.10f),
        // The bottom tab's selected pill and the settings chips are drawn from
        // this. Left on the brand teal they were the one green thing on an
        // otherwise pink page. Its label picks black or white by the blend's
        // own luminance, because a kid may pick pale yellow as readily as navy.
        secondaryContainer = secondary,
        onSecondaryContainer = if (secondary.luminance() > 0.5f) Color(0xFF1B1B1B) else Color.White,
        // Carries the kid's colour to [kidBackdrop]. Transparent on the two
        // brand themes, which is how the wash knows to stay off.
        surfaceTint = tint
    )
}

/**
 * The "My colour" wash: the kid's colour poured in from the top corners and
 * gone by halfway down, over the (barely) tinted ground from [kidColorScheme].
 *
 * A gradient rather than a fill on purpose. Filling the background with a
 * kid's chosen colour makes every thumbnail fight it and every card need an
 * outline; a wash that fades reads as *light in the room* instead, which is
 * the thing they actually asked for. Inert on Dark and Light, where
 * `surfaceTint` is transparent, so this can sit on the root unconditionally.
 */
@Composable
fun Modifier.kidBackdrop(): Modifier {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val tint = scheme.surfaceTint
    val base = this.background(scheme.background)
    if (tint.alpha == 0f) return base
    return base.drawWithCache {
        // Two soft corners rather than one flat band: a single vertical
        // gradient banded visibly on a dark ground, and the corners give
        // the page a light source instead of a horizon.
        val left = Brush.radialGradient(
            colors = listOf(tint.copy(alpha = 0.22f), Color.Transparent),
            center = Offset(0f, 0f),
            radius = size.minDimension * 1.4f
        )
        val right = Brush.radialGradient(
            colors = listOf(tint.copy(alpha = 0.14f), Color.Transparent),
            center = Offset(size.width, size.height * 0.12f),
            radius = size.minDimension * 1.2f
        )
        // A last breath at the bottom so the page doesn't just go flat.
        val floor = Brush.verticalGradient(
            0f to Color.Transparent,
            0.75f to Color.Transparent,
            1f to tint.copy(alpha = 0.10f)
        )
        onDrawBehind {
            drawRect(left)
            drawRect(right)
            drawRect(floor)
        }
    }
}

/**
 * One quiet type scale for every kid-facing screen. Material's defaults are
 * tuned for dense productivity apps; YouTube's shape is a single bold line
 * (the video title) with everything else a step or two quieter. Page
 * titles are titleLarge, sections titleMedium, tile titles titleSmall,
 * captions bodySmall — and nothing else on a screen competes with them.
 */
val PickwickTypography = androidx.compose.material3.Typography(
    headlineSmall = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp),
        lineHeight = androidx.compose.ui.unit.TextUnit(30f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp),
        lineHeight = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
    ),
    titleMedium = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp),
        lineHeight = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
    ),
    titleSmall = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp),
        lineHeight = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
    ),
    bodyLarge = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp),
        lineHeight = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)
    ),
    bodyMedium = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(15f, androidx.compose.ui.unit.TextUnitType.Sp),
        lineHeight = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp)
    ),
    bodySmall = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp),
        lineHeight = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp)
    ),
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp),
        lineHeight = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
    ),
    labelMedium = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp),
        lineHeight = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
    ),
    labelSmall = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
        lineHeight = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
    )
)

/**
 * "today", "3 days ago", "2 weeks ago" — the age of an upload for the meta
 * line under a title, the way every video app says it. Null when the cache
 * row predates the date column (nothing is shown rather than a guess).
 */
fun relativeAge(publishedAt: Long?, now: Long = System.currentTimeMillis()): String? {
    publishedAt ?: return null
    val days = ((now - publishedAt) / 86_400_000L).toInt()
    return when {
        days < 0 -> null
        days == 0 -> "today"
        days == 1 -> "yesterday"
        days < 7 -> "$days days ago"
        days < 30 -> "${days / 7} week${if (days / 7 == 1) "" else "s"} ago"
        days < 365 -> "${days / 30} month${if (days / 30 == 1) "" else "s"} ago"
        else -> "${days / 365} year${if (days / 365 == 1) "" else "s"} ago"
    }
}

/**
 * Watched/played progress. Deliberately not the brand teal: kids read this bar
 * by the same convention YouTube taught them, so it stays red everywhere it
 * appears (thumbnail bars and the player scrubber).
 */
val WatchedProgressRed = Color(0xFFFF0000)

/** The thumbnail-bottom watched bar, one spelling for every grid and row. */
@Composable
fun BoxScope.WatchedProgressBar(fraction: Float) {
    Box(
        Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .height(4.dp).background(Color(0x66FFFFFF))
    )
    Box(
        Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction)
            .height(4.dp).background(WatchedProgressRed)
    )
}

/** "h:mm:ss" over an hour, "m:ss" under — one spelling of a duration everywhere. */
fun formatClock(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

/**
 * "12 min left" / "1 h 5 min left" / "less than a minute left" — the one
 * spelling of screen time left, on the home header and in the player.
 */
fun remainingLabel(ms: Long): String {
    val min = (ms / 60_000L).toInt()
    return when {
        ms < 60_000L -> "less than a minute left"
        min >= 60 -> "${min / 60} h ${min % 60} min left"
        else -> "$min min left"
    }
}

/**
 * SponsorBlock-marked stretches on the player scrubber. Green by the same
 * borrowed-convention logic as [WatchedProgressRed]: SmartTube et al. taught
 * viewers that green-on-the-bar means "this part will be skipped".
 */
val SponsorSegmentGreen = Color(0xFF00C853)

/** The "newer build available" dot on the settings gear. */
val UpdateDot = Color(0xFFFF5252)

/** Status readouts in settings (search-index health, per-channel completeness):
 *  soft Material green/red — full-saturation traffic lights read as alarms. */
val StatusOkGreen = Color(0xFF81C784)
val StatusFailRed = Color(0xFFE57373)

/** "Needs a look, not an alarm": a device offline or behind on updates, the
 *  banner that counts them. Warm rather than red, because a TV that is
 *  switched off is the normal state of a TV. */
val StatusAmber = Color(0xFFE0B360)

/** The home screen's non-channel tiles — the phone grid and the TV row draw
 *  the same tiles, so their identity colors get one spelling here. */
val SurpriseTileCyan = Color(0xFF00ACC1)
val QueueTilePurple = Color(0xFF6A4FA3)
val WatchlistTileTeal = Color(0xFF00897B)
val WatchLaterTileTeal = Color(0xFF4DB6AC)
val DownloadsTileTeal = Color(0xFF00636E)

/** "1.5x" / "0.5x" / "FREE" — one shared spelling of a screen-time multiplier. */
fun timeMultiplierLabel(percent: Int): String = when (percent) {
    0 -> "FREE"
    else -> {
        val whole = percent / 100
        val frac = percent % 100
        if (frac == 0) "${whole}x" else "$whole.${"%02d".format(frac).trimEnd('0')}x"
    }
}

/** Chip color: green = cheaper than normal, amber = costs extra. Null at 1x. */
fun timeMultiplierColor(percent: Int): Color? = when {
    percent == 100 -> null
    percent > 100 -> Color(0xFFB26A00)
    else -> Color(0xFF2E7D32)
}

/**
 * Parent-facing settings/stats: the same teal, desaturated a step. Dense admin
 * forms shouldn't shout, and the muted variant still reads as "same app,
 * grown-up room" rather than switching brand mid-flow.
 */
val AdminDarkColors = darkColorScheme(
    primary = Color(0xFF7FBAB2),
    onPrimary = Color(0xFF00352F),
    primaryContainer = Color(0xFF23514B),
    onPrimaryContainer = Color(0xFFCCE8E3),
    secondary = Color(0xFFAEBFBC),
    onSecondary = Color(0xFF1C2A28),
    secondaryContainer = Color(0xFF3A4B49),
    onSecondaryContainer = Color(0xFFDCE7E5),
    tertiary = Color(0xFF9FC6C0)
)
