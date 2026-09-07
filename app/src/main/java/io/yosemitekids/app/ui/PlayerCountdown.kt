package io.yosemitekids.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.yosemitekids.app.data.Remaining
import io.yosemitekids.app.data.interpolateRemainingMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// ---------------------------------------------------------------------------
// THE PLAYER'S DAILY COUNTDOWN — a chip that appears over the video inside
// the last five minutes and drains a ring as they go.
//
// It is the player's own number, not the chrome's (see TimeLeft.kt for why
// the two are deliberately different): it is read at the playing source's
// drain rate, so on a half-price channel it is twice the home pill and on a
// FREE one only a closing bedtime window counts down.
//
// Ticking is by interpolation for the same reason the chrome's is —
// `SessionGuard.remainingAll()` writes to preferences on a day rollover, so
// it is read on the cadence the player already has (the 5-second tick, a
// fresh video, a change of drain rate) and aged in between. What is aged and
// how is `CountdownAnchor.remainingAt`, which is pure and pinned in
// `PlayerCountdownTest`. The one input the chrome does not have is how much
// of the interval was actually spent playing, which [PlayClock] keeps.
// ---------------------------------------------------------------------------

/** The countdown shows inside this much of the end. */
internal const val COUNTDOWN_WINDOW_MS = 5 * 60_000L

/**
 * Milliseconds of real playback so far, monotonic, fed by the player's
 * `isPlaying` flips. A position delta would do the same job until the first
 * seek; this cannot be dragged backwards by one.
 *
 * Takes its clock as a function so a test can hold time still.
 */
internal class PlayClock(private val now: () -> Long) {
    @Volatile
    private var accumulated = 0L
    @Volatile
    private var playingSince: Long? = null

    fun setPlaying(playing: Boolean) {
        val since = playingSince
        if (playing) {
            if (since == null) playingSince = now()
        } else if (since != null) {
            accumulated += now() - since
            playingSince = null
        }
    }

    fun playedMs(): Long = accumulated + (playingSince?.let { now() - it } ?: 0L)
}

/**
 * The player's last authoritative read of what can stop playback, stamped
 * with the two clocks it is aged on: the wall clock for a window, the play
 * clock for a budget.
 */
internal data class CountdownAnchor(
    val reads: List<Remaining>,
    /** `SystemClock.elapsedRealtime()` at the read. */
    val readAtMs: Long,
    /** [PlayClock.playedMs] at the read. */
    val playedAtReadMs: Long
) {
    /** What is left at [nowMs] with the play clock at [playedNowMs]; null when no rule applies. */
    fun remainingAt(nowMs: Long, playedNowMs: Long): Long? =
        interpolateRemainingMs(reads, nowMs - readAtMs, playedNowMs - playedAtReadMs)
}

/** How full the warning ring is: 1 at the edge of the window, 0 at nothing left. */
internal fun countdownRingFraction(remainingMs: Long, windowMs: Long = COUNTDOWN_WINDOW_MS): Float =
    (remainingMs.toFloat() / windowMs).coerceIn(0f, 1f)

/**
 * Whether the chip is drawn at all. Never while listening: the screen is off
 * or the picture is gone, and the number would be read at the wrong rate —
 * the anchor is re-seeded on the way back in.
 */
internal fun countdownShows(
    remainingMs: Long?,
    listening: Boolean,
    windowMs: Long = COUNTDOWN_WINDOW_MS
): Boolean = !listening && remainingMs != null && remainingMs <= windowMs

/** One tick of the chip: the allowance left, and how much of this video is. */
internal data class CountdownFrame(val remainingMs: Long, val videoLeftMs: Long?)

/**
 * The 1 Hz tick behind the chip. An effect and nothing drawn, so the portrait
 * column and the full-bleed stage read one frame rather than each running a
 * clock of their own. Restarts on a new anchor or a change of listening, and
 * writes null the moment there is nothing to show.
 */
