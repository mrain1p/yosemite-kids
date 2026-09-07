package io.yosemitekids.app.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yosemitekids.app.data.Source
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.VIDEO_FILTERS

// ---------------------------------------------------------------------------
// The home is ONE list of shelves, drawn on both form factors.
//
// It used to be two screens — PhoneHome and TvHomeRows — with the same five
// shelves written out twice, in two orders, with two spellings of every
// heading. Anything the parent will one day be able to reorder or switch off
// had to be edited in both, and the two had already drifted (the phone's
// chips were a row, the TV's were one cycling chip; the phone had no history
// rail; the headings differed).
//
// So order and enabled-ness are DATA now — [UiState.homeSections], a list of
// stable string ids — and the future parent editor is a ViewModel change
// rather than a rewrite of this file.
//
// What is genuinely not a parameter stays two named renderers behind one
// contract ([HomePage]): the page container itself (a phone scrolls a grid
// whose cells ARE the feed; a television scrolls a column of rails), the
// pinned hero (a snap carousel with dots under a thumb; a static row of three
// under a remote), and the feed's own layout. Everything else — the channels
// rail, Keep watching, More like what you watch, Watched lately — is the same
// container at different sizes, and those sizes are [HomeMetrics].
//
// TV GEOMETRY IS PROVISIONAL. Every TV dp below is the handoff's design unit
// times 0.75 (its "×1.5 at 1080p" column is in 720p review pixels and
// misleads). Nobody has held a remote in front of the real television with
// these numbers on it; they are a starting point, not a measurement.
// ---------------------------------------------------------------------------

/** The dp a shelf takes on this form factor. The "different sizes" half of the merge. */
internal data class HomeMetrics(
    /** The channel rail: the art square, and the column it sits in. */
    val channelArt: Dp,
    val channelColumn: Dp,
    /** The dot marking a channel with new videos. */
    val newDot: Dp,
    /** Keep watching / suggestions / history cards. */
    val railCard: Dp,
    /** A hero card. */
    val heroWidth: Dp,
    val heroHeight: Dp,
    val heroGap: Dp,
    /** The play circle on a hero card. */
    val heroPlay: Dp,
    /** The page gutter. On a television this may never drop below 33 dp: 5% of
     *  the panel is safe area, and televisions overscan. */
    val gutter: Dp
)

internal fun homeMetrics(formFactor: FormFactor): HomeMetrics =
    if (formFactor.isTv) HomeMetrics(
        channelArt = 93.dp, channelColumn = 98.dp, newDot = 15.dp,
        railCard = 188.dp,
        heroWidth = 258.dp, heroHeight = 146.dp, heroGap = 15.dp, heroPlay = 40.dp,
        gutter = 33.dp
    ) else HomeMetrics(
        channelArt = 68.dp, channelColumn = 74.dp, newDot = 14.dp,
        railCard = 176.dp,
        heroWidth = 348.dp, heroHeight = 176.dp, heroGap = 12.dp, heroPlay = 52.dp,
        gutter = 16.dp
    )

/** Everything a shelf can ask the home to do. One bundle, because the walk
 *  passes them all and a dozen positional lambdas is unreadable at both ends. */
internal class HomeActions(
    val onPlay: (VideoItem) -> Unit,
    val onOpen: (Source) -> Unit,
    val onDismissKeepWatching: (VideoItem) -> Unit,
    val onOpenMenu: ((VideoItem) -> Unit)?,
    val onOpenChannelByName: (String) -> Unit,
    val onShowAllChannels: () -> Unit,
    val onOpenHistory: () -> Unit,
    val onSurprise: () -> Unit,
    val onHomeFilter: ((String) -> Unit)?,
    /** The ways in that are not shelves — the TV's only door to the You tab. */
    val extras: (@Composable RowScope.() -> Unit)? = null
)

/**
 * Where a shelf puts itself: the seam between the one list of shelves and the
 * two pages that draw them.
 *
 * [block] is what almost everything needs and is the same on both. [hero] and
 * [feed] are the two that are genuinely different containers, and they are
 * methods here — rather than one function with a branch inside it — so that
 * the branch is taken *once*, when the page is chosen, instead of being
 * re-decided in the middle of every shelf.
 */
