package io.yosemitekids.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * What went wrong on this device, kept so the hub can be told.
 *
 * Every warning and error the app logs goes through here — guard 68 refuses
 * a raw `Log.w`/`Log.e` in the app — and lands in two places: logcat, as
 * before, and a small ring on disk that the next contact with the hub
 * drains. That is the whole of the reporting: **no timer, no telemetry**. A
 * device that never talks to a hub keeps its ring and nobody reads it; a
 * television that has been failing all afternoon says so the moment its
 * sweep reaches the hub again, and the hub prints it into the container
 * log and shows it on the console's Devices page.
 *
 * **Its own file, never `config.json`** and never the backup include lists:
 * a log line is not curation, and a value that moves on every failure inside
 * the synced document would put every peer through a merge per failure. The
 * ring is small ([MAX]) and forgets the oldest, because it is a trail to
 * diagnose from, not an audit.
 *
 * Crashes are recorded by the handler [install] puts in front of the
 * default one, synchronously — the process is about to die, and an `apply`
 * that had not flushed would be the one line that mattered.
 */
object Diag {

    private const val TAG = "YosemiteKids"
    private const val FILE = "diag"
    private const val KEY = "entries"

    /** Entries kept on disk; older ones are forgotten. */
    const val MAX = 60

    /** Entries sent in one report, and the longest message in one. */
    const val MAX_PER_REPORT = 40
    const val MAX_MESSAGE = 400

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Wire up the ring and the crash handler. Once, from the Application. */
    fun install(context: Context) {
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                // The class, the message and the first app frame: enough to
                // find it, short enough to sit in a ring of sixty.
                val frame = throwable.stackTrace.firstOrNull { it.className.startsWith("io.yosemitekids") }
                    ?: throwable.stackTrace.firstOrNull()
                val where = frame?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.orEmpty()
                append(p, "crash", "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}$where (${thread.name})", sync = true)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Information only: logcat, never the ring. The ring is for what went wrong. */
    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun w(message: String, t: Throwable? = null) {
        if (t == null) Log.w(TAG, message) else Log.w(TAG, message, t)
        record("warn", message, t)
    }

    fun e(message: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG, message) else Log.e(TAG, message, t)
        record("error", message, t)
    }

    private fun record(level: String, message: String, t: Throwable?) {
        val p = prefs ?: return
        val text = if (t == null) message else "$message — ${t.javaClass.simpleName}: ${t.message.orEmpty()}"
        runCatching { append(p, level, text, sync = false) }
    }

    private fun append(p: SharedPreferences, level: String, message: String, sync: Boolean) {
        synchronized(this) {
            val arr = Ring.append(p.getString(KEY, "[]") ?: "[]", level, message, System.currentTimeMillis())
            val editor = p.edit().putString(KEY, arr)
            if (sync) editor.commit() else editor.apply()
        }
    }

    /**
     * What the hub has not been told yet, as the body of `POST /report`, or
     * null when there is nothing to say. [forgetUpTo] once the hub answered.
     */
    fun pendingReport(kind: String?): Pair<String, Long>? {
        val p = prefs ?: return null
        val raw = p.getString(KEY, "[]") ?: "[]"
        val sentUpTo = p.getLong("sentUpTo", 0L)
        val (body, newest) = Ring.report(raw, sentUpTo, kind, io.yosemitekids.app.BuildConfig.VERSION_NAME)
            ?: return null
        return body to newest
    }

    /** The hub has these; do not send them again. The ring itself is kept for a parent looking locally. */
    fun forgetUpTo(at: Long) {
        prefs?.edit()?.putLong("sentUpTo", at)?.apply()
    }

    /** The ring, newest last, for a settings page or a test. */
    fun entries(): List<Entry> = Ring.parse(prefs?.getString(KEY, "[]") ?: "[]")

    data class Entry(val at: Long, val level: String, val message: String)

    /**
     * The pure half: a JSON array in, a JSON array out, no Android. Kept
     * separate so the cap, the ordering and the report's shape have tests
     * that need no Context.
     */
    object Ring {
        fun parse(raw: String): List<Entry> = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Entry(it.optLong("at"), it.optString("level"), it.optString("msg")) }
            }
        }.getOrDefault(emptyList())

        fun append(raw: String, level: String, message: String, at: Long): String {
            val kept = (parse(raw) + Entry(at, level, message.take(MAX_MESSAGE))).takeLast(MAX)
            return toJson(kept).toString()
        }

        /**
         * The body for `POST /report`: the entries newer than [sentUpTo],
         * capped, plus what the hub cannot otherwise know about the sender.
         * Returns the body and the newest `at` in it, or null when empty.
         */
        fun report(raw: String, sentUpTo: Long, kind: String?, version: String): Pair<String, Long>? {
            val fresh = parse(raw).filter { it.at > sentUpTo }.takeLast(MAX_PER_REPORT)
            if (fresh.isEmpty()) return null
            val body = JSONObject()
                .put("version", version)
                .apply { kind?.let { put("kind", it) } }
                .put("entries", toJson(fresh))
                .toString()
            return body to fresh.maxOf { it.at }
        }

        private fun toJson(entries: List<Entry>): JSONArray = JSONArray().apply {
            entries.forEach { put(JSONObject().put("at", it.at).put("level", it.level).put("msg", it.message)) }
        }
    }
}
