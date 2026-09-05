package io.pickwick.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.unit.sp
import io.pickwick.app.BuildConfig
import io.pickwick.app.data.ConfigJson
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.LanClient
import io.pickwick.app.data.PairedDevice
import io.pickwick.app.data.PairingStore
import io.pickwick.app.data.Updater
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- Pairing ----------------------------------------------------------------

/** "Settings #a1b2c3d4 · edited 3 Aug 2:14 pm" — the comparable version line. */
@Composable
internal fun VersionLine(configStore: ConfigStore, refreshKey: Any? = null) {
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

internal sealed interface DeviceSync {
    data object Checking : DeviceSync
    data object Offline : DeviceSync
    data class Reachable(
        val hash: String,
        val updatedAt: Long,
        /** The device's own identity token — the key in deviceProfiles. */
        val deviceToken: String? = null,
        /** Null for a build that predates the sync blob: push-only, never a merge source. */
        val syncV: Int? = null,
        val syncHash: String? = null,
        val versionName: String? = null,
        val versionCode: Int? = null,
        /** [io.pickwick.app.data.DeviceKind], or null when the build predates it. */
        val kind: String? = null
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

/**
 * What this phone knows about every device it administers, held ABOVE the
 * pages that show it.
 *
 * Not a `remember` inside the Devices page, for two reasons. The page pushes
 * a device's own page and comes back, and a remember in the list is discarded
 * on the way out — every return would flash "checking…" across the fleet.
 * And the LAN must not sit on the path to opening the page: it renders from
 * whatever the last sweep found, instantly, and the sweep runs behind it.
 *
 * [lastAnswer] outlives [syncStates]: a TV that is switched off is still the
 * same build it was an hour ago, so its version and kind keep showing while
 * "Offline" replaces "In sync".
 */
internal class DeviceFleet(private val pairingStore: PairingStore) {
    var devices by mutableStateOf(pairingStore.paired())
        private set
    var syncStates by mutableStateOf<Map<String, DeviceSync>>(emptyMap())
        private set
    var lastAnswer by mutableStateOf<Map<String, DeviceSync.Reachable>>(emptyMap())
        private set
    /** device.key → when it last answered, this process. Nothing on disk records it. */
    var lastSeen by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    /** device.key → pending pairing requests (name, requestToken) awaiting approval. */
    var pendingByDevice by mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap())
        private set
    /** device.key → approved admin phones (name, adminToken). */
    var adminsByDevice by mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap())
        private set
    /** When the parent last forced a sweep — answers "is this thing running?". */
    var lastChecked by mutableStateOf<Long?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    /** device.key → what the last Push did. Push is otherwise mute: it used to
     *  discard its own result, so a device that never got the config looked
     *  exactly like one that did. */
    var pushMessage by mutableStateOf<Map<String, String>>(emptyMap())
    val myToken: String = pairingStore.deviceToken()

    /**
     * Its own scope, not the page's: a refresh started from the list must
     * finish after the parent taps through to a device, or that device is
     * left reading "Checking…" for as long as the page is away.
     */
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )

    fun close() = scope.cancel()

    fun reload() { devices = pairingStore.paired() }

    val hub: PairedDevice? get() = devices.firstOrNull { it.secretless }
    val kidDevices: List<PairedDevice> get() = devices.filterNot { it.secretless }

    /** Re-reads one device's sync state, returning it for the caller to judge. */
    suspend fun refreshOne(device: PairedDevice): LanClient.DeviceStatus? {
        syncStates = syncStates + (device.key to DeviceSync.Checking)
        val status = LanClient.fullStatus(device)
        if (status != null) {
            val answer = DeviceSync.Reachable(
                status.hash, status.updatedAt, status.deviceToken, status.syncV, status.syncHash,
                status.versionName, status.versionCode, status.kind
            )
            syncStates = syncStates + (device.key to answer)
            lastAnswer = lastAnswer + (device.key to answer)
            lastSeen = lastSeen + (device.key to System.currentTimeMillis())
        } else {
            syncStates = syncStates + (device.key to DeviceSync.Offline)
        }
        pendingByDevice = pendingByDevice + (device.key to LanClient.pendingRequests(device))
        adminsByDevice = adminsByDevice + (device.key to LanClient.admins(device))
        return status
    }

    fun refresh(device: PairedDevice) { scope.launch { refreshOne(device) } }

    fun checkAll() { devices.forEach { refresh(it) } }

    /** The sweep the poll runs, forced now, then every row re-read. */
    fun checkNow(onSyncNow: () -> Unit) {
        if (checking) return
        scope.launch {
            checking = true
            onSyncNow()
            // The sweep is fire-and-forget in the ViewModel, so give it a beat
            // before re-reading, or the rows report the state from before it ran.
            delay(1_200)
            devices.forEach { refreshOne(it) }
            lastChecked = System.currentTimeMillis()
            checking = false
        }
    }

    /** Devices reporting an older build than this phone. Unknown versions are not "behind". */
    fun behind(): List<PairedDevice> = devices.filter { d -> lastAnswer[d.key]?.behind() == true }

    /**
     * The other parents: every admin phone approved on any device this one
     * administers, except this one. A co-parent's phone is never paired
     * with this phone directly — the two only ever meet on a TV's admin list
     * — so this is the one place it can be seen from, and it exists only
     * once a sweep has asked.
     */
    fun otherParents(): List<Parent> = otherParents(devices, adminsByDevice, myToken)

    data class Parent(val token: String, val name: String, val manages: List<PairedDevice>)
}

