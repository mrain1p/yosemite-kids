package io.yosemitekids.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.yosemitekids.app.data.LanClient
import io.yosemitekids.app.data.PairingStore
import io.yosemitekids.app.data.ScreeningStore
import io.yosemitekids.app.data.SourceCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- AI screening -------------------------------------------------------------

private data class AiPreset(val name: String, val baseUrl: String, val modelHint: String)

private val AI_PRESETS = listOf(
    AiPreset("OpenRouter", "https://openrouter.ai/api/v1", "anthropic/claude-haiku-4.5"),
    AiPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
    AiPreset("Anthropic", "https://api.anthropic.com/v1", "claude-haiku-4-5"),
    AiPreset("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
    AiPreset("Local", "http://192.168.0.10:11434/v1", "llama3.2")
)

/**
 * The name the Screening page's "AI connection" row leads with: the preset
 * whose base URL this is, else the host of a hand-typed URL, else null for
 * blank. Kept beside the presets so a new preset names itself on the row too.
 */
internal fun aiProviderName(baseUrl: String): String? {
    if (baseUrl.isBlank()) return null
    AI_PRESETS.firstOrNull { it.baseUrl == baseUrl }?.let { return it.name }
    return runCatching { java.net.URI(baseUrl).host }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: "Custom provider"
}

/**
 * The reason the screener stores when the model answered but dropped an id —
 * the one "held back" that says nothing about the video, and the only one the
 * card draws in amber with an apology instead of quoting it.
 *
 * Spelled in `AiScreener.kt` too (the `Verdict.REVIEW` fallback). If that
 * string moves, this card quietly loses its amber and nothing fails, so fold
 * the two into one const the next time that file is open.
 */
private const val NO_VERDICT_REASON = "model returned no verdict"

private val DEFAULT_AI_RULES = """
    No scary, violent, or disturbing content.
    No adult themes, romance, or innuendo.
    No unboxing, toy hauls, or heavy consumerism.
    No clickbait or low-effort "brainrot" content.
""".trimIndent()

@OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
internal fun AiConnectionSection(
    ai: io.yosemitekids.app.data.AiConfig,
    onChanged: (io.yosemitekids.app.data.AiConfig) -> Unit
) {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelsMessage by remember { mutableStateOf<String?>(null) }

    // Fetch the provider's model list as soon as it's reachable (key entered, or
    // a keyless local server). Keyed on url+key so edits re-fetch; the delay
    // debounces typing. Deliberately NOT gated on ai.enabled: the connection is
    // its own thing, and a parent setting one up for channel discovery has no
    // reason to turn content screening on first.
    LaunchedEffect(ai.baseUrl, ai.apiKey) {
        models = emptyList()
        modelsMessage = null
        val keyless = ai.baseUrl.startsWith("http://") // local LAN server
        if (ai.baseUrl.isBlank() || (ai.apiKey.isBlank() && !keyless)) return@LaunchedEffect
        kotlinx.coroutines.delay(800)
        modelsMessage = "Loading models…"
        runCatching { io.yosemitekids.app.data.AiScreener.listModels(ai) }
            .onSuccess {
                models = it
                modelsMessage = if (it.isEmpty()) "Provider returned no models" else null
            }
            .onFailure {
                // The row shows a trimmed line; the log keeps the provider's
                // whole answer, which is what actually says why.
                android.util.Log.w("YosemiteKids", "model list from ${ai.baseUrl} failed", it)
                modelsMessage = "Couldn't load models: ${it.message?.take(80)}"
            }
    }

    Text(
        "Where Yosemite Kids talks to an AI, and which model. Set this up once and the " +
            "two features that use it — screening new videos, and finding channels — " +
            "are each switched on separately below. You can bring your own provider; " +
            "a model running on your own network works too.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AI_PRESETS.forEach { preset ->
            FilterChip(
                modifier = Modifier.tvFocusHighlight(),
                selected = ai.baseUrl == preset.baseUrl,
                onClick = { onChanged(ai.copy(baseUrl = preset.baseUrl, model = preset.modelHint)) },
                label = { Text(preset.name) }
            )
        }
    }
    val endpointSafe = ai.baseUrl.isBlank() ||
        io.yosemitekids.app.data.AiScreener.isEndpointSafe(ai.baseUrl)
    OutlinedTextField(
        value = ai.baseUrl,
        onValueChange = { onChanged(ai.copy(baseUrl = it.trim())) },
        label = { Text("API base URL (OpenAI-compatible)") },
        singleLine = true,
        isError = !endpointSafe,
        modifier = Modifier.fillMaxWidth()
    )
    // Screening refuses to run against this rather than leak the key and the
    // kids' details in the clear, so say why here instead of failing silently.
    if (!endpointSafe) {
        Text(
            "Use https:// — a plain http:// address sends your API key and the " +
                "video titles unencrypted. http:// is allowed only for a model " +
                "running on your own network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = ai.apiKey,
        onValueChange = { onChanged(ai.copy(apiKey = it.trim())) },
        label = { Text("API key (leave empty for a local server)") },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    if (models.isEmpty()) {
        // No list (no key yet, or fetch failed): plain text entry still works.
        OutlinedTextField(
            value = ai.model,
            onValueChange = { onChanged(ai.copy(model = it.trim())) },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        // Type-to-filter dropdown: OpenRouter alone lists hundreds of models.
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = ai.model,
                onValueChange = {
                    onChanged(ai.copy(model = it.trim()))
                    expanded = true
                },
                label = { Text("Model — type to filter, tap to pick") },
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable)
            )
            val filtered = models.filter { it.contains(ai.model, ignoreCase = true) }.take(25)
            ExposedDropdownMenu(
                expanded = expanded && filtered.isNotEmpty(),
                onDismissRequest = { expanded = false }
            ) {
                filtered.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m) },
                        onClick = {
                            onChanged(ai.copy(model = m))
                            expanded = false
                        }
                    )
                }
            }
        }
    }
    modelsMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Sends one short test question, so a wrong key or model says so here " +
                "rather than failing quietly later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        CompactButton(
            enabled = !testing && ai.model.isNotBlank(),
            onClick = {
                scope.launch {
                    testing = true
                    testMessage = "Testing…"
                    runCatching {
                        io.yosemitekids.app.data.AiScreener.screen(
                            ai,
                            listOf(
                                io.yosemitekids.app.data.Video(
                                    url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                                    title = "Fun science experiment for kids",
                                    channelName = "Test Channel",
                                    thumbnailUrl = null,
                                    durationSeconds = 300
                                )
                            )
                        )
                    }
                        .onSuccess { testMessage = "Connected ✓ — ${ai.model} answered" }
                        .onFailure { testMessage = "Failed: ${it.message?.take(160)}" }
                    testing = false
                }
            }
        ) { Text(if (testing) "…" else "Test connection") }
    }
    testMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Screening new videos against house rules — one of the two features that use
 * the connection above, and independent of the other.
 *
 * It used to own the connection fields outright, so a parent who only wanted
 * AI channel discovery had to switch content screening on to reach the key
 * field at all. Two unrelated features, one switch.
 */
