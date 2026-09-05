package io.pickwick.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.PROFILE_AVATARS
import io.pickwick.app.data.PROFILE_COLORS
import io.pickwick.app.data.PairingStore
import io.pickwick.app.data.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One kid, one page: who they are, their rules, and today's levers. It
 * replaced the edit dialog plus the kid chips over the screen-time steppers,
 * so a parent looking for "Dave's bedtime" finds it under Dave rather than
 * under a section that then asks which kid.
 *
 * Applies as you edit: every change goes to [onChanged] and the settings
 * form's auto-save does the rest. A new kid is only handed over once they
 * have a name — an unnamed profile on the TV's who's-watching screen would
 * be a blank tile.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun KidPage(
    profile: Profile,
    isNew: Boolean,
    siblings: List<Profile>,
    pairingStore: PairingStore,
    onBack: () -> Unit,
    onChanged: (Profile) -> Unit,
    onRemove: () -> Unit
) {
    var name by remember { mutableStateOf(profile.name) }
    var age by remember { mutableStateOf(profile.age) }
    var color by remember { mutableStateOf(profile.colorArgb) }
    var avatar by remember { mutableStateOf(profile.avatar) }
    var lookAt by remember { mutableStateOf(profile.lookAt) }
    var pin by remember { mutableStateOf(profile.pin) }
    var settingPin by remember { mutableStateOf(false) }
    var limits by remember { mutableStateOf(profile.limits) }
    var confirmRemove by remember { mutableStateOf(false) }

    val built = profile.copy(
        name = name.trim(), age = age, colorArgb = color, avatar = avatar, lookAt = lookAt,
        pin = pin, limits = limits
    )
    LaunchedEffect(built) {
        if (built != profile && built.name.isNotBlank()) onChanged(built)
    }

    BackHandler(onBack = onBack)

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${built.name}?") },
            text = {
                Text(
                    "${built.name}'s profile disappears from every device. Their " +
                        "watch history stays on each device but is no longer shown."
                )
            },
            confirmButton = {
                Button(onClick = { confirmRemove = false; onRemove() }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            }
        )
    }

    SubPage(title = if (isNew && name.isBlank()) "New kid" else name, onBack = onBack) {
        if (isNew && name.isBlank()) {
            Text(
                "Give them a name to add them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // A kid who isn't on any device yet has no day to sum up.
        if (!isNew) KidOverview(built)
        SectionTitle("Profile")
        SettingsCard {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Age", modifier = Modifier.weight(1f))
            TextButton(onClick = { age = age?.let { (it - 1).coerceAtLeast(2) } }) { Text("−") }
            Text(
                age?.toString() ?: "—",
                modifier = Modifier.width(40.dp),
                textAlign = TextAlign.Center
            )
            TextButton(onClick = { age = ((age ?: 3) + 1).coerceAtMost(16) }) { Text("+") }
        }
        Text(
            "The AI screener judges videos against each kid's age.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsDivider()
        // The same picker the kid gets behind "Change my look" (ProfileHub.kt).
        // A parent's choice here stamps lookAt too, so it beats an older
        // choice waiting on a device — newest wins on both sides.
        LookPicker(
            color, avatar,
            onColor = { color = it; lookAt = System.currentTimeMillis() },
            onAvatar = { avatar = it; lookAt = System.currentTimeMillis() }
        )
        SettingsDivider()
        Text("Profile lock", style = MaterialTheme.typography.labelLarge)
        if (settingPin) {
            var entered by remember { mutableStateOf("") }
            Text(
                "Tap four buttons (arrows or OK) — on the TV, " +
                    "${name.ifBlank { "your kid" }} presses them on the remote, " +
                    "and the screen shows only dots.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                // Parent context: show the arrows while setting — the secrecy
                // matters at entry time on the couch, not here.
                if (entered.isEmpty()) "· · · ·" else directionPinArrows(entered),
                style = MaterialTheme.typography.titleLarge
            )
            DirectionArrowPad(onPress = { dir ->
                if (entered.length < 4) entered += dir
                if (entered.length == 4) {
                    pin = entered
                    settingPin = false
                }
            })
            TextButton(onClick = { settingPin = false }) { Text("Cancel") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pin?.let { "Code: ${directionPinArrows(it)}" }
                        ?: "No code — anyone can pick this profile",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { settingPin = true }) {
                    Text(if (pin == null) "Set code" else "Change")
                }
                if (pin != null) {
                    TextButton(onClick = { pin = null }) { Text("Remove") }
                }
            }
        }
        }

        RulesSection(limits, onChanged = { limits = it }) {
            if (siblings.isNotEmpty()) {
                SettingsDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Copy rules from:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    siblings.forEach { sibling ->
                        TextButton(
                            modifier = Modifier.tvFocusHighlight(),
                            // Rules only: a sibling's timeout is theirs.
                            onClick = { limits = sibling.limits.copy(pausedUntilMillis = null) }
                        ) { Text(sibling.name) }
                    }
                }
            }
        }
        BlockedTimesSection(limits.windows) { limits = limits.copy(windows = it) }

        // A kid who isn't on any device yet has nothing to grant or pause.
        if (!isNew) {
            SectionTitle("Today")
            SettingsCard {
                GrantTimeSection(pairingStore, listOf(built))
                SettingsDivider()
                PauseTodayRow(
                    pausedUntil = limits.pausedUntilMillis,
                    kidName = built.name,
                    onChanged = { until -> limits = limits.copy(pausedUntilMillis = until) }
                )
            }

            Spacer(Modifier.height(32.dp))
            OutlinedButton(
                modifier = Modifier.tvFocusHighlight(),
                onClick = { confirmRemove = true }
            ) { Text("Remove ${built.name}") }
        }
    }
}