/** The pure half of [DeviceFleet.otherParents], so a JVM test can reach it without prefs. */
internal fun otherParents(
    devices: List<PairedDevice>,
    adminsByDevice: Map<String, List<Pair<String, String>>>,
    myToken: String
): List<DeviceFleet.Parent> {
    val byToken = linkedMapOf<String, DeviceFleet.Parent>()
    devices.forEach { d ->
        adminsByDevice[d.key].orEmpty().forEach { (name, token) ->
            if (token == myToken) return@forEach
            val p = byToken[token] ?: DeviceFleet.Parent(token, name, emptyList())
            byToken[token] = p.copy(manages = p.manages + d)
        }
    }
    return byToken.values.toList()
}

/** "0.12.1-fork (47)", or null when the device has never said. */
internal fun DeviceSync.Reachable.versionText(): String? =
    listOfNotNull(versionName, versionCode?.let { "($it)" }).joinToString(" ").ifBlank { null }

/**
 * Older than this phone, by versionCode — the one number that compares. A
 * device that never said (the hub, an old build) is not "behind": the amber
 * dot has to mean something is known, not that something is unknown.
 */
internal fun DeviceSync.Reachable.behind(myVersionCode: Int = BuildConfig.VERSION_CODE): Boolean =
    versionCode != null && versionCode < myVersionCode

/**
 * One device's line under its name — "In sync · 0.12.1-fork (47)", "Offline ·
 * seen 20m ago · 0.12.2-fork (49)", "Checking…" — and whether it wants amber.
 *
 * Pure: the caller has already judged [inSync] through `matches()`, and
 * [last] is the device's most recent answer, which outlives an Offline —
 * a switched-off TV is still the build it was an hour ago.
 */
internal fun deviceStatusLine(
    sync: DeviceSync?,
    last: DeviceSync.Reachable?,
    seenAt: Long?,
    inSync: Boolean,
    myVersionCode: Int = BuildConfig.VERSION_CODE,
    now: Long = System.currentTimeMillis()
): Pair<String, Boolean> {
    val version = last?.versionText()
    val update = if (last?.behind(myVersionCode) == true) "update available" else null
    return when (sync) {
        is DeviceSync.Reachable ->
            listOfNotNull(if (inSync) "In sync" else "Out of sync", version, update)
                .joinToString(" · ") to !inSync
        is DeviceSync.Offline -> listOfNotNull(
            "Offline",
            seenAt?.let { changeAge(it, now) }?.let { "seen $it" },
            version
        ).joinToString(" · ") to true
        is DeviceSync.Checking, null -> listOfNotNull("Checking…", version).joinToString(" · ") to false
    }
}

