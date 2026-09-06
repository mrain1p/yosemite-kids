package io.yosemitekids.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yosemitekids.app.data.PROFILE_AVATARS
import io.yosemitekids.app.data.PROFILE_COLORS
import io.yosemitekids.app.data.PairingStore
import io.yosemitekids.app.data.Profile
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
    var editingAge by remember { mutableStateOf(false) }
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
                // Named, not "Remove": the dialog's own title is the only
                // other place the kid is named, and this is the irreversible tap.
                Button(onClick = { confirmRemove = false; onRemove() }) {
                    Text("Remove ${built.name}")
                }
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
        SettingsCard(padded = false) {
            // Name is a row like the ones under it rather than a boxed field:
            // label left, value right is what every row on this page does, and
            // the outline was the only chrome left on the card.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text("Name", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f).tvFocusHighlight(),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterEnd) {
                            if (name.isEmpty()) Text(
                                "Add a name",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SettingsPlaceholder
                            )
                            inner()
                        }
                    }
                )
            }
            SettingsDivider()
            // One row, not a −/+ pair with its explanation printed underneath:
            // an age is set once, and the sentence is what the row is for.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .tvFocusHighlight()
                    .clickable { editingAge = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Age", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "The AI screener judges videos against it",
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontSize = 12.sp, lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    age?.toString() ?: "Not set",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    YosemiteIcons.ChevronRight, contentDescription = null,
                    tint = SettingsPlaceholder,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (editingAge) {
                // The rules' own keypad, told to count years instead of
                // minutes — the same three taps a parent already knows.
                ExactMinutesDialog(
                    title = "Age", initial = age ?: 7, min = 2, max = 16, allowOff = true,
                    onDismiss = { editingAge = false },
                    onPick = { editingAge = false; age = it },
                    unit = "", hint = "2–16 years", offLabel = "Not set"
                )
            }
            SettingsDivider()
            // The same picker the kid gets behind "Change my look" (ProfileHub.kt).
            // A parent's choice here stamps lookAt too, so it beats an older
            // choice waiting on a device — newest wins on both sides.
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                // The card is unpadded so its rows can run edge to edge; the
                // picker is not a row, so it brings the inset itself.
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                LookPicker(
                    color, avatar,
                    onColor = { color = it; lookAt = System.currentTimeMillis() },
                    onAvatar = { avatar = it; lookAt = System.currentTimeMillis() }
                )
            }
            SettingsDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .tvFocusHighlight()
                    .clickable { settingPin = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Profile lock", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        pin?.let { "Code: ${directionPinArrows(it)}" }
                            ?: "No code — anyone can pick this profile",
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontSize = 12.sp, lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                CompactButton(onClick = { settingPin = true }) {
                    Text(if (pin == null) "Set code" else "Change")
                }
                if (pin != null) {
                    CompactButton(onClick = { pin = null }) { Text("Remove") }
                }
            }
            // Expands under the row rather than replacing it, so the code
            // being set stays attached to the thing it locks.
            if (settingPin) {
                var entered by remember { mutableStateOf("") }
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                ) {
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
            // Unpadded: the pause is a row of the card and brings its own
            // inset, so the line above it spans the card the way it does
            // everywhere else on the page.
            SettingsCard(padded = false) {
                GrantTimeSection(pairingStore, listOf(built))
                SettingsDivider()
                PauseTodayRow(
                    pausedUntil = limits.pausedUntilMillis,
                    kidName = built.name,
                    onChanged = { until -> limits = limits.copy(pausedUntilMillis = until) }
                )
            }

            Spacer(Modifier.height(18.dp))
            // Destructive, not the brand teal, which is reserved for ordinary
            // actions: `error` is the design's #E38C7E, and the border is that
            // colour let down towards the page behind it.
            OutlinedButton(
                onClick = { confirmRemove = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).tvFocusHighlight(),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    "Remove ${built.name}",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp)
                )
            }
            Spacer(Modifier.height(26.dp))
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        StatsRange.values().forEach { r ->
            RangeTab(r.label, selected = r == range, onClick = { range = r })
        }
    }
    // The tabs sit on a hairline the chosen one breaks, so the underline
    // reads as a mark on the row rather than as a stray rule under a word.
    SettingsDivider()
    Spacer(Modifier.height(4.dp))

    SettingsCard(padded = false) {
        val data = loaded
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 12.dp)) {
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
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = 26.sp, lineHeight = 26.sp,
                            fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp
                        ),
                        modifier = Modifier.alignByBaseline()
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${data.stats.videos} video" + if (data.stats.videos == 1) "" else "s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline()
                    )
                }
                Spacer(Modifier.height(6.dp))
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
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontSize = 12.5.sp, lineHeight = 19.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SettingsDivider()
        Column(Modifier.padding(12.dp)) {
            Text(
                // Today's list is a log, the week's is a ranking, and the
                // label is the only thing that says which one is on screen.
                if (range == StatsRange.TODAY) "Watched today" else "Most watched",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
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
            channels.forEachIndexed { i, (name, count) ->
                Spacer(Modifier.height(if (i == 0) 11.dp else 9.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        // Not `background`: this theme leaves the dark
                        // background at the card's own surface, so the groove
                        // would be invisible in the very card that draws it.
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

/**
 * One of the Today / Week words: the chosen one is teal with a line under it.
 *
 * The underline is the word's own width — `IntrinsicSize.Max` is what makes
 * `fillMaxWidth` mean "as wide as the text" rather than "as wide as the row" —
 * so it marks that word instead of sitting behind it as a fixed tab stop.
 * Both states are Medium: the weight swapping under a tap moved the words.
 */
@Composable
private fun RangeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .tvFocusHighlight()
            .clickable(onClick = onClick)
            .padding(top = 2.dp, bottom = 7.dp)
            .width(IntrinsicSize.Max)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium
            ),
            color = tint
        )
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) tint else Color.Transparent)
        )
    }
}
