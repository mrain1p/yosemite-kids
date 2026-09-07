package io.yosemitekids.app.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.yosemitekids.app.data.PlaylistRef

/**
 * The "By playlist" channel layout: the channel's playlists as a row of
 * chips at the top of its page — the same idea as the channel bar on the
 * home screen, one level down — then "All videos" and the grid. A chip opens
 * the playlist as its own page; Back returns to the channel. The row goes
 * into the channel grid as full-span items so the page scrolls as one.
 */
internal fun LazyGridScope.playlistRow(
    playlists: List<PlaylistRef>,
    isTv: Boolean,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    channelName: String = "",
    /** "See all" → every playlist with its video count. */
    onSeeAll: (() -> Unit)? = null
) {
    if (playlists.isEmpty()) return
    item(key = "pl:title", span = { GridItemSpan(maxLineSpan) }) {
        SectionRow(
            "Playlists",
            action = if (onSeeAll != null) "See all (${playlists.size})" else null,
            onAction = onSeeAll
        )
    }
    item(key = "pl:row", span = { GridItemSpan(maxLineSpan) }) {
        PlaylistsRow(playlists.map { it.copy(name = cleanPlaylistName(it.name, channelName)) }, isTv, onOpenPlaylist)
    }
}

/**
 * Every playlist a channel has, one row each with its cover and how many
 * videos are in it — the strip's "See all". A row opens the playlist.
 */
