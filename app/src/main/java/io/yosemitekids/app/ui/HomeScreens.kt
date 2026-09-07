package io.yosemitekids.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import io.yosemitekids.app.data.*

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun KeepWatchingRow(
    items: List<VideoItem>,
    onPlay: (VideoItem) -> Unit,
    /**
     * Hold-to-remove. Null for rows nothing can be dismissed *from* — the
     * suggestions row is a view over the channels, not a list the kid owns,
     * so a hold there has nothing to take the video off.
     */
    onDismiss: ((VideoItem) -> Unit)?,
    rounded: Boolean = false,
    /** TV: the home's initial focus lands on this row's first tile when it is the topmost row. */
    firstFocus: androidx.compose.ui.focus.FocusRequester? = null
) {
    // A hold used to drop the tile silently (marking the video watched on
    // every device) — one accidental toddler-hold and a half-finished film
    // vanished with no way back a kid would find. Now the hold asks.
    var confirm by remember { mutableStateOf<VideoItem?>(null) }
    val haptics = LocalHapticFeedback.current
    confirm?.let { item ->
        val firstAction = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(item) { runCatching { firstAction.requestFocus() } }
        AlertDialog(
            onDismissRequest = { confirm = null },
            shape = androidx.compose.ui.graphics.RectangleShape,
            title = { Text("Done with this one?") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.ignoreSelectUntilRelease()
                ) {
                    MarqueeTitle(item.video.title, focused = false)
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { onDismiss?.invoke(item); confirm = null },
                        modifier = Modifier.fillMaxWidth().focusRequester(firstAction).tvFocusHighlight()
                    ) { Text("✔️  Yes, take it off Keep watching") }
                    TextButton(
                        onClick = { confirm = null },
                        modifier = Modifier.fillMaxWidth().tvFocusHighlight()
                    ) { Text("↩️  No, keep it") }
                }
            },
            confirmButton = {}
        )
    }
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        // Room for the focus glow so it isn't clipped by the row bounds.
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        // Held ◀/▶ paces itself so leftward steps (nothing pre-composed behind
        // the pivot) stop sticking mid-scroll.
        modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
    ) {
        items(items.size, key = { items[it].video.url }) { index ->
            val item = items[index]
            var focused by remember { mutableStateOf(false) }
            val interaction = remember { MutableInteractionSource() }
            Card(
                shape = if (rounded) androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                else androidx.compose.ui.graphics.RectangleShape,
                modifier = Modifier
                    .then(if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                    .pressScale(interaction)
                    .tvFocusHighlight { focused = it }
                    // Touch long-press and remote hold-OK both ask first.
                    .then(
                        if (onDismiss != null) Modifier.dpadLongPress { confirm = item }
                        else Modifier
                    )
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClick = { onPlay(item) },
                        onLongClick = if (onDismiss == null) null else {
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                confirm = item
                            }
                        }
                    )
                    .width(if (rounded) 176.dp else 150.dp)
            ) {
                Column {
                    Box {
                        PosterImage(
                            url = item.video.thumbnailUrl,
                            contentDescription = item.video.title,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                        )
                        item.progress?.let { fraction -> WatchedProgressBar(fraction) }
                    }
                    Column(Modifier.padding(8.dp)) {
                        MarqueeTitle(item.video.title, focused,
                            style = MaterialTheme.typography.bodySmall)
                        // The same meta line the grid tiles carry. These rows
                        // showed the title alone, so a parent who turned on
                        // "show when a video came out" saw no dates anywhere
                        // near the top of the home screen and reasonably
                        // concluded the setting did nothing.
                        videoMeta(item.video.channelName, item.video.publishedAt)
                            .takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The phone/tablet home: a greeting header, Keep watching, a row of round
 * channel chips with "Show all", then the feed — newest videos across every
 * channel as YouTube-style cards, two to a row. The special tiles (Surprise,
 * Favorites, …) moved to the Channels and Favorites tabs; Surprise stays in
 * the channel row where a kid looks for "something to watch".
 */
@Composable
internal fun PhoneHome(
    state: UiState,
    onPlay: (VideoItem) -> Unit,
    onDismissKeepWatching: (VideoItem) -> Unit,
    onOpen: (Source) -> Unit,
    onSurprise: () -> Unit,
    onShowAllChannels: () -> Unit,
    onOpenMenu: (VideoItem) -> Unit,
    onOpenChannelByName: (String) -> Unit,
    onOpenSettings: () -> Unit,
    activeProfile: Profile?,
    onSwitchProfile: (() -> Unit)?,
    onSearch: (String) -> Unit,
    onOpenHub: (() -> Unit)? = null,
    /** The kid's feed order (VIDEO_FILTER_*) and the chip to change it. */
    homeFilter: String = VIDEO_FILTER_NEW,
    onHomeFilter: ((String) -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
    /** The chip row's destinations; null hides the row (TV draws its own). */
    onQuickAction: ((HomeQuickAction) -> Unit)? = null
) {
    if (state.channels.isEmpty()) {
        EmptyHome(onOpenSettings, state.allHeld)
        return
    }
    BoxWithConstraints {
    // One card to a row on a phone, like YouTube's feed: a poster the width
    // of the screen is what a kid recognises as "a video to watch". Two-up
    // was cramped on phones with display scaling, which is most of them.
    val columns = if (maxWidth < 600.dp) GridCells.Fixed(1) else GridCells.Adaptive(minSize = 240.dp)
    LazyVerticalGrid(
        columns = columns,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 16.dp)
    ) {
        item(key = "app-header", span = { GridItemSpan(maxLineSpan) }) {
            HomeHeader(
                onOpenSettings, activeProfile, onSwitchProfile, onSearch,
                state.remainingMs, state.blockReason, greet = true, busy = state.refreshing || state.syncing,
                showSearch = false, onOpenHub = onOpenHub, onOpenSearch = onOpenSearch
            )
        }
        // Channels lead: the kid's own shelf of places, and the thing they
        // reach for first. Then what they were part-way through, then the
        // ways in that aren't channels, then the feed.
        item(key = "channels-title", span = { GridItemSpan(maxLineSpan) }) {
            SectionRow("Channels", action = "Show all", onAction = onShowAllChannels)
        }
        item(key = "channels-row", span = { GridItemSpan(maxLineSpan) }) {
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                // Inset like the rest of the page: a first tile flush against
                // the edge looked cut off.
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                items(state.channels.size, key = { state.channels[it].id }) { i ->
                    val c = state.channels[i]
                    ChannelChip(
                        name = c.name, avatarUrl = c.avatarUrl,
                        isNew = c.id in state.newBadges,
                        onClick = { onOpen(c) }
                    )
                }
            }
        }
        if (state.keepWatching.isNotEmpty()) {
            // A rule between the sections: rows of different shapes ran into
            // one another, and a kid scanning for one had no edge to find it by.
            item(key = "kw-divider", span = { GridItemSpan(maxLineSpan) }) { SectionDivider() }
            item(key = "kw-title", span = { GridItemSpan(maxLineSpan) }) {
                SectionRow("Keep watching")
            }
            item(key = "kw-row", span = { GridItemSpan(maxLineSpan) }) {
                KeepWatchingRow(
                    state.keepWatching, onPlay = onPlay, onDismiss = onDismissKeepWatching,
                    rounded = true
                )
            }
        }
        // Taste, not recency: older videos off the family's own channels that
        // look like what this kid already watched. Sits with Keep watching
        // because it's the same shape; the chips below belong to the grid.
        if (state.suggested.isNotEmpty()) {
            item(key = "sg-divider", span = { GridItemSpan(maxLineSpan) }) { SectionDivider() }
            item(key = "sg-title", span = { GridItemSpan(maxLineSpan) }) {
                SectionRow("More like what you watch")
            }
            item(key = "sg-row", span = { GridItemSpan(maxLineSpan) }) {
                KeepWatchingRow(
                    state.suggested, onPlay = onPlay, onDismiss = null, rounded = true
                )
            }
        }
        // Ways in that aren't channels: a dice, the kid's own shelf, and the
        // two orders they ask for by name. These sat in the channel row as a
        // fake channel, which is not what a round tile in that row means.
        if (onQuickAction != null) {
            item(key = "quick-chips", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .padding(top = 8.dp, bottom = 2.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    YosemiteChip("Surprise me", selected = false, icon = YosemiteIcons.Dice, onClick = onSurprise)
                    YosemiteChip(
                        "Favorites", selected = false, icon = Icons.Filled.Favorite,
                        onClick = { onQuickAction(HomeQuickAction.Favorites) }
                    )
                    YosemiteChip(
                        "Most watched", selected = false, icon = Icons.Filled.Star,
                        onClick = { onQuickAction(HomeQuickAction.MostWatched) }
                    )
                    YosemiteChip(
                        "Latest", selected = false, icon = YosemiteIcons.NewRelease,
                        onClick = { onQuickAction(HomeQuickAction.Latest) }
                    )
                }
            }
        }
        item(key = "feed-divider", span = { GridItemSpan(maxLineSpan) }) { SectionDivider() }
        item(key = "feed-title", span = { GridItemSpan(maxLineSpan) }) {
            // The title stays put and the chips ride its line; a title that
            // changed with the sort read as a different section, and a chip
            // row of its own cost a band of screen.
            SectionRow(
                "For you",
                trailing = onHomeFilter?.let { set -> { VideoFilterChips(homeFilter, set) } }
            )
        }
        if (state.feed.isEmpty()) {
            // The caches are still warming (first launch, or a new channel):
            // breathing placeholders read as "coming", a spinner as "stuck".
            items(6, key = { "skeleton-$it" }) { SkeletonCard() }
        } else {
            items(state.feed, key = { it.video.url }) { item ->
                VideoCard(
                    item = item,
                    avatarUrl = state.channelAvatars[item.video.channelName],
                    onPlay = onPlay,
                    onOpenMenu = onOpenMenu,
                    onOpenChannel = onOpenChannelByName
                )
            }
        }
    }
    }
}

/**
 * The home chip row's destinations. Surprise has its own screen; the other
 * three open a shelf the kid names rather than a filter they have to build:
 * their Favorites, and the channels in most-watched or latest-upload order.
 */
internal enum class HomeQuickAction { Favorites, MostWatched, Latest }

/** A quiet rule between two home sections. */
@Composable
internal fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(top = 6.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

/** Label and icon for a video-list order, one spelling for chips on both form factors. */
internal fun videoFilterLabel(filter: String): Pair<String, androidx.compose.ui.graphics.vector.ImageVector> =
    when (filter) {
        VIDEO_FILTER_RANDOM -> "Random" to YosemiteIcons.Shuffle
        VIDEO_FILTER_POPULAR -> "Popular" to YosemiteIcons.Flame
        else -> "New" to YosemiteIcons.Sparkle
    }

/** Label and icon for a channel order. */
internal fun channelSortLabel(sort: String): Pair<String, androidx.compose.ui.graphics.vector.ImageVector> =
    when (sort) {
        CHANNEL_ORDER_ALPHA -> "A to Z" to YosemiteIcons.SortAlpha
        CHANNEL_ORDER_RANDOM -> "Random" to YosemiteIcons.Shuffle
        CHANNEL_ORDER_LATEST -> "Latest video" to YosemiteIcons.NewRelease
        else -> "Most watched" to Icons.Filled.Star
    }

/**
 * New · Random · Popular — the kid's own order for a video list. Chips,
 * not a menu: three big targets a small thumb (or a remote) can hit. The
 * random one reshuffles on each press, so pressing it again is "mix again".
 */
@Composable
internal fun VideoFilterChips(selected: String, onSelect: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        VIDEO_FILTERS.forEach { value ->
            val (label, icon) = videoFilterLabel(value)
            YosemiteChip(label, selected = selected == value, icon = icon, onClick = { onSelect(value) })
        }
    }
}

/** Most watched · A to Z · Random · Latest video — the kid's order for the channels. */
@Composable
internal fun ChannelSortChips(selected: String, onSelect: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        KID_CHANNEL_SORTS.forEach { value ->
            val (label, icon) = channelSortLabel(value)
            YosemiteChip(label, selected = selected == value, icon = icon, onClick = { onSelect(value) })
        }
    }
}

/**
 * The Channels tab on phones: every channel as a rounded tile, two to a
 * row (a 150 dp adaptive grid gave one wide tile per row on some phones),
 * with the shelves that aren't channels (Up next, Watch later, Downloads)
 * leading and the sort chips on top.
 */
@Composable
internal fun ChannelsScreen(
    state: UiState,
    onOpen: (Source) -> Unit,
    onSurprise: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenWatchLater: () -> Unit,
    onOpenDownloads: () -> Unit,
    onSort: ((String) -> Unit)? = null
) {
    BoxWithConstraints {
        // Two on a phone, as many as fit on a tablet in landscape.
        val columns = if (maxWidth < 600.dp) GridCells.Fixed(2) else GridCells.Adaptive(minSize = 170.dp)
        LazyVerticalGrid(
            columns = columns,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(8.dp)
        ) {
            if (onSort != null) {
                item(key = "sort", span = { GridItemSpan(maxLineSpan) }) {
                    ChannelSortChips(state.channelSort, onSort)
                }
            }
            item(key = "surprise-tile") {
                SpecialTile("", "Surprise me", SurpriseTileCyan, rounded = true, icon = YosemiteIcons.Dice, onClick = onSurprise)
            }
            if (state.queued.isNotEmpty()) item(key = "queue-tile") {
                SpecialTile("", "Up next", QueueTilePurple, rounded = true, icon = YosemiteIcons.UpNext, onClick = onOpenQueue)
            }
            if (state.watchLater.isNotEmpty()) item(key = "watch-later-tile") {
                SpecialTile("", "Watch later", WatchLaterTileTeal, rounded = true, icon = YosemiteIcons.WatchLater, onClick = onOpenWatchLater)
            }
            if (state.downloaded.isNotEmpty()) item(key = "downloads-tile") {
                SpecialTile("", "Downloads", DownloadsTileTeal, rounded = true, icon = YosemiteIcons.Download, onClick = onOpenDownloads)
            }
            items(state.channels, key = { it.id }) { channel ->
                ChannelTile(channel, isNew = channel.id in state.newBadges, onOpen = onOpen, rounded = true)
            }
        }
    }
}

@Composable
internal fun ChannelGrid(
    channels: List<Source>,
    newBadges: Set<String> = emptySet(),
    keepWatching: List<VideoItem> = emptyList(),
    onPlay: (VideoItem) -> Unit = {},
    onDismissKeepWatching: (VideoItem) -> Unit = {},
    onOpen: (Source) -> Unit,
    onSurprise: () -> Unit,
    onOpenWatchlist: () -> Unit,
    hasWatchLater: Boolean = false,
    onOpenWatchLater: () -> Unit = {},
    hasQueue: Boolean = false,
    onOpenQueue: () -> Unit = {},
    hasDownloads: Boolean = false,
    onOpenDownloads: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    activeProfile: io.yosemitekids.app.data.Profile? = null,
    onSwitchProfile: (() -> Unit)? = null,
    onSearch: (String) -> Unit = {},
    remainingMs: Long? = null,
    blockReason: String? = null,
    allHeld: Boolean = false
) {
    if (channels.isEmpty()) {
        EmptyHome(onOpenSettings, allHeld)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        // Room for the focus glow on edge tiles.
        contentPadding = PaddingValues(8.dp)
    ) {
        // Branding + settings scroll away like everything else — content is king.
        item(key = "app-header", span = { GridItemSpan(maxLineSpan) }) {
            HomeHeader(onOpenSettings, activeProfile, onSwitchProfile, onSearch, remainingMs, blockReason)
        }
        // Keep-watching scrolls away with the rest — not sticky.
        if (keepWatching.isNotEmpty()) {
            item(key = "kw-title", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Keep watching",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                )
            }
            item(key = "kw-row", span = { GridItemSpan(maxLineSpan) }) {
                KeepWatchingRow(keepWatching, onPlay = onPlay, onDismiss = onDismissKeepWatching)
            }
        }
        // First tile: Surprise — same size and shape as a channel tile.
        item(key = "surprise-tile") {
            SurpriseTile(onClick = onSurprise)
        }
        // The lined-up videos, only while there are any — an empty queue tile
        // would just be a dead end for the kid.
        if (hasQueue) {
            item(key = "queue-tile") {
                QueueTile(onClick = onOpenQueue)
            }
        }
        // Second tile: the kid's hearted videos.
        item(key = "watchlist-tile") {
            WatchlistTile(onClick = onOpenWatchlist)
        }
        // Watch later earns its tile only once something is in it — an empty
        // shelf is a dead end for the kid, same reasoning as the queue tile.
        if (hasWatchLater) {
            item(key = "watch-later-tile") {
                WatchLaterTile(onClick = onOpenWatchLater)
            }
        }
        // The offline shelf appears once the first download lands.
        if (hasDownloads) {
            item(key = "downloads-tile") {
                SpecialTile(
                    emoji = "⬇️",
                    label = "Downloads",
                    circleColor = DownloadsTileTeal,
                    onClick = onOpenDownloads
                )
            }
        }
        items(channels, key = { it.id }) { channel ->
            ChannelTile(channel, isNew = channel.id in newBadges, onOpen = onOpen)
        }
    }
}

@Composable
private fun ChannelTile(
    channel: Source,
    isNew: Boolean,
    onOpen: (Source) -> Unit,
    modifier: Modifier = Modifier,
    rounded: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Card(
        shape = if (rounded) androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
        else androidx.compose.ui.graphics.RectangleShape,
        modifier = modifier
            .pressScale(interaction)
            .tvFocusHighlight { focused = it }
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                onOpen(channel)
            }
    ) {
        Column {
            // Full-bleed cover, video-tile style (1:1 — avatars are square).
            Box {
                PosterImage(
                    url = channel.avatarUrl,
                    contentDescription = channel.name,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
                if (isNew) NewPill(Modifier.align(Alignment.TopEnd).padding(8.dp))
                // The screen-time "price tag" — shown only when it differs from
                // normal, so kids can pick cheap/free channels knowingly.
                timeMultiplierColor(channel.timeMultiplierPercent)?.let { color ->
                    Text(
                        timeMultiplierLabel(channel.timeMultiplierPercent),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(color)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Box(Modifier.padding(8.dp)) {
                // A step up from body text: channel names are the things a
                // kid reads on this screen, at arm's length or from the couch.
                MarqueeTitle(
                    text = channel.name +
                        if (channel.kind == SourceKind.PLAYLIST) "  ·  playlist" else "",
                    focused = focused,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }
}

/**
 * First-run screen: without it a fresh install is a dead end — the settings
 * entry lives in the home header, which only renders once channels exist.
 * The button auto-focuses so a TV remote can open settings with one press.
 * [allHeld] is the other empty: channels exist, screening is holding all of
 * them, and the fix is on the parent's phone — so say that, not "add channels".
 */
@Composable
private fun EmptyHome(onOpenSettings: () -> Unit, allHeld: Boolean = false) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            if (allHeld) "🔎" else "📺",
            fontSize = androidx.compose.ui.unit.TextUnit(64f, androidx.compose.ui.unit.TextUnitType.Sp)
        )
        Text(
            if (allHeld) "A grown-up is checking your videos" else "No channels yet",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            if (allHeld) "They'll show up here as soon as a parent says OK on their phone."
            else "Add channels in parent settings to fill this screen.",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Button(
            onClick = onOpenSettings,
            modifier = Modifier
                .focusRequester(focus)
                .tvFocusHighlight()
        ) {
            Text("Parent settings")
        }
    }
}

@Composable
internal fun HomeHeader(
    onOpenSettings: () -> Unit,
    activeProfile: io.yosemitekids.app.data.Profile? = null,
    onSwitchProfile: (() -> Unit)? = null,
    onSearch: (String) -> Unit = {},
    remainingMs: Long? = null,
    blockReason: String? = null,
    /** Phones greet the kid by name; TV keeps the wordmark. */
    greet: Boolean = false,
    /**
     * Something is arriving — new videos, settings syncing. Draws a slow arc
     * round the avatar. This header renders its own avatar rather than going
     * through HeaderActions, so it needs telling separately; the home screen
     * is the one place a parent actually watches for a change to land.
     */
    busy: Boolean = false,
    /** The inline search icon — off when the Search tab exists. */
    showSearch: Boolean = true,
    /**
     * The profile hub (switch kid, change my look, parent settings 🔒) behind
     * the avatar. When set, the gear leaves the header — the hub is the one
     * door to settings; when null (legacy callers) the header keeps the gear.
     */
    onOpenHub: (() -> Unit)? = null,
    /** Phones: the search icon opens the search page instead of an inline field. */
    onOpenSearch: (() -> Unit)? = null,
    formFactor: FormFactor = LocalFormFactor.current
) {
    // Collapsed by default: the field costs a full row of home space, so it
    // appears only when the search icon is tapped. State lives here so both
    // home layouts share the behavior.
    var searchOpen by remember { mutableStateOf(false) }
    // The phone's chrome is one bar shared with the Channels and You tabs, so
    // home does not get to invent its own. The ten-foot header below is a
    // different object entirely — no tab bar under it, a remote rather than a
    // thumb, and the wordmark instead of a greeting — and stays where it is.
    if (formFactor.isPhone) {
        Column(Modifier.fillMaxWidth()) {
            PhoneTopBar(
                title = if (greet && activeProfile != null) "Hi, ${activeProfile.name}"
                else "Yosemite Kids",
                profile = activeProfile,
                onOpenHub = onOpenHub ?: onSwitchProfile,
                onOpenSearch = onOpenSearch,
                // Hidden while a rule blocks watching outright — the banner
                // below says why instead, and says it in words.
                remainingMs = remainingMs?.takeIf { blockReason == null },
                busy = busy
            )
            blockReason?.let { Box(Modifier.padding(horizontal = 16.dp)) { BlockedBanner(it) } }
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            // Mini logo mark: dark teal square, white play triangle — the
            // launcher tile at header scale.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .background(Color(0xFF00695C), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            ) {
                // Drawn, not the "▶" glyph: font side bearings and line-height
                // padding left that mark visibly off-centre in the tile. These
                // fractions are ic_launcher.xml's triangle scaled to the tile,
                // so the centroid — not the bounding box — lands on the centre,
                // which is what the eye reads as centred.
                Canvas(Modifier.size(30.dp)) {
                    val w = size.width
                    val h = size.height
                    val play = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.38f, h * 0.28f)
                        lineTo(w * 0.76f, h * 0.50f)
                        lineTo(w * 0.38f, h * 0.72f)
                        close()
                    }
                    drawPath(play, Color.White)
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (greet && activeProfile != null) "Hi, ${activeProfile.name}" else "Yosemite Kids",
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                style = MaterialTheme.typography.titleLarge
            )
            // Time left rides the header line rather than taking a band of
            // its own. Hidden while a rule blocks watching outright — the
            // banner below says why instead.
            if (remainingMs != null && blockReason == null) {
                Spacer(Modifier.width(10.dp))
                TimeChip(remainingMs)
            }
        }
        // Search is a task, not a place: one icon in every header. Phones
        // open the search page; the TV (no tabs, no page) unfolds the field.
        if (showSearch || onOpenSearch != null) {
            HeaderIconButton(Icons.Filled.Search, "Search", onClick = {
                if (onOpenSearch != null) onOpenSearch() else searchOpen = !searchOpen
            })
        }
        // Whose home this is — always visible so a wrong pick gets noticed.
        // With a hub it is the kid's own corner (switch, look, the locked
        // settings door); without one, on shared devices, it is the way back
        // to the who's-watching screen.
        val onChip: (() -> Unit)? = onOpenHub ?: onSwitchProfile
        if (activeProfile != null || onOpenHub != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .let { m ->
                        if (onChip != null) {
                            m.tvFocusHighlight()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                                .clickable { onChip() }
                        } else m
                    }
                    // A 48 dp-tall target: the old 4 dp of padding made the
                    // profile chip the hardest thing on the screen to hit.
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (activeProfile != null) {
                    Box(contentAlignment = Alignment.Center) {
                        if (busy) BusyRing()
                        ProfileAvatar(activeProfile, size = 36)
                    }
                    if (!greet) {
                        Spacer(Modifier.width(6.dp))
                        Text(activeProfile.name, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    // No kid named yet: the hub still opens (it holds settings).
                    Icon(Icons.Filled.Person, contentDescription = "Profile", modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(6.dp))
        }
        if (onOpenHub == null) IconButton(
            modifier = Modifier.size(48.dp),
            onClick = onOpenSettings
        ) {
            val updatePending by UpdateEvents.pending.collectAsState()
            Box {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Settings",
                    modifier = Modifier.size(32.dp)
                )
                // Quiet "a newer build exists" nudge for the parent; settings
                // itself stays behind the PIN, so kids tapping it learn nothing.
                if (updatePending != null) {
                    Box(
                        Modifier.align(Alignment.TopEnd).size(8.dp)
                            .background(UpdateDot, androidx.compose.foundation.shape.CircleShape)
                    )
                }
            }
        }
    }
    blockReason?.let { BlockedBanner(it) }
    if (searchOpen) SearchField(onSearch)
    }
}

/**
 * The ten-foot home: horizontal rows under a pinned focus, like every
 * streaming app's browse screen. Keep watching first, then "New for you"
 * (the same newest-across-channels feed the phone shows as cards), the
 * channels as round chips, what was watched lately, and the special shelves.
 * The remote does everything: OK plays or opens, a held OK opens the same
 * menu a phone long-press does. No touch, so no pull-to-refresh either.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TvHomeRows(
    channels: List<Source>,
    newBadges: Set<String>,
    keepWatching: List<VideoItem>,
    /** "More like what you watch"; empty when off or when nothing is watched yet. */
    suggested: List<VideoItem> = emptyList(),
    /** Something is arriving — draws the slow arc round the avatar. */
    busy: Boolean = false,
    feed: List<VideoItem> = emptyList(),
    recentHistory: List<VideoItem> = emptyList(),
    channelAvatars: Map<String, String?> = emptyMap(),
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: ((VideoItem) -> Unit)? = null,
    onDismissKeepWatching: (VideoItem) -> Unit,
    onOpen: (Source) -> Unit,
    onOpenHistory: () -> Unit = {},
    onSurprise: () -> Unit,
    onOpenWatchlist: () -> Unit,
    hasWatchLater: Boolean = false,
    onOpenWatchLater: () -> Unit = {},
    hasQueue: Boolean = false,
    onOpenQueue: () -> Unit = {},
    onOpenSettings: () -> Unit,
    activeProfile: io.yosemitekids.app.data.Profile? = null,
    onSwitchProfile: (() -> Unit)? = null,
    onSearch: (String) -> Unit = {},
    remainingMs: Long? = null,
    blockReason: String? = null,
    allHeld: Boolean = false,
    onOpenHub: (() -> Unit)? = null,
    onOpenYou: (() -> Unit)? = null,
    /** The kid's chips, as one cycling chip in each row's title (a chip row per title is too much for a remote). */
    channelSort: String = CHANNEL_ORDER_WATCHED,
    onSort: ((String) -> Unit)? = null,
    homeFilter: String = VIDEO_FILTER_NEW,
    onHomeFilter: ((String) -> Unit)? = null
) {
    if (channels.isEmpty()) {
        EmptyHome(onOpenSettings, allHeld)
        return
    }
    // Initial focus lands on the first tile of the topmost video row, so one
    // OK press from a cold start plays something. Re-requested when the feed
    // first arrives: the caches paint after the channel list, and a request
    // made before the tile exists lands nowhere.
    val firstTileFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Channels lead the page now, so the remote starts there: focus that
    // jumped to Keep watching opened the home already scrolled past them.
    val focusKw = false
    val focusFeed = false
    // Once only: rows appear and disappear as the kid watches (Keep watching
    // fills after the first play), and a second request would yank focus
    // away from wherever the remote had put it.
    var focusedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(focusKw, focusFeed) {
        if (focusedOnce) return@LaunchedEffect
        // The lazy column composes the row's tiles during its next measure,
        // so the first request can land before the requester is attached
        // (it throws); short retries cover that — a slow TV needs a couple
        // of seconds.
        repeat(20) {
            if (runCatching { firstTileFocus.requestFocus() }.isSuccess) {
                focusedOnce = true
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(150)
        }
    }

    androidx.compose.foundation.lazy.LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item(key = "header") {
            HomeHeader(
                onOpenSettings, activeProfile, onSwitchProfile, onSearch, remainingMs, blockReason,
                busy = busy, onOpenHub = onOpenHub
            )
        }


        item(key = "channels") {
            TvRow(
                "Channels",
                chip = onSort?.let { set ->
                    {
                        val (label, icon) = channelSortLabel(channelSort)
                        CycleChip(
                            label = label,
                            icon = icon,
                            onClick = {
                                set(
                                    when (channelSort) {
                                        CHANNEL_ORDER_WATCHED -> CHANNEL_ORDER_ALPHA
                                        CHANNEL_ORDER_ALPHA -> CHANNEL_ORDER_RANDOM
                                        CHANNEL_ORDER_RANDOM -> CHANNEL_ORDER_LATEST
                                        else -> CHANNEL_ORDER_WATCHED
                                    }
                                )
                            }
                        )
                    }
                }
            ) {
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
                ) {
                    items(channels.size, key = { channels[it].id }) { i ->
                        TvChannelChip(
                            channels[i],
                            isNew = channels[i].id in newBadges,
                            onOpen = onOpen,
                            modifier = if (i == 0) Modifier.focusRequester(firstTileFocus) else Modifier
                        )
                    }
                }
            }
        }

        if (keepWatching.isNotEmpty()) {
            item(key = "kw") {
                TvRow("Keep watching") {
                    KeepWatchingRow(
                        keepWatching, onPlay = onPlay, onDismiss = onDismissKeepWatching, rounded = true,
                        firstFocus = null
                    )
                }
            }
        }
        // Taste, not recency: older videos off the family's own channels that
        // match what this kid already watched.
        if (suggested.isNotEmpty()) {
            item(key = "suggested") {
                TvRow("More like what you watch") {
                    KeepWatchingRow(
                        suggested, onPlay = onPlay, onDismiss = null, rounded = true,
                        firstFocus = null
                    )
                }
            }
        }
        // The ways in that aren't channels, as the same chips the phone
        // shows. They were square tiles in an "Explore" row, which put two
        // different shapes and two different idioms on one page.
        item(key = "quick-chips") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)
            ) {
                YosemiteChip("Surprise me", selected = false, icon = YosemiteIcons.Dice, onClick = onSurprise)
                if (onOpenYou != null) {
                    YosemiteChip("You", selected = false, icon = Icons.Filled.Person, onClick = onOpenYou)
                } else {
                    YosemiteChip("Favorites", selected = false, icon = Icons.Filled.Favorite, onClick = onOpenWatchlist)
                    if (hasWatchLater) {
                        YosemiteChip("Watch later", selected = false, icon = YosemiteIcons.WatchLater, onClick = onOpenWatchLater)
                    }
                }
                if (hasQueue) {
                    YosemiteChip("Up next", selected = false, icon = YosemiteIcons.UpNext, onClick = onOpenQueue)
                }
            }
        }

        if (feed.isNotEmpty()) {
            item(key = "feed") {
                TvRow(
                    "For you",
                    chip = onHomeFilter?.let { set ->
                        {
                            val (label, icon) = videoFilterLabel(homeFilter)
                            CycleChip(
                                label = label,
                                icon = icon,
                                onClick = {
                                    set(
                                        when (homeFilter) {
                                            VIDEO_FILTER_NEW -> VIDEO_FILTER_RANDOM
                                            VIDEO_FILTER_RANDOM -> VIDEO_FILTER_POPULAR
                                            else -> VIDEO_FILTER_NEW
                                        }
                                    )
                                }
                            )
                        }
                    }
                ) {
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
                    ) {
                        items(feed.size, key = { feed[it].video.url }) { i ->
                            val item = feed[i]
                            ShelfVideoTile(
                                item,
                                avatarUrl = channelAvatars[item.video.channelName],
                                onPlay = onPlay,
                                onOpenMenu = onOpenMenu,
                                modifier = if (i == 0 && focusFeed) Modifier.focusRequester(firstTileFocus) else Modifier
                            )
                        }
                    }
                }
            }
        }

        if (recentHistory.isNotEmpty()) {
            item(key = "history") {
                // "All history" is the row's own action, beside its title —
                // it used to be a tile of a different shape inside the row.
                TvRow(
                    "Watched lately",
                    chip = {
                        YosemiteChip("All history", selected = false, icon = YosemiteIcons.History, onClick = onOpenHistory)
                    }
                ) {
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
                    ) {
                        items(recentHistory.size, key = { recentHistory[it].video.url }) { i ->
                            val item = recentHistory[i]
                            ShelfVideoTile(
                                item,
                                avatarUrl = channelAvatars[item.video.channelName],
                                onPlay = onPlay,
                                onOpenMenu = onOpenMenu
                            )
                        }
                    }
                }
            }
        }

    }
}

