package io.yosemitekids.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yosemitekids.app.data.Source
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Video

// ---------------------------------------------------------------------------
// The Channels tab and the channel page.
//
// A CONSIDERED CONTAINER SPLIT, NOT DRIFT. The phone's Channels tab is a
// LazyColumn of full-width rows; the television's is a LazyVerticalGrid four
// across. Two reasons, and neither is "two people wrote it twice":
//
//  * They are different objects in the design, because the input is
//    different. Under a thumb a channel is a row with TWO tap targets — the
//    left region opens the channel, a round button plays its newest video —
//    and it can afford to carry that video's title and age. Under a remote a
//    channel is a picture tile with a name and nothing else: a video title at
//    ten feet is a smudge, and a second focusable control inside a tile is
//    the wrong shape for a d-pad.
//  * The sticky filter row is not optional and not portable. Compose's
//    `LazyGridScope` has NO `stickyHeader` — only `LazyListScope` does — so
//    the phone screen could not be a grid and still pin its filters. That is
//    why this screen stopped being a LazyVerticalGrid on both shapes.
//
// The two containers meet again at [ChannelCard], which is the one place a
// channel is drawn. Change what a channel says and both shapes change.
//
// TV GEOMETRY IS PROVISIONAL, exactly as in HomeShelves.kt: every TV dp here
// is the handoff's design unit through [tvUnits] — its "×1.5 at 1080p" column
// is 720p review pixels and misleads. Nobody has held a remote in front of the
// real television with these numbers on it; they are a starting point, not a
// measurement.
// ---------------------------------------------------------------------------

/**
 * A ten-foot design unit in dp.
 *
 * The handoff's TV frames are drawn at 1280×720 for review and ship at
 * 1920×1080; a 1080p Android television is xhdpi, so it is 960 dp wide. That
 * makes the conversion 960/1280 — three quarters — for *type and geometry
 * alike*, which is why the type here is smaller than the numbers written in
 * the handoff's "TV" column.
 */
internal fun tvUnits(units: Float): Dp = (units * 0.75f).dp

/** The same conversion for type, which is sp rather than dp. */
internal fun tvTypeUnits(units: Float) = (units * 0.75f).sp

/** The dp the two channel screens take on this form factor. */
internal data class ChannelMetrics(
    /**
     * A channel's art. A 68 dp logo beside a phone row; on a television the
     * art *is* the tile, so this is the picture tile's height.
     */
    val art: Dp,
    /** A pinned card on the Pinned rail. */
    val pinnedWidth: Dp,
    val pinnedHeight: Dp,
    /** Between rail cards, and between grid tiles. */
    val gap: Dp,
    /** The dot marking a channel with new videos. Television only — a phone
     *  row has room for the number, and "3 NEW" beats a dot when it fits. */
    val newDot: Dp,
    /** The phone row's round play button: the second of its two tap targets. */
    val rowPlay: Dp,
    /**
     * The gap between the phone row's two tap targets. Twenty, and the design
     * means it: any closer and a thumb aimed at "open the channel" lands on
     * "play the newest video", which is a different screen and a kid cannot
     * tell you which one they meant to press.
     */
    val rowTargetGap: Dp,
    /** The channel page: the channel's own art at the top of its page. */
    val pageArt: Dp,
    /** The channel page: the height of the three square action cards. */
    val actionCard: Dp
)

internal fun channelMetrics(formFactor: FormFactor): ChannelMetrics =
    if (formFactor.isTv) ChannelMetrics(
        art = tvUnits(158f),
        pinnedWidth = tvUnits(214f), pinnedHeight = tvUnits(120f),
        gap = tvUnits(20f),
        newDot = tvUnits(22f),
        // A television row has neither: no thumb, no second target.
        rowPlay = 0.dp, rowTargetGap = 0.dp,
        pageArt = tvUnits(108f), actionCard = tvUnits(104f)
    ) else ChannelMetrics(
        art = 68.dp,
        pinnedWidth = 108.dp, pinnedHeight = 72.dp,
        gap = 12.dp,
        newDot = 14.dp,
        rowPlay = 44.dp, rowTargetGap = 20.dp,
        pageArt = 76.dp, actionCard = 88.dp
    )

/**
 * What a channel row says beyond its name: how much has landed since the kid
 * last looked, and the newest thing there is to play.
 *
 * Held per source in [UiState] rather than looked up at the call site: the
 * answer is a disk read of that channel's cache, and a row on a fifty-channel
 * list must not do one during composition.
 */
