package io.yosemitekids.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.sp
import io.yosemitekids.app.data.SettingsControl
import io.yosemitekids.app.data.SettingsSurface

/**
 * The two rows the redesigned settings pages are built from.
 *
 * Every page was previously a switch with a paragraph of explanation printed
 * underneath it, always, for every control. Four switches meant four
 * paragraphs, so the page a parent scanned for one toggle was mostly prose
 * they had already read. The design keeps a one-line summary and folds the
 * paragraph behind a **?** — the explanation stays available and stops being
 * the page.
 *
 * Both rows carry their own vertical padding and minimum height so a
 * [SettingsDivider] between two of them can run the full width of the card
 * they sit in, edge to edge, the way the design draws it.
 *
 * See docs/design/parent-settings/screens/full-10-playback.png and
 * full-07-screening.png.
 */

/**
 * The words for one control, from the manifest both faces read.
 *
 * A label typed here and typed again in `index.html` is two labels, and they
 * drifted: "Time per session" on the phone was "Minutes a session" on the hub,
 * and the hub offered page sizes and quality steps this app has never had.
 * Taking the words from [SettingsSurface] is also what makes guard 26(c) real
 * rather than ceremonial — a control the phone does not name is a control the
 * phone cannot render, so the reference is load-bearing.
 *
 * Spelled `ctl("id")` on purpose: the guard reads that shape out of these
 * files, in both directions — every control the manifest declares for this
 * face is asked for somewhere, and every id asked for is declared.
 */
internal fun ctl(id: String): SettingsControl = SettingsSurface.control(id)

/** A switch with a summary line, and its full explanation behind a **?**. */
@Composable
internal fun ToggleRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    help: String? = null
) {
    var helpOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(Modifier.weight(1f).padding(end = 4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (help != null) HelpDot(open = helpOpen, onToggle = { helpOpen = !helpOpen })
            Switch(
                modifier = Modifier.tvFocusHighlight(),
                checked = checked,
                onCheckedChange = onCheckedChange,
                // The design's switch: no outline on either state, and the
                // off track a border grey rather than Material's lit one.
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
        if (help != null && helpOpen) {
            // No spacer above: the row's own bottom padding is the gap.
            Text(
                help,
                style = MaterialTheme.typography.bodySmall
                    .copy(fontSize = 12.5.sp, lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 13.dp)
            )
        }
    }
}

/**
 * A row that states a value, and optionally pushes to a page.
 *
 * Trailing text rather than a control: "4 blocked", "not screened",
 * "4 rules". The value is the answer a parent came for, so it is on the row
 * rather than one tap inside it.
 */
@Composable
internal fun ValueRow(
    title: String,
    summary: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    /** The summary in a tone — amber for "Never backed up" — rather than the quiet grey. */
    summaryColor: androidx.compose.ui.graphics.Color? = null,
    /** The value in a tone — amber for a count waiting on the parent (full-14-digest.png). */
    valueColor: androidx.compose.ui.graphics.Color? = null
) {
    val base = Modifier.fillMaxWidth()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            // heightIn before the padding, so the minimum is the row's outer
            // height: 15sp over 12sp does not reach it on its own.
            .heightIn(min = if (summary == null) 56.dp else 64.dp)
            .padding(vertical = 9.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium
                    .copy(fontSize = 14.5.sp, lineHeight = 19.sp)
            )
            if (summary != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = summaryColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                YosemiteIcons.ChevronRight, contentDescription = null,
                tint = SettingsPlaceholder,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * The **?** itself: a 21 dp ring, hollow, in a 44 dp target.
 *
 * The two sizes are the point. 44 dp is the floor for every target in the
 * design, but a 44 dp *layout* slot pushes the switch off a 344 dp row — so
 * the ring reports 21 dp of layout and keeps 44 dp of touch, the way
 * Material's own minimum-touch-target expansion does. Nothing else on these
 * rows is clickable, so the overhang has nothing to steal.
 */
@Composable
internal fun HelpDot(open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(21.dp)
            .wrapContentSize(align = Alignment.Center, unbounded = true)
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .tvFocusHighlight(cornerRadius = 8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(21.dp).border(1.dp, SettingsStrongBorder, CircleShape)
        ) {
            Text(
                if (open) "×" else "?",
                style = MaterialTheme.typography.labelSmall
                    .copy(fontSize = 11.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
