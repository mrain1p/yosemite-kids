package io.yosemitekids.app.data

/**
 * Every settings group, every control inside it, which page it sits on, and
 * which faces it belongs to.
 *
 * The hub's GUI mirrors the phone's Settings. Two UIs meant to mirror each
 * other drift the moment one gains a feature, silently: nothing about a Compose
 * screen tells you a browser page across the repo was supposed to grow the same
 * control, and the drift is found by a parent who cannot do on the NAS the
 * thing they just did on their phone.
 *
 * So this is not documentation. `scripts/check.ps1` and `scripts/check.sh` read
 * it and fail the build in both directions. Editing this file is therefore the
 * moment the "does this belong on the hub?" decision gets made, rather than
 * something that can be skipped.
 *
 * **Keyed on [SettingsSection.fields], not on composables.** The first version
 * listed `...Section(` functions, which misses two whole categories: the
 * Playback page has no section composable at all — it is inline cards — and the
 * "Kid's shelves" card on Channels is inline too. A guard that counts
 * composables cannot see either, so an entire page was invisible to it. Every
 * field `Settings.buildCurrentConfig` writes must appear here, which is a
 * property no amount of inlining can hide from.
 *
 * **And keyed on [SettingsControl], not only on groups.** A group is too coarse
 * to be a promise: `hubReady = true` is permanent, so a new toggle inside a
 * group the hub already renders slipped through unnoticed. That was not
 * hypothetical — it had happened twice and both were live. `screen-time-rules`
 * claimed the hub while the hub rendered four of a kid's seven rules, no
 * `minVideoMinutes` and no pause; `blocked-times` claimed the hub with no
 * windows editor at all. Guard 26 now reads the control list from both ends:
 * every leaf of [Whitelist], [Limits] and [AiConfig] is claimed by exactly one
 * control or listed in [NOT_A_CONTROL], and every control that says it is on
 * the hub is either rendered generically from this manifest or built by hand
 * with a `data-control` attribute the guard can find.
 *
 * **The manifest owns the parent-facing words.** Both faces read [label] and
 * [sub] from here, which is what makes the phone-side half of the guard real
 * rather than a rubber stamp: a control that is not declared has no label to
 * render. It also removes a class of drift nobody was watching — the two faces
 * describing the same switch differently, which they did: the phone's "Time per
 * session" was the hub's "Minutes a session", and the hub offered page sizes
 * (12/24/48) and quality steps the app has never had.
 *
 * It lives in :core because both consumers must read the same list; in :app it
 * would be invisible to the hub, and in :hub invisible to the app.
 */
enum class Page(val title: String) {
    KIDS("Kids"),
    CHANNELS("Channels & playlists"),
    SCREENING("Content screening"),
    PLAYBACK("Playback"),
    LISTING("How videos are listed"),
    DEVICES("Devices & sync"),
    BACKUP("App, hub & backup")
}

enum class Where {
    /** Phone (or TV) only. Usually because it touches Android itself. */
    PHONE,

    /** Hub only. Administration of the box, with no phone equivalent. */
    HUB,

    /** Both faces. The default expectation for anything that is family policy. */
    BOTH
}

/**
 * How a control is drawn. Everything but [CUSTOM] the hub renders from this
 * manifest alone, which is the whole point: a new toggle on an existing page
 * is one declaration here and *nothing* in `index.html`.
 *
 * [CUSTOM] is for a control no generic renderer could do justice to — the
 * channel list with its per-kid chips, the device rows, the windows editor.
 * Those stay hand-written and carry `data-control="<id>"` so the guard can
 * still tell built from promised.
 */
enum class ControlKind { TOGGLE, NUMBER, TEXT, TEXTAREA, CHIPS, CUSTOM }

/** One choice of a [ControlKind.CHIPS] control. `null` is a real value: Off, Auto, All. */
data class ControlOption(val value: Any?, val label: String)

