package io.yosemitekids.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Your change lost" — one phone's private record of merges that went against
 * it.
 *
 * **Deliberately not in the config**, and deliberately not in the backup
 * include lists. This is one device's UI state: putting "you were overruled"
 * into the config would push it to the other parent's phone, which is both
 * useless to them and faintly accusatory. It is also never rendered on a kid
 * device — a child should not learn that their parents disagreed about their
 * bedtime.
 *
 * Nothing here interrupts. A parent hears about a collision when they next
 * open Settings, as one dismissible banner, and never as a dialog raised by a
 * background sweep.
 */
class SyncNotices(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("sync_notices", Context.MODE_PRIVATE)

    /**
     * One unit where both parents had edited and this phone's value lost.
     *
     * [restore] carries what this device held, in the shape [ConfigStore] uses,
     * so "Put it back" is a real one-tap action rather than an instruction to
     * go and find the setting again. It is null for units this build cannot
     * re-apply directly, where the banner explains and leaves it there.
     */
    data class Notice(
        val unit: String,
        val code: String,
        /** What a parent reads: "Emma's sitting length is 30 min — you had set 45 min". */
        val text: String,
        val at: Long,
        val restore: String? = null
    )

    fun all(): List<Notice> = runCatching {
        val arr = JSONArray(prefs.getString("notices", "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Notice(
                unit = o.optString("unit"),
                code = o.optString("code"),
                text = o.optString("text"),
                at = o.optLong("at", 0L),
                restore = o.optString("restore").ifEmpty { null }
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Record a batch, newest last, keyed by unit so a unit that keeps losing
     * shows once rather than accumulating. Capped, because this is a nudge and
     * not an audit trail — the change log in the config is the audit trail.
     */
    fun record(notices: List<Notice>) {
        if (notices.isEmpty()) return
        val merged = (all().filterNot { old -> notices.any { it.unit == old.unit } } + notices)
            .sortedBy { it.at }
            .takeLast(MAX)
        write(merged)
    }

    fun dismiss(unit: String) = write(all().filterNot { it.unit == unit })

    fun clear() = write(emptyList())

    private fun write(notices: List<Notice>) {
        val arr = JSONArray()
        notices.forEach { n ->
            arr.put(
                JSONObject()
                    .put("unit", n.unit).put("code", n.code)
                    .put("text", n.text).put("at", n.at)
                    .apply { n.restore?.let { put("restore", it) } }
            )
        }
        prefs.edit().putString("notices", arr.toString()).apply()
    }

    companion object {
        private const val MAX = 12

        /**
         * Turn a merge collision into something a parent can read.
         *
         * The current state comes first, because that is the fact that matters
         * at the moment they read it — what the rule *is* now, not what the
         * argument was about.
         */
        fun describe(c: ConfigMerge.Collision, who: String?): Notice {
            val by = who?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            val text = when (c.code) {
                "lim" -> "Everyone's screen-time rules changed$by. Your version was replaced."
                "kid" -> "A kid's settings changed$by. Your version was replaced."
                "settings" -> "App settings changed$by. Your version was replaced."
                "ai" -> "The AI screening setup changed$by. Your version was replaced."
                "src" -> "A channel's settings changed$by. Your version was replaced."
                else -> "A setting changed$by. Your version was replaced."
            }
            return Notice(
                unit = c.unit,
                code = c.code,
                text = text,
                at = c.theirsAt,
                restore = c.mine.takeIf { it.isNotBlank() && RESTORABLE.contains(c.code) }
            )
        }

        /**
         * Units this build can put back in one tap. Screen-time rules are the
         * case that actually matters — they are the thing two parents most
         * often disagree about, and the thing a parent most wants back.
         */
        private val RESTORABLE = setOf("lim")
    }
}
