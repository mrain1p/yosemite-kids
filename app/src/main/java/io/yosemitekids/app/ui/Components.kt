package io.yosemitekids.app.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
internal fun YosemiteChip(
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

/**
 * The phone's one top bar, in whatever state the page it sits on puts it:
 * the mark, the page name, time left, search, the kid's face.
 *
 * It is one composable and not three because it used to be three. Home drew a
 * logo and a greeting, Channels drew a title and a count, You drew a name and
 * a chip, and the three drifted — different heights, the avatar at three
 * sizes, the time pill on two of them. A kid moving between tabs saw the
 * furniture jump. Anything that belongs to a page goes in [subtitle]; the rest
 * of the bar is the same object on every tab.
 *
 * Leaf, not container: it takes what it draws, so a preview or a test can
 * render any state of it without a view model.
 */
@Composable
internal fun PhoneTopBar(
    /** The page's own name: "Hi, Amelia", "Channels", the kid's name on You. */
    title: String,
    profile: io.yosemitekids.app.data.Profile?,
    /** The avatar's destination — the profile hub, and the only door to settings. */
    onOpenHub: (() -> Unit)?,
    onOpenSearch: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** A page's own line under the title, drawn as a mono micro-label. */
    subtitle: String? = null,
    /** Minutes left today. Null, or blocked outright, and the pill stays away. */
    remainingMs: Long? = null,
    /** Something is arriving; draws the slow arc round the avatar. */
    busy: Boolean = false
) {
    val tokens = kidTokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)
    ) {
        AppMarkTile()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = androidx.compose.ui.unit.TextUnit(
                        19f, androidx.compose.ui.unit.TextUnitType.Sp
                    ),
                    lineHeight = androidx.compose.ui.unit.TextUnit(
                        24f, androidx.compose.ui.unit.TextUnitType.Sp
                    ),
                    fontWeight = FontWeight.Bold
                )
            )
            if (subtitle != null) Text(
                subtitle.uppercase(),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // The micro-label: mono, wide-tracked, small. It is a count,
                // and counts are the one thing in this app that are read as
                // digits rather than as words.
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = androidx.compose.ui.unit.TextUnit(
                        9.5f, androidx.compose.ui.unit.TextUnitType.Sp
                    ),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = androidx.compose.ui.unit.TextUnit(
                        0.14f, androidx.compose.ui.unit.TextUnitType.Em
                    ),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            )
        }
        if (remainingMs != null) TimeChip(remainingMs)
        if (onOpenSearch != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onOpenSearch() }
            ) {
                Icon(
                    Icons.Filled.Search, contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
        if (onOpenHub != null) {
            // 40 dp of face inside a 44 dp target: the drawn size is the
            // design's, the hit area is the platform minimum, and the two are
            // deliberately not the same number.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable { onOpenHub() }
            ) {
                if (busy) BusyRing()
                if (profile != null) ProfileAvatar(profile, size = 40)
                else Icon(
                    Icons.Filled.Person,
                    contentDescription = "Profile",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

/**
 * The launcher tile at header scale: the action colour, a play triangle, the
 * app's one piece of branding on a kid's screen.
 *
 * The triangle is drawn rather than set as "▶" because the glyph's side
 * bearings and line-height padding put it visibly off-centre in a tile this
 * small. These fractions are `ic_launcher.xml`'s, so the triangle's centroid —
 * not its bounding box — lands on the middle, which is what the eye reads as
 * centred.
 */
@Composable
private fun AppMarkTile(size: Dp = 34.dp) {
    val tokens = kidTokens
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).background(tokens.action, RoundedCornerShape(11.dp))
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val play = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.38f, h * 0.28f)
                lineTo(w * 0.76f, h * 0.50f)
                lineTo(w * 0.38f, h * 0.72f)
                close()
            }
            drawPath(play, tokens.onAction)
        }
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

/**
 * "Channel · 3 days ago" — the quiet line under a title.
 *
 * It degrades to whichever halves it has. The design puts a release date on
 * nearly every card, but `showVideoAge` is a parent switch that defaults to
 * off, so the common case here is the channel alone — and the separator has
 * to go with the part it separates. A blank channel name (a cache row from an
 * older build) drops out the same way, rather than leaving the line opening
 * on a dangling "·".
 *
 * `MetaLineTest` holds this: no leading, trailing or doubled separator, for
 * any combination of empty, blank and null.
 */
internal fun metaLine(channel: String, age: String?): String =
    listOf(channel, age)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .joinToString(" · ")

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
    profile: io.yosemitekids.app.data.Profile?,
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
