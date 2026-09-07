package io.yosemitekids.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.TimeWindow
import kotlinx.coroutines.launch

/** The icon each of the kid's shelves wears, everywhere it is named. */
internal fun shelfIcon(screen: Screen): ImageVector = when (screen) {
    Screen.Watchlist -> Icons.Filled.Favorite
    Screen.WatchLater -> YosemiteIcons.WatchLater
    Screen.Queue -> YosemiteIcons.UpNext
    Screen.Downloads -> YosemiteIcons.Download
    else -> YosemiteIcons.History
}

/** How many videos a row shows before "See all" opens the rest in place. */
private const val ROW_PREVIEW = 12

// ---------------------------------------------------------------------------
// Strings and numbers the page says, kept pure so they are unit-testable and
// so the two form factors cannot spell them differently.
// ---------------------------------------------------------------------------

/**
 * A minute-of-day the way the household reads its clocks — the same choice
 * `SessionGuard.timeOf` makes for the block messages, so a kid never sees
 * "20:00" in one place and "8:00pm" in the other.
 *
 * Lowercase am/pm because these sit in a mono micro-label at 11 sp, where
 * capitals shout. The design draws school as a bare "8:30–3:30"; the
 * meridiem is kept because half past three and half past eight in the morning
 * are exactly the confusion this row must not create for an early reader.
 */
internal fun clockLabel(minuteOfDay: Int, use24h: Boolean): String {
    val m = ((minuteOfDay % 1440) + 1440) % 1440
    val h = m / 60
    val mm = (m % 60).toString().padStart(2, '0')
    if (use24h) return "$h:$mm"
    val hour12 = when (h % 12) { 0 -> 12; else -> h % 12 }
    return "$hour12:$mm${if (h < 12) "am" else "pm"}"
}

/**
 * A window's stretch, as the pill says it.
 *
 * A window that crosses midnight names only its start: "Bedtime 8:00pm–7:00am"
 * is a sentence, and what a child needs off that pill is the moment the
 * television stops.
 */
internal fun windowRangeLabel(startMin: Int, endMin: Int, use24h: Boolean): String =
    if (endMin <= startMin) clockLabel(startMin, use24h)
    else "${clockLabel(startMin, use24h)}–${clockLabel(endMin, use24h)}"

/**
 * The card's own mono line: what the card is, and — when a budget exists —
 * how much of today has gone. Null budget means the parent set no daily
 * limit, and the card says nothing about minutes rather than inventing a
 * denominator.
 */
internal fun blockedWindowsLabel(watchedMin: Int, budgetMin: Int?): String =
    if (budgetMin == null) "VIDEOS ARE OFF"
    else "VIDEOS ARE OFF · $watchedMin OF $budgetMin MINUTES USED"

/** "You're offline. 3 downloads still play." — and the truth when there are none. */
internal fun offlineLine(downloads: Int): String = when (downloads) {
    0 -> "Nothing saved to watch without Wi-Fi."
    1 -> "1 download still plays."
    else -> "$downloads downloads still play."
}

