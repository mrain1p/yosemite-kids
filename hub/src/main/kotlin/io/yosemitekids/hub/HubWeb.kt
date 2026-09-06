package io.yosemitekids.hub

import io.yosemitekids.app.data.MasterToken

import io.yosemitekids.app.data.ChannelIndex

import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.Page
import io.yosemitekids.app.data.SettingsSurface
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * The admin GUI's data layer: which pages exist, what they render, and what an
 * edit from a browser does.
 *
 * Kept apart from [HubServer] because the server is about HTTP — bounds,
 * sessions, status codes — and this is about the family's settings. The one
 * rule crossing the boundary is that every edit goes through [HubStore.edit],
 * which stamps it. An unstamped edit carries no causality and loses to the
 * first peer that syncs, so a parent changing something on the NAS would watch
 * it silently revert.
 *
 * Edits are applied as a JSON patch over the stored document rather than
 * through a bespoke route per control. Two reasons, and the second is the one
 * that matters: a route per control is a route per control to forget, and the
 * merge is itself defined at the JSON level precisely so a field this build
 * does not model survives a round trip. Patching keys rather than rebuilding a
 * Whitelist keeps that property for everything the hub does not touch.
 */
object HubWeb {

    data class HubPage(val id: String, val title: String)

    /**
     * The pages this GUI serves, in the phone's own navigation order.
     *
     * Derived from [SettingsSurface] rather than listed again here, so the two
     * faces cannot present different pages. `scripts/check.*` reads the same
     * manifest from the other direction.
     */
    val pages: List<HubPage> = listOf(
        HubPage("kids", "Kids"),
        HubPage("channels", "Channels & playlists"),
        HubPage("screening", "Content screening"),
        HubPage("playback", "Playback"),
        HubPage("listing", "How videos are listed"),
        HubPage("devices", "Devices & sync"),
        HubPage("backup", "App, hub & backup")
    )

    /**
     * Root keys a browser may set.
     *
     * An allowlist rather than a denylist: a new field added to the config by a
     * future build must be opted in deliberately, not exposed by accident. The
     * three deliberate omissions are `sync` (the merge's own bookkeeping, which
     * only the stamper may write), `master` (elected between devices, not
     * chosen), and `updatedAt` (stamped at serialization).
     *
     * `ai` is here but its key never is — see [scrubPatch].
     */
    private val PATCHABLE = setOf(
        "entries", "blocked", "profiles", "limits", "ai", "deviceProfiles",
        "sponsorSkip", "autoplay", "suggest", "listen",
        "qualityTv", "qualityPhone",
        "showVideoAge", "pageSize", "channelLayout", "channelOrder"
    )

    /** Everything the page renders, in one round trip. */
    fun state(
        store: HubStore,
        tokens: HubTokens,
        dataDir: String,
        now: Long,
        index: ChannelIndex? = null,
        master: HubMaster? = null,
        crawl: HubCrawl? = null
    ): String {
        val config = runCatching { store.load() }.getOrElse { Whitelist(emptyList(), emptySet()) }
        val raw = runCatching { JSONObject(ConfigJson.toJson(config)) }.getOrElse { JSONObject() }

        // The search index as the Devices page tells it: who builds it, how
        // far it is, whether anyone is pulling it. Counted against the
        // config's channels, not the index's files, so a channel added an
        // hour ago reads as "not yet indexed" rather than not at all.
        val indexJson = index?.let { ix ->
            val states = ix.allStates()
            val holder = config.masterDeviceToken
            val lastRun = ix.lastRunInfo()
            JSONObject()
                .put("sources", config.sources.size)
                .put("complete", config.sources.count { states[it.id]?.complete == true })
                .put("videos", config.sources.sumOf { states[it.id]?.count ?: 0 })
                .put(
                    "lastRun",
                    lastRun?.let { r -> JSONObject().put("at", r.atMillis).put("pages", r.pages).put("failed", r.failed) }
                        ?: JSONObject.NULL
                )
                .put("master", holder ?: "")
                .put("masterIsMe", holder != null && holder == tokens.selfToken())
                .put("masterIsHub", MasterToken.isHub(holder))
                .put("armed", tokens.armed(now))
                .put("lastPullAt", tokens.devices().maxOfOrNull { it.pulledAt } ?: 0L)
                .put("election", master?.last ?: "")
                .put("crawl", crawl?.last ?: "")
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
                    .put("kid", config.deviceProfiles[it.token] ?: "")
            )
        }