@Composable
internal fun PlayerCountdownTicker(
    anchor: State<CountdownAnchor?>,
    listening: State<Boolean>,
    /** `elapsedRealtime`, the same clock the anchor was stamped on. */
    now: () -> Long,
    playedMs: () -> Long,
    /** This video's duration minus its position, or null before either is known. */
    videoLeftMs: () -> Long?,
    into: MutableState<CountdownFrame?>
) {
    val a = anchor.value
    val quiet = listening.value
    LaunchedEffect(a, quiet) {
        if (a == null || quiet) {
            into.value = null
            return@LaunchedEffect
        }
        while (isActive) {
            val left = a.remainingAt(now(), playedMs())
            into.value = if (countdownShows(left, quiet)) CountdownFrame(left!!, videoLeftMs()) else null
            delay(1_000)
        }
    }
}

/**
 * A ring that empties clockwise from the top, around whatever sits inside it.
 * Drawn, not a progress indicator: Material's spins the other way and has
 * a cap the design does not.
 */
@Composable
internal fun CountdownRing(
    fraction: Float,
    color: Color,
    track: Color,
    size: Dp,
    stroke: Dp,
    content: @Composable () -> Unit
) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(
                color = track, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(w)
            )
            if (fraction > 0f) drawArc(
                color = color, startAngle = -90f, sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                useCenter = false, topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(w, cap = StrokeCap.Round)
            )
        }
        content()
    }
}

/**
 * "5 min left / This video ends in 4:52", behind an amber ring around a dark
 * disc that says the same number short. Amber and only amber: this is
 * [KidTokens.timeWarning]'s whole job, and the end card's own countdown is
 * drawn in the action colour precisely so the two are never confused.
 *
 * [onArtwork] is the full-bleed stage, where the chip rides the video and
 * takes the scrim; on the portrait page it takes the card surface and the
 * page's own text colour, so it survives the light look.
 */
@Composable
internal fun DailyCountdownChip(
    frame: CountdownFrame,
    onArtwork: Boolean,
    modifier: Modifier = Modifier,
    formFactor: FormFactor = LocalFormFactor.current
) {
    val tokens = kidTokens
    val tv = formFactor.isTv
    // TV geometry is PROVISIONAL: sized from the handoff's 720p frame through
    // tvUnits and not yet seen on the TV emulator.
    val disc = if (tv) tvUnits(44f) else 36.dp
    val ring = if (tv) tvUnits(4f) else 3.dp
    val radius = if (tv) tvUnits(16f) else 14.dp
    val fill = if (onArtwork) tokens.artworkScrim else MaterialTheme.colorScheme.surfaceContainer
    val text = if (onArtwork) tokens.onArtwork else MaterialTheme.colorScheme.onSurface
    val quiet = if (onArtwork) tokens.onArtwork.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    val track = if (onArtwork) tokens.onArtwork.copy(alpha = 0.14f) else MaterialTheme.colorScheme.outlineVariant
    val ends = frame.videoLeftMs?.let { "This video ends in " + formatClock(it / 1000) }
    val sentence = remainingLabel(frame.remainingMs) + (ends?.let { ". $it" } ?: "")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(fill, RoundedCornerShape(radius))
            .border(1.dp, tokens.timeWarning, RoundedCornerShape(radius))
            .padding(horizontal = if (tv) tvUnits(14f) else 12.dp, vertical = if (tv) tvUnits(10f) else 10.dp)
            // The disc says "5m"; a screen reader gets the sentence once.
            .semantics { contentDescription = sentence }
    ) {
        CountdownRing(
            fraction = countdownRingFraction(frame.remainingMs),
            color = tokens.timeWarning,
            track = track,
            size = disc,
            stroke = ring
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(disc - ring * 3)
                    .background(MaterialTheme.colorScheme.scrim, CircleShape)
            ) {
                Text(
                    remainingShort(frame.remainingMs),
                    color = tokens.timeWarning,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = if (tv) 12.sp else 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
        Spacer(Modifier.width(if (tv) tvUnits(12f) else 10.dp))
        Column {
            Text(
                remainingLabel(frame.remainingMs),
                color = text,
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = if (tv) 16.sp else 14.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            if (ends != null) Text(
                ends,
                color = quiet,
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = if (tv) TextUnit(14.5f, TextUnitType.Sp) else TextUnit(12.5f, TextUnitType.Sp)
                )
            )
        }
    }
}
