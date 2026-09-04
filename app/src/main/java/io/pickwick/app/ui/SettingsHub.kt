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
 * Connecting this phone to a self-hosted hub.
 *
 * Optional, and the wording says so: a household that never runs one loses
 * nothing. What it buys is that the TV keeps getting new videos and settings
 * when no phone is home and awake, which is otherwise the app's biggest
 * practical limit.
 *
 * Once joined, the hub is stored as an ordinary paired device — so everything
 * that already syncs with a TV syncs with it, and there is no second code path
 * to keep in step.
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

    if (joined) {
        Text("Connected to the hub at ${host.ifBlank { "your server" }}.")
        Text(
            "It appears under Kid devices as \"Pickwick hub\" and syncs like any " +
                "other device. Remove it there to disconnect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    Text(
        "A hub is a small server you run yourself — on a NAS, a Pi, any machine " +
            "that stays on. It holds the same settings your devices do and keeps " +
            "them in step when your phone is out of the house.",
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CompactButton(
            enabled = !busy && host.isNotBlank() && admin.isNotBlank(),
            onClick = {
                scope.launch {
                    busy = true
                    message = "Connecting…"
                    val name = android.os.Build.MODEL ?: "A phone"
                    HubEnrolment.join(host, 8765, admin.trim(), name).fold(
                        onSuccess = { device ->
                            pairingStore.addPaired(device)
                            // A phone that administers anything is a parent
                            // device, the same rule the QR flow applies.
                            pairingStore.setRole(PairingStore.Role.PARENT)
                            joined = true
                            message = null
                            onJoined()
                        },
                        onFailure = { e ->
                            // Each of these sends a parent somewhere different,
                            // so none of them collapses into "failed".
                            message = when ((e as? HubEnrolment.HubError)?.failure) {
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
                        }
                    )
                    busy = false
                }
            }
        ) { Text(if (busy) "Connecting…" else "Connect") }
        if (busy) CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
    }
    message?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
