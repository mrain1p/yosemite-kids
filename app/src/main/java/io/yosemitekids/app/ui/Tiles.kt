package io.yosemitekids.app.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * One line, always the same tile height; scrolls sideways while focused.
 *
 * The single-line shape survives for text that is *not* a card title — a
 * dialog heading, a channel name under a logo. Video titles use [CardTitle],
 * which is two lines.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun MarqueeTitle(
    text: String,
    focused: Boolean,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium
) {
    Text(
        text,
        maxLines = 1,
        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
        style = style,
        modifier = if (focused) Modifier.basicMarquee() else Modifier
    )
}

// ---------------------------------------------------------------------------
// Card geometry: the one type scale and the one title box every tile inherits.
//
// Two rules hold this together, and both are worth stating because the
// tempting shortcut breaks a different thing each time.
//
// **Type in sp, geometry in dp.** The design's sizes are pixels measured
// against fixed frames. Written as dp they would stop honouring a family's
// font-size setting — on a kid's app, of all places. Written as sp with the
// tile height *also* in sp, a large-font household gets tiles that grow until
// a rail is one card wide. So the type is sp and every box around it is dp,
// sized from the type at the default scale.
//
// **A title is two lines, and the box is always two lines tall**, whether the
// title needs them or not — that is what keeps the meta line at the same
// height on every card in a rail. When the font scale is turned up the box
// does not grow: [titleLinesIn] hands back fewer lines, so a large-font phone
// degrades to one ellipsised line rather than to a clipped half-line or a
// rail of ragged cards.
// ---------------------------------------------------------------------------

/** Every card title is drawn in a box this many lines tall. */
internal const val CARD_TITLE_LINES = 2

/**
 * The line height a style actually draws at, in sp, whatever it declares.
 * Styles copied from the Material scale can leave `lineHeight` unspecified,
 * and the title box is computed from it — an unspecified value there is a
 * zero-height card, not a compile error.
 */
internal val TextStyle.lineHeightSp: Float
    get() = when {
        lineHeight.isSp -> lineHeight.value
        fontSize.isSp -> fontSize.value * 1.3f
        else -> 20f
    }

/**
 * The fixed height of a card's title box: [lines] lines of a style whose line
 * height is [lineHeightSp], **at the default font scale**. Dp, deliberately —
 * see the note above.
 */
internal fun cardTitleHeight(lineHeightSp: Float, lines: Int = CARD_TITLE_LINES): Dp =
    (lineHeightSp * lines).dp

/**
 * How many whole lines of [lineHeight] fit in a [box] that is not allowed to
 * grow. Never zero, and never more than [max]: at the default font scale this
 * is exactly [CARD_TITLE_LINES], and at 1.5× it is one — which is the whole
 * point, because the alternative is a second line sliced in half by the box.
 */
internal fun titleLinesIn(box: Dp, lineHeight: Dp, max: Int = CARD_TITLE_LINES): Int {
    if (lineHeight.value <= 0f) return max
    // A hair of slack: 40.dp / 20.dp is 1.9999997 often enough to matter.
    return ((box / lineHeight) + 0.01f).toInt().coerceIn(1, max)
}

