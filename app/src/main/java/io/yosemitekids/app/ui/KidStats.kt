package io.yosemitekids.app.ui

import android.content.Context
import io.yosemitekids.app.data.ProfileNamespace
import io.yosemitekids.app.data.SessionGuard
import io.yosemitekids.app.data.SourceCache
import io.yosemitekids.app.data.VideoCache
import io.yosemitekids.app.data.WatchHistoryStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ---------------------------------------------------------------------------
// The numbers at the top of a kid's page (raw-kid.png): "4h 55m · 38 videos",
// "Watched on 5 of 7 days · 42 min a day", and the most-watched channels.
//
// Built from what this device already keeps for the kid — SessionGuard's
// per-day minutes and the synced watch history — not from a paired device's
// stats payload, so the page answers even when every TV is off. The maths is
// a plain function over those two lists so KidStatsTest can pin it without a
// Context.
// ---------------------------------------------------------------------------

/**
 * The ranges the chips offer. Only what the device can honestly answer.
 *
 * [SessionGuard.history] keeps the last 59 archived days plus today, so
 * **All** is sixty and there is no year — offering one would be a chip that
 * silently means the same as this one. Today and Week were the only two for
 * a while, which is a month of a child's watching the page could not show
 * and a parent asked for the first time they looked.
 */
internal enum class StatsRange(val label: String, val days: Int) {
    TODAY("Today", 1),
    WEEK("Week", 7),
    MONTH("Month", 30),
    ALL("All", 60)
}

internal data class KidStats(
    /** Minutes this device played for the kid across the range. */
    val minutes: Int,
    /** Distinct videos last watched inside the range, on any synced device. */
    val videos: Int,
    /** Days in the range with at least a minute played. */
    val daysWatched: Int,
    val days: Int,
    /** Channel → videos watched in the range, biggest first. */
    val channels: List<Pair<String, Int>>,
    /**
     * What was actually watched, newest first — the history itself rather
     * than a ranking of the channels it came from.
     *
     * A parent asking "what has she been watching" wants the titles; the
     * channel ranking answers "where is the time going", which is a different
     * question and was the only one this page could answer.
     */
    val recent: List<WatchedVideo> = emptyList()
)

/** One row of the watched list: what it was, whose channel, and when. */
internal data class WatchedVideo(
    val title: String,
    val channel: String?,
    val watchedAt: Long
)

/**
 * @param dayKeys the range's days as yyyyMMdd, oldest first, today last —
 *   today is the one day [history] never holds (SessionGuard archives a day
 *   only when the next one starts), so its minutes arrive as [todayMinutes].
 * @param watched (lastWatchedAt, channel) per history row; a null channel is
 *   a video no cached source lists any more and counts towards the total only.
 * @param titles the same rows' titles, by the same index — kept apart so the
 *   existing arithmetic and its test are untouched by a column that only the
 *   list needs. A row with no title is one no cached feed still lists, and it
 *   is left out of the list rather than drawn as a blank.
 * @param recentMax how many rows the watched list shows.
 */
internal fun kidStats(
    dayKeys: List<String>,
    todayMinutes: Int,
    history: List<Pair<String, Int>>,
    rangeStartMs: Long,
    watched: List<Pair<Long, String?>>,
    topChannels: Int = 5,
    titles: List<String?> = emptyList(),
    recentMax: Int = 30
): KidStats {
    val past = dayKeys.dropLast(1).toSet()
    val archived = history.filter { (day, _) -> day in past }
    val inRange = watched.filter { (at, _) -> at >= rangeStartMs }
    return KidStats(
        minutes = todayMinutes + archived.sumOf { it.second },
        videos = inRange.size,
        daysWatched = archived.count { it.second > 0 } + (if (todayMinutes > 0) 1 else 0),
        days = dayKeys.size,
        channels = inRange.mapNotNull { it.second }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(topChannels)
            .map { it.key to it.value },
        recent = watched.indices
            .filter { watched[it].first >= rangeStartMs && titles.getOrNull(it) != null }
            .sortedByDescending { watched[it].first }
            .take(recentMax)
            .map { WatchedVideo(titles[it]!!, watched[it].second, watched[it].first) }
    )
}

/** "4h 55m", "38m" — the big number on the card. */
internal fun formatWatchTime(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** "Watched on 5 of 7 days · 42 min a day" — the week's second line. */
internal fun weekSummary(s: KidStats): String =
    "Watched on ${s.daysWatched} of ${s.days} days · ${s.minutes / s.days} min a day"

/** The last [days] days as yyyyMMdd, oldest first, ending on the day of [now]. */
internal fun lastDayKeys(days: Int, now: Long): List<String> {
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    return (days - 1 downTo 0).map { back ->
        fmt.format(Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -back)
        }.time)
    }
}

/** Local midnight that opens the last [days] days ending on the day of [now]. */
internal fun rangeStartMs(days: Int, now: Long): Long =
    Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -(days - 1))
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

internal data class KidStatsLoaded(val stats: KidStats, val today: SessionGuard.Snapshot)

/**
 * Read the kid's stores for one range. Disk and prefs throughout — call it
 * off the main thread. The channel join is the same one Stats.build makes:
 * history is keyed by URL and only the cached feeds know the channel.
 */
internal fun loadKidStats(context: Context, profileId: String, range: StatsRange): KidStatsLoaded {
    val app = context.applicationContext
    val suffix = ProfileNamespace(app).suffixFor(profileId)
    val guard = SessionGuard(app, suffix)
    val snap = guard.snapshot()
    val videoCache = VideoCache(app)
    val channelByUrl = SourceCache(app).load()
        .flatMap { videoCache.load(it.id) }
        .associate { it.url to it.channelName }
    val titleByUrl = SourceCache(app).load()
        .flatMap { videoCache.load(it.id) }
        .associate { it.url to it.title }
    val rows = WatchHistoryStore(app, suffix).all()
    val watched = rows.map { (url, p) -> p.lastWatchedAt to channelByUrl[url] }
    val titles = rows.map { (url, _) -> titleByUrl[url] }
    val now = System.currentTimeMillis()
    return KidStatsLoaded(
        kidStats(
            dayKeys = lastDayKeys(range.days, now),
            todayMinutes = snap.watchedTodayMin,
            history = guard.history(),
            rangeStartMs = rangeStartMs(range.days, now),
            watched = watched,
            titles = titles
        ),
        snap
    )
}
