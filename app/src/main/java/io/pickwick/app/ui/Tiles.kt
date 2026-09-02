package io.pickwick.app.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** One line, always the same tile height; scrolls sideways while focused. */
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
                Text(emoji, fontSize = TextUnit(56f, TextUnitType.Sp))
            }
            Box(Modifier.padding(8.dp)) {
                Text(
                    label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }
}

/**
 * "⏳ 42 min left" in the home header. Amber inside the last five minutes so
 * the kid sees it coming before the player's pill says so.
 */
@Composable
internal fun TimeChip(remainingMs: Long) {
    val urgent = remainingMs <= 5 * 60_000L
    Text(
        "⏳ " + remainingLabel(remainingMs),
        maxLines = 1,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = Color.White,
        modifier = Modifier
            .background(
                if (urgent) Color(0xFFB26A00) else MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
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
    statusBadge: (@Composable BoxScope.() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val finished = (item.progress ?: 0f) >= 0.98f
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
            .graphicsLayer { alpha = if (finished) 0.55f else 1f }
            .clip(RoundedCornerShape(14.dp))
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
            item.progress?.let { fraction -> WatchedProgressBar(fraction) }
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
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .then(channelTap)
            ) {
                if (avatarUrl != null) {
                    PosterImage(avatarUrl, item.video.channelName, Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    item.video.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall.copy(
                        lineHeight = TextUnit(18f, TextUnitType.Sp)
                    )
                )
                Text(
                    item.video.channelName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * A channel in the home row: round avatar, name under it, a NEW dot when
 * there's something unseen. The round shape is what says "channel" on every
 * video app a kid has seen.
 */
@Composable
internal fun ChannelChip(
    name: String,
    avatarUrl: String?,
    isNew: Boolean,
    modifier: Modifier = Modifier,
    emoji: String? = null,
    tint: Color = MaterialTheme.colorScheme.surfaceVariant,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(84.dp)
            .pressScale(interaction)
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onClick() }
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Box {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(tint)
            ) {
                if (emoji != null) {
                    Text(emoji, fontSize = TextUnit(30f, TextUnitType.Sp))
                } else {
                    PosterImage(avatarUrl, name, Modifier.fillMaxSize())
                }
            }
            if (isNew) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .background(Color(0xFF4DB6AC), CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium
        )
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
        Row(Modifier.padding(top = 8.dp)) {
            Box(Modifier.size(28.dp).background(tone, CircleShape))
            Spacer(Modifier.width(8.dp))
            Column {
                Box(Modifier.fillMaxWidth(0.9f).height(14.dp).background(tone, RoundedCornerShape(4.dp)))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(0.5f).height(12.dp).background(tone, RoundedCornerShape(4.dp)))
            }
        }
    }
}

/** Big, rounded section heading with an optional trailing action ("Show all"). */
@Composable
internal fun SectionRow(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f)
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction, modifier = Modifier.tvFocusHighlight()) {
                Text(action, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