/**
 * The You tab, one page.
 *
 * Top to bottom: the same chrome every tab wears, the parent's blocked
 * windows, the offline banner when there is no network, a strip of chips
 * naming each shelf, then every shelf as a row with its count and a "See all"
 * that unfolds it in place — so there is never a second screen to come back
 * from. One layout for both form factors: rows are the television's native
 * shape and read fine under a thumb.
 *
 * The handoff draws four rows; this has five. **Up next is ours**, it is a
 * shelf the kid fills themselves, and dropping it to match a picture would
 * take a feature away.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun YouScreen(
    state: UiState,
    profile: Profile?,
    isTv: Boolean,
    /** The chrome's live time-left, handed on unread — see `TimeLeft.kt`. */
    timeLeft: State<Long?>,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: ((VideoItem) -> Unit)?,
    /** The avatar in the header: the kid's corner (switch, look, settings). */
    onOpenHub: (() -> Unit)?,
    onOpenSearch: (() -> Unit)? = null,
    /** TV: the top menu chips, drawn in the header row. */
    topChips: (@Composable () -> Unit)? = null
) {
    var expanded by remember { mutableStateOf<Screen?>(null) }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val shelves = state.youShelves
    // Where each shelf's title sits in the grid, for the chip strip to scroll
    // to. Counted rather than guessed: the blocked-windows card and the
    // offline banner come and go, and a hard-coded offset silently jumped to
    // the wrong row the first time one of them appeared.
    val leading = 1 + // the header
        (if (state.timeWindows.isNotEmpty()) 1 else 0) +
        (if (state.offline) 1 else 0) +
        1 // the chip strip
    fun indexOf(screen: Screen): Int =
        leading + 3 * shelves.indexOfFirst { it.screen == screen }.coerceAtLeast(0)

    LazyVerticalGrid(
        columns = GridCells.Fixed(if (isTv) 4 else 2),
        state = grid,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "you-header", span = { GridItemSpan(maxLineSpan) }) {
            if (!isTv) {
                // The same bar as Home and Channels — this page is a tab, not a
                // destination, and a tab that redraws the furniture reads as a
                // different app.
                Column {
                    PhoneTopBar(
                        title = profile?.name ?: "You",
                        profile = profile,
                        onOpenHub = onOpenHub,
                        onOpenSearch = onOpenSearch,
                        timeLeft = timeLeft,
                        busy = state.refreshing || state.syncing
                    )
                    state.blockReason?.let {
                        Box(Modifier.padding(horizontal = 16.dp)) { BlockedBanner(it) }
                    }
                }
                return@item
            }
            Column(Modifier.padding(horizontal = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    // The kid's name is the page title, and the avatar lives in
                    // the top right exactly where it does on every other page.
                    // A big second copy of it here made this the one screen
                    // with a different header shape, and put the same face on
                    // screen three times counting the tab.
                    //
                    // The name and the pill share ONE weighted box, and the
                    // actions take what is left. Two weights in the same row —
                    // a `weight(1f, fill = false)` title and a `weight(1f)`
                    // spacer — split the free space between them, so a short
                    // name left the title's unused half sitting empty and
                    // parked the avatar in the middle of the screen.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            profile?.name ?: "You",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        TimeLeftPill(timeLeft, leadingGap = 10.dp)
                    }
                    HeaderActions(
                        profile = profile, onOpenHub = onOpenHub, onOpenSearch = onOpenSearch,
                        busy = state.refreshing || state.syncing
                    )
                    topChips?.invoke()
                }
                state.blockReason?.let { BlockedBanner(it) }
            }
        }
        if (state.timeWindows.isNotEmpty()) {
            item(key = "you-windows", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                    BlockedWindowsCard(
                        windows = state.timeWindows,
                        watchedMin = state.watchedTodayMin,
                        budgetMin = state.budgetTodayMin
                    )
                }
            }
        }
        if (state.offline) {
            item(key = "you-offline", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
                    OfflineBanner(state.downloaded.size)
                }
            }
        }
        item(key = "you-strip", span = { GridItemSpan(maxLineSpan) }) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).horizontalScroll(rememberScrollState())
            ) {
                shelves.forEach { shelf ->
                    YosemiteChip(
                        shelf.title,
                        selected = expanded == shelf.screen,
                        icon = shelfIcon(shelf.screen),
                        onClick = {
                            expanded = if (shelf.items.isNotEmpty()) shelf.screen else null
                            scope.launch { grid.animateScrollToItem(indexOf(shelf.screen)) }
                        }
                    )
                }
            }
        }
        shelves.forEach { shelf ->
            val open = expanded == shelf.screen && shelf.items.isNotEmpty()
            val downloads = shelf.screen == Screen.Downloads
            // History is the one shelf drawn back: it is a record, not an
            // invitation, and at full weight it competed with the two shelves
            // the kid built on purpose.
            //
            // Per card, and never on one already dimmed. A finished video
            // renders at 48% everywhere in the app, and almost everything in
            // History is finished — a flat 70% over the whole row multiplied
            // into 34%, which on the emulator was a card a child cannot read.
            // The two rules mean the same thing ("you are done with this"), so
            // the stronger one wins and the shelf rule applies to the rest.
            fun alphaFor(item: VideoItem): Float =
                if (shelf.screen == Screen.History && !item.isFinished()) 0.7f else 1f
            item(key = "you-divider-${shelf.title}", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(horizontal = 8.dp)) { ShelfRule() }
            }
            item(key = "you-title-${shelf.title}", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(horizontal = 8.dp)) {
                    ShelfHeader(
                        title = shelf.title,
                        count = shelf.items.size,
                        action = when {
                            shelf.items.isEmpty() -> null
                            open -> "Show less"
                            // "Manage", not "See all": with no network this row
                            // IS the library, so the link has to read as the way
                            // into it rather than as a peek at more of it.
                            downloads -> "Manage"
                            else -> "See all"
                        },
                        onAction = { expanded = if (open) null else shelf.screen }
                    )
                }
            }
            if (shelf.items.isEmpty()) {
                item(key = "you-empty-${shelf.title}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        when (shelf.screen) {
                            Screen.Watchlist -> "Nothing here yet. Hold a video and pick Add to Favorites."
                            Screen.WatchLater -> "Nothing saved for later. Hold a video and pick Add to Watch later."
                            Screen.Queue -> "Nothing lined up. Hold a video and pick Add to Up next."
                            else -> "Nothing watched yet. Whatever you watch shows up here."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            } else if (open) {
                // Unfolded: the whole shelf as cards, in place.
                items(shelf.items.size, key = { "you-card-${shelf.title}-${shelf.items[it].video.url}" }) { i ->
                    val item = shelf.items[i]
                    if (isTv || downloads) {
                        ShelfVideoTile(
                            item, state.channelAvatars[item.video.channelName], onPlay, onOpenMenu,
                            width = if (isTv) 236.dp else 200.dp,
                            metaOverride = if (downloads) downloadMeta(item, state) else null,
                            posterOverlay = if (downloads) downloadOverlay(item, state) else null,
                            modifier = Modifier.graphicsLayer { alpha = alphaFor(item) }
                        )
                    } else {
                        VideoCard(
                            item = item,
                            avatarUrl = state.channelAvatars[item.video.channelName],
                            onPlay = onPlay,
                            onOpenMenu = onOpenMenu,
                            modifier = Modifier.padding(horizontal = 4.dp)
                                .graphicsLayer { alpha = alphaFor(item) }
                        )
                    }
                }
            } else {
                item(key = "you-row-${shelf.title}", span = { GridItemSpan(maxLineSpan) }) {
                    CompositionLocalProvider(
                        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides TvRowPivot
                    ) {
                        val preview = shelf.items.take(ROW_PREVIEW)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
                        ) {
                            items(preview.size, key = { preview[it].video.url }) { i ->
                                val item = preview[i]
                                ShelfVideoTile(
                                    item,
                                    avatarUrl = state.channelAvatars[item.video.channelName],
                                    onPlay = onPlay,
                                    onOpenMenu = onOpenMenu,
                                    width = if (isTv) 236.dp else 200.dp,
                                    metaOverride = if (downloads) downloadMeta(item, state) else null,
                                    posterOverlay = if (downloads) downloadOverlay(item, state) else null,
                                    modifier = Modifier.graphicsLayer { alpha = alphaFor(item) }
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "you-request", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) { RequestTile() }
        }
    }
}

// ---------------------------------------------------------------------------
// The kid's own page furniture.
// ---------------------------------------------------------------------------

/**
 * The parent's blocked windows, named. Not new state: these are the very
 * [TimeWindow]s `SessionGuard` enforces, read out of the store the checks run
 * against, so this card cannot advertise a bedtime the television is not
 * keeping.
 *
 * A scrolling row rather than a wrapped list because there is no limit on how
 * many a parent may set, and a card that grows a line per window would push
 * the shelves off the first screen on the day someone adds homework time.
 */
@Composable
private fun BlockedWindowsCard(
    windows: List<TimeWindow>,
    watchedMin: Int,
    budgetMin: Int?,
    formFactor: FormFactor = LocalFormFactor.current
) {
    val use24h = android.text.format.DateFormat.is24HourFormat(
        androidx.compose.ui.platform.LocalContext.current
    )
    val pad = if (formFactor.isTv) tvUnits(18f) else 13.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (formFactor.isTv) tvUnits(20f) else 16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                1.dp, MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(if (formFactor.isTv) tvUnits(20f) else 16.dp)
            )
            .padding(vertical = pad)
    ) {
        Text(
            blockedWindowsLabel(watchedMin, budgetMin),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            style = monoLabelStyle(formFactor),
            modifier = Modifier.padding(horizontal = pad)
        )
        Spacer(Modifier.height(if (formFactor.isTv) tvUnits(14f) else 10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (formFactor.isTv) tvUnits(10f) else 8.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = pad)
        ) {
            windows.forEachIndexed { i, w ->
                TimeWindowPill(w, i, use24h, formFactor)
            }
        }
    }
}

