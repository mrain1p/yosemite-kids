package io.yosemitekids.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.yosemitekids.app.data.Profile
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

/**
 * The You tab, one page: the kid's avatar and name with the time chip and
 * their two own actions, a strip of chips naming each shelf, then every
 * shelf as a row. "See all" on a row unfolds it into a grid right there
 * (and a chip jumps to its row), so there is never a second screen to come
 * back from. One layout for both form factors — rows are the TV's native
 * shape and read fine under a thumb.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun YouScreen(
    state: UiState,
    profile: Profile?,
    isTv: Boolean,
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
    // Each shelf's index in the grid, for the chip strip to scroll to: the
    // header and strip are two items, then a divider + title + body per shelf.
    fun indexOf(screen: Screen): Int = 2 + 3 * shelves.indexOfFirst { it.screen == screen }.coerceAtLeast(0)

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
                        remainingMs = state.remainingMs?.takeIf { state.blockReason == null },
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
                    Text(
                        profile?.name ?: "You",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    val left = state.remainingMs
                    if (left != null && state.blockReason == null) {
                        Spacer(Modifier.width(10.dp))
                        TimeChip(left)
                    }
                    Spacer(Modifier.weight(1f))
                    HeaderActions(
                        profile = profile, onOpenHub = onOpenHub, onOpenSearch = onOpenSearch,
                        busy = state.refreshing || state.syncing
                    )
                    topChips?.invoke()
                }
                state.blockReason?.let { BlockedBanner(it) }
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
            item(key = "you-divider-${shelf.title}", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(horizontal = 8.dp)) { SectionDivider() }
            }
            item(key = "you-title-${shelf.title}", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(horizontal = 8.dp)) {
                    SectionRow(
                        shelf.title,
                        action = when {
                            shelf.items.isEmpty() -> null
                            open -> "Show less"
                            shelf.items.size > ROW_PREVIEW -> "See all (${shelf.items.size})"
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
                    if (isTv) {
                        ShelfVideoTile(item, state.channelAvatars[item.video.channelName], onPlay, onOpenMenu, width = 236.dp)
                    } else {
                        VideoCard(
                            item = item,
                            avatarUrl = state.channelAvatars[item.video.channelName],
                            onPlay = onPlay,
                            onOpenMenu = onOpenMenu,
                            modifier = Modifier.padding(horizontal = 4.dp)
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
                                    width = if (isTv) 236.dp else 200.dp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
