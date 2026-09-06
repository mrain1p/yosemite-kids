package io.yosemitekids.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yosemitekids.app.data.AiScreener
import io.yosemitekids.app.data.ConfigMerge
import io.yosemitekids.app.data.ConfigStore
import io.yosemitekids.app.data.Digest
import io.yosemitekids.app.data.DigestStore
import io.yosemitekids.app.data.LanClient
import io.yosemitekids.app.data.PairedDevice
import io.yosemitekids.app.data.PairingStore
import io.yosemitekids.app.data.Stats
import io.yosemitekids.app.data.StatsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "How did this week go" (raw-digest.png): the week's heading, then one
 * Watching card per paired device (≈ per kid) — the big number, the days and
 * daily average, last week for comparison, the most-watched channel — then a
 * Screening card with what the AI held this week, then the settings changes
 * made this week.
 *
 * Assembled entirely from data the phone already holds: the cached stats
 * payload (per-day minutes, lifetime channel totals, AI holds) plus
 * DigestStore's daily channel baselines, and the config's own change log.
 * No cloud and nothing is sent anywhere — the design's "sent to every parent
 * device each Sunday" describes a delivery this app does not make, so the
 * explainer says what is true instead. A fresh fetch is attempted once per
 * device on open so the numbers include today, but the page works offline.
 */
@Composable
fun WeeklyDigestScreen(
    pairingStore: PairingStore,
    configStore: ConfigStore,
    /** The real review queue, where a held video can be ruled on. */
    onOpenReview: () -> Unit,
    onOpenBlocked: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext

    data class DeviceDigest(
        val device: PairedDevice,
        val kidName: String?,
        val weekly: Digest.Weekly,
        val fresh: Boolean
    )

    val today = remember { SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) }
    var digests by remember { mutableStateOf<List<DeviceDigest>?>(null) }
    var changes by remember { mutableStateOf<List<ConfigMerge.Change>>(emptyList()) }
    var aiEnabled by remember { mutableStateOf(false) }
    // The "Most watched" page for one device, pushed over the digest. Held
    // here, above the page split, so the digest is still loaded on the way back.
    var channelsFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Disk + JSON + one LAN round trip per device — all off-main; cached
        // snapshots keep the screen honest when a TV is asleep.
        digests = withContext(Dispatchers.IO) {
            val config = configStore.load()
            aiEnabled = config.ai.enabled
            val weekStart = Digest.weekStartMs(today)
            // shownAt, not at: the ordering stamp is forced monotonic and can
            // name a time that never happened (see SyncActivityScreen).
            changes = config.sync.log
                .filter { (it.shownAt.takeIf { s -> s > 0 } ?: it.at) >= weekStart }
                .reversed()
            val statsCache = StatsCache(appContext)
            val digestStore = DigestStore(File(appContext.filesDir, "digest"))
            val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
            pairingStore.paired().mapNotNull { device ->
                val live = LanClient.stats(device)?.also { statsCache.save(device.key, it) }
                val cached = if (live == null) statsCache.load(device.key) else null
                val json = live ?: cached?.second
                val payload = json?.let { Stats.parse(it) } ?: return@mapNotNull null
                DeviceDigest(
                    device = device,
                    kidName = payload.profileName,
                    // Baselines are keyed per kid — a shared TV's payload is
                    // whichever profile is active, and its file must match.
                    weekly = Digest.assemble(
                        payload,
                        digestStore.load(DigestStore.key(device.key, payload.profileName)),
                        today,
                        // A stale snapshot's "today" is the day it was fetched —
                        // plotting it under the phone's today would move, say,
                        // Tuesday's minutes onto Friday's bar.
                        payloadDayKey = cached?.first?.let { fmt.format(Date(it)) } ?: today
                    ),
                    fresh = live != null
                )
            }
        }
    }

    val opened = channelsFor?.let { key -> digests?.firstOrNull { it.device.key == key } }
    if (opened != null) {
        BackHandler { channelsFor = null }
        MostWatchedPage(
            who = listOfNotNull(opened.kidName, opened.device.name).joinToString(" · "),
            weekly = opened.weekly,
            onBack = { channelsFor = null }
        )
        return
    }

    BackHandler { onBack() }
    SubPage(title = "Weekly digest", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        Text(
            Digest.weekOfLabel(today),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium)
        )
        Text(
            "What each device reports, added up on this phone. Nothing leaves your network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionTitle("Watching")
        val list = digests
        when {
            list == null -> Text(
                "Adding it up…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            list.isEmpty() -> SettingsCard {
                Text("Nothing watched yet.")
                Text(
                    "The digest fills in once a paired device has reported some stats.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> list.forEachIndexed { i, d ->
                // Identity-keyed so per-card state (the AI summary) can't
                // attach to the wrong device if the list ever changes.
                key(d.device.key) {
                    if (i > 0) Spacer(Modifier.height(12.dp))
                    WatchingCard(
                        device = d.device,
                        kidName = d.kidName,
                        weekly = d.weekly,
                        fresh = d.fresh,
                        aiEnabled = aiEnabled,
                        configStore = configStore,
                        onChannels = { channelsFor = d.device.key }
                    )
                }
            }
        }

        // The payload only lists holds the parent hasn't ruled on yet — items
        // resolved mid-week drop out, so these are the open queue from this
        // week, not the week's full screening record. The same video held on
        // two devices is one video, as it is on the review page.
        val holds = list.orEmpty().flatMap { it.weekly.blocked }.distinctBy { it.videoId }
        val waiting = holds.count { it.verdict == AiScreener.Verdict.REVIEW.name }
        val blocked = holds.size - waiting
        if (aiEnabled || holds.isNotEmpty()) {
            SectionTitle("Screening")
            SettingsCard(padded = false) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    ValueRow(
                        "Waiting for your OK",
                        value = if (waiting > 0) waiting.toString() else "None",
                        valueColor = if (waiting > 0) WarningAmber else null,
                        onClick = onOpenReview
                    )
                    SettingsDivider()
                    ValueRow(
                        "Blocked this week",
                        value = if (blocked > 0) "$blocked blocked" else "None",
                        onClick = onOpenBlocked
                    )
                }
            }
        }

        SectionTitle("Changes this week")
        if (changes.isEmpty()) {
            SettingsCard {
                Text(
                    if (list == null) "Adding it up…" else "No settings changes this week.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else SettingsCard(padded = false) {
            changes.forEachIndexed { i, c ->
                if (i > 0) SettingsDivider()
                ChangeRow(c)
            }
        }
    }
}

