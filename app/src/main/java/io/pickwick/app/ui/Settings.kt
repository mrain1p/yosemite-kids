package io.pickwick.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.LanClient
import io.pickwick.app.data.LanServer
import io.pickwick.app.data.Limits
import io.pickwick.app.data.PairedDevice
import io.pickwick.app.data.PairingStore
import io.pickwick.app.data.PairingWindow
import io.pickwick.app.data.SettingsStore
import io.pickwick.app.data.SourceCache
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.CHANNEL_LAYOUT_NEWEST
import io.pickwick.app.data.CHANNEL_LAYOUT_PLAYLISTS
import io.pickwick.app.data.CHANNEL_LAYOUT_POPULAR
import io.pickwick.app.data.CHANNEL_ORDER_ALPHA
import io.pickwick.app.data.CHANNEL_ORDER_RANDOM
import io.pickwick.app.data.CHANNEL_ORDER_WATCHED
import io.pickwick.app.data.CHANNEL_ORDER_LATEST
import io.pickwick.app.data.PLAYBACK_QUALITIES
import io.pickwick.app.data.PAGE_SIZES
import io.pickwick.app.data.qualityLabel
import io.pickwick.app.data.YouTubeRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Holds the TV's running LAN server so the settings QR can reference it. */
object LanServerHolder {
    @Volatile
    var server: LanServer? = null
}

/**
 * Parent-gated admin: biometrics on phones, PIN pad on TVs, then a form-based
 * editor — channel search, screen-time steppers, grants, pairing, updates.
 */
@Composable
fun SettingsFlow(
    settings: SettingsStore,
    configStore: ConfigStore,
    pairingStore: PairingStore,
    isTv: Boolean,
    isKidDevice: Boolean = false,
    onDone: (changed: Boolean) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity
    val biometricsAvailable = remember {
        activity != null && androidx.biometric.BiometricManager.from(context).canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }
    var stage by remember {
        mutableStateOf(
            when {
                // TV shows only the pairing QR — nothing editable to gate. (Trade-off:
                // the QR token is a pairing credential; fine while the kids are young.)
                isTv -> Stage.Editor
                biometricsAvailable -> Stage.Biometric
                settings.hasPin() -> Stage.Enter
                else -> Stage.Create
            }
        )
    }
    var firstPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler { onDone(false) }

    MaterialTheme(colorScheme = AdminDarkColors) {
    Surface(Modifier.fillMaxSize()) {
        when (stage) {
            Stage.Biometric -> {
                LaunchedEffect(Unit) {
                    val prompt = androidx.biometric.BiometricPrompt(
                        activity!!,
                        androidx.core.content.ContextCompat.getMainExecutor(context),
                        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(
                                result: androidx.biometric.BiometricPrompt.AuthenticationResult
                            ) {
                                stage = Stage.Editor
                            }

                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                if (settings.hasPin()) stage = Stage.Enter else onDone(false)
                            }
                        }
                    )
                    prompt.authenticate(
                        androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                            .setTitle("Parents only")
                            .setSubtitle("Confirm it's you to open settings")
                            .setAllowedAuthenticators(
                                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                            )
                            .build()
                    )
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Confirm it's you…",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Stage.Enter -> key(stage, error) {
                // The stretched hash costs ~100ms of CPU — off the main thread,
                // or every PIN entry drops frames on a TV box.
                val scope = rememberCoroutineScope()
                var checking by remember { mutableStateOf(false) }
                PinPad(title = "Enter parent PIN", error = error) { pin ->
                    if (!checking) {
                        checking = true
                        scope.launch {
                            val ok = kotlinx.coroutines.withContext(
                                kotlinx.coroutines.Dispatchers.Default
                            ) { settings.checkPin(pin) }
                            checking = false
                            if (ok) {
                                error = null
                                stage = Stage.Editor
                            } else {
                                error = "Wrong PIN — try again"
                            }
                        }
                    }
                }
            }
            Stage.Create -> key(stage) {
                PinPad(
                    title = "Set a parent PIN",
                    subtitle = "Parents will need this 4-digit PIN to change settings",
                    error = error
                ) { pin ->
                    firstPin = pin
                    error = null
                    stage = Stage.Confirm
                }
            }
            Stage.Confirm -> key(stage) {
                val scope = rememberCoroutineScope()
                var saving by remember { mutableStateOf(false) }
                PinPad(title = "Enter the same PIN again to confirm") { pin ->
                    when {
                        saving -> {}
                        pin == firstPin -> {
                            saving = true
                            scope.launch {
                                kotlinx.coroutines.withContext(
                                    kotlinx.coroutines.Dispatchers.Default
                                ) { settings.setPin(pin) }
                                stage = Stage.Editor
                            }
                        }
                        else -> {
                            error = "PINs didn't match — start again"
                            stage = Stage.Create
                        }
                    }
                }
            }
            Stage.Editor -> {
                // State, not the passed-in flag: dedicating the device to a kid
                // must swap this screen to the QR immediately — the parent is
                // standing there with the other phone ready to scan, and being
                // bounced out of settings first would read as "nothing happened".
                var kidDevice by remember { mutableStateOf(isKidDevice) }
                if (isTv) TvSettingsScreen(configStore, pairingStore)
                else if (kidDevice) KidDeviceScreen(configStore)
                else AdminScreen(configStore, pairingStore, onDone,
                    onBecameKidDevice = { kidDevice = true })
            }
        }
    }
    }
}

private enum class Stage { Biometric, Enter, Create, Confirm, Editor }

/**
 * Settings on a kid's phone/tablet: nothing to edit here (the parent phone
 * owns the config) — just the pairing QR, like the TV screen, phone-shaped.
 */
