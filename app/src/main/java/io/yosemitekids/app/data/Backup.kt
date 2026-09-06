package io.yosemitekids.app.data

import android.content.Context
import org.json.JSONObject

/**
 * The whole family in one file: config (channels, kids, rules, blocks,
 * safe-list, AI settings minus the key), every kid's watch state (resume
 * points, favourites, watch-later) and the AI verdict cache. An uninstall
 * used to cost all of it — the whitelist.txt export only ever carried the
 * channel links. Restore is a merge for the per-kid stores and a replace for
 * the config, the same operations the LAN sync already trusts.
 */
object Backup {

    // The envelope itself is declared in :core, because the hub writes one
    // too and a file from either face has to be readable by the other.
    const val SCHEMA = BackupFile.SCHEMA
    private const val KIND = BackupFile.KIND
    private const val LEGACY_KIND = BackupFile.LEGACY_KIND

    data class Summary(val channels: Int, val kids: Int, val verdicts: Int, val exportedAt: Long)

    fun export(context: Context): String {
        val app = context.applicationContext
        val store = ConfigStore(app)
        val config = store.load()
        return JSONObject()
            .put("kind", KIND)
            .put("schema", SCHEMA)
            .put("exportedAt", System.currentTimeMillis())
            .put("app", io.yosemitekids.app.BuildConfig.VERSION_NAME)
            // Key deliberately absent: a backup file travels (email, cloud
            // drive) and the API key is the one thing that must not.
            .put("config", JSONObject(ConfigJson.toJson(config, includeSecrets = false)))
            .put("watchState", JSONObject(WatchSync.exportJson(app)))
            .put("verdicts", JSONObject(ScreeningStore(app).exportJson(config.ai.rulesVersion)))
            .toString(2)
    }

    /**
     * When this phone last wrote a backup file — 0 if never — for the row
     * that offers the next one. Nothing else recorded it, so "Never backed
     * up" could only ever be guessed. Stamped by the UI once the file is
     * actually written, not when [export] builds the JSON: the picker
     * failing to open the file is precisely the case a parent must not be
     * told went fine.
     *
     * Its own prefs file, deliberately outside the Auto Backup include list:
     * it is about this phone's copy, and a restored phone has not backed up.
     */
    fun lastExportedAt(context: Context): Long =
        stampPrefs(context).getLong(LAST_EXPORT_AT, 0L)

    fun noteExported(context: Context, at: Long = System.currentTimeMillis()) {
        stampPrefs(context).edit().putLong(LAST_EXPORT_AT, at).apply()
    }

    private fun stampPrefs(context: Context) =
        context.applicationContext.getSharedPreferences("backup", Context.MODE_PRIVATE)

    private const val LAST_EXPORT_AT = "last_export_at"

    /** What a file holds, before committing to it — for the confirm dialog. */
    fun inspect(json: String): Result<Summary> = runCatching {
        val root = parse(json)
        val config = ConfigJson.fromJson(root.getJSONObject("config").toString())
        Summary(
            channels = config.sources.size,
            kids = config.profiles.size,
            verdicts = root.optJSONObject("verdicts")?.length() ?: 0,
            exportedAt = root.optLong("exportedAt", 0L)
        )
    }

    /**
     * Replace the config, merge watch state and verdicts. The config goes
     * first and through [ConfigStore.saveRaw], so a malformed file is
     * rejected whole rather than half-applied.
     */
    fun restore(context: Context, json: String): Result<Summary> = runCatching {
        val app = context.applicationContext
        val root = parse(json)
        val summary = inspect(json).getOrThrow()
        val store = ConfigStore(app)
        // A restore genuinely means replace, so this stays a whole-file write
        // rather than a merge. But it must not un-delete: a backup is evidence
        // about *content*, never evidence that a deletion was reversed.
        // Without carrying the device's tombstones across, restoring a
        // six-month-old bundle silently brings back every channel removed
        // since — on a parental-controls app, the worst possible outcome of
        // pressing a button called Restore.
        val configJson = withTombstonesOf(store.load(), root.getJSONObject("config")).toString()
        check(store.saveRaw(configJson)) { "the settings in that file couldn't be read" }
        root.optJSONObject("watchState")?.let { WatchSync.mergeJson(app, it.toString()) }
        root.optJSONObject("verdicts")?.let { verdicts ->
            val version = ConfigStore(app).load().ai.rulesVersion
            ScreeningStore(app).importJson(verdicts.toString(), version)
        }
        summary
    }

    /** Shape check shared by inspect and restore; throws with a parent-readable reason. */
    internal fun parse(json: String): JSONObject {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: throw IllegalArgumentException("that isn't a Yosemite Kids backup file")
        require(root.optString("kind").let { it == KIND || it == LEGACY_KIND }) { "that isn't a Yosemite Kids backup file" }
        require(root.optInt("schema", 0) in 1..SCHEMA) {
            "that backup was made by a newer Yosemite Kids — update this app first"
        }
        require(root.has("config")) { "that backup has no settings in it" }
        return root
    }
}

/**
 * The restored config, carrying forward the deletions this device already
 * knew about.
 *
 * Per key, the later of the two tombstones wins, and the namespace floors are
 * carried too. Stamps are left alone: the bundle's content is what a restore
 * is *for*, and only the delete evidence has to survive it.
 */
internal fun withTombstonesOf(current: Whitelist, restored: org.json.JSONObject): org.json.JSONObject {
    val mine = current.sync
    if (mine.gone.isEmpty() && mine.floor.isEmpty()) return restored
    val theirs = ConfigMerge.syncFromJson(restored)
    val gone = LinkedHashMap(theirs.gone)
    mine.gone.forEach { (k, v) -> gone[k] = maxOf(gone[k] ?: 0L, v) }
    val floor = LinkedHashMap(theirs.floor)
    mine.floor.forEach { (k, v) -> floor[k] = maxOf(floor[k] ?: 0L, v) }
    val merged = ConfigStamp.prune(
        theirs.copy(
            v = SyncMeta.VERSION,
            docAt = maxOf(theirs.docAt, mine.docAt),
            gone = gone,
            floor = floor
        )
    )
    return org.json.JSONObject(restored.toString()).put("sync", ConfigMerge.syncToJson(merged))
}
