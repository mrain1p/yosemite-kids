package io.yosemitekids.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yosemitekids.app.data.ALL_DAYS
import io.yosemitekids.app.data.DAY_LABELS
import io.yosemitekids.app.data.LISTEN_MULTIPLIERS
import io.yosemitekids.app.data.LanClient
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.PairingStore
import io.yosemitekids.app.data.SessionGuard
import io.yosemitekids.app.data.SettingsControl
import io.yosemitekids.app.data.TimeWindow
import io.yosemitekids.app.data.TimeWindows
import io.yosemitekids.app.data.WEEKDAYS
import kotlinx.coroutines.launch

// --- Screen time ------------------------------------------------------------

/**
 * The kid's recurring rules — minute limits and the video-length floor — in
 * one card under its own title, so the kid page reads Profile → Rules →
 * Blocked times → Today. [trailing] is the page's own tail for the card
 * (copy-from-sibling), kept inside the border because it acts on these rules.
 */
@Composable
internal fun RulesSection(
    limits: Limits,
    onChanged: (Limits) -> Unit,
    /** Break-skip write-through for callers that need one; null = form state only. */
    onBreakPassCommitted: ((passUntil: Long?) -> Unit)? = null,
    trailing: @Composable ColumnScope.() -> Unit = {}
) {
    SectionTitle(
        "Screen-time rules",
        // The Shorts sentence used to be printed under the last row, always.
        // It answers a question asked once, so it folds away with the rest.
        help = "Every rule off means unlimited watching. Tap a value to set it " +
            "exactly or turn it off. YouTube Shorts are never shown regardless " +
            "of the last rule — it also hides the short clips a channel uploads " +
            "as regular videos."
    )
    SettingsCard(padded = false) {
        // Label, range and unit from the manifest, so the hub's number fields
        // are the same rule with the same name and the same bounds. They were
        // not: "Time per session" was "Minutes a session" over there, with a
        // different ceiling.
        RuleRow(
            control = ctl("rules-session"),
            value = limits.sessionMinutes,
            onChanged = { onChanged(limits.copy(sessionMinutes = it)) }
        )
        SettingsDivider()
        RuleRow(
            control = ctl("rules-weekday-sessions"),
            value = limits.weekdaySessions,
            hint = "1–12 sessions",
            onChanged = { onChanged(limits.copy(weekdaySessions = it)) }
        )
        SettingsDivider()
        RuleRow(
            control = ctl("rules-weekend-sessions"),
            value = limits.weekendSessions,
            hint = "1–12 sessions",
            onChanged = { onChanged(limits.copy(weekendSessions = it)) }
        )
        SettingsDivider()
        RuleRow(
            control = ctl("rules-break"),
            value = limits.breakMinutes,
            onChanged = { onChanged(limits.copy(breakMinutes = it)) }
        )
        // Only while there's a break rule to skip — no rule, no row.
        if (limits.breakMinutes != null) {
            // The card is unpadded now, so anything that isn't a full-width
            // row brings the 12dp inset itself.
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                SkipBreakRow(limits.breakPassUntilMillis) { until ->
                    onChanged(limits.copy(breakPassUntilMillis = until))
                    onBreakPassCommitted?.invoke(until)
                }
            }
        }
        SettingsDivider()
        RuleRow(
            control = ctl("rules-min-video"),
            value = limits.minVideoMinutes,
            onChanged = { onChanged(limits.copy(minVideoMinutes = it)) }
        )
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { trailing() }
    }

    // Under the card, not in it: it describes the five rows above rather than
    // being a sixth one, and it moved out from between Break and Hide, where
    // it read as a note on the row it happened to follow.
    val set = rulesSet(limits)
    val summary = buildString {
        if (set == 0) {
            // A bedtime with every rule off is a real configuration — don't
            // contradict the blocked-times card below this one.
            append(
                if (limits.windows.isEmpty()) "No limits set — unlimited watching."
                else "No minute limits — the blocked times below still apply."
            )
        } else {
            append("$set of $KID_RULE_COUNT rules set.")
            val s = limits.sessionMinutes
            val wd = limits.weekdaySessions
            val we = limits.weekendSessions
            if (s != null && wd != null) append(" Weekdays: up to ${s * wd} min total.")
            if (s != null && we != null) append(" Weekends: up to ${s * we} min total.")
        }
    }
    Text(
        summary,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 9.dp)
    )
}

