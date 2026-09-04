package io.pickwick.app.data

/**
 * Every settings section, and which faces it belongs on.
 *
 * The hub's GUI is meant to mirror the phone's Settings. Two UIs meant to
 * mirror each other drift the moment one of them gains a feature, silently:
 * nothing about a Compose screen tells you a browser page across the repo was
 * supposed to grow the same control, and the drift is found by a parent who
 * cannot do on the NAS the thing they just did on their phone.
 *
 * So this is not documentation. `scripts/check.ps1` and `scripts/check.sh`
 * read it and fail the build in both directions — a phone section with no
 * entry here, a hub page with no entry here, an entry claiming to be built on
 * the hub that is not. Editing this file is therefore the moment the "does
 * this belong on the hub?" decision actually gets made, rather than something
 * that can be skipped.
 *
 * Divergence is expected and entirely allowed. What is not allowed is
 * divergence nobody stated.
 *
 * It lives in :core because both consumers must read the same list; in :app it
 * would be invisible to the hub, and in :hub invisible to the app.
 */
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
    /**
     * The `internal fun ...Section(` composable in :app, or "" for a hub-only
     * page. The guard matches on this, so it must be the real function name.
     */
    val composable: String,
    val where: Where,
    /**
     * False for a [Where.BOTH] section not yet built on the hub. The guard
     * skips it and the check prints what is outstanding, so the remaining work
     * is visible rather than quietly absent.
     */
    val hubReady: Boolean,
    /** Why it is placed this way. The part a future session cannot re-derive. */
    val why: String
)

object SettingsSurface {

    val sections: List<SettingsSection> = listOf(
        SettingsSection("channels", "Channels", "ChannelsSection", Where.BOTH, true,
            "The curation itself. The whole point of the app."),
        SettingsSection("screen-time-rules", "Screen time", "RulesSection", Where.BOTH, false,
            "Family policy, not device state."),
        SettingsSection("blocked-times", "Blocked times", "BlockedTimesSection", Where.BOTH, false,
            "Family policy, and merge-carried like the rest of limits."),
        SettingsSection("grant-time", "Grant extra time", "GrantTimeSection", Where.BOTH, false,
            "A parent away from home granting time is exactly what an always-on box is for."),
        SettingsSection("ai-screening", "AI screening", "AiScreeningSection", Where.BOTH, false,
            "Policy. Carried by the merge and identical on both faces."),
        SettingsSection("ai-review", "AI review", "AiReviewSection", Where.BOTH, false,
            "Reviewing verdicts needs no device-local state."),
        SettingsSection("ai-discovery", "Suggestions", "AiDiscoverySection", Where.BOTH, false,
            "Suggestions are config, and reviewing them on a big screen is easier."),
        SettingsSection("directory", "Directory", "DirectorySection", Where.BOTH, false,
            "Shared catalogue; nothing device-specific in it."),
        SettingsSection("export", "Backup", "ExportSection", Where.BOTH, false,
            "A backup taken from the always-on box is the more useful one."),
        SettingsSection("ai-connection", "AI connection", "AiConnectionSection", Where.BOTH, false,
            "Model and base URL yes; the API key no. The hub strips secrets before " +
                "writing and has no SecretStore, so a key entered there could not " +
                "survive a restart. The hub's page must say so rather than offer a " +
                "field that silently forgets."),
        SettingsSection("devices", "Devices", "PhoneDevicesSection", Where.BOTH, true,
            "Both list devices, but they are not the same page: the phone lists what " +
                "it paired, the hub lists everything enrolled with it and approves or " +
                "revokes — administration the phone has no equivalent of."),

        SettingsSection("downloads", "Downloads", "DownloadsSection", Where.PHONE, false,
            "The platform download manager. Nothing on the hub to point it at."),
        SettingsSection("local-videos", "Local videos", "LocalVideosSection", Where.PHONE, false,
            "Device storage."),
        SettingsSection("hub-join", "Hub", "HubSection", Where.PHONE, false,
            "How a phone joins a hub. Meaningless on the hub itself."),
        SettingsSection("app-update", "Update", "UpdateSection", Where.PHONE, false,
            "The app self-updates from a release APK; the hub updates by rebuilding " +
                "its image. Same word, different page."),

        SettingsSection("hub-health", "Hub status", "", Where.HUB, true,
            "Uptime, data directory, enrolled device count. No phone equivalent and " +
                "none wanted.")
    )

    /** [Where.BOTH] sections still to be built on the hub. */
    fun outstandingOnHub(): List<SettingsSection> =
        sections.filter { it.where == Where.BOTH && !it.hubReady }

    /** Every id the hub's page registry is allowed to use. */
    fun hubIds(): Set<String> =
        sections.filter { it.where != Where.PHONE }.map { it.id }.toSet()
}
