package io.pickwick.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.pickwick.app.data.*

/**
 * TV D-pad scrolling: the default bring-into-view spec scrolls only when the
 * focused item reaches the container's edge, so the grid sits still and then
 * lurches a whole row at once. This pivot spec instead keeps the focused item
 * anchored ~30% from the leading edge and animates every step — steady, smooth
 * motion (the same approach as Compose's TV libraries).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal class TvPivotBringIntoView(
    /** Where the focused item's leading edge settles, as a fraction of the container. */
    private val pivot: Float
) : androidx.compose.foundation.gestures.BringIntoViewSpec {

    // Spring, not tween: holding the D-pad interrupts the animation on every
    // step, and a tween restarts from zero velocity each time (a pulsing,
    // stop-start feel). A no-bounce spring carries its velocity into the new
    // target, so held-key scrolling glides at a steady rate — the Netflix feel.
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        )

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val target = pivot * containerSize
        val leadingEdge = if (size <= containerSize && containerSize - target < size) {
            containerSize - size // near the end: don't overscroll past the content
        } else target
        return offset - leadingEdge
    }
}

/** Vertical browsing: the focused row settles a quarter down the screen. */
private val TvColumnPivot = TvPivotBringIntoView(0.25f)

/**
 * Inside a row: the focused tile stays pinned left-of-center. Not tighter than
 * this: with the pivot hugging the left edge there are no composed tiles to the
 * left of focus, so a held LEFT press must compose each tile inside the ~50ms
 * key-repeat window — which the TV can't do, making left-hold stutter while
 * right-hold (90% of the row pre-composed) glides. 25% keeps a runway on both sides.
 */
internal val TvRowPivot = TvPivotBringIntoView(0.25f)

/**
 * Something the kid can act on when a load fails: a face, a plain line, a
 * button. The raw message is still there for the parent, small, because
 * "tell a parent" only helps if the parent then has something to read.
 */
@Composable
private fun FriendlyError(detail: String?, onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(24.dp)
    ) {
        Text(
            "😕",
            fontSize = androidx.compose.ui.unit.TextUnit(64f, androidx.compose.ui.unit.TextUnitType.Sp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Hmm, something didn't work.",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Is the Wi-Fi on? Try again, or tell a parent.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.focusRequester(focus).tvFocusHighlight().height(52.dp)
        ) { Text("🔄  Try again", style = MaterialTheme.typography.titleMedium) }
        if (!detail.isNullOrBlank()) {
            Spacer(Modifier.height(24.dp))
            Text(
                // The build is part of the message: "which version is this
                // TV on?" is the first question when a photo of this arrives.
                "For the grown-ups: $detail · Pickwick " +
                    "${io.pickwick.app.BuildConfig.VERSION_NAME} (${io.pickwick.app.BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 3
            )
        }
    }
}

/** The phone's bottom tabs — YouTube's mental model, four thumb-sized targets. */
private enum class Tab(val label: String, val emoji: String) {
    Home("Home", "🏠"), Channels("Channels", "📺"), Favorites("Favorites", "❤️"), Search("Search", "🔍")
}

private fun tabFor(screen: Screen): Tab? = when (screen) {
    Screen.Home -> Tab.Home
    Screen.Channels, Screen.Surprise, is Screen.ChannelVideos, is Screen.WatchedVideos -> Tab.Channels
    Screen.Watchlist, Screen.WatchLater, Screen.Queue, Screen.Downloads, Screen.History -> Tab.Favorites
    Screen.Search, is Screen.SearchResults -> Tab.Search
}

/** Screens that are a tab's own root: no back arrow, the tab is the way around. */
private fun isTabRoot(screen: Screen): Boolean =
    screen == Screen.Home || screen == Screen.Channels ||
        screen == Screen.Watchlist || screen == Screen.Search

/** The TV's top menu on every page below home: Home, Favorites, History, as focusable chips. */
@Composable
private fun TvTopChips(screen: Screen, vm: MainViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = false, onClick = vm::goHome,
            label = { Text("🏠 Home") }, modifier = Modifier.tvFocusHighlight()
        )
        FilterChip(
            selected = screen == Screen.Watchlist, onClick = vm::openWatchlist,
            label = { Text("❤️ Favorites") }, modifier = Modifier.tvFocusHighlight()
        )
        FilterChip(
            selected = screen == Screen.History, onClick = vm::openHistory,
            label = { Text("🕘 History") }, modifier = Modifier.tvFocusHighlight()
        )
    }
    Spacer(Modifier.width(8.dp))
}

