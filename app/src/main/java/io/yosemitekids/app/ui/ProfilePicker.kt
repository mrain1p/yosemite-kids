package io.yosemitekids.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.isValidDirectionPin

/**
 * The kid's face of a profile: a colored circle with a bundled Fluent Emoji
 * image when the APK carries one, else the emoji itself rendered large. The
 * lookup-by-name fallback means a missing asset degrades to text, never a hole.
 */
@Composable
fun ProfileAvatar(profile: Profile, size: Int, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resId = remember(profile.avatar) {
        val name = FLUENT_AVATARS[profile.avatar]
        if (name == null) 0
        else context.resources.getIdentifier(name, "drawable", context.packageName)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color(profile.colorArgb))
    ) {
        if (resId != 0) {
            Image(
                painter = androidx.compose.ui.res.painterResource(resId),
                contentDescription = profile.name,
                modifier = Modifier.size((size * 0.68f).dp)
            )
        } else {
            Text(
                profile.avatar,
                fontSize = TextUnit(size * 0.5f, TextUnitType.Sp)
            )
        }
    }
}

/** Emoji → bundled Fluent Emoji 3D drawable (res/drawable-nodpi/avatar_*.png). */
val FLUENT_AVATARS: Map<String, String> = mapOf(
    "🦊" to "avatar_fox", "🐼" to "avatar_panda", "🦁" to "avatar_lion",
    "🐸" to "avatar_frog", "🐰" to "avatar_rabbit", "🦄" to "avatar_unicorn",
    "🐙" to "avatar_octopus", "🦖" to "avatar_trex",
    "🚗" to "avatar_car", "🚀" to "avatar_rocket", "🚂" to "avatar_train",
    "🚜" to "avatar_tractor", "🚁" to "avatar_helicopter", "⛵" to "avatar_sailboat",
    "🤖" to "avatar_robot", "👻" to "avatar_ghost", "🌟" to "avatar_star",
    "🌈" to "avatar_rainbow", "🍉" to "avatar_watermelon", "⚽" to "avatar_soccer",
    "🎸" to "avatar_guitar", "🧁" to "avatar_cupcake"
)

/**
 * Full-screen "Who's watching?" — one row of big tiles, D-pad and touch alike.
 * Picking a protected profile detours through the blind direction-PIN entry.
 * [remainingMinutes] (profile id → minutes left today) makes budget-stealing
 * visible on the tile itself; null entries show no number.
 */
