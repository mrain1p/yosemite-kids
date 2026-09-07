package io.yosemitekids.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.yosemitekids.app.data.DownloadEvents
import io.yosemitekids.app.data.DownloadService
import io.yosemitekids.app.data.DownloadStatus
import io.yosemitekids.app.data.DownloadStore
import io.yosemitekids.app.data.LocalLibrary
import kotlinx.coroutines.launch

// --- Offline downloads ------------------------------------------------------

/**
 * What offline downloads are, for the **?** on the "Offline downloads" title
 * (raw-phone.png). It used to open the card; a parent who came to change the
 * quality read it every time.
 */
internal const val DOWNLOADS_HELP =
    "The kid taps the download arrow on any video to ask for it offline. " +
        "Approved videos are saved to this device and play without internet — " +
        "good for car trips. Watching them still uses screen time. With AI " +
        "screening on, each request is deep-checked first; refused ones never " +
        "reach this list, they go under Blocked videos where you can overrule."

/**
 * The parent's side of offline downloads: approve or decline the kid's
 * requests, watch active downloads, pick the quality, and free up space.
 * No storage cap — usage is shown and cleanup is a deliberate delete here.
 */
@Composable
internal fun DownloadsSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { DownloadStore(context.applicationContext) }
    // Any change from the kid's taps or the service re-reads the queue live.
    val changes by DownloadEvents.changes.collectAsState()
    val entries = remember(changes) { store.entries() }
    val activeProgress by DownloadEvents.progress.collectAsState()
    var quality by remember { mutableIntStateOf(store.maxHeight) }

    // Android 13+: the first approval asks to show the progress notification.
    val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    fun approve(url: String) {
        store.approve(url)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        DownloadService.start(context)
    }

    // Label above rather than beside: three chips plus a two-word label do not
    // fit one line on a phone at display scale, and the label wrapped to two
    // lines against the chips. The chips share the row in thirds, so the
    // choice reads as one segmented control rather than three tags.
    Text("Download quality", style = MaterialTheme.typography.bodyMedium)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        listOf(480, 720, 1080).forEach { h ->
            val on = quality == h
            FilterChip(
                selected = on,
                onClick = { quality = h; store.maxHeight = h },
                label = {
                    Text("${h}p", modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                },
                // Outlined either way, the chosen one in the accent over its
                // 16% tint: M3's default selected chip is a solid fill, which
                // on this page reads as a button rather than a choice made.
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = SettingsTextSecondary,
                    selectedContainerColor = SettingsAccentTint,
                    selectedLabelColor = MaterialTheme.colorScheme.primary
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, if (on) MaterialTheme.colorScheme.primary else SettingsStrongBorder
                ),
                modifier = Modifier.weight(1f).tvFocusHighlight()
            )
        }
    }

    val requested = entries.filter { it.status == DownloadStatus.REQUESTED }
    val active = entries.filter {
        it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
    }
    val failed = entries.filter { it.status == DownloadStatus.FAILED }
    val done = entries.filter { it.status == DownloadStatus.DONE }

    if (requested.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Waiting for your OK", style = MaterialTheme.typography.titleSmall)
        requested.forEach { e ->
            DownloadRow(e, subtitle = e.video.channelName) {
                CompactButton(
                    onClick = { approve(e.video.url) }
                ) { Text("Approve") }
                CompactButton(
                    onClick = { store.remove(e.video.url) }
                ) { Text("No") }
            }
        }
    }

    if (active.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Downloading", style = MaterialTheme.typography.titleSmall)
        active.forEach { e ->
            val fraction = activeProgress?.takeIf { it.first == e.video.url }?.second
            val label = when {
                fraction != null -> "${(fraction * 100).toInt()}%"
                else -> "queued"
            }
            DownloadRow(e, subtitle = label) {
                IconButton(onClick = { store.remove(e.video.url) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Cancel download")
                }
            }
        }
    }

    if (failed.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Didn't finish", style = MaterialTheme.typography.titleSmall)
        failed.forEach { e ->
            DownloadRow(e, subtitle = e.error ?: "Download failed") {
                CompactButton(
                    onClick = { approve(e.video.url) }
                ) { Text("Retry") }
                IconButton(onClick = { store.remove(e.video.url) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove")
                }
            }
        }
    }

    if (done.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("On this device", style = MaterialTheme.typography.titleSmall)
        done.forEach { e ->
            val size = remember(changes, e.video.url) {
                e.video.videoId?.let { store.sizeOf(it) } ?: 0L
            }
            DownloadRow(e, subtitle = formatBytes(size)) {
                IconButton(onClick = { store.remove(e.video.url) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete download")
                }
            }
        }
        Text(
            "Total: ${formatBytes(remember(changes) { store.totalSize() })}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (entries.isEmpty()) {
        Text(
            "No requests yet",
            style = MaterialTheme.typography.bodySmall,
            color = SettingsTextTertiary
        )
    }
}

/** One video in the downloads lists: thumb, title/subtitle, trailing actions. */
@Composable
private fun DownloadRow(
    entry: DownloadStore.Entry,
    subtitle: String,
    actions: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        AsyncImage(
            model = entry.video.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.width(72.dp).height(40.dp).clip(MaterialTheme.shapes.small)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.video.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}


// --- Videos from this phone --------------------------------------------------

/**
 * Parent links videos already on this phone into the kid's Downloads shelf.
 * SAF only: the files stay where they are, Yosemite Kids stores links. Folders are
 * the recommended path (one permission grant covers everything inside, and a
 * rescan picks up new files); single-file picking exists for one-offs.
 */
@Composable
internal fun LocalVideosSection(profiles: List<io.yosemitekids.app.data.Profile> = emptyList()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val library = remember { LocalLibrary(context.applicationContext) }
    // Local edits ride the downloads change signal — same shelf, same refresh.
    val changes by DownloadEvents.changes.collectAsState()
    val trees = remember(changes) { library.trees() }
    val items = remember(changes) { library.items() }
    val scope = rememberCoroutineScope()
    // Thumbnail/metadata extraction is ~0.1s per file: a folder of episodes
    // needs a narrated scan, not a frozen section.
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    /** A picked folder awaiting the "who can watch these?" answer. */
    var pendingTree by remember { mutableStateOf<android.net.Uri?>(null) }

    fun rescan() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            library.rescan { done, total -> if (total > 0) progress = done to total }
            progress = null
        }
    }

    fun linkTree(uri: android.net.Uri, forKids: Set<String>) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            library.addTree(uri, forKids)
            library.rescan { done, total -> if (total > 0) progress = done to total }
            progress = null
        }
    }

    pendingTree?.let { uri ->
        WhoForDialog(
            title = "Who can watch this folder?",
            profiles = profiles,
            initialIds = emptySet(),
            confirmLabel = "Link folder",
            onDismiss = { pendingTree = null },
            onConfirm = { forKids ->
                pendingTree = null
                linkTree(uri, forKids)
            }
        )
    }

    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            if (profiles.size >= 2) pendingTree = uri
            else linkTree(uri, emptySet())
        }
    }
    val pickVideos = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            library.addFiles(uris) { done, total -> progress = done to total }
            progress = null
        }
    }

    Text(
        "Home videos, rips, purchases. Yosemite Kids links to the files where " +
            "they are — nothing is copied or uploaded — and shows them on the " +
            "kid's Downloads shelf, with the folder name where the channel name " +
            "usually goes.",
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 20.sp),
        color = SettingsTextTertiary
    )

    // Two ways into the same thing, so neither is the primary: a folder is
    // the one to reach for, but a filled teal button beside an outlined one
    // says "this is the answer" about a choice that depends on the phone.
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
        listOf<Pair<String, () -> Unit>>(
            "Add folder" to { pickFolder.launch(null) },
            "Add videos" to { pickVideos.launch(arrayOf("video/*")) }
        ).forEach { (label, onClick) ->
            OutlinedButton(
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, SettingsStrongBorder),
                contentPadding = PaddingValues(horizontal = 12.dp),
                // .height, not heightIn: M3's own 40dp minimum only yields to
                // an exact incoming constraint.
                modifier = Modifier.weight(1f).height(32.dp).tvFocusHighlight(cornerRadius = 8.dp)
            ) { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
        }
    }
    // Its own line: the design has no linked-folder state, and squeezing a
    // third button into a row of two equal halves un-equals them.
    if (trees.isNotEmpty()) CompactButton(onClick = { rescan() }) { Text("Rescan") }

    progress?.let { (done, total) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else done.toFloat() / total },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Text("Adding $done of $total", style = MaterialTheme.typography.bodySmall)
        }
    }

    trees.forEach { tree ->
        val count = items.count { it.treeUri == tree.uri && it.available }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "📁 ${tree.name}  ·  $count video(s)",
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (profiles.size >= 2) {
                KidToggleChips(
                    profiles = profiles,
                    selectedIds = tree.profileIds,
                    onChanged = { forKids ->
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            library.setTreeProfiles(tree.uri, forKids)
                        }
                    }
                )
                Spacer(Modifier.width(4.dp))
            }
            CompactButton(
                onClick = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { library.forgetTree(tree.uri) } }
            ) { Text("Forget") }
        }
    }

    val sorted = items.sortedWith(compareBy({ it.video.channelName }, { it.video.title }))
    sorted.forEach { item ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            AsyncImage(
                model = item.video.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.width(72.dp).height(40.dp).clip(MaterialTheme.shapes.small)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.video.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    if (item.available) {
                        "${item.video.channelName} · ${formatClock(item.video.durationSeconds)}"
                    } else "File missing — rescan, or forget its folder",
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Folder videos are managed by their folder — a removed row would
            // silently come back on the next rescan.
            if (item.treeUri.isEmpty()) {
                IconButton(onClick = {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { library.remove(item.video.url) }
                }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove video")
                }
            }
        }
    }

    if (items.isEmpty() && trees.isEmpty()) {
        Text(
            "Nothing linked yet",
            style = MaterialTheme.typography.bodySmall,
            color = SettingsTextTertiary,
            // 3dp on top of the card's 8dp gap: the design sets this one
            // further off the buttons than the card spaces its children.
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}
