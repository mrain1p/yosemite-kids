package io.yosemitekids.app.data

/** One of a channel's own playlists, as listed on its Playlists tab. */
data class PlaylistRef(
    val id: String,
    val url: String,
    val name: String,
    val thumbnailUrl: String?,
    val videoCount: Long
)

object PlaylistRefs {
    /** The `list=` id from any YouTube playlist URL form, or null. */
    fun playlistIdFrom(url: String): String? =
        Regex("[?&]list=([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
}