internal interface HomePage {
    val metrics: HomeMetrics

    /** A full-width block: a heading, a rail, a control row, a rule. */
    fun block(key: String, content: @Composable () -> Unit)

    /** The pinned hero. Snap carousel under a thumb, static row under a remote. */
    fun hero(items: List<PinnedItem>, firstFocus: FocusRequester?, onOpen: (Source) -> Unit)

    /** The feed. One card; the phone's page IS the grid, the TV's arrives as rows of three. */
    fun feed(items: List<VideoItem>, card: @Composable (VideoItem) -> Unit)

    /**
     * The feed's placeholders while the caches warm. Through the same
     * container as the feed itself, deliberately: a skeleton laid out as one
     * full-width block on a television is a grey wall the height of the panel,
     * which is a worse answer than the spinner it replaced.
     */
    fun skeleton(count: Int)
}

/**
 * The one walk over the shelves. Order and enabled-ness come from [sections];
 * an empty shelf collapses without trace, which is how every shelf in this app
 * behaves and is why a fresh install with nothing pinned shows no hero at all
 * rather than an "ask a grown-up" tile.
 */
private fun HomePage.drawShelves(
    state: UiState,
    actions: HomeActions,
    firstFocus: FocusRequester?,
    focusShelf: String?
) {
    val counts = homeShelfCounts(state)
    var drawn = 0
    fun rule() {
        if (drawn++ > 0) block("rule-$drawn") { ShelfRule() }
    }
    for (section in state.homeSections) {
        if (!section.enabled) continue
        val count = counts[section.id] ?: 0
        if (count == 0 && section.id != HomeShelf.VIDEOS) continue
        val focus = firstFocus.takeIf { section.id == focusShelf }
        when (section.id) {
            HomeShelf.PINNED -> {
                // No heading: the hero IS the top of the page, and a "Pinned"
                // title over cards that each say PINNED is the label twice.
                rule()
                hero(state.pinned, focus, actions.onOpen)
            }
            HomeShelf.CHANNELS -> {
                rule()
                block("channels-head") {
                    ShelfHeader("Channels", count, "See all", actions.onShowAllChannels)
                }
                block("channels") {
                    ChannelRail(state.channels, state.newBadges, metrics, focus, actions.onOpen)
                }
            }
            HomeShelf.KEEP_WATCHING -> {
                rule()
                block("kw-head") { ShelfHeader("Keep watching", count) }
                block("kw") {
                    KeepWatchingRow(
                        state.keepWatching,
                        onPlay = actions.onPlay,
                        onDismiss = actions.onDismissKeepWatching,
                        rounded = true,
                        width = metrics.railCard,
                        firstFocus = focus
                    )
                }
            }
            HomeShelf.SUGGESTED -> {
                rule()
                block("sg-head") { ShelfHeader("More like what you watch", count) }
                block("sg") {
                    KeepWatchingRow(
                        state.suggested,
                        onPlay = actions.onPlay,
                        // A view over the channels, not a list the kid owns:
                        // a hold here has nothing to take the video off.
                        onDismiss = null,
                        rounded = true,
                        width = metrics.railCard,
                        firstFocus = focus
                    )
                }
            }
            HomeShelf.VIDEOS -> {
                rule()
                block("feed-head") { ShelfHeader("Videos", count) }
                block("feed-controls") {
                    FeedControlRow(
                        filter = state.homeFilter,
                        onFilter = actions.onHomeFilter,
                        onSurprise = actions.onSurprise,
                        extras = actions.extras
                    )
                }
                if (state.feed.isEmpty()) {
                    // The caches are still warming (first launch, or a new
                    // channel): breathing placeholders read as "coming", a
                    // spinner reads as "stuck".
                    skeleton(6)
                } else {
                    feed(state.feed) { item ->
                        VideoCard(
                            item = item,
                            avatarUrl = state.channelAvatars[item.video.channelName],
                            onPlay = actions.onPlay,
                            onOpenMenu = actions.onOpenMenu,
                            onOpenChannel = actions.onOpenChannelByName
                        )
                    }
                }
            }
            HomeShelf.HISTORY -> {
                rule()
                block("history-head") {
                    ShelfHeader("Watched lately", count, "See all", actions.onOpenHistory)
                }
                block("history") {
                    KeepWatchingRow(
                        state.recentHistory,
                        onPlay = actions.onPlay,
                        onDismiss = null,
                        rounded = true,
                        width = metrics.railCard,
                        firstFocus = focus
                    )
                }
            }
        }
    }
}