/** A titled TV row whose horizontal scroll pivots on the focused tile; an optional chip sits by the title. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TvRow(title: String, chip: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column {
        if (chip == null) TvRowTitle(title) else Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            TvRowTitle(title)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.padding(top = 10.dp)) { chip() }
        }
        CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides TvRowPivot
        ) { content() }
    }
}

/** One focusable chip that steps to the next choice on OK — the remote's version of a chip row. */
@Composable
private fun CycleChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    YosemiteChip(label, selected = false, icon = icon, onClick = onClick)
}

/**
 * A channel in the TV row: the round avatar every video app uses for
 * "channel", big enough to read from the couch, with the NEW dot and the
 * screen-time price tag the square tile used to carry.
 */
@Composable
private fun TvChannelChip(
    channel: Source,
    isNew: Boolean,
    onOpen: (Source) -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(136.dp)
            .pressScale(interaction)
            .tvFocusHighlight { focused = it }
            .clip(RoundedCornerShape(20.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                onOpen(channel)
            }
            .padding(vertical = 10.dp, horizontal = 6.dp)
    ) {
        Box {
            ChannelArt(channel.avatarUrl, channel.name, size = 96.dp)
            if (isNew) NewPill(Modifier.align(Alignment.TopEnd).padding(4.dp))
            timeMultiplierColor(channel.timeMultiplierPercent)?.let { color ->
                Text(
                    timeMultiplierLabel(channel.timeMultiplierPercent),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(color, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        MarqueeTitle(
            text = channel.name +
                if (channel.kind == SourceKind.PLAYLIST) "  ·  playlist" else "",
            focused = focused,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

// One spelling per special tile: the phone grid and the TV row draw the same
// ones, and a repaint of one layout must not drift from the other.
@Composable
private fun SurpriseTile(modifier: Modifier = Modifier, onClick: () -> Unit) =
    SpecialTile("", "Surprise me", SurpriseTileCyan, modifier = modifier, icon = YosemiteIcons.Dice, onClick = onClick)

@Composable
private fun QueueTile(modifier: Modifier = Modifier, onClick: () -> Unit) =
    SpecialTile("", "Up next", QueueTilePurple, modifier = modifier, icon = YosemiteIcons.UpNext, onClick = onClick)

@Composable
private fun WatchlistTile(modifier: Modifier = Modifier, onClick: () -> Unit) =
    SpecialTile("", "Favorites", WatchlistTileTeal, modifier = modifier, icon = Icons.Filled.Favorite, onClick = onClick)

@Composable
private fun WatchLaterTile(modifier: Modifier = Modifier, onClick: () -> Unit) =
    SpecialTile("", "Watch later", WatchLaterTileTeal, modifier = modifier, icon = YosemiteIcons.WatchLater, onClick = onClick)

@Composable
private fun TvRowTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        ),
        modifier = Modifier.padding(start = 8.dp, top = 12.dp)
    )
}