@Composable
private fun KidDeviceScreen(configStore: ConfigStore) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PairingPanel(configStore)
        Spacer(Modifier.height(20.dp))
        UpdateSection()
    }
}

// ---------------------------------------------------------------------------
// Admin editor
// ---------------------------------------------------------------------------

/** TV settings: no form — just the pairing QR and a live version line. All
 *  editing happens on the paired phone; pushes update this screen in place. */
@Composable
private fun TvSettingsScreen(configStore: ConfigStore, pairingStore: PairingStore) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PairingPanel(configStore, tv = true)

        // Same section the phone settings has: checks on open and offers the
        // Install button right here, so the TV updates without a computer.
        Spacer(Modifier.height(20.dp))
        UpdateSection(tv = true)
    }
}

/**
 * The pairing QR + live version line, shared by the TV settings screen and the
 * kid-phone/tablet settings section — any device running the LAN server can be
 * paired to a parent phone the same way.
 */
@Composable
internal fun PairingPanel(configStore: ConfigStore, tv: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var hash by remember { mutableStateOf("") }
    var edited by remember { mutableStateOf(0L) }
    var approved by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf(0) }
    val server = LanServerHolder.server
    val ip = remember { LanServer.localIp() }
    // Live: a push from the phone changes the fingerprint on screen within 2s,
    // so the parent can watch the two devices match — and the same tick is
    // what turns "waiting" into "paired" without anyone pressing anything.
    LaunchedEffect(Unit) {
        val store = PairingStore(context)
        while (true) {
            val snapshot = withContext(kotlinx.coroutines.Dispatchers.IO) {
                listOf(
                    ConfigStore.fingerprint(configStore.load()),
                    configStore.updatedAt().toString(),
                    store.approvedPhones().size.toString(),
                    store.pendingRequests().size.toString()
                )
            }
            hash = snapshot[0]
            edited = snapshot[1].toLongOrNull() ?: 0L
            approved = snapshot[2].toInt()
            pending = snapshot[3].toInt()
            // A QR on screen is the parent's consent to hand out the first
            // admin slot; this tick is what holds that window open, and it
            // lapses seconds after the screen goes away.
            if (server != null && ip != null) PairingWindow.keepOpen()
            kotlinx.coroutines.delay(2_000)
        }
    }
    DisposableEffect(Unit) { onDispose { PairingWindow.close() } }

    val step = when {
        server == null || ip == null -> PairStep.NoNetwork
        approved > 0 -> PairStep.Paired
        pending > 0 -> PairStep.Asking
        else -> PairStep.Waiting
    }
    // One centred column with a real measure: a TV runs this text edge to
    // edge otherwise, and the ends fall off the panel into overscan.
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (tv) 48.dp else 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = if (tv) 640.dp else 460.dp)
        ) {
            Text(
                "Manage this ${if (tv) "TV" else "device"} from your phone",
                style = MaterialTheme.typography.titleLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Scan with the parent's phone camera. Pickwick on the phone then " +
                    "controls channels and screen time here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))

            // The QR on its own white card: a code printed straight onto a
            // dark screen is hard for a phone camera to lock onto.
            if (server != null && ip != null) {
                Surface(
                    color = androidx.compose.ui.graphics.Color.White,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(4.dp)
                ) {
                    Box(Modifier.padding(14.dp)) {
                        // No secret in the QR: it only says where to *ask*. New
                        // phones need approval on the already-paired phone
                        // before they can administer.
                        val deviceName = java.net.URLEncoder.encode(android.os.Build.MODEL ?: "TV", "UTF-8")
                        QrImage("pickwick://pair?name=$deviceName&host=$ip&port=${server.port}")
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            PairStatus(step, approved)

            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(10.dp))
            // The settings fingerprint is diagnostics, not the headline: it
            // reads as a quiet footer under the thing the parent came for.
            val editedText = edited.takeIf { it > 0 }?.let {
                "  ·  edited " + java.text.SimpleDateFormat("d MMM h:mm a", java.util.Locale.US)
                    .format(java.util.Date(it))
            } ?: ""
            Text(
                "Settings #$hash$editedText",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                "Updates live — after a push from the phone both devices show the same number.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

/** Where pairing has got to, for the line under the QR. */
private enum class PairStep { NoNetwork, Waiting, Asking, Paired }

/**
 * The one line that says what is happening and what to do next — a QR with
 * nothing under it leaves a parent watching a static screen wondering whether
 * anything is working, and never says when they can leave.
 */
@Composable
private fun PairStatus(step: PairStep, approved: Int) {
    val (tint, text, detail) = when (step) {
        PairStep.NoNetwork -> Triple(
            StatusFailRed,
            "Pairing needs Wi-Fi",
            "This device isn't on the network — connect it and come back."
        )
        PairStep.Waiting -> Triple(
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Waiting for a phone to scan…",
            "Open Pickwick on the parent's phone, or point its camera here."
        )
        PairStep.Asking -> Triple(
            MaterialTheme.colorScheme.primary,
            "A phone is asking to pair",
            "Approve it on the phone that already manages this device."
        )
        PairStep.Paired -> Triple(
            StatusOkGreen,
            if (approved == 1) "Paired with 1 phone" else "Paired with $approved phones",
            "All set — you can go back. Settings arrive from the phone by themselves."
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (step) {
            PairStep.Waiting, PairStep.Asking -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = tint,
                modifier = Modifier.size(18.dp)
            )
            PairStep.Paired -> Text("✓", color = tint, style = MaterialTheme.typography.titleMedium)
            PairStep.NoNetwork -> Text("!", color = tint, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = tint)
    }
    Spacer(Modifier.height(4.dp))
    Text(
        detail,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
    )
}

@Composable
private fun AdminScreen(
    configStore: ConfigStore,
    pairingStore: PairingStore,
    onDone: (changed: Boolean) -> Unit,
    /** The parent confirmed "this device is a kid's" — swap to the QR screen. */
    onBecameKidDevice: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val yt = remember { YouTubeRepository() }

    // Bumped when the config file is replaced underneath the form (a Pull from a
    // kid device) — the whole form reloads from disk, dropping unsaved edits.
    var configEpoch by remember { mutableIntStateOf(0) }
    // Off-main read (file + JSON): opening settings shows a beat of spinner
    // instead of freezing the tap that opened them.
    val loadedConfig by produceState<Whitelist?>(initialValue = null, configEpoch) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val c = configStore.load()
            if (c.profiles.isNotEmpty()) c
            else {
                // Every family has a kid: rules, grants and pauses all live on
                // the kid's page now, so a kid-less config would have nowhere
                // to edit them. The family's existing setup becomes "Kid" —
                // the upgrade story stays "what you had, now with a name".
                // Done here, on the admin phone only, so the id is minted once
                // and reaches every device by the usual push; a TV inventing
                // its own would never agree with the phone's fingerprint.
                val kid = io.pickwick.app.data.Profile(
                    id = io.pickwick.app.data.Profile.newId(),
                    name = "Kid",
                    age = c.ai.childAge,
                    limits = c.limits.copy(pausedUntilMillis = null)
                )
                val migrated = c.copy(
                    profiles = listOf(kid),
                    // The rules moved to the kid; only "pause everyone" stays.
                    limits = Limits(pausedUntilMillis = c.limits.pausedUntilMillis)
                )
                configStore.save(migrated)
                val json = ConfigStore.toJson(migrated)
                io.pickwick.app.data.LanPushScope.scope.launch {
                    pairingStore.paired().forEach { LanClient.pushConfig(it, json) }
                }
                migrated
            }
        }
    }
    val initial = loadedConfig ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    var entries by remember(initial) { mutableStateOf(initial.sources) }
    var limits by remember(initial) { mutableStateOf(initial.limits) }
    var blocked by remember(initial) { mutableStateOf(initial.blockedVideoIds) }
    var ai by remember(initial) { mutableStateOf(initial.ai) }
    var aiAllowed by remember(initial) { mutableStateOf(initial.aiAllowedVideoIds) }
    var profiles by remember(initial) { mutableStateOf(initial.profiles) }
    var blockedFor by remember(initial) { mutableStateOf(initial.blockedFor) }
    var allowedFor by remember(initial) { mutableStateOf(initial.allowedFor) }
    var deviceProfiles by remember(initial) { mutableStateOf(initial.deviceProfiles) }
    var masterToken by remember(initial) { mutableStateOf(initial.masterDeviceToken) }
    var sponsorSkip by remember(initial) { mutableStateOf(initial.sponsorSkip) }
    var autoplayNext by remember(initial) { mutableStateOf(initial.autoplayNext) }
    var suggestSimilar by remember(initial) { mutableStateOf(initial.suggestSimilar) }
    var channelLayout by remember(initial) { mutableStateOf(initial.channelLayout) }
    var channelOrder by remember(initial) { mutableStateOf(initial.channelOrder) }
    var listenPercent by remember(initial) { mutableStateOf(initial.listenPercent) }
    var qualityTv by remember(initial) { mutableStateOf(initial.qualityTv) }
    var qualityPhone by remember(initial) { mutableStateOf(initial.qualityPhone) }
    var pageSize by remember(initial) { mutableStateOf(initial.pageSize) }
    var showVideoAge by remember(initial) { mutableStateOf(initial.showVideoAge) }
    var baseline by remember(initial) { mutableStateOf(initial) }
    /** Entries added by this session's URL import — shown with a NEW tag for review. */
    var newIds by remember { mutableStateOf(setOf<String>()) }

    // Real names (keyed by url) for entries that carry no label — pasted links
    // and file imports. A raw "UU…" id tells a parent nothing when they're
    // setting time multipliers, so resolve names the way the kid's home screen
    // does: seed from its tile cache (instant, offline), then fetch the rest —
    // memoized and rate-limited upstream, so this never hammers YouTube. Shared
    // by the channel list rows and the export, which writes them as "| Name".
    val context = androidx.compose.ui.platform.LocalContext.current
    val resolvedNames = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(entries) {
        if (resolvedNames.isEmpty()) {
            SourceCache(context).load().forEach { s ->
                if (s.url !in resolvedNames) resolvedNames[s.url] = s.name
            }
        }
        entries.filter { it.label == null && it.url !in resolvedNames }.forEach { e ->
            launch {
                runCatching { yt.source(e, background = true) }
                    .onSuccess { s -> resolvedNames[e.url] = s.name }
            }
        }
    }

    var statsDevice by remember { mutableStateOf<PairedDevice?>(null) }
    var digestOpen by remember { mutableStateOf(false) }

    // Pushes chase each other: the newest cancels the one in flight, so a
    // retry never delivers a config the parent has since edited past.
    val pushJob = remember {
        java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>()
    }

    // Stats takes over the screen while open.
    statsDevice?.let { device ->
        StatsScreen(device, configStore) { statsDevice = null }
        return
    }
    if (digestOpen) {
        WeeklyDigestScreen(pairingStore, configStore) { digestOpen = false }
        return
    }

    /**
     * The form as a config: what the auto-apply below and Push both deliver.
     * `baseline` is what disk holds as far as this form knows — the open-time
     * snapshot until the first auto-save, then whatever was last written — so
     * a second judging change after an auto-save gets its own version bump.
     */
    fun buildCurrentConfig(): Whitelist {
        // Changed rules/age/model mean old verdicts no longer apply — bumping the
        // version makes every device re-screen its catalog against the new rules.
        // Measured against the baseline, so building twice between saves yields
        // the same version, not two bumps.
        val judgingChanged = ai.rules != baseline.ai.rules ||
            ai.childAge != baseline.ai.childAge ||
            ai.model != baseline.ai.model ||
            ai.baseUrl != baseline.ai.baseUrl ||
            io.pickwick.app.data.screeningJudgmentChanged(baseline.profiles, profiles)
        val finalAi = if (judgingChanged) ai.copy(rulesVersion = baseline.ai.rulesVersion + 1) else ai
        // A removed kid must not linger: entries owned only by them fall back
        // to everyone, their per-video rulings and device assignment are dropped.
        val validIds = profiles.map { it.id }.toSet()
        fun scrub(overlay: Map<String, Set<String>>) = overlay
            .mapValues { (_, pids) -> pids.intersect(validIds) }
            .filterValues { it.isNotEmpty() }
        return Whitelist(
            entries.map { e ->
                if (e.profileIds.isEmpty()) e
                else e.copy(profileIds = e.profileIds.intersect(validIds))
            },
            blocked, limits, finalAi, aiAllowed,
            profiles, scrub(blockedFor), scrub(allowedFor),
            deviceProfiles.filterValues { it in validIds },
            masterDeviceToken = masterToken,
            sponsorSkip = sponsorSkip,
            autoplayNext = autoplayNext,
            suggestSimilar = suggestSimilar,
            channelLayout = channelLayout,
            channelOrder = channelOrder,
            listenPercent = listenPercent,
            qualityTv = qualityTv,
            qualityPhone = qualityPhone,
            pageSize = pageSize,
            showVideoAge = showVideoAge
        )
    }

    /**
     * Apply on change. There is no Save button: every edit lands on disk and
     * on the devices by itself, the way Android settings behave. Debounced,
     * because a stepper tap is rarely alone and every push to a sleeping TV
     * costs a connect timeout. Each push cancels the one before it, so a
     * superseded config never lands on a late retry.
     */
    fun pushAll(config: Whitelist) {
        val json = ConfigStore.toJson(config)
        pushJob.getAndSet(io.pickwick.app.data.LanPushScope.scope.launch {
            val devices = pairingStore.paired()
            if (devices.isEmpty()) return@launch
            // Logged per device, like the reconcile's line: "did the edit
            // reach the TV" is undiagnosable otherwise.
            suspend fun push(targets: List<PairedDevice>): List<PairedDevice> =
                targets.filterNot { d ->
                    LanClient.pushConfig(d, json).also { sent ->
                        android.util.Log.i(
                            "Pickwick",
                            "settings push → ${d.name}: ${if (sent) "accepted" else "unreachable"}"
                        )
                    }
                }
            var missed = push(devices)
            // A standby Chromecast rejoins Wi-Fi seconds after waking, and a
            // just-(re)installed app has no server until its next launch —
            // both miss the push by moments. Two quiet retries beat leaving
            // it to the 5-minute reconcile.
            repeat(2) {
                if (missed.isEmpty()) return@launch
                delay(20_000)
                missed = push(missed)
            }
        })?.cancel()
    }
    val current = buildCurrentConfig()
    val currentHash = ConfigStore.fingerprint(current)
    val baselineHash = ConfigStore.fingerprint(baseline)
    LaunchedEffect(currentHash) {
        if (currentHash == baselineHash) return@LaunchedEffect
        delay(1_500)
        withContext(kotlinx.coroutines.Dispatchers.IO) { configStore.save(current) }
        baseline = current
        pushAll(current)
    }
    // Leaving mid-debounce must not lose the last tap: flush on the way out.
    // The save lands before onDone, because closing makes MainActivity
    // re-read the file and it has to see this write.
    fun close() {
        if (currentHash == baselineHash) { onDone(true); return }
        scope.launch {
            withContext(kotlinx.coroutines.Dispatchers.IO) { configStore.save(current) }
            baseline = current
            pushAll(current)
            onDone(true)
        }
    }
    BackHandler { close() }

    // The kid page takes over the screen while open (profile, rules, today).
    // It edits the form's profile list directly; the auto-apply above does
    // the rest, so a pause tapped there is on the TV a moment later.
    var openKid by remember { mutableStateOf<Pair<io.pickwick.app.data.Profile, Boolean>?>(null) }
    openKid?.let { (kid, isNew) ->
        KidPage(
            profile = profiles.firstOrNull { it.id == kid.id } ?: kid,
            isNew = isNew,
            siblings = profiles.filter { it.id != kid.id },
            pairingStore = pairingStore,
            onBack = { openKid = null },
            onChanged = { p ->
                profiles = if (profiles.any { it.id == p.id }) profiles.map { if (it.id == p.id) p else it }
                    else profiles + p
            },
            onRemove = {
                profiles = profiles.filter { it.id != kid.id }
                openKid = null
            }
        )
        return
    }

    // --- Hub -------------------------------------------------------------------
    // The root is a short list; each row opens its own page (stock Android
    // settings shape). Everything below the header used to be one scroll of
    // eighteen sections, and "where is X" was the first support question.
    var page by remember { mutableStateOf<HubPage?>(null) }
    page?.let { p ->
        BackHandler { page = null }
        SubPage(title = p.title, onBack = { page = null }) {
            when (p) {
                HubPage.Kids -> {
                    SectionTitle("Kids")
                    SettingsCard {
                        KidsSection(profiles, onOpen = { kid, isNew -> openKid = kid to isNew })
                    }
                }
                HubPage.Channels -> {
                    SectionTitle("Channels & playlists")
                    SettingsCard {
                        ChannelsSection(entries, newIds, yt, resolvedNames, profiles, onChanged = { entries = it })
                    }
                    SectionTitle("Suggested channels")
                    SettingsCard {
                        DirectorySection(entries) { e ->
                            entries = (entries + e).distinctBy { it.id }
                            newIds = newIds + e.id
                        }
                    }
                }
                HubPage.Screening -> {
                    SectionTitle("AI content screening")
                    SettingsCard { AiScreeningSection(ai, profiles, onChanged = { ai = it }) }
                    if (ai.enabled) {
                        SectionTitle("Waiting for your OK")
                        SettingsCard {
                            AiReviewSection(
                                ai = ai,
                                profiles = profiles,
                                pairingStore = pairingStore,
                                resolved = blocked + aiAllowed + blockedFor.keys + allowedFor.keys,
                                onAllow = { id, forKids ->
                                    if (forKids == null) aiAllowed = aiAllowed + id
                                    else allowedFor = allowedFor + (id to forKids)
                                },
                                onBlock = { id, forKids ->
                                    if (forKids == null) blocked = blocked + id
                                    else blockedFor = blockedFor + (id to forKids)
                                }
                            )
                        }
                    }
                    SectionTitle("Discover with AI")
                    SettingsCard {
                        AiDiscoverySection(ai, entries, yt) { e ->
                            entries = (entries + e).distinctBy { it.id }
                            newIds = newIds + e.id
                        }
                    }
                }
                HubPage.Devices -> {
                    SectionTitle("Kid devices")
                    SettingsCard {
                        PhoneDevicesSection(
                            pairingStore, configStore,
                            onBecameKidDevice = onBecameKidDevice,
                            profiles = profiles,
                            deviceProfiles = deviceProfiles,
                            // The form's fingerprint, not the file's: between an
                            // edit and its auto-save, "in sync ✓" measured against
                            // disk would be a lie for a beat.
                            localHash = currentHash,
                            // Push must deliver what the parent is LOOKING AT.
                            saveCurrent = {
                                val config = buildCurrentConfig()
                                configStore.save(config)
                                ConfigStore.toJson(config)
                            },
                            onAssign = { token, profileId ->
                                deviceProfiles =
                                    if (profileId == null) deviceProfiles - token
                                    else deviceProfiles + (token to profileId)
                            },
                            masterToken = masterToken,
                            onMakeMaster = { token -> masterToken = token },
                            onOpenStats = { statsDevice = it },
                            onConfigReplaced = { configEpoch++ }
                        )
                        // Per-device Stats answers "what's happening today"; this
                        // answers "how did the week go" across every device.
                        CompactButton(onClick = { digestOpen = true }) { Text("Weekly digest") }
                    }
                    // Search index: who's the master, and how far each channel's
                    // crawl has got. Read-only — the master does the work.
                    SectionTitle("Search index")
                    SettingsCard { SearchIndexSection(entries, masterToken, pairingStore) }
                    SectionTitle("Offline downloads")
                    SettingsCard { DownloadsSection() }
                    SectionTitle("Videos from this phone")
                    SettingsCard { LocalVideosSection(profiles) }
                }
                HubPage.Playback -> {
                    SectionTitle("Playback")
                    SettingsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Skip sponsors & intros", modifier = Modifier.weight(1f))
                            Switch(
                                modifier = Modifier.tvFocusHighlight(),
                                checked = sponsorSkip,
                                onCheckedChange = { sponsorSkip = it }
                            )
                        }
                        Text(
                            "Skips the parts of a video the SponsorBlock community has marked: " +
                                "sponsor messages, merch plugs, intros/outros and \"like and " +
                                "subscribe\" reminders. Marked parts show in green on the TV's " +
                                "playback bar. Lookups send only an anonymous fingerprint of the " +
                                "video, never what is being watched.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SectionTitle("Listening")
                    SettingsCard { ListenRateRow(listenPercent) { listenPercent = it } }
                    SectionTitle("After a video")
                    SettingsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Autoplay the next video", modifier = Modifier.weight(1f))
                            Switch(
                                modifier = Modifier.tvFocusHighlight(),
                                checked = autoplayNext,
                                onCheckedChange = { autoplayNext = it }
                            )
                        }
                        Text(
                            "When a video the kid picked ends, the next unwatched one from the same " +
                                "channel lines up behind a short countdown (Play now / Not now). " +
                                "Screen-time rules still apply. Off: every video ends on the shelf.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SectionTitle("Suggestions")
                    SettingsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("More like what you watch", modifier = Modifier.weight(1f))
                            Switch(
                                modifier = Modifier.tvFocusHighlight(),
                                checked = suggestSimilar,
                                onCheckedChange = { suggestSimilar = it }
                            )
                        }
                        Text(
                            "A home row of older videos from the channels you have already added, " +
                                "matched to what this kid actually watched. Off: the home screen " +
                                "stays newest-first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Folded away by default. A parent deciding whether to
                        // leave this on deserves the whole mechanism, but it is
                        // three screens of text on a phone and every other
                        // setting would be below it.
                        SuggestionExplainer()
                    }
                    SectionTitle("Picture quality")
                    SettingsCard {
                        Text(
                            "A ceiling, not a target: Auto follows the connection and the " +
                                "device, and a number caps it. Kids can change it for the " +
                                "video they're watching from the player.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text("On TVs", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PLAYBACK_QUALITIES.forEach { h ->
                                FilterChip(
                                    selected = qualityTv == h,
                                    onClick = { qualityTv = h },
                                    label = { Text(qualityLabel(h)) },
                                    modifier = Modifier.tvFocusHighlight()
                                )
                            }
                        }
                        SettingsDivider()
                        Text("On phones & tablets", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PLAYBACK_QUALITIES.forEach { h ->
                                FilterChip(
                                    selected = qualityPhone == h,
                                    onClick = { qualityPhone = h },
                                    label = { Text(qualityLabel(h)) },
                                    modifier = Modifier.tvFocusHighlight()
                                )
                            }
                        }
                    }
                    SectionTitle("Kid's shelves")
                    SettingsCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Show when a video came out", modifier = Modifier.weight(1f))
                            Switch(
                                modifier = Modifier.tvFocusHighlight(),
                                checked = showVideoAge,
                                onCheckedChange = { showVideoAge = it }
                            )
                        }
                        Text(
                            "Adds \"3 days ago\" beside the channel name under a video, the way " +
                                "other video apps do. Videos whose date YouTube didn't give us " +
                                "show the channel alone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SettingsDivider()
                        Text("Videos before \"Show more\"", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PAGE_SIZES.forEach { n ->
                                FilterChip(
                                    selected = pageSize == n,
                                    onClick = { pageSize = n },
                                    label = { Text(n?.toString() ?: "All") },
                                    modifier = Modifier.tvFocusHighlight()
                                )
                            }
                        }
                        Text(
                            "Grids stop after this many videos and offer a button for the " +
                                "next batch, so a scroll has an end. All = keep loading as " +
                                "the kid scrolls.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SettingsDivider()
                        Text("Channel page layout", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                CHANNEL_LAYOUT_NEWEST to "Newest first",
                                CHANNEL_LAYOUT_POPULAR to "Popular first"
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = channelLayout == value,
                                    onClick = { channelLayout = value },
                                    label = { Text(label) },
                                    modifier = Modifier.tvFocusHighlight()
                                )
                            }
                        }
                        Text(
                            "The kid's default for a channel's page (they can switch with the chips " +
                                "on the page). Newest first is the upload feed. Popular first orders " +
                                "the same videos by how often they've been watched on YouTube (no " +
                                "numbers are ever shown). A channel's own playlists — seasons, songs, " +
                                "series — always show above the videos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SettingsDivider()
                        Text("Channel row order", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                CHANNEL_ORDER_WATCHED to "Most watched",
                                CHANNEL_ORDER_ALPHA to "A to Z",
                                CHANNEL_ORDER_RANDOM to "Random",
                                CHANNEL_ORDER_LATEST to "Latest video"
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = channelOrder == value,
                                    onClick = { channelOrder = value },
                                    label = { Text(label) },
                                    modifier = Modifier.tvFocusHighlight()
                                )
                            }
                        }
                        Text(
                            "The default order of the home screen's row of channels. Most watched " +
                                "puts the kid's favourites first; A to Z is easiest to scan; Random " +
                                "reshuffles on each visit, for the kid who always picks the first one; Latest video puts the channel that uploaded most recently first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HubPage.Backup -> {
                    SectionTitle("App")
                    SettingsCard { UpdateSection(onUpdateFound = {}) }
                    SectionTitle("Import, export & backup")
                    SettingsCard {
                        ExportSection(
                            current = {
                                // Fill resolved names in as labels so the receiving
                                // parent sees "url | Name" lines, not bare urls.
                                Whitelist(
                                    entries.map { e ->
                                        if (e.label == null) e.copy(label = resolvedNames[e.url]) else e
                                    },
                                    blocked, limits
                                )
                            },
                            onImport = { parsed ->
                                // Links only — screen-time rules are UI-managed, never file-driven.
                                val fresh = parsed.sources.filter { p -> entries.none { it.id == p.id } }
                                entries = entries + fresh
                                newIds = newIds + fresh.map { it.id }
                                fresh.size
                            },
                            onConfigReplaced = { configEpoch++ }
                        )
                    }
                }
            }
        }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Parent settings",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                )
                // The build, where a parent looks first: sideloaded TVs and
                // phones drift apart, and "which one is this?" needs no adb.
                Text(
                    "Pickwick ${io.pickwick.app.BuildConfig.VERSION_NAME} " +
                        "(${io.pickwick.app.BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(modifier = Modifier.tvFocusHighlight(), onClick = ::close) { Text("Done") }
        }
        Text(
            "Changes apply as you make them and reach the kids' devices on their own.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Donations fund the re-shipping treadmill (YouTube breaks playback every
        // few weeks). Deliberately at the top: the slot is seen on every visit.
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        ElevatedCard(
            onClick = { uriHandler.openUri("https://pickwick.tv/donate.html") },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text("❤️", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Support Pickwick", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Free forever — but not maintenance-free",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SettingsCard(padded = false) {
            val kidsLine = profiles.joinToString(", ") { it.name }.ifEmpty { "No kids yet" }
            HubRow(PickwickIcons.People, "Kids", kidsLine) { page = HubPage.Kids }
            SettingsDivider()
            HubRow(
                PickwickIcons.Channels, "Channels & playlists",
                "${entries.size} source${if (entries.size == 1) "" else "s"} · suggestions"
            ) { page = HubPage.Channels }
            SettingsDivider()
            HubRow(
                PickwickIcons.Shield, "Content screening",
                if (ai.enabled) "AI screening on · review queue, discover" else "AI screening off"
            ) { page = HubPage.Screening }
            SettingsDivider()
            HubRow(
                PickwickIcons.Devices, "Devices", "Kid devices, downloads, search index"
            ) { page = HubPage.Devices }
            SettingsDivider()
            HubRow(
                PickwickIcons.PlayArrow, "Playback", "Sponsor skipping, listening"
            ) { page = HubPage.Playback }
            SettingsDivider()
            HubRow(
                PickwickIcons.Save, "Backup & app", "Import, export, updates"
            ) { page = HubPage.Backup }
        }

        SectionTitle("Pause everyone")
        SettingsCard {
            Text(
                "Bonus minutes and a single kid's pause are on that kid's page. This " +
                    "one stops every kid at once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PauseTodayRow(
                pausedUntil = limits.pausedUntilMillis,
                onChanged = { until -> limits = limits.copy(pausedUntilMillis = until) }
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The six pages behind the settings root. */
private enum class HubPage(val title: String) {
    Kids("Kids"),
    Channels("Channels & playlists"),
    Screening("Content screening"),
    Devices("Devices"),
    Playback("Playback"),
    Backup("Backup & app")
}

/** One page of the hub: back + title, then the content in a scroll. */
@Composable
internal fun SubPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(modifier = Modifier.tvFocusHighlight(), onClick = onBack) { Text("‹ Back") }
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
        }
        content()
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * A root row: icon, title, one-line summary, chevron.
 *
 * Drawn icons, not emoji. Six emoji in a column rendered at six different
 * optical weights and colours — the one part of the app a parent lands on
 * first looked like a sticker sheet. Emoji stay where they are content: the
 * kid's avatar.
 */
@Composable
private fun HubRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusHighlight()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Icon(
            PickwickIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * The rounded, bordered group every related set of controls sits in, so a
 * parent can tell at a glance which rows belong together. Children are
 * spaced apart; [SettingsDivider] separates rows that need a firmer line.
 */
@Composable
internal fun SettingsCard(padded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = if (padded) Modifier.padding(16.dp) else Modifier.padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(if (padded) 8.dp else 0.dp),
            content = content
        )
    }
}

/**
 * What "More like what you watch" actually does, in the parent's words.
 *
 * Worth the space: a suggestion row is the one part of this app that decides
 * something on its own, and a parent who curated every channel by hand is owed
 * a plain answer to "what is it doing, and where does it get the videos". The
 * claims here are the code — see `HomeState.suggestionsFor`; if that changes,
 * this changes with it.
 */
@Composable
private fun SuggestionExplainer() {
    var open by remember { mutableStateOf(false) }
    if (!open) {
        CompactButton(onClick = { open = true }) { Text("How this works") }
        return
    }
    val points = listOf(
        "What it reads" to
            "The titles of videos this kid has opened on this device. Nothing else — " +
            "not their age, not the time of day, not what other kids watch.",
        "How it matches" to
            "It looks for words shared between those titles and videos the kid has " +
            "never opened. The more words in common, the higher a video ranks. What " +
            "they watched most recently counts for more than what they watched weeks " +
            "ago, so the row follows them as their interests move.",
        "Where the videos come from" to
            "Only the channels on your list. Turning this on cannot introduce a " +
            "channel you have not added, and blocked videos and screening still " +
            "apply exactly as they do everywhere else.",
        "What it ignores" to
            "View counts, likes, trending, and anything YouTube itself recommends. " +
            "Popularity is not part of the ranking.",
        "Keeping it varied" to
            "At most two videos per channel, so one busy channel cannot fill the row. " +
            "It reaches back through a channel's older videos, which is the point — " +
            "the rest of the home screen is newest-first.",
        "When it is empty" to
            "A kid who has not watched anything yet gets no row at all, and neither " +
            "does one whose titles have nothing in common with anything unwatched. " +
            "It fills in on its own as they watch.",
        "Where it runs" to
            "On the device, from that device's own history. Nothing about what your " +
            "kid watches is sent anywhere to build it, and each device works out its " +
            "own row rather than sharing one."
    )
    // The card spaces its children evenly, which left a heading as far from
    // its own paragraph as from the previous one — seven headings and seven
    // bodies with no visible grouping. Each pair is one child instead.
    points.forEach { (heading, body) ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(heading, style = MaterialTheme.typography.labelLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    CompactButton(onClick = { open = false }) { Text("Show less") }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
internal fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp)
    )
    Spacer(Modifier.height(8.dp))
}

/**
 * Read-only crawl status for the search index: which device is the master and
 * how complete each whitelisted source's catalog is on this device. The master
 * crawls; every device (this one included, via LAN push) shows what it holds.
 */
@Composable
private fun SearchIndexSection(
    entries: List<io.pickwick.app.data.WhitelistEntry>,
    masterToken: String?,
    pairingStore: io.pickwick.app.data.PairingStore
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val myToken = remember { pairingStore.deviceToken() }
    // Live: the shared flow ticks on every manifest write, so counts climb
    // while the parent watches instead of freezing at whatever they were when
    // the screen opened. Construction is deliberately I/O-free, so seed the
    // flow from disk here, off-main.
    val index = remember { io.pickwick.app.data.ChannelIndex(context) }
    LaunchedEffect(Unit) {
        withContext(kotlinx.coroutines.Dispatchers.IO) { index.refresh() }
    }
    val states by io.pickwick.app.data.ChannelIndex.sharedStates.collectAsState()
    val scope = rememberCoroutineScope()
    // The index is keyed by the CANONICAL source id (a @handle resolves to its
    // UC… id during the feed fetch), while entries keep their raw whitelist id.
    // SourceCache maps url → resolved Source, bridging the two — without it a
    // handle entry shows "not started" while its videos sit under the UC key.
    val canonicalByUrl by produceState<Map<String, String>>(emptyMap()) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            io.pickwick.app.data.SourceCache(context).load().associate { it.url to it.id }
        }
    }
    fun stateFor(e: io.pickwick.app.data.WhitelistEntry) =
        states[canonicalByUrl[e.url]] ?: states[e.id]
    val isMaster = masterToken != null && masterToken == myToken
    var expanded by remember { mutableStateOf(false) }

    Text(
        when {
            isMaster -> "This device is the ★ master — it builds the index and shares it."
            masterToken != null -> "Another device is the master; the index arrives over your home network."
            else -> "No master yet — the first parent device to open the app claims it."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Summary first: the per-channel list is long and rarely what the parent
    // came for. Totals answer "is search working" at a glance. Sum only the
    // sources that are still whitelisted — a dropped channel's index file can
    // outlive it on a device that hasn't run the cleanup yet.
    val whitelistedStates = entries.mapNotNull { stateFor(it) }
    val totalVideos = whitelistedStates.sumOf { it.count }
    val complete = whitelistedStates.count { it.complete }
    Spacer(Modifier.height(6.dp))
    // Top-aligned: the sentence wraps to two lines on a phone, and a
    // centre-aligned button then floated against the middle of the paragraph.
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        val channels = if (entries.size == 1) "1 channel" else "${entries.size} channels"
        Text(
            "$totalVideos videos indexed across $channels — $complete fully indexed.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        // The shared flow is process-local; a crawl that ran in the WorkManager
        // process (or before this screen opened) only appears after a disk
        // re-read. This forces one.
        IconButton(
            onClick = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { index.refresh() } },
            modifier = Modifier.size(32.dp).tvFocusHighlight()
        ) {
            // material-icons-core shipped here has no Refresh; a glyph does the job.
            Text("↻", style = MaterialTheme.typography.titleMedium)
        }
    }

    if (!expanded) {
        CompactButton(onClick = { expanded = true }) { Text("Read more") }
    } else {
        // Run diagnostics: when the master last crawled and when the next
        // background run fires, so "is it stuck?" is answerable on screen.
        if (isMaster) {
            val lastRun by io.pickwick.app.data.ChannelIndex.lastRun.collectAsState()
            // Seed from disk — the flow only ticks on runs within this process.
            LaunchedEffect(Unit) {
                if (lastRun == null) {
                    io.pickwick.app.data.ChannelIndex.lastRun.value =
                        withContext(kotlinx.coroutines.Dispatchers.IO) {
                            io.pickwick.app.data.ChannelIndex(context).lastRunInfo()
                        }
                }
            }
            val runSchedule by remember {
                io.pickwick.app.data.IndexCrawlWorker.runSchedule(context)
            }.collectAsState(initial = null)
            val fmt = remember {
                java.text.SimpleDateFormat("d MMM h:mm a", java.util.Locale.US)
            }
            Spacer(Modifier.height(8.dp))
            lastRun?.let { run ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(10.dp)
                            .background(
                                if (run.failed) StatusFailRed else StatusOkGreen,
                                androidx.compose.foundation.shape.CircleShape
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Last run ${fmt.format(java.util.Date(run.atMillis))} — " +
                            "${run.pages} page(s) indexed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } ?: Text(
                "No background run yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val sched = runSchedule
            Text(
                when {
                    sched == null -> "Next run — checking…"
                    sched.running -> "Running now…"
                    sched.nextRunAt != null ->
                        "Next run ${fmt.format(java.util.Date(sched.nextRunAt))} — pending"
                    else -> "Next run — not scheduled"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        entries.forEach { e ->
            val s = stateFor(e)
            val label = e.label ?: e.id
            val status = when {
                s == null -> "not started"
                s.complete -> "${s.count} videos ✓"
                else -> "${s.count} videos, still indexing…"
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            ) {
                Text(
                    label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (s?.complete == true) StatusOkGreen
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// PIN pad (unchanged Google-TV-style entry)
// ---------------------------------------------------------------------------

@Composable
private fun PinPad(
    title: String,
    subtitle: String? = null,
    error: String? = null,
    onComplete: (String) -> Unit
) {
    var entered by remember { mutableStateOf("") }
    var selectedRow by remember { mutableIntStateOf(0) }
    // Google TV layout: three digit rows, then a lone centered 0.
    val rows: List<List<String?>> = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(null, "0", null)
    )
    val padFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { padFocus.requestFocus() }

    fun press(label: String?) {
        label ?: return
        if (entered.length < 4) entered += label
        if (entered.length == 4) {
            val pin = entered
            entered = ""
            onComplete(pin)
        }
    }

    fun digitFor(key: Key): String? = when (key) {
        Key.Zero -> "0"; Key.One -> "1"; Key.Two -> "2"; Key.Three -> "3"; Key.Four -> "4"
        Key.Five -> "5"; Key.Six -> "6"; Key.Seven -> "7"; Key.Eight -> "8"; Key.Nine -> "9"
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .focusRequester(padFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> { selectedRow = (selectedRow + rows.size - 1) % rows.size; true }
                    Key.DirectionDown -> { selectedRow = (selectedRow + 1) % rows.size; true }
                    Key.DirectionLeft -> { press(rows[selectedRow][0]); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { press(rows[selectedRow][1]); true }
                    Key.DirectionRight -> { press(rows[selectedRow][2]); true }
                    else -> digitFor(event.key)?.let { press(it); true } ?: false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        subtitle?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            if (i < entered.length) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }

        Spacer(Modifier.height(32.dp))
        rows.forEachIndexed { rowIndex, cols ->
            val isSelected = rowIndex == selectedRow
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 6.dp)
            ) {
                cols.forEach { label ->
                    if (label == null) {
                        Spacer(Modifier.size(width = 72.dp, height = 60.dp))
                    } else {
                        PinCell(
                            label = label,
                            highlighted = isSelected,
                            onClick = { selectedRow = rowIndex; press(label) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "▲▼ choose a row  ·  ◀ / OK / ▶ pick the number",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PinCell(label: String, highlighted: Boolean, onClick: () -> Unit) {
    Card(
        shape = androidx.compose.ui.graphics.RectangleShape,
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (highlighted) {
            androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
        } else null,
        modifier = Modifier
            .clickable { onClick() }
            .size(width = 72.dp, height = 60.dp)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.titleLarge,
                color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
