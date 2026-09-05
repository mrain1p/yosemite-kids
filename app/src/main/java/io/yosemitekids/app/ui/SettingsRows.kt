package io.yosemitekids.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
 * See docs/design/parent-settings/screens/raw-playback.png and
 * raw-screening.png.
 */

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
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (help != null) {
                HelpDot(open = helpOpen, onToggle = { helpOpen = !helpOpen })
                Spacer(Modifier.width(10.dp))
            }
            Switch(
                modifier = Modifier.tvFocusHighlight(),
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
        if (help != null && helpOpen) {
            Spacer(Modifier.height(8.dp))
            Text(
                help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    /** The value in a tone — amber for a count waiting on the parent (raw-digest.png). */
    valueColor: androidx.compose.ui.graphics.Color? = null
) {
    val base = Modifier.fillMaxWidth()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(vertical = 12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = summaryColor ?: MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                YosemiteIcons.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The **?** itself.
 *
 * A 28dp circle rather than an [androidx.compose.material3.IconButton]'s 48:
 * it sits inline beside a switch, and the default touch target pushed the
 * switch off the row on a 344dp screen. Still comfortably tappable, and the
 * whole row is not clickable so there is nothing else here to hit by mistake.
 */
@Composable
internal fun HelpDot(open: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(28.dp).clickable(onClick = onToggle).tvFocusHighlight()
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (open) "×" else "?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
