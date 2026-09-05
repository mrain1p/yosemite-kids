package io.yosemitekids.app.data

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager

/** Rough connection tier from the OS link estimate — enough to pick sane quality. */
object NetworkQuality {

    enum class Tier { LOW, MEDIUM, HIGH }

    private fun downstreamKbps(context: Context): Int {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)?.linkDownstreamBandwidthKbps ?: 0
    }

    fun tier(context: Context): Tier {
        val kbps = downstreamKbps(context)
        return when {
            kbps >= 20_000 -> Tier.HIGH
            kbps >= 5_000 -> Tier.MEDIUM
            else -> Tier.LOW
        }
    }

    fun isTv(context: Context): Boolean =
        (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager)
            .currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

/** Resolution targets, set once at app start from connection + device. */
object QualityTargets {
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

    fun configure(context: Context) {
        val tier = NetworkQuality.tier(context)
        val tv = NetworkQuality.isTv(context)
        videoThumbMinWidth = when (tier) {
            NetworkQuality.Tier.HIGH -> if (tv) 480 else 320
            NetworkQuality.Tier.MEDIUM -> 320
            NetworkQuality.Tier.LOW -> 240
        }
        avatarMinWidth = when (tier) {
            NetworkQuality.Tier.HIGH -> 320
            else -> 160
        }
        playbackMaxHeight = when (tier) {
            NetworkQuality.Tier.HIGH -> if (tv) 1080 else 720
            NetworkQuality.Tier.MEDIUM -> if (tv) 720 else 480
            NetworkQuality.Tier.LOW -> null // muxed fallback
        }
        autoMaxHeight = playbackMaxHeight
    }
}
