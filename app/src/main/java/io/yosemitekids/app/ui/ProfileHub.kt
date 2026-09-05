package io.yosemitekids.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.yosemitekids.app.data.PROFILE_AVATARS
import io.yosemitekids.app.data.PROFILE_COLORS
import io.yosemitekids.app.data.Profile

/**
 * Behind the avatar in the top-right corner: the kid's own corner of the
 * app. Switch who's watching (shared devices), change their look, and the
 * one door to the parent settings, with a lock on it so it reads as "not
 * for you" rather than as a broken button. One dialog for both form
 * factors — its rows are plain focusable buttons, so the remote walks them.
 */
@Composable
internal fun ProfileHubDialog(
    profile: Profile?,
    onSwitch: (() -> Unit)?,
    onChangeLook: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    theme: String = THEME_DARK,
    onTheme: ((String) -> Unit)? = null,
    /** Fetch new videos and sync settings now, rather than waiting for the poll. */
    onRefresh: (() -> Unit)? = null,
    /** Something is already in flight, so the row says so instead of inviting a second tap. */
    busy: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (profile != null) {
                    ProfileAvatar(profile, size = 44)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    profile?.name ?: "Yosemite Kids",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        text = { HubRows(onSwitch, onChangeLook, onOpenSettings, theme, onTheme, onRefresh, onDismiss, busy) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.tvFocusHighlight()) { Text("Close") } }
    )
}

/**
 * The phone's version of the hub: a bottom sheet, the way every phone app
 * hangs an account menu off its avatar. Same rows as the TV dialog.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileHubSheet(
    profile: Profile?,
    onSwitch: (() -> Unit)?,
    onChangeLook: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    theme: String = THEME_DARK,
    onTheme: ((String) -> Unit)? = null,
    /** Fetch new videos and sync settings now, rather than waiting for the poll. */
    onRefresh: (() -> Unit)? = null,
    /** Something is already in flight, so the row says so instead of inviting a second tap. */
    busy: Boolean = false
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                if (profile != null) {
                    ProfileAvatar(profile, size = 48)
                    Spacer(Modifier.width(14.dp))
                }
                Text(profile?.name ?: "Yosemite Kids", style = MaterialTheme.typography.titleLarge)
            }
            HubRows(onSwitch, onChangeLook, onOpenSettings, theme, onTheme, onRefresh, onDismiss, busy)
        }
    }
}

@Composable
private fun HubRows(
    onSwitch: (() -> Unit)?,
    onChangeLook: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    theme: String = THEME_DARK,
    onTheme: ((String) -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    busy: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The look of the whole app, at the top of the kid's own corner
        // rather than buried in the parent's settings — it is theirs.
        if (onTheme != null) {
            Text(
                "Theme",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
            ) {
                KID_THEMES.forEach { t ->
                    YosemiteChip(themeLabel(t), selected = theme == t, onClick = { onTheme(t) })
                }
            }
        }
        if (onRefresh != null) {
            HubRow(
                YosemiteIcons.History,
                if (busy) "Checking for new videos…" else "Check for new videos",
                { if (!busy) { onRefresh(); onDismiss?.invoke() } }
            )
        }
        if (onSwitch != null) HubRow(YosemiteIcons.People, "Switch who's watching", onSwitch)
        if (onChangeLook != null) HubRow(YosemiteIcons.Palette, "Change my look", onChangeLook)
        HubRow(Icons.Filled.Settings, "Parent settings", onOpenSettings, locked = true)
    }
}

@Composable
private fun HubRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    locked: Boolean = false
) {
    val fg = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusHighlight()
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 14.dp)
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(26.dp))
            // The lock on the gear's shoulder: small, but there — this door
            // reads as "not for you" rather than as a broken button.
            if (locked) Icon(
                Icons.Filled.Lock, contentDescription = "Locked",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).size(14.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = fg)
    }
}

/**
 * The colour dots and the avatar grid — the same picker the parent's kid
 * page uses, so a kid restyling themselves and a parent doing it for them
 * choose from one set. Every cell is a focusable click target for the TV.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LookPicker(
    color: Long,
    avatar: String,
    onColor: (Long) -> Unit,
    onAvatar: (String) -> Unit
) {
    Text("Color", style = MaterialTheme.typography.labelLarge)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PROFILE_COLORS.forEach { c ->
            Box(
                Modifier
                    .size(36.dp)
                    .tvFocusHighlight()
                    .clip(CircleShape)
                    .background(Color(c))
                    .clickable { onColor(c) },
                contentAlignment = Alignment.Center
            ) {
                if (c == color) Text("✓", color = Color.White)
            }
        }
    }
    Text("Avatar", style = MaterialTheme.typography.labelLarge)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        PROFILE_AVATARS.forEach { a ->
            ProfileAvatar(
                Profile(id = "preview", name = a, colorArgb = color, avatar = a),
                size = 44,
                modifier = Modifier
                    .tvFocusHighlight()
                    .clip(CircleShape)
                    .clickable { onAvatar(a) }
                    .let { m ->
                        if (a == avatar) m.background(
                            MaterialTheme.colorScheme.primary, CircleShape
                        ).padding(2.dp) else m
                    }
            )
        }
    }
}

/** "Change my look": the picker with a live preview, applied on Done. */
@Composable
internal fun LookDialog(
    profile: Profile,
    onDone: (avatar: String, colorArgb: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var color by remember { mutableStateOf(profile.colorArgb) }
    var avatar by remember { mutableStateOf(profile.avatar) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(profile.copy(colorArgb = color, avatar = avatar), size = 56)
                Spacer(Modifier.width(14.dp))
                Text("This is me!", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                LookPicker(color, avatar, onColor = { color = it }, onAvatar = { avatar = it })
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            Button(onClick = { onDone(avatar, color) }, modifier = Modifier.tvFocusHighlight()) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.tvFocusHighlight()) { Text("Cancel") }
        }
    )
}
