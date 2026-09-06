package io.yosemitekids.hub

import io.yosemitekids.app.data.MasterToken

import io.yosemitekids.app.data.ChannelIndex

import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.Grant
import io.yosemitekids.app.data.Grants
import io.yosemitekids.app.data.Page
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SettingsSurface
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
     * `grants` is a fourth, and for a harder reason than the other three: a
     * patch **replaces** the key it names, so a browser that could set the
     * array could also leave an entry out of it — and a grant missing from a
     * save is expiry to the stamper, which tombstones that `grant|<id>` unit
     * for the whole fleet. It could equally send an id already live somewhere
     * as a merge key. Extra minutes therefore have a route of their own,
     * [grant], which only ever appends and mints the id here.
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
        crawl: HubCrawl? = null,
        /** When this process started, for the health block. 0 when nobody said. */
        startedAt: Long = 0L
    ): String {
        val config = runCatching { store.load() }.getOrElse { Whitelist(emptyList(), emptySet()) }
        // Keyless, and explicitly so. `config` comes off the hub's own disk,
        // which has had the key stripped from it, so `toJson` would write
        // `apiKey: ""` — an empty field, but a field, and one that would
        // start carrying a value the first time anything overlays the key
        // before getting here. The page has no use for it: it renders the
        // key's last four characters from `hub.keyTail` and never its value.
        val raw = runCatching { JSONObject(ConfigJson.toJson(config, includeSecrets = false)) }
            .getOrElse { JSONObject() }

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
                    // Where it called from and when — the two facts the hub
                    // actually knows about a device, and the two a parent asks
                    // for when one has gone quiet. Learned on authenticated
                    // calls only (HubServer.authorised); the hub never goes
                    // looking, which is what guard 7 is about.
                    .put("address", it.address ?: "")
                    .put("lastSeenAt", it.lastSeenAt)
                    // By the device's own identity, which is the key every
                    // device reads this map by — see [assignDevice].
                    .put("kid", it.deviceId?.let { id -> config.deviceProfiles[id] } ?: "")
                    // Whether it has ever said who it is. Until it has, the
                    // page offers no kid chips rather than offering an
                    // assignment that would be filed nowhere.
                    .put("known", it.deviceId != null)
                    .put("idConflict", it.idConflict)
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
            // Every control this face is expected to render, with the words to
            // render it with. The page builds the plain ones from this alone —
            // which is the whole point: a new toggle on an existing page is one
            // declaration in :core and nothing at all in index.html.
            .put("controls", controlsJson())
            // The document itself, minus its bookkeeping. The page renders from
            // this, so a control is only ever as stale as the last fetch.
            .put("config", raw.apply { remove("sync") })
            // Which is why the feed is lifted out first: it lives inside that
            // bookkeeping, and until now nothing rendered it anywhere but the
            // phone. See [changesJson].
            .put("changes", changesJson(config))
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
                    // Whether this box holds the AI key, and the most the page
                    // may ever be shown of it. Never the value: a field that
                    // rendered it back would put a credential into a browser,
                    // its autofill and every screenshot of this page.
                    .put("holdsKey", store.holdsKey())
                    .put("keyTail", store.keyTail())
                    .put("deviceCount", tokens.devices().size)
                    .put("startedAt", startedAt)
                    // The one health number a NAS actually needs. A volume
                    // that fills up takes the atomic write with it — the
                    // temp file is written, the rename never happens — and
                    // the symptom is settings that stop sticking with
                    // nothing on screen to explain it. Best effort: a bind
                    // mount whose backing store cannot be queried answers 0,
                    // which the page renders as nothing rather than as "no
                    // space left".
                    .put("freeBytes", runCatching { File(dataDir).usableSpace }.getOrDefault(0L))
                    // Named, not counted. "Six groups still to come" tells a
                    // parent nothing; the list tells them whether the one they
                    // want is among them.
                    .put("outstanding", outstanding)
            )
            .toString()
    }

    /**
     * The change feed, newest first.
     *
     * Nothing here is computed: every stamped edit and every merge already
     * appends a line through `ConfigMerge.describe`'s own vocabulary, and the
     * config this hub is holding carries the last `SyncMeta.MAX_LOG` of them.
     * They had simply never been rendered anywhere but the phone — so "why
     * did the TV change?" was unanswerable on the one box in the house that
     * had the answer sitting on its disk.
     *
     * Both stamps travel. `at` is forced monotonic (see `ConfigStamp.stamped`)
     * so a device whose clock came back wrong can still win its own edit, and
     * showing that value would hand a parent a time that never happened; the
     * page prefers `shownAt` for exactly the reason the phone's `ChangeRow`
     * does, and falls back when an older build minted no `shownAt`.
     */
    private fun changesJson(config: Whitelist): JSONArray =
        JSONArray().also { arr ->
            config.sync.log.asReversed().forEach { c ->
                arr.put(
                    JSONObject()
                        .put("code", c.code)
                        .put("text", c.text)
                        .put("who", c.who)
                        .put("at", c.at)
                        .put("shownAt", c.shownAt)
                )
            }
        }

    /**
     * The manifest's controls, as the browser needs them.
     *
     * Exactly [SettingsSurface.hubControls] — no more, because a control from a
     * group the manifest says is not ready here would render an edit that is
     * not meant to exist yet, and no less, because guard 26(b) checks the same
     * list from the other end.
     *
     * `null` is a real option value (Off, Auto, All) and travels as JSON null,
     * which the patch path reads as "unset this key" — see [applyPatch].
     */
    private fun controlsJson(): JSONArray {
        val groupOf = SettingsSurface.sections.flatMap { s -> s.controls.map { it.id to s } }.toMap()
        return JSONArray().also { arr ->
            SettingsSurface.hubControls().forEach { c ->
                val section = groupOf.getValue(c.id)
                arr.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("group", section.id)
                        .put("page", pageIdOf(section.page))
                        .put("label", c.label)
                        .put("sub", c.sub)
                        .put("kind", c.kind.name)
                        .put("json", c.json)
                        .put("unit", c.unit)
                        .put("min", c.min ?: JSONObject.NULL)
                        .put("max", c.max ?: JSONObject.NULL)
                        .put(
                            "options",
                            JSONArray().also { opts ->
                                c.options.forEach { o ->
                                    opts.put(
                                        JSONObject()
                                            .put("value", o.value ?: JSONObject.NULL)
                                            .put("label", o.label)
                                    )
                                }
                            }
                        )
                )
            }
        }
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
     *
     * A key sent as JSON `null` is **removed**, not set to null. That is what
     * "no rule", "Auto" and "All" are on this wire: `ConfigJson` asks `has()`
     * before `getInt` in half a dozen places, so a literal null would throw
     * where absence means the default. Turning a rule off from the browser is
     * therefore a delete, and it has to be — the alternative is a control that
     * can be set and never cleared.
     */
    fun applyPatch(store: HubStore, who: String, now: Long, patch: JSONObject): Boolean {
        val keys = patch.keys().asSequence().filter { it in PATCHABLE }.toList()
        if (keys.isEmpty()) return false

        store.edit(who, now) { current ->
            val doc = JSONObject(ConfigJson.toJson(current))
            keys.forEach { if (patch.isNull(it)) doc.remove(it) else doc.put(it, patch.get(it)) }
            mintKidIds(doc)
            refuseDistantPauses(doc, current, now)
            ConfigJson.fromJson(scrubPatch(doc).toString())
        }
        return true
    }

    /**
     * How far ahead of the hub's own clock a browser may put a pause.
     *
     * The hub reads no calendar: a container runs UTC and the family does not,
     * so "until midnight" is computed in the parent's browser and arrives as an
     * instant. Generous enough for any timezone plus a day, tight enough that a
     * mistyped or hostile value cannot pause a household for a month.
     */
    internal const val PAUSE_MAX_AHEAD_MS = 36L * 60 * 60 * 1000

    /**
     * Refuse a pause the browser has just pushed further out than
     * [PAUSE_MAX_AHEAD_MS], and leave every other pause exactly as it was.
     *
     * Comparing against what is already stored is the whole subtlety. A blanket
     * clamp over the document would also judge pauses a *phone* set against a
     * clock this container may not agree with — so an unrelated edit made on
     * the NAS would quietly un-pause a family. Only a value this patch changed
     * is bounded.
     */
    private fun refuseDistantPauses(doc: JSONObject, current: Whitelist, now: Long) {
        fun keep(limits: JSONObject?, was: Long?) {
            limits ?: return
            if (!limits.has("pausedUntil")) return
            val until = limits.optLong("pausedUntil", 0L)
            if (until <= now + PAUSE_MAX_AHEAD_MS || until == was) return
            if (was == null) limits.remove("pausedUntil") else limits.put("pausedUntil", was)
        }
        keep(doc.optJSONObject("limits"), current.limits.pausedUntilMillis)
        val kids = doc.optJSONArray("profiles") ?: return
        for (i in 0 until kids.length()) {
            val kid = kids.optJSONObject(i) ?: continue
            keep(
                kid.optJSONObject("limits"),
                current.profile(kid.optString("id"))?.limits?.pausedUntilMillis
            )
        }
    }

    // --- extra minutes ----------------------------------------------------

    /** What a grant did, so the page can say something true when it did nothing. */
    enum class Granted { OK, BAD_KID, BAD_MINUTES, BAD_DATE }

    /**
     * How far from this container's own day a granted date may sit.
     *
     * One day either side, because the hub reads no calendar of its own: the
     * day comes from the parent's browser, and a family in Auckland is a day
     * ahead of a container in UTC while a family in Honolulu is a day behind.
     * Wider than that and a mistyped year would sit in the config for ever —
     * a grant is expired by the *text* of its date, so one dated far ahead
     * never expires and quietly pays out again every day until someone finds
     * it.
     */
    internal const val GRANT_MAX_DAYS_AWAY = 1L

    /**
     * The same bound `LanServer`'s own `POST /grant` enforces. Two faces that
     * disagreed about it would mean a parent granting on the NAS what their
     * phone refuses, or the reverse.
     */
    internal val GRANT_MINUTES = 1..240

    /** [Profile.newId]'s shape, which is what every kid id in this config is. */
    private val KID_ID = Regex("[0-9a-f]{8}")

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Extra minutes for today, for one kid or (blank [kidId]) for everyone.
     *
     * Deliberately not a config patch. `grants` is not in [PATCHABLE] and must
     * not be: a patch replaces the array, so a browser holding a stale copy
     * would silently expire a co-parent's tap, and one holding a chosen copy
     * could mint a colliding merge key. Here the hub appends a single entry
     * and mints its own id, so the only thing a browser decides is who, how
     * many minutes, and which day.
     *
     * [date] is the browser's own local day and is checked against this
     * container's UTC day only for distance — never rewritten to it. The
     * container's day is arithmetic on [now] rather than a calendar lookup:
     * Unix time carries no leap seconds, so the division *is* the day, and it
     * is used for this bound and nothing else.
     *
     * The hub cannot deliver these minutes itself. It holds no credential on
     * any device, so unlike the phone there is no `POST /grant` it may fire:
     * it writes the tap into the config and nudges, and the minutes arrive by
     * the same path every rule does.
     */
    fun grant(
        store: HubStore,
        who: String,
        now: Long,
        kidId: String,
        minutes: Int,
        date: String
    ): Granted {
        // A kid id lands in the fingerprint between commas (ConfigJson's `;G:`
        // tail), so a stray delimiter there would let two different documents
        // hash alike and read as in sync for ever. Existence is deliberately
        // not checked: the browser only offers kids it just read, and the LAN
        // route accepts an unknown kid for the same reason — a grant may
        // precede the push that introduces the child.
        if (kidId.isNotEmpty() && !KID_ID.matches(kidId)) return Granted.BAD_KID
        if (minutes !in GRANT_MINUTES) return Granted.BAD_MINUTES
        val day = Grants.dayNumber(date) ?: return Granted.BAD_DATE
        if (Math.abs(day - Math.floorDiv(now, DAY_MS)) > GRANT_MAX_DAYS_AWAY) return Granted.BAD_DATE

        store.edit(who, now) { current ->
            // Minted against what this hub is holding rather than blind. Four
            // CSPRNG bytes collide with a handful of live grants about never,
            // but a collision is not a failure — it is two taps merged into
            // one, silently, and the retry costs nothing.
            val taken = current.grants.map { it.id }.toSet()
            var id = Profile.newId()
            while (id in taken) id = Profile.newId()
            current.copy(
                grants = current.grants + Grant(
                    id = id,
                    kidId = kidId.ifEmpty { null },
                    date = date,
                    minutes = minutes,
                    at = now
                )
            )
        }
        return Granted.OK
    }

    /**
     * Give every kid the browser sent without an id a real one.
     *
     * A kid id is merge-key material — `kid|<id>`, and the key of every
     * per-kid overlay, device assignment, grant and verdict filed under that
     * child. The GUI minted them in the browser from the clock, as the low
     * eight hex of `Date.now()`: sequential, guessable, and identical for two
     * kids added in the same millisecond on two faces of the same household.
     * A collision there does not fail; it merges two children into one
     * profile with one set of rules. [Profile.newId] is four CSPRNG bytes,
     * which is what the phone has always used.
     *
     * An id that arrived is kept exactly as it is, whatever its shape: moving
     * one would orphan every stamp and overlay under it, so the clock-minted
     * ids families already have stay. Only a blank one, or a duplicate of an
     * id already used earlier in the same array, is replaced.
     */
    private fun mintKidIds(doc: JSONObject) {
        val arr = doc.optJSONArray("profiles") ?: return
        val seen = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val kid = arr.optJSONObject(i) ?: continue
            val given = kid.optString("id")
            val id = if (given.isNotBlank() && seen.add(given)) given else Profile.newId()
            seen += id
            kid.put("id", id)
        }
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

    /** What an assignment did, so the page can say something true when it did nothing. */
    enum class Assigned { OK, NO_SUCH_DEVICE, NEVER_CALLED }

    /**
     * Dedicate a device to one kid, or hand it back to the picker with "".
     *
     * Keyed by the device's **own** pairing token, which it announces on every
     * authenticated call (`X-Device-Id` → [HubTokens.noteSeen]) — never by
     * [HubTokens.Device.token], the enrolment token this hub minted. That was
     * the bug: every device resolves `config.deviceProfiles` by its own
     * pairing token (`ConfigSync.kidHere`, `Stats`, `SettingsDevices`), so an
     * assignment filed under the enrolment token was filed where nothing
     * would ever look. "This device is for Emma", set here, did nothing at
     * all — and it failed silently, because a map lookup that misses is
     * indistinguishable from a device nobody assigned.
     *
     * A device that has never called has not said who it is, so there is no
     * key to file under. That is [Assigned.NEVER_CALLED] and the page says
     * so: unavailable is recoverable, silently wrong is not.
     */
    fun assignDevice(
        store: HubStore,
        tokens: HubTokens,
        who: String,
        now: Long,
        ref: String,
        kidId: String
    ): Assigned {
        val device = tokens.devices().singleOrNull { deviceRef(it.token) == ref }
            ?: return Assigned.NO_SUCH_DEVICE
        val id = device.deviceId ?: return Assigned.NEVER_CALLED
        store.edit(who, now) { current ->
            // The enrolment token comes off with it. Entries this hub wrote
            // under that key before the fix name no device and are inert, but
            // they are a `dev|<token>` unit in the sync blob for ever
            // otherwise, propagated to the whole fleet.
            val cleared = current.deviceProfiles - device.token - id
            current.copy(
                deviceProfiles = if (kidId.isBlank()) cleared else cleared + (id to kidId)
            )
        }
        return Assigned.OK
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