/**
 * The home screen, both shapes.
 *
 * The empty cases come first and are the same on both: no channels at all, or
 * channels the kid cannot see any of. Everything below that is the shelf list.
 */
@Composable
internal fun KidHome(
    state: UiState,
    actions: HomeActions,
    header: @Composable () -> Unit,
    onOpenSettings: () -> Unit,
    formFactor: FormFactor = LocalFormFactor.current
) {
    if (state.channels.isEmpty()) {
        EmptyHome(onOpenSettings, state.allHeld)
        return
    }
    val metrics = homeMetrics(formFactor)
    if (formFactor.isTv) TvHomeColumn(state, actions, header, metrics)
    else PhoneHomeGrid(state, actions, header, metrics)
}

// --- the two pages ---------------------------------------------------------

private class GridPage(
    private val scope: LazyGridScope,
    override val metrics: HomeMetrics
) : HomePage {
    override fun block(key: String, content: @Composable () -> Unit) =
        scope.item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

    override fun hero(items: List<PinnedItem>, firstFocus: FocusRequester?, onOpen: (Source) -> Unit) =
        block("hero") { PinnedHeroCarousel(items, metrics, firstFocus, onOpen) }

    // The phone's page is itself the feed's grid — one card to a line, or as
    // many as fit on a tablet — so the feed's items are the page's own cells.
    override fun feed(items: List<VideoItem>, card: @Composable (VideoItem) -> Unit) =
        scope.items(items, key = { it.video.url }) { card(it) }

    override fun skeleton(count: Int) =
        scope.items(count, key = { "skeleton-$it" }) { SkeletonCard() }
}

