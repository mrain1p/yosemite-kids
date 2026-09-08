package io.yosemitekids.app.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.yosemitekids.app.data.AiScreener
import io.yosemitekids.app.data.NowPlaying
import io.yosemitekids.app.data.RemotePlayerControl
import io.yosemitekids.app.data.SessionGuard
import io.yosemitekids.app.data.WatchHistoryStore
import io.yosemitekids.app.data.YouTubeRepository
import io.yosemitekids.app.data.listenDrainPercent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VIDEO_URL = "video_url"
        /** Optional: ordered page-URLs to auto-advance through (playlist sources). */
        const val EXTRA_QUEUE = "queue"
        const val EXTRA_INDEX = "index"
        /**
         * Optional, parallel to EXTRA_QUEUE: channel name and drain percent per
         * item. A hand-built queue crosses channels, so one launch-wide value
         * would credit every minute to the first channel and bill it at the
         * first channel's rate. Absent (playlist/single launches) → the single
         * EXTRA_CHANNEL / EXTRA_TIME_PERCENT applies to every item, as before.
         */
        const val EXTRA_QUEUE_CHANNELS = "queue_channels"
        const val EXTRA_QUEUE_PERCENTS = "queue_percents"
        /**
         * Optional, parallel to EXTRA_QUEUE: display title and poster per item.
         * Only the end-of-video "Up next" card reads them — it shows what's
         * coming before it plays, which a bare URL can't. Absent → the card
         * still counts down, just without a name or picture.
         */
        const val EXTRA_QUEUE_TITLES = "queue_titles"
        const val EXTRA_QUEUE_THUMBS = "queue_thumbs"
        /** Parallel to EXTRA_QUEUE: length in seconds (the heart saves a whole Video). */
        const val EXTRA_QUEUE_DURATIONS = "queue_durations"
        /** The channel's avatar, for the overlay's channel button. */
        const val EXTRA_CHANNEL_AVATAR = "channel_avatar"
        /** How long the end-of-video card waits before auto-advancing / leaving. */
        const val UP_NEXT_SECONDS = 6
        const val END_CARD_SECONDS = 12
        /** True when EXTRA_QUEUE came from the kid's QueueStore: items that
         *  truly finish are removed there, so the queue self-clears. */
        const val EXTRA_FROM_QUEUE = "from_queue"
        const val EXTRA_CHANNEL = "channel"
        /** Screen-time drain rate for this launch, percent (100 normal, 0 FREE). */
        const val EXTRA_TIME_PERCENT = "time_percent"
        /**
         * Store suffix of the kid this playback belongs to, resolved by the
         * launching screen. The launcher is the only place with the full
         * resolution rules (device assignment beats a remembered pick) — the
         * player re-deriving it from device-local state is how a dedicated TV
         * once enforced a stale kid's rules and wrote history into their stores.
         */
        const val EXTRA_PROFILE_SUFFIX = "profile_suffix"
        /**
         * The kid's profile id, resolved by the launcher alongside the suffix.
         * The pre-play deep check needs the *id* (per-kid verdicts and allow
         * overrides key on it); the suffix is a storage namespace and can't be
         * mapped back. Absent → fail-closed on the strictest per-kid verdict.
         */
        const val EXTRA_PROFILE_ID = "profile_id"

        /** The PiP window's play/pause button talks back through this broadcast. */
        private const val PIP_ACTION = "io.yosemitekids.app.PIP_CONTROL"
        private const val PIP_EXTRA_PLAY = "play"
        /**
         * The one player that is up. A second launch — a tap on the shelf while
         * the first floats in its picture-in-picture window, or a LAN /play —
         * replaces it instead of playing over it. Weak on purpose: a reference
         * here must never keep a finished activity alive.
         */
        private var live: java.lang.ref.WeakReference<PlayerActivity>? = null
    }

    private var player: ExoPlayer? = null
    private lateinit var history: WatchHistoryStore
    private lateinit var sessionGuard: SessionGuard
    private lateinit var channelUsage: io.yosemitekids.app.data.ChannelUsage
    private lateinit var repo: YouTubeRepository
    private lateinit var downloads: io.yosemitekids.app.data.DownloadStore
    private lateinit var localLibrary: io.yosemitekids.app.data.LocalLibrary
    private var queue: List<String> = emptyList()
    private var queueChannels: List<String> = emptyList()
    private var queuePercents: IntArray? = null
    private var queueTitles: List<String> = emptyList()
    private var queueThumbs: List<String> = emptyList()
    private var queueDurations: List<Long> = emptyList()
    /** The kid's Favorites, for the heart in the overlay. */
    private lateinit var favorites: io.yosemitekids.app.data.SavedListStore
    private val isFavorite = mutableStateOf(false)
    /** Timestamp of the last heart tap, for the big ❤️ that pops mid-screen. */
    private val heartBurst = mutableStateOf(0L)
    /** The current channel's avatar and whitelist id (resolved by name, off-main). */
    private val channelAvatar = mutableStateOf<String?>(null)
    @Volatile
    private var channelSourceId: String? = null
    /** "Stop after this one" — the moon button. Wins over autoplay and lineups. */
    private val stopAfterThis = mutableStateOf(false)
    /**
     * The parent's autoplay switch, read with the config. State, because the
     * overlay's Autoplay pill shows it — READ-ONLY there: a kid writing a
     * parent's setting from the player would be a policy change, so the
     * kid's own lever stays the moon ("stop after this one").
     */
    private val autoplayState = mutableStateOf(true)
    private var autoplayOn: Boolean
        get() = autoplayState.value
        set(value) { autoplayState.value = value }
    /**
     * Non-null only for EXTRA_FROM_QUEUE launches: the lineup the kid is
     * playing *is* their Up next, and a video that truly finishes leaves it.
     * Deliberately not shared with the Queue tile's adds — that opens its
     * own store instance, because making this one unconditional would make
     * every launch remove finished videos from the queue, which is a change
     * in what "from the queue" means.
     */
    private var queueStore: io.yosemitekids.app.data.QueueStore? = null
    /** The kid's storage namespace, for the lists the tiles under the video write to. */
    private var profileSuffix: String = ""
    /** Watch later, for the tile under the video and the TV toolbar slot. */
    private lateinit var watchLater: io.yosemitekids.app.data.SavedListStore
    private val inWatchLater = mutableStateOf(false)
    private val inQueue = mutableStateOf(false)
    /** "Similar": title-matched across every channel, empty when the parent's switch is off. */
    private val similar = mutableStateOf<List<io.yosemitekids.app.data.Video>>(emptyList())
    /** Upload time of the playing video, from the cache row, for the release-time line. */
    private val currentPublishedAt = mutableStateOf<Long?>(null)
    /** How many of the channel's videos the cache holds, for the channel card's meta line. */
    private val channelVideoCount = mutableIntStateOf(0)
    /**
     * The kid's chosen look, for the phone's content column. The stage keeps
     * the dark scheme regardless — a video surface is black in every look —
     * but the page under it follows the kid like every other screen does.
     */
    private val kidScheme = mutableStateOf<androidx.compose.material3.ColorScheme?>(null)
    private var timePercent = 100
    private var currentPageUrl: String? = null
    private var currentTitle: String = ""
    private var currentChannel: String = ""
    private var currentPlayback: YouTubeRepository.Playback? = null
    private var isTv = false
    /** Non-null once a screen-time rule fires mid-playback. */
    private val timeUpMessage = mutableStateOf<String?>(null)
    /** True while ExoPlayer waits for data (initial buffer, seek, stall). */
    private val buffering = mutableStateOf(false)
    /** Controls overlay stays visible until this timestamp (poked by keys and taps). */
    private val controlsVisibleUntil = mutableStateOf(0L)
    /** Mirrors ExoPlayer.playWhenReady: drives the play/pause glyph and keeps
     *  the controls up while paused — a frozen frame with nothing on it reads
     *  as "broken" to a kid. */
    private val wantsPlay = mutableStateOf(true)
    /**
     * The end-of-video card: what plays next (or nothing, on the last video)
     * and how many seconds until it does so on its own. Hoisted like the queue
     * index: the countdown must keep running with the screen off in listen mode.
     */
    private val endCard = mutableStateOf<EndCard?>(null)
    /** Which of the two end-card buttons the TV remote is on (0 = primary). */
    private val endCardCursor = mutableIntStateOf(0)
    /** Which of the two error-screen buttons the TV remote is on. */
    private val errorCursor = mutableIntStateOf(0)
    private var endCardJob: kotlinx.coroutines.Job? = null
    /**
     * The daily countdown's last authoritative read (see PlayerCountdown.kt).
     * Re-seeded whenever the drain rate can have changed — a fresh video, a
     * flip of listen mode, a parent's grant — and on every 5-second tick;
     * aged by interpolation in between, never re-read faster.
     */
    private val countdownAnchor = mutableStateOf<CountdownAnchor?>(null)
    /** What the chip draws this second; null hides it. Written by the root ticker. */
    private val countdownFrame = mutableStateOf<CountdownFrame?>(null)
    /** Real playback so far, for ageing a budget between reads. */
    private val playClock = PlayClock { SystemClock.elapsedRealtime() }
    /** A double-tap seek just happened: signed seconds + timestamp, for the ripple label. */
    private val seekFeedback = mutableStateOf<Pair<Int, Long>?>(null)
    /** Last time a held ◀/▶ key repeat was allowed to seek (see onKeyDown). */
    private var lastHeldSeekAt = 0L
    /** Transient top-of-screen pill: time-left warnings, subtitles toggled, … */
    private val notice = mutableStateOf<Notice?>(null)
    /**
     * The single one-minute moment already had; re-armed when time is granted
     * back. The five-minute pill it used to sit beside is the countdown chip
     * now, which stays up for the whole of the last five minutes.
     */
    private var warnedOneMinute = false
    /** Kid's sticky captions choice (survives across videos and app runs). */
    private var captionsOn = false
    private var currentSubtitles: List<YouTubeRepository.Subtitle> = emptyList()
    private val trackPanel = mutableStateOf(TvTrackPanel.Hidden)
    private val trackCursor = mutableIntStateOf(0)
    private val selectedAudioTrack = mutableIntStateOf(0)
    private val selectedSubtitleTrack = mutableIntStateOf(-1)
    /** Community-marked promo stretches for the current video (see SponsorBlock). */
    private val sponsorSegments =
        mutableStateOf<List<io.yosemitekids.app.data.SponsorBlock.Segment>>(emptyList())
    /** Parent's SponsorBlock switch, read off-main on first use; null = not read yet. */
    private var sponsorSkipOn: Boolean? = null

    /** The quality ceiling in force, for the player's own picker; null = Auto. */
    private val qualityCeiling = mutableStateOf<Int?>(null)
    private val qualityPickerOpen = mutableStateOf(false)
    /** The parent's "show when a video came out" switch, for the list under the video. */
    private val showVideoAge = mutableStateOf(false)

    /**
     * The kid picked a quality in the player: re-resolve this video at the new
     * ceiling and carry on from where they were. Applies to whatever plays
     * next too (the ceiling is global for the session), but it is not written
     * to the config — the parent's setting is the default, not the memory.
     */
    private fun setQuality(height: Int?) {
        if (io.yosemitekids.app.data.QualityTargets.userMaxHeight == height) return
        io.yosemitekids.app.data.QualityTargets.userMaxHeight = height
        qualityCeiling.value = height
        haptic()
        notice.value = Notice("Quality: ${io.yosemitekids.app.data.qualityLabel(height)}")
        val exo = player ?: return
        val at = exo.currentPosition
        val page = currentPageUrl ?: return
        val playing = exo.playWhenReady
        lifecycleScope.launch {
            val pb = runCatching {
                repo.resolvePlayback(page, io.yosemitekids.app.data.QualityTargets.effectiveMaxHeight())
            }.getOrNull() ?: return@launch
            // The kid may have moved on while the streams resolved.
            if (currentPageUrl != page) return@launch
            currentPlayback = pb
            playbackState.value = pb
            attachSources(pb, audioOnly = listenActive && pb.audioUrl != null, resumeMs = at)
            player?.playWhenReady = playing
        }
    }

    /** True while the system has the video in its picture-in-picture window (phones). */
    private val inPip = mutableStateOf(false)
    /**
     * Phone held upright: the video sits at the top with the lineup below it,
     * YouTube-style; landscape is the full-screen stage. TVs never leave the
     * stage, and neither does the PiP window (its shape is the video's).
     */
    private val portraitLayout = mutableStateOf(false)
    /** "More from <channel>" under the portrait video: autoplay's own candidates. */
    private val moreFromChannel = mutableStateOf<List<io.yosemitekids.app.data.Video>>(emptyList())
    /** Which list is open under the portrait video; null = the first tab that has something. */
    private val portraitTab = mutableStateOf<PlayerTab?>(null)
    /** Where the video is drawn on screen, for the shrink-to-PiP animation. */
    private var videoBounds: android.graphics.Rect? = null
    /**
     * The mounted video view, for the swipe-down gesture to move while a
     * finger is on it. Held as a plain reference and not as state: a drag
     * writes View properties every frame and must never recompose the stage.
     */
    private var stageView: PlayerView? = null
    /** Live only while a ⛶ press has the orientation forced; see [forceOrientation]. */
    private var orientationListener: android.view.OrientationEventListener? = null
    private var pipReceiver: android.content.BroadcastReceiver? = null

    // Queue position, resolved streams, and terminal error, hoisted out of the
    // composition. Deliberate: recomposition needs display frames, which stop
    // dead when the screen is off — a LaunchedEffect-driven advance would
    // stall a listen-mode playlist at the end of its first video until the
    // screen came back. Activity-level functions + lifecycleScope (a plain
    // main-looper dispatcher) keep working in the dark; composition just
    // renders whatever these say when frames exist.
    private val indexState = mutableIntStateOf(0)
    private val playbackState = mutableStateOf<YouTubeRepository.Playback?>(null)
    private val errorState = mutableStateOf<String?>(null)
    /** Kid the deep check judges for — see [EXTRA_PROFILE_ID]. */
    private var gateProfileId: String? = null
    /** Family config for the gate (AI settings, overrides, kids), loaded off-main once. */
    private var familyConfig: io.yosemitekids.app.data.Whitelist? = null

    /** Identity token for [RemotePlayerControl.owner]; see onDestroy. */
    private val remoteToken = Any()
    /** Channel name → parent's channel note, resolved once alongside the config. */
    private var channelNotes: Map<String, String>? = null
    private val screeningStore by lazy { io.yosemitekids.app.data.ScreeningStore(this) }
    /** True while the pre-play deep check is talking to the AI ("Checking this one…"). */
    private val deepChecking = mutableStateOf(false)
    /** Non-null once the deep check said no: the gentle screen before going back. */
    private val blockedGently = mutableStateOf<String?>(null)
    /** Latches on the first resolved video (see the PlayerView comment below). */
    private val everPlayed = mutableStateOf(false)
    private var resolveJob: kotlinx.coroutines.Job? = null
    private var sponsorJob: kotlinx.coroutines.Job? = null

    /**
     * Family screen-off listening rate; null = feature off (default), which
     * keeps the old behavior: locking the phone pauses. Loaded off-main once,
     * phones only.
     */
    private var listenPercent: Int? = null
    /**
     * True while playback is sound-only: the screen went dark, the kid switched
     * apps, or a "Allow listening" window is on ([listenOnlyWindow]). Drives the
     * audio-only stream swap and the listening drain rate.
     *
     * State-backed so the countdown can gate off while listening, and the
     * setter re-seeds the countdown: the drain rate is different on the other
     * side of this flip, and the old anchor would keep ageing the old rate.
     */
    private val listeningState = mutableStateOf(false)
    private var listenActive: Boolean
        get() = listeningState.value
        set(value) {
            if (listeningState.value == value) return
            listeningState.value = value
            reseedCountdown()
        }
    /**
     * True while a window marked "Allow listening" is blocking watching. Unlike
     * the screen-off kind, coming back to the player must NOT restore the
     * picture — bedtime is still on — so this pins listening on until the
     * window ends.
     */
    private var listenOnlyWindow = false
    /** Kid-facing line while a window has playback down to sound only. */
    private val listenOnlyMessage = mutableStateOf<String?>(null)

    private fun pokeControls() {
        // Touch gets a beat longer: a finger has to travel to the button it
        // just revealed, a remote press already sits on the right key.
        controlsVisibleUntil.value = System.currentTimeMillis() + if (isTv) 3_000 else 4_000
    }

    private fun hideControls() {
        controlsVisibleUntil.value = 0L
    }

    /** A little "got it" under the thumb — phones only, TVs have nothing to buzz. */
    private fun haptic() {
        if (isTv) return
        window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /**
     * Android's answer to prefers-reduced-motion: Accessibility → Remove
     * animations, and Developer options' animator scale, both land on this
     * one number, and zero means somebody asked for no motion. Read at the
     * moment it matters rather than cached — a parent turning it on for a
     * child who is dizzy should not have to restart the video — which a
     * single Settings.Global read on a finger-lift can afford.
     */
    private fun animationsEnabled(): Boolean =
        android.provider.Settings.Global.getFloat(
            contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) != 0f

    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.playWhenReady) exo.pause() else exo.play()
        haptic()
        pokeControls()
    }

    /** ±seconds from the playhead, clamped, with the double-tap label. */
    private fun seekBy(deltaSeconds: Int, showFeedback: Boolean) {
        val exo = player ?: return
        val target = (exo.currentPosition + deltaSeconds * 1_000L)
            .coerceIn(0L, exo.duration.coerceAtLeast(0L))
        exo.seekTo(target)
        if (showFeedback) {
            seekFeedback.value = deltaSeconds to System.currentTimeMillis()
            haptic()
        }
        pokeControls()
    }

    /** Neighbour in the lineup; false when there is none that way. */
    private fun stepQueue(direction: Int): Boolean {
        val next = indexState.intValue + direction
        if (next !in queue.indices) return false
        dismissEndCard()
        playIndex(next)
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        DeviceShape.seed(this)
        isTv = DeviceShape.current.isTv
        live?.get()?.takeIf { it !== this && !it.isFinishing }?.finish()
        live = java.lang.ref.WeakReference(this)
        if (!isTv) {
            // The player opens the way the phone is held, rotation lock
            // permitting (USER, not SENSOR): upright is the portrait layout,
            // sideways the edge-to-edge stage. ⛶ forces a turn on demand —
            // see forceOrientation. The stage draws under the cutout; the
            // portrait layout pads itself off the system bars instead.
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
        applyLayoutFor(resources.configuration)

        // Progress, screen time and channel minutes all belong to the kid the
        // launching screen resolved — delivered in the intent, never re-derived
        // here. Fallback (extra absent should be impossible; the activity isn't
        // exported): the mirrored active pick, which MainActivity keeps current.
        val profileSuffix = intent.getStringExtra(EXTRA_PROFILE_SUFFIX) ?: run {
            val config = io.yosemitekids.app.data.ConfigStore(this).load()
            // Membership-checked: a remembered pick can name a since-deleted kid,
            // and suffixFor would silently mint an orphan namespace for it.
            val active = io.yosemitekids.app.data.ActiveProfileStore(this).activeId()
                ?.takeIf { config.profile(it) != null }
            io.yosemitekids.app.data.ProfileNamespace(this).suffixFor(active)
        }
        this.profileSuffix = profileSuffix
        gateProfileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        channelUsage = io.yosemitekids.app.data.ChannelUsage(this, profileSuffix)
        currentChannel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty()
        queue = intent.getStringArrayListExtra(EXTRA_QUEUE)
            ?: listOfNotNull(intent.getStringExtra(EXTRA_VIDEO_URL))
        if (queue.isEmpty()) {
            finish(); return
        }
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, queue.lastIndex)
        queueChannels = intent.getStringArrayListExtra(EXTRA_QUEUE_CHANNELS).orEmpty()
        queuePercents = intent.getIntArrayExtra(EXTRA_QUEUE_PERCENTS)
        queueTitles = intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES).orEmpty()
        queueThumbs = intent.getStringArrayListExtra(EXTRA_QUEUE_THUMBS).orEmpty()
        queueDurations = intent.getLongArrayExtra(EXTRA_QUEUE_DURATIONS)?.toList().orEmpty()
        channelAvatar.value = intent.getStringExtra(EXTRA_CHANNEL_AVATAR)
        favorites = io.yosemitekids.app.data.SavedListStore(this, profileSuffix)
        watchLater = io.yosemitekids.app.data.SavedListStore(
            this, profileSuffix, io.yosemitekids.app.data.SavedListStore.WATCH_LATER
        )
        if (intent.getBooleanExtra(EXTRA_FROM_QUEUE, false)) {
            queueStore = io.yosemitekids.app.data.QueueStore(this, profileSuffix)
        }

        history = WatchHistoryStore(this, profileSuffix)
        sessionGuard = SessionGuard(this, profileSuffix)
        repo = YouTubeRepository()
        downloads = io.yosemitekids.app.data.DownloadStore(this)
        localLibrary = io.yosemitekids.app.data.LocalLibrary(this)
        // Per-item when the parallel array is present; the gate below judges
        // the *starting* item's rate (matching single launches — later items
        // are enforced by the 5-second tick, same as any mid-playback change).
        timePercent = (queuePercents?.getOrNull(startIndex)
            ?: intent.getIntExtra(EXTRA_TIME_PERCENT, 100)).coerceIn(0, 400)

        // Screen-time rules: blocked before we even build the player.
        sessionGuard.checkStart(timePercent)?.let { reason ->
            // A window marked "Allow listening" refuses the picture, not the
            // story — start sound-only instead of showing the block screen.
            // Only when listening clears *every* rule: an exhausted budget or a
            // break lock still stops the story. TVs are out (no playing with
            // the panel off) and so is an unset family rate, which means the
            // feature doesn't exist for this family. The config read is on the
            // main thread here, but only on this path, and its answer decides
            // what to show next — same read the profile fallback above does.
            val listenThrough = !isTv &&
                sessionGuard.checkStart(timePercent, listening = true) == null &&
                io.yosemitekids.app.data.ConfigStore(this).load().listenPercent
                    ?.also { listenPercent = it } != null
            if (!listenThrough) {
                showBlockedScreen(reason)
                return
            }
            // Flags now, service once the player exists (see below): the whole
            // startup path from here on reads listenActive to stay audio-only.
            listenActive = true
            listenOnlyWindow = true
            listenOnlyMessage.value = reason
        }

        captionsOn = getSharedPreferences("player", MODE_PRIVATE).getBoolean("captions", false)

        // The family config, once, off-main (file read + JSON parse): the
        // autoplay switch, and on phones the listening rate. Racing the first
        // power-button press is theoretical (this finishes in ms), and losing
        // the race just means that one lock pauses like the feature was off.
        lifecycleScope.launch(Dispatchers.IO) {
            val cfg = io.yosemitekids.app.data.ConfigStore(this@PlayerActivity).load()
            familyConfig = cfg
            autoplayOn = cfg.autoplayNext
            if (!isTv && listenPercent == null) listenPercent = cfg.listenPercent
            // The parent's ceiling for this form factor. Read before the first
            // resolve in practice (a file read beats a network fetch), and the
            // kid's own pick in the player overrides it for the session.
            io.yosemitekids.app.data.QualityTargets.userMaxHeight =
                if (isTv) cfg.qualityTv else cfg.qualityPhone
            qualityCeiling.value = io.yosemitekids.app.data.QualityTargets.userMaxHeight
            showVideoAge.value = cfg.showVideoAge
            // The look the kid picked, resolved the way MainActivity resolves
            // it: their profile's colour, their device-local theme choice.
            kidScheme.value = kidColorScheme(
                cfg.profile(gateProfileId),
                io.yosemitekids.app.data.KidPrefs(this@PlayerActivity, profileSuffix).theme()
            )
        }

        // Read further ahead than the 50s default: with the chunked data source
        // the network can outrun playback, so minutes of buffer absorb Wi-Fi
        // dips instead of stalling. Back-buffer keeps 30s behind the playhead
        // so the kid's favourite "watch that again" ±10s hops don't refetch.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                50_000, 300_000,
                androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                androidx.media3.exoplayer.DefaultLoadControl
                    .DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setBackBuffer(30_000, false)
            // Hard byte ceiling, and not optional: the time-based window above
            // is only a request, and media3's default video target is 128 MB.
            // A Chromecast gives us a 256 MB heap that the browse screen's
            // thumbnails have already eaten ~120 MB of, so an unbounded
            // 5-minute window on a high-bitrate stream would OOM. 48 MB is
            // several minutes at the bitrates these streams actually run at.
            .setTargetBufferBytes(48 * 1024 * 1024)
            .build()
        player = ExoPlayer.Builder(this).setLoadControl(loadControl)
            .apply {
                if (!isTv) {
                    // Phone niceties, and listen mode's life support: the wake
                    // mode holds CPU + Wi-Fi while playing with the screen off
                    // (without it, doze starves the stream mid-song). Focus
                    // handling and becoming-noisy make it behave like a music
                    // app around calls and unplugged headphones. TVs keep the
                    // exact pre-listen behavior — nothing here helps a TV.
                    setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
                    setAudioAttributes(
                        androidx.media3.common.AudioAttributes.Builder()
                            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        /* handleAudioFocus = */ true
                    )
                    setHandleAudioBecomingNoisy(true)
                }
            }
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        buffering.value = playbackState == Player.STATE_BUFFERING
                        if (playbackState == Player.STATE_ENDED) {
                            // Mark fully watched, then move on (playlist) or stay (single).
                            // The store commits (fsync, deliberate) — off-main.
                            currentPageUrl?.let { url ->
                                val dur = duration
                                lifecycleScope.launch(Dispatchers.IO) {
                                    history.save(url, dur, dur)
                                    // Queue launches self-clear: only a video that
                                    // truly finished leaves the lineup. Written here
                                    // (not by the home screen) because with the
                                    // screen off in listen mode, this is the only
                                    // code still running.
                                    queueStore?.remove(url)
                                }
                            }
                            showEndCard()
                        }
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        wantsPlay.value = playWhenReady
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // isPlaying, not playWhenReady: a buffering stall spends
                        // no budget, and the countdown must not say it did.
                        playClock.setPlaying(isPlaying)
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        // The PiP window takes the video's shape, so a 4:3 or
                        // vertical video must not float in a 16:9 frame.
                        refreshPipParams()
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        // Never leave a frozen screen: skip ahead (playlist) or say why.
                        onPlaybackFailed(error.message ?: error.errorCodeName)
                    }
                })
            }

        // Launched into an "Allow listening" window: the player exists now, so
        // the notification and the screen-off handover can be armed.
        if (listenOnlyWindow) armListenOnly()

        // Back while a video plays shrinks it into the picture-in-picture
        // window (the YouTube reflex) rather than stopping it; paused, ended
        // or on a card, Back leaves as it always did. TVs never enter PiP, so
        // there Back is plain finish.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!enterPip()) finish()
            }
        })
        if (pipSupported()) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                    if (intent.action != PIP_ACTION) return
                    val exo = player ?: return
                    if (intent.getBooleanExtra(PIP_EXTRA_PLAY, true)) exo.play() else exo.pause()
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this, receiver, android.content.IntentFilter(PIP_ACTION),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            pipReceiver = receiver
        }

        // Parent's phone can pause/resume via the LAN server ("come to dinner").
        RemotePlayerControl.owner = remoteToken
        RemotePlayerControl.handler = handler@{ cmd ->
            if (player == null || timeUpMessage.value != null) return@handler false
            runOnUiThread {
                val exo = player ?: return@runOnUiThread
                when (cmd) {
                    "pause" -> exo.pause()
                    "play" -> exo.play()
                }
                // Publish right away so the phone's next stats poll sees the change.
                if (exo.duration > 0) {
                    NowPlaying.update(
                        currentTitle, currentChannel,
                        exo.currentPosition, exo.duration, exo.isPlaying
                    )
                }
                if (isTv) pokeControls()
            }
            true
        }

        // Auto-skip the promo stretches SponsorBlock knows about. Deliberately
        // silent — the jump is the whole feature, no toast. Polled at 500ms:
        // at most half a second of a sponsor read plays before the seek, and a
        // position read this light is free next to the 5s stats tick below.
        lifecycleScope.launch {
            while (isActive) {
                delay(500)
                val exo = player ?: continue
                if (!exo.isPlaying) continue
                val pos = exo.currentPosition
                // 300ms tail guard: landing exactly on endMs must not re-match
                // on the next tick while the seek is still settling.
                sponsorSegments.value
                    .firstOrNull { pos >= it.startMs && pos < it.endMs - 300 }
                    ?.let { exo.seekTo(it.endMs) }
            }
        }

        // A parent's grant or rules edit landing mid-video. Worth interrupting
        // for: extra minutes arriving silently look like the countdown warning
        // was wrong, and a rules change here is what stops the film.
        lifecycleScope.launch {
            io.yosemitekids.app.data.KidNotices.messages.collect {
                notice.value = Notice(it.text)
                // Minutes granted mid-video: the countdown re-reads now rather
                // than ageing the old figure for up to five more seconds, and
                // the one-minute moment can happen again if it comes to that.
                warnedOneMinute = false
                reseedCountdown()
            }
        }

        // Persist progress and enforce screen-time rules every 5s while playing.
        lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                saveProgress()
                // A window can open or close mid-story: bedtime arriving drops
                // the picture instead of stopping the video, and morning gives
                // it back. Checked whether or not playback is running, so a
                // paused story is in the right mode when it resumes.
                syncListenOnlyWindow()
                // The countdown's authoritative read, ABOVE the player check
                // below: during the pre-play deep check nothing plays, but a
                // bedtime window can still close, and a number that froze
                // there looked right in every screenshot taken after a tap.
                readCountdownAnchor()
                val exo = player ?: continue
                // Publish now-playing for the parent's stats screen.
                if (exo.duration > 0) {
                    io.yosemitekids.app.data.NowPlaying.update(
                        currentTitle, currentChannel,
                        exo.currentPosition, exo.duration, exo.isPlaying
                    )
                }
                if (exo.isPlaying && timeUpMessage.value == null) {
                    // Stats record real watch time; the budget drains at the
                    // source's multiplier (exact integer ms — 25% of 5s = 1250ms),
                    // further scaled by the family listening rate while the
                    // screen is off.
                    val drain = currentDrain()
                    channelUsage.addSeconds(currentChannel, 5)
                    sessionGuard.tick(5_000L * drain / 100, listenActive, multiplierPercent = drain)?.let { reason ->
                        exo.pause()
                        timeUpMessage.value = reason
                        countdownAnchor.value = null
                        delay(6_000)
                        finish()
                    }
                    // Re-read after the tick's write so the chip reflects it
                    // now, and take the one-minute moment from the same read:
                    // one wall-clock minute before the budget runs out at the
                    // current drain rate (on FREE sources only an approaching
                    // bedtime counts down). A grant that lifts it re-arms it.
                    if (timeUpMessage.value == null) {
                        val left = readCountdownAnchor()?.minOfOrNull { it.ms }
                        if (left == null || left > 60_000L) warnedOneMinute = false
                        else if (!warnedOneMinute) {
                            warnedOneMinute = true
                            notice.value = Notice("1 minute left! ⏳")
                        }
                    }
                }
            }
        }

        setContent {
            // The kid's look for the page under the video (the portrait column
            // and the quality dialog); the stage below re-asserts the dark
            // scheme for itself, because a video surface is black in every look.
            val scheme = kidScheme.value ?: YosemiteDarkColors
            MaterialTheme(colorScheme = scheme, typography = YosemiteTypography) {
                val pip by inPip
                val portrait by portraitLayout
                // The PiP parameters (auto-enter, the play/pause button) follow
                // the same state pipEligible reads, so recomposition is the
                // one place that keeps them current.
                val eligible = pipEligible()
                LaunchedEffect(eligible) { refreshPipParams() }
                // One clock for the countdown, above both layouts, so the
                // portrait column and the full-bleed stage draw the same
                // second. Gated off while listening (see PlayerCountdown.kt).
                PlayerCountdownTicker(
                    anchor = countdownAnchor,
                    listening = listeningState,
                    now = { SystemClock.elapsedRealtime() },
                    playedMs = { playClock.playedMs() },
                    videoLeftMs = {
                        player?.let { p ->
                            val d = p.duration
                            if (d > 0) (d - p.currentPosition).coerceAtLeast(0) else null
                        }
                    },
                    into = countdownFrame
                )
                // One stage, *moved* between the two layouts rather than
                // rebuilt: a rotation would otherwise recreate the AndroidView
                // and its SurfaceView, and cut to black mid-video. The dark
                // scheme is applied inside the movable content so the stage
                // looks the same in both layouts whatever the page's look.
                val stage = remember {
                    movableContentOf { compact: Boolean ->
                        MaterialTheme(colorScheme = YosemiteDarkColors, typography = YosemiteTypography) {
                            PlayerStage(compact)
                        }
                    }
                }
                if (qualityPickerOpen.value) {
                    val ceiling by qualityCeiling
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { qualityPickerOpen.value = false },
                        title = { Text("Picture quality") },
                        text = {
                            Column {
                                Text(
                                    "Auto follows the connection. A number is a ceiling — " +
                                        "a slow connection still drops below it.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                io.yosemitekids.app.data.PLAYBACK_QUALITIES.forEach { h ->
                                    androidx.compose.material3.TextButton(
                                        onClick = { qualityPickerOpen.value = false; setQuality(h) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                io.yosemitekids.app.data.qualityLabel(h),
                                                style = MaterialTheme.typography.titleSmall,
                                                modifier = Modifier.weight(1f)
                                            )
                                            if (h == ceiling) Icon(Icons.Filled.Check, contentDescription = "Selected")
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { qualityPickerOpen.value = false }) {
                                Text("Close")
                            }
                        }
                    )
                }
                if (portrait && !pip) {
                    PortraitPlayerScaffold { stage(true) }
                } else {
                    // The letterbox is `scrim`, which every scheme keeps black:
                    // a video's surround is not themed, on any player.
                    //
                    // A landscape phone lands here too, and that is a decision
                    // rather than a default: it gets the phone's full-size
                    // overlay (back, the pills, touch transport, a live
                    // scrubber) with the countdown chip riding the video
                    // top-right as it does on a TV — the TV-only pieces (the
                    // cursor toolbar, the non-interactive scrubber) are gated
                    // on isTv inside the overlay, not on this branch.
                    Box(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim),
                        contentAlignment = Alignment.Center
                    ) {
                        stage(false)
                    }
                }
            }
        }

        playIndex(startIndex)
    }

    /**
     * The video and everything drawn over it — cards, spinner, gestures, the
     * controls — filling whatever box it is placed in: the whole screen on
     * the stage, the 16:9 slot at the top of the portrait layout ([compact]
     * trims the overlay to fit). In the PiP window only the picture shows.
     */
    @Composable
    private fun PlayerStage(compact: Boolean) {
        val playback by playbackState
        val error by errorState
        val played by everPlayed
        val timeUp by timeUpMessage
        val blocked by blockedGently
        val checking by deepChecking
        val listenOnly by listenOnlyMessage
        val card by endCard
        val pip by inPip
        val tokens = kidTokens
        // Swipe down to put the video away (see PlayerGestures.kt for the
        // numbers). Held here rather than in the touch layer below because the
        // picture is what moves, and the touch layer comes and goes with the
        // card states while the picture does not. It rides in the movable
        // content with everything else, so a rotation mid-drag keeps its place.
        val canSwipeAway = remember { pipSupported() }
        val dismissDrag = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current.density
        val dismissDragState = rememberDraggableState { deltaPx ->
            // Follows the finger 1:1 and never rides above the top: an upward
            // drag on a video means nothing, and letting it bank up negative
            // travel would leave the next downward one feeling dead.
            scope.launch {
                dismissDrag.snapTo((dismissDrag.value + deltaPx / density).coerceAtLeast(0f))
            }
        }
        // Home mid-drag auto-enters the window (see pipParams) without ever
        // reaching onDragStopped. The picture has to be whole again when the
        // kid taps the window to come back, not parked where the finger left it.
        LaunchedEffect(pip) { if (pip) dismissDrag.snapTo(0f) }
        // The picture follows the finger by moving the PlayerView itself, not
        // by a Compose graphicsLayer over it. A layer transform is a matrix on
        // the window's render node and the video is a SurfaceView — its own
        // composited layer, positioned from the *View* hierarchy — so the
        // layer scaled every overlay and left the video exactly where it was.
        // Measured on the emulator: the drag worked and nothing moved. View
        // properties do reach the surface, so that is what this drives.
        if (canSwipeAway) LaunchedEffect(Unit) {
            snapshotFlow { dismissDrag.value }.collect { travelled ->
                val view = stageView ?: return@collect
                val shrink = PlayerDismiss.scale(travelled)
                // Shrink about the middle, then walk back the exact half the
                // shrink freed: the bottom-right corner stays put and the
                // picture tucks itself into the corner the little window
                // appears in rather than shrinking in place. That also means
                // it never spills past the slot it lives in, so the page
                // underneath is never drawn over.
                val walk = PlayerDismiss.cornerTravelFraction(travelled)
                view.scaleX = shrink
                view.scaleY = shrink
                view.translationX = walk * view.width
                view.translationY = walk * view.height
                view.alpha = PlayerDismiss.alpha(travelled)
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim)
                // Above the transform on purpose: the source rect the system
                // animates the little window out of is where the video *lives*,
                // not where a finger has dragged it to for the last 200ms.
                .onGloballyPositioned { c ->
                    val b = c.boundsInWindow()
                    videoBounds = android.graphics.Rect(
                        b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                timeUp != null -> BlockedCard(timeUp!!, isTv = isTv) { finish() }
                // Deliberately reason-free: the AI's explanation goes to
                // the parent's phone, not a TV the child is watching.
                blocked != null -> BlockedCard(blocked!!, isTv = isTv) { finish() }
                // The raw extractor/ExoPlayer message is for logcat (see
                // onPlaybackFailed); the kid gets a way forward instead.
                error != null -> ErrorCard(
                    isTv = isTv,
                    cursor = errorCursor.intValue,
                    onRetry = { playIndex(indexState.intValue) },
                    onBack = { finish() }
                )
                // Sound only, by the parent's window: no video view at
                // all, and the screen is no longer held awake, so this
                // is what the kid sees for the few seconds before it
                // goes dark — and again if they wake the phone.
                listenOnly != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        listenOnly!!,
                        color = tokens.onArtwork,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        playback?.title.orEmpty(),
                        color = tokens.onArtwork.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                // Before the first resolved video there is no frame to
                // hold, so a bare spinner is honest. The label appears
                // only while the deep check runs — stream resolution is
                // fast enough not to need explaining.
                !played -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (checking) "Checking this one…" else "Getting it ready…",
                        color = tokens.onArtwork.copy(alpha = 0.85f)
                    )
                }
                // Composed from the first video onwards and never swapped
                // out again — resolving the *next* one used to replace this
                // view with a spinner, which destroys the SurfaceView and
                // takes the last frame with it. Keeping it mounted holds
                // that frame under the spinner instead of cutting to black.
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PlayerView(context).apply {
                            this.player = this@PlayerActivity.player
                            // No stock controller anywhere: the remote
                            // drives a TV directly, and phones get the
                            // kid-sized Compose controls below — the
                            // Media3 bar's fingertip buttons and its
                            // playback-speed menu were never meant for
                            // a six-year-old.
                            useController = false
                            // Without this the view drops its shutter (opaque
                            // black) the moment the player is re-prepared with
                            // the next video — the other half of the cut to black.
                            setKeepContentOnPlayerReset(true)
                            // The swipe-away gesture moves this view directly;
                            // see the snapshotFlow above for why it cannot be
                            // a Compose transform.
                            stageView = this
                        }
                    }
                )
            }
            // Spinner over the held frame: resolving the next video's
            // streams, initial buffer, seek, or a mid-video stall.
            if (timeUp == null && error == null && blocked == null &&
                listenOnly == null && played && card == null &&
                (playback == null || buffering.value)
            ) {
                CircularProgressIndicator()
            }
            // Every card state, enumerated once. The controls, the touch layer
            // and the daily countdown all key off this, so a card added later
            // cannot be forgotten by one of them and drawn under by another.
            val videoOnStage = timeUp == null && blocked == null && error == null &&
                listenOnly == null && played && card == null && !pip
            val showControls = videoOnStage
            val frame by countdownFrame
            // The daily countdown is deliberately OUTSIDE the controls' fade:
            // it has to stay up while the chrome is hidden, which is most of
            // the time. The full stage draws it top-right over the video; the
            // portrait slot leaves it to the content column, which reads the
            // same frame (see PortraitPlayerScaffold).
            val countdownUp = videoOnStage && frame != null && !compact
            if (!pip) HeartBurst(heartBurst)
            if (!isTv && showControls) {
                // Touch layer under the controls: single tap shows/hides
                // them, a double tap on either edge hops ±10 s (the
                // YouTube gesture every kid already knows), a double tap
                // in the middle toggles play, and a drag downwards puts the
                // video in the little window. Buttons above it consume
                // their own taps, so this only ever sees the bare video.
                //
                // The drag and the taps are separate handlers on one box and
                // stay out of each other's way through touch slop: a tap
                // never travels far enough to start a drag, and the moment a
                // drag does start it consumes the moves, which cancels the
                // tap detector. A third gesture here would have to earn the
                // same proof — the ±10 s hop is the one a child uses most,
                // and it is the one that would go quietly.
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (!canSwipeAway) Modifier else Modifier.draggable(
                                orientation = Orientation.Vertical,
                                state = dismissDragState,
                                // The chrome goes as soon as the picture
                                // starts to move — a shrinking video with a
                                // full-size control bar riding it reads as a
                                // glitch rather than a gesture.
                                onDragStarted = { hideControls() },
                                onDragStopped = { velocityPx ->
                                    val travelled = dismissDrag.value
                                    val flick = velocityPx / density
                                    // enterPip() asks pipEligible(): a blocked
                                    // card, the pre-play check or a paused
                                    // video says no, and then this is a drag
                                    // that springs back rather than a gesture
                                    // that half-worked.
                                    if (PlayerDismiss.shouldDismiss(travelled, flick) && enterPip()) {
                                        dismissDrag.snapTo(0f)
                                    } else {
                                        pokeControls()
                                        if (animationsEnabled()) {
                                            dismissDrag.animateTo(
                                                0f,
                                                spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMediumLow
                                                )
                                            )
                                        } else dismissDrag.snapTo(0f)
                                    }
                                }
                            )
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    if (System.currentTimeMillis() < controlsVisibleUntil.value) {
                                        hideControls()
                                    } else pokeControls()
                                },
                                onDoubleTap = { offset ->
                                    val third = size.width / 3f
                                    when {
                                        offset.x < third -> seekBy(-10, showFeedback = true)
                                        offset.x > 2 * third -> seekBy(+10, showFeedback = true)
                                        else -> togglePlayPause()
                                    }
                                }
                            )
                        }
                )
                SeekRipple(seekFeedback)
            }
            if (showControls) {
                PlayerControlsOverlay(
                    isTv = isTv,
                    compact = compact,
                    onMinimise = { enterPip() },
                    // ⛶: the stage wants portrait, the slot wants landscape.
                    onToggleFullscreen = { forceOrientation(landscape = compact) },
                    visibleUntil = controlsVisibleUntil,
                    wantsPlay = wantsPlay,
                    title = currentTitle,
                    channel = currentChannel,
                    publishedAt = if (showVideoAge.value) currentPublishedAt.value else null,
                    countdownUp = countdownUp,
                    autoplayOn = autoplayState.value,
                    sponsorSegments = sponsorSegments.value,
                    panelState = trackPanel,
                    cursorState = trackCursor,
                    playback = playback,
                    selectedAudio = selectedAudioTrack.intValue,
                    selectedSubtitle = selectedSubtitleTrack.intValue,
                    captionsOn = captionsOn,
                    hasPrevious = indexState.intValue > 0,
                    hasNext = indexState.intValue < queue.lastIndex,
                    nextTitle = queueTitles.getOrNull(indexState.intValue + 1),
                    avatarUrl = channelAvatar.value,
                    onOpenChannel = ::openChannel,
                    isFavorite = isFavorite.value,
                    onToggleFavorite = ::toggleFavorite,
                    inWatchLater = inWatchLater.value,
                    onToggleWatchLater = ::toggleWatchLater,
                    inQueue = inQueue.value,
                    onToggleQueue = ::toggleQueue,
                    stopAfterThis = stopAfterThis.value,
                    onToggleStopAfter = ::toggleStopAfter,
                    onBack = { finish() },
                    onTogglePlay = ::togglePlayPause,
                    onSeekBy = { seekBy(it, showFeedback = false) },
                    onSeekTo = { ms -> player?.seekTo(ms); pokeControls() },
                    onPrevious = { stepQueue(-1) },
                    onNext = { stepQueue(+1) },
                    onToggleCaptions = { toggleCaptions(); pokeControls() },
                    onPoke = ::pokeControls,
                    playerProvider = { player }
                )
            }
            if (countdownUp) frame?.let { f ->
                // Under the phone's top row of buttons, beside the TV's title
                // line (which keeps clear of it — see the overlay's top scrim).
                DailyCountdownChip(
                    frame = f,
                    onArtwork = true,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            end = if (isTv) tvUnits(44f) else 16.dp,
                            top = if (isTv) tvUnits(28f) else 64.dp
                        )
                )
            }
            card?.let { c ->
                EndCardOverlay(
                    card = c,
                    isTv = isTv,
                    compact = compact,
                    cursor = endCardCursor.intValue,
                    channel = currentChannel,
                    onPrimary = { endCardPrimary() },
                    onSecondary = { endCardSecondary() },
                    onPick = { video -> playExtra(video) }
                )
            }
            if (timeUp == null && !pip) NoticeOverlay(notice)
        }
    }

    /**
     * Phone held upright: the video slot on top, then the page the design
     * draws under it — the daily countdown while it is on, the title, the
     * channel card, the three action tiles, the kid's own chips (the moon,
     * the quality), and the lists as tabs. Padded off the system bars, which
     * stay visible here (the stage hides them). This column is in the kid's
     * look; only the slot above it is the dark stage.
     */
    @Composable
    private fun PortraitPlayerScaffold(stage: @Composable () -> Unit) {
        val playback by playbackState
        val index by indexState
        val more by moreFromChannel
        val alike by similar
        val favorite by isFavorite
        val later by inWatchLater
        val queued by inQueue
        val stopAfter by stopAfterThis
        val avatar by channelAvatar
        val frame by countdownFrame
        val timeUp by timeUpMessage
        val blocked by blockedGently
        val error by errorState
        val listenOnly by listenOnlyMessage
        val chosen by portraitTab
        val videoCount by channelVideoCount
        val showAge by showVideoAge
        // Read under the index: appendToQueue grows the lists just before the
        // index moves, so a step is what brings the new entries on screen.
        val upNext = (index + 1..queue.lastIndex).toList()
        val titles = queueTitles
        val thumbs = queueThumbs
        val channel = currentChannel
        // Up next is a tab only while there is one; the other two are always
        // there — "Similar" with the parent's switch off is an empty list
        // under its own name, not a tab that vanished.
        val tabs = buildList {
            if (upNext.isNotEmpty()) add(PlayerTab.UpNext)
            add(PlayerTab.MoreFromChannel)
            add(PlayerTab.Similar)
        }
        val tab = chosen?.takeIf { it in tabs } ?: tabs.first()
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) { stage() }
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
            ) {
                // The same countdown the stage draws top-right in landscape:
                // one frame, one clock (PlayerCountdownTicker), on the page
                // instead of over the picture. Hidden under a card for the
                // same reasons the stage hides it (videoOnStage).
                val f = frame
                if (f != null && timeUp == null && blocked == null && error == null && listenOnly == null) {
                    item {
                        DailyCountdownChip(
                            frame = f,
                            onArtwork = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                        )
                    }
                }
                item {
                    Text(
                        playback?.title ?: titles.getOrNull(index).orEmpty(),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 12.dp)
                    )
                }
                item {
                    ChannelCard(
                        avatar = avatar,
                        name = channel,
                        videoCount = videoCount,
                        onOpen = if (avatar != null) ::openChannel else null
                    )
                }
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 14.dp)
                    ) {
                        val tokens = kidTokens
                        ActionTile(
                            if (favorite) "Favorited" else "Favorite",
                            if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            on = favorite, tint = tokens.action, onClick = ::toggleFavorite
                        )
                        ActionTile(
                            if (later) "Saved" else "Watch later",
                            YosemiteIcons.WatchLater,
                            on = later, tint = tokens.offline, onClick = ::toggleWatchLater
                        )
                        ActionTile(
                            if (queued) "Queued" else "Queue",
                            YosemiteIcons.UpNext,
                            on = queued, tint = tokens.action, onClick = ::toggleQueue
                        )
                    }
                }
                item {
                    // The kid's own levers, as chips: the moon is theirs (the
                    // Autoplay pill on the video is the parent's), and the
                    // quality pick is for the session.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 10.dp)
                    ) {
                        YosemiteChip(
                            if (stopAfter) "Stopping after this" else "Stop after this",
                            selected = stopAfter,
                            icon = YosemiteIcons.Moon,
                            onClick = ::toggleStopAfter
                        )
                        val ceiling by qualityCeiling
                        YosemiteChip(
                            io.yosemitekids.app.data.qualityLabel(ceiling),
                            selected = false,
                            icon = YosemiteIcons.Quality,
                            onClick = { qualityPickerOpen.value = true }
                        )
                    }
                }
                item { PlayerTabRow(tabs, tab) { portraitTab.value = it } }
                val rowMod = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)
                when (tab) {
                    PlayerTab.UpNext -> items(upNext) { j ->
                        SmallVideoRow(
                            title = titles.getOrNull(j)?.ifBlank { null } ?: "One more",
                            thumb = thumbs.getOrNull(j)?.ifBlank { null },
                            subtitle = queueChannels.getOrNull(j)?.ifBlank { null },
                            durationSeconds = queueDurations.getOrNull(j),
                            modifier = rowMod
                        ) { haptic(); dismissEndCard(); playIndex(j) }
                    }
                    PlayerTab.MoreFromChannel -> if (more.isEmpty()) {
                        item { EmptyTabLine("Nothing else from ${channel.ifBlank { "this channel" }} yet") }
                    } else items(more) { v ->
                        SmallVideoRow(
                            title = v.title,
                            thumb = v.thumbnailUrl,
                            subtitle = if (showAge) relativeAge(v.publishedAt) else null,
                            durationSeconds = v.durationSeconds,
                            modifier = rowMod
                        ) { playExtra(v) }
                    }
                    PlayerTab.Similar -> if (alike.isEmpty()) {
                        item { EmptyTabLine("Nothing similar yet") }
                    } else items(alike) { v ->
                        SmallVideoRow(
                            title = v.title,
                            thumb = v.thumbnailUrl,
                            subtitle = metaLine(v.channelName, if (showAge) relativeAge(v.publishedAt) else null),
                            durationSeconds = v.durationSeconds,
                            modifier = rowMod
                        ) { playExtra(v) }
                    }
                }
            }
        }
    }

    /** The channel, as a card that opens it: art, name, how many videos, a chevron. */
    @Composable
    private fun ChannelCard(avatar: String?, name: String, videoCount: Int, onOpen: (() -> Unit)?) {
        val scheme = MaterialTheme.colorScheme
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surfaceContainer)
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
                .then(if (onOpen != null) Modifier.clickable { onOpen() } else Modifier)
                .padding(12.dp)
        ) {
            ChannelArt(avatar, name, 44.dp, radius = 11.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name.ifBlank { "This channel" },
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                if (videoCount > 0) {
                    Spacer(Modifier.height(2.dp))
                    MicroLabel("$videoCount videos", scheme.onSurfaceVariant, isTv = false)
                }
            }
            if (onOpen != null) Icon(
                YosemiteIcons.ChevronRight, contentDescription = "Open channel",
                tint = scheme.onSurfaceVariant
            )
        }
    }

    /**
     * One of the three tiles under the video. A toggle, and it says so: the
     * label changes and the glyph takes its own colour once the video is
     * theirs — the tile itself does not fill, so three lit tiles still read
     * as three tiles and not one bar.
     */
    @Composable
    private fun RowScope.ActionTile(
        label: String,
        icon: ImageVector,
        on: Boolean,
        tint: Color,
        onClick: () -> Unit
    ) {
        val scheme = MaterialTheme.colorScheme
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surfaceContainer)
                .border(1.dp, if (on) tint else scheme.outlineVariant, RoundedCornerShape(14.dp))
                .clickable { onClick() }
                .padding(horizontal = 8.dp)
        ) {
            Icon(
                icon, contentDescription = null,
                tint = if (on) tint else scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = scheme.onSurface,
                maxLines = 1,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
            )
        }
    }

    /** The underlined tabs over the list; the hairline under them runs edge to edge. */
    @Composable
    private fun PlayerTabRow(tabs: List<PlayerTab>, active: PlayerTab, onPick: (PlayerTab) -> Unit) {
        val scheme = MaterialTheme.colorScheme
        Column(Modifier.padding(top = 16.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                tabs.forEach { t ->
                    val on = t == active
                    // Bounded to the label's own width: the underline below
                    // fills its column, and an unbounded column would take
                    // the whole row and push the other tabs off the screen.
                    Column(
                        Modifier
                            .width(IntrinsicSize.Max)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onPick(t) }
                    ) {
                        Text(
                            t.label,
                            color = if (on) scheme.onBackground else scheme.onSurfaceVariant,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Medium
                            ),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(if (on) scheme.onBackground else Color.Transparent)
                        )
                    }
                }
            }
            androidx.compose.material3.HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
        }
    }

    /** What a tab says when it has nothing to list — a line, never a blank. */
    @Composable
    private fun EmptyTabLine(text: String) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp)
        )
    }

    /** Back to the shelf, which opens the channel on resume. */
    private fun openChannel() {
        io.yosemitekids.app.data.PlayerRequests.openChannel = currentChannel
        finish()
    }

    /** The drain rate in force right now: the source's, scaled by the family listening rate when the screen is off. */
    private fun currentDrain(): Int =
        if (listenActive) listenDrainPercent(timePercent, listenPercent) else timePercent

    /** Re-read the countdown's anchor; the chip re-seeds from it on the next tick. */
    private fun reseedCountdown() {
        lifecycleScope.launch { readCountdownAnchor() }
    }

    /**
     * The one place the player asks the guard what is left. Off-main, because
     * `remainingAll()` can write a day rollover. Returns the reads so the
     * 5-second tick can take its one-minute moment from the same answer
     * instead of asking twice.
     */
    private suspend fun readCountdownAnchor(): List<io.yosemitekids.app.data.Remaining>? {
        if (!::sessionGuard.isInitialized) return null
        val drain = currentDrain()
        val listening = listenActive
        val reads = kotlinx.coroutines.withContext(Dispatchers.IO) {
            sessionGuard.remainingAll(drain, listening)
        }
        if (timeUpMessage.value != null) return null
        countdownAnchor.value = CountdownAnchor(reads, SystemClock.elapsedRealtime(), playClock.playedMs())
        return reads
    }

    /** The playing video as the saved lists want it — title, poster and length from the lineup. */
    private fun currentVideo(): io.yosemitekids.app.data.Video? {
        val url = currentPageUrl ?: return null
        val i = indexState.intValue
        val duration = queueDurations.getOrNull(i)?.takeIf { it > 0 }
            ?: ((player?.duration ?: 0L) / 1000).coerceAtLeast(0)
        return io.yosemitekids.app.data.Video(
            url = url,
            title = currentTitle,
            channelName = currentChannel,
            thumbnailUrl = queueThumbs.getOrNull(i)?.ifEmpty { null },
            durationSeconds = duration
        )
    }

    /**
     * Watch later, from the tile under the video or the TV toolbar. A toggle
     * like the heart rather than add-only: the hold menu on every shelf
     * already lets the kid take a video off again, so add-only here would
     * be a rule this app does not actually have.
     */
    private fun toggleWatchLater() {
        val video = currentVideo() ?: return
        val now = !inWatchLater.value
        inWatchLater.value = now
        haptic()
        notice.value = Notice(if (now) "Saved for later 🕒" else "Taken off Watch later")
        lifecycleScope.launch(Dispatchers.IO) {
            if (now) watchLater.add(video) else watchLater.remove(video.url)
        }
        pokeControls()
    }

    /**
     * Up next. Its own store instance, not [queueStore]: that one exists only
     * for EXTRA_FROM_QUEUE launches and means "finished videos leave the
     * lineup" — sharing it would change what that flag means. The add can be
     * refused (already queued, or the cap), and the tile must not flip on a
     * refusal, so the state follows the store's answer.
     */
    private fun toggleQueue() {
        val video = currentVideo() ?: return
        val store = io.yosemitekids.app.data.QueueStore(this, profileSuffix)
        val wanted = !inQueue.value
        haptic()
        lifecycleScope.launch(Dispatchers.IO) {
            if (wanted) {
                val queued = store.add(video) || video.url in store.urls()
                inQueue.value = queued
                notice.value = Notice(if (queued) "Added to Up next ☰" else "Up next is full")
            } else {
                store.remove(video.url)
                inQueue.value = false
                notice.value = Notice("Taken off Up next")
            }
        }
        pokeControls()
    }

    /**
     * "Similar": videos across every channel whose titles match this one,
     * the same local, explainable scorer the home's "More like what you
     * watch" uses. Honours the parent's switch the way Home does — off means
     * an EMPTY list, not a hidden tab, so nothing looks accidentally missing.
     * Every source's cache file is read, so this runs off-main, after the
     * title is known.
     */
    private fun loadSimilar(title: String, pageUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val cfg = familyConfig
                ?: io.yosemitekids.app.data.ConfigStore(this@PlayerActivity).load()
                    .also { familyConfig = it }
            val picks = if (!cfg.suggestSimilar) emptyList() else {
                val cache = io.yosemitekids.app.data.VideoCache(this@PlayerActivity)
                val known = io.yosemitekids.app.data.SourceCache(this@PlayerActivity).load()
                    .flatMap { cache.load(it.id) }
                val candidates = known.asSequence()
                    .filter { it.url != pageUrl && it.url !in queue }
                    // Family-wide and this kid's own blocks, and nothing they
                    // have already finished — the same fence autoplay keeps.
                    .filter {
                        cfg.isBlockedFor(it.videoId, gateProfileId) != true &&
                            history.progress(it.url)?.isFinished != true
                    }
                    .distinctBy { it.url }
                    .map { VideoItem(it, null) }
                    .toList()
                suggestionsFor(listOf(title), candidates, emptyMap(), SIMILAR_MAX).map { it.video }
            }
            if (isActive && currentPageUrl == pageUrl) similar.value = picks
        }
    }

    /** The moon: "stop after this one", with the pill that says so. */
    private fun toggleStopAfter() {
        stopAfterThis.value = !stopAfterThis.value
        haptic()
        notice.value = Notice(
            if (stopAfterThis.value) "Stopping after this one 🌙"
            else "Playing on after this one"
        )
        pokeControls()
    }

    /**
     * A video just ended. Mid-lineup: an "Up next" card names what's coming
     * and counts down before playing it — the kid can jump in or bail, and
     * nothing starts behind their back. Last video: a short "that's the end"
     * card with Watch again, then back to the shelf on its own. Both run on
     * [lifecycleScope] so a listen-mode playlist still advances in the dark.
     */
    private fun showEndCard() {
        hideControls()
        endCardJob?.cancel()
        endCardJob = lifecycleScope.launch {
            val i = indexState.intValue
            // Same-channel autoplay: nothing lined up, the parent's switch is
            // on and the kid didn't ask to stop — the channel's next unwatched
            // video joins the lineup and gets the countdown like a playlist
            // would. The "More from" row is the ones after it.
            var more: List<io.yosemitekids.app.data.Video> = emptyList()
            if (i >= queue.lastIndex && !stopAfterThis.value) {
                val candidates = kotlinx.coroutines.withContext(Dispatchers.IO) { channelCandidates() }
                if (autoplayOn && candidates.isNotEmpty()) {
                    appendToQueue(candidates.first())
                    more = candidates.drop(1).take(3)
                } else {
                    more = candidates.take(3)
                }
            }
            val hasNext = i < queue.lastIndex && !stopAfterThis.value
            // In the PiP window a card is a postage stamp nobody can read or
            // press: move straight on, or close the window when the lineup
            // is done.
            if (inPip.value) {
                if (hasNext) stepQueue(+1) else finish()
                return@launch
            }
            endCardCursor.intValue = 0
            val seconds = if (hasNext) UP_NEXT_SECONDS else END_CARD_SECONDS
            endCard.value = EndCard(
                nextTitle = if (hasNext) queueTitles.getOrNull(i + 1) else null,
                nextThumb = if (hasNext) queueThumbs.getOrNull(i + 1) else null,
                nextChannel = if (hasNext) queueChannels.getOrNull(i + 1)?.ifBlank { null } ?: currentChannel else null,
                nextDurationSeconds = if (hasNext) queueDurations.getOrNull(i + 1)?.takeIf { it > 0 } else null,
                hasNext = hasNext,
                secondsLeft = seconds,
                totalSeconds = seconds,
                more = more
            )
            var left = seconds
            while (left > 0) {
                delay(1_000)
                left--
                endCard.value = endCard.value?.copy(secondsLeft = left) ?: return@launch
            }
            endCardPrimaryAuto()
        }
    }

    /**
     * The current channel's cached videos after this one, unwatched and not
     * parent-blocked, in the channel's own (newest-first) order, wrapping to
     * the top. Deep screening is not applied here — it runs when the video
     * actually plays, same as any press from the shelf.
     */
    private fun channelCandidates(): List<io.yosemitekids.app.data.Video> {
        val sourceId = channelSourceId ?: return emptyList()
        val cached = io.yosemitekids.app.data.VideoCache(this).load(sourceId)
        if (cached.isEmpty()) return emptyList()
        val current = currentPageUrl
        val at = cached.indexOfFirst { it.url == current }
        val ordered = if (at < 0) cached else cached.drop(at + 1) + cached.take(at)
        return ordered.filter { v ->
            // Family-wide *and* this kid's own blocks: autoplay must never
            // surface what the shelf would have hidden from them.
            v.url != current && v.url !in queue &&
                familyConfig?.isBlockedFor(v.videoId, gateProfileId) != true &&
                history.progress(v.url)?.isFinished != true
        }
    }

    /** Tack one more video onto the lineup, with the display fields the cards need. */
    private fun appendToQueue(video: io.yosemitekids.app.data.Video) {
        // Pad the parallel lists to the queue's length first: single launches
        // carry one entry each, and getOrNull would otherwise drift.
        fun <T> List<T>.padTo(n: Int, filler: T): List<T> =
            if (size >= n) this else this + List(n - size) { filler }
        val n = queue.size
        queueTitles = queueTitles.padTo(n, "") + video.title
        queueThumbs = queueThumbs.padTo(n, "") + video.thumbnailUrl.orEmpty()
        queueDurations = queueDurations.padTo(n, 0L) + video.durationSeconds
        queueChannels = queueChannels.padTo(n, currentChannel) + video.channelName
        queuePercents = (queuePercents?.toList()?.padTo(n, timePercent) ?: List(n) { timePercent })
            .plus(timePercent).toIntArray()
        queue = queue + video.url
    }

    /** A "More from this channel" pick on the end card: line it up and go. */
    private fun playExtra(video: io.yosemitekids.app.data.Video) {
        haptic()
        appendToQueue(video)
        stepQueue(queue.lastIndex - indexState.intValue)
    }

    /** The heart: save or unsave the playing video for this kid, with a pop. */
    private fun toggleFavorite() {
        val video = currentVideo() ?: return
        val url = video.url
        val nowFavorite = !isFavorite.value
        isFavorite.value = nowFavorite
        haptic()
        if (nowFavorite) heartBurst.value = System.currentTimeMillis()
        notice.value = Notice(if (nowFavorite) "Added to Favorites ❤️" else "Taken off Favorites")
        lifecycleScope.launch(Dispatchers.IO) {
            if (nowFavorite) favorites.add(video) else favorites.remove(url)
        }
        pokeControls()
    }

    private fun dismissEndCard() {
        endCardJob?.cancel()
        endCardJob = null
        endCard.value = null
    }

    /** Countdown ran out: play what's next, or leave when there is nothing. */
    private fun endCardPrimaryAuto() {
        val c = endCard.value ?: return
        if (c.hasNext) stepQueue(+1) else finish()
    }

    /** The big button: Play now / Watch again. */
    private fun endCardPrimary() {
        val c = endCard.value ?: return
        haptic()
        if (c.hasNext) {
            stepQueue(+1)
        } else {
            dismissEndCard()
            player?.seekTo(0)
            player?.play()
            pokeControls()
        }
    }

    /** The quiet button: Not now / All done — both mean "back to the shelf". */
    private fun endCardSecondary() {
        haptic()
        dismissEndCard()
        finish()
    }

    private fun onPlaybackFailed(message: String) {
        // The detail is for whoever reads logcat; the kid gets the friendly
        // card with a way forward (see ErrorCard).
        android.util.Log.w("YosemiteKids", "playback failed for $currentPageUrl: $message")
        if (indexState.intValue < queue.lastIndex) playIndex(indexState.intValue + 1)
        else {
            errorCursor.intValue = 0
            errorState.value = message
        }
    }

    /**
     * Resolves streams for queue position [i] and hands them to the player.
     * A downloaded video plays from disk — instant start, and no network
     * needed at all (car trips). Otherwise target height follows connection +
     * device (1080p TV on fast Wi-Fi, down to muxed).
     */
    private fun playIndex(i: Int) {
        indexState.intValue = i
        dismissEndCard()
        selectedAudioTrack.intValue = 0
        selectedSubtitleTrack.intValue = -1
        trackPanel.value = TvTrackPanel.Hidden
        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            playbackState.value = null
            errorState.value = null
            currentPageUrl = queue[i]
            // Per-item stats/drain: the 5-second tick reads these fields, so a
            // cross-channel queue charges and credits each video correctly.
            queueChannels.getOrNull(i)?.let { currentChannel = it }
            queuePercents?.getOrNull(i)?.let { timePercent = it.coerceIn(0, 400) }
            // The overlay's heart and channel button, and autoplay's source:
            // resolved by channel name from the tile cache, off-main.
            val pageUrl = queue[i]
            val channelName = currentChannel
            launch(Dispatchers.IO) {
                val fav = favorites.urls().contains(pageUrl)
                val later = watchLater.urls().contains(pageUrl)
                val queued = io.yosemitekids.app.data.QueueStore(this@PlayerActivity, profileSuffix)
                    .urls().contains(pageUrl)
                val source = io.yosemitekids.app.data.SourceCache(this@PlayerActivity).load()
                    .firstOrNull { it.name == channelName }
                if (isActive) {
                    isFavorite.value = fav
                    inWatchLater.value = later
                    inQueue.value = queued
                    channelSourceId = source?.id
                    if (source?.avatarUrl != null) channelAvatar.value = source.avatarUrl
                    // The cache row for this video carries the one thing the
                    // intent does not: when it came out. Same read the
                    // candidates below make, memoised in VideoCache.
                    val cached = if (source != null) {
                        io.yosemitekids.app.data.VideoCache(this@PlayerActivity).load(source.id)
                    } else emptyList()
                    currentPublishedAt.value = cached.firstOrNull { it.url == pageUrl }?.publishedAt
                    channelVideoCount.intValue = cached.size
                    // The portrait list under the video: the same channel
                    // candidates autoplay will draw from, computed now so the
                    // list is there before the video is (cache read, off-main).
                    val more = if (source != null) {
                        // Newest first where the cache knows dates ("New from"), else the
                        // channel order from after the current one.
                        channelCandidates().sortedByDescending { it.publishedAt ?: Long.MIN_VALUE }.take(12)
                    } else emptyList()
                    if (isActive) moreFromChannel.value = more
                }
            }
            sponsorSegments.value = emptyList()
            // Segment lookup rides alongside stream resolution, never on
            // its critical path — a slow or down SponsorBlock server
            // costs nothing but unskipped sponsors. Its own job, not a
            // child of this one: a child would keep resolveJob "active" for
            // as long as the server takes, and the listen-mode swap guards
            // read that as "still resolving" and skip. Advancing the queue
            // cancels it explicitly instead.
            io.yosemitekids.app.data.SponsorBlock.videoIdOf(queue[i])?.let { vid ->
                sponsorJob?.cancel()
                sponsorJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val on = sponsorSkipOn
                        ?: io.yosemitekids.app.data.ConfigStore(this@PlayerActivity)
                            .load().sponsorSkip.also { sponsorSkipOn = it }
                    if (!on) return@launch
                    val segments = io.yosemitekids.app.data.SponsorBlock.segmentsFor(vid)
                    // The blocking fetch outlives cancellation — isActive
                    // keeps a late answer from tagging the *next* video.
                    if (segments.isNotEmpty() && isActive && currentPageUrl == pageUrl) {
                        sponsorSegments.value = segments
                    }
                }
            }
            val pb = runCatching {
                val local = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    // Sideloaded file (content:// via SAF) or a finished
                    // download — both play from disk with no network.
                    localLibrary.playback(queue[i])
                        ?: downloads.localPlayback(queue[i])
                }
                val resolved = local ?: repo.resolvePlayback(
                    queue[i],
                    io.yosemitekids.app.data.QualityTargets.effectiveMaxHeight()
                )
                // First play of a streamed video: the once-per-video deep check
                // (description + tags + transcript), riding the StreamInfo we
                // just paid for. Local files skip it — offline playback can't
                // reach the AI, and downloads were screened before they landed.
                if (local == null && deepCheckBlocks(queue[i], resolved)) null else resolved
            }.getOrElse { e ->
                // A superseded resolve (queue advanced again) must not be
                // mistaken for a broken video and trigger its own advance.
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Mid-playlist failure: skip to the next video instead of dying.
                onPlaybackFailed(e.message ?: e.javaClass.simpleName)
                return@launch
            }
            if (pb == null) {
                onDeepBlocked(i)
                return@launch
            }
            currentTitle = pb.title
            currentPlayback = pb
            ListenService.title = pb.title
            ListenService.channelName = currentChannel
            playbackState.value = pb
            everPlayed.value = true
            similar.value = emptyList()
            loadSimilar(pb.title, pageUrl)
            attachSources(
                pb,
                audioOnly = listenActive && pb.audioUrl != null,
                resumeMs = null
            )
        }
    }

    /**
     * Whether the pre-play deep check refuses this video for the launching kid.
     * One AI call per video per rules version, cached in [screeningStore] like
     * a batch verdict (with the deep flag, so the cheap title pass never
     * overwrites it) — after that, this answers from disk. Fail-open on
     * purpose: an unreachable or erroring provider plays the video unchecked
     * this once and caches nothing, so the next press tries again — the kid is
     * not punished for an outage.
     */
    private suspend fun deepCheckBlocks(
        pageUrl: String,
        pb: YouTubeRepository.Playback
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val id = io.yosemitekids.app.data.SponsorBlock.videoIdOf(pageUrl)
            ?: return@withContext false
        val cfg = familyConfig
            ?: io.yosemitekids.app.data.ConfigStore(this@PlayerActivity).load()
                .also { familyConfig = it }
        val ai = cfg.ai
        if (!ai.enabled || ai.model.isBlank()) return@withContext false
        // A parent's explicit allow beats every AI verdict, deep ones included.
        if (id in cfg.allowedIdsFor(gateProfileId)) return@withContext false

        val note = (channelNotes ?: io.yosemitekids.app.data.DeepCheck.notesByChannelName(
            cfg.sources, io.yosemitekids.app.data.SourceCache(this@PlayerActivity).load()
        ).also { channelNotes = it })[currentChannel]

        io.yosemitekids.app.data.DeepCheck.cached(
            screeningStore, id, ai.rulesVersion, AiScreener.noteHash(note)
        )?.let {
            android.util.Log.i("YosemiteKids",
                "Deep check $id: cached ${it.verdictFor(gateProfileId)} (\"${it.reason}\")"
            )
            return@withContext it.verdictFor(gateProfileId) != AiScreener.Verdict.ALLOW
        }

        deepChecking.value = true
        val entry = try {
            // Bounded overall: past ~20s the kid is staring at a spinner and an
            // answer that slow is treated like an outage (play this once).
            io.yosemitekids.app.data.DeepCheck.runAndStore(
                ai, cfg.profiles, screeningStore, id, pb.title, currentChannel,
                pb, timeoutMs = 20_000, channelNote = note
            )
        } finally {
            deepChecking.value = false
        }
        // Null = failure/timeout: play unchecked this once, nothing cached.
        entry != null && entry.verdictFor(gateProfileId) != AiScreener.Verdict.ALLOW
    }

    /**
     * Deep check said no. Mid-queue the lineup just moves on, same as a broken
     * video. For the video the kid actually pressed, a gentle reason-free line
     * — the AI's explanation is for the parent's phone, not a TV with a child
     * in front of it — then back to the shelf they came from, where the
     * verdict now hides this video.
     */
    private fun onDeepBlocked(i: Int) {
        if (i < queue.lastIndex) {
            playIndex(i + 1)
            return
        }
        blockedGently.value = "This one isn't available."
        lifecycleScope.launch {
            delay(4_000)
            finish()
        }
    }

    /**
     * Hands the resolved streams to the (single, reused) player. [audioOnly]
     * is the listen-mode swap: just the audio track — the video stream would
     * only be downloaded to feed a decoder nobody is watching. [resumeMs]
     * null means a fresh video (resume from saved history); a value is an
     * in-place stream swap that must not lose the playhead or override the
     * kid's pause.
     */
    private fun attachSources(
        pb: YouTubeRepository.Playback,
        audioOnly: Boolean,
        resumeMs: Long?
    ) {
        val exo = player ?: return
        currentSubtitles = pb.subtitles
        // Android 13+ builds the lock-screen/QS media controls from the
        // session's metadata and ignores the notification adapter's strings,
        // so the title must ride on the MediaItem itself — a bare-URI item
        // leaves the lock screen showing whatever the system scrapes instead.
        val mediaMetadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(pb.title)
            .setArtist(currentChannel.ifBlank { null })
            .build()
        val audioUrl = pb.audioTracks.getOrNull(selectedAudioTrack.intValue)?.url ?: pb.audioUrl
        // DefaultDataSource: http for streams, file for offline
        // downloads, content for sideloaded SAF files. Wrapped so
        // googlevideo streams fetch in range-parameter chunks.
        // Measured on a Chromecast, same video: unwrapped, the
        // buffer starved (stalled at 0s ahead, then crept up at
        // ~1.5x playback); chunked, the whole video was resident
        // 19s in. See ChunkedStreamDataSource for why.
        val factory = io.yosemitekids.app.data.ChunkedStreamDataSource.Factory(
            androidx.media3.datasource.DefaultDataSource.Factory(this)
        )
        fun progressive(url: String) =
            androidx.media3.exoplayer.source.ProgressiveMediaSource
                .Factory(factory).createMediaSource(
                    MediaItem.Builder().setUri(url)
                        .setMediaMetadata(mediaMetadata).build()
                )
        val wasPlaying = if (resumeMs != null) exo.playWhenReady else true
        if (audioOnly && audioUrl != null) {
            exo.setMediaSource(progressive(audioUrl))
        } else {
            // Subtitles ride along as side-loaded tracks on the video item;
            // DefaultMediaSourceFactory parses them during extraction (the
            // modern pipeline — SingleSampleMediaSource is the legacy path
            // that media3 1.4+ refuses at play time). Whether one is shown
            // is the kid's sticky captions choice.
            val subConfigs = pb.subtitles.map { sub ->
                MediaItem.SubtitleConfiguration
                    .Builder(android.net.Uri.parse(sub.url))
                    .setMimeType(sub.mimeType)
                    .setLanguage(sub.languageTag.ifBlank { null })
                    .setLabel(sub.name.ifBlank { null })
                    .build()
            }
            val video = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(factory)
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(pb.videoUrl)
                        .setMediaMetadata(mediaMetadata)
                        .setSubtitleConfigurations(subConfigs)
                        .build()
                )
            // HD: separate video+audio merged in the player (NewPipe-style).
            exo.setMediaSource(
                if (audioUrl != null) {
                    androidx.media3.exoplayer.source.MergingMediaSource(
                        video, progressive(audioUrl)
                    )
                } else video
            )
        }
        applyCaptionsPreference()
        exo.prepare()
        if (resumeMs != null) {
            exo.seekTo(resumeMs)
        } else {
            currentPageUrl?.let { page ->
                history.progress(page)?.takeIf { !it.isFinished }
                    ?.let { exo.seekTo(it.positionMs) }
            }
        }
        exo.playWhenReady = wasPlaying
        pokeControls() // brief peek at the title and position at start
        // Fresh video: the countdown shouldn't wait five seconds for the first
        // tick, and a cross-channel step may have changed the drain rate.
        if (resumeMs == null) reseedCountdown()
    }

    /**
     * Leaving the player while playing (phones, listening rate set) — power
     * button or switching to another app: keep the sound going instead of
     * pausing. Entered from [onStop] whenever the activity isn't finishing.
     */
    private fun enterListenMode() {
        if (isTv || listenActive) return
        listenPercent ?: return // unset = feature off: onStop pauses as always
        if (timeUpMessage.value != null) return
        val exo = player ?: return
        // Mid-advance (between videos) counts as playing: the resolve finishes
        // in the dark and attachSources starts the next one audio-only.
        if (!exo.isPlaying && resolveJob?.isActive != true) return
        listenActive = true
        // Drop to the bare audio stream where one exists (HD sources): the
        // video track is most of the bandwidth and all of the decode work,
        // and nobody is watching. Muxed and local files just keep playing.
        // NOT mid-advance, though: currentPlayback is still the *previous*
        // video there, and re-attaching it seeked to exo's end-of-old-video
        // position fires an instant ENDED that marks the next queued video
        // watched and drops it. The in-flight resolve honors listenActive on
        // its own when it attaches.
        if (resolveJob?.isActive != true) {
            currentPlayback?.let { pb ->
                if (pb.audioUrl != null) {
                    attachSources(pb, audioOnly = true, resumeMs = exo.currentPosition)
                }
            }
        }
        ListenService.title = currentTitle
        ListenService.channelName = currentChannel
        ListenService.player = exo
        ListenService.start(this)
    }

    /**
     * Follows a "Allow listening" window opening or closing under a story
     * that is already playing. Bedtime arriving must not cut the story off
     * mid-sentence — that is the whole reason the checkbox exists — so the
     * picture goes and the sound stays; when the window ends, the picture is
     * available again.
     */
    private fun syncListenOnlyWindow() {
        if (isTv || listenPercent == null) return
        val blocking = sessionGuard.listenOnlyWindow() != null
        if (blocking == listenOnlyWindow) return
        if (blocking) {
            listenOnlyWindow = true
            // The reason line, phrased by the guard exactly as the kid would
            // otherwise have been stopped with.
            listenOnlyMessage.value = sessionGuard.checkStart(timePercent)
            armListenOnly()
        } else {
            listenOnlyWindow = false
            listenOnlyMessage.value = null
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // Only the pin is gone. Backgrounded or screen-off, this is
            // ordinary listen mode and stays exactly as it is; in front of the
            // kid, the picture comes back.
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                exitListenMode()
            }
        }
    }

    /**
     * Sound-only because a window says so: the audio swap and the notification
     * that ordinary listen mode gets from [enterListenMode], plus dropping the
     * keep-awake so the screen goes dark by itself. Nothing here turns the
     * screen off — the system's own timeout does, once we stop holding it on.
     */
    private fun armListenOnly() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        listenActive = true
        val exo = player ?: return
        // Same mid-advance guard as enterListenMode: the in-flight resolve
        // attaches audio-only on its own (it reads listenActive).
        if (resolveJob?.isActive != true) {
            currentPlayback?.let { pb ->
                if (pb.audioUrl != null) {
                    attachSources(pb, audioOnly = true, resumeMs = exo.currentPosition)
                }
            }
        }
        ListenService.title = currentTitle
        ListenService.channelName = currentChannel
        ListenService.player = exo
        ListenService.start(this)
    }

    /** Back in front (unlock, or lock screen never engaged): video + normal rate. */
    private fun exitListenMode() {
        if (!listenActive) return
        // A window still has watching blocked — being back in front of the
        // player doesn't lift bedtime, it just means the kid is looking at the
        // "listening only" card.
        if (listenOnlyWindow) return
        listenActive = false
        ListenService.stop(this)
        val exo = player ?: return
        // Same mid-advance guard as enterListenMode: a stale playback must not
        // be re-attached over an in-flight resolve.
        if (resolveJob?.isActive != true) {
            currentPlayback?.let { pb ->
                if (pb.audioUrl != null) {
                    attachSources(pb, audioOnly = false, resumeMs = exo.currentPosition)
                }
            }
        }
    }

    /** Friendly full-screen block (bedtime / session limits) instead of the player. */
    private fun showBlockedScreen(reason: String) {
        preBlocked = true
        setContent {
            MaterialTheme(colorScheme = YosemiteDarkColors, typography = YosemiteTypography) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim),
                    contentAlignment = Alignment.Center
                ) {
                    BlockedCard(reason, isTv = isTv) { finish() }
                }
                LaunchedEffect(Unit) {
                    delay(7_000)
                    finish()
                }
            }
        }
    }

    /** True when the pre-player block screen is up: any remote key dismisses it. */
    private var preBlocked = false

    /** TV remote: OK toggles play/pause, ◀ ▶ seek ±10s, with a time overlay. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (!isTv) return super.onKeyDown(keyCode, event)
        if (preBlocked || timeUpMessage.value != null || blockedGently.value != null) {
            // The card already says what happened; the first press goes home.
            if (keyCode != android.view.KeyEvent.KEYCODE_BACK) finish()
            return super.onKeyDown(keyCode, event)
        }
        val exo = player ?: return super.onKeyDown(keyCode, event)
        // Two-button cards (end of video, playback error): ◀ ▶ pick, OK acts.
        endCard.value?.let {
            if (handleTwoButtonKey(keyCode, endCardCursor, ::endCardPrimary, ::endCardSecondary)) {
                return true
            }
            // Transport keys must not reach the ended player: a seek would
            // restart it under the card and re-arm the countdown.
            if (keyCode in END_CARD_SWALLOWED_KEYS) return true
        }
        if (errorState.value != null) {
            if (handleTwoButtonKey(
                    keyCode, errorCursor,
                    onPrimary = { playIndex(indexState.intValue) },
                    onSecondary = { finish() }
                )
            ) return true
        }
        if (trackPanel.value != TvTrackPanel.Hidden && handleTrackPanelKey(keyCode)) {
            pokeControls()
            return true
        }
        val repeat = event?.repeatCount ?: 0
        val handled = when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (repeat == 0) togglePlayPause(); true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> { exo.play(); true }
            android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { exo.pause(); true }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                heldSeek(+1, repeat); true
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                heldSeek(-1, repeat); true
            }
            // Next/previous in the lineup: the media keys most remotes carry,
            // and channel up/down, which is what a TV remote has plenty of.
            android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
            android.view.KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (repeat == 0 && !stepQueue(+1)) notice.value = Notice("That's the last one")
                true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            android.view.KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (repeat == 0 && !stepQueue(-1)) seekBy(-exo.currentPosition.toInt() / 1000, false)
                true
            }
            // Up just peeks at the time without changing anything.
            android.view.KeyEvent.KEYCODE_DPAD_UP -> true
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                trackCursor.intValue = 0
                trackPanel.value = TvTrackPanel.Toolbar
                true
            }
            android.view.KeyEvent.KEYCODE_CAPTIONS -> {
                trackCursor.intValue = if (captionsOn) {
                    selectedSubtitleTrack.intValue + 1
                } else 0
                trackPanel.value = TvTrackPanel.Subtitles
                true
            }
            else -> false
        }
        if (handled) {
            pokeControls()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Held ◀/▶ on the remote. The OS repeats a held key every ~50 ms and each
     * repeat used to be a full 10 s hop — a one-second hold flew 200 s. Now a
     * tap is 10 s, and a hold paces itself at four hops a second, growing to
     * 30 s hops after the first second so a long video is still crossable.
     */
    private fun heldSeek(direction: Int, repeat: Int) {
        if (repeat == 0) {
            seekBy(10 * direction, showFeedback = false)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastHeldSeekAt < 250) return
        lastHeldSeekAt = now
        seekBy((if (repeat > 20) 30 else 10) * direction, showFeedback = false)
    }

    /** ◀ ▶ move between two buttons, OK presses the highlighted one, Back leaves. */
    private fun handleTwoButtonKey(
        keyCode: Int,
        cursor: androidx.compose.runtime.MutableIntState,
        onPrimary: () -> Unit,
        onSecondary: () -> Unit
    ): Boolean = when (keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
        android.view.KeyEvent.KEYCODE_DPAD_UP -> { cursor.intValue = 0; true }
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { cursor.intValue = 1; true }
        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
        android.view.KeyEvent.KEYCODE_ENTER,
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
            if (cursor.intValue == 0) onPrimary() else onSecondary(); true
        }
        android.view.KeyEvent.KEYCODE_BACK -> { onSecondary(); true }
        else -> false
    }

    private fun handleTrackPanelKey(keyCode: Int): Boolean {
        val pb = currentPlayback ?: return false
        return when (trackPanel.value) {
            TvTrackPanel.Hidden -> false
            TvTrackPanel.Toolbar -> when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                    trackCursor.intValue = (trackCursor.intValue - 1).coerceAtLeast(0); true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    trackCursor.intValue =
                        (trackCursor.intValue + 1).coerceAtMost(TvToolbarSlot.entries.lastIndex); true
                }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER -> {
                    // Exhaustive: a slot added to TvToolbarSlot with no branch
                    // here does not compile, which is the whole point of the enum.
                    when (TvToolbarSlot.entries[trackCursor.intValue.coerceIn(0, TvToolbarSlot.entries.lastIndex)]) {
                        TvToolbarSlot.Audio -> {
                            trackCursor.intValue = selectedAudioTrack.intValue
                            trackPanel.value = TvTrackPanel.Audio
                        }
                        TvToolbarSlot.Subtitles -> {
                            trackCursor.intValue = if (captionsOn) {
                                selectedSubtitleTrack.intValue + 1
                            } else 0
                            trackPanel.value = TvTrackPanel.Subtitles
                        }
                        // The phone's tiles and avatar, reachable from the remote:
                        // the toolbar is the one place a TV kid can "press" something.
                        TvToolbarSlot.Favorite -> toggleFavorite()
                        TvToolbarSlot.WatchLater -> toggleWatchLater()
                        TvToolbarSlot.Queue -> toggleQueue()
                        TvToolbarSlot.Channel -> {
                            // Only when the uploader is a whitelisted channel: a
                            // playlist's uploader often isn't, and finishing the
                            // player to open nothing would dump the kid mid-video.
                            if (channelSourceId != null) {
                                io.yosemitekids.app.data.PlayerRequests.openChannel = currentChannel
                                finish()
                            } else {
                                notice.value = Notice("That channel isn't on your list")
                            }
                        }
                    }
                    true
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_BACK -> {
                    trackPanel.value = TvTrackPanel.Hidden; true
                }
                else -> false
            }
            TvTrackPanel.Audio -> handleOptionKey(
                keyCode, pb.audioTracks.size.coerceAtLeast(1), toolbarCursor = TvToolbarSlot.Audio.ordinal
            ) {
                val chosen = if (pb.audioTracks.isEmpty()) 0
                    else trackCursor.intValue.coerceIn(0, pb.audioTracks.lastIndex)
                if (pb.audioTracks.isNotEmpty() && chosen != selectedAudioTrack.intValue) {
                    selectedAudioTrack.intValue = chosen
                    attachSources(pb, audioOnly = false, resumeMs = player?.currentPosition)
                }
                notice.value = Notice(
                    "Audio: " + (pb.audioTracks.getOrNull(chosen)?.name ?: "Original")
                )
            }
            TvTrackPanel.Subtitles -> handleOptionKey(
                keyCode, pb.subtitles.size + 1, toolbarCursor = TvToolbarSlot.Subtitles.ordinal
            ) {
                val chosen = trackCursor.intValue - 1
                selectedSubtitleTrack.intValue = chosen
                captionsOn = chosen >= 0
                getSharedPreferences("player", MODE_PRIVATE)
                    .edit().putBoolean("captions", captionsOn).apply()
                applyCaptionsPreference()
                notice.value = Notice(
                    if (chosen < 0) "Subtitles off"
                    else "Subtitles: ${pb.subtitles[chosen].name}"
                )
            }
        }
    }

    private fun handleOptionKey(
        keyCode: Int,
        optionCount: Int,
        toolbarCursor: Int,
        select: () -> Unit
    ): Boolean = when (keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
            trackCursor.intValue = (trackCursor.intValue - 1).coerceAtLeast(0); true
        }
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
            trackCursor.intValue = (trackCursor.intValue + 1).coerceAtMost(optionCount - 1); true
        }
        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
        android.view.KeyEvent.KEYCODE_ENTER -> {
            select()
            trackPanel.value = TvTrackPanel.Toolbar
            trackCursor.intValue = toolbarCursor
            true
        }
        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
        android.view.KeyEvent.KEYCODE_BACK -> {
            trackPanel.value = TvTrackPanel.Toolbar
            trackCursor.intValue = toolbarCursor
            true
        }
        else -> false
    }

    /**
     * Applies the sticky captions choice to the player: text track enabled or
     * not, preferring the device language, then whatever this video offers.
     */
    private fun applyCaptionsPreference() {
        val exo = player ?: return
        val builder = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, !captionsOn)
        if (captionsOn) {
            val selected = currentSubtitles.getOrNull(selectedSubtitleTrack.intValue)
            val languages =
                (listOfNotNull(selected?.languageTag) +
                    java.util.Locale.getDefault().language +
                    currentSubtitles.map { it.languageTag })
                    .filter { it.isNotBlank() }.distinct()
            builder.setPreferredTextLanguages(*languages.toTypedArray())
        }
        exo.trackSelectionParameters = builder.build()
    }

    private fun toggleCaptions() {
        if (currentSubtitles.isEmpty()) {
            notice.value = Notice("No subtitles for this video")
            return
        }
        captionsOn = !captionsOn
        getSharedPreferences("player", MODE_PRIVATE)
            .edit().putBoolean("captions", captionsOn).apply()
        applyCaptionsPreference()
        notice.value = Notice(if (captionsOn) "Subtitles on 💬" else "Subtitles off")
    }

    /**
     * Position is read here (ExoPlayer is main-thread only) but written on IO:
     * history.save uses commit() on purpose (crash-safety), and its fsync would
     * otherwise stall the UI thread mid-playback on every 5s tick.
     */
    private fun saveProgress() {
        val exo = player ?: return
        val url = currentPageUrl ?: return
        val pos = exo.currentPosition
        val dur = exo.duration
        if (dur <= 0) return
        lifecycleScope.launch(Dispatchers.IO) { history.save(url, pos, dur) }
    }

    override fun onResume() {
        super.onResume()
        // The single exit from listen mode: "resumed" is the one state that
        // means the kid is actually looking at the player again, on every
        // unlock path (keyguard, swipe, no lock at all).
        exitListenMode()
        // Back from HOME onto a paused frame: show where things stand rather
        // than a still picture with no hint that OK resumes it.
        if (player != null && !inPip.value) pokeControls()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayoutFor(newConfig)
    }

    /**
     * Portrait or landscape decides the layout and the system bars: the
     * stage is immersive, the portrait layout lives under a visible status
     * bar like any other screen. Handled here, not by recreation (see the
     * manifest's configChanges) — the player must survive a turn.
     */
    private fun applyLayoutFor(config: android.content.res.Configuration) {
        if (isTv) return
        val portrait = config.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        portraitLayout.value = portrait
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
            if (portrait) show(bars) else {
                hide(bars)
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    /**
     * ⛶. The phone's own rotation setting is overridden just long enough to
     * turn the screen; once the phone is physically held that way (and the
     * kid has auto-rotate on), the override is released so the next turn
     * back is followed like any other. With auto-rotate off the override
     * stays until ⛶ is pressed again — the rotation lock is respected in
     * spirit: nothing moves unless a button is pressed.
     */
    private fun forceOrientation(landscape: Boolean) {
        haptic()
        requestedOrientation =
            if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        orientationListener?.disable()
        orientationListener = null
        val autoRotate = android.provider.Settings.System.getInt(
            contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0
        ) == 1
        if (!autoRotate) return
        val listener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                // Wide dead zones: a phone tilted halfway must not flip-flop.
                val heldLandscape = orientation in 60..120 || orientation in 240..300
                val heldPortrait = orientation <= 30 || orientation >= 330 || orientation in 150..210
                if ((landscape && heldLandscape) || (!landscape && heldPortrait)) {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
                    disable()
                    if (orientationListener === this) orientationListener = null
                }
            }
        }
        if (listener.canDetectOrientation()) {
            orientationListener = listener
            listener.enable()
        }
    }

    // ---- Picture-in-picture (phones) ------------------------------------

    private fun pipSupported(): Boolean =
        !isTv && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * Shrinking only makes sense with a video on and playing, and no card over
     * it. The daily countdown is enumerated here on purpose and NOT a blocker:
     * it is a chip over a playing video, not a card, and a kid with four
     * minutes left may still shrink the video to the corner — the chip simply
     * hides in the window (PlayerStage draws nothing in PiP) and the time-up
     * card arrives there as it always has.
     */
    private fun pipEligible(): Boolean =
        pipSupported() && player != null && everPlayed.value && wantsPlay.value &&
            timeUpMessage.value == null && blockedGently.value == null &&
            errorState.value == null && listenOnlyMessage.value == null &&
            endCard.value == null

    private fun pipParams(): android.app.PictureInPictureParams {
        val size = player?.videoSize
        val ratio = if (size != null && size.width > 0 && size.height > 0) {
            size.width.toFloat() / size.height
        } else 16f / 9f
        // The system refuses anything outside 1:2.39 .. 2.39:1.
        val clamped = ratio.coerceIn(1f / 2.39f, 2.39f)
        val playing = wantsPlay.value
        val toggle = android.app.PendingIntent.getBroadcast(
            this, if (playing) 1 else 2,
            android.content.Intent(PIP_ACTION).setPackage(packageName)
                .putExtra(PIP_EXTRA_PLAY, !playing),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val action = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(
                this,
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            ),
            if (playing) "Pause" else "Play",
            if (playing) "Pause the video" else "Play the video",
            toggle
        )
        val builder = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational((clamped * 10_000).toInt(), 10_000))
            .setActions(listOf(action))
        videoBounds?.takeIf { !it.isEmpty }?.let { builder.setSourceRectHint(it) }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            // The home gesture shrinks the video on its own (a smooth
            // animation instead of the app vanishing and a window popping
            // up), but only while there is a playing video to shrink.
            builder.setAutoEnterEnabled(pipEligible()).setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun refreshPipParams() {
        if (!pipSupported() || player == null) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    /** Shrink into the PiP window; false when that isn't possible right now. */
    private fun enterPip(): Boolean {
        if (!pipEligible()) return false
        hideControls()
        trackPanel.value = TvTrackPanel.Hidden
        return runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Home on Android 8–11: shrink by hand. 12+ auto-enters (see pipParams).
        if (android.os.Build.VERSION.SDK_INT < 31) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        when {
            isInPictureInPictureMode -> {
                hideControls()
                trackPanel.value = TvTrackPanel.Hidden
            }
            // The window's ✕: the system stops the activity without finishing
            // it. Finish properly so playback ends and the shelf is what's left.
            lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED -> finish()
            // Expanded back to full size: show where things stand.
            else -> pokeControls()
        }
    }

    override fun onStart() {
        super.onStart()
        io.yosemitekids.app.data.AppVisibility.startedActivities++
    }

    override fun onStop() {
        io.yosemitekids.app.data.AppVisibility.startedActivities--
        super.onStop()
        // Screen off and switching to another app are the same "listening,
        // not watching" state (family listening rate set): keep the sound
        // going either way. Only actually leaving the player — back/close,
        // which finishes the activity — stops playback.
        // The foreground-service start from onStop rides the "leaving a
        // user-visible state" exemption; if an OEM's timing disagrees, losing
        // the race must mean "this leave pauses", not a crash.
        // Not from the PiP window, though: it stops for the screen going off
        // or its own ✕, and a visible window must never be swapped to the
        // audio-only stream underneath — those leaves simply pause.
        if (!isFinishing && !inPip.value) runCatching { enterListenMode() }
        // Inline, not dispatched: lifecycleScope dies with the activity, and the
        // exit position is the one write that must not be dropped. Nothing is
        // animating by now, so the blocking commit is harmless.
        val exo = player
        val url = currentPageUrl
        if (exo != null && url != null && exo.duration > 0) {
            history.save(url, exo.currentPosition, exo.duration)
        }
        // Watching counts as presence — the who's-watching screen must not
        // re-ask right after a long video just because home sat idle.
        io.yosemitekids.app.data.ActiveProfileStore(this).touch()
        // Listen mode is the whole point of not pausing here; leaving with
        // the feature off, the player paused, or the activity finishing
        // still pauses as always.
        if (!listenActive) player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (live?.get() === this) live = null
        orientationListener?.disable()
        orientationListener = null
        pipReceiver?.let { runCatching { unregisterReceiver(it) } }
        pipReceiver = null
        endCardJob?.cancel()
        resolveJob = null
        // A replacement player (LAN /play, a tapped notification) is already
        // created by now — only tear down the handler this instance installed.
        if (RemotePlayerControl.owner === remoteToken) {
            RemotePlayerControl.handler = null
            RemotePlayerControl.owner = null
        }
        io.yosemitekids.app.data.NowPlaying.clear()
        ListenService.stop(this)
        if (ListenService.player === player) ListenService.player = null
        player?.release()
        player = null
    }
}

