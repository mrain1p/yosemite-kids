package io.yosemitekids.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistParser
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The list's way in and out: save or share the current list (including unsaved
 * edits) as a whitelist.txt, read one back in, or offer the whole list to the
 * community directory. [current] is a provider rather than a value so the file
 * written is whatever is on screen at the moment the row is tapped.
 * [onImport] merges parsed links into the form and returns how many were new.
 *
 * Renders its own titled cards (raw-backup.png), so the caller places it
 * rather than wrapping it.
 */
@Composable
internal fun ExportSection(
    current: () -> Whitelist,
    onImport: (Whitelist) -> Int,
    /** A full-backup restore replaced config.json; the form must reload from disk. */
    onConfigReplaced: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    /** The full-backup card's own line: a "Saved" under Share… would answer the wrong row. */
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var lastBackupAt by remember { mutableStateOf(io.yosemitekids.app.data.Backup.lastExportedAt(context)) }
    var submitting by remember { mutableStateOf(false) }
    var askingLang by remember { mutableStateOf(false) }
    /** A restore awaiting the parent's OK: the file's contents and its summary. */
    var pendingRestore by remember {
        mutableStateOf<Pair<String, io.yosemitekids.app.data.Backup.Summary>?>(null)
    }

    val backupLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupMessage = "Writing backup…"
            backupMessage = runCatching {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val text = io.yosemitekids.app.data.Backup.export(context)
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray())
                    } ?: error("couldn't open the file")
                    // Stamped only now, with the bytes on disk.
                    io.yosemitekids.app.data.Backup.noteExported(context)
                }
                lastBackupAt = io.yosemitekids.app.data.Backup.lastExportedAt(context)
                "Backup saved ✓ — keep it somewhere safe (it holds no API key)"
            }.getOrElse { "Backup failed: ${it.message}" }
        }
    }

    val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            backupMessage = "Reading…"
            runCatching {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = ByteArray(MAX_BACKUP_BYTES + 1)
                        var n = 0
                        while (n < bytes.size) {
                            val r = input.read(bytes, n, bytes.size - n)
                            if (r <= 0) break
                            n += r
                        }
                        check(n <= MAX_BACKUP_BYTES) { "that file is too big to be a backup" }
                        String(bytes, 0, n)
                    } ?: error("couldn't open the file")
                    text to io.yosemitekids.app.data.Backup.inspect(text).getOrThrow()
                }
            }.onSuccess { (text, summary) ->
                backupMessage = null
                pendingRestore = text to summary
            }.onFailure { backupMessage = "Restore failed: ${it.message}" }
        }
    }

    pendingRestore?.let { (text, summary) ->
        val when_ = if (summary.exportedAt > 0) {
            java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(summary.exportedAt))
        } else "an unknown date"
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Made $when_: ${summary.channels} channel(s)/playlist(s), " +
                        "${summary.kids} kid profile(s), ${summary.verdicts} AI verdict(s). " +
                        "This phone's channels, kids, rules, blocks and safe-list will be " +
                        "replaced by the backup; watch history and favourites are merged in. " +
                        "Push to the kid devices afterwards."
                )
            },
            confirmButton = {
                Button(onClick = {
                    pendingRestore = null
                    scope.launch {
                        backupMessage = "Restoring…"
                        val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            io.yosemitekids.app.data.Backup.restore(context, text)
                        }
                        backupMessage = result.fold(
                            { "Restored ✓ — now Push to each kid device" },
                            { "Restore failed: ${it.message}" }
                        )
                        if (result.isSuccess) onConfigReplaced()
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } }
        )
    }

    fun exportText(): String {
        val today = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US)
            .format(java.util.Date())
        return io.yosemitekids.app.data.WhitelistExporter.toText(current(), today)
    }

    val saveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        message = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(exportText().toByteArray())
            } ?: error("couldn't open the file")
            "Saved ✓"
        }.getOrElse { "Save failed: ${it.message}" }
    }

    val openLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = "Reading…"
            runCatching {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        // Bounded like every other read from something we don't
                        // control: the picker can hand back any file at all, and
                        // a multi-gigabyte one must not be pulled into memory.
                        val bytes = ByteArray(MAX_IMPORT_BYTES + 1)
                        var n = 0
                        while (n < bytes.size) {
                            val r = input.read(bytes, n, bytes.size - n)
                            if (r <= 0) break
                            n += r
                        }
                        check(n <= MAX_IMPORT_BYTES) { "that file is too big" }
                        String(bytes, 0, n)
                    } ?: error("couldn't open the file")
                    // Parsing regexes a megabyte line-by-line — that stays off
                    // main too; only the form merge lands there.
                    WhitelistParser.parse(text)
                }
            }.onSuccess { parsed ->
                val added = onImport(parsed)
                message = if (added > 0) {
                    "Added $added new channel(s)/playlist(s) — review them below (tagged NEW)"
                } else "Nothing new in that file"
            }.onFailure { message = "Import failed: ${it.message}" }
        }
    }

    if (askingLang) {
        SubmitToDirectoryDialog(
            count = current().sources.size,
            onDismiss = { askingLang = false },
            onConfirm = { lang ->
                askingLang = false
                submitting = true
                message = "Submitting…"
                val sources = current().sources
                val appContext = context.applicationContext
                // LanPushScope, not the composition scope: a long list outlives
                // Save & close, and half-sent-then-silently-dropped is the one
                // outcome a parent can't diagnose. The toast survives the
                // screen; the inline message covers it while it's still open.
                io.yosemitekids.app.data.LanPushScope.scope.launch {
                    val result = runCatching {
                        io.yosemitekids.app.data.DirectorySubmitter.submit(sources, lang)
                    }.map { io.yosemitekids.app.data.DirectorySubmitter.summarize(it) }
                        .getOrElse { "Couldn't reach the directory — check the connection and try again." }
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        submitting = false
                        message = result
                        android.widget.Toast.makeText(
                            appContext, result, android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        )
    }

    // raw-backup.png: two titled cards of rows, each card's paragraph behind
    // a ? on its title. The paragraphs used to print always, above two rows
    // of buttons that never fit one phone width.
    SectionTitle(
        "The channel list",
        help = "Save the channel list as a file — keep it as a backup, or send it to " +
            "another parent. Import a saved file to add its channels here. Or offer " +
            "the whole list to the shared Yosemite Kids directory, where other families " +
            "can find it after review."
    )
    SettingsCard(padded = false) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            ValueRow("Share…", onClick = {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Yosemite Kids whitelist")
                    putExtra(android.content.Intent.EXTRA_TEXT, exportText())
                }
                runCatching {
                    context.startActivity(
                        android.content.Intent.createChooser(send, "Share whitelist")
                    )
                }.onFailure { message = "No app available to share with" }
            })
            SettingsDivider()
            ValueRow("Save to file…", onClick = {
                runCatching { saveLauncher.launch("yosemite-kids-whitelist.txt") }
                    .onFailure { message = "No file picker on this device" }
            })
            SettingsDivider()
            ValueRow("Import from file…", onClick = {
                // Exports are text/plain, but pickers on some devices type a .txt
                // from a share or a download as octet-stream and would hide it.
                runCatching {
                    openLauncher.launch(
                        arrayOf("text/plain", "text/*", "application/octet-stream")
                    )
                }.onFailure { message = "No file picker on this device" }
            })
            SettingsDivider()
            // A row has no disabled state the way the old button did, so the
            // reason it would have been disabled is said on the row instead.
            val empty = current().sources.isEmpty()
            ValueRow(
                if (submitting) "Submitting…" else "Submit list to directory…",
                summary = if (empty && !submitting) "Nothing to submit yet" else null,
                onClick = { if (!submitting && !empty) askingLang = true }
            )
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp))
            }
        }
    }

    SectionTitle(
        "Full backup",
        help = "Everything on this phone — channels, kids and their rules, " +
            "blocked and allowed videos, AI screening settings without the key, " +
            "every kid's resume points and favourites, and the AI verdict cache. " +
            "Restore it on a fresh install and push to the kid devices."
    )
    SettingsCard(padded = false) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            // "Never backed up" in the warning tone: it is the state the
            // root raises this page for. Once one exists, the date instead.
            ValueRow(
                "Full backup…",
                summary = if (lastBackupAt > 0L) {
                    "Last backup " + java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                        .format(java.util.Date(lastBackupAt))
                } else "Never backed up",
                summaryColor = if (lastBackupAt > 0L) null else WarningAmber,
                onClick = {
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                        .format(java.util.Date())
                    runCatching { backupLauncher.launch("yosemite-kids-backup-$stamp.json") }
                        .onFailure { backupMessage = "No file picker on this device" }
                }
            )
            SettingsDivider()
            ValueRow("Restore backup…", onClick = {
                runCatching {
                    restoreLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                }.onFailure { backupMessage = "No file picker on this device" }
            })
            backupMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp))
            }
        }
    }
}

