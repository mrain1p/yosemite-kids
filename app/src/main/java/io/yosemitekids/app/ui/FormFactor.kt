package io.yosemitekids.app.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Which of the two shapes this app takes. One APK ships to a phone in a hand
 * and a television across a room, and almost every layout decision downstream
 * is really this decision — how big a tile is, whether a row is a grid, and
 * whether the thing that moves is a finger or a d-pad.
 *
 * It is deliberately two values and not a size class. The split that matters
 * here is *input and distance*, not width: a tablet held at arm's length wants
 * the phone's layout at a larger size, not the ten-foot one.
 */
enum class FormFactor {
    Phone,
    Tv;

    val isTv: Boolean get() = this == Tv
    val isPhone: Boolean get() = this == Phone
}

/**
 * The one place the question is asked. Before this, the same two lines were
 * inlined in four separate files and the answer travelled onward under four
 * different names (`cards`, `rounded`, `greet`, `voice`), which made it
 * impossible to tell from a call site whether two screens were making the same
 * decision or two different ones.
 *
 * Not to be confused with the identical-looking check in `data/Pairing.kt`,
 * which answers "what kind of device is this on the LAN" and decides whether a
 * device auto-assumes the KID role. Same expression, different question —
 * folding them together would couple pairing behaviour to a layout concern.
 */
fun formFactorOf(context: Context): FormFactor =
    if ((context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
            .currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    ) FormFactor.Tv else FormFactor.Phone

/**
 * Seeded once per Activity in `onCreate`, before any composition runs.
 *
 * A process-wide value rather than something threaded through a provider,
 * because a device does not become a television: this is fixed for the life of
 * the process, and wrapping two large `setContent` bodies in another brace
 * level to carry a constant buys nothing but a diff.
 */
object DeviceShape {
    @Volatile
    var current: FormFactor = FormFactor.Phone
        private set

    fun seed(context: Context) {
        current = formFactorOf(context)
    }
}

/**
 * The form factor for everything in the composition.
 *
 * **Containers read this; leaves take a parameter defaulted from it.** That
 * rule is the whole point, and it is worth stating because the shortcut is so
 * tempting: if a leaf composable reads `LocalFormFactor.current` directly then
 * no `@Preview` and no Compose test can ever render it in the other shape, and
 * the app quietly becomes harder to check than it was before this existed. The
 * failure is silent — everything still compiles and still runs on a device.
 *
 * Guard 31 in `scripts/check.{ps1,sh}` enforces it.
 *
 * Its default reads [DeviceShape], so a preview or a test can still put either
 * shape in place with `CompositionLocalProvider(LocalFormFactor provides …)`
 * without the app needing a provider on every screen.
 */
val LocalFormFactor = staticCompositionLocalOf { DeviceShape.current }

/**
 * The floor under every ten-foot page gutter.
 *
 * Five per cent of a television panel is safe area and televisions overscan:
 * a page drawn closer to the edge than this loses its first column on somebody's
 * set, and the family that owns that set has no way to say so. 44 review pixels
 * of the handoff's 1280-wide TV frame, in the dp a 960 dp panel measures them in.
 *
 * Stated as a value rather than a comment because [TV_PAGE_GUTTER] is the number
 * that has to clear it, and `ChannelsScreenTest` says so.
 */
val TV_SAFE_GUTTER = 33.dp

/**
 * The inset every kid-facing page gets from the screen host on a television —
 * the one place the ten-foot margin is decided, so no screen has to remember it.
 */
val TV_PAGE_GUTTER = 40.dp
