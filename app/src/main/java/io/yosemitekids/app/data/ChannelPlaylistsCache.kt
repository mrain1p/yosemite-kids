package io.yosemitekids.app.data

import android.content.Context
import java.io.File

/** One of a channel's own playlists, as listed on its Playlists tab. */
data class PlaylistRef(
    val id: String,
    val url: String,
    val name: String,
    val thumbnailUrl: String?,
    val videoCount: Long
)

/**
 * Remembers which playlists each channel has, so the "By playlist" channel
 * layout costs one extractor fetch per channel per day instead of one per
 * visit. A saved empty list is a real answer ("this channel has no
 * playlists") and is honoured for the same day, so a channel without any
 * doesn't get re-asked on every open.
 *
 * The playlists' videos are not stored here: each playlist is fetched like
 * any whitelisted playlist source and lands in [VideoCache] under its own id.
 */
class ChannelPlaylistsCache(context: Context) {

    private val dir = File(context.filesDir, "playlist_cache").apply { mkdirs() }

    // Source ids can be "user/<name>" or "c/<name>" — a slash would be a
    // directory, and the write would silently fail every visit.
    private fun fileFor(sourceId: String) =
        File(dir, sourceId.replace(Regex("[^A-Za-z0-9_.@-]"), "_") + ".tsv")

    /** Null when this channel has never been asked. */
    fun load(sourceId: String): List<PlaylistRef>? {
        val file = fileFor(sourceId)
        if (!file.exists()) return null
        return runCatching { file.readLines().mapNotNull { parseRow(it) } }.getOrNull()
    }

    fun isFresh(sourceId: String, maxAgeMs: Long = MAX_AGE_MS): Boolean {
        val file = fileFor(sourceId)
        return file.exists() && System.currentTimeMillis() - file.lastModified() < maxAgeMs
    }

    fun save(sourceId: String, playlists: List<PlaylistRef>) {
        // Write beside, then rename: an empty file is a real answer here ("no
        // playlists", honoured for a day), so a crash mid-write must not
        // leave one behind.
        runCatching {
            val target = fileFor(sourceId)
            val tmp = File(target.path + ".tmp")
            tmp.writeText(playlists.joinToString("\n") { formatRow(it) })
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    companion object {
        /** Playlists change slowly; a day keeps a fifty-channel library to a handful of fetches per open. */
        const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

        internal fun parseRow(line: String): PlaylistRef? {
            val p = line.split('\t')
            if (p.size < 5 || p[0].isBlank()) return null
            return PlaylistRef(
                id = p[0],
                url = p[1],
                name = p[2],
                thumbnailUrl = p[3].takeIf { it.isNotBlank() },
                videoCount = p[4].toLongOrNull() ?: -1L
            )
        }

        internal fun formatRow(ref: PlaylistRef): String = listOf(
            ref.id, ref.url, ref.name, ref.thumbnailUrl.orEmpty(), ref.videoCount.toString()
        ).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }

        /** The `list=` id of a playlist URL, in any of YouTube's spellings. */
        internal fun playlistIdFrom(url: String): String? =
            Regex("[?&]list=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
    }
}
