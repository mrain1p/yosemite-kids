package io.yosemitekids.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// THE TEN-FOOT NAV RAIL — the television's whole chrome.
//
// Before this, a page below home carried two chips in its title row
// ([TvTopChips]) and the home carried a header with the mark, the greeting,
// the time pill and a search icon. Neither was a place: the chips appeared on
// some screens and not others, and there was nowhere a kid could look to
// answer "where am I and where else can I go". The rail is that place, on
// every page, in one fixed column down the left edge.
//
// IT IS ALSO THE FIRST FOCUSABLE IN THE TREE, which is a bigger change than it
// looks. Every existing focus-loss path — a dialog dismissed, coming back from
// the player, a screen switch that lands nowhere — used to resolve to the
// first content tile on the next key press. It now resolves to the rail, and
// reads as "the remote jumped to the menu". The content container carries
// `focusRestorer()` for exactly that reason; see [YosemiteScreen].
//
// TV GEOMETRY IS PROVISIONAL, exactly as in ChannelShelves.kt and
// HomeShelves.kt: every dp here is the handoff's design unit through
// [tvUnits]. Nobody has held a remote in front of the real television with
// these numbers on it.
// ---------------------------------------------------------------------------

// THE PAGE BESIDE IT NARROWS, AND THAT IS THE PART WITH NO COMPILER ERROR.
// The page takes whatever the rail leaves, so every ten-foot tile in this app
// — all of them sized against a ~880 dp page — now lives in 834 dp with the
// rail closed and 753 dp with it open. Nothing fails when a tile stops
// fitting: a grid quietly drops a column, a row quietly squeezes, and it
// photographs fine on the one panel anybody tried. `TvNavRailTest` is what
// holds them.

/**
 * The rail with its labels showing: the mark and the kid's name, four named
 * destinations, the time card. 196 design units.
 *
 * Named rather than inlined because it is arithmetic the page depends on, and
 * `TvNavRailTest` pins what still fits beside it.
 */
internal val TV_RAIL_EXPANDED = tvUnits(196f)

/**
 * The rail once focus has moved into the page: icons only, 88 units.
 *
 * This is the width that matters for tile sizes, because it is the width for
 * all of the time a kid is actually browsing — the rail is only open while the
 * remote is in it, or before the remote has been touched at all.
 */
internal val TV_RAIL_COLLAPSED = tvUnits(88f)

/** The four places the rail goes. Search is the fourth; see [railStopFor]. */
internal enum class RailStop(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Channels("Channels", YosemiteIcons.Channels),
    You("You", Icons.Filled.Person),
    Search("Search", Icons.Filled.Search)
}

/**
 * Which rail item is lit for a given screen, or none.
 *
 * Deliberately its own `when` over the sealed [Screen] rather than a call to
 * the phone's `tabFor`: the phone has three tabs and the rail has four stops,
 * and the compiler's exhaustiveness check here is what makes a new screen a
 * decision instead of an omission. A screen that belongs to no stop lights
 * nothing — that is honest, not a gap.
 */
internal fun railStopFor(screen: Screen): RailStop? = when (screen) {
    Screen.Home -> RailStop.Home
    Screen.Channels, Screen.Surprise, is Screen.ChannelVideos, is Screen.WatchedVideos,
    is Screen.Playlists -> RailStop.Channels
    Screen.You, Screen.Watchlist, Screen.WatchLater, Screen.Queue, Screen.Downloads,
    Screen.History -> RailStop.You
    Screen.Search, is Screen.SearchResults -> RailStop.Search
}

/**
 * The rail.
 *
 * A container in the sense guard 32 means — it is composed once, by the screen
 * host — but it takes everything it draws, including whether it is [expanded],
 * so a preview or a Compose test can put it in either state without a view
 * model and without a television.
 *
 * [onFocusChange] is how the host learns the remote has arrived or left; the
 * host owns the expanded/collapsed decision because "expanded" is also true
 * before the kid has touched the remote at all, which the rail cannot know.
 */
@Composable
internal fun TvNavRail(
    /** The lit item, from [railStopFor]. */
    current: RailStop?,
    /** Whose television this is. Null before profiles exist; the name is dropped, not faked. */
    kidName: String?,
    /**
     * The chrome's live time-left (see `TimeLeft.kt`), handed on unread so the
     * once-a-second tick recomposes the card at the bottom and not the rail.
     * THE SAME STATE THE PHONE'S TOP BAR GETS — one ticker for the app, or two
     * pieces of chrome drift a second apart and a kid sees two numbers.
     */
    timeLeft: State<Long?>,
    expanded: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onStop: (RailStop) -> Unit
) {
    val width by animateDpAsState(
        if (expanded) TV_RAIL_EXPANDED else TV_RAIL_COLLAPSED,
        tween(220), label = "railWidth"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            // ORDER IS LOAD-BEARING: onFocusChanged only observes focus
            // targets that come AFTER it in the chain, so the group it is
            // watching has to follow it. Written the other way round the rail
            // watches its own ancestor, never sees its items take focus, and
            // simply never collapses — which reads as a design deviation
            // rather than as the bug it is. Same order as FocusHighlight.kt.
            .onFocusChanged { onFocusChange(it.hasFocus) }
            .focusGroup()
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            // Labels shrink out of a rail that is narrowing; without this they
            // spill over the page for the length of the animation.
            .clipToBounds()
            .padding(
                start = tvUnits(14f),
                end = tvUnits(14f),
                top = tvUnits(24f),
                bottom = tvUnits(20f)
            )
    ) {
        RailMark(kidName, expanded)
        Spacer(Modifier.height(tvUnits(22f)))
        RailStop.entries.forEach { stop ->
            RailItem(
                stop = stop,
                selected = stop == current,
                expanded = expanded,
                onClick = { onStop(stop) }
            )
            Spacer(Modifier.height(tvUnits(6f)))
        }
        // Everything above is the menu; the card below is pinned to the floor.
        Spacer(Modifier.weight(1f))
        RailTimeCard(timeLeft, expanded)
    }
}