/** A whitelist.txt is a few kB; anything near this is not one. */
private const val MAX_IMPORT_BYTES = 1024 * 1024

/** Config + verdict cache + every kid's history: a busy family is well under this. */
private const val MAX_BACKUP_BYTES = 16 * 1024 * 1024

/**
 * Consent gate for the directory submission: the list stops being private the
 * moment it is sent, so say plainly what leaves and what happens next before
 * asking for the one thing the worker can't infer — the channels' language.
 */
@Composable
private fun SubmitToDirectoryDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var lang by remember { mutableStateOf(java.util.Locale.getDefault().language) }
    val valid = io.yosemitekids.app.data.DirectorySubmitter.isValidLang(lang.trim().lowercase())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Submit your list to the directory?") },
        text = {
            Column {
                Text(
                    "All $count channel(s) and playlist(s) in your list are sent for " +
                        "review. Duplicates are weeded out before anything is published: " +
                        "anything already in the directory — or already waiting for " +
                        "review — is skipped, so if you submitted last week, only what " +
                        "you've added since goes through. Each new one becomes a public " +
                        "suggestion: checked by AI, then by a person, before it appears " +
                        "in the shared directory where other families can add it. The " +
                        "review queue holds about 50 suggestions at a time — if your " +
                        "list is longer, just submit again later to send the rest. " +
                        "Nothing about you or your kids is sent — only the links."
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = lang,
                    onValueChange = { lang = it },
                    label = { Text("Language of these channels") },
                    placeholder = { Text("2-letter language code (en, es, …)") },
                    singleLine = true,
                    isError = !valid
                )
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = { onConfirm(lang.trim().lowercase()) }) {
                Text("Submit")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