data class SettingsControl(
    /** Stable id. Both faces address the control by exactly this string. */
    val id: String,
    /** The words the parent reads, on both faces. */
    val label: String,
    /** The line under the label. Blank for a control that needs none. */
    val sub: String = "",
    val kind: ControlKind,
    /**
     * The Kotlin property path this control sets — `"sponsorSkip"`,
     * `"limits.minVideoMinutes"`, `"ai.model"`. Empty for a control that
     * writes no config at all. This is what guard 26(a) matches against the
     * declared properties of [Whitelist], [Limits] and [AiConfig], and what
     * `SettingsSurfaceTest` resolves by reflection — so a field renamed in
     * one place and not the other fails rather than drifting.
     */
    val writes: String = "",
    /**
     * The JSON leaf path a browser patches, when [ConfigJson] spells it
     * differently from the property (`autoplayNext` is `autoplay` on the
     * wire). Defaults to [writes] because they are usually the same word.
     */
    val json: String = writes,
    /** For [ControlKind.CHIPS]: the choices, in the order the phone offers them. */
    val options: List<ControlOption> = emptyList(),
    /** For [ControlKind.NUMBER]: the range the phone enforces, mirrored by the hub. */
    val min: Int? = null,
    val max: Int? = null,
    /** "min" — shown after a number, on both faces. */
    val unit: String = "",
    val where: Where = Where.BOTH,
    /**
     * Why this control is on one face only. Guard 26(d) requires it whenever
     * [where] is not [Where.BOTH]: "specific to each" is a decision, and a
     * decision with no recorded reason is re-litigated every round.
     */
    val why: String = ""
)

data class SettingsSection(
    /** Stable id. The hub's page registry uses exactly this string. */
    val id: String,
    val title: String,
    val page: Page,
    /**
     * The `...Section(` composable in :app, or "" when the phone renders these
     * controls inline with no composable of their own — which is most of the
     * Playback page.
     */
    val composable: String,
    /**
     * The `Whitelist` fields this group writes, exactly as
     * `Settings.buildCurrentConfig` names them. Empty for a group that writes
     * none: a screen-time grant is device-local session state and never enters
     * config.json at all.
     */
    val fields: List<String>,
    val where: Where,
    /**
     * False for a [Where.BOTH] group not yet built on the hub. The guard skips
     * it and the check prints what is outstanding, so the remaining work is
     * visible rather than quietly absent.
     */
    val hubReady: Boolean,
    /** Why it is placed this way. The part a future session cannot re-derive. */
    val why: String,
    /** Every control inside this group. See [SettingsControl]. */
    val controls: List<SettingsControl> = emptyList()
)

object SettingsSurface {