/**
 * The saved shelves, as chips above the Favorites tab: one place for
 * everything the kid put aside, instead of four tiles hunting for space.
 */
@Composable
private fun ShelfChips(s: UiState, vm: MainViewModel) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        FilterChip(
            selected = s.screen == Screen.Watchlist, onClick = vm::openWatchlist,
            label = { Text("❤️ Favorites") }
        )
        FilterChip(
            selected = s.screen == Screen.WatchLater, onClick = vm::openWatchLater,
            label = { Text("🕒 Watch later") }
        )
        FilterChip(
            selected = s.screen == Screen.Queue, onClick = vm::openQueue,
            label = { Text("📚 Up next") }
        )
        FilterChip(
            selected = s.screen == Screen.History, onClick = vm::openHistory,
            label = { Text("🕘 History") }
        )
        if (s.downloaded.isNotEmpty()) FilterChip(
            selected = s.screen == Screen.Downloads, onClick = vm::openDownloads,
            label = { Text("⬇️ Downloads") }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickwickScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
    /** Who this home screen belongs to; null before profiles exist. */
    activeProfile: io.pickwick.app.data.Profile? = null,
    /** Non-null on shared devices with 2+ kids: header avatar re-opens the picker. */
    onSwitchProfile: (() -> Unit)? = null,
    /** Starts the Up next queue from this position in the visible lineup. */
    onPlayQueue: (Int) -> Unit = {},
    onPlay: (VideoItem) -> Unit
) {
    val state by vm.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val isTv = remember {
        (context.getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager)
            .currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }
    val phone = !isTv

    // The Watched shelf is the app's only second level: back there means back
    // to the channel it belongs to, not all the way out to home.
    BackHandler(enabled = state.screen != Screen.Home) {
        vm.goBack()
    }

    // Coming back from the player: re-read progress so bars/filters update.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    vm.refreshProgress()
                    // Fresh progress after watching — share it with paired devices.
                    vm.syncWatchState()
                    // The player's pre-play deep check may have blocked the very
                    // video the kid just pressed — it must not still be on the
                    // shelf they land back on.
                    vm.reapplyScreening()
                    // The kid tapped the channel avatar in the player.
                    PlayerRequests.openChannel?.let { name ->
                        PlayerRequests.openChannel = null
                        android.util.Log.i("Pickwick", "player asked for channel \"$name\"")
                        vm.openChannelByName(name)
                    }
                }
                // App returned to foreground: pick up any whitelist edits.
                Lifecycle.Event.ON_START -> vm.refreshIfIdle()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The feed's hold menu lives here: the feed cards are not inside a
    // VideoGrid, so they borrow the same menu with the same actions.
    var feedMenuFor by remember { mutableStateOf<VideoItem?>(null) }
    feedMenuFor?.let { item ->
        VideoActionMenu(
            item = item,
            watchlisted = state.watchlisted, onToggleWatchlist = vm::toggleWatchlist,
            watchLater = state.watchLater, onToggleWatchLater = vm::toggleWatchLater,
            queued = state.queued, onToggleQueue = vm::toggleQueue,
            onToggleWatched = vm::toggleWatched,
            downloadPending = state.downloadPending, downloaded = state.downloaded,
            onToggleDownload = if (isTv) null else vm::toggleDownload,
            castTargets = if (isTv) emptyList() else vm.castTargets(), onCast = vm::castTo,
            onDismiss = { feedMenuFor = null }
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
        CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides
                if (isTv) TvColumnPivot
                else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current,
            // No touch on TV → the overscroll stretch shader is pure render cost
            // at every list edge. Off.
            androidx.compose.foundation.LocalOverscrollConfiguration provides
                if (isTv) null
                else androidx.compose.foundation.LocalOverscrollConfiguration.current
        ) {
        Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
        // Screens fade and lift in rather than cutting. Keyed on the screen
        // alone, so progress/badge updates within a screen never animate; the
        // leaving screen keeps the state it was showing (`s`), so it doesn't
        // flash the arriving screen's empty grid while it fades out.
        AnimatedContent(
            targetState = state,
            contentKey = { it.screen },
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 24 })
                    .togetherWith(fadeOut(tween(140)))
            },
            label = "screen",
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = if (phone) 8.dp else 16.dp)
        ) { s ->
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                s.loading && phone && s.screen != Screen.Home -> {
                    // Skeleton grid: the shape of what's coming.
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(170.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(8.dp),
                        userScrollEnabled = false
                    ) { items(6) { SkeletonCard() } }
                }
                s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                s.error != null -> FriendlyError(s.error, onRetry = vm::retryCurrent)
                s.screen is Screen.Home -> {
                    // TV gets the ten-foot layout: horizontal rows under a pinned
                    // focus, like every streaming app's browse screen. No touch, so
                    // no pull-to-refresh either (auto + poll refresh cover it).
                    if (isTv) {
                        TvHomeRows(
                            channels = s.channels,
                            newBadges = s.newBadges,
                            keepWatching = s.keepWatching,
                            feed = s.feed,
                            recentHistory = s.recentHistory,
                            channelAvatars = s.channelAvatars,
                            onPlay = onPlay,
                            onOpenMenu = { feedMenuFor = it },
                            onDismissKeepWatching = vm::dismissKeepWatching,
                            onOpen = vm::openChannel,
                            onOpenHistory = vm::openHistory,
                            onSurprise = vm::surpriseMe,
                            onOpenWatchlist = vm::openWatchlist,
                            hasWatchLater = s.watchLater.isNotEmpty(),
                            onOpenWatchLater = vm::openWatchLater,
                            hasQueue = s.queued.isNotEmpty(),
                            onOpenQueue = vm::openQueue,
                            onOpenSettings = onOpenSettings,
                            activeProfile = activeProfile,
                            onSwitchProfile = onSwitchProfile,
                            onSearch = vm::search,
                            remainingMs = s.remainingMs,
                            blockReason = s.blockReason,
                            allHeld = s.allHeld
                        )
                    } else {
                        PullToRefreshBox(
                            isRefreshing = s.refreshing,
                            onRefresh = { vm.refresh(userInitiated = true) },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            PhoneHome(
                                state = s,
                                onPlay = onPlay,
                                onDismissKeepWatching = vm::dismissKeepWatching,
                                onOpen = vm::openChannel,
                                onSurprise = vm::surpriseMe,
                                onShowAllChannels = vm::openChannels,
                                onOpenMenu = { feedMenuFor = it },
                                onOpenChannelByName = vm::openChannelByName,
                                onOpenSettings = onOpenSettings,
                                activeProfile = activeProfile,
                                onSwitchProfile = onSwitchProfile,
                                onSearch = vm::search
                            )
                        }
                    }
                }
                s.screen is Screen.Channels -> Column(Modifier.fillMaxSize()) {
                    Text(
                        "📺 Channels",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        ),
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                    ChannelsScreen(
                        state = s,
                        onOpen = vm::openChannel,
                        onSurprise = vm::surpriseMe,
                        onOpenQueue = vm::openQueue,
                        onOpenWatchLater = vm::openWatchLater,
                        onOpenDownloads = vm::openDownloads
                    )
                }
                s.screen is Screen.Search -> Column(Modifier.fillMaxSize()) {
                    Text(
                        "🔍 Search",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        ),
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                    SearchField(
                        onSearch = vm::search, voice = phone,
                        onVoiceUnavailable = { vm.showNoticeExternal("Voice search isn't on this device yet") }
                    )
                    Text(
                        "Only your channels — nothing else can turn up.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    if (s.recentSearches.isNotEmpty()) {
                        SectionRow("Recent", action = "Clear", onAction = vm::clearRecentSearches)
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.recentSearches.forEach { q ->
                                AssistChip(
                                    onClick = { vm.search(q) },
                                    label = { Text(q, style = MaterialTheme.typography.titleSmall) },
                                    modifier = Modifier.height(44.dp)
                                )
                            }
                        }
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    val title = when (val sc = s.screen) {
                        is Screen.ChannelVideos -> sc.source.name
                        is Screen.WatchedVideos -> "🕘 History · ${sc.source.name}"
                        is Screen.History -> "🕘 History"
                        is Screen.Surprise -> "🎲 Surprise!"
                        is Screen.Watchlist -> "❤️ Favorites"
                        is Screen.WatchLater -> "🕒 Watch later"
                        is Screen.Downloads -> "⬇️ Downloads"
                        is Screen.Queue -> "📚 Up next"
                        is Screen.SearchResults -> if (phone) "🔍 Search" else "🔍 “${sc.query}”"
                        else -> ""
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        // A visible way back on touch devices: the system
                        // back gesture is not a thing a five-year-old knows.
                        // TV keeps the remote's Back key and no extra focusable.
                        if (!isTv && !isTabRoot(s.screen)) {
                            IconButton(
                                onClick = {
                                    if (s.screen is Screen.SearchResults) vm.openSearch()
                                    else vm.goBack()
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        // The channel's own avatar beside its name, so the page
                        // reads as the channel and not just a list.
                        if (s.screen is Screen.ChannelVideos) {
                            val src = (s.screen as Screen.ChannelVideos).source
                            Box(
                                Modifier.size(if (isTv) 48.dp else 36.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) { PosterImage(src.avatarUrl, src.name, Modifier.fillMaxSize()) }
                            Spacer(Modifier.width(if (isTv) 14.dp else 10.dp))
                        }
                        Text(
                            title,
                            style = (if (isTv) MaterialTheme.typography.headlineSmall
                            else MaterialTheme.typography.titleLarge).copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            ),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        // TV has no tabs and no back arrow, so a page below home
                        // used to be a title and a grid with nothing above it.
                        // These chips are the ten-foot menu: where you are, and
                        // the three places a kid goes from anywhere. Up from the
                        // grid's first row lands on them.
                        if (isTv) TvTopChips(s.screen, vm)
                        val screen = s.screen
                        if (screen is Screen.ChannelVideos &&
                            screen.source.kind == SourceKind.PLAYLIST &&
                            s.videos.isNotEmpty()
                        ) {
                            FilterChip(
                                selected = false,
                                onClick = {
                                    // Resume mid-video if one is in progress, else the next
                                    // unwatched — never a finished one (they're visible now).
                                    val next = s.videos.firstOrNull {
                                        it.progress != null && it.progress < 0.98f
                                    }
                                        ?: s.videos.firstOrNull { it.progress == null }
                                        ?: s.videos.first()
                                    onPlay(next)
                                },
                                label = { Text("▶ Continue") },
                                modifier = Modifier.tvFocusHighlight()
                            )
                        }
                        if (screen is Screen.Queue && s.videos.isNotEmpty()) {
                            FilterChip(
                                selected = false,
                                onClick = { onPlayQueue(0) },
                                label = { Text("▶ Play") },
                                modifier = Modifier.tvFocusHighlight()
                            )
                        }
                    }
                    if (phone && s.screen is Screen.SearchResults) {
                        SearchField(
                            onSearch = vm::search, initial = (s.screen as Screen.SearchResults).query,
                            voice = true,
                            onVoiceUnavailable = { vm.showNoticeExternal("Voice search isn't on this device yet") }
                        )
                    }
                    if (phone && tabFor(s.screen) == Tab.Favorites) ShelfChips(s, vm)
                    // Search hits from the crawled index are screened live, in
                    // windows — an honest bar (we know the window's size), with
                    // results appended below as verdicts land.
                    val sp = s.searchScreening
                    if (s.screen is Screen.SearchResults && sp != null) {
                        LinearProgressIndicator(
                            progress = {
                                if (sp.total == 0) 0f else sp.done.toFloat() / sp.total
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                        )
                        Text(
                            "Showing ${s.videos.size} · " +
                                "${sp.total - sp.done} awaiting screening…" +
                                if (sp.beyondWindow > 0) " (${sp.beyondWindow} more matches)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    if (s.screen is Screen.Queue) {
                        QueueList(
                            s.videos,
                            onPlayFrom = onPlayQueue,
                            onMove = vm::moveQueue,
                            onRemove = vm::removeFromQueue,
                            grabFocus = isTv
                        )
                    } else {
                    VideoGrid(
                        s.videos,
                        onPlay = onPlay,
                        // An all-held source must say so — a silently empty grid
                        // reads as broken to the kid and the parent alike.
                        emptyText = when {
                            // Mid-screening emptiness isn't "held" — results are coming.
                            sp != null -> "Checking these videos for you…"
                            // Kid's line first; the parent's pointer is the
                            // second sentence, not the whole message.
                            s.held > 0 ->
                                "A grown-up is checking these ${s.held} video(s).\n" +
                                    "They'll appear once a parent says OK on their phone."
                            s.screen is Screen.History ->
                                "Nothing watched yet.\nWhatever you watch shows up here."
                            s.screen is Screen.WatchedVideos ->
                                "Nothing watched here yet."
                            s.screen is Screen.Watchlist ->
                                "No favorites yet.\nHold any video and tap ❤️ Add to Favorites."
                            s.screen is Screen.SearchResults ->
                                "Nothing with that name in your channels.\nTry another word!"
                            else -> "Nothing here yet."
                        },
                        loadingMore = s.loadingMore,
                        watchlisted = s.watchlisted,
                        onToggleWatchlist = vm::toggleWatchlist,
                        watchLater = s.watchLater,
                        onToggleWatchLater = vm::toggleWatchLater,
                        downloadPending = s.downloadPending,
                        downloaded = s.downloaded,
                        // Downloads are phone-only: the TV stays on home Wi-Fi.
                        onToggleDownload = if (isTv) null else vm::toggleDownload,
                        queued = s.queued,
                        onToggleQueue = vm::toggleQueue,
                        onToggleWatched = vm::toggleWatched,
                        castTargets = if (isTv) emptyList() else vm.castTargets(),
                        onCast = vm::castTo,
                        cards = phone,
                        avatarFor = { s.channelAvatars[it] },
                        onOpenChannel = if (phone) vm::openChannelByName else null,
                        grabFocus = isTv,
                        onNearEnd = when (s.screen) {
                            is Screen.ChannelVideos -> vm::loadMoreUploads
                            // Scrolling deep extends the screening window — the
                            // AI bill follows what's actually being looked at.
                            is Screen.SearchResults -> vm::screenMoreSearch
                            else -> null
                        },
                        // Only on the channel itself, and only once there is
                        // something behind it — an empty shelf is a dead end.
                        extraTileAt = s.watchedTileAt
                            ?.takeIf {
                                s.screen is Screen.ChannelVideos &&
                                    s.channelWatched.isNotEmpty()
                            },
                        extraTile = if (s.screen is Screen.ChannelVideos &&
                            s.channelWatched.isNotEmpty() && s.watchedTileAt != null
                        ) {
                            { focus ->
                                WatchedShelfTile(
                                    count = s.channelWatched.size,
                                    focusRequester = focus,
                                    rounded = phone,
                                    onOpen = vm::openChannelWatched
                                )
                            }
                        } else null,
                        scrollTo = s.scrollTo,
                        onScrolled = vm::scrollHandled,
                        // The "By playlist" layout: a playlists row above the
                        // grid, only once the channel's listing has produced any.
                        header = if (s.screen is Screen.ChannelVideos && s.channelPlaylists.isNotEmpty()) {
                            { playlistRow(s.channelPlaylists, isTv, vm::openPlaylist) }
                        } else null
                    )
                    }
                }
            }
        }
        }
        // Transient top-center pill, same look as the player's notices — today
        // it says a save-offline request was refused by the check. Slides in
        // and out like the player's, and remembers its last text for the exit.
        var shownNotice by remember { mutableStateOf<String?>(null) }
        if (state.notice != null) shownNotice = state.notice
        androidx.compose.animation.AnimatedVisibility(
            visible = state.notice != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
        ) {
            Text(
                shownNotice.orEmpty(),
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .background(
                        androidx.compose.ui.graphics.Color(0xCC000000),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
        }
        // Phone: the bottom tabs. Hidden while the whitelist is empty — the
        // first-run screen is the whole story then.
        if (phone && state.channels.isNotEmpty()) {
            val current = tabFor(state.screen)
            NavigationBar(tonalElevation = 0.dp) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab,
                        onClick = {
                            when (tab) {
                                Tab.Home -> vm.goHome()
                                Tab.Channels -> vm.openChannels()
                                Tab.Favorites -> vm.openWatchlist()
                                Tab.Search -> vm.openSearch()
                            }
                        },
                        icon = {
                            Text(
                                tab.emoji,
                                fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp)
                            )
                        },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
        }
        }
        }
    }
}
