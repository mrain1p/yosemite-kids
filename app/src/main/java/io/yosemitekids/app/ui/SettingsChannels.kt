package io.yosemitekids.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.TIME_MULTIPLIERS
import io.yosemitekids.app.data.WhitelistEntry
import io.yosemitekids.app.data.WhitelistParser
import io.yosemitekids.app.data.YouTubeRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Channels & playlists, built to raw-channels.png.
 *
 * The page used to be one tile per source carrying every control at once —
 * time-multiplier chip, per-kid chips, playlist pins, AI note, delete — with
 * the search box and the paste field under the list and the directory under
 * those. Fifty tiles of five controls each was the page a parent scrolled to
 * find one channel.
 *
 * Now the page is a list: search, filter tabs, a count and a sort, then one
 * row per source — initial, name, "Channel · Amelia", chevron. Everything a
 * row used to carry lives on the source's own page ([SourcePage]). Adding is
 * behind the app bar's **+** ([AddSourceSheet]), removing several at once
 * behind **Select**.
 */

// --- List state -------------------------------------------------------------

/** How many rows the list shows before "Show more". */
internal const val SOURCES_PER_PAGE = 30

/** The filter tabs under the search field. */
internal sealed class SourceFilter {
    object All : SourceFilter()
    object Channels : SourceFilter()
    object Playlists : SourceFilter()

    /** Added this session and not yet looked at — the NEW tag's set. */
    object New : SourceFilter()

    /** Visible to every kid. Only offered once there are two kids to differ. */
    object Everyone : SourceFilter()
    data class Kid(val id: String) : SourceFilter()

    /**
     * Assigned only to kids who no longer exist. The per-kid chips refuse to
     * untick the last kid, so the only way here is removing a kid a source
     * was reserved for — and then the source silently shows nowhere.
     */
    object Nobody : SourceFilter()
}

internal enum class SourceSort(val label: String) {
    /** The config list is append-order, so its reverse is newest first. */
    RECENT("Recently added"),
    ALPHA("A to Z")
}

/**
 * Everything the list page remembers across a push to a source page and
 * back. Held by AdminScreen above its early returns: a `remember` inside the
 * Channels branch leaves composition the moment a row opens, and the search
 * and the selection would be gone on return.
 */
internal class ChannelListState {
    var query by mutableStateOf("")
    var filter by mutableStateOf<SourceFilter>(SourceFilter.All)
    var sort by mutableStateOf(SourceSort.RECENT)
    var selecting by mutableStateOf(false)
    var selected by mutableStateOf(setOf<String>())
    var shown by mutableStateOf(SOURCES_PER_PAGE)

    fun stopSelecting() {
        selecting = false
        selected = emptySet()
    }

    /** Back to the page's first-visit shape. */
    fun reset() {
        query = ""
        filter = SourceFilter.All
        sort = SourceSort.RECENT
        shown = SOURCES_PER_PAGE
        stopSelecting()
    }
}

internal fun sourceKindLabel(entry: WhitelistEntry): String =
    if (entry.kind == SourceKind.PLAYLIST) "Playlist" else "Channel"

/**
 * Who sees [entry], as the row spells it: the kid's name in a one-kid family,
 * "Everyone", the named kids, or "Nobody" (see [SourceFilter.Nobody]).
 */
internal fun sourceAudience(entry: WhitelistEntry, profiles: List<Profile>): String {
    val everyone = if (profiles.size == 1) profiles[0].name else "Everyone"
    if (entry.profileIds.isEmpty()) return everyone
    val live = profiles.filter { it.id in entry.profileIds }
    return when {
        live.isEmpty() -> "Nobody"
        live.size == profiles.size -> everyone
        else -> live.joinToString(", ") { it.name }
    }
}

/** The second half of a row's meta line: what is new about it, else who sees it. */
internal fun sourceMetaTail(entry: WhitelistEntry, profiles: List<Profile>, isNew: Boolean): String =
    if (isNew) "just added" else sourceAudience(entry, profiles)

/**
 * The whole meta line, as one sentence in one colour. A source no kid can see
 * says so plainly rather than spelling it "Channel · Nobody": the row is a
 * problem to fix, and the kind of source is not the news.
 */
internal fun sourceMetaLine(entry: WhitelistEntry, profiles: List<Profile>, isNew: Boolean): String =
    if (sourceAudience(entry, profiles) == "Nobody") "No kid can see this"
    else "${sourceKindLabel(entry)} · ${sourceMetaTail(entry, profiles, isNew)}"

