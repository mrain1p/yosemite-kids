package io.yosemitekids.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.WhitelistEntry
import io.yosemitekids.app.data.WhitelistParser
import io.yosemitekids.app.data.YouTubeRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// --- AI discovery -------------------------------------------------------------

/** A model suggestion that survived verification against real YouTube. */
private data class DiscoveryCard(
    val entry: WhitelistEntry,
    val name: String,
    val url: String,
    val imageUrl: String?,
    val subtitle: String,
    val why: String
)

/**
 * Natural-language channel discovery: the AI proposes candidates, each is
 * verified via a real YouTube search, and only verified results are shown —
 * with the actual name/avatar/counts, an open-in-YouTube inspection link, and
 * an explicit Add. Hallucinated channels never reach the whitelist.
 */
@Composable
internal fun AiDiscoverySection(
    ai: io.yosemitekids.app.data.AiConfig,
    entries: List<WhitelistEntry>,
    yt: YouTubeRepository,
    onAdd: (WhitelistEntry) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    /** Whether [message] is the good news — the one line that reads in green. */
    var messageOk by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var cards by remember { mutableStateOf<List<DiscoveryCard>>(emptyList()) }

    val ready = ai.model.isNotBlank() && (ai.apiKey.isNotBlank() || ai.baseUrl.startsWith("http://"))
    if (!ready) {
        Text(
            "Describe what your kid loves and let AI suggest channels. To use this, " +
                "fill in \"AI connection\" above — endpoint, key and model. Content " +
                "screening is a separate feature and can stay off.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // Off the divider above it, the same as the ready state's first line.
            modifier = Modifier.padding(vertical = 12.dp)
        )
        return
    }

    Text(
        "Describe what your kid loves. Inspect anything in YouTube before adding it.",
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 20.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp)
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 9.dp)
    ) {
        val fieldShape = RoundedCornerShape(8.dp)
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .clip(fieldShape)
                .background(MaterialTheme.colorScheme.background)
                .border(
                    1.dp,
                    if (focused) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    fieldShape
                )
                .padding(horizontal = 14.dp)
        ) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                // A bare BasicTextField draws black on black, and its caret
                // with it: both colours have to be named here.
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused || it.hasFocus },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(
                            "e.g. calm science videos for a dinosaur fan, age 7",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                            color = SettingsPlaceholder,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        inner()
                    }
                }
            )
        }
        Spacer(Modifier.width(7.dp))
        Button(
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp).tvFocusHighlight(cornerRadius = 8.dp),
            enabled = !busy && query.isNotBlank(),
            onClick = {
                scope.launch {
                    busy = true
                    cards = emptyList()
                    messageOk = false
                    message = "Asking ${ai.model}…"
                    val suggestions = runCatching {
                        io.yosemitekids.app.data.AiScreener.suggest(ai, query.trim())
                    }.getOrElse { e ->
                        message = "AI request failed: ${e.message?.take(120)}"
                        busy = false
                        return@launch
                    }
                    if (suggestions.isEmpty()) {
                        message = "No suggestions — try describing it differently"
                        busy = false
                        return@launch
                    }

                    message = "Checking ${suggestions.size} suggestion(s) on YouTube…"
                    val verified = coroutineScope {
                        suggestions.map { s ->
                            async { runCatching { verifySuggestion(s, yt) }.getOrNull() }
                        }.awaitAll()
                    }.filterNotNull().distinctBy { it.entry.id }

                    cards = verified
                    messageOk = verified.isNotEmpty()
                    message = when {
                        verified.isEmpty() -> "None of the suggestions could be verified on YouTube — try again"
                        else -> "Verified ${verified.size} of ${suggestions.size} — " +
                            "inspect, then add what you like"
                    }
                    busy = false
                }
            }
        ) {
            Text(
                if (busy) "…" else "Suggest",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.5.sp),
                fontWeight = FontWeight.Medium
            )
        }
    }
    message?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
            // Green only for the line that says it worked; everything else —
            // asking, checking, nothing found — is a state, not good news.
            color = if (messageOk) SettingsSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 11.dp)
        )
    }

    cards.forEach { card ->
        val alreadyAdded = entries.any { it.id == card.entry.id || it.url == card.entry.url }
        SettingsDivider()
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.Top
            ) {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = card.name,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                )
                Column(Modifier.weight(1f)) {
                    // Neither the name nor the blurb is clamped: a channel a
                    // parent has never heard of is exactly the one whose name
                    // and reason they need in full.
                    Text(
                        card.name,
                        style = MaterialTheme.typography.bodyMedium
                            .copy(fontSize = 14.5.sp, lineHeight = 20.sp)
                    )
                    Text(
                        card.subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            if (card.why.isNotBlank()) Text(
                card.why,
                style = MaterialTheme.typography.bodySmall
                    .copy(fontSize = 12.5.sp, lineHeight = 19.sp),
                // Not teal: the reason is prose, and teal here read as a link.
                color = SettingsTextSecondary,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(top = 9.dp)
            ) {
                DiscoveryButton(
                    label = "Inspect",
                    modifier = Modifier.weight(1f),
                    border = SettingsStrongBorder,
                    contentColor = SettingsTextSecondary,
                    containerColor = MaterialTheme.colorScheme.background,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(card.url)
                                )
                            )
                        }
                    }
                )
                DiscoveryButton(
                    label = if (alreadyAdded) "Added ✓" else "Add",
                    modifier = Modifier.weight(1f),
                    enabled = !alreadyAdded,
                    border = if (alreadyAdded) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.primary,
                    containerColor = SettingsAccentTint,
                    onClick = { onAdd(card.entry) }
                )
            }
        }
    }
}

