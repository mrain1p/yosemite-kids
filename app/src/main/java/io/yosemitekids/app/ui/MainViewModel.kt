package io.yosemitekids.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.yosemitekids.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search hits screened per window — the bill tracks what's actually looked at,
 *  not the thousands a broad query can match against a full back catalog. */
private const val SEARCH_SCREEN_BATCH = 50

/** Home feed: this many newest per channel, interleaved, capped overall. */
private const val FEED_PER_CHANNEL = 12
private const val FEED_MAX = 80
/** History shelf: the most recent this many watches. */
private const val HISTORY_MAX = 120
/** The TV home row is a glance, not the shelf. */
private const val HISTORY_ROW_MAX = 12
/** "More like what you watch" is one row, not a second feed. */
private const val SUGGEST_ROW_MAX = 12
/** Playlists shown in a channel page row. */
private const val PLAYLIST_ROW_MAX = 30
/** Videos per row on the You tab and per parent-picked playlist row. */
private const val YOU_ROW_MAX = 12
/** Videos a You shelf carries (its row shows the first dozen; "See all" unfolds the rest in place). */
private const val YOU_PAGE_MAX = 60
/** Parent-picked playlist rows fetched per channel visit (each is one page request when uncached). */
private const val PLAYLIST_SHELVES_MAX = 3