private class ColumnPage(
    private val scope: LazyListScope,
    override val metrics: HomeMetrics,
    private val feedColumns: Int
) : HomePage {
    override fun block(key: String, content: @Composable () -> Unit) =
        scope.item(key = key) { content() }

    override fun hero(items: List<PinnedItem>, firstFocus: FocusRequester?, onOpen: (Source) -> Unit) =
        block("hero") { PinnedHeroRow(items, metrics, firstFocus, onOpen) }

    // A column has no cells, so the grid is built: chunks of three, each chunk
    // one lazy row. Partial rows are padded with empty weights, otherwise the
    // last two cards would stretch to fill the line.
    private fun cells(count: Int, key: (Int) -> Any, cell: @Composable (Int) -> Unit) {
        (0 until count).chunked(feedColumns).forEach { chunk ->
            scope.item(key = "cells-${key(chunk.first())}") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)
                ) {
                    chunk.forEach { i -> Box(Modifier.weight(1f)) { cell(i) } }
                    repeat(feedColumns - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    override fun feed(items: List<VideoItem>, card: @Composable (VideoItem) -> Unit) =
        cells(items.size, { items[it].video.url }) { card(items[it]) }

    override fun skeleton(count: Int) = cells(count, { "skeleton-$it" }) { SkeletonCard() }
}

@Composable
private fun PhoneHomeGrid(
    state: UiState,
    actions: HomeActions,
    header: @Composable () -> Unit,
    metrics: HomeMetrics
) {
    BoxWithConstraints {
        // One card to a row on a phone, like YouTube's feed: a poster the
        // width of the screen is what a kid recognises as "a video to watch".
        // Two-up was cramped on phones with display scaling, which is most.
        val columns = if (maxWidth < 600.dp) GridCells.Fixed(1)
        else GridCells.Adaptive(minSize = 240.dp)
        LazyVerticalGrid(
            columns = columns,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 16.dp)
        ) {
            item(key = "app-header", span = { GridItemSpan(maxLineSpan) }) { header() }
            GridPage(this, metrics).drawShelves(state, actions, firstFocus = null, focusShelf = null)
        }
    }
}

@Composable
private fun TvHomeColumn(
    state: UiState,
    actions: HomeActions,
    header: @Composable () -> Unit,
    metrics: HomeMetrics
) {
    // Initial focus lands on the first tile of the topmost populated shelf, so
    // one OK press from a cold start does something. THIS RETRY LOOP IS LEAD:
    // the lazy column composes that shelf's tiles during its next measure, so
    // the first request can land before the requester is attached — and an
    // unattached requestFocus() throws. Short retries cover it; a slow TV
    // needs a couple of seconds. Move or reorder this and focus lands nowhere,
    // which compiles, passes every unit test, and is visible only on a device.
    val firstTileFocus = remember { FocusRequester() }
    // Once only: shelves appear and disappear as the kid watches (Keep
    // watching fills after the first play), and a second request would yank
    // focus away from wherever the remote had since put it.
    var focusedOnce by remember { mutableStateOf(false) }
    val focusShelf = firstFocusableShelf(state.homeSections, homeShelfCounts(state))
    LaunchedEffect(Unit) {
        if (focusedOnce) return@LaunchedEffect
        repeat(20) {
            if (runCatching { firstTileFocus.requestFocus() }.isSuccess) {
                focusedOnce = true
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(150)
        }
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item(key = "header") { header() }
        ColumnPage(this, metrics, feedColumns = 3)
            .drawShelves(state, actions, firstTileFocus, focusShelf)
    }
}

// --- shelf furniture -------------------------------------------------------

/**
 * A shelf's heading: the title, a mono count, and the link out.
 *
 * The count is mono because it is read as digits rather than as a word, and it
 * is the same micro-label the top bar's subtitle uses. "See all" is a link in
 * the action colour and not a button: it is the one thing on the line that
 * goes somewhere, and a filled pill beside a heading reads as the heading's
 * own control.
 */
@Composable
internal fun ShelfHeader(
    title: String,
    count: Int? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    formFactor: FormFactor = LocalFormFactor.current
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)
    ) {
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = if (formFactor.isTv) 22.sp else 17.sp,
                fontWeight = FontWeight.Bold
            )
        )
        if (count != null && count > 0) {
            Spacer(Modifier.width(10.dp))
            Text(
                count.toString(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // The one mono label with no tracking: letter-spaced digits
                // read as two numbers ("3 5"), which is exactly the mistake a
                // count must not invite.
                style = monoLabelStyle(formFactor).copy(letterSpacing = TextUnit(0f, TextUnitType.Em))
            )
        }
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                color = kidTokens.action,
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = if (formFactor.isTv) 16.sp else 14.5.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier
                    .tvFocusHighlight(cornerRadius = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onAction() }
                    // 44 dp of target around a link that draws smaller — the
                    // hit area is the platform minimum, the type is the design's.
                    .padding(horizontal = 10.dp, vertical = 11.dp)
            )
        }
    }
}

/** The mono micro-label: counts, meta, badges. One spelling, both shapes. */
@Composable
internal fun monoLabelStyle(formFactor: FormFactor = LocalFormFactor.current): TextStyle =
    MaterialTheme.typography.labelSmall.copy(
        fontSize = if (formFactor.isTv) 13.sp else 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = TextUnit(0.12f, TextUnitType.Em),
        fontFamily = FontFamily.Monospace
    )

/**
 * The 1 dp rule between two shelves, inset to the gutter.
 *
 * Rows of different shapes ran into one another and a kid scanning for one had
 * no edge to find it by.
 */
@Composable
internal fun ShelfRule() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        // No inset of its own: the block it sits in is already at the page
        // gutter, so the rule runs exactly the width of the shelves it parts.
        modifier = Modifier.padding(top = 8.dp)
    )
}