/** [deviceStatusLine] for one row, judged against this phone's fingerprints. */
private fun rowStatusLine(
    device: PairedDevice,
    fleet: DeviceFleet,
    localHash: String,
    localSecretlessHash: String,
    localSyncHash: String
): Pair<String, Boolean> {
    val sync = fleet.syncStates[device.key]
    val inSync = (sync as? DeviceSync.Reachable)
        ?.matches(expectedHash(device, localHash, localSecretlessHash), localSyncHash) == true
    return deviceStatusLine(sync, fleet.lastAnswer[device.key], fleet.lastSeen[device.key], inSync)
}
/** The kid-device badge, from what the device said about itself. Null = say nothing. */
private fun kindBadge(kind: String?): String? = when (kind) {
    io.pickwick.app.data.DeviceKind.TV -> "TV"
    io.pickwick.app.data.DeviceKind.TABLET -> "TABLET"
    io.pickwick.app.data.DeviceKind.PHONE -> "PHONE"
    else -> null
}

/**
 * The fleet: one row per device, the hub and this phone among them, and the
 * amber banner when any of them is behind. See raw-devices.png.
 *
 * Rows, not tiles: every device used to be a card with its own button row,
 * and five devices was a screen and a half of buttons a parent scrolled past
 * to find the one that said "offline". Now the row says it, and the actions
 * are on the page the row opens.
 */