/**
 * One rule: its name, what it is set to, and a chevron into the keypad.
 *
 * No − and + any more. The pair could only reach Off by stepping off the
 * bottom of the range — a move nothing on screen announced — and the exact
 * value was already a tap on the number between them, so the row loses two
 * controls and gains nothing a parent could previously set.
 */
@Composable
private fun RuleRow(
    /**
     * The rule itself, from the shared manifest: its name, its range and the
     * unit after the number. The hub renders the same declaration, which is
     * the only reason the two faces can be trusted to mean the same thing by
     * "Break between sessions".
     */
    control: SettingsControl,
    value: Int?,
    hint: String = "${control.min}–${control.max} minutes",
    onChanged: (Int?) -> Unit
) {
    val label = control.label
    val min = control.min ?: 1
    val max = control.max ?: Int.MAX_VALUE
    val unit = control.unit
    var editing by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .tvFocusHighlight()
            .clickable { editing = true }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            when {
                value == null -> "Off"
                unit.isEmpty() -> "$value"
                else -> "$value $unit"
            },
            style = MaterialTheme.typography.bodyMedium,
            // Off in the faintest tone: an unset rule should not read like a
            // figure someone chose.
            color = if (value == null) SettingsPlaceholder else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            YosemiteIcons.ChevronRight, contentDescription = null,
            tint = SettingsPlaceholder,
            modifier = Modifier.size(18.dp)
        )
    }
    if (editing) {
        // An Off row opens at its minimum: the parent tapped it to set a
        // value, and a keypad seeded at nothing has nothing to confirm.
        ExactMinutesDialog(
            title = label, initial = value ?: min, min = min, max = max, allowOff = true,
            onDismiss = { editing = false },
            onPick = { editing = false; onChanged(it) },
            unit = unit, hint = hint
        )
    }
}

// Not named formatClock: Theme.kt's formatClock(Long) formats a *duration*,
// and an Int seconds value passed here would silently print garbage.
internal fun formatMinuteOfDay(minOfDay: Int): String = "%d:%02d".format(minOfDay / 60, minOfDay % 60)

/**
 * The blocked-clock-window list: bedtime, school hours, homework. A list
 * rather than a single bedtime switch because the ordinary cases need more
 * than one window and need different days each. Empty is a real state — no
 * window at all — so there is nothing to switch "off" and no default schedule
 * for a parent who never added one.
 *
 * Rows are collapsed to one line each (alarm-list style) and only the tapped
 * one opens: three windows fully expanded was three name fields, six clocks
 * and three day rows on one screen.
 */
@Composable
internal fun BlockedTimesSection(
    windows: List<TimeWindow>,
    /**
     * Skip-pass write-through for callers that need one; null = form state
     * only (the kid page, where the form's auto-save carries it).
     */
    onPassCommitted: ((windowId: String, passUntil: Long?) -> Unit)? = null,
    onChanged: (List<TimeWindow>) -> Unit
) {
    SectionTitle(ctl("blocked-times-windows").label)
    SettingsCard(padded = false) {
        var expandedId by remember { mutableStateOf<String?>(null) }
        var adding by remember { mutableStateOf(false) }

        if (windows.isEmpty()) {
            Text(
                "No blocked times — screen time is limited by minutes only, not by clock.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // The card no longer pads its children; this one is a
                // paragraph rather than a row, so it brings its own.
                modifier = Modifier.padding(12.dp)
            )
        }
        windows.forEachIndexed { i, w ->
            if (i > 0) SettingsDivider()
            if (w.id == expandedId) {
                TimeWindowEditor(
                    window = w,
                    onPassCommitted = onPassCommitted,
                    onChanged = { updated -> onChanged(windows.toMutableList().also { it[i] = updated }) },
                    onRemove = {
                        expandedId = null
                        onChanged(windows.filterIndexed { j, _ -> j != i })
                    },
                    onCollapse = { expandedId = null }
                )
            } else {
                TimeWindowRow(w) { expandedId = w.id }
            }
        }
        if (windows.isNotEmpty()) SettingsDivider()

        // One Add with three starting points behind it rather than three
        // buttons: a window has to start with *some* times, and naming the
        // presets is more honest than inventing a schedule behind a blank
        // "Add" — but they don't need to sit on the screen until asked for.
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .tvFocusHighlight()
                    .clickable { adding = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                Text(
                    "+",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Add blocked time",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = adding,
                onDismissRequest = { adding = false }
            ) {
                fun add(label: String, startMin: Int, endMin: Int, days: Set<Int>) {
                    adding = false
                    val id = newWindowId(windows)
                    onChanged(windows + TimeWindow(id = id, label = label, startMin = startMin, endMin = endMin, days = days))
                    expandedId = id
                }
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Bedtime") },
                    onClick = { add("Bedtime", 19 * 60 + 30, 7 * 60, ALL_DAYS) }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("School hours") },
                    onClick = { add("School hours", 8 * 60 + 30, 15 * 60, WEEKDAYS) }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Custom") },
                    // A deliberate placeholder — blank name, neutral midday
                    // span — that reads as "yours to fill in", not a schedule
                    // we invented.
                    onClick = { add("", 12 * 60, 14 * 60, ALL_DAYS) }
                )
            }
        }
    }
}