/**
 * The one control row above the feed: Surprise me, a rule, then the order.
 *
 * The chips keep the app's own vocabulary — New, Random, Popular — and not the
 * handoff's New / Favorites / Most watched. "Favorites" there is a filter over
 * data this app holds per kid on the device and does not sort a cross-channel
 * feed by; inventing it here would be a new feature wearing a restyle's
 * clothes. The pill and the chips are the design's; the words are ours.
 */
@Composable
internal fun FeedControlRow(
    filter: String,
    onFilter: ((String) -> Unit)?,
    onSurprise: () -> Unit,
    extras: (@Composable RowScope.() -> Unit)? = null,
    formFactor: FormFactor = LocalFormFactor.current
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        SurprisePill(onSurprise, formFactor)
        Box(
            Modifier
                .width(1.dp)
                .height(24.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        if (onFilter != null) {
            VIDEO_FILTERS.forEach { value ->
                val (label, icon) = videoFilterLabel(value)
                YosemiteChip(label, selected = filter == value, icon = icon, onClick = { onFilter(value) })
            }
        }
        extras?.invoke(this)
    }
}

/** The one outlined control in the app: a pill in the action colour, on nothing. */
@Composable
private fun SurprisePill(onSurprise: () -> Unit, formFactor: FormFactor) {
    val tokens = kidTokens
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(36.dp)
            .tvFocusHighlight(cornerRadius = 18.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.5.dp, tokens.action, RoundedCornerShape(18.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onSurprise() }
            .padding(horizontal = 14.dp)
    ) {
        androidx.compose.material3.Icon(
            YosemiteIcons.Dice,
            contentDescription = null,
            tint = tokens.action,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            "Surprise me",
            color = tokens.action,
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = if (formFactor.isTv) 15.sp else 13.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

// --- the channels rail -----------------------------------------------------

/**
 * The channels shelf. The same rail on both, at [HomeMetrics]' two sizes —
 * this is what "collapses to a parameter" looks like, and it is why the two
 * home screens could be merged at all.
 */
@Composable
private fun ChannelRail(
    channels: List<Source>,
    newBadges: Set<String>,
    metrics: HomeMetrics,
    firstFocus: FocusRequester?,
    onOpen: (Source) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        // Inset like the rest of the page: a first tile flush against the edge
        // looked cut off, and on a television the focus ring needs somewhere
        // to go.
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
    ) {
        items(channels.size, key = { channels[it].id }) { i ->
            val channel = channels[i]
            ChannelChip(
                name = channel.name + if (channel.kind == SourceKind.PLAYLIST) "  ·  playlist" else "",
                avatarUrl = channel.avatarUrl,
                isNew = channel.id in newBadges,
                art = metrics.channelArt,
                column = metrics.channelColumn,
                newDot = metrics.newDot,
                onClick = { onOpen(channel) },
                modifier = if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus)
                else Modifier
            )
        }
    }
}

// --- the pinned hero -------------------------------------------------------

/**
 * The hero under a thumb: full-width cards that snap, and dots below.
 *
 * Snap because the card is nearly the whole width of the phone — a free
 * scroll leaves two half-cards, which is exactly the state a five-year-old
 * cannot get out of. The dots say how many there are, which a snapped
 * carousel otherwise hides.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PinnedHeroCarousel(
    items: List<PinnedItem>,
    metrics: HomeMetrics,
    firstFocus: FocusRequester?,
    onOpen: (Source) -> Unit
) {
    if (items.isEmpty()) return
    val listState = rememberLazyListState()
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        LazyRow(
            state = listState,
            flingBehavior = androidx.compose.foundation.gestures.snapping
                .rememberSnapFlingBehavior(listState),
            horizontalArrangement = Arrangement.spacedBy(metrics.heroGap),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            items(items.size, key = { items[it].source.id }) { i ->
                PinnedHeroCard(
                    item = items[i],
                    width = metrics.heroWidth,
                    height = metrics.heroHeight,
                    play = metrics.heroPlay,
                    onOpen = onOpen,
                    modifier = if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus)
                    else Modifier
                )
            }
        }
        if (items.size > 1) HeroDots(items.size, listState.firstVisibleItemIndex)
    }
}

/** Which card the carousel is on. 7 dp, the current one in the action colour. */
@Composable
private fun HeroDots(count: Int, current: Int) {
    val tokens = kidTokens
    val idle = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(7.dp)
                    .background(if (i == current) tokens.action else idle, CircleShape)
            )
        }
    }
}