/**
 * One of a suggestion's two actions. Equal widths on purpose: inspecting is
 * the step the design asks a parent to take first, so it is not the smaller
 * of the two.
 */
@Composable
private fun DiscoveryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    border: Color,
    contentColor: Color,
    containerColor: Color,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, border),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = SettingsPlaceholder
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        // OutlinedButton's own minimum is 40dp, and only a fixed height
        // overrides it.
        modifier = modifier.height(32.dp).tvFocusHighlight(cornerRadius = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** Resolves one AI suggestion against real YouTube; null when nothing matches. */
private suspend fun verifySuggestion(
    s: io.yosemitekids.app.data.AiScreener.Suggestion,
    yt: YouTubeRepository
): DiscoveryCard? = when (s.kind) {
    SourceKind.CHANNEL -> yt.searchChannels(s.searchQuery).firstOrNull()?.let { r ->
        WhitelistParser.parse(r.url).sources.firstOrNull()?.let { e ->
            DiscoveryCard(
                entry = e.copy(label = r.name),
                name = r.name,
                url = r.url,
                imageUrl = r.avatarUrl,
                subtitle = listOfNotNull(
                    "Channel",
                    r.subscriberCount.takeIf { it > 0 }?.let { "${formatCount(it)} subscribers" }
                ).joinToString(" · "),
                why = s.why
            )
        }
    }
    SourceKind.PLAYLIST -> yt.searchPlaylists(s.searchQuery).firstOrNull()?.let { r ->
        WhitelistParser.parse(r.url).sources.firstOrNull()?.let { e ->
            DiscoveryCard(
                entry = e.copy(label = r.name),
                name = r.name,
                url = r.url,
                imageUrl = r.thumbnailUrl,
                // What it is, how much of it, then whose: the uploader reads
                // as a name rather than as an attribution clause.
                subtitle = listOfNotNull(
                    "Playlist",
                    r.videoCount.takeIf { it > 0 }?.let { "$it videos" },
                    r.uploaderName?.trim()?.takeIf { it.isNotEmpty() }
                ).joinToString(" · "),
                why = s.why
            )
        }
    }
}

// --- Community directory ------------------------------------------------------

private val DIRECTORY_AGE_ORDER = listOf("2-4", "5-7", "8-10", "11+")

/**
 * Browse the reviewed community directory (pickwick.tv/directory) and add
 * entries to the whitelist. Directory review means "other parents vouch for
 * this", not "right for this family" — added entries land tagged NEW and go
 * through the same per-kid switches and screening as any hand-added channel.
 */
@Composable
internal fun DirectorySection(
    entries: List<WhitelistEntry>,
    onAdd: (WhitelistEntry) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var all by remember { mutableStateOf<List<io.yosemitekids.app.data.DirectoryEntry>>(emptyList()) }
    var selectedAges by remember { mutableStateOf(setOf<String>()) }
    var selectedTopics by remember { mutableStateOf(setOf<String>()) }
    var selectedLangs by remember { mutableStateOf(setOf<String>()) }

    fun load() = scope.launch {
        busy = true
        message = "Loading the directory…"
        runCatching { io.yosemitekids.app.data.Directory.fetch() }
            .onSuccess { list ->
                all = list
                // Pre-select the device language once there's more than one to
                // choose from — one tap on the chip widens back to everything.
                val device = java.util.Locale.getDefault().language
                selectedLangs = if (
                    list.mapTo(mutableSetOf()) { it.langCode }.size > 1 &&
                    list.any { it.langCode == device }
                ) setOf(device) else emptySet()
                message = if (list.isEmpty()) "The directory is empty right now" else null
            }
            .onFailure { message = "Couldn't load the directory: ${it.message?.take(120)}" }
        busy = false
    }

    Text(
        "Channels other Yosemite Kids families vouch for — every entry is reviewed " +
            "before it appears. Adding one works like adding by hand: it lands " +
            "tagged NEW below, with your per-kid switches and screening applying " +
            "as usual.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (all.isEmpty()) {
        CompactButton(
            enabled = !busy,
            onClick = { load() }
        ) { Text(if (busy) "…" else "Browse the directory") }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val ages = all.flatMap { it.ages }.distinct()
        .sortedBy { DIRECTORY_AGE_ORDER.indexOf(it).let { i -> if (i == -1) 99 else i } }
    val topics = all.flatMap { it.topics }.distinct().sorted()
    // Language filter only exists once a second language is published — a lone
    // "English" chip would just be noise.
    val langs = all.map { it.langCode to it.langName }.distinct().sortedBy { it.second }
    val multiLang = langs.size > 1

    if (multiLang) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            langs.forEach { (code, name) ->
                FilterChip(
                    selected = code in selectedLangs,
                    onClick = {
                        selectedLangs = if (code in selectedLangs) selectedLangs - code else selectedLangs + code
                    },
                    label = { Text(name.ifBlank { code }) },
                    modifier = Modifier.padding(end = 6.dp).tvFocusHighlight()
                )
            }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        ages.forEach { a ->
            FilterChip(
                selected = a in selectedAges,
                onClick = {
                    selectedAges = if (a in selectedAges) selectedAges - a else selectedAges + a
                },
                label = { Text("ages " + a.replace("-", "–")) },
                modifier = Modifier.padding(end = 6.dp).tvFocusHighlight()
            )
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        topics.forEach { t ->
            FilterChip(
                selected = t in selectedTopics,
                onClick = {
                    selectedTopics = if (t in selectedTopics) selectedTopics - t else selectedTopics + t
                },
                label = { Text(t) },
                modifier = Modifier.padding(end = 6.dp).tvFocusHighlight()
            )
        }
    }

    val shown = all.filter { e ->
        (selectedLangs.isEmpty() || e.langCode in selectedLangs) &&
            (selectedAges.isEmpty() || e.ages.any { it in selectedAges }) &&
            (selectedTopics.isEmpty() || e.topics.any { it in selectedTopics })
    }
    // What "Add all" would actually add: the filtered view, minus what's
    // already in the list — so the button doubles as a "how many are new" count.
    val addable = remember(shown, entries) {
        shown.mapNotNull { d ->
            WhitelistParser.parse(d.url).sources.firstOrNull()?.copy(label = d.name)
        }.distinctBy { it.id }
            .filter { p -> entries.none { it.id == p.id || it.url == p.url } }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (shown.size == all.size) "${all.size} channels & playlists"
            else "${shown.size} of ${all.size} shown",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        CompactButton(
            enabled = addable.isNotEmpty(),
            onClick = {
                addable.forEach(onAdd)
                message = "Added ${addable.size} — tagged NEW in the channel list below"
            }
        ) { Text(if (addable.isEmpty()) "All added ✓" else "Add all (${addable.size})") }
        CompactButton(
            enabled = !busy,
            onClick = { load() }
        ) { Text("Refresh") }
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    shown.forEach { d ->
        // Same accept-path as AI discovery: the canonical WhitelistEntry comes
        // from the parser, so a directory add is byte-identical to a hand add.
        val parsed = remember(d.url) { WhitelistParser.parse(d.url).sources.firstOrNull() }
        val alreadyAdded = parsed != null &&
            entries.any { it.id == parsed.id || it.url == parsed.url }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (d.kind == SourceKind.PLAYLIST) listOf("Playlist") else emptyList())
                        .plus(if (multiLang && d.langName.isNotBlank()) listOf(d.langName) else emptyList())
                        .plus(d.ages.map { "ages " + it.replace("-", "–") })
                        .plus(d.topics)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (d.note.isNotBlank()) {
                    Text(
                        d.note,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        // Prose, not a link: teal is for what a parent presses.
                        color = SettingsTextSecondary
                    )
                }
            }
            CompactButton( onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(d.url)
                        )
                    )
                }
            }) { Text("YouTube") }
            CompactButton(
                enabled = parsed != null && !alreadyAdded,
                onClick = { parsed?.let { onAdd(it.copy(label = d.name)) } }
            ) { Text(if (alreadyAdded) "Added ✓" else "Add") }
        }
    }
}
