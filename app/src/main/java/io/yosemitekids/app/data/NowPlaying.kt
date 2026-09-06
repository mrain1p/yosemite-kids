package io.yosemitekids.app.data

/** What this device is playing right now, for the parent's stats screen. */
object NowPlaying {

    data class State(
        val title: String,
        val channel: String,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val updatedAt: Long
    )

    @Volatile
    private var state: State? = null

    fun update(title: String, channel: String, positionMs: Long, durationMs: Long, playing: Boolean) {
        state = State(title, channel, positionMs, durationMs, playing, System.currentTimeMillis())
    }

    fun clear() {
        state = null
    }

    /** Null once the player stops updating (stale after 30s). */
    fun current(): State? =
        state?.takeIf { System.currentTimeMillis() - it.updatedAt < 30_000 }
}

/**
 * What the player asked the home screen to do once it comes back: the kid
 * tapped the channel avatar, so open that channel. A flag rather than an
 * activity result — the home screen reads it on resume, whichever way the
 * player closed.
 */
object PlayerRequests {
    @Volatile
    var openChannel: String? = null
}

/**
 * Bridge from the LAN server to whatever player is on screen, so a parent's
 * phone can pause/resume playback ("come to dinner"). The active PlayerActivity
 * registers itself; null means nothing is playing.
 */
/**
 * How many of the app's activities are started (visible or about to be).
 * Android 10+ silently drops an activity start from a process with no
 * visible window, so a LAN "Play on TV" that arrives while the TV sits on
 * its launcher must be refused rather than reported as playing.
 */
object AppVisibility {
    @Volatile
    var startedActivities: Int = 0

    val inForeground: Boolean get() = startedActivities > 0

    /**
     * Whether an activity started from this process right now would be
     * shown. The rule the "Play on TV" refusal applies, named once so the
     * update prompt from a phone (`POST /check-updates`) applies the same
     * one rather than a second copy that drifts.
     */
    val canStartActivity: Boolean
        get() = android.os.Build.VERSION.SDK_INT < 29 || inForeground
}

object RemotePlayerControl {
    /** Handles "pause" | "play"; returns false when there is no active player. */
    @Volatile
    var handler: ((String) -> Boolean)? = null

    /**
     * Who installed [handler]. A new player's onCreate runs before the old
     * one's onDestroy, so the old one may only clear what it installed.
     */
    @Volatile
    var owner: Any? = null

    /**
     * "Play this on the TV" from a parent's phone. [timePercent] is the
     * source's screen-time drain rate as the phone knows it — the device
     * only has a URL and a channel name, not the whitelist entry behind them.
     */
    data class PlayRequest(
        val url: String,
        val title: String,
        val channel: String,
        val thumb: String?,
        val timePercent: Int
    )

    /**
     * Registered by the home activity for the life of the process: launches
     * the player for [PlayRequest]s the device's own rules allow. Returns
     * false when the video is blocked here or nothing can show it.
     */
    @Volatile
    var playHandler: ((PlayRequest) -> Boolean)? = null
}
