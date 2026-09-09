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
 *
 * The numbers are in `:core`'s [KID_DARK], not here, because the browser is
 * about to draw the same screens and cannot read a Compose file. This is the
 * Android binding of that table and nothing else — the reasoning for each
 * value lives beside the value. Guard 48 fails if a hex literal comes back.
 */
val YosemiteDarkColors = darkColorScheme(
    primary = Color(KID_DARK.primary),
    onPrimary = Color(KID_DARK.onPrimary),
    primaryContainer = Color(KID_DARK.primaryContainer),
    onPrimaryContainer = Color(KID_DARK.onPrimaryContainer),
    secondary = Color(KID_DARK.secondary),
    onSecondary = Color(KID_DARK.onSecondary),
    secondaryContainer = Color(KID_DARK.secondaryContainer),
    onSecondaryContainer = Color(KID_DARK.onSecondaryContainer),
    tertiary = Color(KID_DARK.tertiary!!),
    background = Color(KID_DARK.background),
    onBackground = Color(KID_DARK.onBackground),
    surface = Color(KID_DARK.surface),
    onSurface = Color(KID_DARK.onSurface),
    // The steps above the ground, which previously were *not* ours — they fell
    // through to Material's baseline, so every card, chip and tab pill was a
    // stock purple-grey the theme had no say in. Naming them is what lets
    // [kidColorScheme] tint them; until it could, "My colour" washed the page
    // and left the cards on it grey.
    surfaceContainerLowest = Color(KID_DARK.surfaceContainerLowest),
    surfaceContainerLow = Color(KID_DARK.surfaceContainerLow),
    surfaceContainer = Color(KID_DARK.surfaceContainer),
    surfaceContainerHigh = Color(KID_DARK.surfaceContainerHigh),
    surfaceContainerHighest = Color(KID_DARK.surfaceContainerHighest),
    surfaceVariant = Color(KID_DARK.surfaceVariant),
    onSurfaceVariant = Color(KID_DARK.onSurfaceVariant),
    outline = Color(KID_DARK.outline),
    outlineVariant = Color(KID_DARK.outlineVariant),
    // Not Material's "elevation tint" here: this app uses surfaceTint purely
    // as the carrier for the kid's own colour, and transparent means "no
    // wash". See [kidColorScheme] and [Modifier.kidBackdrop].
    surfaceTint = Color(KID_DARK.surfaceTint)
)

/**
 * The one fixed brand anchor. The logo tile keeps the logo's own teal in
 * every look — Dark, Light and the kid's colour alike — so the app still
 * reads as Yosemite Kids from across the room when everything else on the
 * page has taken the kid's tint. Deliberately not `primary`, which "My colour"
 * moves; a mark that changes colour per kid is not a mark.
 */
val YosemiteBrandTeal = Color(KidBrand.TEAL)
val OnYosemiteBrand = Color(KidBrand.ON_TEAL)

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

/**
 * The daylight version of the same brand: paper surfaces, the teal kept for
 * accents. Values in `:core`'s [KID_LIGHT], for the same reason as the dark
 * scheme above. It names no `tertiary`, which is why that field is nullable.
 */