/** A transient kid-facing message; `at` gives repeats a fresh identity. */
private data class Notice(val text: String, val at: Long = System.currentTimeMillis())

private enum class TvTrackPanel { Hidden, Toolbar, Audio, Subtitles }

/**
 * The TV toolbar's slots (▼ from the player), left to right — the order the
 * cursor walks them AND the order the overlay draws them. One list on purpose:
 * the key handler `when`s over it and the overlay iterates it, so a slot added
 * here is a compile error until both know what it does. The cursor itself
 * stays an Int index into [entries]; nothing here takes focus (guard 37).
 */
private enum class TvToolbarSlot { Audio, Subtitles, Favorite, WatchLater, Queue, Channel }

/** How many "Similar" picks the tab under the video shows. */
private const val SIMILAR_MAX = 12

/** The three lists under the portrait video, as tabs. */
private enum class PlayerTab(val label: String) {
    UpNext("Up next"), MoreFromChannel("More from channel"), Similar("Similar")
}

/** Keys that would seek, step or toggle the player while an end card is up. Volume and Back pass. */
private val END_CARD_SWALLOWED_KEYS = setOf(
    android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
    android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    android.view.KeyEvent.KEYCODE_CHANNEL_UP,
    android.view.KeyEvent.KEYCODE_CHANNEL_DOWN,
    android.view.KeyEvent.KEYCODE_DPAD_UP,
    android.view.KeyEvent.KEYCODE_DPAD_DOWN
)