/** Unique within the config; the id keys a window's pass, so it must not be reused. */
private fun newWindowId(existing: List<TimeWindow>): String {
    val taken = existing.map { it.id }.toSet()
    return generateSequence(1) { it + 1 }.map { "w$it" }.first { it !in taken }
}

/** Collapsed: name, hours, days — enough to answer "what's set" without opening it. */
@Composable
private fun TimeWindowRow(window: TimeWindow, onOpen: () -> Unit) {
    val skipped = (window.passUntilMillis ?: 0L) > System.currentTimeMillis()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .tvFocusHighlight()
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                window.label.ifBlank { "Unnamed" },
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(2.dp))
            // Days first, then the hours: "which days" is what a parent scans
            // a list of windows for, and two windows differ by it more often
            // than by the clock.
            Text(
                listOfNotNull(
                    daySummary(window.days),
                    "${formatMinuteOfDay(window.startMin)} → ${formatMinuteOfDay(window.endMin)}",
                    if (window.allowListening) "listening allowed" else null,
                    if (skipped) "skipped once" else null
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall
                    .copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("⌄", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * "Mon–Thu", "Mon–Wed, Fri" — runs of consecutive days collapse to a range,
 * because four listed days are read as four things and a range as one.
 * Days are Sun..Sat, so [DAY_LABELS] is indexed by day − 1. The kid-facing
 * copy of this lives in core's Whitelist.kt and is deliberately its own.
 */
private fun daySummary(days: Set<Int>): String = when (days) {
    ALL_DAYS -> "Every day"
    WEEKDAYS -> "Mon–Fri"
    io.yosemitekids.app.data.WEEKEND_DAYS -> "Weekends"
    else -> days.sorted()
        .fold(mutableListOf<MutableList<Int>>()) { runs, day ->
            val last = runs.lastOrNull()
            if (last != null && day == last.last() + 1) last.add(day)
            else runs.add(mutableListOf(day))
            runs
        }
        .joinToString(", ") { run ->
            if (run.size == 1) DAY_LABELS[run.first() - 1]
            else "${DAY_LABELS[run.first() - 1]}–${DAY_LABELS[run.last() - 1]}"
        }
}

@Composable
private fun TimeWindowEditor(
    window: TimeWindow,
    onPassCommitted: ((String, Long?) -> Unit)?,
    onChanged: (TimeWindow) -> Unit,
    onRemove: () -> Unit,
    onCollapse: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        // The card is unpadded, so the expanded editor supplies the inset its
        // collapsed row does; without it the name field touches the border.
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = window.label,
                onValueChange = { onChanged(window.copy(label = it)) },
                singleLine = true,
                label = { Text("Name") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            CompactButton(onClick = onCollapse) { Text("Done") }
        }
        // Max is 23:59, not 23:30: the clock picker can land on any minute, so
        // the half-hour grid is only what − and + snap to, not the set of
        // reachable times.
        StepperRow(
            label = "Starts", value = window.startMin, step = 30, min = 0, max = 24 * 60 - 1,
            allowOff = false, format = ::formatMinuteOfDay, picker = StepperPicker.Clock,
            onChanged = { onChanged(window.copy(startMin = it ?: window.startMin)) }
        )
        StepperRow(
            label = "Ends", value = window.endMin, step = 30, min = 0, max = 24 * 60 - 1,
            allowOff = false, format = ::formatMinuteOfDay, picker = StepperPicker.Clock,
            onChanged = { onChanged(window.copy(endMin = it ?: window.endMin)) }
        )
        DayChips(window.days) { onChanged(window.copy(days = it)) }
        AllowListeningRow(window, onChanged)
        SkipOnceRow(window, onPassCommitted, onChanged)
        TextButton(onClick = onRemove) { Text("Remove this blocked time") }
    }
}

/**
 * The film that runs past the sitting cap: waives the next break only — the
 * first break it covers spends it, and an unused skip expires at midnight.
 * Committed and pushed the moment it's tapped, same as a window's Skip.
 */
@Composable
private fun SkipBreakRow(passUntil: Long?, onChanged: (Long?) -> Unit) {
    val skipped = (passUntil ?: 0L) > System.currentTimeMillis()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (skipped) "  Next break skipped" else "  Skip the next break",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        CompactButton(
            onClick = { onChanged(if (skipped) null else endOfToday()) }
        ) { Text(if (skipped) "Undo" else "Skip") }
    }
}

/**
 * Bedtime's exception: the window still stops watching, but a story the kid is
 * listening to plays on. Shown on every window because the parent's own window
 * names are the only thing that says which one is bedtime; off by default, so
 * a window that already exists keeps blocking exactly as it did.
 */
@Composable
private fun AllowListeningRow(window: TimeWindow, onChanged: (TimeWindow) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChanged(window.copy(allowListening = !window.allowListening)) }
    ) {
        Checkbox(
            modifier = Modifier.tvFocusHighlight(),
            checked = window.allowListening,
            onCheckedChange = { onChanged(window.copy(allowListening = it)) }
        )
        Column(Modifier.weight(1f)) {
            Text("Allow listening", style = MaterialTheme.typography.bodyMedium)
            Text(
                // Says what the kid gets, not what the setting does: the screen
                // is the thing a parent is trying to keep off at bedtime.
                "Audio keeps playing — the screen stays off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The sick day, the school holiday, the film that runs long. Scoped to this
 * window and to one occurrence — it's set to the moment that occurrence would
 * have ended, so it lapses on its own and skipping bedtime tonight never
 * unlocks tomorrow morning's school hours.
 */
@Composable
private fun SkipOnceRow(
    window: TimeWindow,
    onPassCommitted: ((String, Long?) -> Unit)?,
    onChanged: (TimeWindow) -> Unit
) {
    val now = System.currentTimeMillis()
    val skipped = (window.passUntilMillis ?: 0L) > now
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (skipped) "Skipped this once" else "Skip the next one",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        CompactButton(
            onClick = {
                val passUntil = if (skipped) null else {
                    val cal = java.util.Calendar.getInstance()
                    TimeWindows.minutesUntilEndOfNext(
                        window,
                        cal.get(java.util.Calendar.DAY_OF_WEEK),
                        cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                            cal.get(java.util.Calendar.MINUTE)
                    )?.let { now + it * 60_000L }
                }
                onChanged(window.copy(passUntilMillis = passUntil))
                // "Skipped this once" must be true the moment it's shown —
                // committed and pushed now, not parked until Save & close.
                onPassCommitted?.invoke(window.id, passUntil)
            }
        ) { Text(if (skipped) "Undo" else "Skip") }
    }
}

@Composable
private fun DayChips(days: Set<Int>, onChanged: (Set<Int>) -> Unit) {
    // Scrolls: seven chips overflow a phone in portrait, and clipping at the
    // screen edge mangled the row.
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        DAY_LABELS.forEachIndexed { index, name ->
            val day = index + 1
            val on = day in days
            FilterChip(
                modifier = Modifier.tvFocusHighlight(),
                selected = on,
                // Clearing the last day would leave a window that blocks
                // nothing but still looks configured; Remove is how you mean
                // that, so the tap is simply ignored.
                onClick = { if (!on || days.size > 1) onChanged(if (on) days - day else days + day) },
                label = { Text(name, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

// --- Listening ----------------------------------------------------------------

/**
 * Family-wide screen-off listening rate — one knob on purpose. Off means the
 * feature doesn't exist: locking the phone pauses playback, exactly the
 * pre-listen behavior, with no hidden default rate. Phones only; a TV can't
 * play with its panel off, so TVs simply ignore the setting.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ListenRateRow(percent: Int?, onChange: (Int?) -> Unit) {
    var helpOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            // 48dp rather than the design's 68: the Playback page wraps this
            // row in the same 12/10 inset a ToggleRow carries itself, so the
            // outer height matches the switches above and below it.
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Column(Modifier.weight(1f).padding(end = 4.dp)) {
                Text(
                    ctl("listening-rate").label,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (percent == null) "Locking the phone stops playback"
                    else "Audio continues with the screen off",
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = SettingsTextTertiary
                )
            }
            HelpDot(open = helpOpen, onToggle = { helpOpen = !helpOpen })
            val color = percent?.let { timeMultiplierColor(it) }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    // A rate that costs or saves time is worth a fill; 1x and
                    // Off are not, so they stay hollow like the design's chips.
                    .let {
                        if (color != null) it.background(color)
                        else it.border(1.dp, SettingsStrongBorder, RoundedCornerShape(8.dp))
                    }
                    .combinedClickable(
                        onClick = {
                            // Unknown value (hand-edited config) self-heals: indexOf
                            // gives -1, so the next tap lands on Off.
                            val i = LISTEN_MULTIPLIERS.indexOf(percent)
                            onChange(LISTEN_MULTIPLIERS[(i + 1) % LISTEN_MULTIPLIERS.size])
                        },
                        onLongClick = { onChange(null) }
                    )
                    .widthIn(min = 54.dp)
                    .padding(horizontal = 11.dp)
            ) {
                Text(
                    percent?.let(::timeMultiplierLabel) ?: "Off",
                    style = MaterialTheme.typography.labelLarge
                        .copy(fontSize = 12.5.sp, lineHeight = 13.sp),
                    color = if (color == null) SettingsTextTertiary else Color.White
                )
            }
        }
        if (helpOpen) {
            Spacer(Modifier.height(10.dp))
            Text(
                buildString {
                    append("For listening rather than watching — audio keeps going with the screen off.")
                    if (percent == 0) {
                        append(" Listening is free — it doesn't use up screen time.")
                    } else if (percent != null) {
                        append(
                            " Those minutes count at ${timeMultiplierLabel(percent)} " +
                                "of each channel's usual rate."
                        )
                    }
                },
                style = MaterialTheme.typography.bodySmall
                    .copy(fontSize = 12.5.sp, lineHeight = 20.sp),
                color = SettingsTextTertiary,
                // The caller's inset supplies 10 of the design's 13dp below.
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
    }
}

/**
 * What tapping a stepper's value opens, and so which rows can be set to an
 * exact figure at all. [None] leaves the value as plain text — the counting
 * rows (sessions per day, child age) step by 1 already, so there is nothing
 * finer for a picker to offer and a tappable number would only add a dialog
 * between a parent and a two-tap edit.
 */
internal enum class StepperPicker { None, Minutes, Clock }

/**
 * Touch stepper. Stepping below [min] turns the rule Off (null).
 *
 * − and + move by whole [step]s *on the grid* — from an off-grid 19:45 the next
 * step down is 19:30, not 19:15 — because the exact minutes come from tapping
 * the value ([picker]), and the buttons stay the coarse "roughly there" control
 * they have always been. Phone-only in practice: TV settings is the pairing QR
 * and nothing else.
 */
@Composable
internal fun StepperRow(
    label: String,
    value: Int?,
    step: Int,
    min: Int,
    max: Int,
    allowOff: Boolean = true,
    format: (Int) -> String,
    picker: StepperPicker = StepperPicker.None,
    onChanged: (Int?) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = 44.dp).padding(vertical = 2.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f))
        CompactButton(
            onClick = {
                val next = stepDown(value ?: return@CompactButton, step)
                when {
                    next >= min -> onChanged(next)
                    allowOff -> onChanged(null)
                    else -> onChanged(min)
                }
            }
        ) { Text("−") }
        StepperValue(
            title = label.trim(),
            value = value,
            text = value?.let(format) ?: "Off",
            picker = picker, min = min, max = max, allowOff = allowOff,
            onChanged = onChanged
        )
        CompactButton(
            onClick = {
                onChanged(if (value == null) min else stepUp(value, step).coerceIn(min, max))
            }
        ) { Text("+") }
    }
}

/** Next grid point strictly below [value]; may fall under the row's minimum. */
private fun stepDown(value: Int, step: Int): Int =
    if (value % step == 0) value - step else value / step * step

/** Next grid point strictly above [value]. */
private fun stepUp(value: Int, step: Int): Int = (value / step + 1) * step

/**
 * The number between − and +. With a [picker] it becomes the way to land on an
 * exact figure — 19:45 bedtime, a 20-minute session — which the coarse buttons
 * can't express; it gets a filled chip so it reads as tappable rather than as
 * the label it used to be.
 */
@Composable
private fun StepperValue(
    title: String,
    value: Int?,
    text: String,
    picker: StepperPicker,
    min: Int,
    max: Int,
    allowOff: Boolean,
    onChanged: (Int?) -> Unit
) {
    if (picker == StepperPicker.None) {
        Text(text, modifier = Modifier.widthIn(min = 64.dp), textAlign = TextAlign.Center)
        return
    }
    var editing by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { editing = true }
            .widthIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, textAlign = TextAlign.Center)
    }
    if (editing) {
        // An Off row opens at its minimum: the parent tapped it to set a value,
        // and a picker seeded at nothing has nothing to confirm.
        val seed = value ?: min
        val close = { editing = false }
        val pick: (Int?) -> Unit = { editing = false; onChanged(it) }
        when (picker) {
            StepperPicker.Clock -> ExactClockDialog(title, seed, close, pick)
            StepperPicker.Minutes -> ExactMinutesDialog(title, seed, min, max, allowOff, close, pick)
            StepperPicker.None -> {}
        }
    }
}

/**
 * Always 24-hour, matching how [formatMinuteOfDay] prints the row behind it —
 * a dialog that says 7:45 PM over a row that says 19:45 reads like two
 * different times. Keypad rather than the dial: four digits beats spinning a
 * ring to one particular minute, which is the whole point of the picker.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ExactClockDialog(
    title: String,
    initialMin: Int,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialMin / 60,
        initialMinute = initialMin % 60,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimeInput(state = state) },
        confirmButton = {
            TextButton(onClick = { onPick(state.hour * 60 + state.minute) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Exact minutes for the duration rows, clamped to the row's own range.
 *
 * [unit], [hint] and [offLabel] are the three places the word "minutes" leaks
 * into the keypad; naming them lets a row that counts something else — a
 * child's age, a session count — reuse this rather than grow a second dialog
 * that behaves almost the same.
 */
@Composable
internal fun ExactMinutesDialog(
    title: String,
    initial: Int,
    min: Int,
    max: Int,
    allowOff: Boolean,
    onDismiss: () -> Unit,
    onPick: (Int?) -> Unit,
    unit: String = "min",
    hint: String = "$min–$max minutes",
    offLabel: String = "Turn off"
) {
    // Opens selected, so the first digit typed replaces the old value instead
    // of appending to it — 45 min becomes 20, not 4520.
    var field by remember {
        mutableStateOf(
            TextFieldValue(initial.toString(), selection = TextRange(0, initial.toString().length))
        )
    }
    val focus = remember { FocusRequester() }
    val entered = field.text.toIntOrNull()
    val valid = entered != null && entered in min..max

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                // Digits only: the number keyboard is a hint to the IME, not a
                // guarantee — a paste or a hardware keyboard gets past it. The
                // cap keeps a long paste from ever reaching toIntOrNull.
                onValueChange = { new ->
                    field = new.copy(text = new.text.filter(Char::isDigit).take(4))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                suffix = if (unit.isEmpty()) null else ({ Text(unit) }),
                supportingText = { Text(hint) },
                isError = field.text.isNotEmpty() && !valid,
                modifier = Modifier.focusRequester(focus)
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onPick(entered) }) { Text("Set") }
        },
        dismissButton = {
            Row {
                if (allowOff) TextButton(onClick = { onPick(null) }) { Text(offLabel) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

// --- Grants -----------------------------------------------------------------

@Composable
internal fun GrantTimeSection(
    pairingStore: PairingStore,
    profiles: List<io.yosemitekids.app.data.Profile> = emptyList()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var minutes by remember { mutableIntStateOf(15) }
    var granted by remember { mutableStateOf<GrantReceipt?>(null) }
    var targetKidId by remember(profiles.map { it.id }) {
        mutableStateOf(profiles.firstOrNull()?.id)
    }

    var customising by remember { mutableStateOf(false) }

    // The card is unpadded so its rows can run edge to edge; this block is
    // not a row, so it brings the design's 12dp itself.
    Column(Modifier.padding(12.dp)) {
        // Granting to a child, not to a device: the minutes land on that kid's
        // guard here and on every paired device.
        if (profiles.size >= 2) {
            KidSelectorChips(profiles, targetKidId ?: profiles.first().id) { targetKidId = it }
            Spacer(Modifier.height(9.dp))
        }

        Text("Bonus watch time", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(9.dp))
        // Four amounts to pick from rather than two buttons to press: the four
        // cover what a parent actually grants, and the fifth keeps every value
        // the stepper could reach behind the same keypad it used.
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            BONUS_PRESETS.forEach { preset ->
                BonusChip(
                    label = "$preset min",
                    selected = minutes == preset,
                    onClick = { minutes = preset },
                    modifier = Modifier.weight(1f)
                )
            }
            BonusChip(
                label = if (minutes in BONUS_PRESETS) "Custom" else "$minutes min",
                selected = minutes !in BONUS_PRESETS,
                onClick = { customising = true },
                modifier = Modifier.weight(1f)
            )
        }
        if (customising) {
            ExactMinutesDialog(
                title = "Bonus watch time", initial = minutes, min = 5, max = 180,
                // No Off: a grant of nothing isn't a grant, and Grant is what
                // applies it — there is no rule here to switch off.
                allowOff = false,
                onDismiss = { customising = false },
                onPick = { customising = false; minutes = it ?: minutes }
            )
        }
        Spacer(Modifier.height(10.dp))
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp).tvFocusHighlight(),
            shape = RoundedCornerShape(8.dp),
            onClick = {
                val amount = minutes
                val kidId = if (profiles.isEmpty()) null else targetKidId
                val kidName = profiles.firstOrNull { it.id == kidId }?.name
                val suffix = io.yosemitekids.app.data.ProfileNamespace(context.applicationContext)
                    .suffixFor(kidId)
                // One tap, one grant, one id. It goes into the config first,
                // because that is the copy every device will hold: a TV that
                // is asleep now finds it at its next sync, within fifteen
                // minutes of waking. The guard here and the LAN call to each
                // awake device are the fast paths, and they carry the same id
                // so nothing counts the tap twice.
                val now = System.currentTimeMillis()
                val grant = io.yosemitekids.app.data.Grant(
                    id = io.yosemitekids.app.data.Profile.newId(),
                    kidId = kidId,
                    date = io.yosemitekids.app.data.Grants.dateOf(now),
                    minutes = amount,
                    at = now
                )
                val who = kidName?.let { " for $it" } ?: ""
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        io.yosemitekids.app.data.ConfigStore(context.applicationContext)
                            .update(who = pairingStore.myName(), by = pairingStore.by()) {
                                it.copy(grants = it.grants + grant)
                            }
                        SessionGuard(context.applicationContext, suffix).applyGrant(grant)
                    }
                    val devices = pairingStore.paired()
                    var ok = 0
                    devices.forEach { if (LanClient.grant(it, amount, kidId, grant)) ok++ }
                    granted = GrantReceipt(
                        if (devices.isEmpty()) "Granted $amount extra minutes$who 🎉"
                        else "Granted $amount min$who: here now, on $ok of ${devices.size} device(s) now, " +
                            "and on any asleep at their next sync 🎉"
                    )
                }
            }
        ) {
            // The button says the amount, so the chip row and the action can
            // never disagree about what a tap is about to give.
            Text(
                if (granted != null) "Granted $minutes min ✓" else "Grant $minutes min",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.5.sp)
            )
        }
        Spacer(Modifier.height(8.dp))
        // Where the minutes go, in plain words: the tap is in the family config
        // now, so every device gets it by the same path as every rule.
        Text(
            "Reaches every device: awake ones right away, asleep ones within " +
                "fifteen minutes of waking, through the hub if there is one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        granted?.let { receipt ->
            Spacer(Modifier.height(4.dp))
            // Kept alongside the button's own "Granted ✓": the label says the
            // tap landed, the receipt says on how many devices.
            Text(receipt.text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // A receipt, not a status line: it was true when it appeared and says
            // nothing about now, so it goes rather than sitting under the row until
            // the app is next restarted. Keyed on the receipt (`at` gives two
            // identical grants separate identities) so a second Grant gets its own
            // full five seconds.
            LaunchedEffect(receipt) {
                kotlinx.coroutines.delay(5_000)
                if (granted == receipt) granted = null
            }
        }
    }
}

