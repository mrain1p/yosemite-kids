package io.yosemitekids.app

import androidx.compose.ui.unit.dp
import io.yosemitekids.app.data.Source
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.ui.FormFactor
import io.yosemitekids.app.ui.RailStop
import io.yosemitekids.app.ui.Screen
import io.yosemitekids.app.ui.TV_CHANNEL_COLUMNS
import io.yosemitekids.app.ui.TV_PAGE_GUTTER
import io.yosemitekids.app.ui.TV_RAIL_COLLAPSED
import io.yosemitekids.app.ui.TV_RAIL_EXPANDED
import io.yosemitekids.app.ui.channelMetrics
import io.yosemitekids.app.ui.homeMetrics
import io.yosemitekids.app.ui.railStopFor
import io.yosemitekids.app.ui.tvUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nav rail as values.
 *
 * The rail took a bite out of every ten-foot page and there is no compiler
 * error for a tile that no longer fits — the grid quietly drops a column, the
 * hero row quietly squeezes, and it looks fine in a screenshot taken on the
 * one panel size anybody tried. Every TV tile in this app was sized against a
 * ~880 dp page; this is what holds them against the narrower one.
 */
class TvNavRailTest {

    /** A 1080p Android television is xhdpi, so 1920 px is 960 dp across. */
    private val panel = 960.dp

    /**
     * What a page actually gets to draw in beside a rail of [rail].
     *
     * The insets are `YosemiteScreen`'s: the full [TV_PAGE_GUTTER] on the
     * right, and half of it on the left, where the rail stands in for the
     * panel edge and the page needs room for a focus ring rather than a
     * ten-foot margin.
     */
    private fun page(rail: androidx.compose.ui.unit.Dp) =
        panel - rail - TV_PAGE_GUTTER - TV_PAGE_GUTTER / 2

    /** The width for all of the time a kid is browsing — the rail is shut. */
    private val browsing = page(TV_RAIL_COLLAPSED)

    /** The width while the remote is in the rail, or before it has been touched. */
    private val menuOpen = page(TV_RAIL_EXPANDED)

    // --- the two widths ----------------------------------------------------

    @Test
    fun `both rail widths are the handoff's units through the one conversion`() {
        assertEquals(tvUnits(196f), TV_RAIL_EXPANDED)
        assertEquals(tvUnits(88f), TV_RAIL_COLLAPSED)
        assertEquals(147.dp, TV_RAIL_EXPANDED)
        assertEquals(66.dp, TV_RAIL_COLLAPSED)
    }

    @Test
    fun `the page is what the rail leaves, in both of the rail's states`() {
        // Every ten-foot tile in this app was sized against a ~880 dp page.
        // These are the two numbers it actually gets now.
        assertEquals(834.dp, browsing)
        assertEquals(753.dp, menuOpen)
    }

    @Test
    fun `the rail replaces the page's left gutter rather than adding to it`() {
        // If the page kept its own inset on the rail's side the kid would get
        // 66 dp of chrome and then 40 dp of nothing before the first tile. The
        // rail is wider than the gutter it stands in for, so the ten-foot
        // safe area is still covered on that edge.
        assertTrue(
            "the rail ($TV_RAIL_COLLAPSED) must be at least the page gutter ($TV_PAGE_GUTTER) it replaces",
            TV_RAIL_COLLAPSED.value >= TV_PAGE_GUTTER.value
        )
    }

    // --- what still has to fit in what is left -----------------------------

    /**
     * VideoGrid on a television is `GridCells.Adaptive(240.dp)` with 20 dp
     * between cells and 12 dp of padding either side. N fit when the page can
     * hold N tiles, N-1 gaps and both paddings.
     */
    private fun videoTilesAcross(width: androidx.compose.ui.unit.Dp): Int =
        generateSequence(1) { it + 1 }
            .first { (it + 1) * 240f + it * 20f + 24f > width.value }