/** What the end-of-video card shows; see [PlayerActivity.showEndCard]. */
private data class EndCard(
    val nextTitle: String?,
    val nextThumb: String?,
    val nextChannel: String?,
    /** Its length, for the badge on the poster; null when the lineup did not say. */
    val nextDurationSeconds: Long?,
    val hasNext: Boolean,
    val secondsLeft: Int,
    /** What [secondsLeft] started from, so the ring knows how full it is. */
    val totalSeconds: Int,
    /** Up to three more from the same channel, tappable (phones). */
    val more: List<io.yosemitekids.app.data.Video> = emptyList()
)

/** A big ❤️ that pops up from the middle and fades — the "it's yours now" moment. */
@Composable
private fun BoxScope.HeartBurst(state: State<Long>) {
    val at by state
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(at) {
        if (at == 0L) return@LaunchedEffect
        visible = true
        delay(700)
        visible = false
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.scaleIn(
            initialScale = 0.3f,
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy
            )
        ) + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 1.4f),
        modifier = Modifier.align(Alignment.Center)
    ) {
        Text(
            "❤️",
            fontSize = androidx.compose.ui.unit.TextUnit(120f, androidx.compose.ui.unit.TextUnitType.Sp)
        )
    }
}

/**
 * Top-center pill that slides in with a notice for a few seconds, then slides
 * away. The last text is remembered so the exit animation has something to
 * show — the state is already null by the time it plays.
 */