/**
 * One device's week: "4h 55m  Amelia · Living room", the days-and-average
 * line with last week beside it, the most-watched channel as a row that
 * pushes the full list, and — with screening on — the AI's note on the week.
 */
@Composable
private fun WatchingCard(
    device: PairedDevice,
    kidName: String?,
    weekly: Digest.Weekly,
    fresh: Boolean,
    aiEnabled: Boolean,
    configStore: ConfigStore,
    onChannels: () -> Unit
) {
    val scope = rememberCoroutineScope()
    SettingsCard(padded = false) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(
                    formatWatchTime(weekly.totalMin),
                    // Medium, not the scale's Bold: at 24sp the number is
                    // already the loudest thing on the page.
                    style = MaterialTheme.typography.headlineSmall
                        .copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
                    modifier = Modifier.alignByBaseline()
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    listOfNotNull(kidName, device.name).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline()
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                digestWeekLine(weekly),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!fresh) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "From its last report — today may be missing minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        SettingsDivider()
        Column(Modifier.padding(horizontal = 12.dp)) {
            val top = weekly.topChannels.firstOrNull()
            ValueRow(
                "Most watched",
                // The channel numbers come from a daily baseline, and a young
                // store counts from a later day than the window's first.
                summary = weekly.channelsSinceDay
                    ?.takeIf { it != weekly.days.first().first }
                    ?.let { "Counted since ${prettyWeekDay(it)}" }
                    ?: if (top == null) "Needs a day of history to compare against" else null,
                value = top?.let { "${it.first} · ${formatWatchTime(it.second)}" } ?: "Not yet",
                onClick = if (top != null) onChannels else null
            )
        }

        if (aiEnabled) {
            SettingsDivider()
            var summary by remember { mutableStateOf<String?>(null) }
            var busy by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            Column(Modifier.padding(12.dp)) {
                summary?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                } ?: CompactButton(
                    enabled = !busy,
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    AiScreener.summarizeDigest(
                                        configStore.load().ai,
                                        Digest.summaryFacts(kidName, weekly)
                                    )
                                }
                            }.onSuccess { summary = it }
                                .onFailure { error = "Couldn't reach the AI — the numbers above still stand." }
                            busy = false
                        }
                    }
                ) { Text(if (busy) "Summarizing…" else "Summarize the week") }
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Every channel with minutes in the window, biggest first, with a bar. */
@Composable
private fun MostWatchedPage(who: String, weekly: Digest.Weekly, onBack: () -> Unit) {
    SubPage(title = "Most watched", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        Text(
            listOfNotNull(
                who,
                weekly.channelsSinceDay
                    ?.takeIf { it != weekly.days.first().first }
                    ?.let { "counted since ${prettyWeekDay(it)}" }
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        SettingsCard(padded = false) {
            Column(Modifier.padding(16.dp)) {
                val max = (weekly.topChannels.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
                weekly.topChannels.forEachIndexed { i, (name, mins) ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            formatWatchTime(mins),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(mins.toFloat() / max)
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            }
        }
    }
}

/** "1h 12m more than last week" / "20m less than last week" / "same as last week"; null without a last week. */
internal fun weekComparison(totalMin: Int, lastWeekMin: Int?): String? {
    if (lastWeekMin == null) return null
    val delta = totalMin - lastWeekMin
    return when {
        delta > 0 -> "${formatWatchTime(delta)} more than last week"
        delta < 0 -> "${formatWatchTime(-delta)} less than last week"
        else -> "same as last week"
    }
}

/** "Watched on 5 of 7 days · 42 min a day · 1h 12m more than last week". */
internal fun digestWeekLine(w: Digest.Weekly): String = listOfNotNull(
    "Watched on ${w.daysWatched} of ${w.days.size} days",
    "${w.totalMin / w.days.size.coerceAtLeast(1)} min a day",
    weekComparison(w.totalMin, w.lastWeekMin)
).joinToString(" · ")

private fun prettyWeekDay(yyyymmdd: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyyMMdd", Locale.US).parse(yyyymmdd)!!
    SimpleDateFormat("EEE d", Locale.US).format(parsed)
}.getOrDefault(yyyymmdd)
