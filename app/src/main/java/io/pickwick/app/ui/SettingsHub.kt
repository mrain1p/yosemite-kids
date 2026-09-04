package io.pickwick.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * This screen will also be where the TVs are introduced to the hub, because a
 * TV cannot do it for itself: its entire parent settings screen is a QR code,
 * so there is no field to type an address into and a remote is the worst
 * imaginable way to enter one. This phone is paired with both ends, so it is
 * the only thing that can make the introduction.
 *
 * The plumbing for that is built and tested — HubEnrolment.tokenFor,
 * LanClient.sendHubDetails, POST /join-hub — and is deliberately not called
 * from here yet. An enrolled TV would re-merge every five minutes forever,
 * because ProfileLooks.ack runs only on an inbound push and its overlay would
 * never clear, and its kid would stop seeing the "your rules changed" pill for
 * the same reason. Both are named in "Next up" in docs/FORK-NOTES.md.
 */
@Composable
internal fun HubSection(pairingStore: PairingStore, onJoined: () -> Unit) {
    val scope = rememberCoroutineScope()

    val hub = remember { pairingStore.paired().firstOrNull { it.name == PairedDevice.HUB_NAME } }
    var host by remember { mutableStateOf(hub?.host ?: "") }
    var admin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var joined by remember { mutableStateOf(hub != null) }

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

    if (joined) {
        Text("Connected to the hub at ${host.ifBlank { "your server" }}.")
        Text(
            "It appears under Kid devices as \"Pickwick hub\" and syncs like any " +
                "other device. Remove it there to disconnect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        // Stated, not hidden. The plumbing to connect a TV exists and is
        // tested (LanClient.sendHubDetails, HubEnrolment.tokenFor,
        // POST /join-hub), and it is deliberately not wired up yet: a TV that
        // synced with a hub today would re-merge every five minutes forever,
        // because ProfileLooks.ack only runs on an inbound push, and its kid
        // would stop getting the "your rules changed" pill for the same
        // reason. See "Next up" in docs/FORK-NOTES.md.
        Text(
            "Your TVs still get their settings from a phone, not from the hub. " +
                "Connecting them directly is coming.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Text(
        "A hub is a small server you run yourself — on a NAS, a Pi, any machine " +
            "that stays on. It holds the same settings your phones do, so two " +
            "parents stay in step without both being home.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        "TVs still get their settings from a phone. Connecting them straight " +
            "to the hub is coming.",
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
                    HubEnrolment.join(host, 8765, token, name).fold(
                        onSuccess = { device ->
                            pairingStore.addPaired(device)
                            // A phone that administers anything is a parent
                            // device, the same rule the QR flow applies.
                            pairingStore.setRole(PairingStore.Role.PARENT)
                            joined = true
                            admin = ""
                            message = null
                            onJoined()
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
