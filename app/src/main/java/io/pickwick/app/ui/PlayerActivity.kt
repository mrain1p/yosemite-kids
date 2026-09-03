package io.pickwick.app.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.pickwick.app.data.AiScreener
import io.pickwick.app.data.NowPlaying
import io.pickwick.app.data.RemotePlayerControl
import io.pickwick.app.data.SessionGuard
import io.pickwick.app.data.WatchHistoryStore
import io.pickwick.app.data.YouTubeRepository
import io.pickwick.app.data.listenDrainPercent
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
        private const val PIP_ACTION = "io.pickwick.app.PIP_CONTROL"
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
    private lateinit var channelUsage: io.pickwick.app.data.ChannelUsage
    private lateinit var repo: YouTubeRepository
    private lateinit var downloads: io.pickwick.app.data.DownloadStore
    private lateinit var localLibrary: io.pickwick.app.data.LocalLibrary
    private var queue: List<String> = emptyList()
    private var queueChannels: List<String> = emptyList()
    private var queuePercents: IntArray? = null
    private var queueTitles: List<String> = emptyList()
    private var queueThumbs: List<String> = emptyList()
    private var queueDurations: List<Long> = emptyList()
    /** The kid's Favorites, for the heart in the overlay. */
    private lateinit var favorites: io.pickwick.app.data.SavedListStore
    private val isFavorite = mutableStateOf(false)
    /** Timestamp of the last heart tap, for the big ❤️ that pops mid-screen. */
    private val heartBurst = mutableStateOf(0L)
    /** The current channel's avatar and whitelist id (resolved by name, off-main). */
    private val channelAvatar = mutableStateOf<String?>(null)
    @Volatile
    private var channelSourceId: String? = null
    /** "Stop after this one" — the moon button. Wins over autoplay and lineups. */
    private val stopAfterThis = mutableStateOf(false)
    /** The parent's autoplay switch, read with the config. */
    @Volatile
    private var autoplayOn = true
    /** Non-null only for EXTRA_FROM_QUEUE launches. */
    private var queueStore: io.pickwick.app.data.QueueStore? = null
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
    /** Wall-clock ms of watching left before a rule stops playback; null = no rule. */
    private val remainingLeftMs = mutableStateOf<Long?>(null)
    /** A double-tap seek just happened: signed seconds + timestamp, for the ripple label. */
    private val seekFeedback = mutableStateOf<Pair<Int, Long>?>(null)
    /** Last time a held ◀/▶ key repeat was allowed to seek (see onKeyDown). */
    private var lastHeldSeekAt = 0L
    /** Transient top-of-screen pill: time-left warnings, subtitles toggled, … */
    private val notice = mutableStateOf<Notice?>(null)
    /** Which time-left warnings (5, 1 min) already fired; cleared when time is granted back. */
    private val warnedMinutes = mutableSetOf<Int>()
    /** Kid's sticky captions choice (survives across videos and app runs). */
    private var captionsOn = false
    private var currentSubtitles: List<YouTubeRepository.Subtitle> = emptyList()
    private val trackPanel = mutableStateOf(TvTrackPanel.Hidden)
    private val trackCursor = mutableIntStateOf(0)
    private val selectedAudioTrack = mutableIntStateOf(0)
    private val selectedSubtitleTrack = mutableIntStateOf(-1)
    /** Community-marked promo stretches for the current video (see SponsorBlock). */
    private val sponsorSegments =
        mutableStateOf<List<io.pickwick.app.data.SponsorBlock.Segment>>(emptyList())
    /** Parent's SponsorBlock switch, read off-main on first use; null = not read yet. */
    private var sponsorSkipOn: Boolean? = null

    /** The quality ceiling in force, for the player's own picker; null = Auto. */
    private val qualityCeiling = mutableStateOf<Int?>(null)
    private val qualityPickerOpen = mutableStateOf(false)

    /**
     * The kid picked a quality in the player: re-resolve this video at the new
     * ceiling and carry on from where they were. Applies to whatever plays
     * next too (the ceiling is global for the session), but it is not written
     * to the config — the parent's setting is the default, not the memory.
     */
    private fun setQuality(height: Int?) {
        if (io.pickwick.app.data.QualityTargets.userMaxHeight == height) return
        io.pickwick.app.data.QualityTargets.userMaxHeight = height
        qualityCeiling.value = height
        haptic()
        notice.value = Notice("Quality: ${io.pickwick.app.data.qualityLabel(height)}")
        val exo = player ?: return
        val at = exo.currentPosition
        val page = currentPageUrl ?: return
        val playing = exo.playWhenReady
        lifecycleScope.launch {
            val pb = runCatching {
                repo.resolvePlayback(page, io.pickwick.app.data.QualityTargets.effectiveMaxHeight())
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
    private val moreFromChannel = mutableStateOf<List<io.pickwick.app.data.Video>>(emptyList())
    /** Where the video is drawn on screen, for the shrink-to-PiP animation. */
    private var videoBounds: android.graphics.Rect? = null
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
    private var familyConfig: io.pickwick.app.data.Whitelist? = null

    /** Identity token for [RemotePlayerControl.owner]; see onDestroy. */
    private val remoteToken = Any()
    /** Channel name → parent's channel note, resolved once alongside the config. */
    private var channelNotes: Map<String, String>? = null
    private val screeningStore by lazy { io.pickwick.app.data.ScreeningStore(this) }
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
     */
    private var listenActive = false
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
        isTv = (getSystemService(UI_MODE_SERVICE) as android.app.UiModeManager)
            .currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
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
            val config = io.pickwick.app.data.ConfigStore(this).load()
            // Membership-checked: a remembered pick can name a since-deleted kid,
            // and suffixFor would silently mint an orphan namespace for it.
            val active = io.pickwick.app.data.ActiveProfileStore(this).activeId()
                ?.takeIf { config.profile(it) != null }
            io.pickwick.app.data.ProfileNamespace(this).suffixFor(active)
        }
        gateProfileId = intent.getStringExtra(EXTRA_PROFILE_ID)
        channelUsage = io.pickwick.app.data.ChannelUsage(this, profileSuffix)
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
        favorites = io.pickwick.app.data.SavedListStore(this, profileSuffix)
        if (intent.getBooleanExtra(EXTRA_FROM_QUEUE, false)) {
            queueStore = io.pickwick.app.data.QueueStore(this, profileSuffix)
        }

        history = WatchHistoryStore(this, profileSuffix)
        sessionGuard = SessionGuard(this, profileSuffix)
        repo = YouTubeRepository()
        downloads = io.pickwick.app.data.DownloadStore(this)
        localLibrary = io.pickwick.app.data.LocalLibrary(this)
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
                io.pickwick.app.data.ConfigStore(this).load().listenPercent
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
            val cfg = io.pickwick.app.data.ConfigStore(this@PlayerActivity).load()
            familyConfig = cfg
            autoplayOn = cfg.autoplayNext
            if (!isTv && listenPercent == null) listenPercent = cfg.listenPercent
            // The parent's ceiling for this form factor. Read before the first
            // resolve in practice (a file read beats a network fetch), and the
            // kid's own pick in the player overrides it for the session.
            io.pickwick.app.data.QualityTargets.userMaxHeight =
                if (isTv) cfg.qualityTv else cfg.qualityPhone
            qualityCeiling.value = io.pickwick.app.data.QualityTargets.userMaxHeight
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
            io.pickwick.app.data.KidNotices.messages.collect {
                notice.value = Notice(it.text)
            }
        }

        // Persist progress and enforce screen-time rules every 5s while playing.
        lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                saveProgress()
                val exo = player ?: continue
                // Publish now-playing for the parent's stats screen.
                if (exo.duration > 0) {
                    io.pickwick.app.data.NowPlaying.update(
                        currentTitle, currentChannel,
                        exo.currentPosition, exo.duration, exo.isPlaying
                    )
                }
                // A window can open or close mid-story: bedtime arriving drops
                // the picture instead of stopping the video, and morning gives
                // it back. Checked whether or not playback is running, so a
                // paused story is in the right mode when it resumes.
                syncListenOnlyWindow()
                // The drain rate in force right now — what the overlay's
                // "N min left" chip must be computed at, playing or paused.
                val drain =
                    if (listenActive) listenDrainPercent(timePercent, listenPercent)
                    else timePercent
                if (timeUpMessage.value == null) {
                    remainingLeftMs.value = sessionGuard.remainingMs(drain, listenActive)
                }
                if (exo.isPlaying && timeUpMessage.value == null) {
                    // Stats record real watch time; the budget drains at the
                    // source's multiplier (exact integer ms — 25% of 5s = 1250ms),
                    // further scaled by the family listening rate while the
                    // screen is off.
                    channelUsage.addSeconds(currentChannel, 5)
                    sessionGuard.tick(5_000L * drain / 100, listenActive, multiplierPercent = drain)?.let { reason ->
                        exo.pause()
                        timeUpMessage.value = reason
                        delay(6_000)
                        finish()
                    }
                    // Soften the cutoff: warn at 5 and 1 wall-clock minutes before
                    // the budget runs out at the current drain rate (on FREE
                    // sources only an approaching bedtime counts down). A parent
                    // grant that lifts the countdown re-arms the warnings.
                    if (timeUpMessage.value == null)
                        sessionGuard.remainingMs(drain, listenActive)?.let { left ->
                        val threshold = when {
                            left <= 60_000L -> 1
                            left <= 5 * 60_000L -> 5
                            else -> null
                        }
                        if (threshold == null) warnedMinutes.clear()
                        else if (warnedMinutes.add(threshold)) {
                            notice.value = Notice(
                                if (threshold == 1) "1 minute left! ⏳"
                                else "$threshold minutes left! ⏳"
                            )
                        }
                    }
                }
            }
        }

        setContent {
            MaterialTheme(colorScheme = PickwickDarkColors, typography = PickwickTypography) {
                val pip by inPip
                val portrait by portraitLayout
                // The PiP parameters (auto-enter, the play/pause button) follow
                // the same state pipEligible reads, so recomposition is the
                // one place that keeps them current.
                val eligible = pipEligible()
                LaunchedEffect(eligible) { refreshPipParams() }
                // One stage, *moved* between the two layouts rather than
                // rebuilt: a rotation would otherwise recreate the AndroidView
                // and its SurfaceView, and cut to black mid-video.
                val stage = remember { movableContentOf { compact: Boolean -> PlayerStage(compact) } }
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
                                io.pickwick.app.data.PLAYBACK_QUALITIES.forEach { h ->
                                    androidx.compose.material3.TextButton(
                                        onClick = { qualityPickerOpen.value = false; setQuality(h) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                io.pickwick.app.data.qualityLabel(h),
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
                    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
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
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
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
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        playback?.title.orEmpty(),
                        color = Color.White.copy(alpha = 0.7f),
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
                        color = Color.White.copy(alpha = 0.85f)
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
            val showControls = timeUp == null && blocked == null && error == null &&
                listenOnly == null && played && card == null && !pip
            if (!pip) HeartBurst(heartBurst)
            if (!isTv && showControls) {
                // Touch layer under the controls: single tap shows/hides
                // them, a double tap on either edge hops ±10 s (the
                // YouTube gesture every kid already knows), a double tap
                // in the middle toggles play. Buttons above it consume
                // their own taps, so this only ever sees the bare video.
                Box(
                    Modifier
                        .fillMaxSize()
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
                    remainingMs = remainingLeftMs,
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
     * Phone held upright: the video slot on top, then the title, the channel
     * row with the heart and the moon, and what could play next — the rest
     * of the lineup, then more from the channel. Padded off the system bars,
     * which stay visible here (the stage hides them).
     */
    @Composable
    private fun PortraitPlayerScaffold(stage: @Composable () -> Unit) {
        val playback by playbackState
        val index by indexState
        val more by moreFromChannel
        val favorite by isFavorite
        val stopAfter by stopAfterThis
        val avatar by channelAvatar
        // Read under the index: appendToQueue grows the lists just before the
        // index moves, so a step is what brings the new entries on screen.
        val upNext = (index + 1..queue.lastIndex).toList()
        val titles = queueTitles
        val thumbs = queueThumbs
        val channel = currentChannel
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
                item {
                    Column(Modifier.padding(horizontal = 16.dp).padding(top = 10.dp, bottom = 8.dp)) {
                        Text(
                            playback?.title ?: titles.getOrNull(index).orEmpty(),
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Spacer(Modifier.height(10.dp))
                        // The channel, as a row that opens it: art, name, a chevron.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .then(if (avatar != null) Modifier.clickable { openChannel() } else Modifier)
                                .padding(vertical = 4.dp)
                        ) {
                            if (avatar != null) {
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Color(0x33FFFFFF))) {
                                    coil.compose.AsyncImage(
                                        model = avatar,
                                        contentDescription = channel,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                            }
                            if (channel.isNotBlank()) Text(
                                channel,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (avatar != null) Icon(
                                PickwickIcons.ChevronRight, contentDescription = "Open channel",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        // The kid's two actions on this video, spelled out —
                        // a heart and a moon floating by the name read as
                        // decoration, not as buttons.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PwChip(
                                if (favorite) "In Favorites" else "Favorite",
                                selected = favorite,
                                icon = if (favorite) androidx.compose.material.icons.Icons.Filled.Favorite
                                else androidx.compose.material.icons.Icons.Filled.FavoriteBorder,
                                onClick = ::toggleFavorite
                            )
                            PwChip(
                                if (stopAfter) "Stopping after this" else "Stop after this",
                                selected = stopAfter,
                                icon = PickwickIcons.Moon,
                                onClick = ::toggleStopAfter
                            )
                            val ceiling by qualityCeiling
                            PwChip(
                                io.pickwick.app.data.qualityLabel(ceiling),
                                selected = false,
                                icon = PickwickIcons.Quality,
                                onClick = { qualityPickerOpen.value = true }
                            )
                        }
                    }
                }
                if (upNext.isNotEmpty()) {
                    item { SectionLabel("Up next") }
                    items(upNext) { j ->
                        val secs = queueDurations.getOrNull(j) ?: 0L
                        SmallVideoRow(
                            title = titles.getOrNull(j)?.ifBlank { null } ?: "One more",
                            thumb = thumbs.getOrNull(j)?.ifBlank { null },
                            subtitle = listOfNotNull(
                                queueChannels.getOrNull(j)?.ifBlank { null },
                                secs.takeIf { it > 0 }?.let { formatClock(it) }
                            ).joinToString(" · ").ifBlank { null },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                        ) { haptic(); dismissEndCard(); playIndex(j) }
                    }
                }
                if (more.isNotEmpty()) {
                    item { SectionLabel("More from $channel") }
                    items(more) { v ->
                        SmallVideoRow(
                            title = v.title,
                            thumb = v.thumbnailUrl,
                            subtitle = listOfNotNull(
                                relativeAge(v.publishedAt),
                                v.durationSeconds.takeIf { it > 0 }?.let { formatClock(it) }
                            ).joinToString(" · ").ifBlank { null },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                        ) { playExtra(v) }
                    }
                }
            }
        }
    }

    /**
     * A titled group in the list under the video, with a rule above it: the
     * video's own details, what is lined up and what else the channel has
     * ran together as one column of text otherwise.
     */
    @Composable
    private fun SectionLabel(text: String) {
        androidx.compose.material3.HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Text(
            text,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)
        )
    }

    /** Back to the shelf, which opens the channel on resume. */
    private fun openChannel() {
        io.pickwick.app.data.PlayerRequests.openChannel = currentChannel
        finish()
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
            var more: List<io.pickwick.app.data.Video> = emptyList()
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
                hasNext = hasNext,
                secondsLeft = seconds,
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
    private fun channelCandidates(): List<io.pickwick.app.data.Video> {
        val sourceId = channelSourceId ?: return emptyList()
        val cached = io.pickwick.app.data.VideoCache(this).load(sourceId)
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
    private fun appendToQueue(video: io.pickwick.app.data.Video) {
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
    private fun playExtra(video: io.pickwick.app.data.Video) {
        haptic()
        appendToQueue(video)
        stepQueue(queue.lastIndex - indexState.intValue)
    }

    /** The heart: save or unsave the playing video for this kid, with a pop. */
    private fun toggleFavorite() {
        val url = currentPageUrl ?: return
        val i = indexState.intValue
        val duration = queueDurations.getOrNull(i)?.takeIf { it > 0 }
            ?: ((player?.duration ?: 0L) / 1000).coerceAtLeast(0)
        val video = io.pickwick.app.data.Video(
            url = url,
            title = currentTitle,
            channelName = currentChannel,
            thumbnailUrl = queueThumbs.getOrNull(i)?.ifEmpty { null },
            durationSeconds = duration
        )
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
        android.util.Log.w("Pickwick", "playback failed for $currentPageUrl: $message")
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
                val source = io.pickwick.app.data.SourceCache(this@PlayerActivity).load()
                    .firstOrNull { it.name == channelName }
                if (isActive) {
                    isFavorite.value = fav
                    channelSourceId = source?.id
                    if (source?.avatarUrl != null) channelAvatar.value = source.avatarUrl
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
            io.pickwick.app.data.SponsorBlock.videoIdOf(queue[i])?.let { vid ->
                sponsorJob?.cancel()
                sponsorJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val on = sponsorSkipOn
                        ?: io.pickwick.app.data.ConfigStore(this@PlayerActivity)
                            .load().sponsorSkip.also { sponsorSkipOn = it }
                    if (!on) return@launch
                    val segments = io.pickwick.app.data.SponsorBlock.segmentsFor(vid)
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
                    io.pickwick.app.data.QualityTargets.effectiveMaxHeight()
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
        val id = io.pickwick.app.data.SponsorBlock.videoIdOf(pageUrl)
            ?: return@withContext false
        val cfg = familyConfig
            ?: io.pickwick.app.data.ConfigStore(this@PlayerActivity).load()
                .also { familyConfig = it }
        val ai = cfg.ai
        if (!ai.enabled || ai.model.isBlank()) return@withContext false
        // A parent's explicit allow beats every AI verdict, deep ones included.
        if (id in cfg.allowedIdsFor(gateProfileId)) return@withContext false

        val note = (channelNotes ?: io.pickwick.app.data.DeepCheck.notesByChannelName(
            cfg.sources, io.pickwick.app.data.SourceCache(this@PlayerActivity).load()
        ).also { channelNotes = it })[currentChannel]

        io.pickwick.app.data.DeepCheck.cached(
            screeningStore, id, ai.rulesVersion, AiScreener.noteHash(note)
        )?.let {
            android.util.Log.i(
                "Pickwick",
                "Deep check $id: cached ${it.verdictFor(gateProfileId)} (\"${it.reason}\")"
            )
            return@withContext it.verdictFor(gateProfileId) != AiScreener.Verdict.ALLOW
        }

        deepChecking.value = true
        val entry = try {
            // Bounded overall: past ~20s the kid is staring at a spinner and an
            // answer that slow is treated like an outage (play this once).
            io.pickwick.app.data.DeepCheck.runAndStore(
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
        val factory = io.pickwick.app.data.ChunkedStreamDataSource.Factory(
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
        // Fresh video: the chip shouldn't wait five seconds for the first tick.
        if (resumeMs == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val drain =
                    if (listenActive) listenDrainPercent(timePercent, listenPercent)
                    else timePercent
                val left = sessionGuard.remainingMs(drain, listenActive)
                if (timeUpMessage.value == null) remainingLeftMs.value = left
            }
        }
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
            MaterialTheme(colorScheme = PickwickDarkColors, typography = PickwickTypography) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black),
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
                    trackCursor.intValue = (trackCursor.intValue + 1).coerceAtMost(TV_TOOLBAR_LAST); true
                }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER -> {
                    when (trackCursor.intValue) {
                        TV_TOOLBAR_AUDIO -> {
                            trackCursor.intValue = selectedAudioTrack.intValue
                            trackPanel.value = TvTrackPanel.Audio
                        }
                        TV_TOOLBAR_SUBTITLES -> {
                            trackCursor.intValue = if (captionsOn) {
                                selectedSubtitleTrack.intValue + 1
                            } else 0
                            trackPanel.value = TvTrackPanel.Subtitles
                        }
                        // The phone's heart and avatar, reachable from the remote:
                        // the toolbar is the one place a TV kid can "press" something.
                        TV_TOOLBAR_FAVORITE -> toggleFavorite()
                        TV_TOOLBAR_CHANNEL -> {
                            // Only when the uploader is a whitelisted channel: a
                            // playlist's uploader often isn't, and finishing the
                            // player to open nothing would dump the kid mid-video.
                            if (channelSourceId != null) {
                                io.pickwick.app.data.PlayerRequests.openChannel = currentChannel
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
                keyCode, pb.audioTracks.size.coerceAtLeast(1), toolbarCursor = 0
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
                keyCode, pb.subtitles.size + 1, toolbarCursor = 1
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

    /** Shrinking only makes sense with a video on and playing, and no card over it. */
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
        io.pickwick.app.data.AppVisibility.startedActivities++
    }

    override fun onStop() {
        io.pickwick.app.data.AppVisibility.startedActivities--
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
        io.pickwick.app.data.ActiveProfileStore(this).touch()
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
        io.pickwick.app.data.NowPlaying.clear()
        ListenService.stop(this)
        if (ListenService.player === player) ListenService.player = null
        player?.release()
        player = null
    }
}

/** A transient kid-facing message; `at` gives repeats a fresh identity. */
private data class Notice(val text: String, val at: Long = System.currentTimeMillis())

private enum class TvTrackPanel { Hidden, Toolbar, Audio, Subtitles }

// Cursor positions on the TV toolbar (▼ from the player), left to right.
private const val TV_TOOLBAR_AUDIO = 0
private const val TV_TOOLBAR_SUBTITLES = 1
private const val TV_TOOLBAR_FAVORITE = 2
private const val TV_TOOLBAR_CHANNEL = 3
private const val TV_TOOLBAR_LAST = TV_TOOLBAR_CHANNEL

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
    val hasNext: Boolean,
    val secondsLeft: Int,
    /** Up to three more from the same channel, tappable (phones). */
    val more: List<io.pickwick.app.data.Video> = emptyList()
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
        Text(
            shown?.text.orEmpty(),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .background(Color(0xCC000000), shape = RoundedCornerShape(24.dp))
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
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(Color(0x59FFFFFF))
        ) {
            Text(
                if (delta < 0) "◀◀\n${-delta} s" else "▶▶\n$delta s",
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

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
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .scale(scale)
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(if (primary) PickwickDarkColors.primary else Color(0x33FFFFFF))
            .border(
                width = if (highlighted) 3.dp else 0.dp,
                color = if (highlighted) Color.White else Color.Transparent,
                shape = RoundedCornerShape(28.dp)
            )
            // Touch only: a focusable here would steal the remote's keys from
            // the activity, which is where TV cursoring lives.
            .then(if (isTv) Modifier else Modifier.clickable { onClick() })
            .padding(horizontal = 28.dp)
    ) {
        Text(
            label,
            color = if (primary) PickwickDarkColors.onPrimary else Color.White,
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
            color = Color.White,
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
            color = Color.White,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Try again, or pick a different one.",
            color = Color.White.copy(alpha = 0.7f),
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
 * comes next and counts down; on the last video it celebrates and offers a
 * replay. Auto-advance is the default, but never silent — the countdown is
 * the whole point.
 */
@Composable
private fun BoxScope.EndCardOverlay(
    card: EndCard,
    isTv: Boolean,
    cursor: Int,
    channel: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onPick: (io.pickwick.app.data.Video) -> Unit,
    /** The portrait video slot: no poster, no "More from" — the list below the video has both. */
    compact: Boolean = false
) {
    val showMore = !isTv && !compact && card.more.isNotEmpty()
    Box(
        Modifier.fillMaxSize().background(Color(0xB3000000)),
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
                Text(
                    "Up next",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
                if (card.nextThumb != null && !compact) {
                    coil.compose.AsyncImage(
                        model = card.nextThumb,
                        contentDescription = card.nextTitle,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier
                            .width(if (isTv) 320.dp else 208.dp)
                            .height(if (isTv) 180.dp else 117.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x33FFFFFF))
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    card.nextTitle ?: "The next video",
                    color = Color.White,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.width(if (isTv) 520.dp else if (compact) 300.dp else 420.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Playing in ${card.secondsLeft}…",
                    color = PickwickDarkColors.primary,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    KidButton("▶  Play now", primary = true, highlighted = isTv && cursor == 0, isTv = isTv, onClick = onPrimary)
                    KidButton("Not now", primary = false, highlighted = isTv && cursor == 1, isTv = isTv, onClick = onSecondary)
                }
            } else {
                Text(
                    "🎉",
                    fontSize = androidx.compose.ui.unit.TextUnit(if (compact) 36f else 64f, androidx.compose.ui.unit.TextUnitType.Sp)
                )
                Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
                Text(
                    "That's the end!",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Back to the shelf in ${card.secondsLeft}…",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium
                )
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
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelLarge
                )
                card.more.forEach { v ->
                    SmallVideoRow(v.title, v.thumbnailUrl, Modifier.width(360.dp)) { onPick(v) }
                }
            }
        }
        }
    }
}

/**
 * A poster with its title beside it — the list shape of YouTube's portrait
 * "Up next", shared by the end card's "More from" column and the portrait
 * layout's lists. Kid-sized: a 72 dp poster is a target, not a thumbnail.
 */
@Composable
private fun SmallVideoRow(
    title: String,
    thumb: String?,
    modifier: Modifier = Modifier,
    /** "Channel · 4:53", the quiet line under the title. */
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(4.dp)
    ) {
        coil.compose.AsyncImage(
            model = thumb,
            contentDescription = title,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .width(136.dp)
                .height(76.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0x33FFFFFF))
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                color = Color.White,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/** The heart: save for later, with the pop. Filled and red once it's theirs. */
@Composable
private fun HeartButton(isFavorite: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable { onClick() }
    ) {
        Icon(
            if (isFavorite) androidx.compose.material.icons.Icons.Filled.Favorite
            else androidx.compose.material.icons.Icons.Filled.FavoriteBorder,
            contentDescription = if (isFavorite) "In your Favorites" else "Add to Favorites",
            tint = if (isFavorite) Color(0xFFFF5A79) else Color.White,
            modifier = Modifier.size(28.dp)
        )
    }
}

/** The moon: stop after this one; lit while armed. */
@Composable
private fun MoonButton(stopAfterThis: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (stopAfterThis) Color(0x59FFFFFF) else Color.Transparent)
            .clickable { onClick() }
    ) {
        Icon(
            PickwickIcons.Moon,
            contentDescription = "Stop after this one",
            tint = if (stopAfterThis) Color(0xFFFFD54F) else Color.White,
            modifier = Modifier.size(26.dp)
        )
    }
}

/** Picture-in-picture: a frame with a small filled one in its corner. Drawn, like the transport glyphs. */
@Composable
private fun PipGlyph(size: androidx.compose.ui.unit.Dp, color: Color) {
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
private fun FullscreenGlyph(expand: Boolean, size: androidx.compose.ui.unit.Dp, color: Color) {
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
private fun PlayPauseGlyph(playing: Boolean, size: androidx.compose.ui.unit.Dp, color: Color) {
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
private fun SkipGlyph(forward: Boolean, size: androidx.compose.ui.unit.Dp, color: Color) {
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

/** Time-left chip in the top bar; turns amber inside the last five minutes. */
@Composable
private fun RemainingChip(ms: Long) {
    val urgent = ms <= 5 * 60_000L
    Text(
        "⏳ " + remainingLabel(ms),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        maxLines = 1,
        modifier = Modifier
            .background(if (urgent) Color(0xE6B26A00) else Color(0x80000000), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
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
    sponsorSegments: List<io.pickwick.app.data.SponsorBlock.Segment>,
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
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(if (interactive) 36.dp else 18.dp)
            .then(gestures)
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp)
                .background(Color(0x40FFFFFF))
        )
        Box(
            Modifier.align(Alignment.CenterStart)
                .fillMaxWidth((bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f))
                .height(4.dp).background(Color(0x8CFFFFFF))
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

/**
 * The kid-sized player chrome, one composable for both form factors. It
 * shows for a few seconds after any key or tap, stays while paused (a frozen
 * frame with nothing on it reads as broken), while a scrub is in progress, and
 * while a TV track sheet is open. Phones get the transport buttons; on TV the
 * remote is the transport, so the middle shows only the state glyph.
 */
@Composable
private fun BoxScope.PlayerControlsOverlay(
    isTv: Boolean,
    /**
     * The portrait video slot: title, channel, heart and moon live below the
     * video there, so the chrome is only what steers playback, at sizes that
     * fit a 16:9 strip.
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
    remainingMs: State<Long?>,
    sponsorSegments: List<io.pickwick.app.data.SponsorBlock.Segment>,
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
    val left by remainingMs
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun wanted() = now < until || !playing || scrubFraction != null || panel != TvTrackPanel.Hidden
    val visible = wanted()
    LaunchedEffect(until, playing, scrubFraction != null, panel) {
        while (isActive) {
            now = System.currentTimeMillis()
            playerProvider()?.let {
                positionMs = it.currentPosition.coerceAtLeast(0)
                durationMs = it.duration.coerceAtLeast(0)
                bufferedMs = it.bufferedPosition.coerceAtLeast(0)
            }
            if (!wanted()) break
            delay(250)
        }
    }

    val edge = if (isTv) 32.dp else if (compact) 8.dp else 16.dp
    androidx.compose.animation.AnimatedVisibility(
        visible = visible && durationMs > 0,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(if (compact) 72.dp else 140.dp).background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0xB3000000), Color.Transparent)
                    )
                )
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (compact) 96.dp else 180.dp).background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xCC000000))
                    )
                )
            )
            // Top bar: back (phones), title + channel, time left, captions (phones).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(horizontal = edge, vertical = if (isTv) 20.dp else if (compact) 2.dp else 10.dp)
            ) {
                if (!isTv) {
                    androidx.compose.material3.IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(if (compact) 44.dp else 52.dp)
                    ) {
                        Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(if (compact) 26.dp else 30.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                if (compact) Spacer(Modifier.weight(1f))
                // The channel's face: tap to see the rest of its videos. On
                // TV it's a label only (the remote has no cursor for it).
                if (avatarUrl != null && !compact) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0x33FFFFFF))
                            .then(if (isTv) Modifier else Modifier.clickable { onOpenChannel() })
                    ) {
                        coil.compose.AsyncImage(
                            model = avatarUrl,
                            contentDescription = channel,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                }
                if (!compact) Column(
                    Modifier
                        .weight(1f)
                        .then(if (isTv || avatarUrl == null) Modifier else Modifier.clickable { onOpenChannel() })
                ) {
                    Text(
                        title,
                        color = Color.White,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    if (channel.isNotBlank()) Text(
                        if (isTv) channel else "$channel  ›",
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                left?.let { ms ->
                    Spacer(Modifier.width(12.dp))
                    RemainingChip(ms)
                }
                if (!isTv && !compact) {
                    // Heart: save for later, with the pop. Moon: stop after this one.
                    Spacer(Modifier.width(4.dp))
                    HeartButton(isFavorite, onToggleFavorite)
                    MoonButton(stopAfterThis, onToggleStopAfter)
                }
                if (!isTv && playback?.subtitles?.isNotEmpty() == true) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { onToggleCaptions() }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(width = 30.dp, height = 22.dp)
                                .background(
                                    if (captionsOn) Color.White else Color.Transparent,
                                    RoundedCornerShape(3.dp)
                                )
                                .border(2.dp, Color.White, RoundedCornerShape(3.dp))
                        ) {
                            Text(
                                "CC",
                                color = if (captionsOn) Color(0xFF0F0F0F) else Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
                if (!isTv && onMinimise != null) {
                    // Shrink to the floating window and keep browsing.
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { onMinimise() }
                    ) {
                        PipGlyph(size = 26.dp, color = Color.White)
                    }
                }
            }
            // Middle: phones get the transport; TV gets the state glyph.
            if (!isTv) {
                val side = if (compact) 44.dp else 60.dp
                val main = if (compact) 64.dp else 88.dp
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
                            .background(Color(0x33FFFFFF))
                            .then(
                                if (hasPrevious) Modifier.clickable { onPrevious() }
                                else Modifier
                            )
                    ) {
                        SkipGlyph(forward = false, size = side / 2,
                            color = if (hasPrevious) Color.White else Color(0x66FFFFFF))
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(main)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable { onTogglePlay() }
                    ) {
                        PlayPauseGlyph(playing = playing, size = main * 0.55f, color = Color(0xFF0F0F0F))
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(side)
                            .clip(CircleShape)
                            .background(Color(0x33FFFFFF))
                            .then(
                                if (hasNext) Modifier.clickable { onNext() }
                                else Modifier
                            )
                    ) {
                        SkipGlyph(forward = true, size = side / 2,
                            color = if (hasNext) Color.White else Color(0x66FFFFFF))
                    }
                }
            } else if (!playing) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Color(0xCCFFFFFF))
                ) {
                    PlayPauseGlyph(playing = false, size = 52.dp, color = Color(0xFF0F0F0F))
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
                    .padding(horizontal = edge, vertical = if (isTv) 18.dp else if (compact) 0.dp else 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val shownPos = scrubFraction?.let { (it * durationMs).toLong() } ?: positionMs
                    Text(
                        formatClock(shownPos / 1000) + " / " + formatClock(durationMs / 1000),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (hasNext) {
                            Text(
                                "Next: " + (nextTitle ?: "one more"),
                                color = Color.White.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.widthIn(max = if (compact) 150.dp else 320.dp)
                            )
                            Spacer(Modifier.width(if (compact) 8.dp else 16.dp))
                        }
                        if (!isTv && onToggleFullscreen != null) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .clickable { onToggleFullscreen() }
                            ) {
                                FullscreenGlyph(expand = compact, size = 24.dp, color = Color.White)
                            }
                        }
                        if (isTv && panel != TvTrackPanel.Hidden) {
                            val onToolbar = panel == TvTrackPanel.Toolbar
                            // Name the focused action: the glyphs alone are a guess
                            // from the couch, and a heart next to a face needs no
                            // explaining once it's spelled out.
                            if (onToolbar) {
                                Text(
                                    when (cursor) {
                                        TV_TOOLBAR_AUDIO -> "Audio"
                                        TV_TOOLBAR_SUBTITLES -> "Subtitles"
                                        TV_TOOLBAR_FAVORITE ->
                                            if (isFavorite) "In your Favorites" else "Add to Favorites"
                                        else -> "More from $channel"
                                    },
                                    color = Color.White.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(Modifier.width(16.dp))
                            }
                            TvTrackIcon(
                                TvTrackGlyph.Audio, "Audio",
                                onToolbar && cursor == TV_TOOLBAR_AUDIO
                            )
                            Spacer(Modifier.width(16.dp))
                            TvTrackIcon(
                                TvTrackGlyph.Captions, "Subtitles",
                                onToolbar && cursor == TV_TOOLBAR_SUBTITLES
                            )
                            Spacer(Modifier.width(16.dp))
                            TvEmojiIcon(
                                if (isFavorite) "❤️" else "🤍",
                                onToolbar && cursor == TV_TOOLBAR_FAVORITE
                            )
                            Spacer(Modifier.width(16.dp))
                            TvAvatarIcon(avatarUrl, onToolbar && cursor == TV_TOOLBAR_CHANNEL)
                            Spacer(Modifier.width(24.dp))
                        }
                    }
                }
                Spacer(Modifier.height(if (isTv) 10.dp else if (compact) 0.dp else 2.dp))
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
        }
    }
}

private enum class TvTrackGlyph { Audio, Captions }

/** A toolbar slot drawn with an emoji (the heart), in the same ring as the track icons. */
@Composable
private fun TvEmojiIcon(text: String, selected: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.Transparent)
    ) {
        Text(text, fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp))
    }
}

/** The channel's face as a toolbar slot: selecting it leaves for the channel page. */
@Composable
private fun TvAvatarIcon(avatarUrl: String?, selected: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.Transparent)
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0x33FFFFFF))
        ) {
            if (avatarUrl != null) {
                coil.compose.AsyncImage(
                    model = avatarUrl,
                    contentDescription = "Channel",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    "📺",
                    modifier = Modifier.align(Alignment.Center),
                    fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)
                )
            }
        }
    }
}

@Composable
private fun TvTrackIcon(glyph: TvTrackGlyph, label: String, selected: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) Color.White else Color.Transparent)
    ) {
        val ink = if (selected) Color(0xFF0F0F0F) else Color.White
        when (glyph) {
            TvTrackGlyph.Audio -> Box(contentAlignment = Alignment.Center) {
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
            TvTrackGlyph.Captions -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 30.dp, height = 22.dp)
                    .border(2.dp, ink, RoundedCornerShape(3.dp))
            ) {
                Text(
                    "CC",
                    color = ink,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
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
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 48.dp, bottom = 118.dp)
            .width(330.dp)
            .background(Color(0xE6000000), RoundedCornerShape(8.dp))
            .padding(vertical = 8.dp)
    ) {
        Text(
            if (panel == TvTrackPanel.Audio) "Audio" else "Subtitles",
            color = Color.White,
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
                        if (index == cursor) Color(0x33FFFFFF) else Color.Transparent
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (checked) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Text(
                    label,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }
    }
}