/**
 * A video title on a card: two lines, a fixed box, and the marquee kept for
 * the ones that still do not fit.
 *
 * The marquee is why this is not just a `Text` with `maxLines = 2`. On a TV a
 * kid cannot tap a title to see the rest of it, so a focused tile whose title
 * overflows collapses to a single scrolling line — the old [MarqueeTitle]
 * behaviour, now reached only when it earns its keep. Titles that fit in two
 * lines never move, which is most of them.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CardTitle(
    text: String,
    focused: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier
) {
    val box = cardTitleHeight(style.lineHeightSp)
    val lineHeight = with(LocalDensity.current) { style.lineHeightSp.sp.toDp() }
    val lines = titleLinesIn(box, lineHeight)
    // Whether the wrapped form spills out of the box. Measured rather than
    // guessed: it depends on the width the card was actually given.
    var overflows by remember(text, lines) { mutableStateOf(false) }
    val scroll = focused && overflows
    Box(modifier.height(box)) {
        Text(
            text,
            maxLines = if (scroll) 1 else lines,
            softWrap = !scroll,
            overflow = if (scroll) TextOverflow.Clip else TextOverflow.Ellipsis,
            style = style,
            // Only the wrapped pass reports: the scrolling one overflows by
            // construction, and letting it answer would latch the card into
            // marquee for good.
            onTextLayout = { if (!scroll) overflows = it.hasVisualOverflow },
            modifier = if (scroll) Modifier.basicMarquee() else Modifier
        )
    }
}

/** The feed card's title — the full-width phone item, the TV grid tile. */
@Composable
internal fun feedCardTitleStyle(formFactor: FormFactor = LocalFormFactor.current): TextStyle =
    MaterialTheme.typography.titleSmall.copy(
        fontSize = if (formFactor.isTv) 20.sp else 15.5.sp,
        lineHeight = if (formFactor.isTv) 26.sp else 20.sp,
        fontWeight = if (formFactor.isTv) FontWeight.Bold else FontWeight.SemiBold
    )

/** The rail card's title — a step quieter, because a rail is scanned, not read. */
@Composable
internal fun railCardTitleStyle(formFactor: FormFactor = LocalFormFactor.current): TextStyle =
    MaterialTheme.typography.titleSmall.copy(
        fontSize = if (formFactor.isTv) 17.sp else 13.5.sp,
        lineHeight = if (formFactor.isTv) 22.sp else 18.sp,
        fontWeight = FontWeight.SemiBold
    )

/** The channel rail's name, under the art. Quieter again than a rail title. */
@Composable
internal fun channelNameStyle(formFactor: FormFactor = LocalFormFactor.current): TextStyle =
    MaterialTheme.typography.labelMedium.copy(
        fontSize = if (formFactor.isTv) 15.sp else 12.sp,
        lineHeight = if (formFactor.isTv) 19.sp else 16.sp,
        fontWeight = FontWeight.SemiBold
    )

/** "Channel · today" under a title, on either form factor. */
@Composable
internal fun cardMetaStyle(formFactor: FormFactor = LocalFormFactor.current): TextStyle =
    MaterialTheme.typography.bodySmall.copy(
        fontSize = if (formFactor.isTv) 14.5.sp else 12.5.sp,
        lineHeight = if (formFactor.isTv) 19.sp else 16.sp
    )

/**
 * "WATCHED", beside the meta line of a video the kid finished.
 *
 * Mono and uppercase because it is a label rather than a word to read, and in
 * [KidTokens.watched] rather than the action colour — done is not a thing to
 * press.
 */
@Composable
internal fun WatchedTag(formFactor: FormFactor = LocalFormFactor.current) {
    Text(
        "watched".uppercase(),
        color = kidTokens.watched,
        maxLines = 1,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = if (formFactor.isTv) 12.sp else 9.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = TextUnit(0.14f, TextUnitType.Em),
            fontFamily = FontFamily.Monospace
        )
    )
}

/**
 * The line under a card's title, and the finished half of the watched state.
 *
 * **Watched is two signals, and they never appear together.** A part-watched
 * video keeps the red bar along the bottom of its poster — [WatchedProgressRed]
 * is deliberately not the brand colour, because kids read that bar by the
 * convention YouTube taught them. A *finished* video has no progress left to
 * report: it dims and says so here instead. They describe different states —
 * how far you got, versus done — so a card is never both, and the poster drops
 * its bar the moment the tag appears.
 */
@Composable
internal fun CardMetaRow(
    meta: String,
    watched: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = cardMetaStyle()
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text(
            meta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (watched) {
            Spacer(Modifier.width(6.dp))
            WatchedTag()
        }
    }
}

