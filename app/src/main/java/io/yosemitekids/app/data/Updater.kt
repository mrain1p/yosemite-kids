package io.yosemitekids.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.yosemitekids.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** Live "a newer build exists" signal, for the dot on the settings gear. */
object UpdateEvents {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Updater.UpdateInfo?>(null)
}

/**
 * Self-update over GitHub: `version.json` in the repo names the latest build and
 * where its APK lives (a GitHub Release asset). If it's newer than this install,
 * the parent can download it and the system installer takes over.
 */
class Updater(private val context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("updater", Context.MODE_PRIVATE)

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String
    )

    /**
     * What a manifest check found. Four answers, not two: [check] folds the
     * last three into null, which is right for the gear dot (nothing to show
     * either way) and wrong for a phone that asked a TV to update — "up to
     * date" and "couldn't reach GitHub" want different words there.
     */
    sealed class Check {
        /** This build carries no manifest URL: there is nothing to check against. */
        data object Off : Check()
        data object UpToDate : Check()
        data class Offered(val info: UpdateInfo) : Check()
        /** The manifest could not be fetched or read; the cached answer stands. */
        data class Failed(val why: String) : Check()
    }

    /** Null when up to date (or the update URL isn't configured / reachable). */
    suspend fun check(): UpdateInfo? = (checkDetailed() as? Check.Offered)?.info

    suspend fun checkDetailed(): Check = withContext(Dispatchers.IO) {
        if (!canCheck()) return@withContext Check.Off
        val url = BuildConfig.UPDATE_MANIFEST_URL
        runCatching {
            val busted = url + (if ('?' in url) "&" else "?") + "cb=" + System.currentTimeMillis()
            val body = Http.client.newCall(Request.Builder().url(busted).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext Check.Failed("HTTP ${resp.code}")
                    resp.body?.string().orEmpty()
                }
            val json = JSONObject(body)
            val code = json.getInt("versionCode")
            // Remember what the manifest said whether newer or not: [pending]
            // filters against the running build, so a cached "update available"
            // clears itself on the first check after installing it. A failed
            // fetch never reaches here — the cached answer survives to retry.
            prefs.edit()
                .putLong("last_check_at", System.currentTimeMillis())
                .putInt("manifest_code", code)
                .putString("manifest_name", json.optString("versionName", code.toString()))
                .putString("manifest_url", json.optString("apkUrl"))
                .apply()
            UpdateEvents.pending.value = pending()
            if (code <= BuildConfig.VERSION_CODE) Check.UpToDate
            else Check.Offered(
                UpdateInfo(
                    versionCode = code,
                    versionName = json.optString("versionName", code.toString()),
                    apkUrl = json.getString("apkUrl")
                )
            )
        }.getOrElse { Check.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /** The cached manifest answer, if it's still newer than this install. */
    fun pending(): UpdateInfo? {
        val code = prefs.getInt("manifest_code", 0)
        if (code <= BuildConfig.VERSION_CODE) return null
        val apkUrl = prefs.getString("manifest_url", null)?.ifBlank { null } ?: return null
        return UpdateInfo(code, prefs.getString("manifest_name", null) ?: code.toString(), apkUrl)
    }

    /**
     * Launch-time path: publish the cached answer immediately (the dot shows
     * with no network at all), then refresh it with one real fetch per app
     * process. This used to be throttled to one fetch a day, which meant a
     * release shipped inside that window showed no gear dot until something
     * else (the settings screen) happened to check — the manifest is ~150
     * bytes, so freshness on every cold launch costs nothing. The process
     * guard (not a prefs timestamp) is what keeps activity re-creation from
     * re-fetching.
     */
    suspend fun autoCheck() {
        UpdateEvents.pending.value = pending()
        if (checkedThisProcess.compareAndSet(false, true)) check()
    }

    /** Downloads the APK, then hands it to the system installer (user confirms). */
    suspend fun downloadAndInstall(info: UpdateInfo) = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "yosemite-kids-update.apk")
        Http.client.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { resp ->
            check(resp.isSuccessful) { "Download failed: HTTP ${resp.code}" }
            apk.outputStream().use { out ->
                resp.body?.byteStream()?.copyTo(out) ?: error("Empty download")
            }
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    companion object {
        /**
         * The first build whose APK carried the manifest URL, so the first
         * that can offer an update from its own settings screen. A device
         * reporting an older code has no update check at all and has to be
         * sideloaded once by hand; the Devices page says so rather than
         * sending a parent to look for a button that build does not have.
         */
        const val FIRST_SELF_UPDATING_VERSION_CODE = 4

        /**
         * Whether this build can check for updates at all.
         *
         * `UPDATE_MANIFEST_URL` is a build property that defaults to blank, so
         * every build shipped so far answers false — [check] then returns null
         * without making a request. That is indistinguishable from "up to
         * date" unless the caller asks, which is exactly how the settings
         * screen came to tell parents they were current after a check that
         * never happened. One predicate, so a UI that reports a result and the
         * code that produces it cannot disagree about what null means.
         */
        fun canCheck(): Boolean {
            val url = BuildConfig.UPDATE_MANIFEST_URL
            return url.isNotBlank() && "CHANGE_ME" !in url
        }

        /** One fresh manifest fetch per process, however many Updaters exist. */
        private val checkedThisProcess = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}

/**
 * "Update now" from a paired phone — the device half of `POST /check-updates`.
 *
 * The phone can make this device fetch `version.json`, download the APK and
 * put the system installer's prompt on its screen; it cannot press that
 * prompt. Android hands the APK to its own installer and the confirmation
 * belongs to whoever holds the remote, which is the right place for it — a
 * LAN credential should not be able to change what is installed on the kids'
 * TV without a person in front of it agreeing.
 *
 * The decision is [run], written over three lambdas so a JVM test can drive
 * every branch without a Context or a network; [handleBlocking] is the one
 * place they are bound to the real [Updater].
 */
object RemoteUpdate {
    /** The install prompt is up on this device, for the build named in the answer. */
    const val OFFERED = "offered"
    const val UP_TO_DATE = "up-to-date"
    /** This build has no manifest URL ([Updater.canCheck] is false): it cannot check at all. */
    const val OFF = "off"
    /** The manifest or the APK could not be fetched. */
    const val FAILED = "failed"
    /** A request from a phone is already checking or downloading. */
    const val BUSY = "busy"
    /**
     * An update exists but the app is not on screen, so the installer prompt
     * would be dropped without a trace (Android 10+ refuses activity starts
     * from a process with no visible window). Nothing was downloaded.
     */
    const val NOT_ON_SCREEN = "not-on-screen"

    /**
     * What goes on the wire. [versionName]/[versionCode] name the build the
     * installer is about to install when [status] is [OFFERED], and the build
     * this device is running for every other status — in each case the one
     * the phone would want to print.
     */
    data class Outcome(val status: String, val versionName: String, val versionCode: Int) {
        fun toJson(): String = JSONObject()
            .put("status", status)
            .put("versionName", versionName)
            .put("versionCode", versionCode)
            .toString()
    }

    /**
     * One in flight per device. A download holds a LAN worker thread for as
     * long as it takes, and a parent tapping twice (or two parents once each)
     * must not start a second copy of the same download beside the first.
     */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * @param check  the manifest check.
     * @param onScreen whether an activity started now would actually appear —
     *   asked before the download (no point fetching what cannot be shown)
     *   and again after it, because a download takes long enough for the app
     *   to have been backgrounded meanwhile, and a prompt that was silently
     *   dropped must not be reported as up.
     * @param install downloads and hands the APK to the installer; throws on failure.
     */
    suspend fun run(
        check: suspend () -> Updater.Check,
        onScreen: () -> Boolean,
        install: suspend (Updater.UpdateInfo) -> Unit,
        installedName: String = BuildConfig.VERSION_NAME,
        installedCode: Int = BuildConfig.VERSION_CODE
    ): Outcome {
        fun here(status: String) = Outcome(status, installedName, installedCode)
        if (!inFlight.compareAndSet(false, true)) return here(BUSY)
        try {
            return when (val found = check()) {
                Updater.Check.Off -> here(OFF)
                Updater.Check.UpToDate -> here(UP_TO_DATE)
                is Updater.Check.Failed -> here(FAILED)
                is Updater.Check.Offered -> {
                    if (!onScreen()) return here(NOT_ON_SCREEN)
                    val installed = runCatching { install(found.info) }.isSuccess
                    when {
                        !installed -> here(FAILED)
                        !onScreen() -> here(NOT_ON_SCREEN)
                        else -> Outcome(OFFERED, found.info.versionName, found.info.versionCode)
                    }
                }
            }
        } finally {
            inFlight.set(false)
        }
    }

    /**
     * The route's body. Blocking on purpose: `LanServer.handle` runs on its
     * worker pool, never on Main, and the phone is waiting on the answer with
     * a read timeout sized for a download (see `LanClient.checkUpdates`).
     */
    fun handleBlocking(context: Context): String {
        val updater = Updater(context.applicationContext)
        return kotlinx.coroutines.runBlocking {
            run(
                check = { updater.checkDetailed() },
                onScreen = { AppVisibility.canStartActivity },
                install = { updater.downloadAndInstall(it) }
            )
        }.toJson()
    }
}