/**
 * The top of the page (raw-kid.png): the kid's face, the same one-line
 * summary their row on Kids shows, a Today/Week choice, and a card with
 * how much was watched and where. The controls that were the whole page
 * before sit under it, unchanged.
 */
@Composable
private fun KidOverview(profile: Profile) {
    var range by remember { mutableStateOf(StatsRange.WEEK) }
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // Prefs, files and a JSON parse per cached feed — off-main, and the card
    // shows a placeholder for the beat it takes.
    val loaded by produceState<KidStatsLoaded?>(null, profile.id, range) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadKidStats(context, profile.id, range) }.getOrNull()
        }
    }

    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ProfileAvatar(profile, size = 68)
        Spacer(Modifier.height(10.dp))
        Text(
            kidSummary(profile),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatsRange.values().forEach { r ->
            RangeTab(r.label, selected = r == range, onClick = { range = r })
        }
    }
    Spacer(Modifier.height(4.dp))

    SettingsCard(padded = false) {
        val data = loaded
        Column(Modifier.padding(16.dp)) {
            if (data == null) {
                Text(
                    "Adding it up…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row {
                    Text(
                        formatWatchTime(data.stats.minutes),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.alignByBaseline()
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${data.stats.videos} video" + if (data.stats.videos == 1) "" else "s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline()
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when (range) {
                        StatsRange.WEEK -> weekSummary(data.stats)
                        // Today has no days to count, so its line is the one
                        // the TV is enforcing right now.
                        StatsRange.TODAY -> data.today.state + (
                            data.today.budgetTodayMin?.let { " · ${data.today.watchedTodayMin} of $it min" }
                                ?: " · no daily limit"
                            )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SettingsDivider()
        Column(Modifier.padding(16.dp)) {
            Text(
                "Most watched",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val channels = data?.stats?.channels.orEmpty()
            if (data != null && channels.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Nothing watched ${if (range == StatsRange.TODAY) "today" else "this week"} yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val max = (channels.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
            channels.forEach { (name, count) ->
                Spacer(Modifier.height(10.dp))
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
                        count.toString(),
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
                            .fillMaxWidth(count.toFloat() / max)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }
    // The two numbers have different reach, and a parent comparing them to
    // the TV's own stats page would otherwise think one of them is wrong.
    Text(
        "Minutes are what this device played; videos count every synced device.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    )
}

/** One of the Today / Week words: the chosen one is teal with a line under it. */
@Composable
private fun RangeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .tvFocusHighlight()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .width(28.dp)
                .height(2.dp)
                .background(if (selected) tint else Color.Transparent)
        )
    }
}
