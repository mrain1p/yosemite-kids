package io.pickwick.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
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
import io.pickwick.app.data.*

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun KeepWatchingRow(
    items: List<VideoItem>,
    onPlay: (VideoItem) -> Unit,
    onDismiss: (VideoItem) -> Unit,
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
                        onClick = { onDismiss(item); confirm = null },
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
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
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
                    .dpadLongPress { confirm = item }
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClick = { onPlay(item) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            confirm = item
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
                    Box(Modifier.padding(8.dp)) {
                        MarqueeTitle(item.video.title, focused,
                            style = MaterialTheme.typography.bodySmall)
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
    onSearch: (String) -> Unit
) {
    if (state.channels.isEmpty()) {
        EmptyHome(onOpenSettings, state.allHeld)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 170.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 24.dp)
    ) {
        item(key = "app-header", span = { GridItemSpan(maxLineSpan) }) {
            HomeHeader(
                onOpenSettings, activeProfile, onSwitchProfile, onSearch,
                state.remainingMs, state.blockReason, greet = true, showSearch = false
            )
        }
        if (state.keepWatching.isNotEmpty()) {
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
        item(key = "channels-title", span = { GridItemSpan(maxLineSpan) }) {
            SectionRow("Channels", action = "Show all", onAction = onShowAllChannels)
        }
        item(key = "channels-row", span = { GridItemSpan(maxLineSpan) }) {
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                item(key = "surprise") {
                    ChannelChip(
                        name = "Surprise me", avatarUrl = null, isNew = false,
                        emoji = "🎲", tint = SurpriseTileCyan, onClick = onSurprise
                    )
                }
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
        item(key = "feed-title", span = { GridItemSpan(maxLineSpan) }) {
            SectionRow("New for you")
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

/**
 * The Channels tab on phones: every channel as a rounded tile, with the
 * shelves that aren't channels (Up next, Watch later, Downloads) leading.
 */
@Composable
internal fun ChannelsScreen(
    state: UiState,
    onOpen: (Source) -> Unit,
    onSurprise: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenWatchLater: () -> Unit,
    onOpenDownloads: () -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(8.dp)
    ) {
        item(key = "surprise-tile") {
            SpecialTile("🎲", "Surprise me!", SurpriseTileCyan, rounded = true, onClick = onSurprise)
        }
        if (state.queued.isNotEmpty()) item(key = "queue-tile") {
            SpecialTile("📚", "Up next", QueueTilePurple, rounded = true, onClick = onOpenQueue)
        }
        if (state.watchLater.isNotEmpty()) item(key = "watch-later-tile") {
            SpecialTile("🕒", "Watch later", WatchLaterTileTeal, rounded = true, onClick = onOpenWatchLater)
        }
        if (state.downloaded.isNotEmpty()) item(key = "downloads-tile") {
            SpecialTile("⬇️", "Downloads", DownloadsTileTeal, rounded = true, onClick = onOpenDownloads)
        }
        items(state.channels, key = { it.id }) { channel ->
            ChannelTile(channel, isNew = channel.id in state.newBadges, onOpen = onOpen, rounded = true)
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
    activeProfile: io.pickwick.app.data.Profile? = null,
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
                if (isNew) {
                    Text(
                        "NEW",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color(0xFF4DB6AC))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
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
    activeProfile: io.pickwick.app.data.Profile? = null,
    onSwitchProfile: (() -> Unit)? = null,
    onSearch: (String) -> Unit = {},
    remainingMs: Long? = null,
    blockReason: String? = null,
    /** Phones greet the kid by name; TV keeps the wordmark. */
    greet: Boolean = false,
    /** The inline search icon — off when the Search tab exists. */
    showSearch: Boolean = true
) {
    // Collapsed by default: the field costs a full row of home space, so it
    // appears only when the search icon is tapped. State lives here so both
    // home layouts share the behavior.
    var searchOpen by remember { mutableStateOf(false) }
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
                    .background(Color(0xFF00695C), androidx.compose.foundation.shape.RoundedCornerShape(if (greet) 8.dp else 0.dp))
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
                if (greet && activeProfile != null) "Hi, ${activeProfile.name}! 👋" else "Pickwick",
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold,
                    letterSpacing = androidx.compose.ui.unit.TextUnit(0.5f, androidx.compose.ui.unit.TextUnitType.Sp)
                )
            )
        }
        // Whose home this is — always visible so a wrong pick gets noticed.
        // On shared devices it's also the way back to the who's-watching screen.
        activeProfile?.let { profile ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .let { m ->
                        if (onSwitchProfile != null) {
                            m.tvFocusHighlight()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                                .clickable { onSwitchProfile() }
                        } else m
                    }
                    // A 48 dp-tall target: the old 4 dp of padding made the
                    // profile chip the hardest thing on the screen to hit.
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                ProfileAvatar(profile, size = 32)
                if (!greet) {
                    Spacer(Modifier.width(6.dp))
                    Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.width(6.dp))
        }
        if (showSearch) {
            IconButton(
                modifier = Modifier.size(48.dp),
                onClick = { searchOpen = !searchOpen }
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = if (searchOpen) "Close search" else "Search",
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        IconButton(
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
    // Screen time left, on its own line: a phone's header row is already full
    // (wordmark, avatar, two icons), and squeezing a chip in there folded the
    // wordmark into a vertical column. Hidden while a rule blocks watching
    // outright — the banner says why instead.
    if (remainingMs != null && blockReason == null) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) { TimeChip(remainingMs) }
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
    activeProfile: io.pickwick.app.data.Profile? = null,
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
    // Initial focus lands on the first tile of the topmost video row, so one
    // OK press from a cold start plays something. Re-requested when the feed
    // first arrives: the caches paint after the channel list, and a request
    // made before the tile exists lands nowhere.
    val firstTileFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val focusKw = keepWatching.isNotEmpty()
    val focusFeed = !focusKw && feed.isNotEmpty()
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
            HomeHeader(onOpenSettings, activeProfile, onSwitchProfile, onSearch, remainingMs, blockReason)
        }

        if (keepWatching.isNotEmpty()) {
            item(key = "kw") {
                TvRow("Keep watching") {
                    KeepWatchingRow(
                        keepWatching, onPlay = onPlay, onDismiss = onDismissKeepWatching, rounded = true,
                        firstFocus = firstTileFocus
                    )
                }
            }
        }

        if (feed.isNotEmpty()) {
            item(key = "feed") {
                TvRow("New for you") {
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
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

        item(key = "channels") {
            TvRow("Channels") {
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
                            modifier = if (i == 0 && !focusFeed && !focusKw) Modifier.focusRequester(firstTileFocus)
                            else Modifier
                        )
                    }
                }
            }
        }

        if (recentHistory.isNotEmpty()) {
            item(key = "history") {
                TvRow("Watched lately") {
                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
                    ) {
                        item(key = "all-history") {
                            SpecialTile(
                                "🕘", "All history", MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.width(150.dp), onClick = onOpenHistory
                            )
                        }
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

        item(key = "explore") {
            TvRow("Explore") {
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    // Lined-up videos lead the row while any exist.
                    if (hasQueue) {
                        item(key = "queue") {
                            QueueTile(Modifier.width(150.dp), onOpenQueue)
                        }
                    }
                    item(key = "surprise") {
                        SurpriseTile(Modifier.width(150.dp), onSurprise)
                    }
                    item(key = "watchlist") {
                        WatchlistTile(Modifier.width(150.dp), onOpenWatchlist)
                    }
                    if (hasWatchLater) {
                        item(key = "watch-later") {
                            WatchLaterTile(Modifier.width(150.dp), onOpenWatchLater)
                        }
                    }
                }
            }
        }
    }
}

/** A titled TV row whose horizontal scroll pivots on the focused tile. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TvRow(title: String, content: @Composable () -> Unit) {
    Column {
        TvRowTitle(title)
        CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides TvRowPivot
        ) { content() }
    }
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
            Box(
                Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                PosterImage(channel.avatarUrl, channel.name, Modifier.fillMaxSize())
            }
            if (isNew) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(18.dp)
                        .background(Color(0xFF4DB6AC), CircleShape)
                )
            }
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
    SpecialTile("🎲", "Surprise me!", SurpriseTileCyan, modifier = modifier, onClick = onClick)

@Composable
private fun QueueTile(modifier: Modifier = Modifier, onClick: () -> Unit) =
    SpecialTile("📚", "Up next", QueueTilePurple, modifier = modifier, onClick = onClick)

@Composable
private fun WatchlistTile(modifier: Modifier = Modifier, onClick: () -> Unit) =
    SpecialTile("❤️", "Favorites", WatchlistTileTeal, modifier = modifier, onClick = onClick)

@Composable
private fun WatchLaterTile(modifier: Modifier = Modifier, onClick: () -> Unit) =
    SpecialTile("🕒", "Watch later", WatchLaterTileTeal, modifier = modifier, onClick = onClick)

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