/**
 * The letter an avatar stands in with. A leading article is not what anyone
 * scans for — "The Magic School Bus" files under M, the way the design (and a
 * bookshelf) has it.
 */
internal fun sourceInitial(name: String): String =
    name.trim().removePrefix("The ").removePrefix("A ")
        .firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

/** The rows the list shows for one combination of tab, search and sort. */
internal fun filterSources(
    entries: List<WhitelistEntry>,
    filter: SourceFilter,
    query: String,
    sort: SourceSort,
    newIds: Set<String>,
    profiles: List<Profile>,
    nameOf: (WhitelistEntry) -> String
): List<WhitelistEntry> {
    val q = query.trim()
    val picked = entries.filter { e ->
        val byTab = when (filter) {
            SourceFilter.All -> true
            SourceFilter.Channels -> e.kind == SourceKind.CHANNEL
            SourceFilter.Playlists -> e.kind == SourceKind.PLAYLIST
            SourceFilter.New -> e.id in newIds
            SourceFilter.Everyone ->
                e.profileIds.isEmpty() || profiles.all { it.id in e.profileIds }
            is SourceFilter.Kid -> e.profileIds.isEmpty() || filter.id in e.profileIds
            SourceFilter.Nobody ->
                e.profileIds.isNotEmpty() && profiles.none { it.id in e.profileIds }
        }
        byTab && (q.isEmpty() || nameOf(e).contains(q, ignoreCase = true) ||
            e.id.contains(q, ignoreCase = true))
    }
    return when (sort) {
        SourceSort.RECENT -> picked.asReversed()
        SourceSort.ALPHA -> picked.sortedBy { nameOf(it).lowercase() }
    }
}

// --- Shared parts of the two search pages ---------------------------------------

/**
 * The design's search field: a 40dp box with the magnifier inside it, not
 * M3's 56dp `OutlinedTextField` with its floating label machinery.
 *
 * [borderColor] is the whole difference between the two callers — the channel
 * list's is the quiet card border, because searching is one of several things
 * that page does; the YouTube search's is teal at all times, because typing
 * there is the page's only job. Either way focus brightens it, which is the
 * only thing a TV remote has to go on once the M3 field is gone.
 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    borderColor: Color,
    iconTint: Color = borderColor,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                1.dp,
                if (focused) MaterialTheme.colorScheme.primary else borderColor,
                shape
            )
            .padding(horizontal = 12.dp)
    ) {
        Icon(
            Icons.Filled.Search, contentDescription = null,
            tint = iconTint, modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(9.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            // BasicTextField draws in black on a black page unless both the
            // text and the caret are named.
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focused = it.isFocused || it.hasFocus },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = SettingsPlaceholder,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    inner()
                }
            }
        )
        if (trailing != null) trailing()
    }
}

/**
 * The quiet row of words both search pages filter with: the active one in
 * teal over a 2dp underline of its own width, the rest grey. Chips would put
 * six filled pills across a 344dp page; these read as a heading that happens
 * to be tappable.
 */
@Composable
private fun <T> TextTabs(
    tabs: List<Pair<T, String>>,
    selected: T,
    /** The channel list's tabs outgrow the page once a family has kids. */
    scrollable: Boolean,
    onPick: (T) -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
    ) {
        tabs.forEach { (value, label) ->
            val on = value == selected
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                fontWeight = FontWeight.Medium,
                color = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .tvFocusHighlight(cornerRadius = 8.dp)
                    .clickable { onPick(value) }
                    // Drawn rather than laid out: the underline is exactly the
                    // word's width, which a Box child inside a Row cannot be
                    // without measuring the text twice.
                    .drawBehind {
                        if (on) drawRect(
                            color = accent,
                            topLeft = Offset(0f, size.height - 2.dp.toPx()),
                            size = Size(size.width, 2.dp.toPx())
                        )
                    }
                    .padding(top = 2.dp, bottom = 7.dp)
            )
        }
    }
}

// --- The list -----------------------------------------------------------------

