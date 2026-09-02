package io.pickwick.app

import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.Profile
import io.pickwick.app.data.ProfileLooks
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A kid's restyle on a device, adopted by the phone (ProfileLooks.mergeInto) and carried in the config. */
class ProfileLooksTest {

    private val dave = Profile(id = "aaaaaaaa", name = "Dave", avatar = "🦊", colorArgb = 1L, lookAt = 100L)
    private val emma = Profile(id = "bbbbbbbb", name = "Emma", avatar = "🐼", colorArgb = 2L)
    private val config = Whitelist(
        sources = listOf(WhitelistEntry("UCa", "https://youtube.com/channel/UCa", null, SourceKind.CHANNEL)),
        blockedVideoIds = emptySet(),
        profiles = listOf(dave, emma)
    )

    private fun looks(id: String, avatar: String, color: Long, at: Long) =
        """{"$id":{"avatar":"$avatar","color":$color,"at":$at}}"""

    @Test
    fun newerLookIsAdoptedAndMovesTheFingerprint() {
        val merged = ProfileLooks.mergeInto(config, looks(dave.id, "🚀", 7L, 200L))!!
        val d = merged.profile(dave.id)!!
        assertEquals("🚀", d.avatar)
        assertEquals(7L, d.colorArgb)
        assertEquals(200L, d.lookAt)
        assertEquals(emma, merged.profile(emma.id))
        assertNotEquals(ConfigStore.fingerprint(config), ConfigStore.fingerprint(merged))
    }

    @Test
    fun olderOrEqualLookIsIgnored() {
        assertNull(ProfileLooks.mergeInto(config, looks(dave.id, "🚀", 7L, 100L)))
        assertNull(ProfileLooks.mergeInto(config, looks(dave.id, "🚀", 7L, 50L)))
    }

    @Test
    fun unknownKidsAndGarbageAreIgnored() {
        assertNull(ProfileLooks.mergeInto(config, looks("cccccccc", "🚀", 7L, 999L)))
        assertNull(ProfileLooks.mergeInto(config, "not json"))
        assertNull(ProfileLooks.mergeInto(config, """{"${dave.id}":{"avatar":"","color":1,"at":999}}"""))
        assertNull(ProfileLooks.mergeInto(config, """{"${dave.id}":{"avatar":"🚀","at":999}}"""))
    }

    @Test
    fun lookAtRoundTripsThroughJsonAndIsOmittedWhenUnset() {
        val json = ConfigStore.toJson(config)
        val back = ConfigStore.fromJson(json)
        assertEquals(100L, back.profile(dave.id)!!.lookAt)
        assertEquals(0L, back.profile(emma.id)!!.lookAt)
        // Emma's profile object carries no lookAt key at all.
        assertEquals(1, Regex("\"lookAt\"").findAll(json).count())
    }

    @Test
    fun applyLookPrefersTheNewerStamp() {
        val look = ProfileLooks.Look("🐙", 9L, 150L)
        assertEquals("🐙", ProfileLooks.applyLook(dave, look).avatar)
        assertTrue(ProfileLooks.applyLook(dave, look.copy(at = 100L)) === dave)
        assertFalse(ProfileLooks.applyLook(emma, look) === emma)
    }

    @Test
    fun pickedPlaylistsRoundTripAndMoveTheFingerprint() {
        val entry = config.sources.first()
        val picked = config.copy(sources = listOf(entry.copy(playlistIds = listOf("PLx", "PLy"))))
        val back = ConfigStore.fromJson(ConfigStore.toJson(picked))
        assertEquals(listOf("PLx", "PLy"), back.sources.first().playlistIds)
        assertFalse(ConfigStore.toJson(config).contains("playlists"))
        assertNotEquals(ConfigStore.fingerprint(config), ConfigStore.fingerprint(picked))
    }
}