@Composable
private fun BoxScope.NoticeOverlay(state: MutableState<Notice?>) {
    val n = state.value
    var shown by remember { mutableStateOf<Notice?>(null) }
    if (n != null) shown = n
    androidx.compose.animation.AnimatedVisibility(
        visible = n != null,
        enter = androidx.compose.animation.slideInVertically { -it } +
            androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { -it } +
            androidx.compose.animation.fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
    ) {
        val tokens = kidTokens
        Text(
            shown?.text.orEmpty(),
            color = tokens.onArtwork,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .background(tokens.artworkScrim, shape = RoundedCornerShape(24.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
    LaunchedEffect(n) {
        if (n == null) return@LaunchedEffect
        delay(4_000)
        if (state.value == n) state.value = null
    }
}

/**
 * The "◀◀ 10 s" / "10 s ▶▶" that pops on the edge a double tap landed on and
 * fades right out — the same feedback the YouTube app gives, so the gesture
 * is learnable by watching what happens.
 */
@Composable
private fun BoxScope.SeekRipple(state: State<Pair<Int, Long>?>) {
    val fb by state
    var visible by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    LaunchedEffect(fb) {
        val f = fb ?: return@LaunchedEffect
        last = f
        visible = true
        delay(650)
        visible = false
    }
    val delta = last?.first ?: 0
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn() +
            androidx.compose.animation.scaleIn(initialScale = 0.7f),
        exit = androidx.compose.animation.fadeOut(),
        modifier = Modifier
            .align(if (delta < 0) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(horizontal = 48.dp)
    ) {
        val tokens = kidTokens
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(tokens.onArtwork.copy(alpha = 0.35f))
        ) {
            Text(
                if (delta < 0) "◀◀\n${-delta} s" else "▶▶\n$delta s",
                color = tokens.onArtwork,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

/**
 * Ink for a glyph on a button filled with [KidTokens.onArtwork] — the pause
 * bars, a lit CC. Not `onBackground`: that is the page's text colour and
 * flips to near-white on the dark look, where the button is still white.
 */
private fun KidTokens.inkOnArtworkFill(): Color = readableOn(onArtwork)

/** A pill-shaped kid button: big, rounded, one job. On TV the remote's cursor
 *  highlights it instead of touch focus (see the activity's key handling). */
@Composable
internal fun KidButton(
    label: String,
    primary: Boolean,
    highlighted: Boolean,
    isTv: Boolean,
    onClick: () -> Unit
) {
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (highlighted) 1.06f else 1f, label = "kidButtonScale"
    )
    val tokens = kidTokens
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .scale(scale)
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (primary) MaterialTheme.colorScheme.primary else tokens.onArtwork.copy(alpha = 0.2f))
            .border(
                width = if (highlighted) 3.dp else 0.dp,
                color = if (highlighted) tokens.onArtwork else Color.Transparent,
                shape = RoundedCornerShape(28.dp)
            )
            // Touch only: a focusable here would steal the remote's keys from
            // the activity, which is where TV cursoring lives.
            .then(if (isTv) Modifier else Modifier.clickable { onClick() })
            .padding(horizontal = 28.dp)
    ) {
        Text(
            label,
            color = if (primary) MaterialTheme.colorScheme.onPrimary else tokens.onArtwork,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 1
        )
    }
}

/**
 * Bedtime / break / all-done / blocked: a big friendly emoji, the message,
 * and one button. Auto-closes as before; the button is for the kid who
 * doesn't want to wait, and the OK hint is the TV's version of the button.
 */
@Composable
internal fun BlockedCard(message: String, isTv: Boolean, onOk: () -> Unit) {
    val emoji = listOf("🌙", "⏰", "🌟", "🎉", "💛").firstOrNull { it in message } ?: "⏰"
    val text = message.replace(emoji, "").trim()
    val pop = remember { androidx.compose.animation.core.Animatable(0.6f) }
    LaunchedEffect(Unit) {
        pop.animateTo(
            1f,
            androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessLow
            )
        )
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Text(
            emoji,
            fontSize = androidx.compose.ui.unit.TextUnit(88f, androidx.compose.ui.unit.TextUnitType.Sp),
            modifier = Modifier.scale(pop.value)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text,
            color = kidTokens.onArtwork,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(28.dp))
        KidButton(
            if (isTv) "Okay 👍  (press OK)" else "Okay 👍",
            primary = true, highlighted = isTv, isTv = isTv, onClick = onOk
        )
    }
}

/** Playback failed on the video the kid actually pressed: a way forward, not a stack trace. */
@Composable
private fun ErrorCard(isTv: Boolean, cursor: Int, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Text(
            "😕",
            fontSize = androidx.compose.ui.unit.TextUnit(72f, androidx.compose.ui.unit.TextUnitType.Sp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Hmm, this video won't play right now.",
            color = kidTokens.onArtwork,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Try again, or pick a different one.",
            color = kidTokens.onArtwork.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            KidButton("🔄  Try again", primary = true, highlighted = isTv && cursor == 0, isTv = isTv, onClick = onRetry)
            KidButton("Go back", primary = false, highlighted = isTv && cursor == 1, isTv = isTv, onClick = onBack)
        }
    }
}

/**
 * The end-of-video card over the held last frame. Mid-lineup it previews what
 * comes next — the poster with the play disc in it, the title, the channel —
 * and counts down in a ring; on the last video it celebrates and offers a
 * replay. Auto-advance is the default, but never silent — the countdown is
 * the whole point.
 *
 * The ring is the ACTION colour and never amber: amber on this screen is the
 * daily countdown, and "playing in 5" is not a warning. The design draws no
 * buttons under it; the two stay, because they are what the TV cursor walks
 * (handleTwoButtonKey) and what a finger presses without hunting for the
 * poster — on a phone the poster is a third target for the same thing.
 */
@Composable
private fun BoxScope.EndCardOverlay(
    card: EndCard,
    isTv: Boolean,
    cursor: Int,
    channel: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onPick: (io.yosemitekids.app.data.Video) -> Unit,
    /** The portrait video slot: no poster, no "More from" — the list below the video has both. */
    compact: Boolean = false
) {
    val showMore = !isTv && !compact && card.more.isNotEmpty()
    val tokens = kidTokens
    val onArtwork = tokens.onArtwork
    val fraction = if (card.totalSeconds > 0) card.secondsLeft.toFloat() / card.totalSeconds else 0f
    Box(
        Modifier.fillMaxSize().background(tokens.artworkScrim.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center
    ) {
        // Side by side: a landscape phone is wide and short, and a stacked
        // card with the "More from" row under it ran off both edges.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(44.dp),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (card.hasNext) {
                    MicroLabel("Up next", onArtwork.copy(alpha = 0.7f), isTv)
                    Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
                    if (!compact) {
                        // TV geometry is PROVISIONAL: the handoff's 720p frame
                        // through tvUnits, not yet seen on a television.
                        val posterW = if (isTv) tvUnits(420f) else 208.dp
                        val posterH = if (isTv) tvUnits(236f) else 117.dp
                        val disc = if (isTv) tvUnits(96f) else 64.dp
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(posterW, posterH)
                                .clip(RoundedCornerShape(16.dp))
                                .background(onArtwork.copy(alpha = 0.2f))
                                .border(3.dp, tokens.action, RoundedCornerShape(16.dp))
                                // Touch only: on TV the cursor is on the buttons below.
                                .then(if (isTv) Modifier else Modifier.clickable { onPrimary() })
                        ) {
                            PosterImage(card.nextThumb, card.nextTitle, Modifier.fillMaxSize())
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(disc).clip(CircleShape).background(tokens.action)
                            ) {
                                PlayPauseGlyph(playing = false, size = disc * 0.5f, color = tokens.onAction)
                            }
                            card.nextDurationSeconds?.let {
                                DurationBadge(formatClock(it), Modifier.align(Alignment.BottomEnd).padding(8.dp))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        card.nextTitle ?: "The next video",
                        color = onArtwork,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        style = if (isTv) {
                            MaterialTheme.typography.headlineSmall.copy(
                                fontSize = tvTypeUnits(26f), fontWeight = FontWeight.Bold
                            )
                        } else MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.width(if (isTv) tvUnits(560f) else if (compact) 300.dp else 420.dp)
                    )
                    card.nextChannel?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it,
                            color = onArtwork.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                    EndCountdownPill(
                        "Playing in ${card.secondsLeft} second" + if (card.secondsLeft == 1) "" else "s",
                        fraction, card.secondsLeft, isTv
                    )
                    Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        KidButton("▶  Play now", primary = true, highlighted = isTv && cursor == 0, isTv = isTv, onClick = onPrimary)
                        KidButton("Not now", primary = false, highlighted = isTv && cursor == 1, isTv = isTv, onClick = onSecondary)
                    }
                } else {
                    Text(
                        "🎉",
                        fontSize = TextUnit(if (compact) 36f else 64f, TextUnitType.Sp)
                    )
                    Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
                    Text(
                        "That's the end!",
                        color = onArtwork,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                    EndCountdownPill("Back to the shelf in ${card.secondsLeft}", fraction, card.secondsLeft, isTv)
                    Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        KidButton("↺  Watch again", primary = true, highlighted = isTv && cursor == 0, isTv = isTv, onClick = onPrimary)
                        KidButton("✓  All done", primary = false, highlighted = isTv && cursor == 1, isTv = isTv, onClick = onSecondary)
                    }
                }
            }
            // "What else is there" is one tap away — three more from the same
            // channel. Touch only: the TV's two-button cursor stays simple.
            if (showMore) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "More from $channel",
                        color = onArtwork.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelLarge
                    )
                    card.more.forEach { v ->
                        SmallVideoRow(
                            v.title, v.thumbnailUrl, Modifier.width(360.dp),
                            durationSeconds = v.durationSeconds
                        ) { onPick(v) }
                    }
                }
            }
        }
    }
}