/** The app bar's **Select** / **Done** and **+** (raw-channels.png). */
@Composable
internal fun ChannelsActions(state: ChannelListState, onAdd: () -> Unit) {
    TextButton(
        modifier = Modifier.tvFocusHighlight(),
        onClick = { if (state.selecting) state.stopSelecting() else state.selecting = true }
    ) { Text(if (state.selecting) "Done" else "Select") }
    IconButton(modifier = Modifier.tvFocusHighlight(), onClick = onAdd) {
        Icon(
            Icons.Filled.Add,
            contentDescription = "Add a channel or playlist",
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
internal fun ChannelsSection(
    entries: List<WhitelistEntry>,
    newIds: Set<String>,
    /** url → real name, resolved by AdminScreen for entries without a label. */
    resolvedNames: Map<String, String>,
    profiles: List<Profile>,
    state: ChannelListState,
    onOpen: (WhitelistEntry) -> Unit,
    onRemove: (Set<String>) -> Unit
) {
    fun displayName(entry: WhitelistEntry) =
        entry.label ?: resolvedNames[entry.url] ?: entry.id

    val visible = filterSources(
        entries, state.filter, state.query, state.sort, newIds, profiles, ::displayName
    )
    var confirmRemove by remember { mutableStateOf(false) }
    var sortSheet by remember { mutableStateOf(false) }

    if (confirmRemove) {
        val n = state.selected.size
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(if (n == 1) "Remove 1 source?" else "Remove $n sources?") },
            text = {
                Text(
                    "The kids stop seeing anything from ${if (n == 1) "it" else "them"}. " +
                        "Their watch history and favorites are kept, so adding it back " +
                        "restores where they were."
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRemove(state.selected)
                    state.stopSelecting()
                    confirmRemove = false
                }) { Text("Remove $n") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            }
        )
    }
    if (sortSheet) SortSheet(
        current = state.sort,
        onPick = { state.sort = it; sortSheet = false },
        onDismiss = { sortSheet = false }
    )

    SearchField(
        value = state.query,
        onValueChange = { state.query = it; state.shown = SOURCES_PER_PAGE },
        placeholder = "Search your sources",
        borderColor = MaterialTheme.colorScheme.outline,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    // Text tabs, not chips: the design draws them as a quiet row of words
    // with the active one in teal, and it scrolls sideways so a family of
    // four does not wrap it into a block.
    val tabs = buildList {
        add(SourceFilter.All to "All")
        add(SourceFilter.Channels to "Channels")
        add(SourceFilter.Playlists to "Playlists")
        add(SourceFilter.New to "New")
        if (profiles.size >= 2) {
            add(SourceFilter.Everyone to "Everyone")
            profiles.forEach { add(SourceFilter.Kid(it.id) to it.name) }
        }
        add(SourceFilter.Nobody to "Nobody sees")
    }
    TextTabs(tabs = tabs, selected = state.filter, scrollable = true) { filter ->
        state.filter = filter
        state.shown = SOURCES_PER_PAGE
    }
    SettingsDivider()

    // The count line and the sort, 4dp further in than the page padding. It
    // stays put in select mode: what is being selected out of how many is
    // exactly the thing a bulk edit needs stated.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, start = 4.dp, end = 4.dp)
    ) {
        val total = entries.size
        val noun = if (total == 1) "source" else "sources"
        Text(
            buildString {
                if (visible.size == total) append("$total $noun")
                else append("${visible.size} of $total $noun")
                if (visible.size > state.shown) append(" · showing first ${state.shown}")
            },
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // Its own target rather than a CompactButton: the design sets the
        // label's right edge 6dp past the text column, which the offset buys
        // back from the button's own padding.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .offset(x = 6.dp)
                .clip(RoundedCornerShape(7.dp))
                .clickable { sortSheet = true }
                .tvFocusHighlight(cornerRadius = 7.dp)
                .padding(horizontal = 6.dp, vertical = 8.dp)
        ) {
            Icon(
                YosemiteIcons.Sort, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                state.sort.label,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }

    // The design pins this bar to the bottom of the screen; SubPage has no
    // slot under its scroll, so it sits over the list instead — where it is
    // still one thumb-move from the rows being ticked.
    if (state.selecting) ChannelsBulkBar(
        visible = visible,
        state = state,
        onRemove = { confirmRemove = true }
    )

    when {
        entries.isEmpty() -> Text(
            "Nothing allowed yet — tap + to add a channel or playlist.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        visible.isEmpty() -> Text(
            "No sources match.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        else -> SettingsCard(padded = false) {
            val rows = visible.take(state.shown)
            rows.forEachIndexed { i, entry ->
                val picked = entry.id in state.selected
                val isNew = entry.id in newIds
                val stranded = sourceAudience(entry, profiles) == "Nobody"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (picked) Modifier.background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            ) else Modifier
                        )
                        .tvFocusHighlight()
                        .clickable {
                            if (state.selecting) {
                                state.selected =
                                    if (picked) state.selected - entry.id else state.selected + entry.id
                            } else onOpen(entry)
                        }
                        // The tick box is what a row says about itself in select
                        // mode; without this TalkBack reads it as a plain button.
                        .semantics { if (state.selecting) selected = picked }
                        .heightIn(min = 60.dp)
                        .padding(horizontal = 11.dp, vertical = 9.dp)
                ) {
                    SourceAvatar(displayName(entry), size = 32)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            displayName(entry),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        // One line, one colour: amber when nothing can see it,
                        // green while it is new, grey otherwise. Teal is
                        // reserved for what a parent can press.
                        Text(
                            sourceMetaLine(entry, profiles, isNew),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = when {
                                stranded -> WarningAmber
                                isNew -> SettingsSuccess
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(11.dp))
                    if (state.selecting) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (picked) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .border(
                                    1.5.dp,
                                    if (picked) MaterialTheme.colorScheme.primary
                                    else SettingsStrongBorder,
                                    RoundedCornerShape(6.dp)
                                )
                        ) {
                            if (picked) Text(
                                "✓",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    } else {
                        Icon(
                            YosemiteIcons.ChevronRight, contentDescription = null,
                            tint = SettingsPlaceholder,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (i < rows.lastIndex) SettingsDivider()
            }
        }
    }
    if (visible.size > state.shown) {
        val rest = visible.size - state.shown
        Spacer(Modifier.height(4.dp))
        CompactButton(onClick = { state.shown += SOURCES_PER_PAGE }) {
            Text("Show ${minOf(rest, SOURCES_PER_PAGE)} more")
        }
    }
}

/**
 * The initial that stands in for a channel's avatar. A circle in the source
 * list, where it reads as a face; a [rounded] square in the search results,
 * which is how the design draws a thing you have not allowed yet.
 */
@Composable
private fun SourceAvatar(name: String, size: Int, rounded: Boolean = false) {
    val shape = if (rounded) RoundedCornerShape(8.dp) else CircleShape
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(
                if (rounded) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
    ) {
        Text(
            sourceInitial(name),
            style = when {
                size >= 48 -> MaterialTheme.typography.titleMedium
                rounded -> MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp)
                else -> MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp)
            },
            fontWeight = FontWeight.Medium,
            color = if (size >= 48 || rounded) MaterialTheme.colorScheme.onSurfaceVariant
                else SettingsTextSecondary
        )
    }
}

/**
 * Select mode's bar: what is ticked, select-all, and the one destructive
 * action. Its own block rather than three [CompactButton]s in the count row —
 * a bulk delete should look like a mode the page is in.
 */
@Composable
private fun ChannelsBulkBar(
    visible: List<WhitelistEntry>,
    state: ChannelListState,
    onRemove: () -> Unit
) {
    val allPicked = visible.isNotEmpty() && state.selected.containsAll(visible.map { it.id })
    val any = state.selected.isNotEmpty()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, SettingsStrongBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Text(
            if (any) "${state.selected.size} selected" else "Tap sources to remove",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = SettingsTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            onClick = {
                state.selected =
                    if (allPicked) emptySet() else visible.mapTo(mutableSetOf()) { it.id }
            },
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, SettingsStrongBorder),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp).tvFocusHighlight(cornerRadius = 8.dp)
        ) {
            Text(
                if (allPicked) "Clear all" else "Select all ${visible.size}",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
        Button(
            onClick = onRemove,
            enabled = any,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
                disabledContentColor = SettingsPlaceholder
            ),
            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp).tvFocusHighlight(cornerRadius = 8.dp)
        ) {
            Text(
                "Remove",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

/**
 * What the sort control opens. Two options is a small sheet, but it says
 * which one is on and what the setting does and does not reach — the old
 * toggle-on-tap left both unanswerable without pressing it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(current: SourceSort, onPick: (SourceSort) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp)) {
                Text(
                    "Sort sources",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "Changes the order you see here. The kid's own order is set " +
                        "under How videos are listed.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            SourceSort.values().forEachIndexed { i, sort ->
                if (i > 0) SettingsDivider()
                val on = sort == current
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusHighlight(cornerRadius = 8.dp)
                        .clickable { onPick(sort) }
                        .semantics { selected = on }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(
                        sort.label,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp),
                        color = if (on) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (on) Text(
                        "✓",
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// --- One source ---------------------------------------------------------------

/**
 * The page a row opens: everything the row used to carry. Time multiplier,
 * which kids see it, pinned playlists, the AI note, and Remove.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun SourcePage(
    entry: WhitelistEntry,
    name: String,
    isNew: Boolean,
    yt: YouTubeRepository,
    profiles: List<Profile>,
    onBack: () -> Unit,
    onChanged: (WhitelistEntry) -> Unit,
    onRemove: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var noteOpen by remember { mutableStateOf(false) }
    var playlistsOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    if (noteOpen) SourceNoteDialog(
        entry, name,
        onDismiss = { noteOpen = false },
        onSave = { onChanged(entry.copy(aiNote = it)); noteOpen = false }
    )
    if (playlistsOpen) PinnedPlaylistsDialog(
        entry, name, yt,
        onDismiss = { playlistsOpen = false },
        onSave = { onChanged(entry.copy(playlistIds = it)); playlistsOpen = false }
    )
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove $name?") },
        text = { Text("It disappears from the kids' apps as soon as the change syncs.") },
        confirmButton = {
            Button(onClick = { confirmRemove = false; onRemove() }) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
    )

    SubPage(title = name, onBack = onBack) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceAvatar(name, size = 48)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    sourceKindLabel(entry) + if (isNew) " · just added" else "",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    entry.url.removePrefix("https://").removePrefix("www."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            CompactButton(onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(entry.url)
                        )
                    )
                }
            }) { Text("YouTube") }
        }
        Spacer(Modifier.height(16.dp))

        SettingsCard {
            Text("Screen time", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TIME_MULTIPLIERS.forEach { pct ->
                    FilterChip(
                        selected = entry.timeMultiplierPercent == pct,
                        onClick = { onChanged(entry.copy(timeMultiplierPercent = pct)) },
                        label = { Text(timeMultiplierLabel(pct)) },
                        modifier = Modifier.tvFocusHighlight()
                    )
                }
            }
            Text(
                "How fast watching this uses up the day's minutes. 1x is normal, FREE " +
                    "never counts. The kid sees the same tag on the tile.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingsDivider()
            Text("Who can see it", style = MaterialTheme.typography.labelLarge)
            if (profiles.size >= 2) {
                KidToggleChips(
                    profiles = profiles,
                    selectedIds = entry.profileIds,
                    onChanged = { onChanged(entry.copy(profileIds = it)) }
                )
            } else {
                Text(
                    sourceAudience(entry, profiles),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (entry.kind == SourceKind.CHANNEL) {
                SettingsDivider()
                ValueRow(
                    title = "Pinned playlists",
                    summary = "Rows at the top of this channel's page",
                    value = entry.playlistIds.size.let { if (it == 0) "None" else "$it pinned" },
                    onClick = { playlistsOpen = true }
                )
            }
            SettingsDivider()
            ValueRow(
                title = "AI instructions",
                summary = "Rules for this source only, on top of the family rules",
                value = if (entry.aiNote.isNullOrBlank()) "None" else "Set",
                onClick = { noteOpen = true }
            )
        }

        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = { confirmRemove = true },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier.fillMaxWidth().tvFocusHighlight()
        ) { Text("Remove $name") }
    }
}

@Composable
private fun SourceNoteDialog(
    entry: WhitelistEntry,
    name: String,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit
) {
    var text by remember(entry.id) { mutableStateOf(entry.aiNote.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI instructions — $name") },
        text = {
            Column {
                Text(
                    "Rules for this channel only, applied on top of the family " +
                        "rules (and winning where they clash). This channel's " +
                        "videos are re-checked under the new instructions; " +
                        "already-blocked ones stay blocked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 3,
                    placeholder = {
                        Text(
                            "e.g. Mild cartoon slapstick is fine here. " +
                                "Block anything filmed as a \"prank\"."
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(text.trim().ifEmpty { null }) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Which of this channel's own playlists become rows on its page. The listing
 * is the same one the kid's "By playlist" layout uses (cached a day per
 * channel), so the phone pays one small request per channel the parent
 * actually opens this for.
 */
@Composable
private fun PinnedPlaylistsDialog(
    entry: WhitelistEntry,
    name: String,
    yt: YouTubeRepository,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var refs by remember(entry.id) { mutableStateOf<List<io.yosemitekids.app.data.PlaylistRef>?>(null) }
    var failed by remember(entry.id) { mutableStateOf(false) }
    var picked by remember(entry.id) { mutableStateOf(entry.playlistIds) }
    LaunchedEffect(entry.id) {
        val cache = io.yosemitekids.app.data.ChannelPlaylistsCache(context)
        val source = io.yosemitekids.app.data.Source(
            entry.id, entry.url, name, null, SourceKind.CHANNEL
        )
        refs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            cache.load(entry.id)?.takeIf { cache.isFresh(entry.id) }
        } ?: runCatching { yt.channelPlaylists(source) }
            .onSuccess { fresh ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cache.save(entry.id, fresh) }
            }
            .onFailure { failed = true }
            .getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pinned playlists — $name") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "All of this channel's playlists already show on its page. Ticked " +
                        "ones are pinned to the top as rows, in the order you tick them; " +
                        "Shorts never make a row.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                val list = refs
                when {
                    list == null && !failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Asking YouTube for the playlists…")
                    }
                    failed -> Text("Couldn't list the playlists right now. Try again later.")
                    list!!.isEmpty() -> Text("This channel has no playlists.")
                    else -> list.forEach { ref ->
                        val on = ref.id in picked
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .tvFocusHighlight()
                                .clickable { picked = if (on) picked - ref.id else picked + ref.id }
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(ref.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (ref.videoCount > 0) Text(
                                    "${ref.videoCount} videos",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(picked) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// --- Adding -------------------------------------------------------------------

/**
 * The one way a source enters the list, whichever flow found it. With two or
 * more kids it asks "who is this for?" first; the dialog is emitted here, so
 * call this from the page the answer belongs on.
 */
@Composable
internal fun rememberSourceAdder(
    entries: List<WhitelistEntry>,
    profiles: List<Profile>,
    onChanged: (List<WhitelistEntry>) -> Unit
): (WhitelistEntry) -> Unit {
    var pendingAdd by remember { mutableStateOf<WhitelistEntry?>(null) }
    pendingAdd?.let { entry ->
        WhoForDialog(
            title = "Who is ${entry.label ?: entry.id} for?",
            profiles = profiles,
            initialIds = emptySet(),
            confirmLabel = "Add",
            onDismiss = { pendingAdd = null },
            onConfirm = { forKids ->
                onChanged((entries + entry.copy(profileIds = forKids)).distinctBy { it.id })
                pendingAdd = null
            }
        )
    }
    return { entry ->
        // With one kid (or none) there's nothing to ask.
        if (profiles.size < 2) onChanged((entries + entry).distinctBy { it.id })
        else pendingAdd = entry
    }
}

/** What **+** opens: the three ways in, each leading to its own flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddSourceSheet(
    onDismiss: () -> Unit,
    onSearch: () -> Unit,
    onPaste: () -> Unit,
    onSuggested: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, bottom = 10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Add a source",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "Everything you allow lands in your list, under New.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(44.dp).tvFocusHighlight(cornerRadius = 8.dp)
                ) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            SheetRow("Search YouTube", "By channel or playlist name", onSearch)
            SheetRow(
                "Paste a channel or playlist link",
                "Straight from the YouTube app", onPaste
            )
            SheetRow(
                "Suggested channels",
                "The directory other parents have vetted", onSuggested
            )
        }
    }
}

/**
 * No leading icon: three teal glyphs down the left of a sheet read as a
 * toolbar, and the words already say which way in each row is.
 */
@Composable
private fun SheetRow(title: String, summary: String, onClick: () -> Unit) {
    SettingsDivider()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusHighlight()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.width(11.dp))
        Icon(
            YosemiteIcons.ChevronRight, contentDescription = null,
            tint = SettingsPlaceholder,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** The paste fallback — and the only way a playlist gets in. */
@Composable
internal fun PasteLinkDialog(onDismiss: () -> Unit, onAdd: (WhitelistEntry) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste a link") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    placeholder = { Text("Channel or playlist link") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank(),
                onClick = {
                    val parsed = WhitelistParser.parse(text.trim()).sources.firstOrNull()
                    if (parsed == null) error = "Couldn't recognise that link"
                    else onAdd(parsed)
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * One hit from a YouTube search, either kind, in the shape the row needs.
 *
 * [entry] comes from [WhitelistParser] on the result's URL — the same parse a
 * pasted link goes through — so an add from here is byte-identical to a paste
 * and the "already added" check compares like with like.
 */
internal data class YouTubeHit(
    val entry: WhitelistEntry,
    val name: String,
    val url: String,
    /** "Channel · 1.2M subscribers · science for kids" / "Playlist · 84 videos · by …". */
    val meta: String
)

/**
 * What the meta line says for one hit. The extractor gives a subscriber count
 * of 0 (or -1) when YouTube hides it, and a blank description when there is
 * none; each piece appears only when it is real.
 */
internal fun channelHitMeta(subscribers: Long, description: String?): String =
    listOfNotNull(
        "Channel",
        subscribers.takeIf { it > 0 }?.let { "${formatCount(it)} subscribers" },
        description?.trim()?.takeIf { it.isNotEmpty() }
    ).joinToString(" · ")

internal fun playlistHitMeta(videoCount: Long, uploaderName: String?): String =
    listOfNotNull(
        "Playlist",
        videoCount.takeIf { it > 0 }?.let { "$it video${if (it == 1L) "" else "s"}" },
        uploaderName?.trim()?.takeIf { it.isNotEmpty() }?.let { "by $it" }
    ).joinToString(" · ")

/** The count line: "8 results · 2 already added". */
internal fun hitCountLine(shown: Int, alreadyAdded: Int): String = buildString {
    append("$shown result${if (shown == 1) "" else "s"}")
    if (alreadyAdded > 0) append(" · $alreadyAdded already added")
}

internal fun isAdded(hit: YouTubeHit, entries: List<WhitelistEntry>): Boolean =
    entries.any { it.id == hit.entry.id || it.url == hit.entry.url }

/**
 * Search YouTube by name (raw-ytsearch.png): a field, All / Channels /
 * Playlists, the count line, then one card per hit with its two actions —
 * inspect it in the YouTube app, or add it.
 *
 * One query runs both of the extractor's searches at once, so a parent who
 * types "Operation Ouch" sees the channel and its full-episode playlist in
 * the same list; the tabs narrow what is shown without searching again.
 * The search fires on its own once typing pauses, and on the keyboard's
 * Search key — there is no button in the design.
 */
@Composable
internal fun AddFromYouTubePage(
    entries: List<WhitelistEntry>,
    yt: YouTubeRepository,
    profiles: List<Profile>,
    onBack: () -> Unit,
    onChanged: (List<WhitelistEntry>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<SourceFilter>(SourceFilter.All) }
    var searching by remember { mutableStateOf(false) }
    // null until the first search returns; the query it answered, so a
    // stale list is never shown under a newer query.
    var hits by remember { mutableStateOf<List<YouTubeHit>?>(null) }
    var searchedFor by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val add = rememberSourceAdder(entries, profiles, onChanged)
    BackHandler(onBack = onBack)

    fun search(text: String) {
        val q = text.trim()
        if (q.isEmpty() || q == searchedFor && !failed) return
        scope.launch {
            searching = true
            failed = false
            searchedFor = q
            // Each search is a network round trip through the extractor; run
            // them together so the page answers in the time of one.
            val found = runCatching {
                coroutineScope {
                    val channels = async { yt.searchChannels(q) }
                    val playlists = async { yt.searchPlaylists(q) }
                    channels.await().mapNotNull { r ->
                        WhitelistParser.parse(r.url).sources.firstOrNull()?.let { e ->
                            YouTubeHit(
                                entry = e.copy(label = r.name),
                                name = r.name, url = r.url,
                                meta = channelHitMeta(r.subscriberCount, r.description)
                            )
                        }
                    } + playlists.await().mapNotNull { r ->
                        WhitelistParser.parse(r.url).sources.firstOrNull()?.let { e ->
                            YouTubeHit(
                                entry = e.copy(label = r.name),
                                name = r.name, url = r.url,
                                meta = playlistHitMeta(r.videoCount, r.uploaderName)
                            )
                        }
                    }
                }
            }
            // Only the newest query's answer lands: a slow first search must
            // not overwrite the results of the one typed after it.
            if (searchedFor == q) {
                found.onSuccess { hits = it.distinctBy { h -> h.entry.id } }
                    .onFailure { failed = true }
                searching = false
            }
        }
    }

    // Type-ahead: a pause after the last keystroke runs the search.
    LaunchedEffect(query) {
        if (query.trim().isEmpty()) { hits = null; searchedFor = ""; failed = false; return@LaunchedEffect }
        delay(600)
        search(query)
    }

    SubPage(title = "Add from YouTube", onBack = onBack) {
        Spacer(Modifier.height(12.dp))
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Channel or playlist name",
            // Teal at all times here: typing is the only thing this page does.
            borderColor = MaterialTheme.colorScheme.primary,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search(query) }),
            trailing = {
                if (searching) CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                ) else if (query.isNotEmpty()) IconButton(
                    onClick = { query = "" },
                    modifier = Modifier.size(30.dp).tvFocusHighlight(cornerRadius = 8.dp)
                ) {
                    Icon(
                        Icons.Filled.Close, contentDescription = "Clear the search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        )
        Spacer(Modifier.height(12.dp))

        // The same quiet row of words as the channel list's tabs.
        val tabs = listOf(
            SourceFilter.All to "All",
            SourceFilter.Channels to "Channels",
            SourceFilter.Playlists to "Playlists"
        )
        TextTabs(tabs = tabs, selected = filter, scrollable = false) { filter = it }
        SettingsDivider()

        val all = hits
        val visible = all?.filter { h ->
            when (filter) {
                SourceFilter.Channels -> h.entry.kind == SourceKind.CHANNEL
                SourceFilter.Playlists -> h.entry.kind == SourceKind.PLAYLIST
                else -> true
            }
        }
        when {
            failed -> Text(
                "Couldn't reach YouTube. Check the connection and try again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            visible == null -> Text(
                "Type a name to search YouTube. Anything you add lands tagged NEW, " +
                    "with your per-kid switches and screening applying as usual.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            visible.isEmpty() -> Text(
                if (all.isEmpty()) "Nothing found. Try the exact name, or paste a link from the + menu."
                else "No ${if (filter == SourceFilter.Channels) "channels" else "playlists"} in these results.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            else -> {
                Text(
                    hitCountLine(visible.size, visible.count { isAdded(it, entries) }),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 10.dp)
                )
                // One card holding every hit, divided — eight separate cards
                // read as eight things floating on the page rather than as a
                // list of answers to one question.
                SettingsCard(padded = false) {
                    visible.forEachIndexed { i, hit ->
                        val alreadyAdded = isAdded(hit, entries)
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                SourceAvatar(hit.name, size = 44, rounded = true)
                                Spacer(Modifier.width(11.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                                    ) {
                                        Text(
                                            hit.name,
                                            style = MaterialTheme.typography.bodyMedium
                                                .copy(fontSize = 14.5.sp),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (alreadyAdded) AddedPill()
                                    }
                                    Text(
                                        hit.meta,
                                        style = MaterialTheme.typography.bodySmall
                                            .copy(fontSize = 12.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 3.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(9.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                HitButton("Open in YouTube", filled = false) {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(hit.url)
                                            )
                                        )
                                    }
                                }
                                if (alreadyAdded) HitButton(
                                    "In your list", filled = false, enabled = false
                                ) {} else HitButton("Add to Yosemite Kids", filled = true) {
                                    add(hit.entry)
                                }
                            }
                        }
                        if (i < visible.lastIndex) SettingsDivider()
                    }
                }
                Text(
                    "Inspect anything in YouTube before you allow it. Added sources land " +
                        "tagged NEW in your list, with your per-kid switches and screening " +
                        "applying as usual.",
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontSize = 12.sp, lineHeight = 18.6.sp),
                    color = SettingsPlaceholder,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 11.dp)
                )
            }
        }
    }
}

/**
 * "1.2M" / "912K" / "84". A whole thousand keeps no decimal: the design's
 * meta line reads "912K subscribers", and "912.0K" is a number pretending to
 * a precision YouTube never gave. Formatted US-side so the suffix trim can
 * count on a dot.
 */
internal fun formatCount(n: Long): String = when {
    n >= 1_000_000 ->
        "%.1f".format(java.util.Locale.US, n / 1_000_000.0).removeSuffix(".0") + "M"
    n >= 1_000 ->
        "%.1f".format(java.util.Locale.US, n / 1_000.0).removeSuffix(".0") + "K"
    else -> n.toString()
}

/**
 * "Added" beside a result already in the list. The badge and the button say
 * the same thing on purpose: the badge is what a parent scanning the column
 * sees, the button is what they were reaching for.
 */
@Composable
private fun AddedPill() {
    Text(
        "Added",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
        fontWeight = FontWeight.Medium,
        color = SettingsSuccess,
        // The design's own #1E2A26 needs a token in Theme.kt; the success
        // green at a tenth over the card lands within a shade of it.
        modifier = Modifier
            .background(SettingsSuccess.copy(alpha = 0.12f), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * A result row's two actions: inspect it where it lives, or allow it. 30dp
 * and radius 7 — a search result is a dense list, and M3's 40dp pill made
 * two of them the tallest thing in the card.
 */
@Composable
private fun HitButton(
    label: String,
    filled: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(7.dp)
    val pad = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    val size = Modifier.height(30.dp).tvFocusHighlight(cornerRadius = 7.dp)
    val content: @Composable RowScope.() -> Unit = {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
    if (filled) Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        contentPadding = pad,
        modifier = size,
        content = content
    ) else OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        border = BorderStroke(1.dp, SettingsStrongBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = SettingsTextSecondary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = SettingsTextTertiary
        ),
        contentPadding = pad,
        modifier = size,
        content = content
    )
}
