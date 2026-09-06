package io.yosemitekids.app.data

/**
 * The URL shape a sideloaded local video carries in a whitelist entry.
 *
 * Lives here, Android-free, because the repository has to recognise one to
 * know it must not ask YouTube for it; the files themselves, and everything
 * that needs a Context to read them, stay in the app's LocalLibrary.
 */
object LocalUrls {
    const val PREFIX = "yosemitekids://local/"

    fun isLocal(url: String): Boolean = url.startsWith(PREFIX)
}