val YosemiteLightColors = androidx.compose.material3.lightColorScheme(
    primary = Color(KID_LIGHT.primary),
    onPrimary = Color(KID_LIGHT.onPrimary),
    primaryContainer = Color(KID_LIGHT.primaryContainer),
    onPrimaryContainer = Color(KID_LIGHT.onPrimaryContainer),
    secondary = Color(KID_LIGHT.secondary),
    onSecondary = Color(KID_LIGHT.onSecondary),
    secondaryContainer = Color(KID_LIGHT.secondaryContainer),
    onSecondaryContainer = Color(KID_LIGHT.onSecondaryContainer),
    background = Color(KID_LIGHT.background),
    onBackground = Color(KID_LIGHT.onBackground),
    surface = Color(KID_LIGHT.surface),
    onSurface = Color(KID_LIGHT.onSurface),
    surfaceVariant = Color(KID_LIGHT.surfaceVariant),
    onSurfaceVariant = Color(KID_LIGHT.onSurfaceVariant),
    // The same steps the dark scheme now names, going the other way: paper,
    // then progressively less of it. Left at Material's baseline these were
    // the light theme's version of the same bug — stock greys the theme did
    // not choose and "My colour" could not reach.
    surfaceContainerLowest = Color(KID_LIGHT.surfaceContainerLowest),
    surfaceContainerLow = Color(KID_LIGHT.surfaceContainerLow),
    surfaceContainer = Color(KID_LIGHT.surfaceContainer),
    surfaceContainerHigh = Color(KID_LIGHT.surfaceContainerHigh),
    surfaceContainerHighest = Color(KID_LIGHT.surfaceContainerHighest),
    outline = Color(KID_LIGHT.outline),
    outlineVariant = Color(KID_LIGHT.outlineVariant),
    surfaceTint = Color(KID_LIGHT.surfaceTint)
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

/*
 * Darkening a fill until its label clears 4.5:1 used to live here as
 * `legible`. It is [Argb.legibleGround] in :core now, called from [kidTinted],
 * because the hub tints a kid's chips for their browser and the walk has to be
 * the same walk — see that function for why the step is 0.06.
 */

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
    // The blend itself is [kidTinted] in :core, not a copy of it here. The hub
    // pours the same kid's colour into the same look to theme their browser,
    // and "7% toward their colour" written twice is two rooms in almost the
    // same shade — the kind of difference nobody reports and nobody can find.
    // What stays here is only the conversion to Compose's types.
    val tinted = kidTinted(
        base = if (theme == THEME_LIGHT) KID_LIGHT else KID_DARK,
        tint = profile.colorArgb.toInt()
    )
    return base.copy(
        primary = Color(tinted.primary),
        onPrimary = Color(tinted.onPrimary),
        primaryContainer = Color(tinted.primaryContainer),
        onPrimaryContainer = Color(tinted.onPrimaryContainer),
        background = Color(tinted.background),
        surface = Color(tinted.surface),
        surfaceVariant = Color(tinted.surfaceVariant),
        surfaceContainerLowest = Color(tinted.surfaceContainerLowest),
        surfaceContainerLow = Color(tinted.surfaceContainerLow),
        surfaceContainer = Color(tinted.surfaceContainer),
        surfaceContainerHigh = Color(tinted.surfaceContainerHigh),
        surfaceContainerHighest = Color(tinted.surfaceContainerHighest),
        outline = Color(tinted.outline),
        outlineVariant = Color(tinted.outlineVariant),
        secondaryContainer = Color(tinted.secondaryContainer),
        onSecondaryContainer = Color(tinted.onSecondaryContainer),
        // Carries the kid's colour to [kidBackdrop]. Transparent on the two
        // brand themes, which is how the wash knows to stay off.
        surfaceTint = Color(tinted.surfaceTint)
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
 * One step of the shared scale, as Compose wants it.
 *
 * A null weight stays null rather than becoming 400: the three body styles
 * take the font's own weight, and naming one would be a different TextStyle
 * from the one the app has always shipped.
 */
private fun TypeStyle.textStyle() = androidx.compose.ui.text.TextStyle(
    fontSize = androidx.compose.ui.unit.TextUnit(
        sizeSp.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp
    ),
    lineHeight = androidx.compose.ui.unit.TextUnit(
        lineHeightSp.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp
    ),
    fontWeight = weight?.let { androidx.compose.ui.text.font.FontWeight(it) }
)

/**
 * One quiet type scale for every kid-facing screen. Material's defaults are
 * tuned for dense productivity apps; YouTube's shape is a single bold line
 * (the video title) with everything else a step or two quieter. Page
 * titles are titleLarge, sections titleMedium, tile titles titleSmall,
 * captions bodySmall — and nothing else on a screen competes with them.
 *
 * The numbers are `:core`'s [KidType], because the browser needs the same
 * ladder and cannot read a Compose file. This is the Android binding.
 */
val YosemiteTypography = androidx.compose.material3.Typography(
    headlineSmall = KidType.headlineSmall.textStyle(),
    titleLarge = KidType.titleLarge.textStyle(),
    titleMedium = KidType.titleMedium.textStyle(),
    titleSmall = KidType.titleSmall.textStyle(),
    bodyLarge = KidType.bodyLarge.textStyle(),
    bodyMedium = KidType.bodyMedium.textStyle(),
    bodySmall = KidType.bodySmall.textStyle(),
    labelLarge = KidType.labelLarge.textStyle(),
    labelMedium = KidType.labelMedium.textStyle(),
    labelSmall = KidType.labelSmall.textStyle()
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
val WatchedProgressRed = Color(KidBrand.WATCHED_PROGRESS)

/** The thumbnail-bottom watched bar, one spelling for every grid and row. */
@Composable
fun BoxScope.WatchedProgressBar(fraction: Float) {
    // Both the height and the track colour come from :core, because the browser
    // draws this same bar and had no way to know either number: it reached for
    // artwork-scrim (80% black) where this is 40% white, and the same bar was
    // pale on the television and dark on the tablet.
    val height = KidGeometry.PROGRESS_HEIGHT.dp
    Box(
        Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .height(height).background(Color(KidBrand.WATCHED_TRACK))
    )
    Box(
        Modifier.align(Alignment.BottomStart).fillMaxWidth(fraction)
            .height(height).background(WatchedProgressRed)
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
val SponsorSegmentGreen = Color(KidBrand.SPONSOR_SEGMENT)

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
