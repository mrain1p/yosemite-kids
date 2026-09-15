package io.yosemitekids.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yosemitekids.app.data.HomeRow
import io.yosemitekids.app.data.HomeRows
import io.yosemitekids.app.data.Profile

/**
 * The home-screen row editor: which shelves one kid's home shows, and in
 * what order.
 *
 * The pinned hero's editor one card down, and the same shape for the same
 * reasons: **every edit goes through [HomeRows.withOrder]** rather than
 * building a [HomeRow] here, so the rank spacing, the catalogue check and
 * "the default, everything on, is no rows at all" are one set of rules the
 * phone and the hub's console agree on (guard 70). The hub's page is the
 * other writer — both declared once, as `listing-rows` in `SettingsSurface`.
 *
 * What a parent sees is what the kid will see: [HomeRows.sections] is the
 * same reconcile the home screen draws from, so a shelf this build has and
 * the saved order never named is already in its place here, switched on.
 *
 * There is no Save. Like every other control on these pages the edit lands
 * in the form's state and the screen's debounced auto-save writes it,
 * stamps it per row, and pushes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeRowsEditor(
    profiles: List<Profile>,
    homeRows: List<HomeRow>,
    onRows: (List<HomeRow>) -> Unit
) {
    // Whose home. Resolved rather than trusted, and remembered without a key,
    // for the reason PinnedHeroEditor gives.
    var kidId by remember { mutableStateOf<String?>(null) }
    val who = profiles.firstOrNull { it.id == kidId } ?: profiles.firstOrNull()
    val forKid = who?.id

    val sections = HomeRows.sections(homeRows, forKid)
    val isDefault = HomeRows.rowsOf(homeRows, forKid).isEmpty()

    fun set(order: List<HomeSection>) =
        onRows(HomeRows.withOrder(homeRows, forKid, order))

    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(ctl("listing-rows").label, style = MaterialTheme.typography.bodyMedium)
        Text(
            ctl("listing-rows").sub,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (profiles.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                profiles.forEach { p ->
                    SegmentChip(p.name, selected = p.id == forKid, onClick = { kidId = p.id })
                }
            }
        }

        sections.forEachIndexed { i, section ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            ) {
                Text(
                    "${i + 1}.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = SettingsPlaceholder,
                    modifier = Modifier.width(20.dp)
                )
                Column(Modifier.weight(1f).padding(end = 4.dp)) {
                    Text(
                        homeShelfLabel(section.id),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = if (section.enabled) MaterialTheme.colorScheme.onSurface else SettingsPlaceholder,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!section.enabled) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Hidden on this home",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = SettingsPlaceholder
                        )
                    }
                }
                MoveButton(YosemiteIcons.ExpandLess, "Move up", i > 0) {
                    set(sections.swappedAt(i, i - 1))
                }
                MoveButton(YosemiteIcons.ExpandMore, "Move down", i < sections.lastIndex) {
                    set(sections.swappedAt(i, i + 1))
                }
                CompactButton(onClick = {
                    set(sections.map { if (it.id == section.id) it.copy(enabled = !it.enabled) else it })
                }) {
                    Text(if (section.enabled) "Hide" else "Show", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (isDefault) "This is the default: every shelf, in this order."
                else "The default is every shelf, all shown, in the order a new install gets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (!isDefault) {
                TextButton(modifier = Modifier.tvFocusHighlight(), onClick = { set(emptyList()) }) {
                    Text("Reset to default")
                }
            }
        }
    }
}

/** [i] and [j] exchanged: the whole of a move, expressed as the order it leaves. */
private fun <T> List<T>.swappedAt(i: Int, j: Int): List<T> =
    toMutableList().also { it[i] = this[j]; it[j] = this[i] }