/**
 * The hero under a remote: three equal cards, side by side, none of them
 * scrolling.
 *
 * A carousel is the wrong object here — a d-pad has no fling, and a row that
 * scrolls sideways under focus would move the two cards the kid can already
 * see. [Modifier.weight] rather than a fixed width so the three stay equal on
 * a panel that is not the 960 dp this was drawn against.
 */
@Composable
private fun PinnedHeroRow(
    items: List<PinnedItem>,
    metrics: HomeMetrics,
    firstFocus: FocusRequester?,
    onOpen: (Source) -> Unit
) {
    if (items.isEmpty()) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(metrics.heroGap),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        items.forEachIndexed { i, item ->
            PinnedHeroCard(
                item = item,
                width = null,
                height = metrics.heroHeight,
                play = metrics.heroPlay,
                onOpen = onOpen,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus)
                        else Modifier
                    )
            )
        }
        // Two pinned items must not become two half-width cards: the third
        // slot stays empty rather than the row stretching to fill it.
        repeat((HOME_PINS_MAX - items.size).coerceAtLeast(0)) { Spacer(Modifier.weight(1f)) }
    }
}

/**
 * One hero card, both shapes: the source's art, a PINNED badge, and its name
 * over a gradient with a mono line saying what is waiting.
 *
 * **One target, and the play circle is part of it.** The app has no "play the
 * newest thing from here" action — the circle says there is something to
 * watch, and the card opens the channel, which is where a kid who pressed it
 * expects to arrive. A second, separately focusable control inside a card is
 * also the wrong shape for a d-pad.
 */
@Composable
private fun PinnedHeroCard(
    item: PinnedItem,
    /** Null on the television, where the three cards share the row by weight. */
    width: Dp?,
    height: Dp,
    play: Dp,
    onOpen: (Source) -> Unit,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = LocalFormFactor.current
) {
    val tokens = kidTokens
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(if (formFactor.isTv) 20.dp else 18.dp)
    Box(
        modifier
            .then(if (width == null) Modifier else Modifier.width(width))
            .height(height)
            .pressScale(interaction)
            .tvFocusHighlight(cornerRadius = if (formFactor.isTv) 20.dp else 18.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                onOpen(item.source)
            }
    ) {
        PosterImage(item.source.avatarUrl, item.source.name, Modifier.fillMaxSize())
        // The wash that makes white text legible on any artwork — bottom-up,
        // because that is where the name is.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Transparent,
                        1f to tokens.artworkScrim
                    )
                )
        )
        Text(
            "pinned".uppercase(),
            color = tokens.onArtwork,
            style = monoLabelStyle(formFactor),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(tokens.artworkScrim, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, end = play + 22.dp, bottom = 14.dp)
        ) {
            Text(
                item.source.name,
                color = tokens.onArtwork,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = if (formFactor.isTv) 22.sp else 19.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            if (item.meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    item.meta.uppercase(),
                    color = tokens.onArtwork,
                    maxLines = 1,
                    style = monoLabelStyle(formFactor)
                )
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp)
                .size(play)
                .background(tokens.action, CircleShape)
        ) {
            // Drawn, not the "▶" glyph: the font's side bearings put that mark
            // visibly off-centre in a circle this size. Same fractions as the
            // launcher tile, so the triangle's centroid lands on the middle.
            androidx.compose.foundation.Canvas(Modifier.size(play)) {
                val w = size.width
                val h = size.height
                val triangle = Path().apply {
                    moveTo(w * 0.38f, h * 0.28f)
                    lineTo(w * 0.76f, h * 0.50f)
                    lineTo(w * 0.38f, h * 0.72f)
                    close()
                }
                drawPath(triangle, tokens.onAction)
            }
        }
    }
}
