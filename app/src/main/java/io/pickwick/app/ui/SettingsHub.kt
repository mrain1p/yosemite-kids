package io.pickwick.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.HubEnrolment
import io.pickwick.app.data.LanClient
import io.pickwick.app.data.PairedDevice
import io.pickwick.app.data.PairingStore
import kotlinx.coroutines.launch

/**
 * Connecting this household to a self-hosted hub.
 *
 * Optional, and the wording says so: a household that never runs one loses
 * nothing.
 *
 * Once joined, the hub is stored as an ordinary paired device — so everything
 * that already syncs with a TV syncs with it, and there is no second code path
 * to keep in step.
 *
 * This screen also introduces the TVs to the hub, because a TV cannot do it for
 * itself: its entire parent settings screen is a QR code, so there is no field
 * to type an address into and a remote is the worst imaginable way to enter
 * one. This phone is paired with both ends, so it is the only thing that can
 * make the introduction. Once a TV holds the hub as a peer, the reconcile in
 * MainViewModel picks it up untouched — that loop is gated only on the paired
 * list being non-empty, never on role or device kind.
 *
 * What that buys, precisely: a TV reconciles when Pickwick opens on it and
 * while a kid is looking at it. It does NOT reconcile while the TV is asleep or
 * on another app — the sweep lives in viewModelScope behind `uiActive`, and
 * there is no background worker. So the promise here is "current the moment a
 * kid opens it, without a parent's phone being nearby", and it is worded that
 * way on purpose: this feature was described as "always up to date" twice
 * before anybody checked whether a TV could sync at all.
 *
 * Renders its own card. Joined, it is the hub card from raw-backup.png — name,
 * address with a live "Connected", the admin-token field for TVs added since —
 * and the same card serves the hub's own device page, so there is one hub form
 * and not two that drift. Not yet joined, it is the address-and-token form.
 *
 * The hub is read from [fleet] rather than looked up by name: a parent can
 * rename it (here, or on its device page), and the flag that marks a hub is
 * [PairedDevice.secretless], which a rename cannot lose.
 */