@Composable
internal fun PhoneDevicesSection(
    fleet: DeviceFleet,
    pairingStore: PairingStore,
    /**
     * Fingerprint of the form as it stands right now (unsaved edits included) —
     * what a device must match to count as in sync. Derived by the caller, so it
     * follows every edit; a value snapshotted from disk here would go stale the
     * moment the parent changed anything.
     */
    localHash: String,
    /**
     * The same form fingerprint as [localHash], computed without the API key.
     * What a peer that deliberately holds no secrets should be reporting.
     */
    localSecretlessHash: String,
    /**
     * Fingerprint of this phone's sync bookkeeping. Read from disk rather than
     * derived from the form, because the form has no opinion about it — the
     * stamper mints it at save time. Compared alongside [localHash] so a peer
     * that knows about a deletion this phone does not cannot read as in sync.
     */
    localSyncHash: String,
    /** The parent device that builds the search index; null = never chosen. */
    masterToken: String?,
    /** Force a config sweep now rather than waiting for the five-minute poll. */
    onSyncNow: () -> Unit,
    onOpenDevice: (PairedDevice) -> Unit,
    onOpenThisPhone: () -> Unit,
    onOpenParent: (DeviceFleet.Parent) -> Unit,
    onOpenHub: () -> Unit,
    onAddDevice: () -> Unit
) {
    // The sweep runs behind the list, never in front of it: the rows are on
    // screen from the last answers before the first request leaves.
    LaunchedEffect(fleet.devices) { fleet.checkAll() }
    // A fresh edit makes any previous "Pushed ✓" a lie — drop the notes the
    // moment the form diverges again.
    LaunchedEffect(localHash) { fleet.pushMessage = emptyMap() }

    val behind = fleet.behind()
    if (behind.isNotEmpty()) {
        UpdateBanner(behind.size)
        Spacer(Modifier.height(12.dp))
    }

    // "Devices ⟳ · 5 paired": the label carries the check-again control, and
    // the count sits where a value would.
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Devices",
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
        RefreshButton(
            busy = fleet.checking,
            contentDescription = "Check every device now",
            onClick = { fleet.checkNow(onSyncNow) }
        )
        Spacer(Modifier.weight(1f))
        Text(
            "${fleet.devices.size} paired",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(6.dp))

    SettingsCard(padded = false) {
        // The hub first, whether or not there is one: the row is how a parent
        // finds out a hub is possible, and where the card that sets it up
        // went (it used to be the heaviest thing on this page).
        val hub = fleet.hub
        if (hub != null) {
            val (line, amber) = rowStatusLine(hub, fleet, localHash, localSecretlessHash, localSyncHash)
            DeviceRow(
                name = hub.name, badge = "HUB", status = line, amber = amber,
                dot = fleet.lastAnswer[hub.key]?.behind() == true,
                refreshing = fleet.syncStates[hub.key] is DeviceSync.Checking,
                onRefresh = { fleet.refresh(hub) },
                onClick = { onOpenDevice(hub) }
            )
        } else {
            DeviceRow(
                name = "A hub you run", badge = null,
                status = "Optional · not connected", amber = false,
                dot = false, refreshing = false, onRefresh = null, onClick = onOpenHub
            )
        }
        SettingsDivider()

        // This phone. Nothing to reach over the LAN, so no ⟳: the version and
        // the master mark are facts it holds itself.
        DeviceRow(
            name = pairingStore.myName(),
            badge = "PARENT · THIS DEVICE",
            status = listOfNotNull(
                if (masterToken == fleet.myToken) "Search-index master" else null,
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            ).joinToString(" · "),
            amber = false, dot = false, refreshing = false, onRefresh = null,
            onClick = onOpenThisPhone
        )

        // Co-parents, as the TVs report them. They appear once a sweep has
        // asked, which is honest: this phone has no other way to know.
        fleet.otherParents().forEach { parent ->
            SettingsDivider()
            DeviceRow(
                name = parent.name,
                badge = "PARENT",
                status = listOfNotNull(
                    if (parent.token == masterToken) "Search-index master" else null,
                    "Manages " + parent.manages.joinToString(", ") { it.name }
                ).joinToString(" · "),
                amber = false, dot = false, refreshing = false, onRefresh = null,
                onClick = { onOpenParent(parent) }
            )
        }

        fleet.kidDevices.forEach { device ->
            SettingsDivider()
            val (line, amber) = rowStatusLine(device, fleet, localHash, localSecretlessHash, localSyncHash)
            val last = fleet.lastAnswer[device.key]
            DeviceRow(
                name = device.name,
                badge = kindBadge(last?.kind),
                status = line, amber = amber,
                dot = last?.behind() == true,
                refreshing = fleet.syncStates[device.key] is DeviceSync.Checking,
                onRefresh = { fleet.refresh(device) },
                onClick = { onOpenDevice(device) }
            )
        }

        SettingsDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .tvFocusHighlight()
                .clickable(onClick = onAddDevice)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Icon(
                Icons.Filled.Add, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text("Add a device", color = MaterialTheme.colorScheme.primary)
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        fleet.lastChecked?.let { "Last checked ${changeAge(it) ?: "just now"}" }
            ?: "Checks every few minutes on its own.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp)
    )
}

/**
 * "2 devices behind on updates". No "Update all": a device installs an update
 * only from its own screen — Android hands the APK to its installer, which
 * asks the person holding the remote — and there is no LAN route that could
 * press that button for them. The banner says where the install is instead.
 */
@Composable
private fun UpdateBanner(count: Int) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        color = StatusAmber.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(1.dp, StatusAmber.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                "$count ${if (count == 1) "device" else "devices"} behind on updates",
                color = StatusAmber,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Text(
                "This phone has ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}). " +
                    "Each device offers the install on its own settings screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** ⟳ in a 36dp target: a spinner while the read is in flight, so a tap is seen to do something. */
@Composable
private fun RefreshButton(busy: Boolean, contentDescription: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.size(36.dp).tvFocusHighlight()
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Icon(
            androidx.compose.material.icons.Icons.Filled.Refresh,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** "HUB", "PARENT · THIS DEVICE", "TV": the small caps tag beside a name. */
@Composable
private fun DeviceBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            letterSpacing = 0.6.sp
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

/** One device: name + badge, status line, amber dot when behind, ⟳, chevron. */
@Composable
private fun DeviceRow(
    name: String,
    badge: String?,
    status: String,
    amber: Boolean,
    dot: Boolean,
    refreshing: Boolean,
    onRefresh: (() -> Unit)?,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusHighlight()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    DeviceBadge(badge)
                }
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (amber) StatusAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (dot) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(7.dp)
                    .background(StatusAmber, androidx.compose.foundation.shape.CircleShape)
            )
        }
        if (onRefresh != null) {
            Spacer(Modifier.width(6.dp))
            RefreshButton(busy = refreshing, contentDescription = "Check $name now", onClick = onRefresh)
        }
        Spacer(Modifier.width(2.dp))
        Icon(
            PickwickIcons.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** "Watching as" chips: Shared or one kid — for this phone and each device. */
@Composable
internal fun WatchingAsRow(
    label: String,
    deviceToken: String?,
    profiles: List<io.pickwick.app.data.Profile>,
    deviceProfiles: Map<String, String>,
    onAssign: (String, String?) -> Unit
) {
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

/**
 * One device's own page: its sync state and the actions on it. Everything
 * the old per-device card carried, one tap in — Push/Pull, Stats, rename,
 * who watches on it, which parents manage it, and Unpair.
 *
 * For the hub the same page carries [HubSection] in place of the kid-device
 * parts, so "Connect my TVs" is still reachable — behind the hub's row, not
 * on top of every other device.
 */
@Composable
internal fun DevicePage(
    fleet: DeviceFleet,
    device: PairedDevice,
    pairingStore: PairingStore,
    configStore: ConfigStore,
    profiles: List<io.pickwick.app.data.Profile>,
    deviceProfiles: Map<String, String>,
    localHash: String,
    localSecretlessHash: String,
    localSyncHash: String,
    /** Saves the form's current state to disk and returns its JSON — what Push sends. */
    saveCurrent: () -> String,
    /** Assign a device (by its own token) to a kid; null = shared (picker). */
    onAssign: (String, String?) -> Unit,
    masterToken: String?,
    /** Promote an admin phone to master (it takes over indexing). */
    onMakeMaster: (String) -> Unit,
    onOpenStats: (PairedDevice) -> Unit,
    onConfigReplaced: () -> Unit,
    /** The hub joined or left, or the device was unpaired: the caller re-reads. */
    onFleetChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    /** A revoke awaiting confirmation: (adminName, adminToken). */
    var pendingRevoke by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingPull by remember { mutableStateOf(false) }
    var pendingUnpair by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var pullMessage by remember { mutableStateOf<String?>(null) }

    val expected = expectedHash(device, localHash, localSecretlessHash)
    val sync = fleet.syncStates[device.key]
    val last = fleet.lastAnswer[device.key]
    val inSync = (sync as? DeviceSync.Reachable)?.matches(expected, localSyncHash)

    pendingRevoke?.let { (adminName, adminToken) ->
        RevokeDialog(
            device = device, adminName = adminName,
            onConfirm = {
                pendingRevoke = null
                scope.launch { LanClient.revokeAdmin(device, adminToken); fleet.refreshOne(device) }
            },
            onDismiss = { pendingRevoke = null }
        )
    }
    if (pendingPull) {
        PullDialog(
            device = device, configStore = configStore,
            onCopied = { message ->
                pullMessage = message
                onConfigReplaced()
                fleet.checkAll()
            },
            onDismiss = { pendingPull = false }
        )
    }
    if (pendingUnpair) {
        AlertDialog(
            onDismissRequest = { pendingUnpair = false },
            title = { Text(if (device.secretless) "Disconnect the hub?" else "Unpair ${device.name}?") },
            text = {
                Text(
                    if (device.secretless)
                        "This phone stops syncing with it. Your TVs keep their own hub " +
                            "connection until you remove them there."
                    else "This phone stops managing it. Its settings stay as they are, and " +
                        "it can be paired again from its own screen."
                )
            },
            confirmButton = {
                Button(onClick = {
                    pendingUnpair = false
                    // Tell the device too (best effort): otherwise it keeps
                    // this phone approved forever, list or no list.
                    scope.launch { LanClient.leave(device) }
                    pairingStore.removePaired(device.key)
                    fleet.reload()
                    onFleetChanged()
                }) { Text(if (device.secretless) "Disconnect" else "Unpair") }
            },
            dismissButton = { TextButton(onClick = { pendingUnpair = false }) { Text("Cancel") } }
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(device.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename device") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, label = { Text("Name") }
                )
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        // Display name only — pairing identity stays device.key.
                        pairingStore.renamePaired(device.key, name.trim())
                        fleet.reload()
                        renaming = false
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } }
        )
    }

    SettingsCard {
        Text(
            when (sync) {
                is DeviceSync.Checking, null -> "Checking…"
                is DeviceSync.Offline -> "Offline — open Pickwick on it, on home Wi-Fi"
                is DeviceSync.Reachable ->
                    if (inSync == true) "In sync ✓" else "Out of sync — #${sync.hash} on device"
            },
            color = when {
                sync is DeviceSync.Reachable && inSync == true -> StatusOkGreen
                sync is DeviceSync.Reachable || sync is DeviceSync.Offline -> StatusAmber
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        )
        val facts = listOfNotNull(
            last?.let { l ->
                l.versionText()?.let { v ->
                    "Running $v" + if (l.behind()) " · behind this phone's ${BuildConfig.VERSION_NAME}" else ""
                }
            },
            fleet.lastSeen[device.key]?.let { changeAge(it) }?.let { "Last answered $it" }
        )
        if (facts.isNotEmpty()) Text(
            facts.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (last?.behind() == true) Text(
            "It offers the update on its own settings screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            if (sync is DeviceSync.Reachable && (inSync != true || sync.syncV == null)) {
                Button(modifier = Modifier.tvFocusHighlight(), onClick = {
                    // Push = "make the device match this screen": saves the
                    // form (unsaved edits included), then overwrites the device.
                    scope.launch {
                        fleet.pushMessage = fleet.pushMessage + (device.key to "Pushing…")
                        val json = withContext(kotlinx.coroutines.Dispatchers.IO) { saveCurrent() }
                        val sent = LanClient.pushConfig(device, json)
                        // Re-read the device rather than trusting the 200:
                        // "it saved and now matches" and "it saved but still
                        // reports something else" are different problems and
                        // only the second one needs explaining.
                        val after = fleet.refreshOne(device)
                        fleet.pushMessage = fleet.pushMessage + (device.key to when {
                            !sent ->
                                "Push failed — ${device.name} didn't answer. " +
                                    "Is it awake and on this Wi-Fi?"
                            after == null ->
                                "Sent, but ${device.name} stopped answering — check it arrived."
                            // Through matches(), not a second copy of its rule.
                            after.matches(expected, localSyncHash) -> "Pushed ✓"
                            // The device took the config and still disagrees:
                            // almost always an older build that can't see a
                            // setting this phone knows about.
                            else ->
                                "${device.name} saved it but still reports different " +
                                    "settings — it's probably on an older version. " +
                                    "Update it and this clears."
                        })
                    }
                }) { Text("Push") }
                Spacer(Modifier.width(4.dp))
                // The reverse direction: adopt the device's settings — the
                // recovery path when this phone was reinstalled and the
                // device still holds the family's blocks/safe-list.
                CompactButton(onClick = { pendingPull = true }) { Text("Pull") }
                Spacer(Modifier.width(4.dp))
            }
            CompactButton(
                enabled = sync !is DeviceSync.Checking,
                onClick = { fleet.refresh(device) }
            ) { Text("Check again") }
        }
        fleet.pushMessage[device.key]?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = if (msg.startsWith("Pushed")) StatusOkGreen else MaterialTheme.colorScheme.primary
            )
        }
        pullMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (device.secretless) {
        SectionTitle("Hub setup")
        // Not removable here: the Disconnect row under "This entry" below
        // already is, and the same action twice on one page reads as two.
        HubSection(pairingStore, fleet, onFleetChanged = { fleet.reload(); onFleetChanged() })
    } else {
        SectionTitle("On this device")
        SettingsCard(padded = false) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                ValueRow(
                    "Stats", "What's been watched on it",
                    onClick = { onOpenStats(device) }
                )
                // A phone can be a kid's own device too — dedicate it and it
                // never asks who's watching.
                if (profiles.size >= 2) {
                    SettingsDivider()
                    Spacer(Modifier.height(8.dp))
                    WatchingAsRow(
                        "Watching:", last?.deviceToken, profiles, deviceProfiles, onAssign
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // Admin phones approved on this device (revocable, except this phone).
        val admins = fleet.adminsByDevice[device.key].orEmpty()
        val pending = fleet.pendingByDevice[device.key].orEmpty()
        if (admins.isNotEmpty() || pending.isNotEmpty()) {
            SectionTitle("Parents who manage it")
            SettingsCard {
                admins.forEach { (adminName, adminToken) ->
                    val isThisPhone = adminToken == fleet.myToken
                    val isMaster = adminToken == masterToken
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            adminName +
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
                            CompactButton(onClick = { pendingRevoke = adminName to adminToken }) {
                                Text("Remove")
                            }
                        }
                    }
                }
                // New phones asking to become admins for this device.
                pending.forEach { (reqName, reqToken) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "“$reqName” asks to manage ${device.name}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Button(modifier = Modifier.tvFocusHighlight(), onClick = {
                            scope.launch { LanClient.approveRequest(device, reqToken); fleet.refreshOne(device) }
                        }) { Text("Approve") }
                        Spacer(Modifier.width(4.dp))
                        CompactButton(onClick = {
                            scope.launch { LanClient.denyRequest(device, reqToken); fleet.refreshOne(device) }
                        }) { Text("Deny") }
                    }
                }
            }
        }
    }

    SectionTitle("This entry")
    SettingsCard(padded = false) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            ValueRow("Name", value = device.name, onClick = { renaming = true })
            SettingsDivider()
            ValueRow(
                if (device.secretless) "Disconnect from this hub" else "Unpair",
                summary = if (device.secretless) "This phone stops syncing with it"
                else "This phone stops managing it",
                onClick = { pendingUnpair = true }
            )
        }
    }
}

