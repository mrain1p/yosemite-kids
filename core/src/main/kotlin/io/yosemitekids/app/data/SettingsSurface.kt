package io.yosemitekids.app.data

/**
 * Every settings group, which page it sits on, and which faces it belongs to.
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
 * **Keyed on [fields], not on composables.** The first version listed
 * `...Section(` functions, which misses two whole categories: the Playback page
 * has no section composable at all — it is inline cards — and the "Kid's
 * shelves" card on Channels is inline too. A guard that counts composables
 * cannot see either, so an entire page was invisible to it. Every field
 * `Settings.buildCurrentConfig` writes must appear here, which is a property no
 * amount of inlining can hide from.
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
    val why: String
)

object SettingsSurface {

    val sections: List<SettingsSection> = listOf(

        // --- Kids ---------------------------------------------------------
        SettingsSection("kids", "Kids", Page.KIDS, "KidsSection", listOf("profiles"),
            Where.BOTH, true,
            "Who the kids are: name, age, avatar, colour, profile lock. Pure config."),
        SettingsSection("screen-time-rules", "Screen time", Page.KIDS, "RulesSection",
            listOf("limits", "profiles"), Where.BOTH, true,
            "Family policy, not device state. Per kid and for everyone."),
        SettingsSection("blocked-times", "Blocked times", Page.KIDS, "BlockedTimesSection",
            listOf("limits", "profiles"), Where.BOTH, true,
            "Merge-carried like the rest of limits."),
        SettingsSection("grant-time", "Grant extra time", Page.KIDS, "GrantTimeSection",
            emptyList(), Where.PHONE, false,
            "Writes no config at all. A grant is device-local session state held " +
                "in SessionGuard's preferences and delivered by a LAN call, so " +
                "there is no config edit the hub could make to express one. " +
                "Giving the hub this needs an outbound grant call of its own."),

        // --- Channels & playlists -----------------------------------------
        SettingsSection("channels", "Channels & playlists", Page.CHANNELS, "ChannelsSection",
            listOf("sources"), Where.BOTH, true,
            "The curation itself. Includes each channel's time multiplier, its " +
                "screening note, and which kids can see it."),
        SettingsSection("kid-shelves", "How videos are listed", Page.LISTING, "",
            listOf("showVideoAge", "pageSize", "channelLayout", "channelOrder"),
            Where.BOTH, true,
            "How the kid's home is laid out. Inline on the phone with no " +
                "composable of its own, which is exactly why this manifest is " +
                "keyed on fields."),
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
                "rather than offering a field that silently forgets."),
        SettingsSection("ai-screening", "AI screening", Page.SCREENING, "AiScreeningSection",
            listOf("ai", "profiles"), Where.BOTH, true,
            "The rules text and the switch. Policy, carried by the merge."),
        SettingsSection("ai-review", "Waiting for your OK", Page.SCREENING, "AiReviewSection",
            listOf("blockedVideoIds", "blockedFor", "allowedFor", "aiAllowedVideoIds"),
            Where.PHONE, false,
            "The rulings are config, but the queue is not: it is built from " +
                "ScreeningStore verdicts and the video cache, neither of which " +
                "the hub holds. It would render an empty list until devices " +
                "push verdicts to it."),
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
                "config, so it belongs on both."),
        SettingsSection("stats", "Stats", Page.DEVICES, "StatsSection",
            emptyList(), Where.BOTH, false,
            "What a kid actually watched on one device, reached from its row on " +
                "the Devices page. Reads rather than writes, and the hub cannot " +
                "show it yet: the numbers live on each device and arrive over " +
                "GET /stats, which the hub never calls because it never " +
                "initiates. Worth having — an always-on box is the natural " +
                "place to collect them."),
        SettingsSection("search-index", "Search index", Page.DEVICES, "SearchIndexSection",
            emptyList(), Where.PHONE, false,
            "Progress of the crawl that makes search work, and which device is " +
                "running it. The hub builds no index and elects no master, so " +
                "it has nothing to show here."),
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
                "config, all inline on the phone with no composable."),
        SettingsSection("quality", "Video quality", Page.PLAYBACK, "",
            listOf("qualityTv", "qualityPhone"), Where.BOTH, true,
            "Set per form factor, so a parent on either face is choosing for " +
                "the TVs and the phones separately."),
        SettingsSection("listening", "Listening", Page.PLAYBACK, "",
            listOf("listenPercent"), Where.BOTH, true,
            "Whether playback continues with the screen off. It only affects " +
                "phones, but it is family config rather than device state, so " +
                "the hub can set it and phones honour it."),

        // --- Backup & app ---------------------------------------------------
        SettingsSection("export", "Import, export & backup", Page.BACKUP, "ExportSection",
            emptyList(), Where.BOTH, true,
            "Reads config rather than writing it. On the phone this is a file " +
                "picker; the hub's equivalent is its own versioned local " +
                "backup, which a file picker cannot give you."),
        SettingsSection("app-update", "App", Page.BACKUP, "UpdateSection",
            emptyList(), Where.PHONE, false,
            "The app self-updates from a release APK; the hub updates by " +
                "rebuilding its image. Same word, different page."),
        SettingsSection("hub-health", "This hub", Page.BACKUP, "",
            emptyList(), Where.HUB, true,
            "Uptime, data directory, enrolled device count, and which settings " +
                "are still only on the phone. No phone equivalent and none wanted.")
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
}
