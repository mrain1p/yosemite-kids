package io.yosemitekids.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yosemitekids.app.data.HubEnrolment
import io.yosemitekids.app.data.LanClient
import io.yosemitekids.app.data.PairedDevice
import io.yosemitekids.app.data.PairingStore
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
 * What that buys, precisely: a TV reconciles when Yosemite Kids opens on it and
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
    removable: Boolean = false,
    /**
     * Open the card with the hub's name, for the one page where this card is
     * the only place the name can be changed. Off wherever the hub's device
     * page is a chevron away: that page's "This entry" card renames it, and
     * two editors for one name read as two different names.
     */
    nameable: Boolean = false
) {
    val scope = rememberCoroutineScope()

    val hub = fleet.hub
    var host by remember { mutableStateOf("") }
    // The secret the parent is typing. Held in composition and nowhere else:
    // it is cleared the moment it has been used, and no path here writes it to
    // PairingStore, to prefs or to the config. What IS stored is what the hub
    // hands back — a per-device enrolment token — which is a credential for
    // this household's hub alone. A parent's password, which a family will
    // have reused elsewhere, never lands on disk on this phone.
    var admin by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    // What the hub calls its own secret, from GET /setup. Null until something
    // answers, and false on a hub too old to have the route — which is the
    // truth for that build, not a fallback.
    var hubHasPassword by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingRemove by remember { mutableStateOf(false) }

    // One field, one word for it, decided by the hub rather than by asking a
    // parent which kind of secret they are holding.
    val secretLabel = if (hubHasPassword == true) "Hub password" else "Admin token"
    val secretHint =
        if (hubHasPassword == true) "The password you set on the hub"
        else "Shown in the hub's log when it starts"

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
    suspend fun connectDevices(hubHost: String, hubPort: Int, adminToken: String): HubConnect {
        var added = 0
        var missed = 0
        for (device in pairingStore.paired().filterNot { it.secretless }) {   // never the hub itself
            val status = LanClient.fullStatus(device)
            when {
                status == null -> missed++
                status.hasHub -> Unit
                else -> {
                    val minted = HubEnrolment.tokenFor(hubHost, hubPort, adminToken, device.name)
                    val refusal = (minted.exceptionOrNull() as? HubEnrolment.HubError)?.failure
                    // Stop on a secret the hub has already refused, rather
                    // than presenting it once per television. Every extra
                    // attempt is another failure against a lockout that
                    // doubles — a household with four TVs could hand itself a
                    // six-hour wait from one mistyped password, and the fourth
                    // refusal reads no differently from the first.
                    if (refusal is HubEnrolment.Failure.BadAdminSecret ||
                        refusal is HubEnrolment.Failure.Throttled
                    ) {
                        return HubConnect(added, missed, refusal)
                    }
                    val token = minted.getOrNull()
                    if (token == null) missed++
                    else if (LanClient.sendHubDetails(device, hubHost, hubPort, token)) added++
                    else missed++
                }
            }
        }
        return HubConnect(added, missed)
    }

    fun describe(e: Throwable): String = when (val f = (e as? HubEnrolment.HubError)?.failure) {
        // Each of these sends a parent somewhere different, so none of them
        // collapses into "failed".
        HubEnrolment.Failure.Unreachable ->
            "Nothing answered at that address. Is the hub running, and is this phone on the same network?"
        HubEnrolment.Failure.NotAHub ->
            "Something answered, but it isn't a Yosemite Kids hub. Check the address and port."
        HubEnrolment.Failure.BadAdminSecret ->
            if (hubHasPassword == true) "That hub password was refused."
            else "That admin token was refused. It's printed in the hub's log when it starts."
        is HubEnrolment.Failure.Throttled ->
            "Too many wrong tries: the hub is refusing every attempt for another " +
                "${waitFor(f.retryAfterSeconds)}, this one included. The recovery token " +
                "in the hub's log still works."
        is HubEnrolment.Failure.Refused ->
            "The hub refused this attempt. Try again — codes are only good for a few minutes."
        null -> "Couldn't connect: ${e.message?.take(120)}"
    }

    if (hub != null) {
        // Ask the hub the moment the card shows, so "Connected" is what it
        // says now and not what the last Devices sweep found. The fleet lives
        // above the pages, so a repeat visit renders the last answer at once.
        LaunchedEffect(hub.key) { fleet.refresh(hub) }
        // And ask what it calls its secret. Unauthenticated, one key, and off
        // the main thread inside probe(); a hub that is off simply leaves the
        // field labelled the way it was last time.
        LaunchedEffect(hub.key) {
            HubEnrolment.probe(hub.host, hub.port).onSuccess { hubHasPassword = it.hasPassword }
        }

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

        // A name field only where this card is the only name editor
        // ([nameable]) — everywhere the hub's own page is a chevron away, that
        // page's "This entry" card renames it, and two editors for one name on
        // one screen read as two different names.
        SettingsCard(padded = false) {
            if (nameable) {
                var name by remember(hub.key) { mutableStateOf(hub.name) }
                // On Done and on losing focus, never per keystroke:
                // renamePaired and reload are synchronous SharedPreferences
                // work on the main thread.
                fun commitName() {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty() && trimmed != hub.name) {
                        pairingStore.renamePaired(hub.key, trimmed)
                        fleet.reload()
                        onFleetChanged()
                    }
                }
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Called",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // The same hand-built field as "Called" on This phone: the
                    // title above already says what it is, so an M3 floating
                    // label would say it twice.
                    Box(
                        Modifier
                            .padding(top = 7.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                            .tvFocusHighlight(cornerRadius = 10.dp)
                            .padding(horizontal = 11.dp, vertical = 12.dp)
                    ) {
                        BasicTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { commitName() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) commitName() }
                        )
                    }
                    Text(
                        "The name it shows under Kid devices and beside the changes it makes.",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                SettingsDivider()
            }
            // The address, and whether anything is answering there right now.
            // "Connected" is the fleet's word, not a stored flag: a hub that
            // was joined and then switched off says Offline.
            val (state, tone) = when (fleet.syncStates[hub.key]) {
                is DeviceSync.Reachable -> "Connected" to SettingsSuccess
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
                    "Added a TV since? Enter the ${secretLabel.lowercase()} again to connect it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = admin,
                    onValueChange = { admin = it },
                    placeholder = { Text(secretLabel) },
                    singleLine = true,
                    visualTransformation = secretMask(showSecret),
                    keyboardOptions = secretKeyboard(),
                    trailingIcon = { RevealToggle(showSecret) { showSecret = !showSecret } },
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
                                val outcome = connectDevices(hub.host, hub.port, admin.trim())
                                admin = ""
                                showSecret = false
                                message = when {
                                    // First, because it is the reason the rest
                                    // of the count is short.
                                    outcome.refused != null ->
                                        describe(HubEnrolment.HubError(outcome.refused)) +
                                            " Nothing further was tried."
                                    outcome.added == 0 && outcome.missed == 0 ->
                                        "Every device is already using the hub."
                                    outcome.missed == 0 ->
                                        "Connected ${outcome.added} ${if (outcome.added == 1) "device" else "devices"}."
                                    else -> "Connected ${outcome.added}. ${outcome.missed} couldn't be reached — " +
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

    // Ask the address the parent has typed what it calls its secret, once they
    // have stopped typing. Debounced rather than per-keystroke: this opens a
    // socket, and half an address is a socket to nowhere. The label is worth
    // the round trip — a parent holding a password they set on the hub should
    // not be reading the word "token".
    LaunchedEffect(host) {
        val typed = host.trim()
        if (typed.isBlank()) {
            hubHasPassword = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(700)
        HubEnrolment.probe(typed, HubEnrolment.DEFAULT_PORT)
            .onSuccess { hubHasPassword = it.hasPassword }
    }

    SettingsCard {
        Text(
            "A hub is a small server you run yourself — on a NAS, a Pi, any machine " +
                "that stays on. It holds the same settings your devices do, so two " +
                "parents stay in step without both being home.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Precise on purpose. A TV reconciles when Yosemite Kids opens on it, not while
        // it is asleep — there is no background sync — and this screen promised the
        // latter twice before anybody checked.
        Text(
            "Your TVs are connected to it too, automatically. They pick up new " +
                "videos and rules whenever Yosemite Kids opens on them, without waiting " +
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
            // One field whatever the hub is holding. Before a password is set
            // that is the token printed in the container log; after, it is the
            // password — and the recovery token from the log still works in
            // the same box, because the hub checks both against one header.
            label = { Text(secretLabel) },
            // Masked because it is the secret that can add devices, and this
            // screen gets shown to people. Revealable because a masked field
            // is where a long typed secret goes wrong silently.
            supportingText = { Text(secretHint) },
            singleLine = true,
            visualTransformation = secretMask(showSecret),
            keyboardOptions = secretKeyboard(),
            trailingIcon = { RevealToggle(showSecret) { showSecret = !showSecret } },
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
                                val outcome = connectDevices(device.host, device.port, token)
                                admin = ""
                                showSecret = false
                                message = when {
                                    // The secret was good enough to join with,
                                    // so this is the lockout closing behind a
                                    // fan-out rather than a wrong password.
                                    outcome.refused != null ->
                                        "Connected. " + describe(HubEnrolment.HubError(outcome.refused)) +
                                            " Use \"Connect my TVs\" once it lifts."
                                    outcome.added == 0 && outcome.missed == 0 -> null
                                    outcome.missed == 0 ->
                                        "Connected, and ${outcome.added} ${if (outcome.added == 1) "TV" else "TVs"} now use it too."
                                    else ->
                                        "Connected, and ${outcome.added} of ${outcome.added + outcome.missed} TVs now use it. " +
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

/**
 * What a fan-out across the televisions achieved, and why it stopped.
 *
 * [refused] is non-null when the hub rejected the secret or was already
 * refusing every attempt. The counts are still meaningful — they say how far
 * the run got — and the message leads with the refusal, because it is the
 * reason the rest of the count is short.
 */
private data class HubConnect(
    val added: Int,
    val missed: Int,
    val refused: HubEnrolment.Failure? = null
)

/** A lockout in the units a parent waits in. */
private fun waitFor(seconds: Int): String = when {
    seconds >= 5400 -> "${(seconds + 1800) / 3600} hours"
    seconds >= 3600 -> "an hour"
    seconds >= 60 -> "${(seconds + 59) / 60} minutes"
    else -> "a moment"
}

/**
 * The keyboard a secret is typed on.
 *
 * The field set none at all, so a phone was free to autocapitalise and
 * autocorrect a password — and the failure that produces is a refused sign-in
 * with a lockout behind it, for a password the parent typed correctly.
 */
private fun secretKeyboard() = KeyboardOptions(
    keyboardType = KeyboardType.Password,
    autoCorrect = false
)

private fun secretMask(shown: Boolean) =
    if (shown) VisualTransformation.None else PasswordVisualTransformation()

/**
 * Show/hide for a masked field. Words rather than an eye glyph: this screen
 * also runs on a television, where a focus ring around a labelled button is
 * legible from a sofa and a 20dp icon is not.
 */
@Composable
private fun RevealToggle(shown: Boolean, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = Modifier.tvFocusHighlight()) {
        Text(if (shown) "Hide" else "Show", style = MaterialTheme.typography.bodySmall)
    }
}