class MainViewModel(
    private val whitelist: WhitelistRepository,
    private val history: WatchHistoryStore,
    private val sourceCache: SourceCache,
    private val videoCache: VideoCache,
    /** Per-channel playlist listings for the "By playlist" layout; null in tests. */
    private val playlistsCache: ChannelPlaylistsCache? = null,
    private val usage: UsageStore,
    private val sessionGuard: SessionGuard,
    private val watchlistStore: SavedListStore,
    private val watchLaterStore: SavedListStore,
    private val queueStore: QueueStore,
    private val pairingStore: PairingStore? = null,
    /** Phone role: lets the periodic sync re-push config a device missed while off. */
    private val configStore: ConfigStore? = null,
    /**
     * Where "your change lost" is recorded for this parent to find later.
     * Null on kid devices and in tests — a child must never be shown that
     * their parents disagreed about their rules.
     */
    private val syncNotices: SyncNotices? = null,
    /**
     * What to run when a config lands during this device's own sweep.
     *
     * The same lambda the LAN server is given for an inbound push, so both
     * arrival paths settle the kid's pending restyle and raise the "your
     * rules changed" pill. Hanging that off the inbound path alone was
     * harmless only while phones were the only devices that swept: a device
     * merging on its own never cleared the overlay, so its hash differed
     * from its peer's forever and it re-merged every five minutes.
     *
     * Null in tests, which have no Context to notify from.
     */
    private val onConfigApplied: ((Whitelist, Whitelist) -> Unit)? = null,
    /** This kid's chip choices (sort, filters); null in tests. */
    private val kidPrefs: KidPrefs? = null,
    /** Offline downloads (phones); null on TV and in tests. */
    private val downloadStore: DownloadStore? = null,
    /** Parent-sideloaded local files (phones); null on TV and in tests. */
    private val localLibrary: LocalLibrary? = null,
    /** True when there's no network at launch — lands the kid on Downloads. */
    private val isOffline: () -> Boolean = { false },
    /** Serializes this device's watch state for cross-device sync. */
    private val exportWatchState: () -> String = { "{}" },
    /** Merges a peer's watch state into this device. Returns true if applied. */
    private val mergeWatchState: (String) -> Boolean = { false },
    /** Serializes this device's AI verdicts for cross-device sharing. */
    private val exportVerdicts: () -> String = { "{}" },
    /** Imports a peer's AI verdicts. Returns true when any were new. */
    private val mergeVerdicts: (String) -> Boolean = { false },
    /**
     * Adopts a device's pending kid looks (`GET /looks`) into this phone's
     * config. Returns true when the config changed — the sweep then pushes it.
     */
    private val mergeLooks: (String) -> Boolean = { false },
    /**
     * Snapshots a device's stats into the phone-side cache. Riding the periodic
     * sync keeps the snapshot warm, so the parent's Stats page has yesterday's
     * picture even when the TV has been off all day.
     */
    private val cacheStats: suspend (PairedDevice) -> Unit = {},
    /**
     * Warms thumbnails into Coil's disk cache, one at a time, so the trickle
     * never competes with the images currently on screen.
     */
    private val prefetchThumbs: suspend (List<String>) -> Unit = {},
    /** AI screening gate; null in tests. Unscreened/blocked videos never render. */
    private val screener: io.yosemitekids.app.data.Screener? = null,
    /** Whose home screen this is; null = pre-profile single-kid behavior. */
    private val activeProfileId: String? = null,
    /** Search index; null in tests. The crawler runs only on the master device. */
    private val channelIndex: ChannelIndex? = null,
    /** The kid's recent searches (phones); null on TV and in tests. */
    private val searchHistory: SearchHistoryStore? = null,
    private val yt: YouTubeRepository = YouTubeRepository()
) : ViewModel() {

    private val crawler = channelIndex?.let { IndexCrawler(yt, it) }

    init {
        android.util.Log.i("YosemiteKids", "MainViewModel created for profile=$activeProfileId")
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var sources: List<Source> = emptyList()
    private var blockedVideoIds: Set<String> = emptySet()
    /** This kid's "hide videos shorter than" rule, in seconds; 0 = no rule. */
    private var minVideoSeconds: Long = 0

    /**
     * The length rule, checked wherever [blockedVideoIds] is: a too-short video
     * is hidden outright, not held for review, so it never counts as
     * "waiting on screening" either. Unknown durations (0) pass — a live
     * stream or a cache row from before durations were stored is not a clip.
     */
    private fun tooShort(v: Video): Boolean =
        minVideoSeconds > 0 && v.durationSeconds in 1 until minVideoSeconds
    /** Channels that exist in the family config but belong to other kids —
     *  used to keep their downloads off this kid's offline shelf. */
    private var hiddenChannelNames: Set<String> = emptySet()

    /** The unfiltered videos behind the current screen; progress/filtering applied on top. */
    private var rawVideos: List<Video> = emptyList()

    // Pagination state for the currently open source.
    private var feedHandle: YouTubeRepository.FeedHandle? = null
    private var uploadsNextPage: org.schabi.newpipe.extractor.Page? = null
    private var loadingMore = false

    /**
     * The offline shelf through this kid's eyes: YouTube downloads follow
     * their channel's per-kid switches, local folders their own visibility.
     */
    private fun visibleDownloads(): List<Video> =
        downloadStore?.downloadedVideos().orEmpty()
            .filter { it.channelName !in hiddenChannelNames } +
            localLibrary?.videos(activeProfileId).orEmpty()

    private fun visibleDownloadUrls(): Set<String> =
        visibleDownloads().map { it.url }.toSet()

    /** Set by the hosting composition; false while another kid's home is up. */
    @Volatile
    var uiActive = true

    /**
     * Phone-side config reconcile: an Allow/Block (or any settings edit) made
     * while a kid device was off must still land when it wakes — the original
     * push just failed silently. If a paired device answers with a *different,
     * older* config, push ours again; a device carrying a newer config (another
     * admin phone edited meanwhile) is left alone for a deliberate Push/Pull.
     */

    fun syncConfigState() {
        val store = configStore ?: return
        val pairing = pairingStore ?: return
        viewModelScope.launch {
            ConfigSync.reconcile(
                store, pairing, syncNotices, onConfigApplied, mergeLooks,
                onChanged = { refresh() },
                // Surfaced as the ring round the avatar. All of this used to
                // happen with nothing on screen to say so.
                onSweeping = { _state.value = _state.value.copy(syncing = it) },
                index = channelIndex
            )
        }
    }

    @Volatile
    private var watchSyncInFlight = false

    /**
     * Exchange watch progress + saved list + AI verdicts with every paired device
     * (phone acts as the hub): pull-merge theirs, push the merged state back.
     * LWW converges; verdicts are add-only per rules version.
     */
    fun syncWatchState() {
        val devices = pairingStore?.paired().orEmpty()
        if (devices.isEmpty() || watchSyncInFlight) return
        watchSyncInFlight = true
        viewModelScope.launch {
            try {
                var changed = false
                var newVerdicts = false
                devices.forEach { device ->
                    // Merge/export are prefs + JSON work — same off-main rule as
                    // the verdict pair below.
                    LanClient.fetchWatchState(device)?.let { json ->
                        if (withContext(Dispatchers.IO) { mergeWatchState(json) }) changed = true
                    }
                    LanClient.pushWatchState(device, withContext(Dispatchers.IO) { exportWatchState() })
                    // Verdict-sharing: pull-merge what each device has screened, so a
                    // video the TV already ruled on is never re-billed to the AI here.
                    LanClient.fetchVerdicts(device)?.let { json ->
                        val fresh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            mergeVerdicts(json)
                        }
                        if (fresh) newVerdicts = true
                    }
                    cacheStats(device)
                }
                // Push the merged set back after every pull, so verdicts also hop
                // between kid devices through this phone (they never talk directly).
                val export = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    exportVerdicts()
                }
                devices.forEach { LanClient.pushVerdicts(it, export) }
                if (changed) refreshProgress()
                // Imported verdicts can clear or hold videos on the current screen.
                if (newVerdicts) reapplyScreening()
            } finally {
                watchSyncInFlight = false
            }
        }
    }

    /**
     * Master-only: push index sources whose content hash differs from what each
     * paired device reports. Compared per source, so a one-channel delta ships
     * one small file, not the family's whole index.
     */
    @Volatile
    private var indexSyncInFlight = false

    fun syncIndex() {
        val index = channelIndex ?: return
        val devices = pairingStore?.paired().orEmpty()
        if (devices.isEmpty() || indexSyncInFlight) return
        indexSyncInFlight = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val me = pairingStore?.deviceToken() ?: return@launch
                val master = configStore?.load()?.masterDeviceToken
                // The legacy relay. From IndexPull.FIRST_PULLING_VERSION_CODE
                // a device pulls the index from the hub itself; older TVs
                // still receive it by push. While a hub holds the slot, this
                // phone relays what it pulled to those, and only to those.
                // Never to the hub: it takes nobody's copy (no POST /index).
                val isParent = pairingStore?.role() != io.yosemitekids.app.data.PairingStore.Role.KID
                val relayForHub = master != null &&
                    io.yosemitekids.app.data.MasterToken.isHub(master) && isParent
                if (master != me && !relayForHub) return@launch
                devices.filterNot { it.isHub }.forEach { device ->
                    if (relayForHub) {
                        val v = LanClient.fullStatus(device)?.versionCode ?: return@forEach
                        if (v >= io.yosemitekids.app.data.IndexPull.FIRST_PULLING_VERSION_CODE) return@forEach
                    }
                    val remoteStatus = LanClient.indexStatus(device) ?: return@forEach
                    val remote = runCatching { org.json.JSONObject(remoteStatus) }
                        .getOrNull() ?: return@forEach
                    index.allStates().forEach { (sourceId, state) ->
                        // contentHash is count:newest only, so a completeness
                        // flip with no new videos (exhaustion accepted after
                        // repeated probes, or a harvest un-completing) would
                        // never ship on the hash alone — compare the flag too.
                        // Deliberately not folded into the hash: the formula is
                        // wire format shared with old builds. A source the
                        // remote lacks entirely yields null for both reads and
                        // still pushes, as before.
                        val remoteEntry = remote.optJSONObject(sourceId)
                        if (remoteEntry?.optInt("hash") != state.contentHash() ||
                            remoteEntry?.optBoolean("complete") != state.complete
                        ) {
                            val body = index.exportSourceWithState(sourceId) ?: return@forEach
                            LanClient.pushIndexSource(device, sourceId, body)
                        }
                    }
                }
            } finally {
                indexSyncInFlight = false
            }
        }
    }

    /**
     * The channel row in the kid's order (their chip), else the parent's.
     * The shuffle seed holds for a visit: the rows are rebuilt on every
     * verdict, resume and back press, and a row that reorders under the
     * kid's thumb each time is a bug, not a surprise. It moves on a
     * pull-to-refresh and on a fresh press of the Random chip ("mix again").
     * Cache reads for "latest video" — call off-main.
     */
    private fun sortByUsage(channels: List<Source>) =
        orderChannels(
            channels, effectiveChannelSort(),
            opens = { usage.opens(it) },
            latestUpload = { id -> videoCache.load(id).take(10).mapNotNull { it.publishedAt }.maxOrNull() },
            seed = shuffleSeed
        )

    private fun effectiveChannelSort(): String = kidChannelSort ?: channelOrder

    private var shuffleSeed = kotlin.random.Random.nextLong()
    /** Seeds for the two video lists' Random chip; each moves when its list is reopened or re-mixed. */
    private var homeShuffleSeed = kotlin.random.Random.nextLong()
    private var channelShuffleSeed = kotlin.random.Random.nextLong()

    /** From the family config; most-watched until the first refresh reads it. */
    private var channelOrder: String = CHANNEL_ORDER_WATCHED
    /** The kid's own picks (KidPrefs), read once at init; null = the parent's default. */
    private var kidChannelSort: String? = null
    private var kidHomeFilter: String? = null
    private var kidChannelFilter: String? = null

    private fun effectiveHomeFilter(): String = kidHomeFilter ?: VIDEO_FILTER_NEW
    private fun effectiveChannelFilter(): String = kidChannelFilter ?: defaultFilterFor(channelLayout)

    /** The Channels tab / home row sort chip. Persisted per kid; a Random press reshuffles. */
    fun setChannelSort(sort: String) {
        if (sort == CHANNEL_ORDER_RANDOM) shuffleSeed = kotlin.random.Random.nextLong()
        kidChannelSort = sort
        viewModelScope.launch {
            withContext(Dispatchers.IO) { kidPrefs?.setChannelSort(sort) }
            _state.value = _state.value.copy(channelSort = sort)
            publishChannels(sources)
        }
    }

    /** The home feed's New / Random / Popular chip. */
    fun setHomeFilter(filter: String) {
        if (filter == VIDEO_FILTER_RANDOM) homeShuffleSeed = kotlin.random.Random.nextLong()
        kidHomeFilter = filter
        viewModelScope.launch {
            withContext(Dispatchers.IO) { kidPrefs?.setHomeFilter(filter) }
            val feed = withContext(Dispatchers.IO) { buildFeed(_state.value.channels) }
            _state.value = _state.value.copy(homeFilter = filter, feed = feed)
        }
    }

    /** A channel page's New / Random / Popular chip — one choice for every channel page. */
    fun setChannelFilter(filter: String) {
        if (filter == VIDEO_FILTER_RANDOM) channelShuffleSeed = kotlin.random.Random.nextLong()
        kidChannelFilter = filter
        viewModelScope.launch {
            withContext(Dispatchers.IO) { kidPrefs?.setChannelFilter(filter) }
            _state.value = _state.value.copy(channelFilter = filter, scrollTo = 0)
            (_state.value.screen as? Screen.ChannelVideos)?.let { publishChannel(it.source) }
        }
    }

    /**
     * Whitelist-scoped search over the local index. Visibility gates match the
     * feeds exactly: the kid's own sources only, parent blocks and the AI
     * screener applied on top — search can never surface what a feed wouldn't.
     */
    fun search(query: String) = viewModelScope.launch {
        val index = channelIndex ?: return@launch
        val q = query.trim()
        if (q.isEmpty()) return@launch
        searchHistory?.let { store ->
            val recent = withContext(Dispatchers.IO) { store.add(q); store.recent() }
            _state.value = _state.value.copy(recentSearches = recent)
        }
        _state.value = _state.value.copy(
            screen = Screen.SearchResults(q), loading = true, videos = emptyList(),
            held = 0, searchScreening = null, error = null
        )
        val visibleIds = withContext(Dispatchers.IO) {
            visibleSources(sources).map { it.id }.toSet()
        }
        val hits = withContext(Dispatchers.IO) { index.search(q, visibleIds) }
        val terms = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Dedup: the same video indexed under two sources is one result. The
        // index has no relevance ranking, so impose one: title hits above
        // channel-name-only hits (stable sort keeps newest-first within each
        // band) — the first screening window should be the results a kid
        // actually searched for, not whatever source iterated first.
        searchMatches = hits.map { it.toVideo() }.distinctBy { it.url }
            .filter { it.videoId !in blockedVideoIds && !tooShort(it) }
            .sortedBy { v -> if (terms.all { it in v.title.lowercase() }) 0 else 1 }
        searchWindow = 0
        searchSent = 0
        // Everything already cleared (or screening off) paints immediately —
        // the back catalog's unscreened bulk trickles in behind it.
        rawVideos = withContext(Dispatchers.IO) {
            searchMatches.filter { screener?.isVisible(it) != false }
        }
        _state.value = _state.value.copy(loading = false, videos = annotated(includeFinished = true))
        screenMoreSearch()
    }

    // ---- live screening of search hits ------------------------------------
    // The crawled index is never pre-screened (that would bill the AI for a
    // whole back catalog nobody may ever search), so search screens on demand:
    // a window of the best matches now, the next window when the kid scrolls.

    /** Relevance-ordered, deduped, unblocked matches for the current query. */
    private var searchMatches: List<Video> = emptyList()
    /** How much of [searchMatches] has been offered to the screener. */
    private var searchWindow = 0
    /** Matches actually sent to the AI this search (the progress bar's total). */
    private var searchSent = 0
    private var searchScreenMoreInFlight = false

    /** Called on search open and again when the kid nears the end of the grid. */
    fun screenMoreSearch() {
        val scr = screener
        if (_state.value.screen !is Screen.SearchResults) return
        if (scr == null || searchScreenMoreInFlight || searchWindow >= searchMatches.size) return
        searchScreenMoreInFlight = true
        viewModelScope.launch {
            try {
                val batch = withContext(Dispatchers.IO) {
                    // Extend the window until it covers the next batch of
                    // unscreened matches; already-verdicted ones pass through free.
                    val out = mutableListOf<Video>()
                    var i = searchWindow
                    while (i < searchMatches.size && out.size < SEARCH_SCREEN_BATCH) {
                        searchMatches[i].takeIf { scr.needsScreening(it) }?.let { out += it }
                        i++
                    }
                    searchWindow = i
                    out
                }
                searchSent += batch.size
                publishSearchScreening()
                if (batch.isNotEmpty()) {
                    scr.screenAsync(viewModelScope, batch) { onSearchVerdicts() }
                }
            } finally {
                searchScreenMoreInFlight = false
            }
        }
    }

    /** A verdict batch landed (ours or a peer's): append what it cleared. */
    private fun onSearchVerdicts() {
        viewModelScope.launch {
            if (_state.value.screen !is Screen.SearchResults) return@launch
            val shown = rawVideos.mapTo(HashSet()) { it.url }
            val cleared = withContext(Dispatchers.IO) {
                searchMatches.filter { it.url !in shown && screener?.isVisible(it) == true }
            }
            // Append-only: cleared videos join at the bottom so nothing the
            // kid is already looking at moves.
            if (cleared.isNotEmpty()) rawVideos = rawVideos + cleared
            publishSearchScreening()
        }
    }

    /** Recompute the progress line/bar and held count for the search screen. */
    private suspend fun publishSearchScreening() {
        val scr = screener
        var pending = 0
        var heldNow = 0
        if (scr != null) withContext(Dispatchers.IO) {
            searchMatches.take(searchWindow).forEach { v ->
                if (scr.needsScreening(v)) pending++
                else if (!scr.isVisible(v)) heldNow++
            }
        }
        if (_state.value.screen !is Screen.SearchResults) return
        _state.value = _state.value.copy(
            videos = annotated(includeFinished = true),
            held = heldNow,
            searchScreening = if (pending > 0) SearchScreening(
                total = searchSent,
                done = searchSent - pending,
                beyondWindow = searchMatches.size - searchWindow
            ) else null
        )
    }

    /** Partially-watched, unblocked videos across all sources, most recent first. */
    private fun keepWatchingRow(): List<VideoItem> =
        sources.flatMap { videoCache.load(it.id) }
            .distinctBy { it.url }
            .filter { it.videoId !in blockedVideoIds && !tooShort(it) }
            .filter { screener?.isVisible(it) != false }
            .mapNotNull { video ->
                history.progress(video.url)
                    ?.takeIf { !it.isFinished && it.fraction > 0.02f }
                    ?.let { VideoItem(video, it.fraction) to it.lastWatchedAt }
            }
            .sortedByDescending { it.second }
            .take(10)
            .map { it.first }

    /**
     * The TV home's "Watched lately" row: the last dozen videos this kid played,
     * newest first, joined to the caches (the phone reaches the same list via
     * the History chip). Cache reads — call off-main.
     */
    private fun historyRow(): List<VideoItem> =
        historyItems(history.all(), sources.flatMap { videoCache.load(it.id) }, HISTORY_ROW_MAX)
            .filter { it.video.videoId !in blockedVideoIds && !tooShort(it.video) && screener?.isVisible(it.video) != false }

    /** A source gets a NEW badge when its newest cached video postdates the kid's last visit. */
    private fun computeNewBadges(): Set<String> =
        sources.mapNotNull { source ->
            val latest = videoCache.load(source.id).firstOrNull()?.url ?: return@mapNotNull null
            val seen = usage.lastSeenLatest(source.id) ?: return@mapNotNull null
            source.id.takeIf { latest != seen }
        }.toSet()

    /**
     * Sources whose every cached video is hidden (parent-blocked or held by AI
     * screening) vanish from the kid's home — an all-held tile opens onto an
     * empty grid, which reads as broken. They reappear as approvals land.
     * Sources with no cached feed yet stay visible: their state is unknown.
     */
    private fun visibleSources(channels: List<Source>): List<Source> =
        channels.filter { source ->
            val cached = videoCache.load(source.id)
            cached.isEmpty() || cached.any { v ->
                v.videoId !in blockedVideoIds && !tooShort(v) && screener?.isVisible(v) != false
            }
        }

    /**
     * The parent's pinned hero, standing in for configuration that does not
     * exist yet.
     *
     * TODO(front-end, pinned-hero config phase): the real list is the parent's.
     * That means a `pinned` field on Whitelist, its own section in ConfigMerge
     * with a tombstone rule and a stamp, an editor on the phone and the
     * matching page on the hub, and a row in docs/LAN-API.md. It is
     * deliberately a phase of its own: putting a new field in config.json is
     * the sectioned merge's problem, and those rules are not the home screen's.
     *
     * Until then the stand-in is DERIVED rather than a literal list of ids. A
     * literal would name one family's channels and leave every other home with
     * an empty hero — and a shelf nobody can see is a shelf nobody can review.
     * So: the first two of this kid's own sources, in whitelist order.
     */
    private fun standInPins(): List<String> = sources.take(2).map { it.id }

    /**
     * The hero's cards.
     *
     * Which sources may appear is entirely [resolvePins]' fail-closed join
     * against [visible] — the list the home already draws, which has been
     * through the per-kid `visibleTo` filter *and* [visibleSources]. Resolving
     * a pin any other way would let a channel restricted to an older sibling,
     * or one whose whole feed is held for review, become the largest thing on
     * a five-year-old's home screen. Cache reads — call off-main.
     */
    private fun pinnedRow(visible: List<Source>): List<PinnedItem> =
        resolvePins(
            pinned = standInPins(),
            visible = visible,
            newCount = { newVideoCount(it.id) },
            videoCount = { source ->
                videoCache.load(source.id).count {
                    it.videoId !in blockedVideoIds && !tooShort(it) && screener?.isVisible(it) != false
                }
            }
        )

    /**
     * How many videos have landed on a source since this kid last looked. The
     * NEW badge is the yes/no form of the same question; the hero says the
     * number. Zero when the kid has never opened it — "everything is new" is
     * not a useful thing to shout on a first launch.
     */
    private fun newVideoCount(sourceId: String): Int {
        val seen = usage.lastSeenLatest(sourceId) ?: return 0
        val at = videoCache.load(sourceId).indexOfFirst { it.url == seen }
        return if (at <= 0) 0 else at
    }

    /**
     * The header's screen-time readout: minutes left at normal drain, and what
     * (if anything) blocks a play press right now. Prefs reads — call off-main.
     */
    private fun screenTime(): Pair<Long?, String?> =
        sessionGuard.remainingMs(100) to sessionGuard.blockReason(100)

    /** Update home-screen tiles without ever disturbing the screen the kid is on. */
    private suspend fun publishChannels(channels: List<Source>) {
        // distinctBy: two whitelist entries (URL form + UC id) can canonicalize
        // to the same channel — duplicate grid keys crash Compose.
        val distinct = channels.distinctBy { it.id }
        // Tiles/rows/badges all re-read every source's cache file — a whole
        // whitelist of disk reads, so computed off-main, applied in one write.
        val (tiles, keepWatching, badges) = withContext(Dispatchers.IO) {
            Triple(sortByUsage(visibleSources(distinct)), keepWatchingRow(), computeNewBadges())
        }
        val (left, reason) = withContext(Dispatchers.IO) { screenTime() }
        val (feed, recent) = withContext(Dispatchers.IO) { buildFeed(tiles) to historyRow() }
        val suggested = withContext(Dispatchers.IO) { suggestionsRow(tiles) }
        // Pins resolve against `tiles`, never against `sources` or the
        // whitelist: `tiles` is what this kid may actually see.
        val pins = withContext(Dispatchers.IO) { pinnedRow(tiles) }
        val onHome = _state.value.screen == Screen.Home
        _state.value = _state.value.copy(
            channels = tiles,
            pinned = pins,
            keepWatching = keepWatching,
            newBadges = badges,
            feed = feed,
            recentHistory = recent,
            suggested = suggested,
            channelAvatars = distinct.associate { it.name to it.avatarUrl },
            allHeld = distinct.isNotEmpty() && tiles.isEmpty(),
            remainingMs = left,
            blockReason = reason,
            loading = if (onHome) false else _state.value.loading
        )
    }

    /**
     * The home feed: each channel's newest cached videos, interleaved so the
     * top of the page is one from every channel (most-watched first) rather
     * than one channel's whole page. Finished videos drop out; half-watched
     * ones keep their bar. Cache-only on purpose — the background warm keeps
     * the caches fresh, and a feed that waits on the network is a spinner.
     */
    private fun buildFeed(channels: List<Source>): List<VideoItem> {
        val perChannel = channels.map { source ->
            videoCache.load(source.id)
                .filter { it.videoId !in blockedVideoIds && !tooShort(it) && screener?.isVisible(it) != false }
                .take(FEED_PER_CHANNEL)
        }
        val mixed = interleave(perChannel, FEED_MAX) { it.url }.mapNotNull { video ->
            val p = history.progress(video.url)
            if (p?.isFinished == true) null else VideoItem(video, p?.fraction)
        }
        return filterVideos(mixed, effectiveHomeFilter(), homeShuffleSeed)
    }

    /**
     * The You tab's rows: every shelf the kid has built, first few videos
     * each, empty shelves left out. File reads throughout — off-main.
     */
    private fun youShelves(): List<YouShelf> {
        fun annotate(videos: List<Video>): List<VideoItem> = videos
            .filter { it.videoId !in blockedVideoIds && !tooShort(it) && screener?.isVisible(it) != false }
            .map { VideoItem(it, history.progress(it.url)?.fraction) }
        val known = sources.flatMap { videoCache.load(it.id) } + watchlistStore.load() + watchLaterStore.load()
        val historyRow = historyItems(history.all(), known, YOU_PAGE_MAX)
            .filter { it.video.videoId !in blockedVideoIds && !tooShort(it.video) && screener?.isVisible(it.video) != false }
        return listOf(
            YouShelf(Screen.Watchlist, "❤️", "Favorites", annotate(watchlistStore.load()).take(YOU_PAGE_MAX)),
            YouShelf(Screen.WatchLater, "🕒", "Watch later", annotate(watchLaterStore.load()).take(YOU_PAGE_MAX)),
            YouShelf(Screen.Queue, "📚", "Up next", annotate(queueStore.load()).take(YOU_PAGE_MAX)),
            YouShelf(Screen.History, "🕘", "History", historyRow),
            // Downloads were approved by the parent one by one: no re-screening.
            YouShelf(
                Screen.Downloads, "⬇️", "Downloads",
                visibleDownloads().map { VideoItem(it, history.progress(it.url)?.fraction) }.take(YOU_PAGE_MAX)
            )
        // The four shelves are always there, empty or not — the page has one
        // shape, and an empty row says what would fill it. Downloads only
        // once anything has been downloaded (phones; a TV never has any).
        ).filter { it.screen != Screen.Downloads || it.items.isNotEmpty() }
    }

    /** The You tab. */
    fun openYou() {
        playlistParent = null
        rawVideos = emptyList()
        feedHandle = null
        uploadsNextPage = null
        _state.value = _state.value.copy(
            screen = Screen.You, loading = false, error = null, videos = emptyList(), held = 0,
            channelWatched = emptyList(), watchedTileAt = null, scrollTo = null
        )
        reloadYou()
    }

    private fun reloadYou() {
        viewModelScope.launch {
            val shelves = withContext(Dispatchers.IO) {
                pruneFinishedSavedLists()
                pruneFinishedQueue()
                youShelves()
            }
            if (_state.value.screen == Screen.You) _state.value = _state.value.copy(youShelves = shelves)
        }
    }

    /** The grid of every channel — "Show all" behind the home row. */
    fun openChannels() {
        _state.value = _state.value.copy(
            screen = Screen.Channels, videos = emptyList(), held = 0, loading = false, error = null
        )
    }

    /** The search page (field, mic, recent searches) with no query yet. */
    fun openSearch() {
        _state.value = _state.value.copy(
            screen = Screen.Search, videos = emptyList(), held = 0, loading = false,
            error = null, searchScreening = null
        )
    }

    fun clearRecentSearches() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { searchHistory?.clear() }
            _state.value = _state.value.copy(recentSearches = emptyList())
        }
    }

    /**
     * The player's channel avatar was tapped: open that channel's grid. Matched
     * by name — the player only ever knows the uploader's name.
     */
    fun openChannelByName(name: String) {
        val source = sources.firstOrNull { it.name == name }
            ?: _state.value.channels.firstOrNull { it.name == name }
        android.util.Log.i("YosemiteKids",
            "open channel by name \"$name\" -> ${source?.id ?: "no match among ${sources.map { it.name }}"}"
        )
        openChannel(source ?: return)
    }

    /**
     * Devices a video can be sent to from this phone's hold menu: the paired
     * list, on a parent device only. A kid's phone administers nothing, and
     * a TV's list is empty anyway.
     */
    fun castTargets(): List<PairedDevice> =
        if (pairingStore?.role() == PairingStore.Role.KID) emptyList()
        else pairingStore?.paired().orEmpty()

    /** "Play on <device>": ask it, and say what happened in the pill. */
    fun castTo(item: VideoItem, device: PairedDevice) {
        val percent = _state.value.channels
            .firstOrNull { it.name == item.video.channelName }?.timeMultiplierPercent ?: 100
        viewModelScope.launch {
            val ok = LanClient.play(
                device,
                RemotePlayerControl.PlayRequest(
                    url = item.video.url,
                    title = item.video.title,
                    channel = item.video.channelName,
                    thumb = item.video.thumbnailUrl,
                    timePercent = percent
                )
            )
            showNotice(
                if (ok) "Playing on ${device.name} 📺"
                else "${device.name} didn't take it — is it on, and is this video allowed there?"
            )
        }
    }

    /**
     * Re-run whatever the current screen loads — the friendly error card's
     * "Try again". Each screen's opener already resets its own state.
     */
    fun retryCurrent() {
        when (val s = _state.value.screen) {
            Screen.Home -> refresh(userInitiated = true)
            Screen.Channels -> openChannels()
            Screen.You -> openYou()
            is Screen.Playlists -> openChannel(s.source)
            Screen.Search -> openSearch()
            Screen.History -> openHistory()
            is Screen.ChannelVideos -> openChannel(s.source)
            is Screen.WatchedVideos -> { goHome(); openChannel(s.source) }
            Screen.Surprise -> surpriseMe()
            Screen.Watchlist -> openWatchlist()
            Screen.WatchLater -> openWatchLater()
            Screen.Downloads -> openDownloads()
            Screen.Queue -> openQueue()
            is Screen.SearchResults -> search(s.query)
        }
    }

    private var refreshInFlight = false

    /**
     * [userInitiated] drives the pull-to-refresh spinner; background refreshes stay
     * silent. The spinner completes as soon as the whitelist file is re-fetched and
     * the tile list updated — the slow per-channel name/avatar resolution continues
     * silently afterwards.
     */
    fun refresh(userInitiated: Boolean = false) = viewModelScope.launch {
        if (refreshInFlight) {
            // Attach the pull spinner to the refresh that's already running.
            if (userInitiated) _state.value = _state.value.copy(refreshing = true)
            return@launch
        }
        refreshInFlight = true
        if (userInitiated) {
            _state.value = _state.value.copy(refreshing = true)
            // A pull is "mix again" for the Random chips.
            shuffleSeed = kotlin.random.Random.nextLong()
            homeShuffleSeed = kotlin.random.Random.nextLong()
        }

        // Paint instantly from the last successful run while the fresh list loads.
        val cached = sourceCache.load()
        if (cached.isNotEmpty()) {
            sources = cached
            publishChannels(cached)
        } else if (_state.value.screen == Screen.Home) {
            _state.value = _state.value.copy(loading = true)
        }
        runCatching { whitelist.load() }
            .onSuccess { list ->
                // Per-kid blocks fold into one set — every downstream check
                // ("is this video blocked?") stays a plain membership test.
                blockedVideoIds = list.blockedVideoIds +
                    list.blockedFor.filterValues { activeProfileId in it }.keys
                minVideoSeconds = (list.limitsFor(activeProfileId).minVideoMinutes ?: 0) * 60L
                screener?.config = list.ai
                screener?.profiles = list.profiles
                screener?.activeProfileId = activeProfileId
                screener?.allowedOverrides = list.allowedIdsFor(activeProfileId)
                channelLayout = list.channelLayout
                channelOrder = list.channelOrder
                suggestSimilar = list.suggestSimilar
                playlistPicks = list.sources.filter { it.playlistIds.isNotEmpty() }
                    .associate { it.url to it.playlistIds }
                _state.value = _state.value.copy(
                    channelSort = effectiveChannelSort(),
                    homeFilter = effectiveHomeFilter(),
                    channelFilter = effectiveChannelFilter(),
                    pageSize = list.pageSize,
                    showVideoAge = list.showVideoAge
                )
                // Only the kid whose home this is owns these prefs. With nobody
                // picked yet (who's-watching screen) `sessionGuard` is still the
                // legacy unsuffixed store — which ProfileNamespace hands to the
                // *first* kid — so writing the family-wide limits there would wipe
                // that kid's real rules every time a push lands on the picker.
                // Their own refresh writes them properly the moment they're picked.
                if (activeProfileId != null || list.profiles.isEmpty()) {
                    sessionGuard.saveLimits(list.limitsFor(activeProfileId))
                }
                // Fast publish: entries merged with cached tile artwork (keyed by URL,
                // which survives id canonicalization). Adds/removes land right here.
                val cachedByUrl = cached.associateBy { it.url }
                fun names(sourcesList: List<Source>) {
                    // Which channel names belong to entries this kid can't see —
                    // resolved from whatever names we have at this point.
                    val byUrl = sourcesList.associateBy { it.url }
                    hiddenChannelNames = list.sources
                        .filterNot { it.visibleTo(activeProfileId) }
                        .mapNotNull { e -> byUrl[e.url]?.name ?: e.label }
                        .toSet()
                }
                // The full entry list resolves and caches (tile art is shared by
                // every kid); only the active kid's subset is published. Matched
                // by URL, never id: resolution canonicalizes user/, c/ and
                // @handle ids to UC… form, so an id join silently drops every
                // non-UC entry once its real identity comes back.
                fun visible(sourcesList: List<Source>): List<Source> {
                    val visibleUrls = list.sources
                        .filter { it.visibleTo(activeProfileId) }
                        .map { it.url }.toSet()
                    return sourcesList.filter { it.url in visibleUrls }
                }
                // Channel notes ride the same name resolution as the tiles:
                // verdict-time lookups only have a video's uploader name, so
                // the map re-derives whenever the resolved names improve.
                fun notes(sourcesList: List<Source>) {
                    screener?.channelNotes =
                        io.yosemitekids.app.data.DeepCheck.notesByChannelName(list.sources, sourcesList)
                }
                val provisional = list.sources.map { e ->
                    cachedByUrl[e.url] ?: Source(e.id, e.url, e.label ?: e.id, null, e.kind)
                }
                names(provisional)
                notes(provisional)
                sources = visible(provisional)
                publishChannels(sources)
                // hiddenChannelNames just settled — re-derive the offline badges.
                refreshDownloadState()
                _state.value = _state.value.copy(refreshing = false) // pull completes here
                refreshInFlight = false

                // Slow detail resolution — background lane when tiles are cosmetic.
                launch {
                    val cosmetic = cached.isNotEmpty()
                    val resolved = coroutineScope {
                        list.sources.map { entry ->
                            async {
                                runCatching { yt.source(entry, background = cosmetic) }
                                    .getOrElse { e ->
                                        android.util.Log.w("YosemiteKids", "source ${entry.id} failed", e)
                                        Source(entry.id, entry.url, entry.label ?: entry.id, null, entry.kind)
                                    }
                            }
                        }.awaitAll()
                    }
                    // Don't cache failed resolutions (null avatar) over previous good ones.
                    // distinctBy: entries in different forms may canonicalize to one channel.
                    val best = resolved.map { s ->
                        if (s.avatarUrl == null) cachedByUrl[s.url]?.takeIf { it.avatarUrl != null } ?: s
                        else s
                    }.distinctBy { it.id }
                    names(best)
                    notes(best)
                    sources = visible(best)
                    sourceCache.save(best)
                    publishChannels(sources)
                    // Warm the per-channel caches after the UI has settled.
                    kotlinx.coroutines.delay(3_000)
                    runCatching { warmCaches() }
                }
            }
            .onFailure { e ->
                android.util.Log.w("YosemiteKids", "whitelist refresh failed", e)
                _state.value = _state.value.copy(refreshing = false)
                refreshInFlight = false
                // Keep showing the cached tiles if we have them; only error on a cold cache.
                if (cached.isEmpty() && _state.value.screen == Screen.Home) {
                    _state.value = _state.value.copy(loading = false, error = e.message)
                }
            }
    }

    /**
     * Walks every source one at a time in the background lane, persisting each
     * source's videos to the disk cache as they land — so channels the kid has
     * never opened still open instantly afterwards (and feed the surprise pool).
     */
    private suspend fun warmCaches() {
        for (source in sources) {
            val videos = runCatching { yt.uploadsPage(source, background = true).videos }
                .getOrElse { e ->
                    android.util.Log.w("YosemiteKids", "warm ${source.id} failed", e)
                    emptyList()
                }
            if (videos.isNotEmpty()) {
                withContext(Dispatchers.IO) { videoCache.save(source.id, videos) }
                prefetchThumbs(videos.mapNotNull { it.thumbnailUrl })
                // Initial sweep: the background warm walks every source, so a fresh
                // whitelist (or new rules) gets its whole catalog screened here.
                kickScreening(videos)
                // Page-1 delta into the search index — zero extra requests, and
                // new uploads become searchable within a refresh or two.
                crawler?.harvestPage1(source, videos)
            }
        }
    }

    /**
     * Sends anything not yet screened to the AI in the background; each verdict
     * batch re-filters whatever screen the kid is on, so held videos pop in as
     * they are cleared.
     */
    private fun kickScreening(videos: List<Video>) {
        screener?.screenAsync(viewModelScope, videos) { reapplyScreening() }
    }

    /** Re-filters whatever is on screen after new verdicts land (AI batch, a
     *  peer's import, or the player's pre-play deep check — the last is why the
     *  home screen calls this on resume: a video blocked at the play press must
     *  be gone from the shelf the kid returns to). */
    fun reapplyScreening() {
        viewModelScope.launch {
            val onHome = _state.value.screen == Screen.Home
            // Search owns its own re-filter: results append as verdicts land
            // (never a wholesale rebuild, which could reorder under the kid).
            val onSearch = _state.value.screen is Screen.SearchResults
            val includeFinished = includeFinishedNow()
            // Tiles and the resume row walk the per-source cache files — off-main.
            val (tiles, keepWatching) = withContext(Dispatchers.IO) {
                sortByUsage(visibleSources(sources.distinctBy { it.id })) to keepWatchingRow()
            }
            // A channel re-splits instead: writing the flat list here first
            // would flash every watched video back into the grid for a frame.
            val channel = channelSource()
            val untouched = onHome || onSearch || channel != null
            _state.value = _state.value.copy(
                // Verdicts can clear (or fully hold) a source — tiles follow along.
                channels = tiles,
                keepWatching = keepWatching,
                videos = if (untouched) _state.value.videos
                else annotated(includeFinished = includeFinished),
                held = if (untouched) _state.value.held else heldByScreening()
            )
            channel?.let { publishChannel(it) }
            if (onSearch) onSearchVerdicts()
        }
    }

    /**
     * Channel browsing keeps fully-watched videos (kids rewatch!) — [publishChannel]
     * then sorts them onto the Watched shelf rather than dropping them. Up next
     * keeps them too: an item there was put there on purpose, so hiding it would
     * look like the add silently failed. Surprise excludes them: it's the
     * discovery mix, and clutter there would defeat its purpose.
     */
    private fun includeFinishedNow(): Boolean =
        channelSource() != null || _state.value.screen == Screen.Queue ||
            // History is finished videos by definition: a re-filter on resume
            // (every return from the player) must not empty the shelf.
            _state.value.screen == Screen.History

    /** Raw videos on the current screen hidden by the screener (no verdict yet or held for review). */
    private fun heldByScreening(): Int = rawVideos.count { v ->
        v.videoId !in blockedVideoIds && !tooShort(v) && screener?.isVisible(v) == false
    }

    /**
     * Which of this channel's videos were already finished when it opened —
     * the frozen half of [splitWatched]. Filled in the first time each video
     * is classified (so a page arriving later is judged on arrival) and never
     * revised while the channel is open, which is what keeps a just-finished
     * video from sliding out from under the kid.
     */
    private val finishedOnArrival = mutableMapOf<String, Boolean>()

    /**
     * Channel grids only. A playlist is a curated sequence — its order *is*
     * the content, and the "▶ Continue" chip already finds the next unwatched
     * one — so sinking watched videos there would scramble what a parent
     * deliberately lined up.
     */
    private fun sinksWatched(source: Source): Boolean = source.kind == SourceKind.CHANNEL

    /**
     * Publishes the channel screen (or its Watched shelf) from [rawVideos].
     * [pin] fixes the "Watched" tile to the end of what's loaded now; it is
     * passed only once per channel, when the first page lands, and later pages
     * append below the tile so nothing already scrolled past moves.
     */
    private fun publishChannel(source: Source, pin: Boolean = false) {
        // Every video stays in the grid where the channel put it (watched ones
        // just dim); the parent's layout setting only reorders. The watched
        // ones are *also* the History tile that leads the grid — a place to
        // find "that one again", not a shelf they were exiled to.
        val items = orderForLayout(annotated(includeFinished = true))
        val watched = orderByWatched(items.filter { (it.progress ?: 0f) >= 0.98f }) { url ->
            history.progress(url)?.lastWatchedAt ?: 0L
        }
        val onShelf = _state.value.screen is Screen.WatchedVideos
        _state.value = _state.value.copy(
            loading = false,
            videos = if (onShelf) watched else items,
            channelWatched = watched,
            // Held videos are unscreened, never watched — the count belongs to
            // the grid they're missing from, not to the shelf.
            held = if (onShelf) 0 else heldByScreening(),
            watchedTileAt = if (watched.isNotEmpty()) 0 else null
        )
    }

    /** The kid's chip for channel pages (the parent's layout as its default), applied to a channel's videos. */
    private fun orderForLayout(items: List<VideoItem>): List<VideoItem> =
        filterVideos(items, effectiveChannelFilter(), channelShuffleSeed)

    /** From the family config; "newest" until the first refresh reads it. */
    private var channelLayout: String = CHANNEL_LAYOUT_NEWEST

    /** The parent's "More like what you watch" switch, read with the config. */
    private var suggestSimilar: Boolean = true

    /**
     * "More like what you watch": cached videos the kid has never opened,
     * ranked against the titles they actually watched. Older videos surface
     * here — the feed above is newest-first, so a channel's back catalogue is
     * otherwise unreachable without scrolling the channel page.
     *
     * Reads every source's cache file, so call it off the main thread.
     */
    private fun suggestionsRow(channels: List<Source>): List<VideoItem> {
        if (!suggestSimilar) return emptyList()
        val watched = history.all().filterValues { it.lastWatchedAt > 0 }
        if (watched.isEmpty()) return emptyList()

        val known = channels.flatMap { videoCache.load(it.id) }
        val byUrl = HashMap<String, io.yosemitekids.app.data.Video>(known.size)
        for (v in known) byUrl.putIfAbsent(v.url, v)

        // Newest watch first — suggestionsFor weights by position, so the order
        // here is the whole point.
        val recent = watched.entries.sortedByDescending { it.value.lastWatchedAt }
        val titles = recent.mapNotNull { byUrl[it.key]?.title }.take(SUGGEST_HISTORY_DEPTH)
        val affinity = recent.mapNotNull { byUrl[it.key]?.channelName }
            .groupingBy { it }.eachCount()

        val candidates = known.asSequence()
            .filter { it.url !in watched }
            .filter {
                it.videoId !in blockedVideoIds && !tooShort(it) &&
                    screener?.isVisible(it) != false
            }
            .distinctBy { it.url }
            .map { VideoItem(it, null) }
            .toList()

        return suggestionsFor(titles, candidates, affinity, SUGGEST_ROW_MAX)
    }

    /**
     * The History shelf: everything this kid has watched, newest first,
     * joined to the caches (and the saved lists, which carry their own
     * metadata) so a video from a channel that has since scrolled off its
     * cached page can still be found.
     */
    fun openHistory() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                screen = Screen.History, loading = true, videos = emptyList(),
                held = 0, error = null
            )
            feedHandle = null
            uploadsNextPage = null
            val items = withContext(Dispatchers.IO) {
                val known = sources.flatMap { videoCache.load(it.id) } +
                    watchlistStore.load() + watchLaterStore.load()
                historyItems(history.all(), known, HISTORY_MAX)
                    .filter { it.video.videoId !in blockedVideoIds && !tooShort(it.video) && screener?.isVisible(it.video) != false }
            }
            // The kid may have moved on while the caches were read.
            if (_state.value.screen != Screen.History) return@launch
            rawVideos = items.map { it.video }
            _state.value = _state.value.copy(loading = false, videos = items, held = 0)
        }
    }

    /** The channel's finished videos, on their own screen. */
    fun openChannelWatched() {
        val source = (_state.value.screen as? Screen.ChannelVideos)?.source ?: return
        _state.value = _state.value.copy(
            screen = Screen.WatchedVideos(source),
            videos = _state.value.channelWatched,
            held = 0,
            // Shared grid: without this the shelf would open at the depth the
            // channel was scrolled to.
            scrollTo = 0
        )
    }

    /** Back out of the Watched shelf. The channel's pages are still loaded. */
    fun backToChannel() {
        val source = (_state.value.screen as? Screen.WatchedVideos)?.source ?: return
        _state.value = _state.value.copy(screen = Screen.ChannelVideos(source), held = 0)
        publishChannel(source)
        // Land back on the tile they left through, rather than at the top of a
        // grid they had already scrolled a long way down.
        _state.value = _state.value.copy(scrollTo = _state.value.watchedTileAt ?: 0)
    }

    /** The grid obeyed [UiState.scrollTo]; don't make it jump again. */
    fun scrollHandled() {
        if (_state.value.scrollTo != null) _state.value = _state.value.copy(scrollTo = null)
    }

    private fun annotated(includeFinished: Boolean): List<VideoItem> =
        rawVideos.mapNotNull { video ->
            if (video.videoId in blockedVideoIds || tooShort(video)) return@mapNotNull null
            if (screener?.isVisible(video) == false) return@mapNotNull null
            val p = history.progress(video.url)
            when {
                p == null -> VideoItem(video, null)
                p.isFinished && !includeFinished -> null
                else -> VideoItem(video, p.fraction)
            }
        }

    /** Re-read watch progress (called when returning from the player). */
    fun refreshProgress() {
        viewModelScope.launch {
            // Saved-list pruning and the row rebuilds are all file reads — off-main.
            val (watchlisted, watchLater, queued) = withContext(Dispatchers.IO) {
                pruneFinishedSavedLists()
                // Also drains the queue when the finish happened outside a
                // queue launch (kid watched the same video from its channel).
                pruneFinishedQueue()
                Triple(watchlistStore.urls(), watchLaterStore.urls(), queueStore.urls())
            }
            if (_state.value.screen == Screen.You) reloadYou()
            if (_state.value.screen == Screen.Home) {
                val (keepWatching, badges) = withContext(Dispatchers.IO) {
                    keepWatchingRow() to computeNewBadges()
                }
                // Back from the player is exactly when the chip has moved.
                val (left, reason) = withContext(Dispatchers.IO) { screenTime() }
                val (feed, recent) = withContext(Dispatchers.IO) {
                    buildFeed(_state.value.channels) to historyRow()
                }
                // Back from the player is also when a watch just landed, so the
                // suggestions are stale by definition until they're rebuilt.
                val suggested = withContext(Dispatchers.IO) {
                    suggestionsRow(_state.value.channels)
                }
                _state.value = _state.value.copy(
                    keepWatching = keepWatching,
                    newBadges = badges,
                    feed = feed,
                    recentHistory = recent,
                    suggested = suggested,
                    watchlisted = watchlisted,
                    watchLater = watchLater,
                    queued = queued,
                    remainingMs = left,
                    blockReason = reason
                )
                return@launch
            }
            _state.value = _state.value.copy(
                watchlisted = watchlisted, watchLater = watchLater, queued = queued
            )
            if (_state.value.screen == Screen.Downloads) {
                // Offline shelf: fresh red bars, but no screener/blocklist re-filtering.
                _state.value = _state.value.copy(videos = downloadItems())
                return@launch
            }
            if (_state.value.screen == Screen.History) {
                // The video just watched belongs at the top now.
                openHistory()
                return@launch
            }
            if (_state.value.screen == Screen.Watchlist) {
                rawVideos = withContext(Dispatchers.IO) { watchlistStore.load() }
            }
            if (_state.value.screen == Screen.WatchLater) {
                rawVideos = withContext(Dispatchers.IO) { watchLaterStore.load() }
            }
            if (_state.value.screen == Screen.Queue) {
                rawVideos = withContext(Dispatchers.IO) { queueStore.load() }
            }
            val channel = channelSource()
            if (channel != null) {
                // A video finished in the player stays put on the grid behind
                // it — the snapshot froze at open — so this only refreshes bars.
                publishChannel(channel)
                return@launch
            }
            _state.value = _state.value.copy(
                videos = annotated(includeFinished = includeFinishedNow()),
                held = heldByScreening()
            )
        }
    }

    /** The channel behind whichever of its two screens is showing, else null. */
    private fun channelSource(): Source? = when (val s = _state.value.screen) {
        is Screen.ChannelVideos -> s.source
        is Screen.WatchedVideos -> s.source
        else -> null
    }

    /**
     * Re-fetch the whitelist when the app comes back to the foreground — but only
     * from the home screen, so a parent's edit lands without yanking the kid out
     * of whatever they're browsing.
     */
    fun refreshIfIdle() {
        if (_state.value.screen == Screen.Home && !_state.value.loading) refresh()
        syncWatchState()
        syncConfigState()
    }

    /**
     * [parent] is set only when a playlist page is opened from a channel's
     * Playlists row: Back then returns to that channel. Every other open
     * clears it, or a stale parent would hijack Back on a later page.
     */
    fun openChannel(source: Source, parent: Source? = null) = viewModelScope.launch {
        playlistParent = parent
        usage.bump(source.id)
        // A fresh visit is a fresh mix for the Random chip.
        channelShuffleSeed = kotlin.random.Random.nextLong()
        _state.value = _state.value.copy(
            screen = Screen.ChannelVideos(source), loading = true, videos = emptyList(),
            held = 0, error = null, channelWatched = emptyList(), watchedTileAt = null,
            channelPlaylists = emptyList(), playlistShelves = emptyList(), scrollTo = 0
        )
        feedHandle = null
        uploadsNextPage = null
        // A channel's playlists are its own organisation — seasons, songs,
        // series — pulled in for every channel: the strip of all of them,
        // then the first few (pinned ones first) as rows.
        if (source.kind == SourceKind.CHANNEL) {
            launch {
                val refs = loadPlaylistRow(source) ?: return@launch
                val ids = (playlistPicks[source.url].orEmpty() + refs.map { it.id }).distinct()
                loadPlaylistShelves(source, ids, refs)
            }
        }
        // A fresh visit is where the sort catches up with what was watched last
        // time — see [finishedOnArrival].
        finishedOnArrival.clear()
        gridTarget = SCREENFUL
        pagesThisPump = 0

        // Paint instantly from the last visit; refresh silently underneath.
        val cachedVideos = withContext(Dispatchers.IO) { videoCache.load(source.id) }
        if (cachedVideos.isNotEmpty()) {
            rawVideos = cachedVideos
            usage.setLastSeenLatest(source.id, cachedVideos.first().url)
            publishChannel(source)
            kickScreening(cachedVideos)
        } else if (source.kind == SourceKind.CHANNEL && source.id.startsWith("UC")) {
            // Cold channel: race a tiny RSS fetch for a fast first paint. The full
            // page replaces it when it lands; guards keep the slower result from
            // clobbering the richer one.
            launch {
                val quick = runCatching { yt.quickFeed(source.id) }.getOrDefault(emptyList())
                val current = _state.value.screen
                if (quick.isNotEmpty() && rawVideos.isEmpty() &&
                    current is Screen.ChannelVideos && current.source.id == source.id
                ) {
                    rawVideos = quick
                    publishChannel(source)
                    kickScreening(quick)
                }
            }
        }

        runCatching { yt.uploadsPage(source) }
            .onSuccess { page ->
                // Guard against a stale response landing after the kid navigated away.
                val current = _state.value.screen
                if (current !is Screen.ChannelVideos || current.source.id != source.id) return@onSuccess
                // Fresh first page, then previously-cached deeper videos after it —
                // a revisit keeps its scroll depth instead of snapping back to 30.
                rawVideos = (page.videos + cachedVideos).distinctBy { it.url }
                feedHandle = page.handle
                uploadsNextPage = page.nextPage
                withContext(Dispatchers.IO) { videoCache.save(source.id, rawVideos.take(500)) }
                rawVideos.firstOrNull()?.let { usage.setLastSeenLatest(source.id, it.url) }
                // Give the on-screen thumbnails a head start before trickling the rest.
                launch {
                    kotlinx.coroutines.delay(1_500)
                    prefetchThumbs(page.videos.mapNotNull { it.thumbnailUrl })
                }
                publishChannel(source)
                kickScreening(rawVideos)
                // Opening a channel fetches its fresh page 1 — harvest it.
                crawler?.harvestPage1(source, page.videos)
                // First fill: page 1 alone can be all watched videos.
                pumpUntilFilled()
            }
            .onFailure {
                android.util.Log.w("YosemiteKids", "open ${source.id} failed", it)
                if (cachedVideos.isEmpty()) {
                    _state.value = _state.value.copy(loading = false, error = it.message ?: it.javaClass.simpleName)
                } else {
                    // Offline, browsing the cache: no page is coming, so settle
                    // on what we have or the tile would never appear at all.
                    pumpUntilFilled()
                }
            }
    }

    /**
     * The "By playlist" layout's row: the channel's playlists, one listing
     * per channel per day (see [ChannelPlaylistsCache]). Nothing else is
     * fetched until a chip is pressed — the playlist then opens like any
     * whitelisted playlist source — so a fifty-channel library pays one
     * small request per channel actually opened. Any failure just leaves
     * the plain grid; the row is a bonus.
     */
    private suspend fun loadPlaylistRow(source: Source): List<PlaylistRef>? {
        val cache = playlistsCache ?: return null
        fun stillHere() = (_state.value.screen as? Screen.ChannelVideos)?.source?.id == source.id
        val listed = withContext(Dispatchers.IO) {
            cache.load(source.id)?.takeIf { cache.isFresh(source.id) }
        } ?: runCatching { yt.channelPlaylists(source) }
            .onSuccess { withContext(Dispatchers.IO) { cache.save(source.id, it) } }
            .onFailure { android.util.Log.w("YosemiteKids", "playlists of ${source.id} failed", it) }
            .getOrNull()
            ?: return null
        // A channel's "Shorts" playlist is the one collection a kid's shelf
        // never wants; every video in it would be dropped by the row's
        // length rule anyway, so the chip would open onto nothing.
        val refs = listed.filterNot { it.name.contains("shorts", ignoreCase = true) }
        if (refs.isEmpty() || !stillHere()) return null
        android.util.Log.i("YosemiteKids", "playlist row for ${source.id}: ${refs.size} playlists")
        // The row goes in above the grid's first item, and a lazy grid keeps
        // its anchor on that item — without this snap the row would sit just
        // above the fold, invisible until the kid happened to scroll up.
        _state.value = _state.value.copy(channelPlaylists = refs.take(PLAYLIST_ROW_MAX), scrollTo = 0)
        return refs
    }

    /** A channel's playlist as a source: it drains screen time at the channel's rate, not the default. */
    private fun playlistSource(ref: PlaylistRef, parent: Source?) =
        Source(
            ref.id, ref.url, ref.name, ref.thumbnailUrl, SourceKind.PLAYLIST,
            timeMultiplierPercent = parent?.timeMultiplierPercent ?: 100
        )

    /** Which channel a playlist page was opened from, so back returns there rather than home. */
    private var playlistParent: Source? = null

    /** Entry URL → the playlist ids the parent picked for it (config), read at refresh. */
    private var playlistPicks: Map<String, List<String>> = emptyMap()

    /**
     * The parent-picked playlists as rows above a channel's grid. Each row
     * needs the playlist's name (the channel's listing, cached a day) and its
     * first videos (its own cache, or one page fetch — the same path a
     * playlist page takes, so a later "See all" opens instantly). Shorts
     * never belong on a row: anything a minute or under is dropped, on top
     * of the kid's own rules. Rows arrive one by one as they load; a row
     * with nothing left in it is simply absent.
     */
    private suspend fun loadPlaylistShelves(source: Source, ids: List<String>, refs: List<PlaylistRef>) {
        fun stillHere() = (_state.value.screen as? Screen.ChannelVideos)?.source?.id == source.id
        val byId = refs.associateBy { it.id }
        for (id in ids.take(PLAYLIST_SHELVES_MAX)) {
            if (!stillHere()) return
            val ref = byId[id] ?: continue
            val playlist = playlistSource(ref, source)
            var videos = withContext(Dispatchers.IO) { videoCache.load(id) }
            if (videos.isEmpty()) {
                val page = runCatching { yt.uploadsPage(playlist, background = true) }
                    .onFailure { android.util.Log.w("YosemiteKids", "playlist row $id failed", it) }
                    .getOrNull()?.videos.orEmpty()
                if (page.isEmpty()) continue
                withContext(Dispatchers.IO) { videoCache.save(id, page.take(500)) }
                videos = page
            }
            val items = withContext(Dispatchers.IO) {
                videos.asSequence()
                    .filter { it.durationSeconds !in 1..60 }
                    .filter { it.videoId !in blockedVideoIds && !tooShort(it) && screener?.isVisible(it) != false }
                    .map { VideoItem(it, history.progress(it.url)?.fraction) }
                    .filter { (it.progress ?: 0f) < 0.98f }
                    .take(YOU_PAGE_MAX)
                    .toList()
            }
            if (items.isEmpty() || !stillHere()) continue
            val shelf = PlaylistShelf(ref, items)
            _state.value = _state.value.copy(
                playlistShelves = (_state.value.playlistShelves + shelf).sortedBy { ids.indexOf(it.playlist.id) },
                // Rows go in above the grid's first item; keep the page at the top.
                scrollTo = 0
            )
        }
    }

    /**
     * The Playlists strip's "See all": every playlist the channel has, with
     * its video count. The listing is already in state (the strip loaded it),
     * so this is a screen swap, not a fetch.
     */
    fun openPlaylists() {
        val source = (_state.value.screen as? Screen.ChannelVideos)?.source ?: return
        playlistParent = source
        _state.value = _state.value.copy(
            screen = Screen.Playlists(source), loading = false, error = null,
            videos = emptyList(), held = 0, scrollTo = 0
        )
    }

    /** A chip on the Playlists row: the playlist as its own page, with the channel behind it for back. */
    fun openPlaylist(ref: PlaylistRef) {
        val parent = (_state.value.screen as? Screen.ChannelVideos)?.source
        openChannel(playlistSource(ref, parent), parent = parent)
    }

    /**
     * Back, one level: the Watched shelf returns to its channel, a playlist
     * page to the channel it was opened from, everything else to home.
     */
    fun goBack() {
        val parent = playlistParent
        val screen = _state.value.screen
        when {
            // The all-playlists page belongs to its channel.
            screen is Screen.Playlists -> { playlistParent = null; openChannel(screen.source) }
            screen is Screen.WatchedVideos -> backToChannel()
            screen is Screen.ChannelVideos && parent != null -> {
                playlistParent = null
                openChannel(parent)
            }
            // A shelf opened from the You tab returns to it.
            screen == Screen.Watchlist || screen == Screen.WatchLater || screen == Screen.Queue ||
                screen == Screen.History || screen == Screen.Downloads -> { playlistParent = null; openYou() }
            else -> { playlistParent = null; goHome() }
        }
    }

    /** Random unwatched mix across whitelisted channels (playlists are excluded —
     *  those are curated sequences with autoplay, not discovery material). */
    fun surpriseMe() = viewModelScope.launch {
        _state.value = _state.value.copy(screen = Screen.Surprise, loading = true, videos = emptyList(), held = 0, error = null)
        feedHandle = null
        uploadsNextPage = null

        val channels = sources.filter { it.kind == SourceKind.CHANNEL }

        // Instant pool from the per-channel disk caches; fall back to a live fetch.
        val diskPool = withContext(Dispatchers.IO) { channels.flatMap { videoCache.load(it.id) } }
        if (diskPool.isNotEmpty()) {
            rawVideos = diskPool.shuffled()
            _state.value = _state.value.copy(loading = false, videos = annotated(includeFinished = false), held = heldByScreening())
            kickScreening(rawVideos)
            return@launch
        }
        runCatching {
            channels.flatMap { source ->
                runCatching { yt.uploadsPage(source, background = true).videos }
                    .getOrDefault(emptyList())
                    .also {
                        if (it.isNotEmpty()) withContext(Dispatchers.IO) { videoCache.save(source.id, it) }
                    }
            }
        }
            .onSuccess { pool ->
                if (_state.value.screen != Screen.Surprise) return@onSuccess
                rawVideos = pool.shuffled()
                _state.value = _state.value.copy(loading = false, videos = annotated(includeFinished = false), held = heldByScreening())
                kickScreening(rawVideos)
            }
            .onFailure {
                android.util.Log.w("YosemiteKids", "surprise failed", it)
                _state.value = _state.value.copy(loading = false, error = it.message ?: it.javaClass.simpleName)
            }
    }

    /**
     * The kid is near the end of the grid and wants more.
     *
     * One upload page is no longer one screenful: the grid carries only
     * unwatched videos, so a page of thirty can add three tiles — or none at
     * all on a channel they've been through. Scroll position alone can't drive
     * that, because the grid barely moves and the "near the end" signal only
     * fires when it does; the kid ends up swiping again and again for one row
     * at a time. So a nudge sets a target instead, and [pumpUntilFilled] keeps
     * fetching until the grid has actually grown by a screenful.
     */
    fun loadMoreUploads() {
        if (_state.value.screen !is Screen.ChannelVideos) return
        if (uploadsNextPage == null) return
        // Fresh demand: aim a screenful past what's showing and restore the
        // page budget, including while a fetch is already in flight — that one
        // will pick the raised target up when it lands.
        gridTarget = maxOf(gridTarget, _state.value.videos.size + SCREENFUL)
        pagesThisPump = 0
        fetchNextPage()
    }

    private fun fetchNextPage() {
        if (loadingMore) return
        val handle = feedHandle ?: return
        val next = uploadsNextPage ?: return
        if (_state.value.screen !is Screen.ChannelVideos) return
        loadingMore = true
        _state.value = _state.value.copy(loadingMore = true)
        viewModelScope.launch {
            runCatching { yt.moreUploads(handle, next) }
                .onSuccess { page ->
                    uploadsNextPage = page.nextPage
                    // distinctBy: page 2 overlaps the cached deep list after a merge.
                    rawVideos = (rawVideos + page.videos).distinctBy { it.url }
                    // Persist the whole accumulated list so the next visit paints
                    // this deep instantly (capped to keep the cache file sane).
                    (_state.value.screen as? Screen.ChannelVideos)?.let { s ->
                        withContext(Dispatchers.IO) { videoCache.save(s.source.id, rawVideos.take(500)) }
                        // Browsed depth becomes searchable depth — the network
                        // cost is already paid, so harvest it into the index.
                        crawler?.harvestHistory(s.source, page.videos)
                    }
                    launch { prefetchThumbs(page.videos.mapNotNull { it.thumbnailUrl }) }
                    // No pin: later pages join below the tile, never above it.
                    (_state.value.screen as? Screen.ChannelVideos)?.let { publishChannel(it.source) }
                    kickScreening(page.videos)
                }
                .onFailure { android.util.Log.w("YosemiteKids", "load more failed", it) }
            loadingMore = false
            _state.value = _state.value.copy(loadingMore = false)
            pumpUntilFilled()
        }
    }

    /**
     * Keep paging until the grid reaches [gridTarget] — a page that yielded
     * nothing the kid can see hasn't answered their scroll, so the next one
     * goes straight out rather than waiting for another swipe.
     *
     * Budgeted per nudge, not per visit: a channel watched right through would
     * otherwise page its whole history in one go, but a kid genuinely scrolling
     * deep gets a fresh allowance every time they ask.
     */
    private fun pumpUntilFilled() {
        val source = (_state.value.screen as? Screen.ChannelVideos)?.source ?: return
        if (_state.value.videos.size < gridTarget &&
            uploadsNextPage != null &&
            pagesThisPump < MAX_PAGES_PER_PUMP
        ) {
            pagesThisPump++
            fetchNextPage()
            return
        }
        // Nothing more coming: what's loaded now is "the first set", and the
        // Watched tile pins to the end of it. Pinning at page 1 instead would
        // strand the tile two tiles from the top of a channel whose recent
        // uploads have all been watched.
        if (_state.value.watchedTileAt == null) publishChannel(source, pin = true)
    }

    /** How much new grid one scroll nudge (or a channel opening) should buy. */
    private var gridTarget = 0

    /** Pages spent since the last nudge — see [pumpUntilFilled]. */
    private var pagesThisPump = 0

    private companion object {
        /** Roughly a grid's worth of tiles on the biggest layout we render. */
        const val SCREENFUL = 12

        /** ~5 upload pages ≈ 150 videos sifted per nudge before we give up. */
        const val MAX_PAGES_PER_PUMP = 5
    }

    /** Hold-to-save: toggles a video in the kid's Favorites. */
    fun toggleWatchlist(item: VideoItem) = toggleSaved(item, watchlistStore, Screen.Watchlist)

    /** Hold-to-save: toggles a video in the kid's Watch later list. */
    fun toggleWatchLater(item: VideoItem) = toggleSaved(item, watchLaterStore, Screen.WatchLater)

    /**
     * Favorites and Watch later behave identically — only the store and the
     * screen mirroring it differ. [ownScreen] is that screen: standing on it,
     * an un-save has to drop the video out of the grid underfoot.
     */
    private fun toggleSaved(item: VideoItem, store: SavedListStore, ownScreen: Screen) {
        val url = item.video.url
        viewModelScope.launch {
            // Store round-trips are file I/O — off-main, per refreshProgress.
            val (urls, listVideos) = withContext(Dispatchers.IO) {
                if (url in savedUrls(ownScreen)) store.remove(url) else store.add(item.video)
                store.urls() to
                    if (_state.value.screen == ownScreen) store.load() else null
            }
            _state.value = _state.value.withSaved(ownScreen, urls)
            if (listVideos != null) {
                rawVideos = listVideos
                _state.value = _state.value.copy(videos = annotated(includeFinished = false), held = heldByScreening())
            }
            syncWatchState() // saves propagate promptly
        }
    }

    private fun savedUrls(ownScreen: Screen): Set<String> =
        if (ownScreen == Screen.WatchLater) _state.value.watchLater else _state.value.watchlisted

    private fun UiState.withSaved(ownScreen: Screen, urls: Set<String>): UiState =
        if (ownScreen == Screen.WatchLater) copy(watchLater = urls) else copy(watchlisted = urls)

    /** Watched-to-the-end videos leave both saved lists automatically. */
    private fun pruneFinishedSavedLists() {
        listOf(watchlistStore, watchLaterStore).forEach { store ->
            store.load()
                .filter { history.progress(it.url)?.isFinished == true }
                .forEach { store.remove(it.url) }
        }
    }

    fun openWatchlist() = openSaved(watchlistStore, Screen.Watchlist)

    fun openWatchLater() = openSaved(watchLaterStore, Screen.WatchLater)

    private fun openSaved(store: SavedListStore, ownScreen: Screen) {
        viewModelScope.launch {
            val videos = withContext(Dispatchers.IO) {
                // Fully-watched entries have served their purpose — drop them silently.
                pruneFinishedSavedLists()
                store.load()
            }
            rawVideos = videos
            feedHandle = null
            uploadsNextPage = null
            _state.value = _state.value
                .withSaved(ownScreen, videos.map { it.url }.toSet())
                .copy(
                    screen = ownScreen,
                    loading = false,
                    error = null,
                    videos = annotated(includeFinished = false),
                    held = heldByScreening()
                )
        }
    }

    /** Tap-to-line-up: toggles a video in the kid's play queue. */
    fun toggleQueue(item: VideoItem) {
        val url = item.video.url
        viewModelScope.launch {
            val (queued, listVideos) = withContext(Dispatchers.IO) {
                if (url in _state.value.queued) {
                    queueStore.remove(url)
                } else {
                    // A refused add (duplicate race or the 50-item cap) just
                    // leaves the badge unflipped — urls() below reads back
                    // what's really stored.
                    queueStore.add(item.video)
                }
                queueStore.urls() to
                    if (_state.value.screen == Screen.Queue) queueStore.load() else null
            }
            _state.value = _state.value.copy(queued = queued)
            if (listVideos != null) {
                rawVideos = listVideos
                _state.value = _state.value.copy(videos = annotated(includeFinished = true), held = heldByScreening())
            }
            // No syncWatchState(): the queue is device-local by design.
        }
    }

    /**
     * Videos that finished *after* they were lined up leave automatically. The
     * comparison against addedAt is the whole point: pruning on watch history
     * alone made a rewatch unqueueable — the kid picks a favourite they've seen,
     * and it vanishes before the lineup is even on screen.
     */
    private fun pruneFinishedQueue() {
        queueStore.entries()
            .filter { it.finishedSinceQueued(history.progress(it.video.url)) }
            .forEach { queueStore.remove(it.video.url) }
    }

    fun openQueue() {
        viewModelScope.launch {
            val videos = withContext(Dispatchers.IO) {
                pruneFinishedQueue()
                queueStore.load()
            }
            rawVideos = videos
            feedHandle = null
            uploadsNextPage = null
            _state.value = _state.value.copy(
                screen = Screen.Queue,
                loading = false,
                error = null,
                queued = videos.map { it.url }.toSet(),
                videos = annotated(includeFinished = true),
                held = heldByScreening()
            )
        }
    }

    fun removeFromQueue(item: VideoItem) {
        viewModelScope.launch {
            val videos = withContext(Dispatchers.IO) {
                queueStore.remove(item.video.url)
                queueStore.load()
            }
            rawVideos = videos
            _state.value = _state.value.copy(
                queued = videos.map { it.url }.toSet(),
                videos = annotated(includeFinished = true),
                held = heldByScreening()
            )
        }
    }

    fun moveQueue(item: VideoItem, delta: Int) {
        viewModelScope.launch {
            val videos = withContext(Dispatchers.IO) {
                queueStore.move(item.video.url, delta)
                queueStore.load()
            }
            rawVideos = videos
            _state.value = _state.value.copy(videos = annotated(includeFinished = true))
        }
    }

    /**
     * Hold-menu "Save offline" row: request a download (parent approves later),
     * or withdraw a request that hasn't been approved yet. Already-downloaded
     * videos ignore it — deleting is the parent's job in settings.
     */
    fun toggleDownload(item: VideoItem) {
        val store = downloadStore ?: return
        val url = item.video.url
        when {
            url in _state.value.downloaded -> return
            url in _state.value.downloadPending -> store.cancelRequest(url)
            else -> {
                // With screening on, the request goes through the AI deep check
                // first and reaches the parent only if it passes — the kid sees
                // the same ⏳ either way.
                store.request(item.video, checking = screener?.config?.enabled == true)
                kickDownloadChecker()
            }
        }
        // The store bumps DownloadEvents.changes, which refreshes the badges.
    }

    /**
     * Works off any CHECKING download requests. Also called once at startup:
     * an app death mid-check must not strand a request in a state neither the
     * parent (not REQUESTED yet) nor the kid (still ⏳) can see resolve.
     */
    private fun kickDownloadChecker() {
        val store = downloadStore ?: return
        val cs = configStore ?: return
        val scr = screener ?: return
        io.yosemitekids.app.data.DownloadChecker.kick(
            viewModelScope, cs, scr.store, store, yt, sourceCache, activeProfileId
        ) { video ->
            // The verdict also hides the video itself — pill first, then the
            // re-filter takes it off the shelf the kid is looking at.
            showNotice("\"${video.title}\" isn't available.")
            reapplyScreening()
        }
    }

    private var noticeJob: kotlinx.coroutines.Job? = null

    // Last, below every property, on purpose: this block starts work that
    // reads the properties above from other threads (publishChannels sorts
    // on IO). Kotlin runs initialisers and init blocks in textual order, so
    // an init placed higher had two bugs: the kid's chip choices it loaded
    // were reset by their own declarations running after it, and the IO
    // thread read channelOrder before its initialiser ran, got null, and
    // the app died at launch. Guard 12 in scripts/check.* keeps it here.
    init {
        // The kid's chips, before the first publish so the rows come up in
        // their order rather than snapping to it a beat later.
        kidPrefs?.let { p ->
            kidChannelSort = p.channelSort()
            kidHomeFilter = p.homeFilter()
            kidChannelFilter = p.channelFilter()
        }
        viewModelScope.launch {
            // All five sets are file reads — off-main like everything else
            // here; the home rows render immediately, badges land a beat later.
            val (saved, dl) = withContext(Dispatchers.IO) {
                Triple(watchlistStore.urls(), watchLaterStore.urls(), queueStore.urls()) to
                    (downloadStore?.pendingUrls().orEmpty() to visibleDownloadUrls())
            }
            val recent = withContext(Dispatchers.IO) { searchHistory?.recent().orEmpty() }
            _state.value = _state.value.copy(
                watchlisted = saved.first,
                watchLater = saved.second,
                queued = saved.third,
                downloadPending = dl.first,
                // Sideloaded local files count as "downloaded": one set drives
                // the ✅ badges, the home tile and the offline auto-open alike.
                downloaded = dl.second,
                recentSearches = recent
            )
            // Car trip / flight: no network but saved videos — open the offline shelf.
            if (isOffline() && dl.second.isNotEmpty()) openDownloads()
        }
        refresh()
        syncWatchState()
        syncConfigState()
        // Requests stranded mid-check by an app death resume where they left off.
        kickDownloadChecker()
        // Approvals, finished downloads and local-library edits update the
        // badges (and the Downloads screen, if it's open) as they happen —
        // LocalLibrary rides the same change signal since it feeds the same shelf.
        viewModelScope.launch {
            DownloadEvents.changes.collect { refreshDownloadState() }
        }
        // A parent's phone just granted time or changed the rules. Keyed
        // per-kid ViewModels all hear it; only the home actually on screen
        // says so, or the kid gets a pill from their sibling's screen.
        viewModelScope.launch {
            io.yosemitekids.app.data.KidNotices.messages.collect {
                if (uiActive) showNotice(it.text)
            }
        }
        // A TV can sit on the home screen for hours — poll for whitelist edits.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(5 * 60 * 1000L)
                // Keyed per-kid ViewModels outlive a profile switch in the
                // activity's store; only the one on screen gets to poll.
                if (!uiActive) continue
                refreshIfIdle()
                syncWatchState()
                syncConfigState()
                syncIndex()
            }
        }
        // The deep crawl lives in IndexCrawlWorker (WorkManager) — it runs even
        // with the app closed, so no in-app loop here.
    }

    /** Shows the kid one transient line, replacing any previous one. */
    /** A screen's own transient line (voice search unavailable, and the like). */
    fun showNoticeExternal(text: String) = showNotice(text)

    private fun showNotice(text: String) {
        noticeJob?.cancel()
        _state.value = _state.value.copy(notice = text)
        noticeJob = viewModelScope.launch {
            kotlinx.coroutines.delay(5_000)
            _state.value = _state.value.copy(notice = null)
        }
    }

    /**
     * The offline shelf: finished downloads plus parent-sideloaded local files.
     * No screener/blocklist re-filtering here: everything on it was explicitly
     * approved (or added) by the parent, and it must work with no network.
     */
    fun openDownloads() {
        if (downloadStore == null && localLibrary == null) return
        viewModelScope.launch {
            val (videos, pending) = withContext(Dispatchers.IO) {
                visibleDownloads() to downloadStore?.pendingUrls().orEmpty()
            }
            rawVideos = videos
            feedHandle = null
            uploadsNextPage = null
            _state.value = _state.value.copy(
                screen = Screen.Downloads,
                loading = false,
                error = null,
                downloadPending = pending,
                downloaded = videos.map { it.url }.toSet(),
                videos = downloadItems()
            )
        }
    }

    private fun downloadItems(): List<VideoItem> =
        rawVideos.map { VideoItem(it, history.progress(it.url)?.fraction) }

    private fun refreshDownloadState() {
        if (downloadStore == null && localLibrary == null) return
        viewModelScope.launch {
            val (pending, downloads) = withContext(Dispatchers.IO) {
                downloadStore?.pendingUrls().orEmpty() to visibleDownloads()
            }
            _state.value = _state.value.copy(
                downloadPending = pending,
                downloaded = downloads.map { it.url }.toSet()
            )
            if (_state.value.screen == Screen.Downloads) {
                rawVideos = downloads
                _state.value = _state.value.copy(videos = downloadItems())
            }
        }
    }

    /**
     * Long-press on a Keep watching tile: mark the video fully watched so it
     * leaves the row (and the channel grids treat it like any finished video).
     */
    fun dismissKeepWatching(item: VideoItem) {
        viewModelScope.launch {
            // history.save commits (fsync) and the row rebuild reads every cache
            // file — both off-main.
            val keepWatching = withContext(Dispatchers.IO) {
                history.progress(item.video.url)?.let {
                    history.save(item.video.url, it.durationMs, it.durationMs)
                }
                keepWatchingRow()
            }
            _state.value = _state.value.copy(keepWatching = keepWatching)
            syncWatchState() // the dismissal propagates like any other watch progress
        }
    }

    /**
     * Hold menu: mark a video watched, or put it back. Unlike finishing one in
     * the player, this moves the tile to the Watched shelf straight away — the
     * kid asked for it, and a tile that stayed put would read as the tap having
     * missed.
     */
    fun toggleWatched(item: VideoItem) {
        val url = item.video.url
        viewModelScope.launch {
            // history.save commits (fsync) — off-main like every store write.
            val nowWatched = withContext(Dispatchers.IO) {
                val existing = history.progress(url)
                // save() ignores a zero duration, and a video that was never
                // opened has no stored one; the feed's own length covers that,
                // and a nominal second covers a live or unknown-length entry.
                val duration = existing?.durationMs?.takeIf { it > 0 }
                    ?: (item.video.durationSeconds * 1000).takeIf { it > 0 }
                    ?: 1_000L
                val watched = existing?.isFinished == true
                // Rewound, not deleted: a removal loses the next sync to any
                // device that still holds the entry, while 0/duration carries a
                // fresh timestamp and wins the merge everywhere.
                history.save(url, if (watched) 0L else duration, duration)
                !watched
            }
            finishedOnArrival[url] = nowWatched
            val channel = channelSource()
            if (channel != null) {
                publishChannel(channel)
                _state.value = _state.value.copy(
                    keepWatching = withContext(Dispatchers.IO) { keepWatchingRow() }
                )
            } else {
                // Every other shelf: same rebuild as returning from the player,
                // so the red bar (and Surprise's hiding of finished videos)
                // follows immediately.
                refreshProgress()
            }
            syncWatchState() // it propagates like any other watch progress
        }
    }

    fun goHome() {
        playlistParent = null
        rawVideos = emptyList()
        feedHandle = null
        uploadsNextPage = null
        finishedOnArrival.clear()
        gridTarget = 0
        pagesThisPump = 0
        _state.value = _state.value.copy(
            screen = Screen.Home, videos = emptyList(), held = 0, loading = false, error = null,
            channelWatched = emptyList(), watchedTileAt = null, scrollTo = null,
            // Re-rank right away so a freshly opened channel climbs immediately.
            channels = sortByUsage(_state.value.channels)
        )
        // Called synchronously from BackHandler — the rows re-read every source's
        // cache file, so they refresh off-main after the screen has switched.
        viewModelScope.launch {
            val (keepWatching, badges) = withContext(Dispatchers.IO) {
                keepWatchingRow() to computeNewBadges()
            }
            _state.value = _state.value.copy(keepWatching = keepWatching, newBadges = badges)
        }
    }
}