/** "Playing in 5 seconds" behind a ring that empties as they go — the action colour, see [EndCardOverlay]. */
@Composable
private fun EndCountdownPill(text: String, fraction: Float, seconds: Int, isTv: Boolean) {
    val tokens = kidTokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(tokens.artworkScrim.copy(alpha = 0.9f), RoundedCornerShape(24.dp))
            .padding(start = 8.dp, end = 18.dp, top = 6.dp, bottom = 6.dp)
    ) {
        CountdownRing(
            fraction = fraction,
            color = tokens.action,
            track = tokens.onArtwork.copy(alpha = 0.14f),
            size = if (isTv) 36.dp else 30.dp,
            stroke = 3.dp
        ) {
            Text(
                "$seconds",
                color = tokens.onArtwork,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                )
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = tokens.onArtwork,
            maxLines = 1,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
    }
}

/** The design's mono uppercase micro-label: "UP NEXT", "· TODAY", "30 VIDEOS". */
@Composable
private fun MicroLabel(text: String, color: Color, isTv: Boolean, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = color,
        maxLines = 1,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = if (isTv) 12.sp else 9.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = TextUnit(0.14f, TextUnitType.Em),
            fontFamily = FontFamily.Monospace
        ),
        modifier = modifier
    )
}

/** "26:34" on a poster's corner, the same badge every tile in the app wears. */
@Composable
private fun DurationBadge(text: String, modifier: Modifier = Modifier) {
    val tokens = kidTokens
    Text(
        text,
        color = tokens.onArtwork,
        maxLines = 1,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
        ),
        modifier = modifier
            .background(tokens.artworkScrim, RoundedCornerShape(5.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

/**
 * A poster with its title beside it — the list shape of YouTube's portrait
 * "Up next", shared by the end card's "More from" column and the portrait
 * layout's tabs. Kid-sized: a 76 dp poster is a target, not a thumbnail.
 */
@Composable
private fun SmallVideoRow(
    title: String,
    thumb: String?,
    modifier: Modifier = Modifier,
    /** "Channel · 4:53" or "today", the quiet line under the title. */
    subtitle: String? = null,
    /** Length, as the badge on the poster's corner; null or 0 draws none. */
    durationSeconds: Long? = null,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(4.dp)
    ) {
        // Scheme colours, not the on-artwork token: this row sits on the page
        // under the portrait video as well as on the end card's scrim, and
        // white text on the light look's paper would vanish.
        Box(
            Modifier
                .width(136.dp)
                .height(76.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            PosterImage(thumb, title, Modifier.fillMaxSize())
            if (durationSeconds != null && durationSeconds > 0) {
                DurationBadge(formatClock(durationSeconds), Modifier.align(Alignment.BottomEnd).padding(5.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * One control on the stage: a rounded tile over the scrim with a glyph in
 * it. Phones tap it; on TV it is a cursor slot and [highlighted] is where
 * the remote is — nothing here takes focus (guard 37). The tile fills only
 * for the cursor; a control's own state (a heart that is already theirs)
 * colours the glyph instead, so state and cursor cannot be confused.
 *
 * [content] is handed the glyph colour and the ground it sits on, because a
 * lit CC box is drawn in the glyph colour with its letters in the ground's.
 */
@Composable
private fun OverlayTile(
    label: String,
    isTv: Boolean,
    highlighted: Boolean,
    onClick: (() -> Unit)?,
    /** The glyph's colour at rest — the state colour, or plain on-artwork. */
    tint: Color,
    size: Dp = 44.dp,
    content: @Composable (glyph: Color, ground: Color) -> Unit
) {
    val tokens = kidTokens
    val ground = if (highlighted) tokens.onArtwork else tokens.artworkScrim.copy(alpha = 0.55f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(ground)
            .then(if (!isTv && onClick != null) Modifier.clickable { onClick() } else Modifier)
            .semantics { contentDescription = label }
    ) {
        content(if (highlighted) tokens.inkOnArtworkFill() else tint, ground)
    }
}

/**
 * "Autoplay ON" — the parent's switch, shown so a kid knows what happens at
 * the end of this one, and READ-ONLY on purpose: pressing it would write a
 * parent's setting from the kid's side. Their own lever is the moon ("stop
 * after this one"). On TV it is not a cursor slot for the same reason.
 */
@Composable
private fun AutoplayPill(on: Boolean, isTv: Boolean, compact: Boolean) {
    val tokens = kidTokens
    val h = if (isTv) 44.dp else if (compact) 30.dp else 34.dp
    val shape = RoundedCornerShape(h / 2)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(h)
            .clip(shape)
            .background(tokens.artworkScrim.copy(alpha = 0.55f))
            .border(1.5.dp, if (on) tokens.action else tokens.onArtwork.copy(alpha = 0.3f), shape)
            .padding(horizontal = if (isTv) 14.dp else 10.dp)
            .semantics {
                contentDescription =
                    if (on) "Autoplay is on. A grown-up's setting." else "Autoplay is off. A grown-up's setting."
            }
    ) {
        Text(
            "Autoplay",
            color = tokens.onArtwork,
            maxLines = 1,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold, fontSize = if (isTv) 15.sp else 12.5.sp
            )
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (on) "ON" else "OFF",
            color = if (on) tokens.action else tokens.onArtwork.copy(alpha = 0.6f),
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                fontSize = if (isTv) 11.sp else 9.5.sp
            )
        )
    }
}

/** The CC box: outlined at rest, filled in the glyph colour when captions are on. */
@Composable
private fun CcGlyph(lit: Boolean, glyph: Color, ground: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 26.dp, height = 18.dp)
            .background(if (lit) glyph else Color.Transparent, RoundedCornerShape(3.dp))
            .border(2.dp, glyph, RoundedCornerShape(3.dp))
    ) {
        Text(
            "CC",
            color = if (lit) ground else glyph,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp)
        )
    }
}

/** A speaker and its sound, drawn: the TV's audio-track slot. */
@Composable
private fun AudioGlyph(ink: Color) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .offset(x = (-7).dp)
                .size(width = 7.dp, height = 12.dp)
                .background(ink, RoundedCornerShape(1.dp))
        )
        Box(
            Modifier
                .offset(x = 1.dp)
                .size(width = 10.dp, height = 18.dp)
                .clip(
                    androidx.compose.foundation.shape.GenericShape { size, _ ->
                        moveTo(0f, size.height * 0.28f)
                        lineTo(size.width * 0.55f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(size.width, size.height)
                        lineTo(size.width * 0.55f, size.height)
                        lineTo(0f, size.height * 0.72f)
                        close()
                    }
                )
                .background(ink)
        )
        Text(
            ")))",
            color = ink,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(start = 22.dp)
        )
    }
}

/** The channel's face in a slot: selecting it leaves for the channel page. */
@Composable
private fun AvatarGlyph(avatarUrl: String?, ground: Color) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(ground)
    ) {
        if (avatarUrl != null) {
            PosterImage(avatarUrl, "Channel", Modifier.fillMaxSize())
        } else {
            Text(
                "📺",
                modifier = Modifier.align(Alignment.Center),
                fontSize = TextUnit(18f, TextUnitType.Sp)
            )
        }
    }
}

