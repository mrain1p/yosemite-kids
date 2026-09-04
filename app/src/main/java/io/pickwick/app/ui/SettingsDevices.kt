package io.pickwick.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.pickwick.app.BuildConfig
import io.pickwick.app.data.ConfigJson
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.LanClient
import io.pickwick.app.data.PairedDevice
import io.pickwick.app.data.PairingStore
import io.pickwick.app.data.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- Pairing ----------------------------------------------------------------

/** "Settings #a1b2c3d4 · edited 3 Aug 2:14 pm" — the comparable version line. */
@Composable
private fun VersionLine(configStore: ConfigStore, refreshKey: Any? = null) {
    // refreshKey follows the live form fingerprint, i.e. every keystroke — the
    // disk re-read behind it must not ride the recomposition on the main thread.
    val text by produceState("", refreshKey) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val config = configStore.load()
            val hash = ConfigJson.fingerprint(config)
            val edited = configStore.updatedAt().takeIf { it > 0 }?.let {
                java.text.SimpleDateFormat("d MMM h:mm a", java.util.Locale.US).format(java.util.Date(it))
            }
            "Settings #$hash" + (edited?.let { " · edited $it" } ?: "")
        }
    }
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun QrImage(content: String) {
    // Built off-main: 480×480 is ~230k pixels, visible time on a TV CPU. One
    // setPixels over an IntArray, not a JNI call per pixel.
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, content) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val size = 480
            val matrix = com.google.zxing.qrcode.QRCodeWriter()
                .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size) { i ->
                if (matrix[i % size, i / size]) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE
            }
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565)
            bmp.setPixels(pixels, 0, size, 0, 0, size, size)
            bmp.asImageBitmap()
        }
    }
    val ready = bitmap
    if (ready != null) {
        Image(
            bitmap = ready,
            contentDescription = "Pairing QR code",
            modifier = Modifier.size(220.dp)
        )
    } else {
        // Same footprint as the image, so the layout doesn't jump when it lands.
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

private sealed interface DeviceSync {
    data object Checking : DeviceSync
    data object Offline : DeviceSync
    data class Reachable(
        val hash: String,
        val updatedAt: Long,
        /** The device's own identity token — the key in deviceProfiles. */
        val deviceToken: String? = null,
        /** Null for a build that predates the sync blob: push-only, never a merge source. */
        val syncV: Int? = null,
        val syncHash: String? = null
    ) : DeviceSync
}

/**
 * Whether a device really matches this phone.
 *
 * Config fingerprint *and* bookkeeping fingerprint, because the two answer
 * different questions: the first is "do we hold the same rules", the second is
 * "do we know the same history". A TV holding a tombstone this phone has never
 * seen matches on the first and not the second — and if that read as "in sync"
 * the deletion would never travel, since the reconcile short-circuits on hash
 * equality and never fetches a body.
 *
 * A legacy peer has no bookkeeping to compare, so it is judged on the config
 * alone, exactly as before.
 */
/**
 * The one comparison rule, over plain values.
 *
 * Two types carry a peer's reported hashes — [DeviceSync.Reachable] for the
 * tile and [LanClient.DeviceStatus] for the re-read after a push — and the
 * push site used to hand-roll this rather than share it. That copy was then
 * missed, so the tile and the note directly beneath it could disagree about
 * the same device. Both wrappers below delegate here; neither restates it.
 */
private fun hashesAgree(
    remoteHash: String,
    remoteSyncV: Int?,
    remoteSyncHash: String?,
    expectedHash: String,
    localSyncHash: String
): Boolean =
    remoteHash == expectedHash && (remoteSyncV == null || remoteSyncHash == localSyncHash)

private fun DeviceSync.Reachable.matches(expectedHash: String, localSyncHash: String): Boolean =
    hashesAgree(hash, syncV, syncHash, expectedHash, localSyncHash)

private fun LanClient.DeviceStatus.matches(expectedHash: String, localSyncHash: String): Boolean =
    hashesAgree(hash, syncV, syncHash, expectedHash, localSyncHash)

/**
 * The fingerprint [device] should be reporting if it agrees with this phone.
 *
 * A secretless peer — a hub — strips the API key before writing and has no
 * SecretStore to put it back, so the hash it advertises is computed over a
 * config with a blank key. Compared against this phone's full fingerprint it
 * could never match, and the hub read as permanently out of sync: the tile
 * said so, Push and Pull were offered forever, and the background reconcile
 * took the merge arm on every single sweep instead of doing nothing.
 *
 * The flag comes from the stored [PairedDevice], recorded at enrolment, never
 * from the peer's own /status. A peer that could declare itself secretless
 * could switch off this phone's only content-level check on the key — and a
 * TV holding a revoked key would then read "in sync" while its screening was
 * dead.
 */
private fun expectedHash(device: PairedDevice, localHash: String, localSecretlessHash: String): String =
    if (device.secretless) localSecretlessHash else localHash

@Composable
internal fun PhoneDevicesSection(
    pairingStore: PairingStore,
    configStore: ConfigStore,
    profiles: List<io.pickwick.app.data.Profile> = emptyList(),
    deviceProfiles: Map<String, String> = emptyMap(),
    /**
     * Fingerprint of the form as it stands right now (unsaved edits included) —
     * what a device must match to count as in sync. Derived by the caller, so it
     * follows every edit; a value snapshotted from disk here would go stale the
     * moment the parent changed anything.
     */
    localHash: String,
    /**
     * Fingerprint of this phone's sync bookkeeping. Read from disk rather than
     * derived from the form, because the form has no opinion about it — the
     * stamper mints it at save time. Compared alongside [localHash] so a peer
     * that knows about a deletion this phone does not cannot read as in sync.
     */
    localSyncHash: String = "",
    /**
     * The same form fingerprint as [localHash], computed without the API key.
     * What a peer that deliberately holds no secrets should be reporting.
     */
    localSecretlessHash: String = localHash,
    /** Force a config sweep now rather than waiting for the five-minute poll. */
    onSyncNow: () -> Unit = {},
    /** Saves the form's current state to disk and returns its JSON — what Push sends. */
    saveCurrent: () -> String,
    /** Assign a device (by its own token) to a kid; null = shared (picker). */
    onAssign: (String, String?) -> Unit = { _, _ -> },
    /** The parent device that builds the search index; null = never chosen. */
    masterToken: String? = null,
    /** Promote an admin phone to master (it takes over indexing). */
    onMakeMaster: (String) -> Unit = {},
    onOpenStats: (PairedDevice) -> Unit,
    onConfigReplaced: () -> Unit = {},
    /** The parent confirmed this very device belongs to a kid (no-TV household). */
    onBecameKidDevice: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(pairingStore.paired()) }
    var syncStates by remember { mutableStateOf<Map<String, DeviceSync>>(emptyMap()) }
    /** device.key → pending pairing requests (name, requestToken) awaiting approval. */
    var pendingByDevice by remember { mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()) }
    /** device.key → approved admin phones (name, adminToken). */
    var adminsByDevice by remember { mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()) }
    /** A revoke awaiting confirmation: (device, adminName, adminToken). */
    var pendingRevoke by remember { mutableStateOf<Triple<PairedDevice, String, String>?>(null) }
    /** A pull awaiting confirmation — it replaces this phone's settings wholesale. */
    var pendingPull by remember { mutableStateOf<PairedDevice?>(null) }
    /** A device being renamed (modal with a text field). */
    var renaming by remember { mutableStateOf<PairedDevice?>(null) }
    var pullMessage by remember { mutableStateOf<String?>(null) }
    /** When the parent last forced a sweep from here — answers "is this thing running?". */
    var lastChecked by remember { mutableStateOf<Long?>(null) }
    var checking by remember { mutableStateOf(false) }
    /** device.key → what the last Push did. Push is otherwise mute: it used to
     *  discard its own result, so a device that never got the config looked
     *  exactly like one that did. */
    var pushMessage by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val myToken = remember { pairingStore.deviceToken() }

    /** Re-reads one device's sync state, returning it for the caller to judge. */
    suspend fun refreshOne(device: PairedDevice): LanClient.DeviceStatus? {
        syncStates = syncStates + (device.key to DeviceSync.Checking)
        val status = LanClient.fullStatus(device)
        syncStates = syncStates + (device.key to
            (status?.let { DeviceSync.Reachable(it.hash, it.updatedAt, it.deviceToken, it.syncV, it.syncHash) }
                ?: DeviceSync.Offline))
        pendingByDevice = pendingByDevice + (device.key to LanClient.pendingRequests(device))
        adminsByDevice = adminsByDevice + (device.key to LanClient.admins(device))
        return status
    }

    fun checkAll() {
        devices.forEach { device -> scope.launch { refreshOne(device) } }
    }

    // A fresh edit makes any previous "Pushed ✓" a lie — drop the notes the
    // moment the form diverges again.
    LaunchedEffect(localHash) { pushMessage = emptyMap() }

    /** "Watching as" chips: Shared or one kid — for this phone and each device. */
    @Composable
    fun assignmentRow(label: String, deviceToken: String?) {
        if (profiles.size < 2) return
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp)
            )
            if (deviceToken == null) {
                Text(
                    "device must be online to set this",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Row
            }
            val assigned = deviceProfiles[deviceToken]
            FilterChip(
                modifier = Modifier.tvFocusHighlight(),
                selected = assigned == null,
                onClick = { onAssign(deviceToken, null) },
                label = { Text("Shared") }
            )
            profiles.forEach { p ->
                Spacer(Modifier.width(4.dp))
                FilterChip(
                    modifier = Modifier.tvFocusHighlight(),
                    selected = assigned == p.id,
                    onClick = { onAssign(deviceToken, p.id) },
                    label = { Text(p.name) }
                )
            }
        }
    }
    LaunchedEffect(devices) { checkAll() }

    pendingRevoke?.let { (device, adminName, adminToken) ->
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            title = { Text("Remove \"$adminName\" as an admin?") },
            text = { Text("That phone will no longer be able to manage ${device.name}. It can re-pair later, but will need approval again.") },
            confirmButton = {
                Button(onClick = {
                    pendingRevoke = null
                    scope.launch { LanClient.revokeAdmin(device, adminToken); checkAll() }
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevoke = null }) { Text("Cancel") }
            }
        )
    }

    pendingPull?.let { device ->
        // Fetched before the parent decides rather than after. A Pull replaces
        // everything on this phone, and until now it did so blind — the parent
        // had no way to see which of their own edits they were discarding.
        // Fetching here also means Copy writes the exact bytes the diff
        // described, instead of re-fetching a config that may have moved in
        // the seconds since.
        var fetched by remember(device.key) { mutableStateOf<String?>(null) }
        var unreachable by remember(device.key) { mutableStateOf(false) }
        var diff by remember(device.key) {
            mutableStateOf<List<io.pickwick.app.data.ConfigMerge.Change>?>(null)
        }
        LaunchedEffect(device.key) {
            val remote = withContext(kotlinx.coroutines.Dispatchers.IO) { LanClient.fetchConfig(device) }
            if (remote == null) {
                unreachable = true
                return@LaunchedEffect
            }
            diff = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    io.pickwick.app.data.ConfigMerge.describe(
                        configStore.load(), ConfigJson.fromJson(remote)
                    )
                }.getOrNull()
            }
            fetched = remote
        }
        AlertDialog(
            onDismissRequest = { pendingPull = null },
            title = { Text("Copy settings from ${device.name}?") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    when {
                        unreachable -> Text(
                            "Couldn't reach ${device.name}. Is it switched on and on home Wi-Fi?"
                        )
                        fetched == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Checking what's on ${device.name}…")
                        }
                        diff.isNullOrEmpty() -> Text(
                            if (diff == null)
                                "${device.name} sent settings this phone couldn't read. " +
                                    "Copying is still possible, but check the result afterwards."
                            else "${device.name} already has the same settings as this phone. " +
                                "Copying changes nothing."
                        )
                        else -> {
                            Text("Copying from ${device.name} will:")
                            // Capped, and honest about the cap: an unbounded
                            // list in a dialog is scrolled past, not read.
                            diff.orEmpty().take(PULL_DIFF_MAX).forEach {
                                Text("•  ${it.text}", style = MaterialTheme.typography.bodySmall)
                            }
                            val extra = diff.orEmpty().size - PULL_DIFF_MAX
                            if (extra > 0) {
                                Text(
                                    "…and $extra more change${if (extra == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (!unreachable) {
                        Text(
                            "Everything on this phone is replaced by what's on ${device.name}. " +
                                "Any unsaved edits on this screen are discarded.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                if (!unreachable) {
                    Button(
                        enabled = fetched != null,
                        onClick = {
                            val remote = fetched ?: return@Button
                            pendingPull = null
                            scope.launch {
                                pullMessage = if (configStore.saveRaw(remote)) {
                                    // onConfigReplaced reloads the whole form from
                                    // disk, which re-derives localHash on its own.
                                    onConfigReplaced()
                                    checkAll()
                                    "Copied ${device.name}'s settings to this phone ✓"
                                } else "Couldn't copy from ${device.name} — it sent something unreadable"
                            }
                        }
                    ) { Text("Copy") }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPull = null }) {
                    Text(if (unreachable) "Close" else "Cancel")
                }
            }
        )
    }

    renaming?.let { device ->
        var name by remember(device.key) { mutableStateOf(device.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename device") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        // Display name only — pairing identity stays device.key.
                        pairingStore.renamePaired(device.key, name.trim())
                        devices = pairingStore.paired()
                        renaming = null
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            }
        )
    }

    VersionLine(configStore, refreshKey = localHash)

    // "Did my change reach the TV?" is a question a parent asks while standing
    // in front of it, and the answer used to be "wait up to five minutes, or
    // background the app". The sweep is the same one the poll runs — this just
    // stops it being the only way to trigger it.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            lastChecked?.let { "Last checked ${changeAge(it) ?: "just now"}" }
                ?: "Checks every few minutes on its own.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        CompactButton(
            enabled = !checking,
            onClick = {
                scope.launch {
                    checking = true
                    onSyncNow()
                    // The sweep is fire-and-forget in the ViewModel, so give it
                    // a beat before re-reading, or the rows report the state
                    // from before it ran.
                    delay(1_200)
                    devices.forEach { refreshOne(it) }
                    lastChecked = System.currentTimeMillis()
                    checking = false
                }
            }
        ) { Text(if (checking) "Checking…" else "Check now") }
    }
    pullMessage?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // A phone can be a kid's own device too — dedicate it and it never asks
    // who's watching.
    assignmentRow("This phone:", myToken)
    if (devices.isEmpty()) {
        Text(
            // Numbered, because it is a procedure across two devices and the
            // old single sentence read as one instruction when it is three.
            // The scan itself uses the phone's ordinary camera app — the QR is
            // a pickwick:// link — so there is nothing to tap here, and saying
            // that plainly beats a parent hunting for a scan button.
            "No kid devices paired yet.\n\n" +
                "1.  On the TV or tablet, open Pickwick → Settings.\n" +
                "2.  Choose \"Pair with a parent phone\". A QR code appears.\n" +
                "3.  Point this phone's normal camera at it and tap the link.\n\n" +
                "The TV's screen says when it is waiting, when this phone has " +
                "asked, and when you are done.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // No-TV household bootstrap: the device in hand IS the kid's. Without
        // this there is no way in — the pairing QR only shows on a device that
        // already knows it's a kid's, and nothing else can make it one. Offered
        // only while this device administers nobody; a phone with kid devices
        // is unambiguously the parent's.
        var confirmDedicate by remember { mutableStateOf(false) }
        // No extra spacer: the card already spaces its children, and a text
        // button carries a 40 dp touch target of its own, so adding to that
        // left the two actions floating a long way from the text they follow.
        CompactButton(onClick = { confirmDedicate = true }) {
            Text("This device is my kid's…")
        }
        if (confirmDedicate) {
            AlertDialog(
                onDismissRequest = { confirmDedicate = false },
                title = { Text("Hand this device to a kid?") },
                text = {
                    Text(
                        "These parent settings will be replaced by a pairing QR " +
                            "code — scan it with a parent's phone to manage this " +
                            "device from there, just like a TV.\n\n" +
                            "This is permanent: the only way to make it a " +
                            "parent device again is reinstalling the app."
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        pairingStore.dedicateAsKid()
                        confirmDedicate = false
                        onBecameKidDevice()
                    }) { Text("Make it a kid's device") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDedicate = false }) { Text("Cancel") }
                }
            )
        }
    }
    devices.forEach { device ->
        val sync = syncStates[device.key]
        // One tile per device: identity and state up top, actions on their own
        // line — buttons squeezed beside the sync text truncated both.
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    IconButton(
                        onClick = { renaming = device },
                        modifier = Modifier.size(28.dp).tvFocusHighlight()
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "Rename ${device.name}",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    when (sync) {
                        is DeviceSync.Checking, null -> "checking…"
                        is DeviceSync.Offline -> "offline — open Pickwick on it, on home Wi-Fi"
                        is DeviceSync.Reachable ->
                            if (sync.matches(expectedHash(device, localHash, localSecretlessHash), localSyncHash)) "in sync ✓"
                            else "out of sync — #${sync.hash} on device"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (sync) {
                        is DeviceSync.Reachable ->
                            if (sync.matches(expectedHash(device, localHash, localSecretlessHash), localSyncHash)) androidx.compose.ui.graphics.Color(0xFF81C784)
                            else MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    if (sync is DeviceSync.Reachable &&
                        (!sync.matches(expectedHash(device, localHash, localSecretlessHash), localSyncHash) || sync.syncV == null)
                    ) {
                        Button(modifier = Modifier.tvFocusHighlight(), onClick = {
                            // Push = "make the device match this screen": saves the
                            // form (unsaved edits included), then overwrites the device.
                            scope.launch {
                                pushMessage = pushMessage + (device.key to "Pushing…")
                                val json = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    saveCurrent()
                                }
                                val sent = LanClient.pushConfig(device, json)
                                // Re-read the device rather than trusting the 200:
                                // "it saved and now matches" and "it saved but still
                                // reports something else" are different problems and
                                // only the second one needs explaining.
                                val after = refreshOne(device)
                                pushMessage = pushMessage + (device.key to when {
                                    !sent ->
                                        "Push failed — ${device.name} didn't answer. " +
                                            "Is it awake and on this Wi-Fi?"
                                    after == null ->
                                        "Sent, but ${device.name} stopped answering — " +
                                            "check it arrived."
                                    // Through matches(), not a second copy of
                                    // its rule. The copy that used to live here
                                    // was missed when the secretless case was
                                    // added, so the tile said "in sync ✓" while
                                    // this note blamed an old version.
                                    after.matches(
                                        expectedHash(device, localHash, localSecretlessHash),
                                        localSyncHash
                                    ) -> "Pushed ✓"
                                    // The device took the config and still disagrees:
                                    // almost always an older build that can't see a
                                    // setting this phone knows about (it stores the
                                    // raw config, so updating it settles this without
                                    // another push).
                                    else ->
                                        "${device.name} saved it but still reports " +
                                            "different settings — it's probably on an " +
                                            "older version. Update it and this clears."
                                })
                            }
                        }) { Text("Push") }
                        Spacer(Modifier.width(4.dp))
                        // The reverse direction: adopt the device's settings — the recovery
                        // path when this phone was reinstalled and the device still holds
                        // the family's blocks/safe-list.
                        CompactButton(onClick = { pendingPull = device }) { Text("Pull") }
                        Spacer(Modifier.width(4.dp))
                    }
                    CompactButton(onClick = { onOpenStats(device) }) { Text("Stats") }
                    CompactButton(onClick = {
                        // Tell the device too (best effort): otherwise it keeps
                        // this phone approved forever, list or no list.
                        scope.launch { LanClient.leave(device) }
                        pairingStore.removePaired(device.key)
                        devices = pairingStore.paired()
                    }) { Text("Unpair") }
                }
                pushMessage[device.key]?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (msg.startsWith("Pushed")) {
                            androidx.compose.ui.graphics.Color(0xFF81C784)
                        } else MaterialTheme.colorScheme.primary
                    )
                }
                assignmentRow("Watching:", (sync as? DeviceSync.Reachable)?.deviceToken)
                // Admin phones approved on this device (revocable, except this phone).
                val admins = adminsByDevice[device.key].orEmpty()
                if (admins.size > 1 || (admins.size == 1 && admins[0].second != myToken)) {
                    admins.forEach { (adminName, adminToken) ->
                        val isThisPhone = adminToken == myToken
                        val isMaster = adminToken == masterToken
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "admin: $adminName" +
                                    (if (isMaster) "  ★ master" else "") +
                                    (if (isThisPhone) "  (this phone)" else ""),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isMaster) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!isMaster) {
                                CompactButton(onClick = { onMakeMaster(adminToken) }) { Text("Make master") }
                            }
                            if (!isThisPhone) {
                                CompactButton(onClick = {
                                    pendingRevoke = Triple(device, adminName, adminToken)
                                }) { Text("Revoke") }
                            }
                        }
                    }
                }
                // New phones asking to become admins for this device.
                pendingByDevice[device.key].orEmpty().forEach { (reqName, reqToken) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "“$reqName” asks to manage ${device.name}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Button(modifier = Modifier.tvFocusHighlight(), onClick = {
                            scope.launch { LanClient.approveRequest(device, reqToken); checkAll() }
                        }) { Text("Approve") }
                        Spacer(Modifier.width(4.dp))
                        CompactButton(onClick = {
                            scope.launch { LanClient.denyRequest(device, reqToken); checkAll() }
                        }) { Text("Deny") }
                    }
                }
            }
        }
    }
}

