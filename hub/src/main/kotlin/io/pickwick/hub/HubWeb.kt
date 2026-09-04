package io.pickwick.hub

import io.pickwick.app.data.SettingsSurface
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.Where
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * The admin GUI's data layer: which pages exist, what they render, and what an
 * edit from a browser does.
 *
 * Kept apart from [HubServer] because the server is about HTTP — bounds,
 * sessions, status codes — and this is about the family's settings. The one
 * rule crossing the boundary is that every edit here goes through
 * [HubStore.edit], which stamps it. An unstamped edit carries no causality and
 * loses to the first peer that syncs, so a parent changing something on the
 * NAS would watch it silently revert.
 */
object HubWeb {

    /**
     * The pages this GUI actually serves.
     *
     * Every id must appear in [SettingsSurface], and every section there marked
     * [Where.BOTH] with `hubReady` must appear here. Both directions are checked
     * by `scripts/check.ps1` and `scripts/check.sh`, so the two faces cannot
     * drift apart without the build saying so.
     */
    val pages: List<HubPage> = listOf(
        HubPage("channels", "Channels"),
        HubPage("devices", "Devices"),
        HubPage("hub-health", "Hub status")
    )

    data class HubPage(val id: String, val title: String)

    /** Everything the page renders, in one round trip. */
    fun state(store: HubStore, tokens: HubTokens, dataDir: String, now: Long): String {
        val config = runCatching { store.load() }.getOrElse { Whitelist(emptyList(), emptySet()) }

        val channels = JSONArray()
        config.sources.forEach {
            channels.put(
                JSONObject()
                    .put("id", it.id)
                    .put("label", it.label ?: it.id)
                    .put("kind", it.kind.name)
                    .put("url", it.url)
            )
        }

        val devices = JSONArray()
        tokens.devices().forEach {
            devices.put(
                JSONObject()
                    // Never the token itself. It is a bearer credential, and a
                    // page has no use for one it can only hand back.
                    .put("ref", deviceRef(it.token))
                    .put("name", it.name)
                    .put("enrolledAt", it.enrolledAt)
            )
        }

        val pending = JSONArray()
        tokens.pending(now).forEach {
            pending.put(
                JSONObject().put("code", it.code).put("name", it.name).put("createdAt", it.createdAt)
            )
        }

        val outstanding = JSONArray()
        SettingsSurface.outstandingOnHub().forEach { outstanding.put(it.title) }

        return JSONObject()
            .put("pages", JSONArray().also { arr -> pages.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title)) } })
            .put("channels", channels)
            .put("blocked", JSONArray().also { arr -> config.blockedVideoIds.sorted().forEach { arr.put(it) } })
            .put("devices", devices)
            .put("pending", pending)
            .put(
                "hub",
                JSONObject()
                    .put("hash", store.fingerprint())
                    .put("updatedAt", store.updatedAt())
                    .put("dataDir", dataDir)
                    .put("deviceCount", tokens.devices().size)
                    // Named, not counted. "Six sections still to come" tells a
                    // parent nothing; the list tells them whether the one they
                    // want is among them.
                    .put("outstanding", outstanding)
            )
            .toString()
    }

    /**
     * A device's stable short reference. Enough to tell two devices apart and
     * to name one for revocation, without handing the browser a credential.
     */
    fun deviceRef(token: String): String = token.take(8)

    /** Add whatever the parent pasted. Returns how many entries were understood. */
    fun addChannels(store: HubStore, who: String, now: Long, text: String): Int {
        val parsed = WhitelistParser.parse(text)
        if (parsed.sources.isEmpty() && parsed.blockedVideoIds.isEmpty()) return 0

        store.edit(who, now) { current ->
            val existing = current.sources.map { it.id }.toSet()
            current.copy(
                // Appended, not merged in place: an entry the family already has
                // keeps its label and its position, so re-pasting a list is a
                // no-op rather than a reshuffle every device has to adopt.
                sources = current.sources + parsed.sources.filter { it.id !in existing },
                blockedVideoIds = current.blockedVideoIds + parsed.blockedVideoIds
            )
        }
        return parsed.sources.size + parsed.blockedVideoIds.size
    }

    fun removeChannel(store: HubStore, who: String, now: Long, id: String): Boolean {
        var found = false
        store.edit(who, now) { current ->
            found = current.sources.any { it.id == id }
            current.copy(sources = current.sources.filterNot { it.id == id })
        }
        return found
    }

    fun unblock(store: HubStore, who: String, now: Long, videoId: String): Boolean {
        var found = false
        store.edit(who, now) { current ->
            found = videoId in current.blockedVideoIds
            current.copy(blockedVideoIds = current.blockedVideoIds - videoId)
        }
        return found
    }

    /** Revoke by the short reference the page was given, never by a raw token. */
    fun revokeDevice(tokens: HubTokens, ref: String): Boolean {
        val match = tokens.devices().filter { deviceRef(it.token) == ref }
        // Exactly one, or nothing. Two devices sharing a prefix is astronomically
        // unlikely and revoking the wrong one is not recoverable from here.
        if (match.size != 1) return false
        tokens.revoke(match.single().token)
        return true
    }

    /** Kinds the page may show, so an unknown one from a newer build is visible. */
    fun kindLabel(kind: String): String = when (kind) {
        SourceKind.CHANNEL.name -> "Channel"
        SourceKind.PLAYLIST.name -> "Playlist"
        else -> kind
    }
}
