package io.yosemitekids.app.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.yosemitekids.app.data.Remaining
import io.yosemitekids.app.data.interpolateRemainingMs
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// THE LIVE TIME-LEFT VALUE — one source for every piece of chrome.
//
// The number used to be a snapshot taken when the home screen published, and
// the only browse-layer loop that refreshed it runs every FIVE MINUTES. That
// was survivable while it sat on one screen; the design puts it in permanent
// chrome on every page, where a kid can watch it not move.
//
// The fix is not a faster poll. `SessionGuard.remainingAll()` calls
// `rolloverIfNewDay()`, which WRITES to preferences — polling it at 1 Hz
// would turn a top bar into a disk write a second, on a television that may
// sit on this screen for hours. So the authoritative read keeps its existing
// cadence (a home publish, coming back from the player, the five-minute
// sweep) and this ages it in between.
//
// Ageing is not "hold the number while paused", which is wrong exactly when
// it matters most: bedtime arrives on the clock on the wall whether or not
// anything is playing, and a kid staring at a paused screen is precisely the
// kid a bedtime is closing in on. Which candidate ticks on which clock is
// `LimitKind`, and the arithmetic is `interpolateRemainingMs` — pure, and
// unit-tested in `TimeLeftInterpolationTest` without a device.
//
// DELIBERATELY NOT THE PLAYER'S NUMBER. The pill is always the 100%-drain
// daily figure — what every screen's chrome says. The player draws its own
// chip from `sessionGuard.remainingMs(drain, listening)` at the playing
// source's drain rate, which on a 50% channel is twice this and on a FREE one
// is unbounded. They differ on purpose: this one answers "how much of today
// is left", the player's answers "how long can THIS go on". Do not unify them.
// ---------------------------------------------------------------------------

/** The absent value: no rule applies, so no pill is drawn. */
internal val NoTimeLeft: State<Long?> = mutableStateOf(null)

/**
 * The live "N min left" the chrome shows, ticking once a second.
 *
 * Returns a [State] rather than a `Long?` on purpose: the caller passes the
 * State down without reading it, so only the pill itself recomposes each
 * second instead of every screen the value travels through.
 *
 * Null while [blocked] — a rule is stopping playback outright and the banner
 * says so in words — and null when [reads] is empty, which is a family with
 * no limits configured at all. **Null hides the pill; it is never drawn with
 * a placeholder**, because a dash where a number belongs reads to a kid as
 * "nought minutes left".
 *
 * A container: it is called once, high up, and its value fans out.
 */
@Composable
internal fun rememberTimeLeftMs(reads: List<Remaining>, blocked: Boolean): State<Long?> {
    val live = remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(reads, blocked) {
        if (blocked || reads.isEmpty()) {
            live.value = null
            return@LaunchedEffect
        }
        // elapsedRealtime, not currentTimeMillis: it cannot be dragged
        // backwards by an NTP correction or by a parent setting the clock,
        // and it keeps running while the device dozes — both of which would
        // otherwise hand a kid minutes back.
        val readAt = SystemClock.elapsedRealtime()
        while (true) {
            // playedMs stays 0: nothing plays behind the browse chrome — the
            // player is its own activity with its own chip. So budget and
            // sitting candidates hold, and only a closing window counts down,
            // which is the truth on this screen.
            live.value = interpolateRemainingMs(reads, SystemClock.elapsedRealtime() - readAt)
            delay(1_000)
        }
    }
    return live
}

/**
 * The chrome's time pill, or nothing at all.
 *
 * The one composable that reads the live value, so the second-by-second
 * recomposition stops here rather than travelling up into a whole page. The
 * hidden-when-null rule lives here too, so no call site can get it wrong —
 * including [leadingGap], the space before the pill in a row that does not
 * space its children itself. That has to be drawn *here*: asking "is there a
 * pill?" at the call site to decide whether to draw a spacer would read the
 * value there and pull the whole surrounding row into the tick.
 */
@Composable
internal fun TimeLeftPill(left: State<Long?>, leadingGap: Dp = 0.dp) {
    val ms = left.value ?: return
    if (leadingGap > 0.dp) Spacer(Modifier.width(leadingGap))
    TimeChip(ms)
}
