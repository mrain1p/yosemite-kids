package io.yosemitekids.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading and writing config.json, and fingerprinting it.
 *
 * Pure: no Context, no disk, no clock. It lives in :core so the phone, the TV
 * and the Docker hub serialize and hash a config identically — two
 * implementations of this would drift, and the symptom would be two devices
 * that hold the same settings computing different hashes and pushing at each
 * other forever.
 *
 * [ConfigStore] is the Android side: the file, the Keystore, the locking.
 */
object ConfigJson {
        /**
         * Short content hash of what actually matters (channels, blocks, rules) —
         * two devices with equal fingerprints are provably in sync.
         */
        /**
         * [includeSecrets] false substitutes an empty API key, giving the
         * fingerprint a peer that deliberately holds no secrets would compute.
         *
         * It defaults to true and must stay that way. Three call sites are
         * load-bearing on the full form: the settings autosave fires on a
         * fingerprint change, so a key-only edit that stopped moving it would
         * never be saved at all; /status advertises the full form; and a
         * migrated kid mints their profile id from it.
         */
        fun fingerprint(w: Whitelist, includeSecrets: Boolean = true): String {
            val canonical = buildString {
                w.sources.forEach {
                    append(it.id); append('|'); append(it.kind.name); append('|')
                    append(it.label ?: "")
                    // Appended only when non-default, so configs that never use
                    // multipliers keep the same hash as pre-multiplier builds.
                    if (it.timeMultiplierPercent != 100) {
                        append("|t"); append(it.timeMultiplierPercent)
                    }
                    // Same append-only rule: all-kids entries keep their old hash.
                    if (it.profileIds.isNotEmpty()) {
                        append("|v"); append(it.profileIds.sorted().joinToString(","))
                    }
                    // Note text is part of the fingerprint (an edit must push),
                    // newline-flattened so it can't fake an entry boundary.
                    if (!it.aiNote.isNullOrBlank()) {
                        append("|n"); append(it.aiNote.trim().replace('\n', ' '))
                    }
                    // Picked playlists, append-only like the rest: a pick must
                    // push (the rows are what the kid sees), an empty pick
                    // keeps the entry's old hash.
                    if (it.playlistIds.isNotEmpty()) {
                        append("|p"); append(it.playlistIds.joinToString(","))
                    }
                    append('\n')
                }
                append("B:"); append(w.blockedVideoIds.sorted().joinToString(","))
                append(";L:")
                append(limitsCanon(w.limits))
                // Appended only when set, so untouched configs keep their hash
                // across builds. Must be in the hash: the offline reconcile
                // (syncConfigState) only re-pushes on a fingerprint mismatch —
                // a pause that didn't change the hash would never reach a
                // device that slept through the original push.
                w.limits.pausedUntilMillis?.let { append(";P:"); append(it) }
                append(";AI:")
                // Escaped, unlike every other field joined on this delimiter.
                // These are parent-typed free text and were never scrubbed of
                // "|" the way TimeWindow.label is scrubbed of "[,;]" below, so
                // a delimiter inside a model name could already shift across a
                // field boundary. Blanking the key widens that: it leaves an
                // empty slot for a stray "|" to migrate across, which would let
                // a peer holding a different model AND different rules read as
                // in sync. Escaping is injective, and leaves the overwhelmingly
                // commoner case — no delimiter in the text — byte-identical to
                // older builds, so no device is forced out of sync by this.
                fun esc(v: Any?): String = v.toString()
                    .replace("\\", "\\\\")
                    .replace("|", "\\|")
                append(
                    listOf(
                        w.ai.enabled, w.ai.baseUrl, w.ai.model,
                        if (includeSecrets) w.ai.apiKey else "",
                        w.ai.rules, w.ai.childAge, w.ai.rulesVersion
                    ).joinToString("|") { esc(it) }
                )
                append(";AA:"); append(w.aiAllowedVideoIds.sorted().joinToString(","))
                // Everything profile-shaped is append-only-when-present, so a
                // family that never adds a second kid keeps its pre-profile hash.
                if (w.profiles.isNotEmpty()) {
                    append(";PR:")
                    w.profiles.forEach { p ->
                        append(
                            listOf(
                                p.id, p.name, p.colorArgb, p.avatar, p.age ?: -1,
                                p.pin ?: "",
                                limitsCanon(p.limits)
                            ).joinToString("|")
                        )
                        // A kid's own pause, append-only-when-set like the
                        // family one above: it has to move the hash or the
                        // offline reconcile would never carry it to the TV.
                        p.limits.pausedUntilMillis?.let { append(";P:"); append(it) }
                        // The look's timestamp rides the same way: a kid's
                        // restyle on the TV changes avatar/colour (already in
                        // the hash) *and* this, so both sides settle on one hash
                        // only once the phone has adopted the newer choice.
                        if (p.lookAt != 0L) { append(";LA:"); append(p.lookAt) }
                        append('\n')
                    }
                }
                fun appendOverlay(tag: String, overlay: Map<String, Set<String>>) {
                    if (overlay.isEmpty()) return
                    append(";$tag:")
                    overlay.entries.sortedBy { it.key }.forEach { (id, pids) ->
                        append(id); append('='); append(pids.sorted().joinToString(",")); append(';')
                    }
                }
                appendOverlay("BF", w.blockedFor)
                appendOverlay("AF", w.allowedFor)
                if (w.deviceProfiles.isNotEmpty()) {
                    append(";DP:")
                    w.deviceProfiles.entries.sortedBy { it.key }
                        .forEach { (t, p) -> append("$t=$p;") }
                }
                // Append-only-when-set, same reasoning as pauses: the offline
                // reconcile only re-pushes on a mismatch, so a master change
                // that didn't move the hash would never reach a sleeping TV.
                w.masterDeviceToken?.let { append(";MS:"); append(it) }
                // Appended only when switched off, so every existing config
                // keeps its hash across the build that introduced the flag.
                if (!w.sponsorSkip) append(";SB:off")
                if (!w.autoplayNext) append(";AP:off")
                if (!w.suggestSimilar) append(";SG:off")
                if (w.channelLayout != CHANNEL_LAYOUT_NEWEST) { append(";CL:"); append(w.channelLayout) }
                if (w.channelOrder != CHANNEL_ORDER_WATCHED) { append(";CO:"); append(w.channelOrder) }
                // Append-only-when-set, like every field added since: a
                // family that never touches these keeps its old hash.
                w.qualityTv?.let { append(";QT:"); append(it) }
                w.qualityPhone?.let { append(";QP:"); append(it) }
                w.pageSize?.let { append(";PS:"); append(it) }
                if (w.showVideoAge) append(";VA:1")
                // Append-only-when-set, same reasoning — and it must be in the
                // hash so the offline reconcile re-pushes a rate change.
                w.listenPercent?.let { append(";LN:"); append(it) }
                // Append-only-when-set, at the tail like every field since.
                // It has to move the hash: the offline reconcile only
                // re-pushes on a mismatch, and a grant that didn't would never
                // reach the TV that slept through the tap — which is the whole
                // reason grants moved into the config. Sorted by id so two
                // phones holding the same taps hash alike; `at` is display
                // only and left out.
                if (w.grants.isNotEmpty()) {
                    append(";G:")
                    w.grants.sortedBy { it.id }.forEach { g ->
                        append(g.id); append(','); append(g.kidId ?: ""); append(',')
                        append(g.date); append(','); append(g.minutes); append(';')
                    }
                }
            }
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray())
                .take(4)
                .joinToString("") { "%02x".format(it) }
        }

        /**
         * Drops the API key from a config payload, leaving every other field —
         * including ones this build doesn't know about — byte-for-byte alone.
         * Returns the input unchanged if it isn't parseable.
         */
        fun stripSecrets(json: String): String = runCatching {
            val root = JSONObject(json)
            val ai = root.optJSONObject("ai") ?: return@runCatching json
            if (!ai.has("apiKey")) return@runCatching json
            ai.remove("apiKey")
            root.toString(2)
        }.getOrDefault(json)

        /**
         * [includeSecrets] is true for anything that goes to another device (a
         * paired TV needs the key to screen), false for the copy that lands on
         * disk, which is cloud-backed-up.
         */
        fun toJson(w: Whitelist, includeSecrets: Boolean = true): String {
            val root = JSONObject()
            root.put("updatedAt", System.currentTimeMillis())
            root.put("entries", JSONArray().apply {
                w.sources.forEach { e ->
                    put(JSONObject().apply {
                        put("id", e.id)
                        put("url", e.url)
                        e.label?.let { put("label", it) }
                        put("kind", e.kind.name)
                        // Omitted at 100 — old builds ignore it, new builds default it.
                        if (e.timeMultiplierPercent != 100) put("time", e.timeMultiplierPercent)
                        // Omitted when visible to everyone, same reasoning.
                        if (e.profileIds.isNotEmpty()) put("profiles", JSONArray(e.profileIds.toList()))
                        if (!e.aiNote.isNullOrBlank()) put("note", e.aiNote.trim())
                        if (e.playlistIds.isNotEmpty()) put("playlists", JSONArray(e.playlistIds))
                    })
                }
            })
            root.put("blocked", JSONArray(w.blockedVideoIds.toList()))
            root.put("limits", limitsToJson(w.limits))
            root.put("ai", JSONObject().apply {
                put("enabled", w.ai.enabled)
                put("baseUrl", w.ai.baseUrl)
                put("model", w.ai.model)
                if (includeSecrets) put("apiKey", w.ai.apiKey)
                put("rules", w.ai.rules)
                w.ai.childAge?.let { put("childAge", it) }
                put("rulesVersion", w.ai.rulesVersion)
            })
            root.put("aiAllowed", JSONArray(w.aiAllowedVideoIds.toList()))
            // Profile fields are written only when used, so a single-kid family's
            // config stays byte-compatible with what older builds understand.
            if (w.profiles.isNotEmpty()) {
                root.put("profiles", JSONArray().apply {
                    w.profiles.forEach { p ->
                        put(JSONObject().apply {
                            put("id", p.id)
                            put("name", p.name)
                            put("color", p.colorArgb)
                            put("avatar", p.avatar)
                            p.age?.let { put("age", it) }
                            p.pin?.let { put("pin", it) }
                            if (p.lookAt != 0L) put("lookAt", p.lookAt)
                            put("limits", limitsToJson(p.limits))
                        })
                    }
                })
            }
            fun overlayJson(overlay: Map<String, Set<String>>) = JSONObject().apply {
                overlay.forEach { (id, pids) -> put(id, JSONArray(pids.toList())) }
            }
            if (w.blockedFor.isNotEmpty()) root.put("blockedFor", overlayJson(w.blockedFor))
            if (w.allowedFor.isNotEmpty()) root.put("allowedFor", overlayJson(w.allowedFor))
            if (w.deviceProfiles.isNotEmpty()) {
                root.put("deviceProfiles", JSONObject(w.deviceProfiles as Map<String, String>))
            }
            // Written only when chosen — absent means unclaimed (older builds).
            w.masterDeviceToken?.let { root.put("master", it) }
            // Written only when off — absent means on, including in configs
            // saved by builds that predate the flag.
            if (!w.sponsorSkip) root.put("sponsorSkip", false)
            if (!w.autoplayNext) root.put("autoplay", false)
            if (!w.suggestSimilar) root.put("suggest", false)
            if (w.channelLayout != CHANNEL_LAYOUT_NEWEST) root.put("channelLayout", w.channelLayout)
            if (w.channelOrder != CHANNEL_ORDER_WATCHED) root.put("channelOrder", w.channelOrder)
            w.qualityTv?.let { root.put("qualityTv", it) }
            w.qualityPhone?.let { root.put("qualityPhone", it) }
            w.pageSize?.let { root.put("pageSize", it) }
            if (w.showVideoAge) root.put("showVideoAge", true)
            // Written only when set — absent means listening off (see Whitelist).
            w.listenPercent?.let { root.put("listen", it) }
            // Only when a parent has added time: a family that never does keeps
            // its bytes. An older build parses past it, and its merge rebuilds
            // from its own root, so the key does not survive a hop through one
            // — the LAN grant is that build's path (see LanServer /grant).
            if (w.grants.isNotEmpty()) root.put("grants", JSONArray(grantsToJson(w.grants)))
            // Last, and only when there is anything to say: a family that has
            // never edited since upgrading writes a byte-identical file, so no
            // fingerprint moves and no fleet-wide re-push fires at upgrade.
            if (!w.sync.isEmpty) root.put("sync", ConfigMerge.syncToJson(w.sync))
            return root.toString(2)
        }

        /**
         * Limits in the fingerprint's canonical form. The legacy trailing
         * `start,end` pair is kept for the one shape older builds could
         * express — a single every-day window — so a family whose bedtime was
         * migrated into the list keeps its existing hash and doesn't trigger a
         * pointless re-push. Richer schedules append instead, which is what
         * changes the hash for them exactly once, at the upgrade.
         *
         * Passes belong in here for the same reason pauses do: the offline
         * reconcile only re-pushes on a mismatch, so a pass that didn't move
         * the hash would never reach a device that slept through the push.
         */
        private fun limitsCanon(l: Limits): String {
            // "Allow listening" is outside what the legacy pair can say, so a
            // window carrying it takes the long form — otherwise ticking the
            // box on an ordinary every-day bedtime wouldn't move the hash and
            // would never reach the kid's phone.
            val legacy = l.windows.singleOrNull()
                ?.takeIf { it.days == ALL_DAYS && it.passUntilMillis == null && !it.allowListening }
            val base = listOf(
                l.sessionMinutes, l.weekdaySessions, l.weekendSessions, l.breakMinutes,
                legacy?.startMin, legacy?.endMin
            ).joinToString(",")
            // Appended only when set (same reason as pauses: the offline
            // reconcile only re-pushes on a mismatch, so a break skip that
            // didn't move the hash would never reach a sleeping device) — and
            // here rather than at the Whitelist level, so a per-kid skip moves
            // that kid's part of the hash too.
            val breakPass = l.breakPassUntilMillis?.let { ";BP:$it" } ?: ""
            // Same shape as the break pass: only present when set, so families
            // without the rule keep their hash across the build that added it.
            val minVideo = l.minVideoMinutes?.let { ";MV:$it" } ?: ""
            if (legacy != null || l.windows.isEmpty()) return base + breakPass + minVideo
            return base + l.windows.joinToString(";", prefix = ";W:") { w ->
                // Parent-typed text is scrubbed of this format's separators so
                // two different window lists can't canonicalize identically.
                "${w.id},${w.label.replace(Regex("[,;]"), " ")},${w.startMin},${w.endMin}," +
                    "${w.days.sorted().joinToString(".")},${w.passUntilMillis ?: 0}," +
                    if (w.allowListening) "1" else "0"
            } + breakPass + minVideo
        }

        private fun limitsToJson(l: Limits) = JSONObject().apply {
            l.sessionMinutes?.let { put("session", it) }
            l.weekdaySessions?.let { put("weekdaySessions", it) }
            l.weekendSessions?.let { put("weekendSessions", it) }
            l.breakMinutes?.let { put("breakMinutes", it) }
            l.minVideoMinutes?.let { put("minVideoMinutes", it) }
            if (l.windows.isNotEmpty()) put("windows", JSONArray(windowsToJson(l.windows)))
            // A single every-day window is exactly what pre-windows builds called
            // bedtime, so keep writing their keys for it: a family whose phone
            // updates before its TV keeps an enforced bedtime in the meantime.
            // Anything richer has no legacy equivalent and is left out rather
            // than flattened into a wrong one. "Allow listening" is the one
            // thing still written flat: a build that can't express it enforces
            // the window outright, which errs toward the stricter rule and is
            // what a TV does anyway. While a pass is active the keys
            // are omitted too — absent bedtime IS the pass, as far as an old
            // build can express it; they come back via the fingerprint change
            // when the lapsed pass is scrubbed on load.
            l.windows.singleOrNull()?.takeIf { it.days == ALL_DAYS && it.passUntilMillis == null }?.let {
                put("bedtimeStart", it.startMin)
                put("bedtimeEnd", it.endMin)
            }
            l.pausedUntilMillis?.let { put("pausedUntil", it) }
            l.breakPassUntilMillis?.let { put("breakPassUntil", it) }
        }

        /** Windows as a JSON array string — also the SharedPreferences form. */
        fun windowsToJson(windows: List<TimeWindow>): String =
            JSONArray().apply {
                windows.forEach { w ->
                    put(JSONObject().apply {
                        put("id", w.id)
                        put("label", w.label)
                        put("start", w.startMin)
                        put("end", w.endMin)
                        // Omitted when it applies every day, the common case.
                        if (w.days != ALL_DAYS) put("days", JSONArray(w.days.sorted()))
                        w.passUntilMillis?.let { put("passUntil", it) }
                        // Written only when set: absent means the plain block,
                        // which is what every pre-listening config meant.
                        if (w.allowListening) put("allowListening", true)
                    })
                }
            }.toString()

        fun windowsFromJson(text: String?): List<TimeWindow> = runCatching {
            val arr = JSONArray(text ?: return emptyList())
            (0 until arr.length()).mapNotNull { i ->
                // Per-window, so one malformed window (a newer build's shape, a
                // hand edit) drops alone instead of disabling every window this
                // list guards — bedtime included. Out-of-range minutes or days
                // would misfire in the week-minute math (negative modulo reads
                // as "always blocking"), so they drop too rather than enforce
                // something nobody configured.
                runCatching {
                    val w = arr.getJSONObject(i)
                    val start = w.getInt("start")
                    val end = w.getInt("end")
                    if (start !in 0..1439 || end !in 0..1439) return@runCatching null
                    val days = w.optJSONArray("days")?.let { d ->
                        (0 until d.length()).map { d.getInt(it) }
                            .filter { it in 1..7 } // Calendar.SUNDAY..SATURDAY
                            .toSet()
                    } ?: ALL_DAYS
                    // Present-but-empty days: block nothing, never inflate an
                    // ambiguous rule to every day.
                    if (days.isEmpty()) return@runCatching null
                    TimeWindow(
                        id = w.optString("id").ifEmpty { "w$i" },
                        label = w.optString("label", "Quiet time"),
                        startMin = start,
                        endMin = end,
                        days = days,
                        passUntilMillis = if (w.has("passUntil")) w.getLong("passUntil") else null,
                        allowListening = w.optBoolean("allowListening", false)
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())

        /**
         * Grants as a JSON array string — the config's `grants` key, and also
         * the SharedPreferences form SessionGuard keeps of the ones it has
         * taken, exactly as windows are shared between the two.
         */
        fun grantsToJson(grants: List<Grant>): String =
            JSONArray().apply {
                grants.forEach { g ->
                    put(JSONObject().apply {
                        put("id", g.id)
                        // Omitted for everyone: absent is the family-wide case.
                        g.kidId?.let { put("kid", it) }
                        put("date", g.date)
                        put("minutes", g.minutes)
                        put("at", g.at)
                    })
                }
            }.toString()

        /**
         * Per-grant, like windows: one malformed entry (a newer build's shape)
         * drops alone rather than costing the family the whole config. The id
         * and the date are checked against the shapes the app mints, because
         * both become delimiters downstream — the id in the `grant|<id>` merge
         * key and the fingerprint, the date in the fingerprint — and a stray
         * `|` or `;` there would let two different documents hash alike.
         */
        fun grantsFromJson(text: String?): List<Grant> = runCatching {
            val arr = JSONArray(text ?: return emptyList())
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val g = arr.getJSONObject(i)
                    val id = g.getString("id")
                    val date = g.getString("date")
                    val minutes = g.getInt("minutes")
                    if (!GRANT_ID.matches(id) || !GRANT_DATE.matches(date) || minutes <= 0) {
                        return@runCatching null
                    }
                    Grant(
                        id = id,
                        kidId = g.optString("kid").ifEmpty { null },
                        date = date,
                        minutes = minutes,
                        at = g.optLong("at", 0L)
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())

        /** [Profile.newId]'s shape, which is what every tap mints and what `POST /grant` accepts. */
        private val GRANT_ID = Regex("[0-9a-f]{8}")
        private val GRANT_DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

        private fun limitsFromJson(lo: JSONObject): Limits {
            fun opt(name: String): Int? = if (lo.has(name)) lo.getInt(name) else null
            // A config written before windows existed carries only the bedtime
            // pair; migrate it in place so an upgrade never silently drops a
            // family's bedtime.
            val windows = if (lo.has("windows")) {
                windowsFromJson(lo.optJSONArray("windows")?.toString())
            } else {
                val start = opt("bedtimeStart")
                val end = opt("bedtimeEnd")
                if (start != null && end != null) {
                    listOf(TimeWindow(id = "bedtime", label = "Bedtime", startMin = start, endMin = end))
                } else emptyList()
            }
            return Limits(
                sessionMinutes = opt("session"),
                weekdaySessions = opt("weekdaySessions"),
                weekendSessions = opt("weekendSessions"),
                breakMinutes = opt("breakMinutes"),
                minVideoMinutes = opt("minVideoMinutes"),
                windows = windows,
                pausedUntilMillis = if (lo.has("pausedUntil")) lo.getLong("pausedUntil") else null,
                breakPassUntilMillis = if (lo.has("breakPassUntil")) lo.getLong("breakPassUntil") else null
            )
        }

        fun fromJson(text: String): Whitelist {
            val root = JSONObject(text)
            val entries = mutableListOf<WhitelistEntry>()
            val arr = root.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = runCatching { SourceKind.valueOf(o.getString("kind")) }
                    .getOrDefault(SourceKind.CHANNEL)
                val pidArr = o.optJSONArray("profiles")
                entries += WhitelistEntry(
                    id = o.getString("id"),
                    url = o.getString("url"),
                    label = o.optString("label").ifEmpty { null },
                    kind = kind,
                    timeMultiplierPercent = o.optInt("time", 100),
                    profileIds = pidArr?.let { arrPids ->
                        (0 until arrPids.length()).map { arrPids.getString(it) }.toSet()
                    } ?: emptySet(),
                    aiNote = o.optString("note").ifEmpty { null },
                    playlistIds = o.optJSONArray("playlists")?.let { pl ->
                        (0 until pl.length()).map { pl.getString(it) }.filter { it.isNotBlank() }
                    } ?: emptyList()
                )
            }
            val blocked = mutableSetOf<String>()
            val blockedArr = root.optJSONArray("blocked") ?: JSONArray()
            for (i in 0 until blockedArr.length()) blocked += blockedArr.getString(i)

            val lo = root.optJSONObject("limits") ?: JSONObject()

            val ao = root.optJSONObject("ai") ?: JSONObject()
            val ai = AiConfig(
                enabled = ao.optBoolean("enabled", false),
                // Absent and blank are different answers. A config written before
                // the ai block existed has no key, and gets the default so the form
                // has a provider to show. A config that SAYS "" chose nothing — and
                // rewriting that to OpenRouter meant the settings autosave then
                // persisted a provider the parent never picked, the first time the
                // screening page was so much as opened.
                baseUrl = if (ao.has("baseUrl")) ao.optString("baseUrl") else AiConfig().baseUrl,
                model = ao.optString("model"),
                apiKey = ao.optString("apiKey"),
                rules = ao.optString("rules"),
                childAge = if (ao.has("childAge")) ao.getInt("childAge") else null,
                rulesVersion = ao.optInt("rulesVersion", 0)
            )
            val aiAllowed = mutableSetOf<String>()
            val aiAllowedArr = root.optJSONArray("aiAllowed") ?: JSONArray()
            for (i in 0 until aiAllowedArr.length()) aiAllowed += aiAllowedArr.getString(i)

            val profiles = mutableListOf<Profile>()
            root.optJSONArray("profiles")?.let { arr2 ->
                for (i in 0 until arr2.length()) {
                    val o = arr2.getJSONObject(i)
                    profiles += Profile(
                        id = o.getString("id"),
                        name = o.optString("name").ifEmpty { "Kid ${i + 1}" },
                        colorArgb = o.optLong("color", PROFILE_COLORS.first()),
                        avatar = o.optString("avatar").ifEmpty { PROFILE_AVATARS.first() },
                        age = if (o.has("age")) o.getInt("age") else null,
                        limits = limitsFromJson(o.optJSONObject("limits") ?: JSONObject()),
                        pin = o.optString("pin").takeIf { isValidDirectionPin(it) },
                        lookAt = o.optLong("lookAt", 0L)
                    )
                }
            }
            fun overlay(name: String): Map<String, Set<String>> {
                val o = root.optJSONObject(name) ?: return emptyMap()
                return o.keys().asSequence().associateWith { id ->
                    val a = o.optJSONArray(id) ?: JSONArray()
                    (0 until a.length()).map { a.getString(it) }.toSet()
                }
            }
            val deviceProfiles = root.optJSONObject("deviceProfiles")?.let { o ->
                o.keys().asSequence().associateWith { o.getString(it) }
            } ?: emptyMap()

            return Whitelist(
                sources = entries.distinctBy { it.id },
                blockedVideoIds = blocked,
                limits = limitsFromJson(lo),
                ai = ai,
                aiAllowedVideoIds = aiAllowed,
                profiles = profiles,
                blockedFor = overlay("blockedFor"),
                allowedFor = overlay("allowedFor"),
                deviceProfiles = deviceProfiles,
                masterDeviceToken = root.optString("master").ifEmpty { null },
                sponsorSkip = root.optBoolean("sponsorSkip", true),
                autoplayNext = root.optBoolean("autoplay", true),
                suggestSimilar = root.optBoolean("suggest", true),
                channelLayout = root.optString("channelLayout").takeIf { it in CHANNEL_LAYOUTS }
                    ?: CHANNEL_LAYOUT_NEWEST,
                channelOrder = root.optString("channelOrder").takeIf { it in CHANNEL_ORDERS }
                    ?: CHANNEL_ORDER_WATCHED,
                listenPercent = if (root.has("listen")) root.getInt("listen") else null,
                qualityTv = root.optInt("qualityTv", 0).takeIf { it in PLAYBACK_QUALITIES },
                qualityPhone = root.optInt("qualityPhone", 0).takeIf { it in PLAYBACK_QUALITIES },
                pageSize = root.optInt("pageSize", 0).takeIf { it in PAGE_SIZES },
                showVideoAge = root.optBoolean("showVideoAge", false),
                // Per-entry lenient, like the sync blob below: a grant a build
                // cannot read costs the kid those minutes, never the config.
                grants = grantsFromJson(root.optJSONArray("grants")?.toString()),
                // Outside the throwing path on purpose: a malformed or
                // future-versioned blob must cost the family its bookkeeping,
                // never its channels. See ConfigMerge.syncFromJson.
                sync = runCatching { ConfigMerge.syncFromJson(root) }.getOrDefault(SyncMeta.EMPTY)
            )
        }
}