        val pending = JSONArray()
        tokens.pending(now).forEach {
            pending.put(JSONObject().put("code", it.code).put("name", it.name).put("createdAt", it.createdAt))
        }

        val outstanding = JSONArray()
        SettingsSurface.outstandingOnHub().forEach {
            outstanding.put(JSONObject().put("title", it.title).put("page", it.page.title))
        }

        return JSONObject()
            .put("pages", JSONArray().also { arr -> pages.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title)) } })
            // The document itself, minus its bookkeeping. The page renders from
            // this, so a control is only ever as stale as the last fetch.
            .put("config", raw.apply { remove("sync") })
            .put("devices", devices)
            .put("pending", pending)
            .put("versions", HubVersions.list(store))
            .put("index", indexJson ?: JSONObject.NULL)
            .put(
                "hub",
                JSONObject()
                    .put("hash", store.fingerprint())
                    .put("updatedAt", store.updatedAt())
                    .put("dataDir", dataDir)
                    .put("deviceCount", tokens.devices().size)
                    // Named, not counted. "Six groups still to come" tells a
                    // parent nothing; the list tells them whether the one they
                    // want is among them.
                    .put("outstanding", outstanding)
            )
            .toString()
    }

    /**
     * A device's stable short reference. Enough to tell two devices apart and
     * to name one, without handing the browser a credential.
     */
    fun deviceRef(token: String): String = token.take(8)

    /**
     * Apply a patch of root keys to the stored config.
     *
     * Returns false when the patch names nothing settable, so the caller can
     * answer 400 rather than reporting a successful no-op.
     */
    fun applyPatch(store: HubStore, who: String, now: Long, patch: JSONObject): Boolean {
        val keys = patch.keys().asSequence().filter { it in PATCHABLE }.toList()
        if (keys.isEmpty()) return false

        store.edit(who, now) { current ->
            val doc = JSONObject(ConfigJson.toJson(current))
            keys.forEach { doc.put(it, patch.get(it)) }
            ConfigJson.fromJson(scrubPatch(doc).toString())
        }
        return true
    }

    /**
     * Strip anything a browser must not be able to set, whatever it sent.
     *
     * The API key is the whole list. The hub removes it on the way to disk
     * anyway, so a key set here would appear to work and be gone after a
     * restart — and worse, it would ride out to every device in the next push
     * before vanishing, which is a credential travelling for no reason.
     */
    private fun scrubPatch(doc: JSONObject): JSONObject {
        doc.optJSONObject("ai")?.remove("apiKey")
        return doc
    }

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

    /**
     * Change one channel's per-channel settings.
     *
     * The three a parent actually reaches for on the phone: how fast this
     * channel spends screen time, the note the AI screens it against, and which
     * kids can see it at all.
     */
    fun editChannel(
        store: HubStore,
        who: String,
        now: Long,
        id: String,
        multiplier: Int?,
        note: String?,
        profileIds: Set<String>?
    ): Boolean {
        var found = false
        store.edit(who, now) { current ->
            found = current.sources.any { it.id == id }
            current.copy(
                sources = current.sources.map { e ->
                    if (e.id != id) e else e.copy(
                        timeMultiplierPercent = multiplier ?: e.timeMultiplierPercent,
                        aiNote = note ?: e.aiNote,
                        profileIds = profileIds ?: e.profileIds
                    )
                }
            )
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

    /** Dedicate a device to one kid, or hand it back to the picker with "". */
    fun assignDevice(
        store: HubStore,
        tokens: HubTokens,
        who: String,
        now: Long,
        ref: String,
        kidId: String
    ): Boolean {
        val device = tokens.devices().singleOrNull { deviceRef(it.token) == ref } ?: return false
        store.edit(who, now) { current ->
            current.copy(
                deviceProfiles = if (kidId.isBlank()) current.deviceProfiles - device.token
                else current.deviceProfiles + (device.token to kidId)
            )
        }
        return true
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

    /** Which manifest groups this page is expected to cover, by page id. */
    internal fun groupsFor(pageId: String): List<String> =
        SettingsSurface.forHub()
            .filter { pageIdOf(it.page) == pageId }
            .map { it.id }

    internal fun pageIdOf(page: Page): String = when (page) {
        Page.KIDS -> "kids"
        Page.CHANNELS -> "channels"
        Page.SCREENING -> "screening"
        Page.PLAYBACK -> "playback"
        Page.LISTING -> "listing"
        Page.DEVICES -> "devices"
        Page.BACKUP -> "backup"
    }
}