/** A finished video: the poster's bar gives way to the WATCHED tag here. */
internal fun VideoItem.isFinished(): Boolean = (progress ?: 0f) >= 0.98f

/**
 * Every poster and channel avatar in the app: a soft placeholder block and a
 * short crossfade. Bare AsyncImage popped each thumbnail in white-on-black
 * the instant it landed, which across a grid reads as flicker.
 */
@Composable
internal fun PosterImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    AsyncImage(
        model = remember(url) {
            ImageRequest.Builder(context).data(url).crossfade(180).build()
        },
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        placeholder = placeholder,
        error = placeholder,
        modifier = modifier
    )
}

/**
 * Tiles squish a touch while pressed — the "it felt my finger" cue every
 * kids' app has. A render-layer scale only: layout never moves, so on TV
 * (where presses are brief OK taps) neighbouring tiles stay put.
 */
@Composable
internal fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
internal fun SpecialTile(
    emoji: String,
    label: String,
    circleColor: Color,
    modifier: Modifier = Modifier,
    rounded: Boolean = false,
    /** An icon in place of the emoji — the chrome's own set, not a glyph from the emoji font. */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Card(
        shape = if (rounded) RoundedCornerShape(16.dp) else androidx.compose.ui.graphics.RectangleShape,
        modifier = modifier
            .pressScale(interaction)
            .tvFocusHighlight()
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                onClick()
            }
    ) {
        Column {
            // Full-bleed color block matching the channel-tile geometry.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(circleColor)
            ) {
                if (icon != null) {
                    androidx.compose.material3.Icon(
                        icon, contentDescription = null, tint = Color.White,
                        modifier = Modifier.fillMaxSize(0.42f)
                    )
                } else Text(emoji, fontSize = TextUnit(56f, TextUnitType.Sp))
            }
            Box(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }
}

/**
 * "20m" behind an amber dot: the time-left pill that rides the top bar.
 *
 * A dot rather than a timer glyph, and mono rather than the text face, because
 * this is a number that changes under the eye — a proportional face makes the
 * pill twitch every time the digits change width, and at 11 sp the clock face
 * was three grey pixels doing no work.
 *
 * Both colours come from the scheme, which is the fix for a real bug and not
 * tidying: the fill used to be `primaryContainer` with a hard-coded white
 * label, which is 1.45:1 on the light theme's pale teal — invisible. The
 * raised surface carries `onSurface` in all three looks by construction.
 *
 * Inside the last five minutes the fill drops back to the page's own ground so
 * the label can be drawn in [KidTokens.timeWarning], which is the one colour
 * guaranteed (by `KidThemeContrastTest`) to clear 4.5:1 against *that*
 * background on every look and every kid's tint. Amber text on the raised step
 * is not: it lands at 3.8:1 on paper, and 11 sp is not large text.
 */
@Composable
internal fun TimeChip(remainingMs: Long) {
    val tokens = kidTokens
    val urgent = remainingMs <= 5 * 60_000L
    val label = if (urgent) tokens.timeWarning else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (urgent) MaterialTheme.colorScheme.background
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .then(
                if (urgent) Modifier.border(1.dp, tokens.timeWarning, RoundedCornerShape(9.dp))
                else Modifier
            )
            .padding(horizontal = 9.dp)
            // The pill says "20m"; a screen reader still gets the sentence.
            .semantics { contentDescription = remainingLabel(remainingMs) }
    ) {
        Box(Modifier.size(7.dp).background(tokens.timeWarning, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            remainingShort(remainingMs),
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = TextUnit(11f, TextUnitType.Sp),
                fontWeight = FontWeight.Bold,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            ),
            color = label
        )
    }
}

/**
 * Full-width home banner while a rule blocks watching (bedtime, break, budget
 * spent, parent pause). Says so up front — without it the kid finds out by
 * tapping tile after tile and getting the block screen each time.
 */