/** The amounts a parent actually grants; anything else is a tap into the keypad. */
private val BONUS_PRESETS = listOf(5, 15, 30, 60)

/**
 * The design's chip: hollow with a strong border, and the accent as a 16%
 * tint when it is the chosen one. Its own Box rather than a [FilterChip]
 * because five of these share a 344dp row — Material's chip padding alone
 * would push the last label out of its slot.
 */
@Composable
private fun BonusChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SettingsAccentTint else Color.Transparent)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else SettingsStrongBorder,
                RoundedCornerShape(8.dp)
            )
            .tvFocusHighlight(cornerRadius = 8.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else SettingsTextSecondary,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/** One Grant tap's confirmation; `at` keeps repeats distinguishable. */
private data class GrantReceipt(val text: String, val at: Long = System.currentTimeMillis())

/**
 * Parent timeout: one switch turns watching off until midnight, on every
 * device, effective immediately (the guard's next tick stops a playing
 * video). Deliberately minimal — no durations, no multi-day bans; the switch
 * back is the undo. With a [kidName] it is that kid's own pause; without, the
 * family-wide one that stops everyone.
 *
 * A confirm dialog used to stand between the button and the pause. It guarded
 * an action that lapses on its own at midnight and that the same control
 * reverses in one tap, and it made the most-pressed lever on the page a
 * three-tap errand.
 */
@Composable
internal fun PauseTodayRow(pausedUntil: Long?, kidName: String? = null, onChanged: (Long?) -> Unit) {
    val active = pausedUntil != null && pausedUntil > System.currentTimeMillis()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (kidName == null) ctl("rules-pause").label else "Turn off $kidName's watching",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(2.dp))
            Text(
                // The root card's own wording for the same state, so the two
                // places a parent can read it cannot drift.
                if (active) "Paused until midnight" else "Until midnight",
                style = MaterialTheme.typography.bodySmall
                    .copy(fontSize = 12.sp, lineHeight = 17.sp),
                color = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            modifier = Modifier.tvFocusHighlight(),
            checked = active,
            onCheckedChange = { onChanged(if (it) endOfToday() else null) },
            // The design's switch, spelled the same way ToggleRow spells it.
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedBorderColor = Color.Transparent,
                uncheckedTrackColor = SettingsStrongBorder,
                uncheckedThumbColor = SettingsTextTertiary,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

/** Midnight tonight. Internal so the root card and this page cannot drift
 *  about what "pause" means — an unbounded pause is one a parent forgets. */
internal fun endOfToday(): Long = java.util.Calendar.getInstance().apply {
    add(java.util.Calendar.DAY_OF_YEAR, 1)
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis
