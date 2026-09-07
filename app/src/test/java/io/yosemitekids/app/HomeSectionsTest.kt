package io.yosemitekids.app

import io.yosemitekids.app.data.Source
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Video
import io.yosemitekids.app.ui.HOME_PINS_MAX
import io.yosemitekids.app.ui.HOME_SHELVES
import io.yosemitekids.app.ui.HomeSection
import io.yosemitekids.app.ui.HomeShelf
import io.yosemitekids.app.ui.UiState
import io.yosemitekids.app.ui.VideoItem
import io.yosemitekids.app.ui.firstFocusableShelf
import io.yosemitekids.app.ui.homeSections
import io.yosemitekids.app.ui.homeShelfCounts
import io.yosemitekids.app.ui.pinMeta
import io.yosemitekids.app.ui.resolvePins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home as data: the shelf order a parent will one day save, and the
 * fail-closed join that decides which channels may be a hero card.
 *
 * Both are pure, and both are the kind of rule that is invisible on a device
 * until it is wrong in a way nobody can see — a saved order that silently
 * shifted after an update, or a channel restricted to an older sibling drawn
 * as the biggest thing on a five-year-old's home screen.
 */
class HomeSectionsTest {

    private fun source(id: String, name: String = id) =
        Source(id, "https://youtube.com/$id", name, null, SourceKind.CHANNEL)

    private fun item(id: String) = VideoItem(
        Video("https://youtu.be/$id", "Video $id", "A channel", null, 100), null
    )

    // --- the shelf list ----------------------------------------------------

    @Test
    fun `nothing saved gives the catalogue, in order, all on`() {
        val sections = homeSections(emptyList())
        assertEquals(HOME_SHELVES, sections.map { it.id })
        assertTrue(sections.all { it.enabled })
    }

    @Test
    fun `a saved order is obeyed, including the off switches`() {
        val saved = listOf(
            HomeSection(HomeShelf.VIDEOS),
            HomeSection(HomeShelf.PINNED, enabled = false),
            HomeSection(HomeShelf.CHANNELS)
        )
        val sections = homeSections(saved, catalogue = listOf(
            HomeShelf.PINNED, HomeShelf.CHANNELS, HomeShelf.VIDEOS
        ))
        assertEquals(
            listOf(HomeShelf.VIDEOS, HomeShelf.PINNED, HomeShelf.CHANNELS),
            sections.map { it.id }
        )
        assertEquals(false, sections.first { it.id == HomeShelf.PINNED }.enabled)
    }

    @Test
    fun `a shelf this build added appears, and the saved order keeps its say`() {
        // The update case: a saved order written before "keep-watching"
        // existed. It must not hide the new shelf, and must not lose its own.
        val saved = listOf(HomeSection(HomeShelf.VIDEOS), HomeSection(HomeShelf.PINNED))
        val sections = homeSections(saved, catalogue = listOf(
            HomeShelf.PINNED, HomeShelf.KEEP_WATCHING, HomeShelf.VIDEOS
        ))
        assertEquals(
            listOf(HomeShelf.VIDEOS, HomeShelf.PINNED, HomeShelf.KEEP_WATCHING),
            sections.map { it.id }
        )
        assertTrue(sections.last().enabled)
    }

    @Test
    fun `a shelf this build dropped leaves no hole`() {
        val saved = listOf(
            HomeSection("shelf-from-a-newer-build"),
            HomeSection(HomeShelf.CHANNELS)
        )
        val sections = homeSections(saved, catalogue = listOf(HomeShelf.PINNED, HomeShelf.CHANNELS))
        assertEquals(listOf(HomeShelf.CHANNELS, HomeShelf.PINNED), sections.map { it.id })
    }

    @Test
    fun `a saved id listed twice is kept once, in its first place`() {
        val saved = listOf(
            HomeSection(HomeShelf.VIDEOS),
            HomeSection(HomeShelf.CHANNELS),
            HomeSection(HomeShelf.VIDEOS, enabled = false)
        )
        val sections = homeSections(saved, catalogue = listOf(HomeShelf.CHANNELS, HomeShelf.VIDEOS))
        assertEquals(listOf(HomeShelf.VIDEOS, HomeShelf.CHANNELS), sections.map { it.id })
        assertTrue(sections.first().enabled)
    }

    // --- the pins ----------------------------------------------------------

    @Test
    fun `pins keep the parent's order, not the whitelist's`() {
        val visible = listOf(source("a"), source("b"), source("c"))
        val pins = resolvePins(listOf("c", "a"), visible)
        assertEquals(listOf("c", "a"), pins.map { it.source.id })
    }