@Composable
internal fun HubSection(
    pairingStore: PairingStore,
    fleet: DeviceFleet,
    /** The hub joined, left, or was renamed: the caller re-reads whatever it derives. */
    onFleetChanged: () -> Unit,
    /**
     * Offer "Remove" beside "Connect my TVs". Off on the hub's own device
     * page, which already ends in a Disconnect row — the same action twice on
     * one screen reads as two different things.
     */
    removable: Boolean = false
) {
    val scope = rememberCoroutineScope()

    val hub = fleet.hub
    var host by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingRemove by remember { mutableStateOf(false) }

    /**
     * Give every kid device this phone administers a hub of its own.
     *
     * Skips any that already has one. Without that check a second run mints a
     * fresh token for a device that is already set up and leaves the old one
     * enrolled on the hub forever, so its device list fills with duplicates
     * nobody can tell apart.
     *
     * Returns how many were newly connected and how many could not be reached,
     * because "3 of 4" is actionable and "done" is not — the missing one is a
     * TV that is switched off, and the parent needs to know to come back.
     */
    suspend fun connectDevices(hubHost: String, hubPort: Int, adminToken: String): Pair<Int, Int> {
        var added = 0
        var missed = 0
        pairingStore.paired()
            .filterNot { it.secretless }   // never the hub itself
            .forEach { device ->
                val status = LanClient.fullStatus(device)
                when {
                    status == null -> missed++
                    status.hasHub -> Unit
                    else -> {
                        val token = HubEnrolment
                            .tokenFor(hubHost, hubPort, adminToken, device.name)
                            .getOrNull()
                        if (token == null) missed++
                        else if (LanClient.sendHubDetails(device, hubHost, hubPort, token)) added++
                        else missed++
                    }
                }
            }
        return added to missed
    }

    fun describe(e: Throwable): String = when ((e as? HubEnrolment.HubError)?.failure) {
        // Each of these sends a parent somewhere different, so none of them
        // collapses into "failed".
        HubEnrolment.Failure.Unreachable ->
            "Nothing answered at that address. Is the hub running, and is this phone on the same network?"
        HubEnrolment.Failure.NotAHub ->
            "Something answered, but it isn't a Pickwick hub. Check the address and port."
        HubEnrolment.Failure.BadAdminToken ->
            "That admin token was refused. It's printed in the hub's log when it starts."
        is HubEnrolment.Failure.Refused ->
            "The hub refused this attempt. Try again — codes are only good for a few minutes."
        null -> "Couldn't connect: ${e.message?.take(120)}"
    }

    if (hub != null) {
        // Ask the hub the moment the card shows, so "Connected" is what it
        // says now and not what the last Devices sweep found. The fleet lives
        // above the pages, so a repeat visit renders the last answer at once.
        LaunchedEffect(hub.key) { fleet.refresh(hub) }

        if (pendingRemove) {
            AlertDialog(
                onDismissRequest = { pendingRemove = false },
                title = { Text("Disconnect the hub?") },
                text = {
                    Text(
                        "This phone stops syncing with it. Your TVs keep their own hub " +
                            "connection until you remove them there."
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        pendingRemove = false
                        // Tell the hub too (best effort): otherwise it keeps
                        // this phone enrolled forever, list or no list.
                        scope.launch { LanClient.leave(hub) }
                        pairingStore.removePaired(hub.key)
                        fleet.reload()
                        message = null
                        onFleetChanged()
                    }) { Text("Disconnect") }
                },
                dismissButton = { TextButton(onClick = { pendingRemove = false }) { Text("Cancel") } }
            )
        }

        SettingsCard(padded = false) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Called",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // The label sits above the field, so the field carries none of
                // its own. Written through on every keystroke, like this
                // phone's own name; a blank is never saved, since a device row
                // with no name is unfindable.
                var name by remember(hub.key) { mutableStateOf(hub.name) }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        val trimmed = it.trim()
                        if (trimmed.isNotEmpty() && trimmed != hub.name) {
                            pairingStore.renamePaired(hub.key, trimmed)
                            fleet.reload()
                            onFleetChanged()
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Both true on this phone: the row on Devices & sync prints
                // the paired name, and a merge from the hub is logged under it.
                Text(
                    "The name it shows under Devices & sync and beside the changes it makes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            SettingsDivider()
            // The address, and whether anything is answering there right now.
            // "Connected" is the fleet's word, not a stored flag: a hub that
            // was joined and then switched off says Offline.
            val (state, tone) = when (fleet.syncStates[hub.key]) {
                is DeviceSync.Reachable -> "Connected" to StatusOkGreen
                is DeviceSync.Offline -> "Offline" to StatusAmber
                is DeviceSync.Checking, null -> "Checking…" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(
                    // The port only when it is not the one every hub listens
                    // on: "192.168.1.245:8765" tells a parent nothing the bare
                    // address does not.
                    if (hub.port == HubEnrolment.DEFAULT_PORT) hub.host else "${hub.host}:${hub.port}",
                    modifier = Modifier.weight(1f)
                )
                Text(state, style = MaterialTheme.typography.bodySmall, color = tone)
            }
            SettingsDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Added a TV since? Enter the admin token again to connect it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = admin,
                    onValueChange = { admin = it },
                    placeholder = { Text("Admin token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        enabled = !busy && admin.isNotBlank(),
                        modifier = Modifier.weight(1f).tvFocusHighlight(),
                        onClick = {
                            scope.launch {
                                busy = true
                                message = "Connecting your TVs…"
                                val (added, missed) = connectDevices(hub.host, hub.port, admin.trim())
                                admin = ""
                                message = when {
                                    added == 0 && missed == 0 -> "Every device is already using the hub."
                                    missed == 0 -> "Connected $added ${if (added == 1) "device" else "devices"}."
                                    else -> "Connected $added. $missed couldn't be reached — " +
                                        "switch them on and try again."
                                }
                                busy = false
                            }
                        }
                    ) { Text(if (busy) "Connecting…" else "Connect my TVs") }
                    if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                    if (removable) OutlinedButton(
                        enabled = !busy,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.tvFocusHighlight(),
                        onClick = { pendingRemove = true }
                    ) { Text("Remove") }
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    SettingsCard {
        Text(
            "A hub is a small server you run yourself — on a NAS, a Pi, any machine " +
                "that stays on. It holds the same settings your devices do, so two " +
                "parents stay in step without both being home.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Precise on purpose. A TV reconciles when Pickwick opens on it, not while
        // it is asleep — there is no background sync — and this screen promised the
        // latter twice before anybody checked.
        Text(
            "Your TVs are connected to it too, automatically. They pick up new " +
                "videos and rules whenever Pickwick opens on them, without waiting " +
                "for a parent's phone to be nearby.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Entirely optional. Without one, everything works exactly as it does now.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Hub address") },
            placeholder = { Text("192.168.1.245:8765") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = admin,
            onValueChange = { admin = it },
            label = { Text("Admin token") },
            // Printed in the hub's container log on first start, or set by you in
            // the compose file. Masked because it is the secret that can add
            // devices, and this screen gets shown to people.
            supportingText = { Text("Shown in the hub's log when it starts") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CompactButton(
                enabled = !busy && host.isNotBlank() && admin.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        message = "Connecting…"
                        val name = android.os.Build.MODEL ?: "A phone"
                        val token = admin.trim()
                        HubEnrolment.join(host, HubEnrolment.DEFAULT_PORT, token, name).fold(
                            onSuccess = { device ->
                                pairingStore.addPaired(device)
                                // A phone that administers anything is a parent
                                // device, the same rule the QR flow applies.
                                pairingStore.setRole(PairingStore.Role.PARENT)

                                // Straight on to the TVs, in the same action. Left
                                // as a separate step it would be the step nobody
                                // did, and the hub would sit there doing half of
                                // what the screen above it promises.
                                message = "Connecting your TVs…"
                                val (added, missed) =
                                    connectDevices(device.host, device.port, token)
                                admin = ""
                                message = when {
                                    added == 0 && missed == 0 -> null
                                    missed == 0 ->
                                        "Connected, and $added ${if (added == 1) "TV" else "TVs"} now use it too."
                                    else ->
                                        "Connected, and $added of ${added + missed} TVs now use it. " +
                                            "Switch the others on and use \"Connect my TVs\"."
                                }
                                // The reload is what flips this card to its
                                // joined shape; the message above survives it.
                                fleet.reload()
                                onFleetChanged()
                            },
                            onFailure = { e -> message = describe(e) }
                        )
                        busy = false
                    }
                }
            ) { Text(if (busy) "Connecting…" else "Connect") }
            if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