@Composable
internal fun BlockedBanner(reason: String) {
    val emoji = listOf("🌙", "⏰", "🌟", "🎉", "💛").firstOrNull { it in reason } ?: "⏰"
    val text = reason.replace(emoji, "").trim()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(emoji, fontSize = TextUnit(32f, TextUnitType.Sp))
        Spacer(Modifier.width(14.dp))
        Text(
            text,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
        )
    }
}

/**
 * One video, YouTube-style, for phones and tablets: rounded poster with a
 * duration badge and the red watched bar, then the channel's round avatar
 * beside a two-line title and the channel name. Big enough to tap with a
 * whole thumb; the hold opens the same menu as everywhere else.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun VideoCard(
    item: VideoItem,
    avatarUrl: String?,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: ((VideoItem) -> Unit)?,
    modifier: Modifier = Modifier,
    /** The avatar + channel-name row is its own target: the channel, not the video. */
    onOpenChannel: ((String) -> Unit)? = null,
    statusBadge: (@Composable BoxScope.() -> Unit)? = null,
    formFactor: FormFactor = LocalFormFactor.current
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val finished = item.isFinished()
    // This card is the television's feed tile too, now that the home draws one
    // feed on both shapes — so it needs what every TV tile needs: a ring where
    // the remote is, and a held OK for the same menu a touch hold opens.
    // Inert on a phone, where nothing takes focus and no key events arrive.
    var focused by remember { mutableStateOf(false) }
    val clickMod = if (onOpenMenu != null) {
        Modifier.combinedClickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onClick = { onPlay(item) },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onOpenMenu(item)
            }
        )
    } else {
        Modifier.clickable(interactionSource = interaction, indication = LocalIndication.current) {
            onPlay(item)
        }
    }
    Column(
        modifier
            .pressScale(interaction)
            // 48%: far enough back that a finished video reads as done at a
            // glance, near enough that it is still browsable — kids rewatch.
            .graphicsLayer { alpha = if (finished && !focused) 0.48f else 1f }
            .tvFocusHighlight(cornerRadius = 14.dp) { focused = it }
            .clip(RoundedCornerShape(14.dp))
            .then(if (onOpenMenu != null) Modifier.dpadLongPress { onOpenMenu(item) } else Modifier)
            .then(clickMod)
            .padding(bottom = 6.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
        ) {
            PosterImage(
                url = item.video.thumbnailUrl,
                contentDescription = item.video.title,
                modifier = Modifier.fillMaxSize()
            )
            if (item.video.durationSeconds > 0) {
                Text(
                    formatClock(item.video.durationSeconds),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
            statusBadge?.invoke(this)
            // The part-watched half of the watched state — see [CardMetaRow].
            // A finished video has nothing left to report here and says so
            // beside its meta line instead.
            item.progress?.takeIf { !finished }?.let { fraction -> WatchedProgressBar(fraction) }
        }
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp)
        ) {
            // Avatar: the channel. Tapping it goes to the channel page, the
            // way it does on YouTube — it used to play the video, which is
            // not what a tap on a face means.
            val channelTap = if (onOpenChannel != null) {
                Modifier.clickable { onOpenChannel(item.video.channelName) }
            } else Modifier
            // 34 dp of face inside a 44 dp target on a phone: the drawn size
            // is the design's, the hit area is the platform minimum, and the
            // two are deliberately not the same number (as in [PhoneTopBar]).
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(if (formFactor.isPhone) 44.dp else 38.dp)
                    .clip(CircleShape)
                    .then(channelTap)
            ) {
                ChannelArt(avatarUrl, item.video.channelName, size = if (formFactor.isPhone) 34.dp else 38.dp)
            }
            Spacer(Modifier.width(8.dp))
            Column {
                CardTitle(item.video.title, focused = focused, style = feedCardTitleStyle(formFactor))
                // "Channel · 3 days ago": the quiet line every video app has.
                CardMetaRow(
                    meta = videoMeta(item.video.channelName, item.video.publishedAt),
                    watched = finished,
                    style = cardMetaStyle(formFactor),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .then(channelTap)
                        .padding(vertical = 2.dp)
                )
            }
        }
    }
}

