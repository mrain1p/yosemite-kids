package io.pickwick.app.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The kid-facing chip: a tonal pill, filled from the surface when idle and
 * with the kid's colour when selected, an optional small icon, 36 dp tall.
 * No outline — thin borders on a dark ground read as a wireframe. Used for
 * every sort, filter and shelf chip on the phone and the TV, so the two
 * never drift apart.
 */
@Composable
internal fun PwChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(36.dp)
            .tvFocusHighlight()
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onClick() }
            .padding(horizontal = if (icon != null) 12.dp else 14.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            color = fg,
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        )
    }
}

/** A bare icon in a header — search, back — sized for a thumb and a remote. */
@Composable
internal fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(onClick = onClick, modifier = modifier.size(46.dp).tvFocusHighlight()) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(26.dp))
    }
}

/**
 * A channel's face as a rounded square. YouTube crops avatars to circles,
 * but the art families add is usually a square logo, and a circle cuts its
 * corners off (the black dot on a diamond, a wordmark losing its ends). A
 * squircle keeps the whole picture and still reads as "channel".
 */
@Composable
internal fun ChannelArt(
    url: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    radius: Dp = size / 4,
    fallback: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(fallback)
    ) {
        if (url != null) PosterImage(url, name, Modifier.fillMaxSize())
    }
}

/** "NEW", as a small pill beside a name — never over the picture. */
@Composable
internal fun NewPill(modifier: Modifier = Modifier) {
    Text(
        "NEW",
        color = MaterialTheme.colorScheme.onPrimary,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/** "Channel · 3 days ago" — the quiet line under a title. Null age = the channel alone. */
internal fun metaLine(channel: String, age: String?): String =
    if (age == null) channel else "$channel · $age"

/**
 * The parent's "show when a video came out" switch, read wherever a tile
 * draws its meta line. A local rather than a parameter: every grid, row and
 * card would otherwise have to thread one boolean through call sites that
 * have nothing else to do with it.
 */
internal val LocalShowVideoAge = androidx.compose.runtime.staticCompositionLocalOf { false }

/** The meta line for a video tile, honouring the setting and an unknown date. */
@Composable
internal fun videoMeta(channel: String, publishedAt: Long?): String =
    metaLine(channel, if (LocalShowVideoAge.current) relativeAge(publishedAt) else null)

/**
 * The right end of every page header: search, then the kid's face. The
 * avatar is the door to their corner — switch, change my look, the locked
 * parent settings — and it is on every screen except the player, so a kid
 * never has to find their way home to restyle themselves.
 */
@Composable
internal fun HeaderActions(
    profile: io.pickwick.app.data.Profile?,
    onOpenHub: (() -> Unit)?,
    onOpenSearch: (() -> Unit)?,
    /**
     * Something is arriving — new videos being fetched, settings syncing, the
     * search index crawling. Draws a slow arc around the avatar.
     *
     * The app already did all of this silently: a settings edit fans out to
     * every device about a second and a half after the last tap, and nothing
     * anywhere said so. A parent watching a TV that had not changed yet had no
     * way to tell "it is coming" from "it did not work", and pressed Push
     * again to find out.
     */
    busy: Boolean = false
) {
    if (onOpenSearch != null) {
        HeaderIconButton(androidx.compose.material.icons.Icons.Filled.Search, "Search", onOpenSearch)
    }
    if (onOpenHub != null) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .tvFocusHighlight()
                .clip(androidx.compose.foundation.shape.CircleShape)
                .clickable { onOpenHub() }
        ) {
            if (busy) BusyRing()
            if (profile != null) ProfileAvatar(profile, size = 34)
            else Icon(
                androidx.compose.material.icons.Icons.Filled.Person,
                contentDescription = "Profile",
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/**
 * A slow arc travelling round the avatar while the app is fetching something.
 *
 * Deliberately quiet: a thin arc at partial opacity, three seconds a turn, no
 * colour change and no movement of the avatar itself. A kid should be able to
 * ignore it completely — it is there so a parent glancing at the screen can
 * tell the difference between working and stuck, not to ask anyone for
 * attention.
 */
@Composable
internal fun BusyRing() {
    val spin = rememberInfiniteTransition(label = "busy")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 3000,
                easing = LinearEasing
            )
        ),
        label = "angle"
    )
    val color = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(Modifier.size(44.dp)) {
        val stroke = 2.5.dp.toPx()
        drawArc(
            color = color.copy(alpha = 0.9f),
            startAngle = angle,
            // A quarter turn, so it reads as motion rather than as a
            // progress value it cannot honestly report.
            sweepAngle = 90f,
            useCenter = false,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            ),
            topLeft = androidx.compose.ui.geometry.Offset(stroke, stroke),
            size = androidx.compose.ui.geometry.Size(size.width - stroke * 2, size.height - stroke * 2)
        )
    }
}