    val sections: List<SettingsSection> = listOf(

        // --- Kids ---------------------------------------------------------
        SettingsSection("kids", "Kids", Page.KIDS, "KidsSection", listOf("profiles"),
            Where.BOTH, true,
            "Who the kids are: name, age, avatar, colour, profile lock. Pure config.",
            controls = listOf(
                SettingsControl(
                    "kids-list", "Kids",
                    sub = "Name, age, avatar and the profile lock.",
                    kind = ControlKind.CUSTOM, writes = "profiles"
                )
            )),
        SettingsSection("screen-time-rules", "Screen time", Page.KIDS, "RulesSection",
            listOf("limits", "profiles"), Where.BOTH, true,
            "Family policy, not device state. Per kid and for everyone.",
            controls = listOf(
                // The five recurring rules. Per kid through Profile.limits and,
                // for a family with no kids added yet, through Whitelist.limits
                // — Whitelist.limitsFor picks between them, and neither face
                // gets to have its own opinion about which.
                SettingsControl(
                    "rules-session", "Time per session",
                    kind = ControlKind.NUMBER, writes = "limits.sessionMinutes",
                    json = "limits.session", min = 5, max = 240, unit = "min"
                ),
                SettingsControl(
                    "rules-weekday-sessions", "Sessions on weekdays",
                    kind = ControlKind.NUMBER, writes = "limits.weekdaySessions",
                    min = 1, max = 12
                ),
                SettingsControl(
                    "rules-weekend-sessions", "Sessions on weekends",
                    kind = ControlKind.NUMBER, writes = "limits.weekendSessions",
                    min = 1, max = 12
                ),
                SettingsControl(
                    "rules-break", "Break between sessions",
                    kind = ControlKind.NUMBER, writes = "limits.breakMinutes",
                    min = 15, max = 240, unit = "min"
                ),
                SettingsControl(
                    "rules-min-video", "Hide videos shorter than",
                    kind = ControlKind.NUMBER, writes = "limits.minVideoMinutes",
                    min = 1, max = 60, unit = "min"
                ),
                SettingsControl(
                    "rules-break-pass", "Skip the next break",
                    sub = "Waives one break, once.",
                    kind = ControlKind.CUSTOM, writes = "limits.breakPassUntilMillis",
                    json = "limits.breakPassUntil", where = Where.PHONE,
                    why = "A pass over the break happening now, taken on the device in " +
                        "the room in the minute it matters. The hub could write the same " +
                        "field, but it cannot call a device — it edits and nudges — so a " +
                        "pass set on the NAS would reach a sleeping television after the " +
                        "break it was meant to skip."
                ),
                SettingsControl(
                    "rules-pause", "Turn off all watching",
                    sub = "Until midnight.",
                    kind = ControlKind.CUSTOM, writes = "limits.pausedUntilMillis",
                    json = "limits.pausedUntil"
                )
            )),
        SettingsSection("blocked-times", "Blocked times", Page.KIDS, "BlockedTimesSection",
            listOf("limits", "profiles"), Where.BOTH, true,
            "Merge-carried like the rest of limits.",
            controls = listOf(
                SettingsControl(
                    "blocked-times-windows", "Blocked times",
                    sub = "Bedtime, school hours — a stretch of the clock when watching is off.",
                    kind = ControlKind.CUSTOM, writes = "limits.windows"
                )
            )),
        SettingsSection("grant-time", "Grant extra time", Page.KIDS, "GrantTimeSection",
            emptyList(), Where.BOTH, true,
            "Merged config since 1.0.5: each tap is a `grant|<id>` unit, which is " +
                "what lets a television that slept through it find the minutes " +
                "when it wakes. The faces differ in delivery, not in effect — the " +
                "phone also calls POST /grant on whatever is awake, and the hub, " +
                "holding no credential on any device, writes the tap and nudges.",
            controls = listOf(
                SettingsControl(
                    "grant-time-minutes", "Bonus watch time",
                    // Not "for one kid": a household with no profiles grants
                    // to everyone, which is the same control on both faces.
                    sub = "Extra minutes for today, on top of the usual limit.",
                    kind = ControlKind.CUSTOM, writes = "grants"
                )
            )),

        // --- Channels & playlists -----------------------------------------
        SettingsSection("channels", "Channels & playlists", Page.CHANNELS, "ChannelsSection",
            listOf("sources", "blockedVideoIds"), Where.BOTH, true,
            "The curation itself. Includes each channel's time multiplier, its " +
                "screening note, and which kids can see it — and the individual " +
                "videos blocked inside an allowed channel, which both faces " +
                "already list here.",
            controls = listOf(
                SettingsControl(
                    "channels-list", "Channels & playlists",
                    sub = "What the kids can watch, how fast it spends screen time, " +
                        "and which kids see it.",
                    kind = ControlKind.CUSTOM, writes = "sources", json = "entries"
                ),
                SettingsControl(
                    "channels-blocked", "Blocked videos",
                    sub = "Individual videos hidden even inside an allowed channel.",
                    kind = ControlKind.CUSTOM, writes = "blockedVideoIds", json = "blocked"
                )
            )),
        SettingsSection("kid-shelves", "How videos are listed", Page.LISTING, "",
            listOf("showVideoAge", "pageSize", "channelLayout", "channelOrder"),
            Where.BOTH, true,
            "How the kid's home is laid out. Inline on the phone with no " +
                "composable of its own, which is exactly why this manifest is " +
                "keyed on fields.",
            controls = listOf(
                SettingsControl(
                    "listing-video-age", "Show when a video came out",
                    sub = "“3 days ago” beside the channel name",
                    kind = ControlKind.TOGGLE, writes = "showVideoAge"
                ),
                SettingsControl(
                    "listing-page-size", "Videos before “Show more”",
                    kind = ControlKind.CHIPS, writes = "pageSize",
                    options = PAGE_SIZES.map { ControlOption(it, it?.toString() ?: "All") }
                ),
                SettingsControl(
                    "listing-channel-layout", "Channel page layout",
                    kind = ControlKind.CHIPS, writes = "channelLayout",
                    // The two the phone offers. CHANNEL_LAYOUTS carries a third
                    // the settings screen has never shown; a face that offered
                    // it would be offering something the other cannot.
                    options = listOf(
                        ControlOption(CHANNEL_LAYOUT_NEWEST, "Newest first"),
                        ControlOption(CHANNEL_LAYOUT_POPULAR, "Popular first")
                    )
                ),
                SettingsControl(
                    "listing-channel-order", "Channel row order",
                    kind = ControlKind.CHIPS, writes = "channelOrder",
                    options = listOf(
                        ControlOption(CHANNEL_ORDER_WATCHED, "Most watched"),
                        ControlOption(CHANNEL_ORDER_ALPHA, "A to Z"),
                        ControlOption(CHANNEL_ORDER_RANDOM, "Random"),
                        ControlOption(CHANNEL_ORDER_LATEST, "Latest video")
                    )
                )
            )),
        SettingsSection("directory", "Suggested channels", Page.CHANNELS, "DirectorySection",
            listOf("sources"), Where.PHONE, false,
            "Fetches the shared directory through :app's Directory client. The " +
                "hub could do this — it is plain HTTP — but :hub has no HTTP " +
                "client dependency today and adding one is its own decision."),

        // --- Content screening --------------------------------------------
        SettingsSection("ai-connection", "AI connection", Page.SCREENING, "AiConnectionSection",
            listOf("ai"), Where.BOTH, true,
            "Provider, base URL and model yes; the API key no. The hub strips " +
                "secrets before writing and has no keystore, so a key entered " +
                "there could not survive a restart. The hub's page says so " +
                "rather than offering a field that silently forgets.",
            controls = listOf(
                SettingsControl(
                    "ai-base-url", "API base URL",
                    sub = "Any OpenAI-compatible endpoint.",
                    kind = ControlKind.TEXT, writes = "ai.baseUrl"
                ),
                SettingsControl(
                    "ai-model", "Model",
                    kind = ControlKind.TEXT, writes = "ai.model"
                ),
                SettingsControl(
                    "ai-api-key", "API key",
                    sub = "Leave empty for a local server.",
                    kind = ControlKind.CUSTOM, writes = "ai.apiKey", where = Where.PHONE,
                    why = "The only field in this manifest that is a credential. It lives " +
                        "in the phone's Keystore-backed SecretStore, never in config.json, " +
                        "and ConfigStore strips it from every copy that reaches disk — so " +
                        "a key typed on the NAS would appear to work, ride out to every " +
                        "device, and be gone after a restart. Giving the hub a store of " +
                        "its own is PLAN-hub-parity step 10."
                )
            )),
        SettingsSection("ai-screening", "AI screening", Page.SCREENING, "AiScreeningSection",
            listOf("ai", "profiles"), Where.BOTH, true,
            "The rules text and the switch. Policy, carried by the merge.",
            controls = listOf(
                SettingsControl(
                    "ai-enabled", "Screen new videos with AI",
                    sub = "Titles and channel names only — never watch history",
                    kind = ControlKind.TOGGLE, writes = "ai.enabled"
                ),
                SettingsControl(
                    "ai-rules", "What to allow, in your words",
                    sub = "Rough notes are fine — the AI understands shorthand. " +
                        "One rule per line.",
                    kind = ControlKind.TEXTAREA, writes = "ai.rules"
                ),
                SettingsControl(
                    "ai-child-age", "Child age",
                    sub = "What the AI screens against when there are no kid profiles.",
                    kind = ControlKind.NUMBER, writes = "ai.childAge", min = 2, max = 16
                )
            )),
        SettingsSection("ai-review", "Waiting for your OK", Page.SCREENING, "AiReviewSection",
            listOf("blockedFor", "allowedFor", "aiAllowedVideoIds"),
            Where.PHONE, false,
            "The rulings are config, but the queue is not: it is built from " +
                "ScreeningStore verdicts and the video cache, neither of which " +
                "the hub holds. It would render an empty list until devices " +
                "push verdicts to it.",
            controls = listOf(
                SettingsControl(
                    "review-allowed", "Allowed anyway",
                    sub = "Videos the AI blocked and a parent let through.",
                    kind = ControlKind.CUSTOM, writes = "aiAllowedVideoIds", json = "aiAllowed",
                    where = Where.PHONE,
                    why = "A ruling is made against the queue, and the queue is " +
                        "ScreeningStore's verdicts plus the video cache — neither of " +
                        "which the hub holds. It would render an empty list."
                ),
                SettingsControl(
                    "review-blocked-for", "Blocked for one kid",
                    sub = "A long-press ruling that applies to one child, not the family.",
                    kind = ControlKind.CUSTOM, writes = "blockedFor", where = Where.PHONE,
                    why = "Same queue, same reason as review-allowed."
                ),
                SettingsControl(
                    "review-allowed-for", "Allowed for one kid",
                    sub = "Fine for the older one, not the younger.",
                    kind = ControlKind.CUSTOM, writes = "allowedFor", where = Where.PHONE,
                    why = "Same queue, same reason as review-allowed."
                )
            )),
        SettingsSection("ai-discovery", "Discover with AI", Page.SCREENING, "AiDiscoverySection",
            listOf("sources"), Where.PHONE, false,
            "Verifies each suggested channel against YouTube through " +
                "NewPipeExtractor, which :hub does not depend on."),

        // --- Devices --------------------------------------------------------
        SettingsSection("devices", "Devices", Page.DEVICES, "PhoneDevicesSection",
            listOf("deviceProfiles", "masterDeviceToken"), Where.BOTH, true,
            "Both faces list devices, but they are not the same page: the phone " +
                "lists what it paired, the hub lists everything enrolled with it " +
                "and approves or revokes. Dedicating a device to one kid is " +
                "config, so it belongs on both.",
            controls = listOf(
                SettingsControl(
                    "devices-kid", "This device is for",
                    sub = "One kid, or anyone — which decides whether the picker shows.",
                    kind = ControlKind.CUSTOM, writes = "deviceProfiles"
                )
            )),
        SettingsSection("stats", "Stats", Page.DEVICES, "StatsSection",
            emptyList(), Where.BOTH, false,
            "What a kid actually watched on one device, reached from its row on " +
                "the Devices page. Reads rather than writes, and the hub cannot " +
                "show it yet: the numbers live on each device and arrive over " +
                "GET /stats, which the hub never calls because it never " +
                "initiates. Worth having — an always-on box is the natural " +
                "place to collect them. Left false deliberately: the ledger " +
                "that would carry the numbers is PLAN-hub-parity steps 5-7, " +
                "which the owner has tabled."),
        SettingsSection("search-index", "Search index", Page.DEVICES, "SearchIndexSection",
            emptyList(), Where.BOTH, true,
            "Progress of the crawl that makes search work, and which peer is " +
                "running it. The hub is the natural builder, always on, on the " +
                "NAS, so its Devices page shows what it has crawled and whether " +
                "a device is pulling; the phone shows the same and takes over " +
                "when the hub is off for a day (MasterElection). Read-only on " +
                "both faces: the master is elected, not chosen."),
        SettingsSection("downloads", "Downloads", Page.DEVICES, "DownloadsSection",
            emptyList(), Where.PHONE, false,
            "The platform download manager and this device's storage."),
        SettingsSection("local-videos", "Local videos", Page.DEVICES, "LocalVideosSection",
            emptyList(), Where.PHONE, false,
            "Files a parent sideloaded onto this device."),
        SettingsSection("hub-join", "Hub", Page.BACKUP, "HubSection",
            emptyList(), Where.PHONE, false,
            "How a phone joins a hub, and introduces its TVs. Its card is on " +
                "App, hub & backup, and again behind the hub's row on Devices. " +
                "Meaningless on the hub itself."),

        // --- Playback -------------------------------------------------------
        SettingsSection("playback", "Playback", Page.PLAYBACK, "",
            listOf("sponsorSkip", "autoplayNext", "suggestSimilar"), Where.BOTH, true,
            "Skip sponsors, autoplay the next video, suggest similar. All " +
                "config, all inline on the phone with no composable.",
            controls = listOf(
                SettingsControl(
                    "playback-sponsor-skip", "Skip sponsors & intros",
                    sub = "Using SponsorBlock community markers",
                    kind = ControlKind.TOGGLE, writes = "sponsorSkip"
                ),
                SettingsControl(
                    "playback-autoplay", "Autoplay the next video",
                    sub = "Behind a short countdown",
                    kind = ControlKind.TOGGLE, writes = "autoplayNext", json = "autoplay"
                ),
                SettingsControl(
                    "playback-suggest", "More like what you watch",
                    sub = "A home row of older videos",
                    kind = ControlKind.TOGGLE, writes = "suggestSimilar", json = "suggest"
                )
            )),
        SettingsSection("quality", "Video quality", Page.PLAYBACK, "",
            listOf("qualityTv", "qualityPhone"), Where.BOTH, true,
            "Set per form factor, so a parent on either face is choosing for " +
                "the TVs and the phones separately.",
            controls = listOf(
                SettingsControl(
                    "quality-tv", "On TVs",
                    kind = ControlKind.CHIPS, writes = "qualityTv",
                    options = PLAYBACK_QUALITIES.map { ControlOption(it, qualityLabel(it)) }
                ),
                SettingsControl(
                    "quality-phone", "On phones & tablets",
                    kind = ControlKind.CHIPS, writes = "qualityPhone",
                    options = PLAYBACK_QUALITIES.map { ControlOption(it, qualityLabel(it)) }
                )
            )),
        SettingsSection("listening", "Listening", Page.PLAYBACK, "",
            listOf("listenPercent"), Where.BOTH, true,
            "Whether playback continues with the screen off. It only affects " +
                "phones, but it is family config rather than device state, so " +
                "the hub can set it and phones honour it.",
            controls = listOf(
                SettingsControl(
                    "listening-rate", "Keep playing when the phone locks",
                    sub = "Phones only. The rate is how fast those minutes count.",
                    kind = ControlKind.CHIPS, writes = "listenPercent", json = "listen",
                    // Off is null, and FREE is 0 — two different things that a
                    // hand-written hub card had collapsed into one "Off".
                    options = listOf(
                        ControlOption(null, "Off"),
                        ControlOption(100, "1x"),
                        ControlOption(75, "0.75x"),
                        ControlOption(50, "0.5x"),
                        ControlOption(25, "0.25x"),
                        ControlOption(0, "FREE")
                    )
                )
            )),

        // --- Backup & app ---------------------------------------------------
        SettingsSection("export", "Import, export & backup", Page.BACKUP, "ExportSection",
            emptyList(), Where.BOTH, true,
            "Reads config rather than writing it. Both faces write the same " +
                "envelope (BackupFile) and read each other's, which is the " +
                "point: the day a backup is wanted is the day the box that " +
                "made it is gone. The hub adds the five-slot version ring a " +
                "file picker cannot give you, and on either face a restore is " +
                "a stamped edit rather than a byte copy."),
        SettingsSection("app-update", "App", Page.BACKUP, "UpdateSection",
            emptyList(), Where.PHONE, false,
            "The app self-updates from a release APK; the hub updates by " +
                "rebuilding its image. Same word, different page."),
        SettingsSection("hub-health", "This hub", Page.BACKUP, "",
            emptyList(), Where.HUB, true,
            "Uptime, data directory, enrolled device count, and which settings " +
                "are still only on the phone. No phone equivalent and none wanted.")
    )

