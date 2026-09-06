package io.yosemitekids.app.data

import org.json.JSONObject

/**
 * The envelope a backup file is wrapped in.
 *
 * Declared once because two faces write one and two faces read one: a phone
 * exports through `Backup`, the hub serves `GET /api/backup`, and a file
 * written by either has to be readable by the other. That is the whole point
 * of taking a backup off a NAS — the day it is needed is the day the NAS is
 * gone, and the only thing left is a phone. Two copies of these three
 * constants is how that quietly stops being true in one release, so a guard
 * holds them to this file.
 */
object BackupFile {

    const val KIND = "yosemite-kids-backup"

    /**
     * Written by every build before the rename, and read forever: moving a
     * family to the new package id is "full backup on the old app, restore
     * here", and that file says pickwick-backup.
     */
    const val LEGACY_KIND = "pickwick-backup"

    /** Bump only for a change an older build could misread. */
    const val SCHEMA = 1

    /**
     * Wrap a config document for download. [config] travels verbatim, sync
     * block included: the receiving side needs the tombstones to know that a
     * channel missing from the file was *deleted* rather than never added.
     */
    fun wrap(config: String, at: Long, app: String): String = JSONObject()
        .put("kind", KIND)
        .put("schema", SCHEMA)
        .put("exportedAt", at)
        .put("app", app)
        .put("config", JSONObject(config))
        .toString(2)

    /**
     * The settings document inside whatever file a parent just handed us:
     * an envelope from either face, or a bare config document — which is
     * what `GET /config` answers and what the hub's own `config.json` is.
     *
     * Null when it is neither, and the test for "bare document" is
     * deliberately evidence-based rather than "does `ConfigJson` parse it".
     * `ConfigJson.fromJson("{}")` succeeds and yields no channels, no kids and
     * no rules, so a restore of any unrelated JSON file would read as a
     * deliberate instruction to empty the family's settings.
     */
    fun configIn(text: String): String? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val kind = root.optString("kind")
        if (kind == KIND || kind == LEGACY_KIND) {
            // A file from a newer build may hold fields this one would drop on
            // the way through, so it is refused rather than half-restored.
            if (root.optInt("schema", 0) !in 1..SCHEMA) return null
            return root.optJSONObject("config")?.toString()
        }
        // `ConfigJson.toJson` writes both of these unconditionally, so their
        // presence is the cheapest honest evidence that this is a config.
        if (root.has("entries") && root.has("limits")) return text
        return null
    }
}