/**
 * A channel in the home rail: its art as a rounded square (see [ChannelArt]
 * for why not a circle), the name under it, and a dot in the action colour
 * when there is something new.
 *
 * One tile for both form factors — [art] and [column] are the whole
 * difference, which is what let the phone's channel row and the ten-foot
 * one become the same shelf. A dot rather than a "NEW" pill because at the
 * ten-foot distance a word that small is a smudge, and because the dot is
 * what marks a new channel everywhere else in the design.
 */
@Composable
internal fun ChannelChip(
    name: String,
    avatarUrl: String?,
    isNew: Boolean,
    modifier: Modifier = Modifier,
    art: Dp = 72.dp,
    column: Dp = 88.dp,
    newDot: Dp = 14.dp,
    emoji: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.surfaceVariant,
    nameStyle: TextStyle = channelNameStyle(),
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(column)
            .pressScale(interaction)
            .tvFocusHighlight(cornerRadius = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(art)
                    .clip(RoundedCornerShape(art / 3.6f))
                    .background(tint)
            ) {
                when {
                    icon != null -> androidx.compose.material3.Icon(
                        icon, contentDescription = null, tint = Color.White,
                        modifier = Modifier.size(art * 0.47f)
                    )
                    emoji != null -> Text(emoji, fontSize = TextUnit(30f, TextUnitType.Sp))
                    else -> PosterImage(avatarUrl, name, Modifier.fillMaxSize())
                }
            }
            if (isNew) Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(newDot)
                    .background(kidTokens.action, CircleShape)
            )
        }
        Spacer(Modifier.height(6.dp))
        // Two lines here too, and a box that does not grow: "BBC Earth
        // Science" was ellipsising to "BBC Earth …" beside "Maddie Moate",
        // and the rail's chips have to sit on one baseline whether a name
        // takes one line or two.
        val nameBox = cardTitleHeight(nameStyle.lineHeightSp)
        val nameLines = titleLinesIn(
            nameBox,
            with(LocalDensity.current) { nameStyle.lineHeightSp.sp.toDp() }
        )
        Box(modifier = Modifier.height(nameBox)) {
            Text(
                name,
                maxLines = nameLines,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = nameStyle
            )
        }
    }
}

/** A breathing grey placeholder in the shape of a [VideoCard], for loading grids. */
@Composable
internal fun SkeletonCard(modifier: Modifier = Modifier) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
    val alpha by pulse.animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    val tone = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier.graphicsLayer { this.alpha = alpha }) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .background(tone, RoundedCornerShape(12.dp))
        )
        // Two title bars, because a card's title box is two lines tall: a
        // one-bar skeleton made the grid jump the moment real titles landed.
        Row(Modifier.padding(top = 8.dp)) {
            Box(Modifier.size(34.dp).background(tone, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column {
                Box(Modifier.fillMaxWidth(0.9f).height(14.dp).background(tone, RoundedCornerShape(4.dp)))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(0.65f).height(14.dp).background(tone, RoundedCornerShape(4.dp)))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(0.5f).height(11.dp).background(tone, RoundedCornerShape(4.dp)))
            }
        }
    }
}

/** Big, rounded section heading with an optional trailing action ("Show all"). */
@Composable
internal fun SectionRow(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    /**
     * Chips that belong to this section (the sort, the filter), on the title's
     * own line. A chip row under every heading cost a band of empty screen per
     * section and pushed the videos below the fold.
     */
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (trailing == null) Modifier.weight(1f) else Modifier.padding(end = 10.dp)
        )
        if (trailing != null) {
            Box(Modifier.weight(1f)) { trailing() }
        }
        if (action != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.tvFocusHighlight()
            ) {
                Text(action, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