/** The 40-unit action tile, and the kid's own name beside it when there is room. */
@Composable
private fun RailMark(kidName: String?, expanded: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        AppMarkTile(size = tvUnits(40f), radius = tvUnits(11f))
        if (expanded && !kidName.isNullOrBlank()) {
            Spacer(Modifier.width(tvUnits(12f)))
            Text(
                kidName,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = tvTypeUnits(16f),
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * One destination.
 *
 * Expanded it is a 48-unit row — icon, label, a raised pill behind the one you
 * are on. Collapsed it is a 60-unit round target, which is the handoff's
 * minimum focusable size on a television and the reason the collapsed rail is
 * not simply the expanded one with the words hidden.
 *
 * Focus is [tvFocusHighlight] and nothing else: the same 3 dp accent ring and
 * lift as the ~111 other focusables in this app. A rail that invented its own
 * focus look would be the one place on the television where "where am I" is
 * answered differently.
 */
@Composable
private fun RailItem(
    stop: RailStop,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val tokens = kidTokens
    val fg = if (selected) tokens.action else MaterialTheme.colorScheme.onSurfaceVariant
    val radius = if (expanded) tvUnits(12f) else tvUnits(30f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center,
        modifier = Modifier
            .then(
                if (expanded) Modifier.fillMaxWidth().height(tvUnits(48f))
                else Modifier.size(tvUnits(60f))
            )
            .tvFocusHighlight(cornerRadius = radius)
            .clip(RoundedCornerShape(radius))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent
            )
            .selectable(selected = selected, role = Role.Tab) { onClick() }
            .padding(horizontal = if (expanded) tvUnits(14f) else 0.dp)
    ) {
        Icon(
            stop.icon,
            // Collapsed there is no label, so the icon has to carry the name
            // for a screen reader; expanded the label says it and a second
            // copy would be read twice.
            contentDescription = if (expanded) null else stop.label,
            tint = fg,
            modifier = Modifier.size(tvUnits(20f))
        )
        if (expanded) {
            Spacer(Modifier.width(tvUnits(12f)))
            Text(
                stop.label,
                color = fg,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = tvTypeUnits(16f),
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
                )
            )
        }
    }
}

/**
 * "20m left" at the foot of the rail, or nothing at all.
 *
 * THE ONE PLACE THE RAIL READS THE LIVE VALUE, so the second-by-second
 * recomposition stops here instead of redrawing four focusable items and the
 * kid's name every tick. Same rule, and the same reason, as [TimeLeftPill].
 *
 * Null is drawn as nothing, never as a placeholder: a dash where a number
 * belongs reads to a five-year-old as "nought minutes left".
 *
 * The urgent treatment is [TimeChip]'s, deliberately: inside the last five
 * minutes the fill drops back to the page's own ground so the label can be
 * drawn in [KidTokens.timeWarning], which is the one colour `KidThemeContrastTest`
 * guarantees against *that* background on every look. Amber on the raised step
 * is 3.8:1 and this is not large text.
 */
@Composable
private fun RailTimeCard(left: State<Long?>, expanded: Boolean) {
    val ms = left.value ?: return
    val tokens = kidTokens
    val urgent = ms <= 5 * 60_000L
    val label = if (urgent) tokens.timeWarning else MaterialTheme.colorScheme.onSurface
    val radius = tvUnits(14f)
    val shape = RoundedCornerShape(radius)
    val text = if (expanded) "${remainingShort(ms)} left" else remainingShort(ms)
    val type = MaterialTheme.typography.labelSmall.copy(
        fontSize = tvTypeUnits(if (expanded) 14f else 12f),
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace
    )
    val skin = Modifier
        .clip(shape)
        .background(
            if (urgent) MaterialTheme.colorScheme.background
            else MaterialTheme.colorScheme.surfaceContainerHigh
        )
        .then(if (urgent) Modifier.border(1.dp, tokens.timeWarning, shape) else Modifier)
        // The card says "20m left"; a screen reader still gets the sentence.
        .semantics { contentDescription = remainingLabel(ms) }
    if (expanded) Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(tvUnits(40f)).then(skin)
            .padding(horizontal = tvUnits(12f))
    ) {
        Box(Modifier.size(tvUnits(10f)).background(tokens.timeWarning, CircleShape))
        Spacer(Modifier.width(tvUnits(8f)))
        Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, style = type, color = label)
    } else Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.size(tvUnits(56f)).then(skin)
    ) {
        Box(Modifier.size(tvUnits(10f)).background(tokens.timeWarning, CircleShape))
        Spacer(Modifier.height(tvUnits(6f)))
        Text(text, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, style = type, color = label)
    }
}