@Composable
internal fun PlaylistsPage(
    playlists: List<PlaylistRef>,
    channelName: String,
    isTv: Boolean,
    onOpenPlaylist: (PlaylistRef) -> Unit
) {
    if (playlists.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No playlists on this channel.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(playlists.size, key = { playlists[it].id }) { i ->
            val p = playlists[i]
            val interaction = remember { MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(interaction)
                    .tvFocusHighlight()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                        onOpenPlaylist(p)
                    }
                    .padding(6.dp)
            ) {
                Box(
                    Modifier
                        .width(if (isTv) 160.dp else 128.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    PosterImage(p.thumbnailUrl, p.name, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        cleanPlaylistName(p.name, channelName),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        if (p.videoCount > 0) "${p.videoCount} videos" else "playlist",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    YosemiteIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * "The World of Insects | SciShow Kids" → "The World of Insects": channels
 * stamp their own name on every playlist title, and on the channel's own
 * page that stamp is just noise. Separators seen in the wild: | · - – — •
 */
internal fun cleanPlaylistName(name: String, channelName: String): String {
    if (channelName.isBlank()) return name.trim()
    val sep = "[|·•\\-–—:]"
    val tail = Regex("\\s*$sep\\s*${Regex.escape(channelName)}\\s*$", RegexOption.IGNORE_CASE)
    val head = Regex("^\\s*${Regex.escape(channelName)}\\s*$sep\\s*", RegexOption.IGNORE_CASE)
    val cleaned = name.replace(tail, "").replace(head, "").trim()
    return cleaned.ifBlank { name.trim() }
}

/**
 * The parent-picked playlists of a channel, one row each: the playlist's
 * name with "See all" (the playlist as its own page), then its first videos
 * as shelf tiles. Above the grid, before the "By playlist" chip row if the
 * parent chose that layout too. Rows the channel has picked but whose
 * videos haven't loaded yet are simply not there — never an empty row.
 */
internal fun LazyGridScope.playlistShelves(
    shelves: List<PlaylistShelf>,
    isTv: Boolean,
    avatarFor: (String) -> String?,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: ((VideoItem) -> Unit)?,
    onOpenPlaylist: (PlaylistRef) -> Unit,
    channelName: String = ""
) {
    shelves.forEach { shelf ->
        if (shelf.items.isEmpty()) return@forEach
        item(key = "pls:title:${shelf.playlist.id}", span = { GridItemSpan(maxLineSpan) }) {
            SectionRow(
                cleanPlaylistName(shelf.playlist.name, channelName),
                action = "See all", onAction = { onOpenPlaylist(shelf.playlist) }
            )
        }
        item(key = "pls:row:${shelf.playlist.id}", span = { GridItemSpan(maxLineSpan) }) {
            VideoShelfRow(shelf.items, isTv, avatarFor, onPlay, onOpenMenu)
        }
    }
}

/** "New for you" on a channel page: the newest videos the kid hasn't started, as a row above the grid. */
internal fun LazyGridScope.newForYouRow(
    items: List<VideoItem>,
    isTv: Boolean,
    avatarFor: (String) -> String?,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: ((VideoItem) -> Unit)?
) {
    if (items.isEmpty()) return
    item(key = "nfy:title", span = { GridItemSpan(maxLineSpan) }) { SectionRow("New for you") }
    item(key = "nfy:row", span = { GridItemSpan(maxLineSpan) }) {
        VideoShelfRow(items, isTv, avatarFor, onPlay, onOpenMenu)
    }
}

/**
 * The rule, the "All videos" title and the kid's sort chips under it — the
 * grid's own header, so the sort sits with what it sorts rather than at
 * the top of the page over rows it doesn't touch.
 */
internal fun LazyGridScope.allVideosHeader(
    filter: String,
    onFilter: ((String) -> Unit)?,
    title: String = "All videos"
) {
    item(key = "all:divider", span = { GridItemSpan(maxLineSpan) }) { SectionDivider() }
    item(key = "all:title", span = { GridItemSpan(maxLineSpan) }) {
        SectionRow(title, trailing = onFilter?.let { set -> { VideoFilterChips(filter, set) } })
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VideoShelfRow(
    items: List<VideoItem>,
    isTv: Boolean,
    avatarFor: (String) -> String?,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: ((VideoItem) -> Unit)?
) {
    CompositionLocalProvider(
        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides TvRowPivot
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 10.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
        ) {
            items(items.size, key = { items[it].video.url }) { i ->
                val item = items[i]
                ShelfVideoTile(
                    item,
                    avatarUrl = avatarFor(item.video.channelName),
                    onPlay = onPlay,
                    onOpenMenu = onOpenMenu,
                    width = if (isTv) 236.dp else 200.dp
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistsRow(
    playlists: List<PlaylistRef>,
    isTv: Boolean,
    onOpenPlaylist: (PlaylistRef) -> Unit
) {
    // No heading here: playlistRow draws it, with the "See all" count. This
    // row used to carry its own and the page showed "Playlists" twice.
    Column {
        CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides TvRowPivot
        ) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 10.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
            ) {
                items(playlists.size, key = { playlists[it].id }) { i ->
                    PlaylistChip(playlists[i], width = if (isTv) 200.dp else 132.dp) {
                        onOpenPlaylist(playlists[i])
                    }
                }
            }
        }
    }
}

/**
 * One playlist in the row: its cover, rounded, with the video count in the
 * corner and the name underneath — a channel chip's shape, but 16:9 because
 * a playlist's cover is a video frame, not a face.
 */
@Composable
private fun PlaylistChip(playlist: PlaylistRef, width: Dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(width)
            .pressScale(interaction)
            .tvFocusHighlight()
            .clip(RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onClick() }
            .padding(4.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            PosterImage(playlist.thumbnailUrl, playlist.name, Modifier.fillMaxSize())
            if (playlist.videoCount > 0) {
                Text(
                    "${playlist.videoCount}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            playlist.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.height(34.dp)
        )
    }
}

/**
 * One video in a horizontal shelf — the TV home rows. Rounded 16:9 poster
 * with the duration and the red watched bar, then the channel's face beside
 * the title and channel name. Finished videos dim the poster, as in the
 * grids. OK plays; a held OK (or a touch hold) opens the same menu the
 * grid's tiles do.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ShelfVideoTile(
    item: VideoItem,
    avatarUrl: String?,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: ((VideoItem) -> Unit)?,
    modifier: Modifier = Modifier,
    width: Dp = 236.dp,
    formFactor: FormFactor = LocalFormFactor.current
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val finished = item.isFinished()
    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .width(width)
            // The whole card recedes, not just its poster: a finished video
            // with a full-brightness title read as the loudest thing in the
            // rail. Focus brings it back, because kids rewatch.
            .graphicsLayer { alpha = if (finished && !focused) 0.48f else 1f }
            .pressScale(interaction)
            .tvFocusHighlight { focused = it }
            .then(if (onOpenMenu != null) Modifier.dpadLongPress { onOpenMenu(item) } else Modifier)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = { onPlay(item) },
                onLongClick = { onOpenMenu?.invoke(item) }
            )
    ) {
        Column {
            Box {
                PosterImage(
                    url = item.video.thumbnailUrl,
                    contentDescription = item.video.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
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
                // Part-watched only: a finished video says so beside its meta
                // line instead, and a card is never both. See [CardMetaRow].
                item.progress?.takeIf { !finished }?.let { fraction -> WatchedProgressBar(fraction) }
            }
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                ChannelArt(avatarUrl, item.video.channelName, size = 28.dp)
                Spacer(Modifier.width(9.dp))
                Column {
                    CardTitle(item.video.title, focused, railCardTitleStyle(formFactor))
                    CardMetaRow(
                        meta = videoMeta(item.video.channelName, item.video.publishedAt),
                        watched = finished,
                        style = cardMetaStyle(formFactor)
                    )
                }
            }
        }
    }
}
