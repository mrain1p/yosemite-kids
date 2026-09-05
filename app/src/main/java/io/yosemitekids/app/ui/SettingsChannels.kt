package io.yosemitekids.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

    if (confirmRemove) {
        val n = state.selected.size
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(if (n == 1) "Remove 1 source?" else "Remove $n sources?") },
            text = { Text("They disappear from the kids' apps as soon as the change syncs.") },
            confirmButton = {
                Button(onClick = {
                    onRemove(state.selected)
                    state.stopSelecting()
                    confirmRemove = false
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            }
        )
    }

    OutlinedTextField(
        value = state.query,
        onValueChange = { state.query = it; state.shown = SOURCES_PER_PAGE },
        placeholder = { Text("Search your sources") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(4.dp))

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
        add(SourceFilter.Nobody to "Nobody")
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    ) {
        tabs.forEach { (filter, label) ->
            val on = state.filter == filter
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                color = if (on) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .tvFocusHighlight()
                    .clickable { state.filter = filter; state.shown = SOURCES_PER_PAGE }
                    .padding(horizontal = 6.dp, vertical = 8.dp)
            )
        }
    }
    SettingsDivider()

    // The count line, or — in select mode — the selection and its one action.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)
    ) {
        if (state.selecting) {
            Text(
                "${state.selected.size} selected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            CompactButton(onClick = {
                state.selected = if (state.selected.containsAll(visible.map { it.id }))
                    emptySet() else visible.mapTo(mutableSetOf()) { it.id }
            }) {
                Text(if (state.selected.containsAll(visible.map { it.id }) && visible.isNotEmpty())
                    "None" else "All")
            }
            CompactButton(
                enabled = state.selected.isNotEmpty(),
                onClick = { confirmRemove = true }
            ) {
                Text(
                    "Remove",
                    color = if (state.selected.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                )
            }
        } else {
            val total = entries.size
            val noun = if (total == 1) "source" else "sources"
            Text(
                buildString {
                    if (visible.size == total) append("$total $noun")
                    else append("${visible.size} of $total $noun")
                    if (visible.size > state.shown) append(" · showing first ${state.shown}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            CompactButton(onClick = {
                state.sort = if (state.sort == SourceSort.RECENT) SourceSort.ALPHA else SourceSort.RECENT
            }) {
                Icon(
                    YosemiteIcons.Sort, contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(state.sort.label)
            }
        }
    }

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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusHighlight()
                        .clickable {
                            if (state.selecting) {
                                state.selected =
                                    if (picked) state.selected - entry.id else state.selected + entry.id
                            } else onOpen(entry)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (state.selecting) {
                        Checkbox(checked = picked, onCheckedChange = null)
                        Spacer(Modifier.width(4.dp))
                    }
                    SourceAvatar(displayName(entry), size = 36)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            displayName(entry), fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        val isNew = entry.id in newIds
                        Row {
                            Text(
                                "${sourceKindLabel(entry)} · ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                sourceMetaTail(entry, profiles, isNew),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isNew) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (!state.selecting) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            YosemiteIcons.ChevronRight, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
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

/** The initial in a circle that stands in for a channel's avatar. */
@Composable
private fun SourceAvatar(name: String, size: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?",
            style = if (size >= 48) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
        Column(Modifier.padding(horizontal = 8.dp).padding(bottom = 24.dp)) {
            Text(
                "Add a channel or playlist",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            SheetRow(Icons.Filled.Search, "Search YouTube", "Find a channel or playlist by name", onSearch)
            SheetRow(
                Icons.Filled.Edit, "Paste a channel or playlist link",
                "Any YouTube link, playlists included", onPaste
            )
            SheetRow(
                YosemiteIcons.Sparkle, "Suggested channels",
                "The directory other parents have vetted", onSuggested
            )
        }
    }
}

@Composable
private fun SheetRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusHighlight()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Icon(
            icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Channel or playlist name") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (searching) ({
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp)
                )
            }) else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search(query) }),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))

        // The same quiet row of words as the channel list's tabs.
        val tabs = listOf(
            SourceFilter.All to "All",
            SourceFilter.Channels to "Channels",
            SourceFilter.Playlists to "Playlists"
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEach { (f, label) ->
                val on = filter == f
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .tvFocusHighlight()
                        .clickable { filter = f }
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                )
            }
        }
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
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp).wrapContentHeight()
                )
                visible.forEach { hit ->
                    val alreadyAdded = isAdded(hit, entries)
                    SettingsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SourceAvatar(hit.name, size = 40)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    hit.name, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    hit.meta,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                modifier = Modifier.tvFocusHighlight(),
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(hit.url)
                                            )
                                        )
                                    }
                                }
                            ) { Text("Open in YouTube") }
                            Button(
                                modifier = Modifier.tvFocusHighlight(),
                                enabled = !alreadyAdded,
                                onClick = { add(hit.entry) }
                            ) { Text(if (alreadyAdded) "Added ✓" else "Add to Yosemite Kids") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

internal fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}