data class ChannelPreview(val newCount: Int, val latest: VideoItem?)

/**
 * The newest video in a channel's cache.
 *
 * The cache arrives newest-first, so the first entry is usually the answer —
 * but only usually: `publishedAt` is null for videos whose extractor had no
 * date, and a channel that re-publishes an old upload puts it at the top of
 * the listing with an old date on it. `maxByOrNull` returns the FIRST maximal
 * element, so a list with no dates at all still answers "the first one",
 * which is the behaviour the NEW badge already assumes.
 */
internal fun newestVideo(videos: List<Video>): Video? =
    videos.maxByOrNull { it.publishedAt ?: Long.MIN_VALUE }

/**
 * "3 NEW" beside a channel's name, or nothing at all.
 *
 * Never "0 NEW": a channel with nothing new says nothing, the way the hero's
 * mono line does. Uppercase because it is a flag, not a sentence.
 */
internal fun newCountLabel(newCount: Int): String =
    if (newCount > 0) "$newCount NEW" else ""

/**
 * The mono line under a channel page's name: how much is here.
 *
 * "30+ VIDEOS · 29 PLAYLISTS", and it degrades to whichever halves it has —
 * a channel whose playlists have not loaded says only the videos, and one
 * whose cache has not landed says nothing rather than "0 VIDEOS". The "+"
 * means "at least": the grid holds one page and there are more behind it.
 */
internal fun channelPageMeta(videos: Int, playlists: Int, more: Boolean): String =
    listOf(
        if (videos > 0) "$videos${if (more) "+" else ""} video${if (videos == 1) "" else "s"}" else "",
        if (playlists > 0) "$playlists playlist${if (playlists == 1) "" else "s"}" else ""
    ).filter { it.isNotEmpty() }.joinToString(" · ")

// --- the Channels tab ------------------------------------------------------

/**
 * The Channels tab, both shapes.
 *
 * Phone: a Surprise me card, the parent's pinned sources as a rail, a sticky
 * filter row, then one row per channel. Television: the sort chips, the
 * pinned row, a rule, and a four-across grid of picture tiles whose FIRST
 * cell is Surprise me — so the d-pad never has to leave the grid to find it,
 * and so the opening focus has somewhere to land that is always there.
 */