// --- Advanced: URL lists (power users) --------------------------------------
// --- App / updates ----------------------------------------------------------

@Composable
internal fun UpdateSection(tv: Boolean = false, onUpdateFound: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val updater = remember { Updater(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<Updater.UpdateInfo?>(null) }

    // Check the moment the screen opens — the parent shouldn't need to know
    // there's a button to press to find out. Offline falls back to the cached
    // answer from the daily launch check, so the Install offer still shows.
    LaunchedEffect(Unit) {
        busy = true
        val found = updater.check() ?: updater.pending()
        update = found
        if (found != null) {
            // On TV the button itself names the version, so the status line is
            // free to carry the thing a remote user actually needs told.
            message = if (tv) "Press OK on the remote to install"
            else "Version ${found.versionName} is available"
            onUpdateFound()
        }
        busy = false
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        // The rest of the TV screen is a centred column; a weighted text would
        // stretch this row to the full width and leave the version stranded on
        // the left of it. On the phone that same weight is what pushes the
        // check/install button to the trailing edge of the form row.
        horizontalArrangement = if (tv) Arrangement.Center else Arrangement.Start
    ) {
        Text(
            "Pickwick ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (tv) Modifier.padding(end = 12.dp) else Modifier.weight(1f)
        )
        val pending = update
        if (pending == null) {
            // Nothing on TV: this screen already re-checks every time it opens,
            // so a manual button there can never report anything the screen
            // isn't about to say on its own — it would just be a dead control
            // the D-pad can't reach. The phone keeps it: that form is long and
            // a parent may want to force a check without reopening settings.
            if (!tv) TextButton(
                modifier = Modifier.tvFocusHighlight(),
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        message = "Checking…"
                        val found = updater.check()
                        update = found
                        message = when {
                            found == null -> "You're up to date"
                            tv -> "Press OK on the remote to install"
                            else -> "Version ${found.versionName} is available"
                        }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Checking…" else "Check for updates") }
        } else {
            // The rest of the TV settings screen is static text and a QR image,
            // so this button is the only focusable thing on it — without an
            // explicit request nothing holds focus and the D-pad does nothing.
            // Retried because the node isn't placed on the first composition.
            val installFocus = remember { FocusRequester() }
            // Re-requested when [busy] clears: disabling the button during a
            // download drops focus, and with nothing else focusable a failed
            // attempt would leave the remote dead with no way to retry.
            LaunchedEffect(tv, pending.versionCode, busy) {
                if (!tv || busy) return@LaunchedEffect
                repeat(10) {
                    if (runCatching { installFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                    delay(100)
                }
            }
            // The shared 1.04 focus scale vanishes against a solid fill, and this
            // button arrives already focused — with nothing else on screen to
            // move focus between, a parent can't tell it's live. A ring makes the
            // selection legible from the couch; the hint line below names the key.
            var focused by remember { mutableStateOf(false) }
            // Same red as the settings-gear dot, so the nudge that got the
            // parent here and the button that resolves it read as one thing.
            Button(
                modifier = Modifier
                    .focusRequester(installFocus)
                    .tvFocusHighlight { focused = it }
                    .then(
                        if (tv && focused) Modifier.border(3.dp, Color.White, RectangleShape)
                        else Modifier
                    ),
                shape = if (tv) RectangleShape else ButtonDefaults.shape,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = UpdateDot,
                    contentColor = Color.White
                ),
                onClick = {
                    scope.launch {
                        busy = true
                        message = "Downloading ${pending.versionName}…"
                        runCatching { updater.downloadAndInstall(pending) }
                            .onSuccess { message = "Follow the install prompt" }
                            .onFailure { message = "Update failed: ${it.message}" }
                        busy = false
                    }
                }
            ) {
                // The TV has no comfortable place for a separate status line to
                // be read from ten feet, so the button states the version itself.
                Text(
                    when {
                        busy -> "Downloading…"
                        tv -> "Version ${pending.versionName} available"
                        else -> "Install ${pending.versionName}"
                    }
                )
            }
        }
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Lines the Pull dialog shows before it says "…and N more". A parent skims a
 * confirmation; a forty-line diff in one is scrolled past rather than read,
 * which would put us back where we started.
 */
private const val PULL_DIFF_MAX = 8