/**
 * One window: a dot, its name, and when it runs.
 *
 * The dot's colour tells one pill from the next and means nothing else — it is
 * cycled by position, not derived from the label, because "Bedtime" is a word
 * this family chose and may be in any language. Which window is *blocking now*
 * is not this card's job: the banner above says that, in a sentence.
 */
@Composable
private fun TimeWindowPill(window: TimeWindow, index: Int, use24h: Boolean, formFactor: FormFactor) {
    val tokens = kidTokens
    val dot = when (index % 3) {
        0 -> tokens.timeWarning
        1 -> tokens.offline
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(if (formFactor.isTv) tvUnits(22f) else 17.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(
                horizontal = if (formFactor.isTv) tvUnits(16f) else 12.dp,
                vertical = if (formFactor.isTv) tvUnits(11f) else 9.dp
            )
    ) {
        Box(
            Modifier
                .size(if (formFactor.isTv) tvUnits(10f) else 8.dp)
                .background(dot, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            window.label.trim().ifEmpty { "Quiet time" },
            maxLines = 1,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = if (formFactor.isTv) tvTypeUnits(16f) else 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            windowRangeLabel(window.startMin, window.endMin, use24h),
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = monoLabelStyle(formFactor).copy(
                letterSpacing = androidx.compose.ui.unit.TextUnit(
                    0f, androidx.compose.ui.unit.TextUnitType.Em
                )
            )
        )
    }
}

/**
 * No network. Drawn in [KidTokens.offline] — the token that exists to mean
 * "this is here without a network" — over a wash of itself, which is the only
 * tint in the app that survives all three looks without a second table.
 */
@Composable
private fun OfflineBanner(downloads: Int, formFactor: FormFactor = LocalFormFactor.current) {
    val tokens = kidTokens
    val radius = if (formFactor.isTv) tvUnits(18f) else 14.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(tokens.offline.copy(alpha = 0.12f))
            .border(1.dp, tokens.offline, RoundedCornerShape(radius))
            .padding(
                horizontal = if (formFactor.isTv) tvUnits(18f) else 14.dp,
                vertical = if (formFactor.isTv) tvUnits(14f) else 12.dp
            )
    ) {
        Box(Modifier.size(if (formFactor.isTv) tvUnits(10f) else 8.dp).background(tokens.offline, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            "You're offline.",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            offlineLine(downloads),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * "Make a request", drawn and deliberately dead.
 *
 * The FLOW does not exist: a kid asking for a channel needs a LAN route, a
 * queue that survives both devices sleeping, and a notification on the
 * parent's phone — `docs/ROADMAP.md` §2E, "Kid → parent requests", sized
 * Large. Wiring a tap to nothing, or to a message claiming a grown-up will
 * see it, would be a lie told to a child.
 *
 * So the slot is drawn, unfocusable and untappable, saying what is actually
 * true today: the grown-up adds channels on their phone. When §2E lands, this
 * is where it hangs, and the layout does not move.
 */
@Composable
private fun RequestTile(formFactor: FormFactor = LocalFormFactor.current) {
    val tokens = kidTokens
    val radius = if (formFactor.isTv) tvUnits(22f) else 18.dp
    val stroke = MaterialTheme.colorScheme.outlineVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .drawBehind {
                // Dashed, because the tile is an invitation rather than an
                // object — and, today, because it is not yet a control.
                drawRoundRect(
                    color = stroke,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 5.dp.toPx()), 0f
                        )
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx())
                )
            }
            .padding(
                horizontal = if (formFactor.isTv) tvUnits(20f) else 14.dp,
                vertical = if (formFactor.isTv) tvUnits(16f) else 14.dp
            )
            .graphicsLayer { alpha = 0.55f }
    ) {
        Text(
            "+",
            color = tokens.action,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "Make a request",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                "Ask a grown-up — they add channels on their phone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Download cards. Same tile as every other shelf, wearing what a download has
// that a video does not: whether it is here yet, and how much of the device
// it took.
// ---------------------------------------------------------------------------

/** "SciShow Kids · 142 MB" once it has landed; the plain meta while it hasn't. */
private fun downloadMeta(item: VideoItem, state: UiState): String? {
    val size = state.downloadSizes[item.video.url] ?: return null
    val bytes = formatBytes(size)
    return if (bytes.isEmpty()) null else "${item.video.channelName} · $bytes"
}

/**
 * What goes over a download's poster: the green SAVED flag once it is on
 * disk, or the percentage and bar while it is still arriving.
 *
 * Returns null for a video that is neither — a sideloaded local file, which
 * is a download to the shelf and to nothing else.
 */
private fun downloadOverlay(item: VideoItem, state: UiState): (@Composable BoxScope.() -> Unit)? {
    val url = item.video.url
    val pending = url in state.downloadPending
    val saved = url in state.downloaded
    if (!pending && !saved) return null
    val fraction = state.downloadProgress?.takeIf { it.first == url }?.second
    return {
        if (pending) DownloadingOverlay(fraction)
        else SavedBadge()
    }
}

/** "↓ SAVED", in the watched/done green: this is here, with or without Wi-Fi. */
@Composable
private fun BoxScope.SavedBadge(formFactor: FormFactor = LocalFormFactor.current) {
    val tokens = kidTokens
    Text(
        "↓ SAVED",
        color = tokens.watched,
        maxLines = 1,
        style = monoLabelStyle(formFactor),
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(6.dp)
            .background(tokens.artworkScrim, RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

/**
 * Still coming: the poster under a scrim with the percentage in the middle of
 * it, a green bar along the bottom, and the word underneath. Null progress is
 * a request the parent has not approved or a queued file nothing has started
 * — the bar stays empty rather than guessing a number.
 */
@Composable
private fun BoxScope.DownloadingOverlay(
    fraction: Float?,
    formFactor: FormFactor = LocalFormFactor.current
) {
    val tokens = kidTokens
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.matchParentSize().background(tokens.artworkScrim)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                fraction?.let { "${(it * 100).toInt()}%" } ?: "…",
                color = tokens.onArtwork,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(4.dp))
            Text("DOWNLOADING", color = tokens.watched, maxLines = 1, style = monoLabelStyle(formFactor))
        }
    }
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(if (formFactor.isTv) tvUnits(8f) else 5.dp)
            .background(tokens.artworkScrim)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction?.coerceIn(0f, 1f) ?: 0f)
                .fillMaxHeight()
                .background(tokens.watched)
        )
    }
}