@Composable
internal fun ChannelsScreen(
    state: UiState,
    onOpen: (Source) -> Unit,
    /** The phone row's round button: play this channel's newest video. */
    onPlay: (VideoItem) -> Unit,
    onSurprise: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenWatchLater: () -> Unit,
    onOpenDownloads: () -> Unit,
    onSort: ((String) -> Unit)? = null,
    formFactor: FormFactor = LocalFormFactor.current
) {
    val metrics = channelMetrics(formFactor)
    // The kid's own shelves. They used to be tiles in this grid, and the
    // design has no room for them among the channels — so they ride the chip
    // row instead, the way the television's home carries its only door to the
    // You tab in [FeedControlRow]'s extras. Each appears only once it has
    // something in it: an empty shelf is a dead end for a kid.
    val shelfChips: (@Composable RowScope.() -> Unit) = {
        if (state.queued.isNotEmpty()) {
            YosemiteChip("Up next", selected = false, icon = YosemiteIcons.UpNext, onClick = onOpenQueue)
        }
        if (state.watchLater.isNotEmpty()) {
            YosemiteChip("Watch later", selected = false, icon = YosemiteIcons.WatchLater, onClick = onOpenWatchLater)
        }
        if (state.downloaded.isNotEmpty()) {
            YosemiteChip("Downloads", selected = false, icon = YosemiteIcons.Download, onClick = onOpenDownloads)
        }
    }
    if (formFactor.isTv) TvChannelsGrid(state, metrics, onOpen, onSurprise, onSort, shelfChips)
    else PhoneChannelsList(state, metrics, onOpen, onPlay, onSurprise, onSort, shelfChips)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PhoneChannelsList(
    state: UiState,
    metrics: ChannelMetrics,
    onOpen: (Source) -> Unit,
    onPlay: (VideoItem) -> Unit,
    onSurprise: () -> Unit,
    onSort: ((String) -> Unit)?,
    shelfChips: @Composable RowScope.() -> Unit
) {
    // No horizontal contentPadding: the sticky bar is full-bleed (it is a
    // surface of its own, like the tab bar) and everything else pays its own
    // 4 dp, which puts it at the design's 16 dp gutter once the page's own
    // 12 dp is counted.
    LazyColumn(
        contentPadding = PaddingValues(bottom = 16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "surprise") {
            Box(Modifier.padding(horizontal = 4.dp)) { SurpriseCard(onSurprise) }
        }
        if (state.pinned.isNotEmpty()) {
            item(key = "pinned-head") {
                Box(Modifier.padding(horizontal = 4.dp)) {
                    ShelfHeader("Pinned", state.pinned.size)
                }
            }
            item(key = "pinned") { PinnedSourceRail(state.pinned, metrics, onOpen) }
        }
        // The one thing on this screen that must not scroll away: a kid two
        // thirds down a fifty-channel list still has to be able to re-sort it
        // without scrolling back to the top. This is why the phone screen is
        // a LazyColumn — see the note at the top of this file.
        stickyHeader(key = "filters") {
            ChannelFilterBar(state.channelSort, onSort, shelfChips)
        }
        items(state.channels.size, key = { state.channels[it].id }) { i ->
            val channel = state.channels[i]
            ChannelCard(
                channel = channel,
                preview = state.channelPreviews[channel.id],
                isNew = channel.id in state.newBadges,
                metrics = metrics,
                onOpen = onOpen,
                onPlay = onPlay,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun TvChannelsGrid(
    state: UiState,
    metrics: ChannelMetrics,
    onOpen: (Source) -> Unit,
    onSurprise: () -> Unit,
    onSort: ((String) -> Unit)?,
    shelfChips: @Composable RowScope.() -> Unit
) {
    // Opening focus. This screen had none: a kid pressing "See all" from the
    // home landed on a page where the remote appeared not to work at all,
    // because focus was nowhere. It goes on the Surprise cell — the first
    // cell of the grid, and the one cell that is always there whatever the
    // whitelist holds. THE RETRY LOOP IS LOAD-BEARING, for the same reason it
    // is in TvHomeColumn: the grid composes its cells during the next
    // measure, so the first request can land before the requester is attached
    // and an unattached requestFocus() throws.
    val firstCell = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (focusedOnce) return@LaunchedEffect
        repeat(20) {
            if (runCatching { firstCell.requestFocus() }.isSuccess) {
                focusedOnce = true
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(150)
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(TV_CHANNEL_COLUMNS),
        horizontalArrangement = Arrangement.spacedBy(metrics.gap),
        verticalArrangement = Arrangement.spacedBy(metrics.gap),
        // Room for the focus ring, which is thicker than a phone's press state
        // and would otherwise be clipped by the grid's own bounds.
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxSize().dpadHeldScrollThrottle()
    ) {
        // Sort chips above everything, not sticky: a d-pad has no "scroll back
        // to the top" problem, and a band pinned over the grid would eat a
        // row of a ten-foot layout for the whole visit.
        if (onSort != null) item(key = "sort", span = { GridItemSpan(maxLineSpan) }) {
            ChannelSortChips(state.channelSort, onSort, extras = shelfChips)
        }
        if (state.pinned.isNotEmpty()) {
            item(key = "pinned-head", span = { GridItemSpan(maxLineSpan) }) {
                ShelfHeader("Pinned", state.pinned.size)
            }
            item(key = "pinned", span = { GridItemSpan(maxLineSpan) }) {
                PinnedSourceRail(state.pinned, metrics, onOpen)
            }
            item(key = "pinned-rule", span = { GridItemSpan(maxLineSpan) }) { ShelfRule() }
        }
        item(key = "surprise") {
            SurpriseCell(metrics, onSurprise, Modifier.focusRequester(firstCell))
        }
        items(state.channels.size, key = { state.channels[it].id }) { i ->
            val channel = state.channels[i]
            ChannelCard(
                channel = channel,
                preview = state.channelPreviews[channel.id],
                isNew = channel.id in state.newBadges,
                metrics = metrics,
                onOpen = onOpen,
                onPlay = {}
            )
        }
    }
}

/** Four across, and the design means four: wider and a name stops being readable
 *  from the couch, narrower and a fifty-channel whitelist is a long scroll. */
internal const val TV_CHANNEL_COLUMNS = 4

/**
 * ONE channel, in whichever shape the page is.
 *
 * The two containers above are deliberately different; this is where they
 * meet again, so that "what a channel says" is one decision rather than two.
 */
@Composable
internal fun ChannelCard(
    channel: Source,
    preview: ChannelPreview?,
    isNew: Boolean,
    metrics: ChannelMetrics,
    onOpen: (Source) -> Unit,
    onPlay: (VideoItem) -> Unit,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = LocalFormFactor.current
) {
    if (formFactor.isTv) {
        ChannelPictureTile(channel, isNew, metrics, onOpen, modifier, formFactor)
    } else {
        ChannelListRow(channel, preview, metrics, onOpen, onPlay, modifier, formFactor)
    }
}

/**
 * A channel under a thumb: a hairline-bottomed row with two tap targets.
 *
 * The left region — logo, name, what is new, and the newest video's title —
 * opens the channel. The round button on the right, wearing that video's own
 * artwork, plays it. They are twenty dp apart because they go to different
 * places (see [ChannelMetrics.rowTargetGap]).
 */
@Composable
private fun ChannelListRow(
    channel: Source,
    preview: ChannelPreview?,
    metrics: ChannelMetrics,
    onOpen: (Source) -> Unit,
    onPlay: (VideoItem) -> Unit,
    modifier: Modifier,
    formFactor: FormFactor
) {
    val tokens = kidTokens
    val latest = preview?.latest
    val newLabel = newCountLabel(preview?.newCount ?: 0)
    Column(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        ) {
            val interaction = remember { MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .pressScale(interaction)
                    .tvFocusHighlight(cornerRadius = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current
                    ) { onOpen(channel) }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                ChannelArt(channel.avatarUrl, channel.name, size = metrics.art)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            channel.name +
                                if (channel.kind == SourceKind.PLAYLIST) "  ·  playlist" else "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontSize = if (formFactor.isTv) tvTypeUnits(18f) else 15.5.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (newLabel.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                newLabel,
                                color = tokens.action,
                                maxLines = 1,
                                style = monoLabelStyle(formFactor)
                            )
                        }
                    }
                    if (latest != null) {
                        // The rail card's title, in the rail card's two-line
                        // box, so every row on this list is the same height
                        // whether a title takes one line or two.
                        CardTitle(
                            latest.video.title,
                            focused = false,
                            style = railCardTitleStyle(formFactor)
                        )
                        // No channel name here: the line above already is the
                        // channel. Through videoMeta so the parent's "show when
                        // a video came out" switch still governs it.
                        val age = videoAgeMeta(latest.video.publishedAt)
                        if (age.isNotEmpty() || latest.isFinished()) {
                            CardMetaRow(age, watched = latest.isFinished(), style = cardMetaStyle(formFactor))
                        }
                    }
                }
            }
            if (latest != null) {
                Spacer(Modifier.width(metrics.rowTargetGap))
                NewestPlayButton(latest, metrics.rowPlay) { onPlay(latest) }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * The round button on a channel row: the newest video's own artwork under a
 * scrim, with a play triangle on it.
 *
 * The artwork rather than a plain accent circle because it is the one thing on
 * the row that says *what* pressing it plays — and a five-year-old reads the
 * picture long before the title beside it.
 */
@Composable
private fun NewestPlayButton(item: VideoItem, size: Dp, onPlay: () -> Unit) {
    val tokens = kidTokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .pressScale(interaction)
            .tvFocusHighlight(cornerRadius = size / 2)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onPlay() }
    ) {
        PosterImage(item.video.thumbnailUrl, "Play ${item.video.title}", Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(tokens.artworkScrim.copy(alpha = 0.42f)))
        PlayTriangle(size, tokens.onArtwork)
    }
}

/**
 * The play mark, drawn rather than set as "▶".
 *
 * Same fractions as the launcher tile and the hero card's circle: the glyph's
 * side bearings and line-height padding put it visibly off-centre, and these
 * put the triangle's centroid — not its bounding box — on the middle, which is
 * what the eye reads as centred.
 */
@Composable
private fun PlayTriangle(size: Dp, color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val triangle = Path().apply {
            moveTo(w * 0.38f, h * 0.28f)
            lineTo(w * 0.76f, h * 0.50f)
            lineTo(w * 0.38f, h * 0.72f)
            close()
        }
        drawPath(triangle, color)
    }
}

/**
 * A channel under a remote: its art as a wide picture tile, the name beneath,
 * and a dot in the action colour when something new has landed.
 *
 * Wide rather than the square a logo actually is, because the design's grid is
 * wide and because the pinned hero already crops channel art this way on the
 * home screen — one crop rule, not two.
 */
@Composable
private fun ChannelPictureTile(
    channel: Source,
    isNew: Boolean,
    metrics: ChannelMetrics,
    onOpen: (Source) -> Unit,
    modifier: Modifier,
    formFactor: FormFactor
) {
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    // No rounded clip on the column itself, only on the art inside it: a
    // corner radius here is a mask over the NAME as well, and an 18 dp arc
    // takes the first letter's left edge off every tile whose label sits near
    // the bottom. The focus ring is already drawn rounded.
    Column(
        modifier
            .pressScale(interaction)
            .tvFocusHighlight(cornerRadius = 18.dp) { focused = it }
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                onOpen(channel)
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(metrics.art)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            PosterImage(channel.avatarUrl, channel.name, Modifier.fillMaxSize())
            if (isNew) Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(metrics.newDot)
                    .background(kidTokens.action, CircleShape)
            )
        }
        Spacer(Modifier.height(8.dp))
        MarqueeTitle(
            channel.name + if (channel.kind == SourceKind.PLAYLIST) "  ·  playlist" else "",
            focused = focused,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = tvTypeUnits(20f),
                fontWeight = FontWeight.Bold
            )
        )
    }
}

