package io.yosemitekids.app.data

import android.content.Context

/**
 * Persists the last successfully resolved home-screen tiles so the app can paint
 * instantly on launch while the fresh list loads in the background.
 */
class SourceCache(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("source_cache", Context.MODE_PRIVATE)

    fun load(): List<Source> =
        prefs.getString("sources", null)
            ?.lineSequence()
            ?.mapNotNull { line ->
                // id \t kind \t name \t avatar \t url \t multiplier% \t about —
                // older 4-field rows are dropped and simply re-fetched once;
                // 5-field rows (from pre-multiplier builds) default to 100%,
                // and 6-field rows (pre-description) simply have no blurb yet.
                val parts = line.split('\t')
                if (parts.size < 5) return@mapNotNull null
                val kind = runCatching { SourceKind.valueOf(parts[1]) }.getOrNull()
                    ?: return@mapNotNull null
                Source(
                    parts[0], parts[4], parts[2], parts[3].ifEmpty { null }, kind,
                    parts.getOrNull(5)?.toIntOrNull() ?: 100,
                    // Through the stripper on the way out as well as the way
                    // in: a row written by an older build predates the rule.
                    about = SafeText.forKids(parts.getOrNull(6))
                )
            }
            ?.toList()
            ?: emptyList()

    fun save(sources: List<Source>) {
        val text = sources.joinToString("\n") { s ->
            listOf(
                s.id, s.kind.name, s.name.tsvCell(), s.avatarUrl.orEmpty(), s.url,
                s.timeMultiplierPercent.toString(),
                // tsvCell flattens the paragraph breaks: this row is one line
                // and a description is the one field here that has newlines.
                s.about.orEmpty().tsvCell()
            ).joinToString("\t")
        }
        prefs.edit().putString("sources", text).apply()
    }
}