/** Picture-in-picture: a frame with a small filled one in its corner. Drawn, like the transport glyphs. */
@Composable
private fun PipGlyph(size: Dp, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.09f
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, h * 0.15f + stroke / 2),
            size = androidx.compose.ui.geometry.Size(w - stroke, h * 0.7f - stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
        )
        drawRoundRect(
            color = color,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.48f),
            size = androidx.compose.ui.geometry.Size(w * 0.38f, h * 0.26f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.04f)
        )
    }
}

/** ⛶: four corner brackets pointing out (go full screen) or in (come back). */
@Composable
private fun FullscreenGlyph(expand: Boolean, size: Dp, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val stroke = w * 0.1f
        val arm = w * 0.28f
        val inset = w * 0.12f
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        fun corner(cx: Float, cy: Float, dx: Float, dy: Float) {
            // Out: the L sits at the corner and opens inward. In: it sits
            // toward the middle and its arms point at the corner.
            val ox = if (expand) cx else cx + dx * arm * 1.1f
            val oy = if (expand) cy else cy + dy * arm * 1.1f
            val sx = if (expand) dx else -dx
            val sy = if (expand) dy else -dy
            drawLine(color, androidx.compose.ui.geometry.Offset(ox, oy),
                androidx.compose.ui.geometry.Offset(ox + sx * arm, oy), stroke, cap)
            drawLine(color, androidx.compose.ui.geometry.Offset(ox, oy),
                androidx.compose.ui.geometry.Offset(ox, oy + sy * arm), stroke, cap)
        }
        corner(inset, inset, 1f, 1f)
        corner(w - inset, inset, -1f, 1f)
        corner(inset, w - inset, 1f, -1f)
        corner(w - inset, w - inset, -1f, -1f)
    }
}