/**
 * "Surprise me" as a card, at the top of the phone's Channels tab: a raised
 * surface, a 52 dp tile in the action colour, and a line saying what it does.
 * Bigger than the home's outlined pill on purpose — here it is the first thing
 * on the page rather than one control among several above a feed.
 */
@Composable
private fun SurpriseCard(onSurprise: () -> Unit, formFactor: FormFactor = LocalFormFactor.current) {
    val tokens = kidTokens
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .pressScale(interaction)
            .tvFocusHighlight(cornerRadius = 18.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onSurprise() }
            .padding(14.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(52.dp).background(tokens.action, RoundedCornerShape(14.dp))
        ) {
            Icon(
                YosemiteIcons.Dice, contentDescription = null, tint = tokens.onAction,
                modifier = Modifier.size(26.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                "Surprise me",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = if (formFactor.isTv) tvTypeUnits(22f) else 17.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                "play something from any channel",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = cardMetaStyle(formFactor)
            )
        }
    }
}

/**
 * "Surprise me" as the first cell of the television's grid.
 *
 * First on purpose: the d-pad never has to leave the grid to find it, and the
 * opening focus has a cell that exists whatever the whitelist holds.
 */
@Composable
private fun SurpriseCell(
    metrics: ChannelMetrics,
    onSurprise: () -> Unit,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = LocalFormFactor.current
) {
    val tokens = kidTokens
    val interaction = remember { MutableInteractionSource() }
    // No rounded clip on the column — see [ChannelPictureTile].
    Column(
        modifier
            .pressScale(interaction)
            .tvFocusHighlight(cornerRadius = 18.dp)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onSurprise() }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.art)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(tvUnits(64f)).background(tokens.action, RoundedCornerShape(14.dp))
            ) {
                Icon(
                    YosemiteIcons.Dice, contentDescription = null, tint = tokens.onAction,
                    modifier = Modifier.size(tvUnits(34f))
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Surprise me",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = tvTypeUnits(20f),
                fontWeight = FontWeight.Bold
            )
        )
    }
}

