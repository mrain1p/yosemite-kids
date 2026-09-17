package io.yosemitekids.app

import io.yosemitekids.app.data.CHANNEL_ORDER_ALPHA
import io.yosemitekids.app.data.CHANNEL_ORDER_ALPHA_DESC
import io.yosemitekids.app.data.CHANNEL_ORDER_RANDOM
import io.yosemitekids.app.data.Source
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.ui.orderChannels
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A kid's favourite channels float to the front of every order, keep the
 * chip's order among themselves, and change nothing for a kid with none -
 * one rule in :crawl, so the phone, the television and the browser agree.
 */
class ChannelFavouritesTest {

    private fun ch(name: String) = Source("UC$name", "https://www.youtube.com/@$name", name, null, SourceKind.CHANNEL)
    private val channels = listOf(ch("pears"), ch("apples"), ch("mangoes"), ch("bananas"))
    private fun names(sort: String, favourites: Set<String>, seed: Long = 1L) =
        orderChannels(channels, sort, opens = { 0 }, latestUpload = { null }, seed = seed, favourites = favourites).map { it.name }

    @Test
    fun favouritesComeFirstInTheChipsOwnOrder() {
        val favs = setOf("https://www.youtube.com/@mangoes", "https://www.youtube.com/@apples")
        assertEquals(listOf("apples", "mangoes", "bananas", "pears"), names(CHANNEL_ORDER_ALPHA, favs))
        assertEquals(listOf("mangoes", "apples", "pears", "bananas"), names(CHANNEL_ORDER_ALPHA_DESC, favs))
    }

    @Test
    fun noFavouritesChangesNothing() {
        assertEquals(listOf("apples", "bananas", "mangoes", "pears"), names(CHANNEL_ORDER_ALPHA, emptySet()))
        // The seeded shuffle is the same shuffle with the hearts lifted out of it.
        val plain = names(CHANNEL_ORDER_RANDOM, emptySet(), seed = 7L)
        val lifted = names(CHANNEL_ORDER_RANDOM, setOf("https://www.youtube.com/@pears"), seed = 7L)
        assertEquals("pears", lifted.first())
        assertEquals(plain.filter { it != "pears" }, lifted.drop(1))
    }
}
