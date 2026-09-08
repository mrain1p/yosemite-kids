package io.yosemitekids.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yosemitekids.app.data.Pin
import io.yosemitekids.app.data.Pins
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.WhitelistEntry

/**
 * The pinned-hero editor: which two or three channels sit at the top of one
 * kid's home screen, and in what order.
 *
 * The container shipped a release ahead of this (see `Pins.kt`), so every
 * device in a household is already carrying, stamping and merging the list
 * by the time anything can write to it. This is the writer, and the hub's
 * "How videos are listed" page is the other one — both declared once, as
 * `listing-pins` in `SettingsSurface`.
 *
 * **Every edit goes through [Pins.withRow]** rather than building a [Pin]
 * here. That is not tidiness: the row's cap, the fail-closed filter and the
 * rank arithmetic are one set of rules that two faces have to agree on, and
 * the failure mode of a second copy is not a crash but a home screen whose
 * order differs between the television and the NAS. Guard 42 keeps this file
 * from minting a card of its own.
 *
 * There is no Save. Like every other control on these pages the edit lands
 * in the form's state and the screen's debounced auto-save writes it, stamps
 * it per card, and pushes it — see `AdminScreen`'s `buildCurrentConfig`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PinnedHeroEditor(
    entries: List<WhitelistEntry>,
    profiles: List<Profile>,
    resolvedNames: Map<String, String>,
    pins: List<Pin>,
    onPins: (List<Pin>) -> Unit
) {
    // Whose row is being edited. A household with no kids has exactly one —
    // the family's own, keyed null, which is what `Whitelist.pinsFor`
    // resolves for a device where nobody has been picked.
    //
    // Resolved rather than trusted, and remembered without a key: the chip a
    // parent pressed can name a kid removed on another page of the same open
    // form, and every auto-save hands this composable a fresh profiles list —
    // which a keyed remember would read as a reason to jump back to the first
    // kid mid-edit.
    var kidId by remember { mutableStateOf<String?>(null) }
    val who = profiles.firstOrNull { it.id == kidId } ?: profiles.firstOrNull()
    val forKid = who?.id

    val row = Pins.rowOf(pins, forKid)
    val byId = entries.associateBy { it.id }
    fun displayName(id: String): String =
        byId[id]?.let { it.label ?: resolvedNames[it.url] ?: it.id } ?: id

    /** Rewrite this kid's row to [order]; every other kid's cards are untouched. */
    fun set(order: List<String>) = onPins(Pins.withRow(pins, forKid, order, entries))

    var adding by remember { mutableStateOf(false) }

    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(ctl("listing-pins").label, style = MaterialTheme.typography.bodyMedium)
        Text(
            ctl("listing-pins").sub,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // One row per kid, so a card is always pinned *for somebody*. Hidden
        // for a household with no profiles: there is one row and naming it
        // would be a choice a parent cannot make wrongly.
        if (profiles.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                profiles.forEach { p ->
                    SegmentChip(
                        p.name,
                        selected = p.id == forKid,
                        onClick = { kidId = p.id; adding = false }
                    )
                }
            }
        }

        if (row.isEmpty()) {
            Text(
                "Nothing pinned" + (who?.let { " for ${it.name}" } ?: "") +
                    ". Their home opens straight into the channel rows.",
                style = MaterialTheme.typography.bodySmall,
                color = SettingsPlaceholder
            )
        }
        row.forEachIndexed { i, pin ->
            PinnedCardRow(
                position = i + 1,
                name = displayName(pin.sourceId),
                kind = byId[pin.sourceId]?.kind,
                // A card whose channel has since been restricted to another
                // kid stays listed, greyed, rather than disappearing: the
                // home screen already refuses to draw it (`resolvePins`), and
                // a parent needs to see the thing they are being told about.
                unreachable = byId[pin.sourceId]?.visibleTo(forKid) == false,
                canMoveUp = i > 0,
                canMoveDown = i < row.size - 1,
                onMoveUp = { set(row.map { it.sourceId }.swapped(i, i - 1)) },
                onMoveDown = { set(row.map { it.sourceId }.swapped(i, i + 1)) },
                onRemove = { set(row.map { it.sourceId } - pin.sourceId) }
            )
        }

        // The cap is the editor's and not the parser's, so it is spent here:
        // no way to add a fourth, and a row that arrived with four from some
        // future build keeps all four (`Pins.withRow`).
        if (Pins.atCap(row)) {
            Text(
                "Three is the most the home screen draws.",
                style = MaterialTheme.typography.bodySmall,
                color = SettingsPlaceholder
            )
        } else {
            // Only what this kid can actually see, which is the same join
            // `resolvePins` makes at draw time. Offering a sibling's channel
            // here would produce a pin that saves, syncs, and never appears.
            val offer = Pins.candidates(entries, forKid)
                .filterNot { e -> row.any { it.sourceId == e.id } }
            if (!adding) {
                CompactButton(onClick = { adding = true }, enabled = offer.isNotEmpty()) {
                    Text(if (offer.isEmpty()) "Nothing left to pin" else "Pin a channel")
                }
            } else {
                Text(
                    "Pick one" + (who?.let { " for ${it.name}" } ?: "") + ":",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                offer.forEach { e ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                adding = false
                                set(row.map { it.sourceId } + e.id)
                            }
                            .tvFocusHighlight(cornerRadius = 8.dp)
                            .heightIn(min = 44.dp)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            e.label ?: resolvedNames[e.url] ?: e.id,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (e.kind == SourceKind.PLAYLIST) "Playlist" else "Channel",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = SettingsPlaceholder
                        )
                    }
                }
                CompactButton(onClick = { adding = false }) { Text("Cancel") }
            }
        }
    }
}

/** One pinned card: where it sits, what it is, and the three things to do to it. */
@Composable
private fun PinnedCardRow(
    position: Int,
    name: String,
    kind: SourceKind?,
    unreachable: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
    ) {
        Text(
            "$position.",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
            color = SettingsPlaceholder,
            modifier = Modifier.width(20.dp)
        )
        Column(Modifier.weight(1f).padding(end = 4.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = if (unreachable) SettingsPlaceholder else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (unreachable) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Not shown — this kid can’t see it any more",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = SettingsPlaceholder
                )
            } else if (kind == SourceKind.PLAYLIST) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Playlist",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = SettingsPlaceholder
                )
            }
        }
        MoveButton(YosemiteIcons.ExpandLess, "Move up", canMoveUp, onMoveUp)
        MoveButton(YosemiteIcons.ExpandMore, "Move down", canMoveDown, onMoveDown)
        CompactButton(onClick = onRemove) {
            Text("Remove", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * A 44 dp target around an 18 dp chevron. Disabled rather than absent at the
 * ends of the row, so the two buttons stay in the same place on every row —
 * a control that moves under the finger is worse than one that is greyed.
 */
@Composable
private fun MoveButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val base = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (enabled) {
            base.clickable(onClick = onClick).tvFocusHighlight(cornerRadius = 8.dp)
        } else {
            base
        }
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else SettingsPlaceholder,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** [i] and [j] exchanged. The whole of a move, expressed as the order it leaves. */
private fun List<String>.swapped(i: Int, j: Int): List<String> =
    toMutableList().also { it[i] = this[j]; it[j] = this[i] }