/**
 * The parent's pinned sources on the Channels tab: art with a CHANNEL or
 * PLAYLIST badge and the name underneath.
 *
 * The badge says the *kind* rather than repeating "PINNED", which the heading
 * directly above it has already said — what a kid cannot tell from the picture
 * is whether pressing it opens a channel or a fixed list of videos.
 */
@Composable
private fun PinnedSourceRail(
    items: List<PinnedItem>,
    metrics: ChannelMetrics,
    onOpen: (Source) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(metrics.gap),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
    ) {
        items(items.size, key = { items[it].source.id }) { i ->
            PinnedSourceTile(items[i], metrics, onOpen)
        }
    }
}

@Composable
private fun PinnedSourceTile(
    item: PinnedItem,
    metrics: ChannelMetrics,
    onOpen: (Source) -> Unit,
    formFactor: FormFactor = LocalFormFactor.current
) {
    val tokens = kidTokens
    val interaction = remember { MutableInteractionSource() }
    var focused by remember { mutableStateOf(false) }
    // No rounded clip on the column — see [ChannelPictureTile].
    Column(
        Modifier
            .width(metrics.pinnedWidth)
            .pressScale(interaction)
            .tvFocusHighlight(cornerRadius = 14.dp) { focused = it }
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                onOpen(item.source)
            }
    ) {
        Box(
            Modifier
                .width(metrics.pinnedWidth)
                .height(metrics.pinnedHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            PosterImage(item.source.avatarUrl, item.source.name, Modifier.fillMaxSize())
            Text(
                (if (item.source.kind == SourceKind.PLAYLIST) "playlist" else "channel").uppercase(),
                color = tokens.onArtwork,
                maxLines = 1,
                // The design's smallest micro-label, not the count's: the tile
                // is 108 dp wide and the badge at count size very nearly fills
                // it, which turns a corner flag into a caption.
                style = monoLabelStyle(formFactor).copy(
                    fontSize = if (formFactor.isTv) tvTypeUnits(11.5f) else 9.5.sp
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(tokens.artworkScrim, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        MarqueeTitle(
            item.source.name,
            focused = focused,
            style = channelNameStyle(formFactor).copy(fontWeight = FontWeight.Bold)
        )
    }
}

/**
 * The sticky filter bar on the phone's Channels tab.
 *
 * A surface of its own rather than a transparent band: the page behind it is
 * a wash on "My colour", and a flat rectangle of the ground colour scrolling
 * over a gradient shows a seam. The tab bar solves the same problem the same
 * way — a raised container plus one hairline rule.
 */
@Composable
private fun ChannelFilterBar(
    sort: String,
    onSort: ((String) -> Unit)?,
    shelfChips: @Composable RowScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Box(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
            ChannelSortChips(sort, onSort ?: {}, extras = shelfChips)
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// --- the channel page ------------------------------------------------------

/**
 * The top of a channel's page: the channel's art and name over a mono line of
 * what is here, then the three things a kid can do without reading anything —
 * play the newest video, play one they loved, or be surprised.
 *
 * Emitted into the grid that draws the rest of the page, so the whole thing
 * scrolls as one rather than as a block bolted above a scrolling grid.
 */
internal fun LazyGridScope.channelBlock(
    source: Source,
    meta: String,
    onNewest: (() -> Unit)?,
    onFavorite: (() -> Unit)?,
    onSurprise: () -> Unit
) {
    item(key = "ch:block", span = { GridItemSpan(maxLineSpan) }) {
        ChannelHeadBlock(source, meta, onNewest, onFavorite, onSurprise)
    }
}

@Composable
private fun ChannelHeadBlock(
    source: Source,
    meta: String,
    onNewest: (() -> Unit)?,
    onFavorite: (() -> Unit)?,
    onSurprise: () -> Unit,
    formFactor: FormFactor = LocalFormFactor.current
) {
    val metrics = channelMetrics(formFactor)
    // A television has the width to put the actions beside the channel; a
    // phone stacks them, which is what the design draws on each.
    if (formFactor.isTv) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Box(Modifier.weight(1f)) { ChannelIdentity(source, meta, metrics, formFactor) }
            Spacer(Modifier.width(24.dp))
            // Three cards a shade wider than they are tall, plus their two
            // gaps: "square-ish" is what the design draws, and a weight here
            // would give each of them a third of the panel.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.width(metrics.actionCard * 3.4f)
            ) {
                ChannelActionCards(metrics, onNewest, onFavorite, onSurprise)
            }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            ChannelIdentity(source, meta, metrics, formFactor)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChannelActionCards(metrics, onNewest, onFavorite, onSurprise)
            }
        }
    }
}

@Composable
private fun ChannelIdentity(
    source: Source,
    meta: String,
    metrics: ChannelMetrics,
    formFactor: FormFactor
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ChannelArt(source.avatarUrl, source.name, size = metrics.pageArt)
        Spacer(Modifier.width(if (formFactor.isTv) 20.dp else 14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                source.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = if (formFactor.isTv) tvTypeUnits(30f) else 21.sp,
                    lineHeight = if (formFactor.isTv) tvTypeUnits(34f) else 26.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    meta.uppercase(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = monoLabelStyle(formFactor)
                )
            }
        }
    }
}

/**
 * The three square action cards.
 *
 * Equal width, by weight, so they stay equal on a panel that is not the one
 * this was drawn against. Favorite appears only when the kid actually has a
 * favourite on this channel — an empty shelf collapses without trace
 * everywhere else in this app, and a card that does nothing is worse than a
 * card that is not there, especially for a five-year-old who will press it.
 *
 * When one is missing its slot is left EMPTY rather than shared out, for the
 * same reason [PinnedHeroRow] holds its third place open: two cards stretched
 * across three slots are not the design at a smaller count, they are a
 * different and worse row.
 */
@Composable
private fun RowScope.ChannelActionCards(
    metrics: ChannelMetrics,
    onNewest: (() -> Unit)?,
    onFavorite: (() -> Unit)?,
    onSurprise: () -> Unit
) {
    val tokens = kidTokens
    if (onNewest != null) {
        ChannelActionCard("Newest", metrics, Modifier.weight(1f), onNewest) {
            PlayTriangle(metrics.actionCard * 0.34f, tokens.action)
        }
    } else Spacer(Modifier.weight(1f))
    if (onFavorite != null) {
        ChannelActionCard("Favorite", metrics, Modifier.weight(1f), onFavorite) {
            Icon(
                Icons.Filled.Favorite, contentDescription = null, tint = tokens.action,
                modifier = Modifier.size(metrics.actionCard * 0.26f)
            )
        }
    } else Spacer(Modifier.weight(1f))
    ChannelActionCard("Surprise", metrics, Modifier.weight(1f), onSurprise) {
        Icon(
            YosemiteIcons.Dice, contentDescription = null, tint = tokens.action,
            modifier = Modifier.size(metrics.actionCard * 0.26f)
        )
    }
}

@Composable
private fun ChannelActionCard(
    label: String,
    metrics: ChannelMetrics,
    modifier: Modifier,
    onClick: () -> Unit,
    formFactor: FormFactor = LocalFormFactor.current,
    glyph: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(metrics.actionCard)
            .pressScale(interaction)
            .tvFocusHighlight(cornerRadius = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onClick() }
            .padding(horizontal = 6.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(metrics.actionCard * 0.42f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) { glyph() }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = if (formFactor.isTv) tvTypeUnits(18f) else 14.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

/**
 * The rule, the "Videos" heading with its mono count and the Watched pill, and
 * the kid's sort chips under it.
 *
 * The pill replaces the History tile that used to sit at the head of the grid:
 * same destination, same count, but in the header where the design puts it and
 * where it does not have to be scrolled past to reach the videos.
 */
internal fun LazyGridScope.channelVideosHeader(
    count: Int,
    watched: Int,
    onWatched: (() -> Unit)?,
    filter: String,
    onFilter: ((String) -> Unit)?,
    title: String = "Videos"
) {
    item(key = "all:divider", span = { GridItemSpan(maxLineSpan) }) { ShelfRule() }
    item(key = "all:title", span = { GridItemSpan(maxLineSpan) }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) { ShelfHeader(title, count) }
            if (watched > 0 && onWatched != null) WatchedPill(watched, onWatched)
        }
    }
    if (onFilter != null) item(key = "all:chips", span = { GridItemSpan(maxLineSpan) }) {
        Box(Modifier.padding(bottom = 6.dp)) { VideoFilterChips(filter, onFilter) }
    }
}

/** "Watched 2": the way to this channel's own history, and back. */
@Composable
private fun WatchedPill(
    count: Int,
    onOpen: () -> Unit,
    formFactor: FormFactor = LocalFormFactor.current
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(if (formFactor.isTv) 40.dp else 36.dp)
            .tvFocusHighlight(cornerRadius = 18.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onOpen() }
            .padding(horizontal = 12.dp)
    ) {
        Icon(
            YosemiteIcons.History, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            "Watched $count",
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = if (formFactor.isTv) tvTypeUnits(16f) else 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

/**
 * The kid's favourite on this channel, if they have one.
 *
 * Pure so the "which one" rule is a test rather than a guess: the first
 * hearted video in the list the page is already showing, which is the channel's
 * own order — so the card plays something the kid can see, not a random pick
 * out of a set they cannot inspect.
 */
internal fun favoriteOf(items: List<VideoItem>, watchlisted: Set<String>): VideoItem? =
    items.firstOrNull { it.video.url in watchlisted }