/**
 * A co-parent's page: which devices they manage, and the two things this
 * phone can do about that — make them the search-index master, or remove
 * them from a device. A co-parent is not a paired device (the two phones
 * only meet on a TV's admin list), so this is per device by construction.
 */
@Composable
internal fun ParentPage(
    fleet: DeviceFleet,
    parent: DeviceFleet.Parent,
    masterToken: String?,
    onMakeMaster: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var pendingRevoke by remember { mutableStateOf<PairedDevice?>(null) }
    pendingRevoke?.let { device ->
        RevokeDialog(
            device = device, adminName = parent.name,
            onConfirm = {
                pendingRevoke = null
                scope.launch { LanClient.revokeAdmin(device, parent.token); fleet.refreshOne(device) }
            },
            onDismiss = { pendingRevoke = null }
        )
    }
    SettingsCard {
        Text(
            if (parent.token == masterToken) "Search-index master — their phone builds the index every device searches."
            else "A parent's phone, approved on the devices below.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (parent.token != masterToken) {
            CompactButton(onClick = { onMakeMaster(parent.token) }) { Text("Make search-index master") }
        }
    }
    SectionTitle("Manages")
    SettingsCard(padded = false) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            parent.manages.forEachIndexed { i, device ->
                if (i > 0) SettingsDivider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(device.name, modifier = Modifier.weight(1f))
                    CompactButton(onClick = { pendingRevoke = device }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun RevokeDialog(
    device: PairedDevice,
    adminName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove \"$adminName\" from ${device.name}?") },
        text = {
            Text(
                "That phone will no longer be able to manage ${device.name}. " +
                    "It can re-pair later, but will need approval again."
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * Copy a device's settings onto this phone, having first shown what that
 * discards.
 *
 * Fetched before the parent decides rather than after. A Pull replaces
 * everything on this phone, and until now it did so blind — the parent had
 * no way to see which of their own edits they were discarding. Fetching
 * here also means Copy writes the exact bytes the diff described, instead
 * of re-fetching a config that may have moved in the seconds since.
 */
@Composable
private fun PullDialog(
    device: PairedDevice,
    configStore: ConfigStore,
    onCopied: (message: String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
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
        onDismissRequest = onDismiss,
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
                        onDismiss()
                        scope.launch {
                            val message = if (configStore.saveRaw(remote)) {
                                "Copied ${device.name}'s settings to this phone ✓"
                            } else "Couldn't copy from ${device.name} — it sent something unreadable"
                            onCopied(message)
                        }
                    }
                ) { Text("Copy") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (unreachable) "Close" else "Cancel") }
        }
    )
}