@Composable
fun WhosWatchingScreen(
    profiles: List<Profile>,
    remainingMinutes: Map<String, Int?>,
    onOpenSettings: () -> Unit,
    onPicked: (Profile) -> Unit
) {
    var pinFor by remember { mutableStateOf<Profile?>(null) }
    // The kid's own password - the one the browser takes - as a second way
    // past the lock, typed. Offered beside the PIN, never instead of it:
    // pickerGate says when. Verified with the same KidPassword the hub uses.
    var passwordFor by remember { mutableStateOf<Profile?>(null) }

    passwordFor?.let { profile ->
        KidPasswordScreen(
            title = "${profile.name}'s password",
            onCancel = { passwordFor = null },
            onEntered = { entered ->
                if (io.yosemitekids.app.data.KidPassword.verify(profile.webPassword, entered)) {
                    passwordFor = null
                    onPicked(profile)
                    true
                } else false
            }
        )
        return
    }

    pinFor?.let { profile ->
        DirectionPinScreen(
            title = "${profile.name}'s code",
            onCancel = { pinFor = null },
            onEntered = { entered ->
                if (entered == profile.pin) {
                    pinFor = null
                    onPicked(profile)
                    true
                } else false
            },
            alternative = if (pickerGate(profile).passwordOffered) "Use the password instead" else null,
            onAlternative = { pinFor = null; passwordFor = profile }
        )
        return
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Surface(Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                "Who's watching?",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(36.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                contentPadding = PaddingValues(horizontal = 32.dp),
                modifier = Modifier.dpadHeldScrollThrottle(keys = DPAD_HORIZONTAL)
            ) {
                items(profiles.size, key = { profiles[it].id }) { i ->
                    val profile = profiles[i]
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = (if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            .tvFocusHighlight()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable {
                                if (profile.pin != null) pinFor = profile else onPicked(profile)
                            }
                            .padding(12.dp)
                    ) {
                        ProfileAvatar(profile, size = 108)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (profile.pin != null) {
                                Text("🔒 ", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                profile.name,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        remainingMinutes[profile.id]?.let { left ->
                            Text(
                                if (left <= 0) "no time left today" else "$left min left today",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
            // Still gated by the parent check inside — this is a doorway, not a hole.
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.tvFocusHighlight()
            ) {
                Text(
                    "Parent settings",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * Blind PIN entry, Google-TV style: the code is four D-pad presses
 * (↑ ↓ ◀ ▶ and the OK button) and the screen shows only dots filling up —
 * a sibling on the couch sees nothing worth memorizing. Touch devices get
 * the same five buttons, so one code works everywhere.
 *
 * [onEntered] returns false on a wrong code; the dots reset with an error.
 */
@Composable
fun DirectionPinScreen(
    title: String,
    onCancel: () -> Unit,
    onEntered: (String) -> Boolean,
    /** A second way in, offered under the pad when there is one ("Use the password instead"). */
    alternative: String? = null,
    onAlternative: (() -> Unit)? = null
) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun press(dir: Char) {
        if (entered.length >= 4) return
        entered += dir
        if (entered.length == 4) {
            val attempt = entered
            entered = ""
            wrong = !onEntered(attempt)
        } else wrong = false
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> { press('U'); true }
                        Key.DirectionDown -> { press('D'); true }
                        Key.DirectionLeft -> { press('L'); true }
                        Key.DirectionRight -> { press('R'); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { press('C'); true }
                        Key.Back, Key.Escape -> { onCancel(); true }
                        else -> false
                    }
                }
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Press the secret buttons on the remote",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (wrong) {
                Spacer(Modifier.height(8.dp))
                Text("That wasn't it — try again", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(
                                if (i < entered.length) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
            // Touch path: same four arrows as buttons. On TV these are never
            // reached — the key handler above consumes D-pad presses first.
            DirectionArrowPad(onPress = ::press)
            Spacer(Modifier.height(20.dp))
            if (alternative != null && onAlternative != null) {
                TextButton(onClick = onAlternative, modifier = Modifier.tvFocusHighlight()) { Text(alternative) }
            }
            TextButton(onClick = onCancel) { Text("Go back") }
        }
    }
}

/**
 * The typed way in: the kid's own password, the one the browser takes, so a
 * child who knows it is not stuck at the four presses on a phone. Verified
 * against the same PBKDF2 record the hub verifies against (KidPassword);
 * nothing here compares text.
 */
@Composable
fun KidPasswordScreen(
    title: String,
    onCancel: () -> Unit,
    onEntered: (String) -> Boolean
) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    fun submit() {
        if (entered.isEmpty()) return
        wrong = !onEntered(entered)
        if (wrong) entered = ""
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(24.dp)
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "The same password you use in the browser",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            androidx.compose.material3.OutlinedTextField(
                value = entered,
                onValueChange = { entered = it; wrong = false },
                singleLine = true,
                isError = wrong,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                modifier = Modifier.tvFocusHighlight()
            )
            if (wrong) {
                Spacer(Modifier.height(8.dp))
                Text("That wasn't it — try again", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onCancel, modifier = Modifier.tvFocusHighlight()) { Text("Go back") }
                androidx.compose.material3.Button(onClick = { submit() }, modifier = Modifier.tvFocusHighlight()) { Text("Unlock") }
            }
        }
    }
}

/** What stands between a tile and the profile, and whether a typed password may stand in for it. */
data class PickerGate(val askPin: Boolean, val passwordOffered: Boolean)

/**
 * The PIN is the phone's lock and stays the only thing that locks it: a kid
 * with a browser password and no PIN opens with a tap, as before 1.9.0. The
 * password is offered only beside a PIN and only when one is set, so "Use the
 * password instead" never leads to a screen that cannot unlock anything.
 * `PickerGateTest` holds the four cases.
 */
internal fun pickerGate(profile: Profile): PickerGate = PickerGate(
    askPin = profile.pin != null,
    passwordOffered = profile.pin != null && profile.webPassword != null
)

/** The D-pad diamond (↑ ↓ ← → around OK) for entering and setting codes. */
@Composable
fun DirectionArrowPad(onPress: (Char) -> Unit) {
    @Composable
    fun key(label: String, dir: Char, emphasized: Boolean = false) {
        IconButton(
            onClick = { onPress(dir) },
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (emphasized) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
        ) {
            Text(
                label,
                style = if (emphasized) MaterialTheme.typography.labelLarge
                else MaterialTheme.typography.titleLarge
            )
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        key("↑", 'U')
        Row(verticalAlignment = Alignment.CenterVertically) {
            key("←", 'L')
            key("OK", 'C', emphasized = true)
            key("→", 'R')
        }
        key("↓", 'D')
    }
}

/** Human-readable form of a stored code, for the parent editor only. */
fun directionPinArrows(pin: String): String =
    if (!isValidDirectionPin(pin)) pin
    else pin.map {
        when (it) {
            'U' -> "↑"; 'D' -> "↓"; 'L' -> "←"; 'R' -> "→"; else -> "OK"
        }
    }.joinToString(" ")
