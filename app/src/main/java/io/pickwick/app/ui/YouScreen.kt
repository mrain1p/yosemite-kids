package io.pickwick.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.Profile

/**
 * The You tab: the kid's own page. Their avatar and name on top with the
 * time chip and the two things that are theirs to change (who's watching,
 * their look), then every shelf they've built — Favorites, Watch later, Up
 * next, History, Downloads — as rows, each with "See all" into the full
 * grid. One layout for both form factors: rows are the TV's native shape
 * and read fine under a thumb; the tiles are the shared [ShelfVideoTile].
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun YouScreen(
    state: UiState,
    profile: Profile?,
    isTv: Boolean,
    onPlay: (VideoItem) -> Unit,
    onOpenMenu: ((VideoItem) -> Unit)?,
    onSeeAll: (Screen) -> Unit,
    onChangeLook: (() -> Unit)?,
    onSwitchProfile: (() -> Unit)?
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "you-header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                if (profile != null) {
                    ProfileAvatar(profile, size = if (isTv) 96 else 72)
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        profile?.name ?: "You",
                        style = (if (isTv) MaterialTheme.typography.headlineMedium
                        else MaterialTheme.typography.headlineSmall).copy(fontWeight = FontWeight.Bold)
                    )
                    val left = state.remainingMs
                    if (left != null && state.blockReason == null) {
                        Spacer(Modifier.height(6.dp))
                        TimeChip(left)
                    }
                }
            }
        }
        item(key = "you-actions") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (onChangeLook != null) AssistChip(
                    onClick = onChangeLook,
                    label = { Text("🎨 Change my look", style = MaterialTheme.typography.titleSmall) },
                    modifier = Modifier.tvFocusHighlight().height(44.dp)
                )
                if (onSwitchProfile != null) AssistChip(
                    onClick = onSwitchProfile,
                    label = { Text("👋 Switch", style = MaterialTheme.typography.titleSmall) },
                    modifier = Modifier.tvFocusHighlight().height(44.dp)
                )
            }
        }
        state.blockReason?.let { reason ->
            item(key = "you-blocked") { BlockedBanner(reason) }
        }
        if (state.youShelves.isEmpty()) {
            item(key = "you-empty") {
                Text(
                    "Nothing saved yet.\nHold any video to add it to Favorites, Watch later or Up next.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        state.youShelves.forEach { shelf ->
            item(key = "you-divider-${shelf.title}") { SectionDivider() }
            item(key = "you-title-${shelf.title}") {
                SectionRow("${shelf.emoji} ${shelf.title}", action = "See all", onAction = { onSeeAll(shelf.screen) })
            }
            item(key = "you-row-${shelf.title}") {
                CompositionLocalProvider(
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides TvRowPivot
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(if (isTv) 14.dp else 10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
                    ) {
                        items(shelf.items.size, key = { shelf.items[it].video.url }) { i ->
                            val item = shelf.items[i]
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