@Composable
internal fun AiScreeningSection(
    ai: io.yosemitekids.app.data.AiConfig,
    profiles: List<io.yosemitekids.app.data.Profile>,
    onChanged: (io.yosemitekids.app.data.AiConfig) -> Unit
) {
    // The design's switch row: title, one summary line, switch — the paragraph
    // that used to sit above it said the same thing four times longer.
    //
    // Not [ToggleRow], for two reasons: this row sits in a card that supplies
    // its own 12dp inset (so ToggleRow's would double it), and the switch has
    // to be disable-able. Fold it back in the day ToggleRow takes an `enabled`.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(vertical = 10.dp)
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text("Screen new videos with AI", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "Titles and channel names only — never watch history",
                style = MaterialTheme.typography.bodySmall
                    .copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            modifier = Modifier.tvFocusHighlight(),
            checked = ai.enabled,
            // Enabled only once there is a connection to screen with. Without
            // this the switch turns on and silently does nothing, which reads
            // as a broken feature rather than as a missing step.
            enabled = ai.model.isNotBlank(),
            onCheckedChange = { on ->
                onChanged(ai.copy(enabled = on, rules = ai.rules.ifBlank { DEFAULT_AI_RULES }))
            },
            // The design's switch, spelled the same way [ToggleRow] spells it.
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                uncheckedTrackColor = SettingsStrongBorder,
                uncheckedThumbColor = SettingsTextTertiary,
                uncheckedBorderColor = androidx.compose.ui.graphics.Color.Transparent
            )
        )
    }
    if (ai.model.isBlank()) {
        Text(
            "Set up the AI connection above first — endpoint, key and model.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        return
    }
    if (!ai.enabled) return

    // A plain textarea: no floating label (the rules are legible as themselves),
    // the page tone inside the card, and six lines deep so the four house rules
    // are all on screen at once.
    OutlinedTextField(
        value = ai.rules,
        onValueChange = { onChanged(ai.copy(rules = it)) },
        minLines = 6,
        shape = RoundedCornerShape(10.dp),
        textStyle = LocalTextStyle.current.copy(fontSize = 13.5.sp, lineHeight = 22.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.background,
            unfocusedContainerColor = MaterialTheme.colorScheme.background,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline
        ),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(9.dp))
    Text(
        "Rough notes are fine — the AI understands shorthand. One rule per line.",
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 19.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (profiles.isEmpty()) {
        StepperRow(
            label = "Child age",
            value = ai.childAge, step = 1, min = 2, max = 16,
            format = { "$it" },
            onChanged = { onChanged(ai.copy(childAge = it)) }
        )
    }
    // The card's footer: what one screening pass actually covers, in the
    // faintest tone — it is the fine print under the rules, not a rule.
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier.padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (profiles.isNotEmpty()) {
            Text(
                "Checked once for the whole family — one AI call, a verdict per kid, " +
                    "against the ages set under Kids. " +
                    profiles.joinToString(", ") { p ->
                        p.age?.let { "${p.name} is $it" } ?: "${p.name} has no age set"
                    } + ".",
                fontSize = 12.sp, lineHeight = 19.sp,
                color = SettingsPlaceholder
            )
        }
        Text(
            "Changing rules re-screens the whole catalog on every device.",
            fontSize = 12.sp, lineHeight = 19.sp,
            color = SettingsPlaceholder
        )
    }
}