/** Play triangle or pause bars, drawn (no icon pack: the extended icon set is megabytes of dex). */
@Composable
private fun PlayPauseGlyph(playing: Boolean, size: Dp, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        if (playing) {
            val barW = w * 0.22f
            val gap = w * 0.16f
            val left = (w - barW * 2 - gap) / 2
            drawRoundRect(
                color, topLeft = androidx.compose.ui.geometry.Offset(left, h * 0.18f),
                size = androidx.compose.ui.geometry.Size(barW, h * 0.64f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.3f)
            )
            drawRoundRect(
                color, topLeft = androidx.compose.ui.geometry.Offset(left + barW + gap, h * 0.18f),
                size = androidx.compose.ui.geometry.Size(barW, h * 0.64f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW * 0.3f)
            )
        } else {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.30f, h * 0.18f)
                lineTo(w * 0.84f, h * 0.50f)
                lineTo(w * 0.30f, h * 0.82f)
                close()
            }
            drawPath(path, color)
        }
    }
}

/** ⏮ / ⏭ drawn: a bar and a triangle pointing at it. */
@Composable
private fun SkipGlyph(forward: Boolean, size: Dp, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val barW = w * 0.12f
        val path = androidx.compose.ui.graphics.Path()
        if (forward) {
            path.moveTo(w * 0.18f, h * 0.22f); path.lineTo(w * 0.66f, h * 0.50f)
            path.lineTo(w * 0.18f, h * 0.78f); path.close()
            drawPath(path, color)
            drawRect(color, androidx.compose.ui.geometry.Offset(w * 0.70f, h * 0.22f),
                androidx.compose.ui.geometry.Size(barW, h * 0.56f))
        } else {
            path.moveTo(w * 0.82f, h * 0.22f); path.lineTo(w * 0.34f, h * 0.50f)
            path.lineTo(w * 0.82f, h * 0.78f); path.close()
            drawPath(path, color)
            drawRect(color, androidx.compose.ui.geometry.Offset(w * 0.18f, h * 0.22f),
                androidx.compose.ui.geometry.Size(barW, h * 0.56f))
        }
    }
}

/**
 * The scrubber: dim track, buffered band, SponsorBlock green marks, red
 * played bar and a knob. On phones it's live — drag the knob or tap the bar —
 * with a fatter hit area than the 4 dp line suggests, and the knob follows the
 * finger (the seek lands on release, so a drag doesn't stutter the video).
 */