    @Test
    fun `a pin the kid cannot see is dropped, and drops silently`() {
        // "b" is pinned but not in the visible list — held for review, or
        // restricted to an older sibling. It must not reach the hero.
        val visible = listOf(source("a"), source("c"))
        val pins = resolvePins(listOf("a", "b", "c"), visible)
        assertEquals(listOf("a", "c"), pins.map { it.source.id })
    }

    @Test
    fun `nothing visible means no hero at all`() {
        assertTrue(resolvePins(listOf("a", "b"), emptyList()).isEmpty())
        assertTrue(resolvePins(emptyList(), listOf(source("a"))).isEmpty())
    }

    @Test
    fun `an unknown id is never resolved by position or by name`() {
        // The failure this guards: falling back to "the first source" when a
        // saved id no longer matches anything.
        val pins = resolvePins(listOf("gone"), listOf(source("a"), source("b")))
        assertTrue(pins.isEmpty())
    }

    @Test
    fun `never more cards than the design draws`() {
        val visible = (1..6).map { source("s$it") }
        val pins = resolvePins(visible.map { it.id }, visible)
        assertEquals(HOME_PINS_MAX, pins.size)
    }

    @Test
    fun `a duplicate pin is one card`() {
        val visible = listOf(source("a"), source("b"))
        assertEquals(listOf("a", "b"), resolvePins(listOf("a", "a", "b"), visible).map { it.source.id })
    }

    @Test
    fun `the hero line says what is new, then how much there is, then nothing`() {
        assertEquals("3 new videos", pinMeta(newCount = 3, videoCount = 40))
        assertEquals("1 new video", pinMeta(newCount = 1, videoCount = 40))
        assertEquals("40 videos", pinMeta(newCount = 0, videoCount = 40))
        assertEquals("1 video", pinMeta(newCount = 0, videoCount = 1))
        // A channel whose cache has not landed says nothing rather than "0".
        assertEquals("", pinMeta(newCount = 0, videoCount = 0))
    }

    @Test
    fun `the meta line is built from the counts of the source it belongs to`() {
        val visible = listOf(source("a"), source("b"))
        val pins = resolvePins(
            pinned = listOf("b", "a"),
            visible = visible,
            newCount = { if (it.id == "b") 2 else 0 },
            videoCount = { if (it.id == "b") 9 else 5 }
        )
        assertEquals(listOf("2 new videos", "5 videos"), pins.map { it.meta })
    }

    // --- counts and the television's opening focus -------------------------

    @Test
    fun `counts come from the state each shelf actually draws`() {
        val state = UiState(
            channels = listOf(source("a"), source("b")),
            keepWatching = listOf(item("k1")),
            suggested = emptyList(),
            feed = listOf(item("f1"), item("f2"), item("f3")),
            recentHistory = listOf(item("h1"))
        )
        val counts = homeShelfCounts(state)
        assertEquals(0, counts[HomeShelf.PINNED])
        assertEquals(2, counts[HomeShelf.CHANNELS])
        assertEquals(1, counts[HomeShelf.KEEP_WATCHING])
        assertEquals(0, counts[HomeShelf.SUGGESTED])
        assertEquals(3, counts[HomeShelf.VIDEOS])
        assertEquals(1, counts[HomeShelf.HISTORY])
    }

    @Test
    fun `opening focus skips empty shelves and disabled ones`() {
        val sections = homeSections(emptyList())
        // A fresh install with nothing pinned: focus belongs to the channels.
        assertEquals(
            HomeShelf.CHANNELS,
            firstFocusableShelf(sections, mapOf(HomeShelf.PINNED to 0, HomeShelf.CHANNELS to 4))
        )
        // With pins, the hero is the topmost thing there is to focus.
        assertEquals(
            HomeShelf.PINNED,
            firstFocusableShelf(sections, mapOf(HomeShelf.PINNED to 2, HomeShelf.CHANNELS to 4))
        )
        // A shelf switched off is not a focus target however full it is.
        val heroOff = sections.map { if (it.id == HomeShelf.PINNED) it.copy(enabled = false) else it }
        assertEquals(
            HomeShelf.CHANNELS,
            firstFocusableShelf(heroOff, mapOf(HomeShelf.PINNED to 2, HomeShelf.CHANNELS to 4))
        )
    }

    @Test
    fun `an entirely empty home asks for no focus rather than the wrong one`() {
        assertNull(firstFocusableShelf(homeSections(emptyList()), emptyMap()))
    }
}
