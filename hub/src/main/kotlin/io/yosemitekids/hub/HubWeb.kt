package io.yosemitekids.hub

import io.yosemitekids.app.data.MasterToken

import io.yosemitekids.app.data.ChannelIndex

import io.yosemitekids.app.data.AiScreener
import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.Grant
import io.yosemitekids.app.data.FamilyDay
import io.yosemitekids.app.data.Page
import io.yosemitekids.app.data.Pin
import io.yosemitekids.app.data.Pins
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.ScreeningStore
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
     * Spelled out rather than derived from `SettingsSurface.Page`, and the
     * KDoc here claimed the opposite until 1.0.7. The list is held equal to
     * the manifest by guard 3, in both directions and both scripts: a page id
     * is the enum constant lowercased, so one invented on either side fails
     * the build. Deriving it would be strictly better — a list that cannot
     * drift beats a list watched by a guard — but guard 3 finds its page ids
     * by grepping the string literals below, so the two have to change
     * together, in both scripts, with the negative test.
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
     *
     * `aiAllowed`, `blockedFor` and `allowedFor` are the review queue's three
     * rulings, and they are patchable where `grants` is not. The difference is
     * what a *missing* entry means. A grant left out of a save is expiry, which
     * the stamper tombstones for the whole fleet, and grants come and go every
     * day — so a browser holding a copy from a minute ago would routinely
     * revoke a co-parent's tap. A ruling is a deliberate answer to a card the
     * parent is looking at, made against a queue the page fetched moments
     * before, and the page re-reads the whole config after every save. The
     * exposure that remains is the same one `entries`, `profiles` and `blocked`
     * have carried since the first patch route: two parents ruling in the same
     * few seconds, one of whom loses. Worth naming, because the directions
     * differ — dropping an `aiAllowed` or `allowedFor` entry hides a video
     * again, which is the safe way to fail, while dropping a `blockedFor` entry
     * lifts a block for one kid, which is not.
     *
     * `home` is the pinned hero's container, and the editor that writes it
     * lives on the Listing page (`listing-pins`). Safe as a whole-object
     * patch because the stamper diffs it per card, so it is the same shape as
     * the phone's whole-form save — and a card left out of it is an unpin,
     * which is what leaving a card out means. Two things follow from that and
     * both are handled rather than hoped about: the browser re-reads `home`
     * immediately before every pin edit, because a card missing from a copy
     * fetched two minutes ago is an unpin nobody asked for; and the ranks in
     * an incoming `home` are ignored entirely — see [normalisedPins].
     */
    private val PATCHABLE = setOf(
        "entries", "blocked", "profiles", "limits", "ai", "deviceProfiles",
        "sponsorSkip", "autoplay", "suggest", "listen",
        "qualityTv", "qualityPhone",
        "showVideoAge", "pageSize", "channelLayout", "channelOrder",
        "aiAllowed", "blockedFor", "allowedFor", "home"
    )

    /** Everything the page renders, in one round trip. */
    fun state(
        store: HubStore,
        tokens: HubTokens,
        dataDir: String,
        now: Long,
        index: ChannelIndex? = null,
        /** The verdicts this hub holds, for the review queue. Null in tests that have none. */
        screening: ScreeningStore? = null,
        master: HubMaster? = null,
        crawl: HubCrawl? = null,
        /** When this process started, for the health block. 0 when nobody said. */
        startedAt: Long = 0L,
        /**
         * The browsers claimed on the kid origin, for the card that revokes
         * one. Null on a hub built without the kid listener at all, which the
         * page renders as an empty list rather than as an error.
         */
        browsers: HubBrowsers? = null,
        /** What the clients have reported going wrong, for the Devices page. Null in tests that have none. */
        reports: HubReports? = null
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
                // Channels YouTube refused outright, in YouTube's words, so the
                // Channels page can say so beside the row and the Devices page
                // can list them. The videos already indexed stay until the parent
                // removes the channel; that is their call, not the crawl's.
                .put(
                    "gone",
                    JSONArray().also { arr ->
                        config.sources.forEach { e ->
                            val s = states[e.id] ?: return@forEach
                            val why = s.gone ?: return@forEach
                            arr.put(
                                JSONObject().put("id", e.id).put("name", e.label ?: e.id)
                                    .put("reason", why).put("at", s.goneAt)
                            )
                        }
                    }
                )
                .put(
                    "lastRun",
                    lastRun?.let { r -> JSONObject().put("at", r.atMillis).put("pages", r.pages).put("failed", r.failed) }
                        ?: JSONObject.NULL
                )
                // The REF, not the token. A parent needs to know whether
                // anyone is building the index and whether it is this box;
                // the page tests this for truthiness and reads the two
                // booleans below for the rest. Eight characters also match
                // the Devices rows, which have always been refs.
                .put("master", holder?.let { deviceRef(it) } ?: "")
                .put("masterIsMe", holder != null && holder == tokens.selfToken())
                .put("masterIsHub", MasterToken.isHub(holder))
                .put("armed", tokens.armed(now))
                .put("lastPullAt", tokens.devices().maxOfOrNull { it.pulledAt } ?: 0L)
                .put("election", master?.last ?: "")
                .put("crawl", crawl?.last ?: "")
                .put("paused", crawl?.paused ?: false)
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

        // Claimed browsers, by the short reference the page revokes with —
        // never the cookie value. A page that could read one back would be a
        // page that could hand a child's credential to anyone looking over a
        // parent's shoulder, and the page has no use for it: it administers,
        // it does not watch.
        val watchers = JSONArray()
        browsers?.browsers()?.forEach {
            watchers.put(
                JSONObject()
                    .put("ref", it.ref)
                    .put("kid", it.kid)
                    .put("claimedAt", it.claimedAt)
                    .put("lastSeenAt", it.lastSeenAt)
            )
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
            // The pinned hero's one number the page needs, from :core rather
            // than typed into index.html. The page decides nothing else about
            // the row — the ranks it would otherwise have to space are minted
            // by normalisedPins — but it does have to know when to stop
            // offering "Pin a channel", and a 3 typed here would be a second
            // cap to drift from the renderer's.
            .put("pins", JSONObject().put("max", Pins.MAX))
            // The home's catalogue for the row editor: every shelf this build
            // draws, in default order, with the one spelling of its name.
            // From :core, so the editor cannot offer a shelf the home cannot
            // draw or call one something the kid page does not.
            .put(
                "shelves",
                JSONArray().also { arr ->
                    io.yosemitekids.app.ui.HOME_SHELVES.forEach { id ->
                        arr.put(JSONObject().put("id", id).put("label", io.yosemitekids.app.ui.homeShelfLabel(id)))
                    }
                }
            )
            // The rows a parent may ADD to a home beside the catalogue: every channel
            // of the family, and every playlist the crawl has indexed for one
            // (HomeRowKind). Labels resolved here, once, so the page names none.
            .put(
                "rowOptions",
                JSONArray().also { arr ->
                    config.sources.forEach { e ->
                        val name = e.label ?: e.id
                        arr.put(JSONObject().put("id", io.yosemitekids.app.ui.HomeRowKind.channelRow(e.id)).put("label", name))
                        index?.loadPlaylists(e.id)?.playlists?.forEach { p ->
                            if (p.videoIds?.isNotEmpty() == true) {
                                arr.put(JSONObject().put("id", io.yosemitekids.app.ui.HomeRowKind.playlistRow(p.id)).put("label", "${p.name} · $name"))
                            }
                        }
                    }
                }
            )
            // The document itself, minus its bookkeeping. The page renders from
            // this, so a control is only ever as stale as the last fetch.
            .put("config", withoutCredentials(raw.apply { remove("sync") }))
            // Which is why the feed is lifted out first: it lives inside that
            // bookkeeping, and until now nothing rendered it anywhere but the
            // phone. See [changesJson].
            .put("changes", changesJson(config))
            // What the AI is holding back, and what it blocked. Null on a hub
            // built without a verdict store at all, which the page renders as
            // "nothing here yet" rather than as an error.
            .put("review", reviewJson(screening, config))
            .put("devices", devices)
            .put("browsers", watchers)
            // What the clients reported going wrong, newest first. An empty
            // list on a hub built without the store, never an absent key.
            .put("reports", reports?.recent() ?: org.json.JSONArray())
            .put("pending", pending)
            .put("versions", HubVersions.list(store))
            .put("index", indexJson ?: JSONObject.NULL)
            .put(
                "hub",
                JSONObject()
                    .put("hash", store.fingerprint())
                    .put("updatedAt", store.updatedAt())
                    // Which build a parent is looking at, on the page they
                    // would look at. `docker pull` is silent about whether it
                    // moved, and an image left behind does not merely lack a
                    // control — it drops the config key behind it on the next
                    // save here (hub/build.gradle.kts says why). This is the
                    // number to read back when a setting will not stick.
                    .put("version", HubBuild.VERSION)
                    .put("dataDir", dataDir)
                    // The one state in which the console must not be believed:
                    // a config.json that is there and will not parse reads as
                    // a family with no children and no channels, and a parent
                    // adding them back would write that emptiness over it.
                    // Every save is refused with this reason until it is
                    // restored (HubServer.mutate).
                    .put("configOk", !store.degraded())
                    // Whether this box holds the AI key, and the most the page
                    // may ever be shown of it. Never the value: a field that
                    // rendered it back would put a credential into a browser,
                    // its autofill and every screenshot of this page.
                    .put("holdsKey", store.holdsKey())
                    .put("keyTail", store.keyTail())
                    .put("deviceCount", tokens.devices().size)
                    // Where the kid app is on this origin, for the card that
                    // tells a parent what to open on the tablet. A path, not
                    // a port: one address for the whole hub.
                    .put("kidPath", HubKidServer.KID_PATH)
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
     * How many held-back videos the page is handed at once.
     *
     * The store caps itself at 5000 verdicts, and a browser asked to draw five
     * thousand cards with a thumbnail each — over a home LAN, from a NAS — is a
     * page nobody opens twice. A parent rules from the top; the count beside
     * the list says how much is behind it.
     */
    internal const val MAX_REVIEW_SHOWN = 60

    /**
     * The AI's two piles, as the review queue draws them.
     *
     * Nothing is looked up to build this. A `ScreeningStore.Entry` already
     * carries the title, channel, thumbnail and the AI's own reason, because a
     * device stored them for exactly this purpose — which is why the hub needs
     * no video cache, no feed and no crawl to put a queue on the page. The
     * thumbnails are URLs the **browser** fetches; this box asks YouTube for
     * nothing, so guard 7 is untouched. That it is the browser and not the hub
     * is a real statement about a page a parent opens, and `docs/HUB.md` makes
     * it.
     *
     * The queue drops whatever the family has already ruled on — the same set
     * the phone calls `resolved` — because a card answered on one face must not
     * come back on the other. The blocked pile is deliberately *not* filtered:
     * it exists so a wrong call can be overruled, and a video allowed for one
     * kid is still blocked for the rest.
     */
    private fun reviewJson(screening: ScreeningStore?, config: Whitelist): Any {
        screening ?: return JSONObject.NULL
        val rules = config.ai.rulesVersion
        val ruled = config.blockedVideoIds + config.aiAllowedVideoIds +
            config.blockedFor.keys + config.allowedFor.keys
        val flagged = runCatching { screening.flagged(rules) }.getOrDefault(emptyList())
        val queue = flagged.filter {
            it.second.verdict == AiScreener.Verdict.REVIEW && it.first !in ruled
        }
        val blocked = flagged.filter { it.second.verdict == AiScreener.Verdict.BLOCK }
        return JSONObject()
            .put("rulesVersion", rules)
            .put("screened", runCatching { screening.screenedCount(rules) }.getOrDefault(0))
            .put("queue", flaggedJson(queue.take(MAX_REVIEW_SHOWN)))
            .put("queueTotal", queue.size)
            .put("blocked", flaggedJson(blocked.take(MAX_REVIEW_SHOWN)))
            .put("blockedTotal", blocked.size)
    }

    private fun flaggedJson(entries: List<Pair<String, ScreeningStore.Entry>>): JSONArray =
        JSONArray().also { arr ->
            entries.forEach { (id, e) ->
                arr.put(
                    JSONObject()
                        .put("id", id)
                        .put("title", e.title)
                        .put("channel", e.channel)
                        .put("thumb", e.thumb ?: "")
                        .put("why", e.reason)
                        .put("at", e.at)
                        // Per-kid verdicts, so a card can say "held for Dave ·
                        // fine for Katy" in the phone's own words instead of
                        // asking for one answer on behalf of every child.
                        .put(
                            "forKids",
                            JSONObject().also { o ->
                                e.perProfile.forEach { (pid, v) -> o.put(pid, v.name) }
                            }
                        )
                )
            }
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
     * The family document with every credential taken out of it.
     *
     * The console renders from the document, and the easiest way to render a
     * page from a document is to ship the document. So this route did, and
     * four credentials went out on every poll - to every signed-in tab, into
     * every network log, and into every screenshot a parent pastes into a
     * thread asking why their hub is slow:
     *
     *  - `profiles[].pin`, the four directions a child presses to reach
     *    their own row on the television. The console has no control for it
     *    - it is set on the phone - and never read it.
     *  - `profiles[].web`, the PBKDF2 record behind a kid's browser
     *    password. Not the password, but salt, iterations and hash for a
     *    credential that may be four characters long, which makes it an
     *    afternoon's offline guessing rather than a secret.
     *  - `master`, the pairing token of the phone that builds the index: a
     *    bearer credential for every device route on the LAN.
     *  - `deviceProfiles`, which is keyed BY device token.
     *
     * What the page actually asks of the first two is "is there one": it
     * draws "Set" or "Change" from `!!k.hasWeb` and nothing else. So that is
     * what it gets, and the record stays on the box. Nothing reads the last
     * two at all, and `devicePage`'s own KDoc says this hub "holds no
     * credential" on a device - which was true of what it stores and false
     * of what it served.
     *
     * [scrubPatch] is the other half: the page edits a kid by copying the
     * profile it was given, so what is withheld here must not read as a
     * removal on the way back.
     */
    private fun withoutCredentials(raw: JSONObject): JSONObject {
        raw.remove("master")
        raw.remove("deviceProfiles")
        val profiles = raw.optJSONArray("profiles") ?: return raw
        for (i in 0 until profiles.length()) {
            val p = profiles.optJSONObject(i) ?: continue
            p.remove("pin")
            if (p.remove("web") != null) p.put("hasWeb", true)
        }
        return raw
    }

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
    fun applyPatch(
        store: HubStore,
        who: String,
        now: Long,
        patch: JSONObject,
        /**
         * The claim store, so a child who is deleted takes their browsers with
         * them. Null in tests that hold none.
         *
         * A browser's credential names a profile, and a profile that stops
         * existing does not stop the browser: `limitsFor` an unknown kid is
         * the **family default**, so a deleted child's tablet would quietly
         * carry on watching under the loosest rules in the house. Nothing
         * would throw and nothing on any screen would say so.
         */
        browsers: HubBrowsers? = null
    ): Boolean {
        val keys = patch.keys().asSequence().filter { it in PATCHABLE }.toList()
        if (keys.isEmpty()) return false

        // Read before the edit, so the comparison below is against what this
        // patch actually removed rather than against what it sent.
        val hadKids = if (browsers != null && "profiles" in keys) {
            runCatching { store.load().profiles.map { it.id } }.getOrDefault(emptyList())
        } else emptyList()

        store.edit(who, now) { current ->
            val doc = JSONObject(ConfigJson.toJson(current))
            keys.forEach { if (patch.isNull(it)) doc.remove(it) else doc.put(it, patch.get(it)) }
            mintKidIds(doc)
            refuseDistantPauses(doc, current, now)
            val next = ConfigJson.fromJson(scrubPatch(doc, current).toString())
            // Only when the patch actually named `home`. Running it on every
            // patch would re-derive a row nobody touched, and a row four cards
            // long from a build that allows four would be trimmed by an edit
            // to the AI model.
            if ("home" in keys) {
                next.copy(pins = normalisedPins(current, next), homeRows = normalisedRows(current, next))
            } else next
        }

        if (hadKids.isNotEmpty()) {
            val kept = runCatching { store.load().profiles.map { it.id } }
                .getOrDefault(hadKids).toSet()
            (hadKids - kept).forEach { browsers?.revokeFor(it) }
        }
        return true
    }

    /**
     * The pinned hero as this hub will store it, given what a browser sent.
     *
     * **The browser's ranks are not read at all.** A page cannot mint one:
     * `Pins.RANK_STEP` spacing, the cap and the fail-closed filter are one set
     * of rules in `:core`, and a second copy of them in JavaScript would not
     * fail loudly — it would drift, and the symptom would be a home screen
     * whose order differs between the television and this box. So the array's
     * own **position** is the parent's order on the way in (it is
     * `Pins.ordered` on the way out, as everywhere else), and every rank is
     * minted here by [Pins.withRow] against the ranks already stored. An
     * unmoved card therefore keeps its rank and stamps nothing, exactly as it
     * does on the phone.
     *
     * Every row on either side is re-derived, and a row the patch does not
     * list is **emptied** — that is what leaving a card out of a whole-object
     * patch means, and it is why the page copies every other kid's cards
     * across from a copy it fetched moments before. A row that arrives in the
     * order it is already stored in mints nothing: `Pins.withRow` finds every
     * rank in place and touches none of them.
     *
     * What this cannot fix, and nothing here should pretend to: two parents
     * moving the same card resolve by the later stamp, silently, per card.
     */
    /**
     * The home's rows, the way [normalisedPins] does the hero: the browser
     * sends one home's shelves in the parent's order with their flags, and
     * every rank is minted here by `HomeRows.withOrder` against what is
     * stored (guard 70). A home the patch does not list is reset to the
     * default, which is what leaving it out of a whole-object patch means.
     */
    private fun normalisedRows(current: Whitelist, next: Whitelist): List<io.yosemitekids.app.data.HomeRow> {
        val homes = (current.homeRows.map { it.kidId } + next.homeRows.map { it.kidId }).distinct()
        var rows = current.homeRows
        homes.forEach { kid ->
            rows = io.yosemitekids.app.data.HomeRows.withOrder(
                rows, kid,
                io.yosemitekids.app.data.HomeRows.rowsOf(next.homeRows, kid)
                    .map { io.yosemitekids.app.ui.HomeSection(it.id, it.enabled) }
            )
        }
        return rows
    }

    private fun normalisedPins(current: Whitelist, next: Whitelist): List<Pin> {
        // Both sides, so a row the patch emptied is emptied rather than kept.
        val rows = (current.pins.map { it.kidId } + next.pins.map { it.kidId }).distinct()
        var pins = current.pins
        rows.forEach { kid ->
            pins = Pins.withRow(
                pins, kid,
                next.pins.filter { it.kidId == kid }.map { it.sourceId },
                // The channels as this same patch leaves them: one request may
                // restrict a channel to one kid and pin it for another, and the
                // pin has to lose.
                next.sources
            )
        }
        return pins
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
        val day = FamilyDay.dayNumber(date) ?: return Granted.BAD_DATE
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
    private fun scrubPatch(doc: JSONObject, current: Whitelist): JSONObject {
        doc.optJSONObject("ai")?.remove("apiKey")
        // Everything [withoutCredentials] withheld, taken from what is stored
        // and never from what was sent.
        //
        // The page edits a kid by copying the profile object it was given and
        // changing one field, and a patch is a replacement: the instant
        // `/api/state` stops sending `pin`, a rename sends a profile that has
        // none. Read as written that is a parent clearing their child's code,
        // and the stamper would push the removal to every device in the house
        // before anyone noticed the television had stopped asking.
        //
        // So these are not patchable, by construction rather than by a list:
        // a kid's code comes from the phone, their browser password has a
        // route of its own (/api/kid-password), and the other two are tokens
        // no browser is shown. A console control for any of them needs a
        // route of its own, which is the point.
        current.masterDeviceToken?.let { doc.put("master", it) } ?: doc.remove("master")
        if (current.deviceProfiles.isEmpty()) doc.remove("deviceProfiles")
        else doc.put("deviceProfiles", JSONObject(current.deviceProfiles as Map<String, String>))
        val stored = current.profiles.associateBy { it.id }
        val profiles = doc.optJSONArray("profiles") ?: return doc
        for (i in 0 until profiles.length()) {
            val p = profiles.optJSONObject(i) ?: continue
            p.remove("hasWeb")
            val was = stored[p.optString("id")]
            was?.pin?.let { p.put("pin", it) } ?: p.remove("pin")
            was?.webPassword?.toJson()?.let { p.put("web", it) } ?: p.remove("web")
        }
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

    /** What a mint did, so the page can say something true when it did nothing. */
    enum class Minted { OK, BAD_KID, TOO_MANY_CODES }

    /** The code a parent reads onto the tablet, and why there is not one. */
    data class Mint(val why: Minted, val code: String? = null)

    /**
     * Mint a claim code for one child, for a browser to redeem on the kid
     * origin.
     *
     * The kid **must exist**, which is the one place this differs from
     * [grant]: a grant may legitimately precede the push that introduces a
     * child, but a credential bound to a profile nobody has ever heard of is a
     * browser that would play under `limitsFor(null)` — the family default —
     * for as long as it lives. Failing closed here costs a parent one re-tap
     * after the kid syncs; failing open hands a child the loosest rules in the
     * house.
     *
     * The config is read, never written. Claimed browsers live in
     * `browsers.json` and deliberately not in the family document: they are
     * local to this box, they are not curation, and putting them in the config
     * would push a bearer credential to every television in the house.
     */
    fun mintClaim(store: HubStore, browsers: HubBrowsers, kidId: String, now: Long): Mint {
        val config = runCatching { store.load() }.getOrNull() ?: return Mint(Minted.BAD_KID)
        if (config.profile(kidId) == null) return Mint(Minted.BAD_KID)
        val code = browsers.mint(kidId, now) ?: return Mint(Minted.TOO_MANY_CODES)
        return Mint(Minted.OK, code)
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