@Composable
private fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    sponsorSegments: List<io.yosemitekids.app.data.SponsorBlock.Segment>,
    interactive: Boolean,
    scrubFraction: Float?,
    onScrubChange: (Float?) -> Unit,
    onSeekTo: (Long) -> Unit
) {
    val fraction = scrubFraction ?: (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val latestScrub = rememberUpdatedState(scrubFraction)
    val knob = if (interactive) 18.dp else 12.dp
    val gestures = if (!interactive) Modifier else Modifier
        .pointerInput(durationMs) {
            detectHorizontalDragGestures(
                onDragStart = { onScrubChange((it.x / size.width).coerceIn(0f, 1f)) },
                onDragEnd = {
                    latestScrub.value?.let { onSeekTo((it * durationMs).toLong()) }
                    onScrubChange(null)
                },
                onDragCancel = { onScrubChange(null) },
                onHorizontalDrag = { change, _ ->
                    change.consume()
                    onScrubChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            )
        }
        .pointerInput(durationMs) {
            detectTapGestures { offset ->
                onSeekTo(((offset.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
            }
        }
    val onArtwork = kidTokens.onArtwork
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(if (interactive) 36.dp else 18.dp)
            .then(gestures)
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp)
                .background(onArtwork.copy(alpha = 0.25f))
        )
        Box(
            Modifier.align(Alignment.CenterStart)
                .fillMaxWidth((bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f))
                .height(4.dp).background(onArtwork.copy(alpha = 0.55f))
        )
        // Green skip marks sit under playback state: an already-viewed
        // stretch stays red, while upcoming sponsor stretches stay green.
        sponsorSegments.forEach { s ->
            val start = (s.startMs.toFloat() / durationMs).coerceIn(0f, 1f)
            val end = (s.endMs.toFloat() / durationMs).coerceIn(0f, 1f)
            if (end > start) Box(
                Modifier.align(Alignment.CenterStart)
                    .padding(start = maxWidth * start)
                    .width(maxWidth * (end - start))
                    .height(4.dp).background(SponsorSegmentGreen)
            )
        }
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxWidth(fraction).height(4.dp)
                .background(WatchedProgressRed)
        )
        Box(
            Modifier.align(Alignment.CenterStart)
                .padding(start = (maxWidth - knob) * fraction)
                .size(knob).clip(CircleShape).background(WatchedProgressRed)
        )
    }
}

/** How much of the TV's top row the daily countdown chip may take, so the title stops short of it. */
private val TV_COUNTDOWN_RESERVE = tvUnits(400f)

/**
 * The kid-sized player chrome, one composable for both form factors and both
 * phone layouts, told what it may do rather than which device it is on:
 * phones get the transport, the live scrubber and tappable tiles; on TV the
 * remote is the transport, the same tiles are cursor slots
 * ([TvToolbarSlot], walked by the activity's onKeyDown — nothing here takes
 * focus, guard 37), and the transport row is a picture of the state.
 *
 * It shows for a few seconds after any key or tap, stays while paused (a
 * frozen frame with nothing on it reads as broken), while a scrub is in
 * progress, and while a TV track sheet is open.
 */
@Composable
private fun BoxScope.PlayerControlsOverlay(
    isTv: Boolean,
    /**
     * The portrait video slot: title, channel, tiles and the countdown live
     * on the page below the video there, so the chrome is only what steers
     * playback, at sizes that fit a 16:9 strip.
     */
    compact: Boolean = false,
    /** Shrink into the PiP window (phones). */
    onMinimise: (() -> Unit)? = null,
    /** ⛶ (phones): go full screen from the slot, come back from the stage. */
    onToggleFullscreen: (() -> Unit)? = null,
    visibleUntil: State<Long>,
    wantsPlay: State<Boolean>,
    title: String,
    channel: String,
    /** When the video came out, for the release-time line; null hides it (the parent's switch, or an unknown date). */
    publishedAt: Long?,
    /** The daily countdown chip is riding the stage's top-right corner: the TV's title line keeps clear of it. */
    countdownUp: Boolean,
    /** The parent's switch, shown and never written here (see [AutoplayPill]). */
    autoplayOn: Boolean,
    sponsorSegments: List<io.yosemitekids.app.data.SponsorBlock.Segment>,
    panelState: State<TvTrackPanel>,
    cursorState: State<Int>,
    playback: YouTubeRepository.Playback?,
    selectedAudio: Int,
    selectedSubtitle: Int,
    captionsOn: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    nextTitle: String?,
    avatarUrl: String?,
    onOpenChannel: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    inWatchLater: Boolean,
    onToggleWatchLater: () -> Unit,
    inQueue: Boolean,
    onToggleQueue: () -> Unit,
    stopAfterThis: Boolean,
    onToggleStopAfter: () -> Unit,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Int) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleCaptions: () -> Unit,
    onPoke: () -> Unit,
    playerProvider: () -> ExoPlayer?
) {
    val until by visibleUntil
    val playing by wantsPlay
    val panel by panelState
    val cursor by cursorState
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun wanted() = now < until || !playing || scrubFraction != null || panel != TvTrackPanel.Hidden
    val visible = wanted()
    LaunchedEffect(until, playing, scrubFraction != null, panel) {
        // Never breaks. It used to stop the moment the chrome faded, and
        // the clock it feeds then froze wherever it was — right under the
        // countdown chip, which stays up when this fades. Slower while
        // hidden, because nothing drawn needs four reads a second then.
        while (isActive) {
            now = System.currentTimeMillis()
            playerProvider()?.let {
                positionMs = it.currentPosition.coerceAtLeast(0)
                durationMs = it.duration.coerceAtLeast(0)
                bufferedMs = it.bufferedPosition.coerceAtLeast(0)
            }
            delay(if (wanted()) 250 else 1_000)
        }
    }

    val edge = if (isTv) 32.dp else if (compact) 8.dp else 16.dp
    val tokens = kidTokens
    val onArtwork = tokens.onArtwork
    val ink = tokens.inkOnArtworkFill()
    val onToolbar = isTv && panel == TvTrackPanel.Toolbar
    val slot = TvToolbarSlot.entries[cursor.coerceIn(0, TvToolbarSlot.entries.lastIndex)]
    androidx.compose.animation.AnimatedVisibility(
        visible = visible && durationMs > 0,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .height(if (isTv) 160.dp else if (compact) 72.dp else 140.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(tokens.artworkScrim.copy(alpha = 0.7f), Color.Transparent)
                        )
                    )
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .height(if (isTv) 220.dp else if (compact) 96.dp else 180.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color.Transparent, tokens.artworkScrim)
                        )
                    )
            )
            // Top row. TV: the title, the channel and when it came out, as the
            // design's top scrim. Phone: back, then (landscape only) the same
            // line at phone size, then Autoplay, CC and the PiP button.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(horizontal = edge, vertical = if (isTv) 24.dp else if (compact) 2.dp else 10.dp)
            ) {
                if (!isTv) {
                    androidx.compose.material3.IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(if (compact) 44.dp else 52.dp)
                    ) {
                        Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = onArtwork,
                            modifier = Modifier.size(if (compact) 26.dp else 30.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (compact) Spacer(Modifier.weight(1f))
                if (isTv) {
                    // TV geometry is PROVISIONAL (tvUnits): not yet seen on a set.
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(end = if (countdownUp) TV_COUNTDOWN_RESERVE else 0.dp)
                    ) {
                        Text(
                            title,
                            color = onArtwork,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontSize = tvTypeUnits(30f), fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(Modifier.height(tvUnits(8f)))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ChannelArt(
                                avatarUrl, channel, tvUnits(32f),
                                radius = tvUnits(8f), fallback = onArtwork.copy(alpha = 0.2f)
                            )
                            Spacer(Modifier.width(tvUnits(10f)))
                            if (channel.isNotBlank()) Text(
                                channel,
                                color = onArtwork.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = tvTypeUnits(18f), fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            relativeAge(publishedAt)?.let { age ->
                                Spacer(Modifier.width(tvUnits(12f)))
                                MicroLabel("· $age", onArtwork.copy(alpha = 0.6f), isTv = true)
                            }
                        }
                    }
                } else if (!compact) {
                    // The channel's face: tap to see the rest of its videos.
                    if (avatarUrl != null) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(onArtwork.copy(alpha = 0.2f))
                                .clickable { onOpenChannel() }
                        ) {
                            PosterImage(avatarUrl, channel, Modifier.fillMaxSize())
                        }
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .then(if (avatarUrl == null) Modifier else Modifier.clickable { onOpenChannel() })
                    ) {
                        Text(
                            title,
                            color = onArtwork,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        val line = metaLine(channel, relativeAge(publishedAt))
                        if (line.isNotBlank()) Text(
                            if (avatarUrl != null) "$line  ›" else line,
                            color = onArtwork.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                if (!isTv) {
                    val tile = if (compact) 36.dp else 44.dp
                    AutoplayPill(autoplayOn, isTv = false, compact = compact)
                    if (playback?.subtitles?.isNotEmpty() == true) {
                        Spacer(Modifier.width(8.dp))
                        OverlayTile(
                            if (captionsOn) "Subtitles on" else "Subtitles off",
                            isTv = false, highlighted = false, onClick = onToggleCaptions,
                            tint = onArtwork, size = tile
                        ) { glyph, ground -> CcGlyph(captionsOn, glyph, ground) }
                    }
                    if (onMinimise != null) {
                        // Shrink to the floating window and keep browsing.
                        Spacer(Modifier.width(8.dp))
                        OverlayTile(
                            "Keep watching in a small window",
                            isTv = false, highlighted = false, onClick = onMinimise,
                            tint = onArtwork, size = tile
                        ) { glyph, _ -> PipGlyph(size = 24.dp, color = glyph) }
                    }
                }
            }
            // Middle: the phone's transport. ⏮ ⏭ step the LINEUP, not ±10 s —
            // that is what the glyphs mean on every player a kid has seen,
            // and the ±10 s hop is the double tap on the video's edges (the
            // touch layer in PlayerStage). Restyled to the design's sizes;
            // the behaviour is the one they already learned.
            if (!isTv) {
                val side = if (compact) 44.dp else 52.dp
                val main = if (compact) 64.dp else 78.dp
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 28.dp else 40.dp),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(side)
                            .clip(CircleShape)
                            .background(tokens.artworkScrim.copy(alpha = 0.55f))
                            .then(
                                if (hasPrevious) Modifier.clickable { onPrevious() }
                                else Modifier
                            )
                            .semantics { contentDescription = "Previous video" }
                    ) {
                        SkipGlyph(forward = false, size = side / 2,
                            color = if (hasPrevious) onArtwork else onArtwork.copy(alpha = 0.4f))
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(main)
                            .clip(CircleShape)
                            .background(onArtwork)
                            .clickable { onTogglePlay() }
                            .semantics { contentDescription = if (playing) "Pause" else "Play" }
                    ) {
                        PlayPauseGlyph(playing = playing, size = main * 0.55f, color = ink)
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(side)
                            .clip(CircleShape)
                            .background(tokens.artworkScrim.copy(alpha = 0.55f))
                            .then(
                                if (hasNext) Modifier.clickable { onNext() }
                                else Modifier
                            )
                            .semantics { contentDescription = "Next video" }
                    ) {
                        SkipGlyph(forward = true, size = side / 2,
                            color = if (hasNext) onArtwork else onArtwork.copy(alpha = 0.4f))
                    }
                }
            }
            if (isTv && (panel == TvTrackPanel.Audio || panel == TvTrackPanel.Subtitles)) {
                TvTrackSheet(
                    panel = panel,
                    cursor = cursor,
                    playback = playback,
                    selectedAudio = selectedAudio,
                    selectedSubtitle = selectedSubtitle,
                    captionsOn = captionsOn
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = edge, vertical = if (isTv) 20.dp else if (compact) 0.dp else 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isTv) {
                        TvTransportState(playing, hasPrevious, hasNext, onArtwork, ink, tokens)
                    } else {
                        val shownPos = scrubFraction?.let { (it * durationMs).toLong() } ?: positionMs
                        Text(
                            formatClock(shownPos / 1000) + " / " + formatClock(durationMs / 1000),
                            color = onArtwork,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                fontSize = if (compact) 14.sp else 16.sp
                            )
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    // The line that names things: the slot the remote is on
                    // (the glyphs alone are a guess from the couch), else
                    // what plays next. One slot, so neither can crowd the row.
                    val label = when {
                        onToolbar -> when (slot) {
                            TvToolbarSlot.Audio -> "Audio"
                            TvToolbarSlot.Subtitles -> "Subtitles"
                            TvToolbarSlot.Favorite ->
                                if (isFavorite) "In your Favorites" else "Add to Favorites"
                            TvToolbarSlot.WatchLater ->
                                if (inWatchLater) "Saved for later" else "Watch later"
                            TvToolbarSlot.Queue ->
                                if (inQueue) "In your Up next" else "Add to Up next"
                            TvToolbarSlot.Channel -> "More from $channel"
                        }
                        hasNext -> "Next: " + (nextTitle ?: "one more")
                        else -> null
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        if (label != null) Text(
                            label,
                            color = onArtwork.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (isTv) 10.dp else 8.dp)
                    ) {
                        if (isTv) {
                            AutoplayPill(autoplayOn, isTv = true, compact = false)
                            // Drawn in the enum's order, which is the order the
                            // cursor walks: one list for both (see TvToolbarSlot).
                            TvToolbarSlot.entries.forEach { s ->
                                val here = onToolbar && s == slot
                                when (s) {
                                    TvToolbarSlot.Audio -> OverlayTile(
                                        "Audio", isTv = true, highlighted = here, onClick = null, tint = onArtwork
                                    ) { glyph, _ -> AudioGlyph(glyph) }
                                    TvToolbarSlot.Subtitles -> OverlayTile(
                                        "Subtitles", isTv = true, highlighted = here, onClick = null, tint = onArtwork
                                    ) { glyph, ground -> CcGlyph(captionsOn, glyph, ground) }
                                    TvToolbarSlot.Favorite -> OverlayTile(
                                        "Favorite", isTv = true, highlighted = here, onClick = null,
                                        tint = if (isFavorite) tokens.action else onArtwork
                                    ) { glyph, _ -> FavoriteGlyph(isFavorite, glyph) }
                                    TvToolbarSlot.WatchLater -> OverlayTile(
                                        "Watch later", isTv = true, highlighted = here, onClick = null,
                                        tint = if (inWatchLater) tokens.offline else onArtwork
                                    ) { glyph, _ -> Icon(YosemiteIcons.WatchLater, null, tint = glyph, modifier = Modifier.size(22.dp)) }
                                    TvToolbarSlot.Queue -> OverlayTile(
                                        "Up next", isTv = true, highlighted = here, onClick = null,
                                        tint = if (inQueue) tokens.action else onArtwork
                                    ) { glyph, _ -> Icon(YosemiteIcons.UpNext, null, tint = glyph, modifier = Modifier.size(22.dp)) }
                                    TvToolbarSlot.Channel -> OverlayTile(
                                        "Channel", isTv = true, highlighted = here, onClick = null, tint = onArtwork
                                    ) { _, _ -> AvatarGlyph(avatarUrl, onArtwork.copy(alpha = 0.2f)) }
                                }
                            }
                        } else if (!compact) {
                            // Landscape phone: the tiles the portrait page
                            // carries under the video live here instead, plus
                            // the moon, which is the kid's own lever.
                            OverlayTile(
                                if (isFavorite) "In your Favorites" else "Add to Favorites",
                                isTv = false, highlighted = false, onClick = onToggleFavorite,
                                tint = if (isFavorite) tokens.action else onArtwork
                            ) { glyph, _ -> FavoriteGlyph(isFavorite, glyph) }
                            OverlayTile(
                                if (inWatchLater) "Saved for later" else "Watch later",
                                isTv = false, highlighted = false, onClick = onToggleWatchLater,
                                tint = if (inWatchLater) tokens.offline else onArtwork
                            ) { glyph, _ -> Icon(YosemiteIcons.WatchLater, null, tint = glyph, modifier = Modifier.size(22.dp)) }
                            OverlayTile(
                                if (inQueue) "In your Up next" else "Add to Up next",
                                isTv = false, highlighted = false, onClick = onToggleQueue,
                                tint = if (inQueue) tokens.action else onArtwork
                            ) { glyph, _ -> Icon(YosemiteIcons.UpNext, null, tint = glyph, modifier = Modifier.size(22.dp)) }
                            // Lit in the action colour, not amber: armed is a
                            // choice the kid made, and amber here means time
                            // is running out.
                            OverlayTile(
                                "Stop after this one",
                                isTv = false, highlighted = false, onClick = onToggleStopAfter,
                                tint = if (stopAfterThis) tokens.action else onArtwork
                            ) { glyph, _ -> Icon(YosemiteIcons.Moon, null, tint = glyph, modifier = Modifier.size(22.dp)) }
                        }
                        if (!isTv && onToggleFullscreen != null) {
                            OverlayTile(
                                if (compact) "Full screen" else "Leave full screen",
                                isTv = false, highlighted = false, onClick = onToggleFullscreen,
                                tint = onArtwork, size = if (compact) 40.dp else 44.dp
                            ) { glyph, _ -> FullscreenGlyph(expand = compact, size = 22.dp, color = glyph) }
                        }
                    }
                }
                Spacer(Modifier.height(if (isTv) 12.dp else if (compact) 0.dp else 4.dp))
                val scrubber: @Composable () -> Unit = {
                    Scrubber(
                        positionMs = positionMs,
                        durationMs = durationMs,
                        bufferedMs = bufferedMs,
                        sponsorSegments = sponsorSegments,
                        interactive = !isTv,
                        scrubFraction = scrubFraction,
                        onScrubChange = { f -> scrubFraction = f; if (f != null) onPoke() },
                        onSeekTo = onSeekTo
                    )
                }
                if (isTv) {
                    // The design's TV bar: the time at either end of it.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val clock = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                            fontSize = tvTypeUnits(18f)
                        )
                        Text(formatClock(positionMs / 1000), color = onArtwork, maxLines = 1, style = clock)
                        Spacer(Modifier.width(14.dp))
                        Box(Modifier.weight(1f)) { scrubber() }
                        Spacer(Modifier.width(14.dp))
                        Text(formatClock(durationMs / 1000), color = onArtwork.copy(alpha = 0.7f), maxLines = 1, style = clock)
                    }
                } else scrubber()
            }
        }
    }
}

/** The heart, filled once it's theirs. */
@Composable
private fun FavoriteGlyph(isFavorite: Boolean, glyph: Color) {
    Icon(
        if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
        contentDescription = null,
        tint = glyph,
        modifier = Modifier.size(22.dp)
    )
}

/**
 * The TV's transport row, bottom-left as the design draws it: a picture of
 * the state, not buttons — the remote is the transport (OK toggles, ◀ ▶
 * seek, channel up/down step the lineup). Sizes are PROVISIONAL (tvUnits).
 */
@Composable
private fun TvTransportState(
    playing: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onArtwork: Color,
    ink: Color,
    tokens: KidTokens
) {
    val side = tvUnits(70f)
    val main = tvUnits(96f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tvUnits(20f))
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(side).clip(CircleShape).background(tokens.artworkScrim.copy(alpha = 0.55f))
        ) {
            SkipGlyph(forward = false, size = side / 2, color = if (hasPrevious) onArtwork else onArtwork.copy(alpha = 0.4f))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(main)
                .clip(CircleShape)
                .background(onArtwork)
                .border(tvUnits(4f), tokens.action.copy(alpha = 0.55f), CircleShape)
        ) {
            PlayPauseGlyph(playing = playing, size = main * 0.55f, color = ink)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(side).clip(CircleShape).background(tokens.artworkScrim.copy(alpha = 0.55f))
        ) {
            SkipGlyph(forward = true, size = side / 2, color = if (hasNext) onArtwork else onArtwork.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun BoxScope.TvTrackSheet(
    panel: TvTrackPanel,
    cursor: Int,
    playback: YouTubeRepository.Playback?,
    selectedAudio: Int,
    selectedSubtitle: Int,
    captionsOn: Boolean
) {
    val options = if (panel == TvTrackPanel.Audio) {
        playback?.audioTracks.orEmpty().mapIndexed { index, track ->
            Triple(
                track.name + if (track.original) "  ·  Original" else "",
                index == selectedAudio,
                index
            )
        }.ifEmpty { listOf(Triple("Original", true, 0)) }
    } else {
        listOf(Triple("Off", !captionsOn, 0)) +
            playback?.subtitles.orEmpty().mapIndexed { index, track ->
                Triple(track.name, captionsOn && index == selectedSubtitle, index + 1)
            }
    }
    val tokens = kidTokens
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 48.dp, bottom = 118.dp)
            .width(330.dp)
            .background(tokens.artworkScrim.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp)
    ) {
        Text(
            if (panel == TvTrackPanel.Audio) "Audio" else "Subtitles",
            color = tokens.onArtwork,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
        val windowStart = (cursor - 3).coerceIn(0, (options.size - 7).coerceAtLeast(0))
        options.drop(windowStart).take(7).forEach { (label, checked, index) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (index == cursor) tokens.onArtwork.copy(alpha = 0.2f) else Color.Transparent
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (checked) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = tokens.onArtwork,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Text(
                    label,
                    color = tokens.onArtwork,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    }
}