/**
 * Behind "+ Add a device": how a TV or tablet is paired, and the two other
 * ways in — a hub, and the no-TV household whose kid device is the phone in
 * hand.
 */
@Composable
internal fun AddDevicePage(
    fleet: DeviceFleet,
    pairingStore: PairingStore,
    onOpenHub: () -> Unit,
    /** The parent confirmed this very device belongs to a kid (no-TV household). */
    onBecameKidDevice: () -> Unit
) {
    SectionTitle("A TV or tablet")
    SettingsCard {
        Text(
            // Numbered, because it is a procedure across two devices and the
            // old single sentence read as one instruction when it is three.
            // The scan itself uses the phone's ordinary camera app — the QR is
            // a pickwick:// link — so there is nothing to tap here, and saying
            // that plainly beats a parent hunting for a scan button.
            "1.  On the TV or tablet, open Pickwick → Settings.\n" +
                "2.  Choose \"Pair with a parent phone\". A QR code appears.\n" +
                "3.  Point this phone's normal camera at it and tap the link.\n\n" +
                "The TV's screen says when it is waiting, when this phone has " +
                "asked, and when you are done.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (fleet.hub == null) {
        SectionTitle("A hub you run")
        SettingsCard(padded = false) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                ValueRow(
                    "Connect a hub",
                    "Optional · a NAS or Pi that stays on, so two parents stay in step",
                    onClick = onOpenHub
                )
            }
        }
    }
    // No-TV household bootstrap: the device in hand IS the kid's. Without
    // this there is no way in — the pairing QR only shows on a device that
    // already knows it's a kid's, and nothing else can make it one. Offered
    // only while this device administers nobody; a phone with kid devices
    // is unambiguously the parent's.
    if (fleet.kidDevices.isEmpty()) {
        var confirmDedicate by remember { mutableStateOf(false) }
        SectionTitle("No TV?")
        SettingsCard {
            Text(
                "If this phone or tablet is the one your kid will use, it can be " +
                    "their device instead of yours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CompactButton(onClick = { confirmDedicate = true }) {
                Text("This device is my kid's…")
            }
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

    /** The manual check, phone only — see the note on the button that runs it. */
    fun checkNow() {
        scope.launch {
            busy = true
            message = "Checking…"
            val found = updater.check()
            update = found
            message = when {
                // Not "You're up to date". UPDATE_MANIFEST_URL is blank in
                // every build shipped so far, and check() returns null for
                // that without making a request — so the reassuring message
                // was reporting the result of a check that never happened.
                // It matters most in the one case this exists for:
                // extraction breaks, and a parent presses this to find out
                // whether a fix is waiting.
                !io.pickwick.app.data.Updater.canCheck() ->
                    "Updates aren't set up for this build"
                found == null -> "You're up to date"
                else -> "Version ${found.versionName} is available"
            }
            busy = false
        }
    }

    fun install(pending: Updater.UpdateInfo) {
        scope.launch {
            busy = true
            message = "Downloading ${pending.versionName}…"
            runCatching { updater.downloadAndInstall(pending) }
                .onSuccess { message = "Follow the install prompt" }
                .onFailure { message = "Update failed: ${it.message}" }
            busy = false
        }
    }

    if (!tv) {
        // raw-backup.png: the version as the card's headline, the build under
        // it, and one full-width button — Check, or Install once a version is
        // known. The old row put "Pickwick 0.12" on the left and a text
        // button on the right, which read as a caption with a link in it.
        Text("Pickwick ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
        Text(
            "Build ${BuildConfig.VERSION_CODE}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        val pending = update
        if (pending == null) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().tvFocusHighlight(),
                enabled = !busy,
                onClick = { checkNow() }
            ) { Text(if (busy) "Checking…" else "Check for updates") }
        } else {
            // Same red as the settings-gear dot, so the nudge that got the
            // parent here and the button that resolves it read as one thing.
            Button(
                modifier = Modifier.fillMaxWidth().tvFocusHighlight(),
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = UpdateDot,
                    contentColor = Color.White
                ),
                onClick = { install(pending) }
            ) { Text(if (busy) "Downloading…" else "Install ${pending.versionName}") }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        // The rest of the TV screen is a centred column; a weighted text would
        // stretch this row to the full width and leave the version stranded on
        // the left of it.
        horizontalArrangement = Arrangement.Center
    ) {
        // No version line here. The pairing panel directly above prints the
        // device name and this same version, and two version lines a
        // centimetre apart on a 10-foot screen reads as a bug rather than as
        // thoroughness.
        val pending = update
        if (pending != null) {
            // Nothing when there is no update: this screen already re-checks
            // every time it opens, so a manual button here can never report
            // anything the screen isn't about to say on its own — it would
            // just be a dead control the D-pad can't reach.
            //
            // The rest of the TV settings screen is static text and a QR image,
            // so this button is the only focusable thing on it — without an
            // explicit request nothing holds focus and the D-pad does nothing.
            // Retried because the node isn't placed on the first composition.
            val installFocus = remember { FocusRequester() }
            // Re-requested when [busy] clears: disabling the button during a
            // download drops focus, and with nothing else focusable a failed
            // attempt would leave the remote dead with no way to retry.
            LaunchedEffect(pending.versionCode, busy) {
                if (busy) return@LaunchedEffect
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
                        if (focused) Modifier.border(3.dp, Color.White, RectangleShape)
                        else Modifier
                    ),
                shape = RectangleShape,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = UpdateDot,
                    contentColor = Color.White
                ),
                onClick = { install(pending) }
            ) {
                // The TV has no comfortable place for a separate status line to
                // be read from ten feet, so the button states the version itself.
                Text(if (busy) "Downloading…" else "Version ${pending.versionName} available")
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
