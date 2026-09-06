package io.yosemitekids.app.data

/**
 * Resolution targets, set once at app start from connection + device.
 *
 * Android-free: the repository reads these to pick thumbnail sizes, and the
 * hub runs that repository too. What decides the tier (a link estimate, a UI
 * mode) needs a Context and stays in the app's NetworkQuality, which calls
 * [configure]; the hub leaves the defaults, which are what a phone on a
 * decent connection gets.
 */
object QualityTargets {

    /** Rough connection tier — enough to pick sane quality. */
    enum class Tier { LOW, MEDIUM, HIGH }

    @Volatile var videoThumbMinWidth: Int = 320
    @Volatile var avatarMinWidth: Int = 160
    /** Max video height for playback; null → muxed fallback (~360p). */
    @Volatile var playbackMaxHeight: Int? = 720

    /**
     * The parent's ceiling for this form factor (config), or the kid's pick
     * for the video they are watching. Null = Auto, the connection-and-device
     * choice [configure] makes. A ceiling only ever caps [autoMaxHeight]: a
     * weak connection still steps below it, and asking for 1080p on a
     * connection that can't carry it would just stall.
     */
    @Volatile var userMaxHeight: Int? = null

    /** What Auto picked, kept so a ceiling can be applied over it. */
    @Volatile private var autoMaxHeight: Int? = 720

    /** The height to resolve at: the lower of Auto's pick and any ceiling. */
    fun effectiveMaxHeight(): Int? {
        val auto = autoMaxHeight
        val cap = userMaxHeight ?: return auto
        return if (auto == null) cap else minOf(auto, cap)
    }

    fun configure(tier: Tier, tv: Boolean) {
        videoThumbMinWidth = when (tier) {
            Tier.HIGH -> if (tv) 480 else 320
            Tier.MEDIUM -> 320
            Tier.LOW -> 240
        }
        avatarMinWidth = when (tier) {
            Tier.HIGH -> 320
            else -> 160
        }
        playbackMaxHeight = when (tier) {
            Tier.HIGH -> if (tv) 1080 else 720
            Tier.MEDIUM -> if (tv) 720 else 480
            Tier.LOW -> null // muxed fallback
        }
        autoMaxHeight = playbackMaxHeight
    }
}