    /**
     * Config leaves that no control sets, and why.
     *
     * Guard 26(a) requires every property of [Whitelist], [Limits] and
     * [AiConfig] to be claimed by exactly one control or named here. A field
     * with nothing to set it is either an omission (the common case, and the
     * one this catches) or a deliberate exception — and an exception with no
     * reason beside it is indistinguishable from the omission.
     */
    val NOT_A_CONTROL: Map<String, String> = mapOf(
        "limits" to "a container; each of its leaves is claimed on its own below",
        "ai" to "a container; each of its leaves is claimed on its own below",
        "sync" to "the merge's own bookkeeping. Only ConfigStamp writes it, and a " +
            "control that could would break causality for the whole household",
        "masterDeviceToken" to "elected between peers by MasterElection, never chosen " +
            "by a parent. Both faces show who holds it and neither offers to set it",
        "ai.rulesVersion" to "bumped by SettingsForm.toConfig when the rules, the age, " +
            "the model or the endpoint change, so every device re-screens. A parent " +
            "setting it by hand would silently un-screen a catalogue"
    )

    /** [Where.BOTH] groups still to be built on the hub. */
    fun outstandingOnHub(): List<SettingsSection> =
        sections.filter { it.where == Where.BOTH && !it.hubReady }

    /** Every id the hub's page registry is allowed to use. */
    fun hubIds(): Set<String> =
        sections.filter { it.where != Where.PHONE }.map { it.id }.toSet()

