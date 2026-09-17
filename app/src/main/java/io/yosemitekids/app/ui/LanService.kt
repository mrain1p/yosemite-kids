package io.yosemitekids.app.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.yosemitekids.app.data.ConfigStore
import io.yosemitekids.app.data.DeviceKind
import io.yosemitekids.app.data.Diag
import io.yosemitekids.app.data.PairingStore
import io.yosemitekids.app.data.ProfileNamespace

/**
 * Keeps a television's LAN server answering after the app is closed.
 *
 * The server itself is process-wide ([LanServerHolder]) and has always
 * outlived the screen that built it - what it did not outlive was the
 * process, which Android reclaims from a cached app within minutes on a
 * box with a Chromecast's memory. So a parent's push, a grant, an index
 * pull or "Play on TV" sent while the television sat on its launcher was
 * refused by nobody: it simply went unanswered, and the hub held it until
 * the app was next opened. A foreground service is the one thing that keeps
 * a process off that list, and this is that service and nothing more: it
 * holds a notification, and if the system restarts it after a kill it
 * rebuilds the server from [buildLanServer] so there is something to hold.
 *
 * Televisions only. On a phone the notification would sit on a lock screen
 * saying "ready for the phone" to the phone, and a phone is rarely the
 * device a parent pushes TO while it is closed. `MainActivity` makes that
 * call; this class refuses to run anywhere else in case something starts
 * it anyway.
 *
 * Declared as `specialUse` from Android 14 (a data-sync service is capped at
 * six hours a day from Android 15, which is not "always on") and as
 * `dataSync` below it. Sideloaded, so no store declaration is owed for the
 * subtype; the manifest property says what it is for all the same.
 */
class LanService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 43
        private const val CHANNEL_ID = "lan"

        /** True when the start was asked for; false when this device is not a television. */
        fun start(context: Context): Boolean {
            if (DeviceKind.of(context) != DeviceKind.TV) return false
            return runCatching {
                ContextCompat.startForegroundService(context, Intent(context, LanService::class.java))
                true
            }.getOrElse {
                // A refused start (a background-start restriction, a policy on
                // some box) leaves the server exactly where it was before this
                // service existed: alive for the life of the process.
                Diag.w("LanService did not start", it)
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LanService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Ready for the phone", NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Keeps this TV answering the parent's phone while the app is closed" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DeviceKind.of(this) != DeviceKind.TV) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Foreground first: the window after startForegroundService is short.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Yosemite Kids is ready for the phone")
                .setContentText("Pushes and grants land even while the app is closed")
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build(),
            when {
                Build.VERSION.SDK_INT >= 34 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else -> 0
            }
        )
        // Restarted by the system with no server in the process: build one,
        // exactly as the screen would have. Otherwise the screen's is fine.
        if (LanServerHolder.server == null) {
            val app = applicationContext
            LanServerHolder.server = runCatching {
                buildLanServer(app, ConfigStore(app), PairingStore(app), ProfileNamespace(app)).also { it.start() }
            }.onFailure { Diag.e("LanService could not rebuild the LAN server", it) }.getOrNull()
        }
        return START_STICKY
    }
}