// --- AI review queue ---------------------------------------------------------

/**
 * Which half of the review flow a page wants.
 *
 * The design gives the queue and the blocked pile their own pages, but they
 * are one flow — same cards, same rulings, same per-kid dialog — so they stay
 * one composable rather than becoming the same code twice.
 */
internal enum class ReviewHalf { QUEUE, BLOCKED, BOTH }

/**
 * THE review queue — the one place a held-back video waits for the parent.
 * Served from this device's own verdict store, merged with what each paired
 * device reports as held (live over the LAN, or its last cached stats snapshot
 * when it's off) — the models are not perfectly deterministic, so a kid device
 * can hold a video this phone's own screening let through. Decisions are
 * committed and pushed as they're tapped, not held for Save & close.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AiReviewSection(
    ai: io.yosemitekids.app.data.AiConfig,
    profiles: List<io.yosemitekids.app.data.Profile>,
    pairingStore: PairingStore,
    /** Already ruled on (parent-blocked or allowed) — dropped from the queue. */
    resolved: Set<String>,
    /** Second arg: null = everyone (tap); a kid set = long-press per-kid ruling. */
    onAllow: (String, Set<String>?) -> Unit,
    onBlock: (String, Set<String>?) -> Unit,
    /** Which half to render — see [ReviewHalf]. */
    show: ReviewHalf = ReviewHalf.BOTH
) {
    /** (videoId, allow?) awaiting the long-press "which kids?" answer. */
    var perKid by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    perKid?.let { (videoId, isAllow) ->
        WhoForDialog(
            title = if (isAllow) "Allow for which kids?" else "Block for which kids?",
            profiles = profiles,
            initialIds = emptySet(),
            confirmLabel = if (isAllow) "Allow" else "Block",
            onDismiss = { perKid = null },
            onConfirm = { forKids ->
                // "All kids" collapses to the family-wide tap ruling.
                val target = forKids.ifEmpty { null }
                if (isAllow) onAllow(videoId, target) else onBlock(videoId, target)
                perKid = null
            }
        )
    }
    // Each half filters by channel with its own tab row, so each keeps its own
    // pick: ruling on the queue must not silently re-filter the blocked page.
    /** Channel the queue is narrowed to, null = All. */
    var queueChannel by remember { mutableStateOf<String?>(null) }
    /** The same, for the blocked page. */
    var blockedChannel by remember { mutableStateOf<String?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { io.yosemitekids.app.data.ScreeningStore(context.applicationContext) }
    var all by remember { mutableStateOf<List<Pair<String, ScreeningStore.Entry>>>(emptyList()) }
    /** device token → videos that device says it is holding back. */
    var remoteByDevice by remember {
        mutableStateOf<Map<String, List<io.yosemitekids.app.data.Stats.AiFlagged>>>(emptyMap())
    }
    /** Cached-feed videos with no verdict yet — the queue is still growing by this much. */
    var stillScreening by remember { mutableIntStateOf(0) }
    // The poll loop outlives any single value of `resolved`; read the latest each
    // pass rather than restarting the (disk-reading) loop on every Allow/Block tap.
    val latestResolved by rememberUpdatedState(resolved)

    // Polled, not read once: screening runs in background batches, and a snapshot
    // taken when the screen opened showed the parent one batch and hid the rest
    // until they left and came back. Off the main thread — this reads every
    // source's cached video list plus the verdict file.
    LaunchedEffect(ai.rulesVersion) {
        while (true) {
            val snapshot = withContext(kotlinx.coroutines.Dispatchers.IO) {
                val flaggedNow = store.flagged(ai.rulesVersion)
                val videoCache = io.yosemitekids.app.data.VideoCache(context.applicationContext)
                val pending = SourceCache(context.applicationContext).load()
                    .flatMap { videoCache.load(it.id) }
                    .mapNotNull { it.videoId }
                    .distinct()
                    // Parent-allowed videos are never sent for screening, so they'd
                    // otherwise sit in this count forever.
                    .count {
                        it !in latestResolved && store.get(it)?.rulesVersion != ai.rulesVersion
                    }
                flaggedNow to pending
            }
            all = snapshot.first
            stillScreening = snapshot.second
            delay(4_000)
        }
    }

    // Kid devices can hold videos this phone let through, so their queues fold in
    // here. First pass paints from the cached snapshots (instant, works with the
    // TV off); later passes refresh live and update the cache the per-device
    // stats page reads. Slower cadence than the local poll — this hits the LAN.
    LaunchedEffect(Unit) {
        val statsCache = io.yosemitekids.app.data.StatsCache(context.applicationContext)
        var live = false
        while (true) {
            remoteByDevice = withContext(kotlinx.coroutines.Dispatchers.IO) {
                pairingStore.paired().associate { device ->
                    val json = (if (live) LanClient.stats(device)
                        ?.also { statsCache.save(device.key, it) } else null)
                        ?: statsCache.load(device.key)?.second
                    device.key to
                        json?.let { io.yosemitekids.app.data.Stats.parse(it)?.aiFlagged }.orEmpty()
                }
            }
            if (live) delay(15_000)
            live = true
        }
    }

    val localFlagged = all.filterNot { (id, _) -> id in resolved }
    // A remote hold on a video the local store also flags is the same card; a
    // remote hold on one it doesn't is still the parent's to rule on.
    val localIds = localFlagged.map { it.first }.toSet()
    val remoteOnly = remoteByDevice.values.flatten()
        .distinctBy { it.videoId }
        .filter { it.videoId !in localIds && it.videoId !in resolved }
        .map { f ->
            f.videoId to ScreeningStore.Entry(
                verdict = if (f.verdict == io.yosemitekids.app.data.AiScreener.Verdict.REVIEW.name)
                    io.yosemitekids.app.data.AiScreener.Verdict.REVIEW
                else io.yosemitekids.app.data.AiScreener.Verdict.BLOCK,
                reason = f.reason,
                title = f.title,
                channel = f.channel,
                thumb = f.thumbnailUrl,
                rulesVersion = ai.rulesVersion,
                at = f.at
            )
        }
    val flagged = (localFlagged + remoteOnly).sortedByDescending { it.second.at }

    // Two piles for the parent: "AI unsure — waiting on you" stays up top, and
    // hard blocks (the title pass or the pre-play deep check, this device or a
    // paired one) sit in their own collapsed section below — those are already
    // ruled and hidden, listed only so a wrong call can be overruled.
    val review = flagged.filter { it.second.verdict == io.yosemitekids.app.data.AiScreener.Verdict.REVIEW }
    val blockedList = flagged.filter { it.second.verdict == io.yosemitekids.app.data.AiScreener.Verdict.BLOCK }

    /**
     * Who a card is held for. With per-kid verdicts that is the whole point —
     * "held for Dave · fine for Katy". Without them, the page's own word for
     * it: on the queue every card was held, on the blocked pile every card was
     * blocked, so the label only has to say which page you are on.
     */
    fun verdictLabel(e: ScreeningStore.Entry): String =
        if (profiles.isNotEmpty() && e.perProfile.isNotEmpty()) {
            val held = profiles.filter {
                e.perProfile[it.id] != io.yosemitekids.app.data.AiScreener.Verdict.ALLOW
            }
            val fine = profiles.filter {
                e.perProfile[it.id] == io.yosemitekids.app.data.AiScreener.Verdict.ALLOW
            }
            listOfNotNull(
                held.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ") { it.name }?.let { "held for $it" },
                fine.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ") { it.name }?.let { "fine for $it" }
            ).joinToString(" · ")
        } else if (e.verdict == io.yosemitekids.app.data.AiScreener.Verdict.REVIEW) {
            "held back"
        } else "AI blocked"

    /** Opens the video where the parent can actually watch it before ruling. */
    fun openInYouTube(videoId: String) {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://www.youtube.com/watch?v=$videoId")
                )
            )
        }
    }

    /**
     * The quiet row of words each half filters by channel with — the active
     * one in teal over a 2dp underline of its own width, the rest grey. The
     * same shape as the channel list's tabs, which are private to that file.
     */
    @Composable
    fun filterTab(label: String, selected: Boolean, onPick: () -> Unit) {
        val accent = MaterialTheme.colorScheme.primary
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            fontWeight = FontWeight.Medium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .tvFocusHighlight(cornerRadius = 8.dp)
                .clickable(onClick = onPick)
                // Drawn rather than laid out: the underline is exactly the
                // label's width, which a Box child in a Row cannot be without
                // measuring the text twice.
                .drawBehind {
                    if (selected) drawRect(
                        color = accent,
                        topLeft = Offset(0f, size.height - 2.dp.toPx()),
                        size = Size(size.width, 2.dp.toPx())
                    )
                }
                .padding(top = 2.dp, bottom = 7.dp)
        )
    }

    /**
     * One held-back video: thumbnail and title, the AI's reason quoted in its
     * own block, then YouTube / Block / Allow.
     *
     * [bulk] adds the "Allow all N from <channel>" link under the buttons —
     * only the queue has a whole channel left to rule on.
     */
    @Composable
    fun flaggedCard(videoId: String, e: ScreeningStore.Entry, bulk: Boolean = false) {
        OutlinedCard(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    val thumbShape = RoundedCornerShape(6.dp)
                    AsyncImage(
                        model = e.thumb,
                        contentDescription = e.title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        // The slot is drawn whether or not the image arrives: a
                        // card whose thumbnail never loaded still reads as a card.
                        modifier = Modifier.size(width = 72.dp, height = 44.dp)
                            .clip(thumbShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outline, thumbShape)
                    )
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.title,
                            style = MaterialTheme.typography.bodyMedium
                                .copy(fontSize = 14.sp, lineHeight = 20.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            listOfNotNull(
                                e.channel.takeIf { it.isNotBlank() },
                                verdictLabel(e)
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall
                                .copy(fontSize = 12.sp, lineHeight = 17.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // The model dropping an id is not a judgement on the video, so
                // it gets the warning tone and an apology rather than a quote
                // the parent would read as the AI's opinion.
                val noVerdict = e.reason == NO_VERDICT_REASON
                if (e.reason.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    val tone =
                        if (noVerdict) WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // So the bar is exactly as tall as the quote.
                            .height(IntrinsicSize.Min)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        Box(Modifier.width(2.dp).fillMaxHeight().background(tone))
                        Text(
                            if (noVerdict) "The model returned no verdict." else e.reason,
                            style = MaterialTheme.typography.bodySmall
                                .copy(fontSize = 12.5.sp, lineHeight = 19.sp),
                            color = tone,
                            modifier = Modifier.padding(vertical = 9.dp, horizontal = 11.dp)
                        )
                    }
                    if (noVerdict) {
                        Spacer(Modifier.height(7.dp))
                        Text(
                            "Nothing is wrong with the video — the model failed to answer. " +
                                "It is screened again when you change the rules.",
                            fontSize = 12.sp, lineHeight = 18.sp,
                            color = SettingsPlaceholder
                        )
                    }
                }
                val buttonShape = RoundedCornerShape(7.dp)
                // Tap rules for everyone; with 2+ kids a long-press picks who —
                // TextButton owns its click, so these are hand-rolled buttons.
                @Composable
                fun rulingButton(label: String, isAllow: Boolean, modifier: Modifier) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = modifier
                            .height(32.dp)
                            .clip(buttonShape)
                            .then(
                                if (isAllow) Modifier.background(MaterialTheme.colorScheme.primary)
                                else Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                    buttonShape
                                )
                            )
                            .tvFocusHighlight(cornerRadius = 7.dp)
                            .dpadLongPress {
                                if (profiles.size >= 2) perKid = videoId to isAllow
                            }
                            .combinedClickable(
                                onClick = {
                                    if (isAllow) onAllow(videoId, null)
                                    else onBlock(videoId, null)
                                },
                                onLongClick = {
                                    if (profiles.size >= 2) perKid = videoId to isAllow
                                }
                            )
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                            fontWeight = FontWeight.Medium,
                            color = if (isAllow) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 9.dp)
                ) {
                    // Watch it yourself before ruling on the AI's call. Hugs its
                    // label; the two rulings split what is left.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(32.dp)
                            .clip(buttonShape)
                            .background(MaterialTheme.colorScheme.background)
                            .border(1.dp, SettingsStrongBorder, buttonShape)
                            .tvFocusHighlight(cornerRadius = 7.dp)
                            .clickable { openInYouTube(videoId) }
                            .padding(horizontal = 11.dp)
                    ) {
                        Text(
                            "YouTube",
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
                            fontWeight = FontWeight.Medium,
                            color = SettingsTextSecondary
                        )
                    }
                    rulingButton("Block", isAllow = false, Modifier.weight(1f))
                    rulingButton("Allow", isAllow = true, Modifier.weight(1f))
                }
                // One channel's whole queue at once: a parent who has allowed
                // two of a channel's videos is really ruling on the channel.
                val sameChannel = if (bulk && e.channel.isNotBlank()) {
                    review.filter { it.second.channel == e.channel }
                } else emptyList()
                if (sameChannel.size > 1) {
                    Text(
                        "Allow all ${sameChannel.size} from ${e.channel}",
                        style = MaterialTheme.typography.labelMedium
                            .copy(fontSize = 12.5.sp, lineHeight = 18.sp),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .tvFocusHighlight(cornerRadius = 8.dp)
                            .clickable {
                                sameChannel.forEach { (id, _) -> onAllow(id, null) }
                                // Nothing is left to filter to.
                                queueChannel = null
                            }
                            .heightIn(min = 44.dp)
                            .wrapContentHeight()
                    )
                }
            }
        }
    }

    @Composable
    fun perKidHint() {
        if (profiles.size >= 2) {
            Text(
                "Tap Allow/Block for all kids — hold to choose which kids.",
                fontSize = 12.sp, lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (show != ReviewHalf.BLOCKED) {
        // The page's own header, in the empty state too: "0 held back" is an
        // answer, a blank page is not.
        Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Text(
                "${review.size} held back",
                fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Hidden from the kid until you decide. Each Allow or Block is saved " +
                    "as you tap it.",
                fontSize = 12.5.sp, lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Its own line, in amber: "still being screened" is a queue that is
            // not finished growing, not part of the sentence above it.
            if (stillScreening > 0) {
                Spacer(Modifier.height(9.dp))
                Text(
                    "$stillScreening more still being screened — they'll appear here " +
                        "as the AI finishes.",
                    fontSize = 12.5.sp, lineHeight = 19.sp,
                    color = WarningAmber
                )
            }
        }
        // One tab per channel with something in the queue, in the order the
        // queue shows them. A queue is usually a handful of channels behaving
        // the same way, so ruling on one channel at a time is the shortest way
        // through it.
        val byChannel = review.map { it.second.channel }
            .filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            filterTab("All ${review.size}", queueChannel == null) { queueChannel = null }
            byChannel.forEach { (name, count) ->
                filterTab("$name $count", queueChannel == name) { queueChannel = name }
            }
        }
        SettingsDivider()
        val shown = review.filter { queueChannel == null || it.second.channel == queueChannel }
        if (shown.isEmpty()) {
            Text(
                if (review.isEmpty()) "Nothing waiting — the kid sees everything the AI approved."
                // The filter outlived its channel: say so rather than reading
                // as an empty queue.
                else "Nothing left from $queueChannel.",
                fontSize = 13.sp, lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 22.dp, horizontal = 18.dp)
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                // No pagination: a parent asked to rule on a queue wants the whole
                // queue, not a batch that refills after each round trip. The cap only
                // exists so a runaway store can't build thousands of cards into one
                // scrolling Column.
                shown.take(300).forEach { (videoId, e) -> flaggedCard(videoId, e, bulk = true) }
                perKidHint()
                if (shown.size > 300) {
                    Text(
                        "…and ${shown.size - 300} more — they appear as you rule on these.",
                        fontSize = 12.sp, lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (show != ReviewHalf.QUEUE) {
        // No SectionTitle on its own page — the app bar already says it.
        if (show == ReviewHalf.BOTH) SectionTitle("Blocked videos")
    }
    if (show != ReviewHalf.QUEUE) if (blockedList.isEmpty()) {
        Text(
            "Nothing blocked right now. Videos the AI blocks — at screening, or in " +
                "the final check just before one plays — collect here so you can " +
                "overrule a wrong call.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        // Collapsed by default: these are already ruled and hidden — routine
        // visits are about the queue above, not re-reading old blocks.
        var blockedExpanded by remember { mutableStateOf(false) }
        Text(
            if (blockedExpanded) "▾ ${blockedList.size} video(s) blocked and hidden — hide list"
            else "▸ ${blockedList.size} video(s) blocked and hidden — show",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier
                .tvFocusHighlight()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .combinedClickable(onClick = { blockedExpanded = !blockedExpanded })
                .padding(horizontal = 4.dp, vertical = 8.dp)
        )
        if (blockedExpanded) {
            Text(
                "Allow overrules the AI — the video reappears on every device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            blockedList.take(300).forEach { (videoId, e) -> flaggedCard(videoId, e) }
            perKidHint()
            if (blockedList.size > 300) {
                Text(
                    "…and ${blockedList.size - 300} more — they appear as you rule on these.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