    /** Groups the hub serves, in the phone's own page order. */
    fun forHub(): List<SettingsSection> =
        sections.filter { it.where != Where.PHONE && it.hubReady }

    /** Every config field this manifest claims to cover. */
    fun coveredFields(): Set<String> = sections.flatMap { it.fields }.toSet()

    /** Every control, in page order. */
    val controls: List<SettingsControl> = sections.flatMap { it.controls }

    private val byId: Map<String, SettingsControl> = controls.associateBy { it.id }

    /**
     * The words for one control. Throws on an unknown id rather than
     * rendering a blank label: a typo would otherwise be a control with no
     * name, which reads as a layout bug and is found by a parent. Guard 26(c)
     * checks the ids the phone asks for against this list, so the throw is a
     * backstop for a build that skipped the gate, not the first line of
     * defence.
     */
    fun control(id: String): SettingsControl =
        byId[id] ?: error("no settings control called \"$id\" — see SettingsSurface")

    /** The controls of one group, or nothing if the group has none declared. */
    fun controlsFor(groupId: String): List<SettingsControl> =
        sections.firstOrNull { it.id == groupId }?.controls.orEmpty()

    /**
     * What the hub is expected to render: every control that is not
     * phone-only, in a group the hub is ready for. This is exactly the list
     * `/api/state` ships, so the browser cannot be offered a control the
     * guard is not checking, nor left without one it is.
     */
    fun hubControls(): List<SettingsControl> =
        forHub().flatMap { section -> section.controls.filter { it.where != Where.PHONE } }
}