    @Test
    fun `the ten-foot video grid is still three tiles across while the kid browses`() {
        assertEquals(3, videoTilesAcross(browsing))
    }

    @Test
    fun `it drops to two while the rail is open, and that is the known cost`() {
        // Stated rather than discovered: the page re-lays out when the rail
        // opens. It only happens while the remote is IN the rail — that is,
        // while the kid is looking at the menu and not at the grid — and the
        // alternative was pinning the page at its wider size and pushing its
        // right-hand column off the panel for as long as the menu was up.
        // If this ever becomes 3 the rail got narrower and the note above
        // YosemiteScreen's content Box is stale.
        assertEquals(2, videoTilesAcross(menuOpen))
    }

    @Test
    fun `three pinned heroes still fit at the width the design draws them`() {
        val home = homeMetrics(FormFactor.Tv)
        // PinnedHeroRow: three cards, two gaps, 8 dp of padding either side.
        // The cards are weighted, so this is not "or they overflow" — it is
        // "or the design's 258 dp card silently becomes something else".
        val needed = 3 * home.heroWidth.value + 2 * home.heroGap.value + 2 * 8f
        assertTrue("three heroes need $needed dp, page is $browsing", needed <= browsing.value)
    }

    @Test
    fun `the channels grid still gives every tile more than its artwork`() {
        val tv = channelMetrics(FormFactor.Tv)
        // TvChannelsGrid: four fixed columns, metrics.gap between them, 4 dp
        // of padding either side. Fixed columns cannot drop one, so a page too
        // narrow crops the art instead — in BOTH rail states.
        for (width in listOf(browsing, menuOpen)) {
            val cell = (width.value - 2 * 4f - (TV_CHANNEL_COLUMNS - 1) * tv.gap.value) / TV_CHANNEL_COLUMNS
            assertTrue("a channel cell is $cell dp at page $width and its art is ${tv.art}", cell >= tv.art.value)
        }
    }

    // --- where each screen lights the rail ---------------------------------

    private val source =
        Source("c1", "https://youtube.com/c1", "A channel", null, SourceKind.CHANNEL)

    @Test
    fun `a channel's own pages keep the Channels stop lit`() {
        // A kid three screens deep must still be able to see where they are.
        assertEquals(RailStop.Channels, railStopFor(Screen.Channels))
        assertEquals(RailStop.Channels, railStopFor(Screen.ChannelVideos(source)))
        assertEquals(RailStop.Channels, railStopFor(Screen.WatchedVideos(source)))
        assertEquals(RailStop.Channels, railStopFor(Screen.Playlists(source)))
        assertEquals(RailStop.Channels, railStopFor(Screen.Surprise))
    }

    @Test
    fun `every shelf the kid owns lights You`() {
        assertEquals(RailStop.You, railStopFor(Screen.You))
        assertEquals(RailStop.You, railStopFor(Screen.Watchlist))
        assertEquals(RailStop.You, railStopFor(Screen.WatchLater))
        assertEquals(RailStop.You, railStopFor(Screen.Queue))
        assertEquals(RailStop.You, railStopFor(Screen.Downloads))
        assertEquals(RailStop.You, railStopFor(Screen.History))
    }

    @Test
    fun `search is a stop of its own, results included`() {
        // The fourth rail item is not decoration: it goes somewhere today,
        // and its results page keeps it lit.
        assertEquals(RailStop.Search, railStopFor(Screen.Search))
        assertEquals(RailStop.Search, railStopFor(Screen.SearchResults("dinosaurs")))
    }

    @Test
    fun `home lights home`() {
        assertEquals(RailStop.Home, railStopFor(Screen.Home))
    }

    @Test
    fun `the rail has exactly the four stops the design draws`() {
        assertEquals(
            listOf("Home", "Channels", "You", "Search"),
            RailStop.entries.map { it.label }
        )
    }
}
