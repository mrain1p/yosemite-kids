package io.pickwick.app.data

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persists each source's last-fetched video list so opening a channel paints
 * instantly from disk while the fresh list loads in the background.
 *
 * Reads are memoised per source on the file's (mtime, length): the home
 * screen walks every source's cache for Keep watching, the NEW badges, the
 * feed and the all-held check — on a fifty-channel family that was fifty
 * TSV parses per refresh, several times over. Now it is one parse per file
 * per change, across every VideoCache instance in the process.
 */
class VideoCache(context: Context) {

    private val dir = File(context.filesDir, "video_cache").apply { mkdirs() }

    private fun fileFor(sourceId: String) = File(dir, "$sourceId.tsv")

    fun load(sourceId: String): List<Video> {
        val file = fileFor(sourceId)
        if (!file.exists()) return emptyList()
        val stamp = file.lastModified() to file.length()
        MEMO[file.path]?.let { (seen, videos) -> if (seen == stamp) return videos }
        val parsed = runCatching {
            file.readLines().mapNotNull { line -> parseRow(line) }
        }.getOrDefault(emptyList())
        MEMO[file.path] = stamp to parsed
        return parsed
    }

    fun save(sourceId: String, videos: List<Video>) {
        runCatching {
            val file = fileFor(sourceId)
            file.writeText(videos.joinToString("\n") { formatRow(it) })
            MEMO[file.path] = (file.lastModified() to file.length()) to videos
        }
    }

    companion object {
        private val MEMO = ConcurrentHashMap<String, Pair<Pair<Long, Long>, List<Video>>>()

        /** Test hook / memory relief. */
        fun clearMemo() = MEMO.clear()

        /**
         * One row: url, title, channel, thumb, seconds, and (new) view count —
         * blank when unknown. Rows written by older builds have five cells
         * and read back with a null count, so no migration is needed.
         */
        internal fun parseRow(line: String): Video? {
            val p = line.split('\t')
            if (p.size < 5) return null
            return Video(
                url = p[0],
                title = p[1],
                channelName = p[2],
                thumbnailUrl = p[3].ifEmpty { null },
                durationSeconds = p[4].toLongOrNull() ?: 0L,
                viewCount = p.getOrNull(5)?.toLongOrNull(),
                // Seventh cell, same append-only rule as the count: rows from
                // older builds read back with no date.
                publishedAt = p.getOrNull(6)?.toLongOrNull()
            )
        }

        internal fun formatRow(v: Video): String = listOf(
            v.url,
            v.title.tsvCell(),
            v.channelName.tsvCell(),
            v.thumbnailUrl.orEmpty(),
            v.durationSeconds.toString(),
            v.viewCount?.toString().orEmpty(),
            v.publishedAt?.toString().orEmpty()
        ).joinToString("\t")
    }
}
