package io.yosemitekids.app.data

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager

/**
 * Rough connection tier from the OS link estimate — enough to pick sane
 * quality. The targets themselves are QualityTargets in :crawl (the
 * repository reads them, and the hub runs that repository); this is the
 * Android half that decides the tier and hands it over.
 */
object NetworkQuality {

    private fun downstreamKbps(context: Context): Int {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)?.linkDownstreamBandwidthKbps ?: 0
    }

    fun tier(context: Context): QualityTargets.Tier {
        val kbps = downstreamKbps(context)
        return when {
            kbps >= 20_000 -> QualityTargets.Tier.HIGH
            kbps >= 5_000 -> QualityTargets.Tier.MEDIUM
            else -> QualityTargets.Tier.LOW
        }
    }

    /** Set the resolution targets once, from this device's connection and form factor. */
    fun configureTargets(context: Context) = QualityTargets.configure(tier(context), isTv(context))

    fun isTv(context: Context): Boolean =
        io.yosemitekids.app.ui.formFactorOf(context).isTv
}
