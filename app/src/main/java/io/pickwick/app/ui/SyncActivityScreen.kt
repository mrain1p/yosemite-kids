package io.pickwick.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.ConfigMerge
import io.pickwick.app.data.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Recent changes: who changed what, and when.
 *
 * There is nothing like this today, so "why did the TV change?" and "did my
 * edit actually stick?" are simply unanswerable — a parent's only recourse is
 * to compare two screens by eye. The log rides inside the config, so this
 * shows a co-parent's edits as soon as any device has carried them here.
 *
 * Read-only, and deliberately unbadged: it is a place to look when something
 * surprises you, not a notification to clear.
 */
@Composable
fun SyncActivityScreen(
    configStore: ConfigStore,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val entries by produceState<List<ConfigMerge.Change>?>(null) {
        value = withContext(Dispatchers.IO) { configStore.load().sync.log.reversed() }
    }

    SubPage("Recent changes", onBack) {
        Text(
            "Settings changes made on any phone in the family, newest first. " +
                "Times are the clock of the phone that made the change.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))

        val log = entries
        when {
            log == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            log.isEmpty() -> SettingsCard {
                Text("Nothing yet.")
                Text(
                    "Changes show up here once someone edits these settings. A family " +
                        "that has just updated starts with an empty list.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> SettingsCard(padded = false) {
                log.forEachIndexed { i, c ->
                    if (i > 0) SettingsDivider()
                    ChangeRow(c)
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(c: ConfigMerge.Change) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            // "Dad's phone changed Emma's rules" reads as one sentence, which
            // is the only form a parent scans without effort.
            listOf(who(c), c.text).filter { it.isNotBlank() }.joinToString(" "),
            style = MaterialTheme.typography.bodyLarge
        )
        // shownAt, not at: the stamp is forced monotonic so a device with a
        // wrong clock can still win an edit, and showing that value would
        // present a parent with a time that never happened.
        changeAge(c.shownAt.takeIf { it > 0 } ?: c.at)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The device that made the change, or nothing at all rather than a placeholder. */
private fun who(c: ConfigMerge.Change): String = c.who.trim()

/**
 * How long ago a change was made, at the granularity this screen needs.
 *
 * Deliberately not [relativeAge], which is built for video ages and answers
 * "today" for anything inside a day — useless here, where the question is
 * usually whether a co-parent's edit landed minutes ago or last week.
 *
 * A future stamp reads as "just now" rather than as a negative age: it means
 * the minting device's clock is ahead, which is the clock notice's problem to
 * report and not something to spell out in a list row.
 */
internal fun changeAge(stamp: Long, now: Long = System.currentTimeMillis()): String? {
    if (stamp <= 0) return null
    val mins = (now - stamp) / 60_000L
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 24 * 60 -> "${mins / 60}h ago"
        mins < 7 * 24 * 60 -> "${mins / (24 * 60)}d ago"
        else -> "${mins / (7 * 24 * 60)}w ago"
    }
}

/**
 * The newest line, for the Settings row: "Dad's phone added SciShow Kids ·
 * 2h ago". Null when there is nothing to say, so the row stays quiet rather
 * than showing an empty state a parent has to interpret.
 */
internal fun latestChangeLine(
    log: List<ConfigMerge.Change>,
    now: Long = System.currentTimeMillis()
): String? {
    val c = log.lastOrNull() ?: return null
    val head = listOf(c.who.trim(), c.text).filter { it.isNotBlank() }.joinToString(" ")
    val age = changeAge(c.shownAt.takeIf { it > 0 } ?: c.at, now)
    return if (age != null) "$head · $age" else head.ifBlank { null }
}
