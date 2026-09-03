package io.pickwick.app.data

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

    const val SCHEMA = 1
    private const val KIND = "pickwick-backup"

    data class Summary(val channels: Int, val kids: Int, val verdicts: Int, val exportedAt: Long)

    fun export(context: Context): String {
        val app = context.applicationContext
        val store = ConfigStore(app)
        val config = store.load()
        return JSONObject()
            .put("kind", KIND)
            .put("schema", SCHEMA)
            .put("exportedAt", System.currentTimeMillis())
            .put("app", io.pickwick.app.BuildConfig.VERSION_NAME)
            // Key deliberately absent: a backup file travels (email, cloud
            // drive) and the API key is the one thing that must not.
            .put("config", JSONObject(ConfigStore.toJson(config, includeSecrets = false)))
            .put("watchState", JSONObject(WatchSync.exportJson(app)))
            .put("verdicts", JSONObject(ScreeningStore(app).exportJson(config.ai.rulesVersion)))
            .toString(2)
    }

    /** What a file holds, before committing to it — for the confirm dialog. */
    fun inspect(json: String): Result<Summary> = runCatching {
        val root = parse(json)
        val config = ConfigStore.fromJson(root.getJSONObject("config").toString())
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
            ?: throw IllegalArgumentException("that isn't a Pickwick backup file")
        require(root.optString("kind") == KIND) { "that isn't a Pickwick backup file" }
        require(root.optInt("schema", 0) in 1..SCHEMA) {
            "that backup was made by a newer Pickwick — update this app first"
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
