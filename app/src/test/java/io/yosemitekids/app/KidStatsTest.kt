package io.yosemitekids.app

import io.yosemitekids.app.ui.KidStats
import io.yosemitekids.app.ui.formatWatchTime
import io.yosemitekids.app.ui.kidStats
import io.yosemitekids.app.ui.lastDayKeys
import io.yosemitekids.app.ui.rangeStartMs
import io.yosemitekids.app.ui.weekSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The stats card at the top of a kid's page: "4h 55m · 38 videos",
 * "Watched on 5 of 7 days · 42 min a day", most-watched channels.
 */
class KidStatsTest {

    private val week = listOf(
        "20260829", "20260830", "20260831", "20260901", "20260902", "20260903", "20260904"
    )

    @Test
    fun weekAddsTodayToTheArchivedDaysInsideTheRange() {
        val history = listOf(
            "20260820" to 90,   // outside the range — must not count
            "20260829" to 60,
            "20260831" to 45,
            "20260901" to 0,    // a day the guard rolled over with nothing played
            "20260903" to 70
        )
        val s = kidStats(week, todayMinutes = 30, history = history, rangeStartMs = 0, watched = emptyList())
        assertEquals(205, s.minutes)
        assertEquals(4, s.daysWatched)
        assertEquals(7, s.days)
        assertEquals("Watched on 4 of 7 days · 29 min a day", weekSummary(s))
    }

    @Test
    fun todayIsOneDayAndNeverReadsTheArchive() {
        val s = kidStats(listOf("20260904"), todayMinutes = 12, history = listOf("20260903" to 70),
            rangeStartMs = 0, watched = emptyList())
        assertEquals(12, s.minutes)
        assertEquals(1, s.daysWatched)
        assertEquals(1, s.days)
    }

    @Test
    fun videosCountOnlyInsideTheRangeAndRankChannelsByCount() {
        val start = 1_000L
        val watched = listOf(
            start + 1 to "Kid Crew", start + 2 to "Kid Crew", start + 3 to "Kid Crew",
            start + 4 to "Wild Kratts", start + 5 to "Wild Kratts",
            start + 6 to "Numberblocks",
            start + 7 to null,          // watched, but no cached source names its channel
            start - 1 to "Kid Crew",    // before the range
            0L to "Kid Crew"            // legacy row with no timestamp
        )
        val s = kidStats(week, 0, emptyList(), start, watched, topChannels = 2)
        assertEquals(7, s.videos)
        assertEquals(listOf("Kid Crew" to 3, "Wild Kratts" to 2), s.channels)
        assertEquals(0, s.daysWatched)
    }

    @Test
    fun formatsHoursAndMinutesTheWayTheCardShowsThem() {
        assertEquals("4h 55m", formatWatchTime(295))
        assertEquals("38m", formatWatchTime(38))
        assertEquals("0m", formatWatchTime(0))
        assertEquals("2h 0m", formatWatchTime(120))
    }

    @Test
    fun dayKeysEndOnTodayAndTheRangeOpensAtLocalMidnight() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 4, 15, 30, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(week, lastDayKeys(7, now))
        assertEquals(listOf("20260904"), lastDayKeys(1, now))
        val start = Calendar.getInstance().apply { timeInMillis = rangeStartMs(7, now) }
        assertEquals(29, start.get(Calendar.DAY_OF_MONTH))
        assertEquals(Calendar.AUGUST, start.get(Calendar.MONTH))
        assertEquals(0, start.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, start.get(Calendar.MINUTE))
        assertTrue(rangeStartMs(1, now) <= now)
        assertEquals(KidStats(0, 0, 0, 1, emptyList()),
            kidStats(lastDayKeys(1, now), 0, emptyList(), rangeStartMs(1, now), emptyList()))
    }
}
