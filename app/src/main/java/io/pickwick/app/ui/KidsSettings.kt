package io.pickwick.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.Limits
import io.pickwick.app.data.PROFILE_AVATARS
import io.pickwick.app.data.PROFILE_COLORS
import io.pickwick.app.data.Profile

// ---------------------------------------------------------------------------
// The "Kids" list of the parent settings, plus the small per-kid widgets
// (toggle chips, who-for dialog) reused by channels, folders and the AI queue.
// ---------------------------------------------------------------------------

/**
 * The family's kids. Each row opens that kid's page ([KidPage]); adding one
 * opens the page on a fresh profile. There is always at least one kid — the
 * admin form makes a "Kid" out of a kid-less config on load — so nothing here
 * has to explain a profile-free mode any more.
 *
 * Rendered inside an unpadded [SettingsCard]: one row per kid, a divider,
 * then "+ Add a kid" as a row of the same card rather than a button below it.
 * See docs/design/parent-settings/screens/raw-kids.png.
 */
@Composable
fun KidsSection(
    profiles: List<Profile>,
    onOpen: (profile: Profile, isNew: Boolean) -> Unit
) {
    profiles.forEach { profile ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusHighlight()
                .clickable { onOpen(profile, false) }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            ProfileAvatar(profile, size = 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profile.name, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    kidSummary(profile),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                PickwickIcons.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        SettingsDivider()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusHighlight()
            .clickable {
                onOpen(
                    Profile(
                        id = Profile.newId(),
                        name = "",
                        colorArgb = PROFILE_COLORS[profiles.size % PROFILE_COLORS.size],
                        avatar = PROFILE_AVATARS[profiles.size % PROFILE_AVATARS.size]
                    ),
                    true
                )
            }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // Centred under the avatars above, so the column of icons reads as one.
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Add, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text("Add a kid", color = MaterialTheme.colorScheme.primary)
    }
}

/** The screen-time rules a kid's page can set, each one either set or not. */
internal const val KID_RULE_COUNT = 5

/**
 * How many of the five per-kid rules are set. Blocked windows and the pause
 * are not rules in this sense — a window is a schedule, the pause is today's
 * state — so the count stays "N of 5" whatever else the kid has.
 */
internal fun rulesSet(l: Limits): Int = listOf(
    l.sessionMinutes, l.weekdaySessions, l.weekendSessions, l.breakMinutes, l.minVideoMinutes
).count { it != null }

/**
 * "Age 7 · 2 of 5 rules set · no profile code" — the three things a parent
 * checks a kid's row for, always all three, so the rows line up and a missing
 * one reads as a gap to fill rather than as nothing to say.
 */
internal fun kidSummary(p: Profile): String = listOf(
    p.age?.let { "Age $it" } ?: "No age set",
    "${rulesSet(p.limits)} of $KID_RULE_COUNT rules set",
    if (p.pin != null) "profile code set" else "no profile code"
).joinToString(" \u00B7 ")

// ---------------------------------------------------------------------------
// Reusable per-kid widgets
// ---------------------------------------------------------------------------

/**
 * One row of name chips showing which kids something applies to — scrolls
 * sideways so a big family never wraps into a broken layout. Tap toggles a
 * kid; the empty set means "everyone" (matching the config convention), so
 * the last remaining kid can't be toggled off — a channel visible to no one
 * just looks broken. Avatars stay in the Kids section; everywhere else in the
 * parent settings, names alone read faster.
 */
@Composable
fun KidToggleChips(
    profiles: List<Profile>,
    selectedIds: Set<String>,
    onChanged: (Set<String>) -> Unit
) {
    val effective = selectedIds.ifEmpty { profiles.map { it.id }.toSet() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        profiles.forEach { profile ->
            val on = profile.id in effective
            FilterChip(
                modifier = Modifier.tvFocusHighlight(),
                selected = on,
                onClick = {
                    val next = if (on) effective - profile.id else effective + profile.id
                    if (next.isEmpty()) return@FilterChip
                    // Collapse back to "everyone" so future kids are included.
                    onChanged(if (next.size == profiles.size) emptySet() else next)
                },
                label = { Text(profile.name) }
            )
        }
    }
}

/** Selector row (exactly one kid active) — screen-time editor, grants. */
@Composable
fun KidSelectorChips(
    profiles: List<Profile>,
    selectedId: String,
    onSelect: (String) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        profiles.forEach { profile ->
            FilterChip(
                modifier = Modifier.tvFocusHighlight(),
                selected = profile.id == selectedId,
                onClick = { onSelect(profile.id) },
                label = { Text(profile.name) }
            )
        }
    }
}

/**
 * "Who is this for?" — checkbox per kid, empty result meaning everyone.
 * Reused by add-channel, folder linking and the AI queue's long-press.
 */
@Composable
fun WhoForDialog(
    title: String,
    profiles: List<Profile>,
    initialIds: Set<String>,
    confirmLabel: String = "OK",
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    var checked by remember {
        mutableStateOf(initialIds.ifEmpty { profiles.map { it.id }.toSet() })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Reachable from a hold in the screening log, so the tail of that
            // hold must not tick a kid on the way in.
            Column(modifier = Modifier.ignoreSelectUntilRelease()) {
                profiles.forEach { profile ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                checked = if (profile.id in checked) checked - profile.id
                                else checked + profile.id
                            }
                    ) {
                        Checkbox(
                            checked = profile.id in checked,
                            onCheckedChange = { on ->
                                checked = if (on) checked + profile.id else checked - profile.id
                            }
                        )
                        Text(profile.name)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = checked.isNotEmpty(),
                onClick = {
                    onConfirm(if (checked.size == profiles.size) emptySet() else checked)
                },
                // Sits outside the column above, and a ruling confirmed by a
                // stray release is the worst thing this dialog could do.
                modifier = Modifier.ignoreSelectUntilRelease()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
