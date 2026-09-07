package io.yosemitekids.app.ui

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
 * Yosemite Kids brand: the logo's dark teal (#00695C), lightened to the tones a
 * dark theme needs — Material wants `primary` legible *on* the background, so
 * the logo colour itself only appears as a container/fill, never as text.
 */
val YosemiteDarkColors = darkColorScheme(
    primary = Color(0xFF4DB6AC),
    onPrimary = Color(0xFF00352F),
    primaryContainer = Color(0xFF00695C),
    onPrimaryContainer = Color(0xFFB2DFDB),
    secondary = Color(0xFFB0CCC7),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCCE8E3),
    tertiary = Color(0xFFA5C8E4),
    // The ground is Material's own dark baseline, kept to the byte: it is what
    // families are looking at today and it is not the thing that needed fixing.
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    // The steps above the ground, which previously were *not* ours — they fell
    // through to Material's baseline, so every card, chip and tab pill was a
    // stock purple-grey the theme had no say in. Naming them is what lets
    // [kidColorScheme] tint them; until it could, "My colour" washed the page
    // and left the cards on it grey.
    surfaceContainerLowest = Color(0xFF0E0D11),
    surfaceContainerLow = Color(0xFF1A1820),
    surfaceContainer = Color(0xFF1E1C25),
    surfaceContainerHigh = Color(0xFF26232D),
    surfaceContainerHighest = Color(0xFF2E2A36),
    surfaceVariant = Color(0xFF2E2A36),
    onSurfaceVariant = Color(0xFFB4AEBD),
    // Two greys one step apart, and which is which matters: `outline` is a
    // card or field border, `outlineVariant` the divider between rows inside
    // one — deliberately darker, so a card reads as a single block.
    outline = Color(0xFF38333F),
    outlineVariant = Color(0xFF272430),
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
val YosemiteLightColors = androidx.compose.material3.lightColorScheme(
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
    // The same steps the dark scheme now names, going the other way: paper,
    // then progressively less of it. Left at Material's baseline these were
    // the light theme's version of the same bug — stock greys the theme did
    // not choose and "My colour" could not reach.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F5F4),
    surfaceContainer = Color(0xFFEDF0EF),
    surfaceContainerHigh = Color(0xFFE7EBEA),
    surfaceContainerHighest = Color(0xFFE1E6E5),
    outline = Color(0xFF6F7B78),
    outlineVariant = Color(0xFFBEC9C6),
    surfaceTint = Color.Transparent
)

/** WCAG contrast between two opaque colours, 1:1 (identical) to 21:1. */
internal fun ratio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** Near-black or white, whichever can actually be read on [bg]. */
internal fun readableOn(bg: Color): Color {
    val ink = Color(0xFF1B1B1B)
    return if (ratio(ink, bg) >= ratio(Color.White, bg)) ink else Color.White
}

/**
 * Darken or lighten [bg] until [fg] clears [min]:1 against it.
 *
 * A kid may pick any colour — the picker offers eight but any ARGB survives
 * sync — and some of them land in the band where neither black nor white is
 * legible on the blend. Amber is the one in the shipped palette: as a chip
 * fill it reached 3.39:1, under the 4.5:1 a label needs. Rather than drop the
 * swatch, the blend gets moved until the label works, which also covers the
 * colours a parent can set that the picker never offers.
 */
internal fun legible(bg: Color, fg: Color, min: Float = 4.5f): Color {
    if (ratio(fg, bg) >= min) return bg
    val away = if (fg.luminance() > 0.5f) Color.Black else Color.White
    var t = 0.06f
    var out = bg
    while (t <= 1f) {
        out = androidx.compose.ui.graphics.lerp(bg, away, t)
        if (ratio(fg, out) >= min) return out
        t += 0.06f
    }
    return out
}

/**
 * The scheme a kid is actually looking at: their pick, tinted with their own
 * colour when they chose "My colour". The tint only ever moves the accents —
 * primary, its container and the focus ring — so a restyle never costs
 * legibility, and the app still reads as Yosemite Kids from across the room.
 */
fun kidColorScheme(
    profile: io.yosemitekids.app.data.Profile?,
    theme: String = THEME_DARK
): androidx.compose.material3.ColorScheme {
    val base = if (theme == THEME_LIGHT) YosemiteLightColors else YosemiteDarkColors
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
    // Cards carry a touch more of the kid's colour than the page behind them,
    // which is what keeps them legible as separate objects once the ground is
    // tinted — the alternative is outlining every card, which the wash was
    // chosen to avoid.
    fun card(c: Color) = androidx.compose.ui.graphics.lerp(c, tint, 0.10f)
    val secondaryRaw = androidx.compose.ui.graphics.lerp(base.secondaryContainer, tint, 0.55f)
    val onSecondary = readableOn(secondaryRaw)
    val secondary = legible(secondaryRaw, onSecondary)
    return base.copy(
        primary = light,
        onPrimary = Color(0xFF1B1B1B),
        primaryContainer = deep,
        onPrimaryContainer = Color.White,
        background = ground(base.background),
        surface = ground(base.surface),
        // Cards sit on the tinted ground; left neutral they read as grey
        // patches on a coloured page. This is every step a card, chip, sheet
        // or tab pill can be drawn from — a filled Card takes
        // surfaceContainerHighest, a chip surfaceContainerHigh and the tab bar
        // surfaceContainer, so tinting only surfaceVariant (as this did) left
        // all three grey and the wash looking like a mistake.
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
        // The bottom tab's selected pill and the settings chips are drawn from
        // this. Left on the brand teal they were the one green thing on an
        // otherwise pink page. Its label picks whichever of black and white
        // reads better on the blend, and the blend then moves until that label
        // clears 4.5:1 — a kid may pick pale yellow as readily as navy, and
        // amber landed at 3.39:1 before [legible] was doing this.
        secondaryContainer = secondary,
        onSecondaryContainer = onSecondary,
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
val YosemiteTypography = androidx.compose.material3.Typography(
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
 * The same number, short enough to ride a top bar: "20m", "1h 5m", "<1m".
 *
 * [remainingLabel] is a sentence, and a sentence in the header pill cost the
 * page title about half its width on a 380 dp phone — the title was ellipsing
 * to "Hi, Ame…" so the chip could say "left". The long form is still what the
 * player says and still what a screen reader reads out; this is only the
 * glanceable spelling.
 */
fun remainingShort(ms: Long): String {
    val min = (ms / 60_000L).toInt()
    return when {
        ms < 60_000L -> "<1m"
        min >= 60 -> "${min / 60}h ${min % 60}m"
        else -> "${min}m"
    }
}

/**
 * "142 MB". The parent's storage list and the kid's Downloads row say the
 * same number in the same words — it moved here the moment the second caller
 * appeared, rather than being written twice with two different idea of what a
 * megabyte is.
 *
 * SI, not binary, because that is what a phone's own storage screen says and
 * the number is going to be compared with it.
 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    else -> "%.0f kB".format(bytes / 1_000.0)
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

/**
 * "Needs you": the review banner and a device behind on updates. Amber rather
 * than the brand teal — teal is reserved for things a parent can press, so a
 * warning in teal reads as a button and a button in amber reads as a warning.
 */
val WarningAmber = Color(0xFFE0B77E)
val WarningAmberSurface = Color(0xFF221D14)
val WarningAmberBorder = Color(0xFF5A4A30)

/** Text ON the amber fill — the count badge on a "waiting for you" card. */
val WarningAmberOn = Color(0xFF231B10)

/**
 * The parent-settings palette, for the roles Material has no slot for.
 * Everything else in the design's token table
 * (docs/design/parent-settings/README.md) lands on a [AdminDarkColors] role;
 * these five do not, and colours live in this file only.
 */
/** Body and feed lines — a step brighter than a label. */
val SettingsTextSecondary = Color(0xFFA5A1AD)
/** Labels and summaries. Same value as `onSurfaceVariant`, named where a
 *  composable needs to say which of the two greys it means. */
val SettingsTextTertiary = Color(0xFF8B8794)
/** The faintest tone: counts, chevrons, asides, the footer. */
val SettingsPlaceholder = Color(0xFF6D6979)
/** Buttons, chips and the **?** ring — a step above a card border. */
val SettingsStrongBorder = Color(0xFF3A3744)
/** "Everything is fine" in the parent settings — greener than [StatusOkGreen],
 *  which stays as it is for the search-index readouts. */
val SettingsSuccess = Color(0xFF7FC8A9)
/** A selected chip's fill: the accent at 16% over the card. */
val SettingsAccentTint = Color(0x298FCFBE)
/** The neutral square an avatar is previewed on while it is being picked —
 *  the kid's own colour would make the grid a colour picker twice over. */
val AvatarTile = Color(0xFF25242C)

/**
 * The device chips on Devices & sync: HUB, PARENT, TV, TABLET.
 *
 * Their own fills, not Material's roles. The parent chip had been borrowing
 * `colorScheme.error`, which is the colour this app uses to say something is
 * wrong — so the phone a parent administers the family from read, at a
 * glance, as a fault. These are the design's: warm rather than alarming, and
 * each dark enough that the small capitals sit on it without vibrating.
 */
val ChipHubSurface = Color(0xFF1E2A26)
val ChipParentSurface = Color(0xFF2E1A1C)
val ChipParentText = Color(0xFFE38C7E)
val ChipNeutralSurface = Color(0xFF1C1B21)

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
 * Parent-facing settings/stats: the palette of the settings design handoff
 * (docs/design/parent-settings/README.md), not a tint of the brand teal.
 *
 * A near-black page under cards one step lighter, one grey for every label and
 * summary, and teal kept for interactive text and the primary button — nav
 * icons are neutral, so amber still reads as a warning rather than as a
 * button. The previous scheme desaturated the teal and left every surface,
 * border and grey at Material's defaults, which is why cards, dividers and
 * secondary text all sat a full step lighter than the design.
 */
val AdminDarkColors = darkColorScheme(
    primary = Color(0xFF8FCFBE),
    onPrimary = Color(0xFF0F2A24),
    primaryContainer = Color(0xFF23514B),
    onPrimaryContainer = Color(0xFFCCE8E3),
    secondary = Color(0xFFAEBFBC),
    onSecondary = Color(0xFF1C2A28),
    secondaryContainer = Color(0xFF3A4B49),
    onSecondaryContainer = Color(0xFFDCE7E5),
    tertiary = Color(0xFF9FC6C0),
    // The page, not a card: SettingsFlow's root Surface paints `surface`, and
    // every card names surfaceContainer so it sits a step above the page.
    background = Color(0xFF101014),
    onBackground = Color(0xFFEDEBF0),
    surface = Color(0xFF101014),
    onSurface = Color(0xFFEDEBF0),
    surfaceContainerLow = Color(0xFF17161C),
    surfaceContainer = Color(0xFF17161C),
    surfaceContainerHigh = Color(0xFF1E1D24),
    surfaceVariant = Color(0xFF25242C),
    onSurfaceVariant = Color(0xFF8B8794),
    // Two greys one step apart, and which is which matters: `outline` is a
    // card or field border, `outlineVariant` the divider between two rows
    // inside one — deliberately darker, so a card reads as one block.
    outline = Color(0xFF2E2B36),
    outlineVariant = Color(0xFF252430),
    error = Color(0xFFE38C7E)
)
