package io.yosemitekids.app.data

import android.content.Context
import org.json.JSONObject

/**
 * A kid restyled themselves — new avatar, new colour — on a device the
 * parent's phone administers. The config only ever flows phone → device, so
 * the device can't write the choice into it. Instead the choice waits here:
 *
 *  - [ConfigStore.load] lays pending looks over the profiles it returns, so
 *    this device shows the new look at once (and its fingerprint moves, the
 *    same way it would after the phone adopts the choice);
 *  - the phone's sync sweep pulls `GET /looks`, [mergeInto] adopts anything
 *    newer than what the config holds (`lookAt`), and the ordinary push
 *    carries the look to every device;
 *  - [ack] drops a pending look once a pushed config carries it (or a newer
 *    one — the parent may have restyled the kid on the phone meanwhile, and
 *    the later choice wins on both sides).
 *
 * An admin phone never uses the pending path: it edits the config directly.
 */
class ProfileLooks(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("profile_looks", Context.MODE_PRIVATE)

    data class Look(val avatar: String, val colorArgb: Long, val at: Long)

    fun pending(): Map<String, Look> =
        prefs.all.mapNotNull { (id, raw) -> parse(raw as? String)?.let { id to it } }.toMap()

    fun record(profileId: String, avatar: String, colorArgb: Long, at: Long = System.currentTimeMillis()) {
        prefs.edit().putString(profileId, "$avatar|$colorArgb|$at").apply()
    }

    /** `{ "<profileId>": { "avatar": "🦊", "color": 4293467747, "at": 1780000000000 } }` */
    fun exportJson(): String = JSONObject().apply {
        pending().forEach { (id, look) ->
            put(id, JSONObject().put("avatar", look.avatar).put("color", look.colorArgb).put("at", look.at))
        }
    }.toString()

    /** Pending looks laid over [config]'s profiles; the config itself is untouched. */
    fun overlay(config: Whitelist): Whitelist {
        val looks = pending()
        if (looks.isEmpty() || config.profiles.isEmpty()) return config
        return config.copy(profiles = config.profiles.map { p -> applyLook(p, looks[p.id]) })
    }

    /** A config just landed from the phone: forget every look it already carries. */
    fun ack(config: Whitelist) {
        val looks = pending()
        if (looks.isEmpty()) return
        val editor = prefs.edit()
        config.profiles.forEach { p ->
            val look = looks[p.id] ?: return@forEach
            if (p.lookAt >= look.at) editor.remove(p.id)
        }
        // A profile the parent removed has nothing to adopt the look any more.
        looks.keys.filter { id -> config.profiles.none { it.id == id } }.forEach { editor.remove(it) }
        editor.apply()
    }

    companion object {
        private fun parse(raw: String?): Look? {
            val p = raw?.split('|') ?: return null
            if (p.size != 3) return null
            return Look(p[0], p[1].toLongOrNull() ?: return null, p[2].toLongOrNull() ?: return null)
        }

        /** The newer choice wins; equal stamps keep the config's. */
        fun applyLook(p: Profile, look: Look?): Profile =
            if (look == null || look.at <= p.lookAt) p
            else p.copy(avatar = look.avatar, colorArgb = look.colorArgb, lookAt = look.at)

        /**
         * A device's `/looks` answer folded into the phone's config. Null when
         * nothing was newer, so the caller can skip the save and the push.
         * Pure, so the merge rules are unit-tested rather than trusted.
         */
        fun mergeInto(config: Whitelist, looksJson: String): Whitelist? {
            val root = runCatching { JSONObject(looksJson) }.getOrNull() ?: return null
            var changed = false
            val profiles = config.profiles.map { p ->
                val o = root.optJSONObject(p.id) ?: return@map p
                val look = Look(
                    avatar = o.optString("avatar").ifEmpty { return@map p },
                    colorArgb = o.optLong("color", -1L).takeIf { it != -1L } ?: return@map p,
                    at = o.optLong("at", 0L).takeIf { it > 0 } ?: return@map p
                )
                val next = applyLook(p, look)
                if (next !== p) changed = true
                next
            }
            return if (changed) config.copy(profiles = profiles) else null
        }
    }
}
