package io.pickwick.app

import io.pickwick.app.data.AiConfig
import io.pickwick.app.data.ConfigMerge
import io.pickwick.app.data.Limits
import io.pickwick.app.data.Profile
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structural diff behind "what am I about to overwrite?" — the line the
 * Pull dialog shows above its replace warning.
 *
 * The dialog exists because today's Pull is blind: a parent accepts a device's
 * whole config with no idea what of theirs it discards. These tests pin what
 * the parent is told, and — as much as anything here — what they are never
 * told, namely the API key.
 */
class ConfigDiffTest {

    private fun entry(id: String, label: String? = null) = WhitelistEntry(
        id = id,
        url = "https://www.youtube.com/channel/$id",
        label = label,
        kind = SourceKind.CHANNEL
    )

    private fun config(
        sources: List<WhitelistEntry> = emptyList(),
        blocked: Set<String> = emptySet(),
        profiles: List<Profile> = emptyList(),
        limits: Limits = Limits(),
        ai: AiConfig = AiConfig()
    ) = Whitelist(
        sources = sources,
        blockedVideoIds = blocked,
        limits = limits,
        ai = ai,
        profiles = profiles
    )

    private fun texts(a: Whitelist, b: Whitelist) = ConfigMerge.describe(a, b).map { it.text }
    private fun codes(a: Whitelist, b: Whitelist) = ConfigMerge.describe(a, b).map { it.code }

    @Test
    fun describeIsEmptyForIdenticalConfigs() {
        val c = config(
            sources = listOf(entry("UCaaa", "SciShow Kids")),
            profiles = listOf(Profile(id = "e3f1a90c", name = "Emma"))
        )
        assertTrue(ConfigMerge.describe(c, c).isEmpty())
        assertTrue(ConfigMerge.identical(c, c))
    }

    @Test
    fun describeNamesAddsAndRemoves() {
        val a = config(sources = listOf(entry("UCaaa", "SciShow Kids")))
        val b = config(sources = listOf(entry("UCbbb", "StoryBots")))

        val out = texts(a, b)
        assertTrue("must name what arrives: $out", out.any { it == "adds StoryBots" })
        assertTrue("must name what is lost: $out", out.any { it == "removes SciShow Kids" })
        assertEquals(listOf("src.add", "src.remove"), codes(a, b))
    }

    @Test
    fun anUnlabelledChannelFallsBackToItsId() {
        val a = config()
        val b = config(sources = listOf(entry("UCaaa")))
        assertEquals(listOf("adds UCaaa"), texts(a, b))
    }

    @Test
    fun describeNamesARename() {
        val a = config(sources = listOf(entry("UCaaa", "SciShow")))
        val b = config(sources = listOf(entry("UCaaa", "SciShow Kids")))
        assertEquals(listOf("renames SciShow to SciShow Kids"), texts(a, b))
    }

    @Test
    fun removingAKidSaysWhatElseGoesWithThem() {
        val a = config(profiles = listOf(Profile(id = "e3f1a90c", name = "Emma")))
        val b = config()
        assertEquals(listOf("removes Emma and their settings"), texts(a, b))
    }

    @Test
    fun screenTimeChangesShowBothNumbers() {
        // The number a parent recognises at a glance is the point — this is
        // the same line the collision banner will later have to offer to
        // put back, so it has to carry the old value as well as the new.
        val a = config(limits = Limits(sessionMinutes = 45))
        val b = config(limits = Limits(sessionMinutes = 30))
        assertEquals(listOf("changes everyone's sitting length 45 min to 30 min"), texts(a, b))
    }

    @Test
    fun aPerKidLimitIsAttributedToThatKid() {
        val a = config(profiles = listOf(Profile(id = "k1", name = "Emma", limits = Limits(sessionMinutes = 45))))
        val b = config(profiles = listOf(Profile(id = "k1", name = "Emma", limits = Limits(sessionMinutes = 30))))
        assertEquals(listOf("changes Emma's sitting length 45 min to 30 min"), texts(a, b))
        assertEquals(listOf("kid.limits"), codes(a, b))
    }

    @Test
    fun clearingALimitReadsAsNoLimitNotAsNull() {
        val a = config(limits = Limits(sessionMinutes = 45))
        val b = config(limits = Limits(sessionMinutes = null))
        assertEquals(listOf("changes everyone's sitting length 45 min to no limit"), texts(a, b))
    }

    @Test
    fun blockCountsAreSummarisedAndPluralisedProperly() {
        assertEquals(
            listOf("adds 1 blocked video"),
            texts(config(), config(blocked = setOf("v1")))
        )
        assertEquals(
            listOf("adds 2 blocked videos"),
            texts(config(), config(blocked = setOf("v1", "v2")))
        )
        assertEquals(
            listOf("removes 1 blocked video"),
            texts(config(blocked = setOf("v1", "v2")), config(blocked = setOf("v1")))
        )
    }

    @Test
    fun aChangedPinIsReportedButNeverShown() {
        val a = config(profiles = listOf(Profile(id = "k1", name = "Leo", pin = "1234")))
        val b = config(profiles = listOf(Profile(id = "k1", name = "Leo", pin = "9876")))

        val out = texts(a, b)
        assertEquals(listOf("changes Leo's code"), out)
        assertFalse("a code must never be rendered", out.any { it.contains("1234") || it.contains("9876") })
    }

    @Test
    fun describeNeverRendersTheApiKeyOrTheEndpoint() {
        val a = config(ai = AiConfig(enabled = false, apiKey = "sk-secret-aaa", baseUrl = "https://a.example/v1"))
        val b = config(ai = AiConfig(enabled = true, apiKey = "sk-secret-bbb", baseUrl = "https://b.example/v1"))

        val out = texts(a, b)
        assertEquals(listOf("turns AI screening on"), out)
        assertFalse(out.any { it.contains("sk-secret") })
        assertFalse(out.any { it.contains("example") })
    }

    @Test
    fun aKeyOnlyChangeIsNotReportedAtAll() {
        // Nothing a parent can act on, and rendering "the key changed" invites
        // a screenshot of a dialog that should never be near a credential.
        val a = config(ai = AiConfig(model = "m", apiKey = "sk-aaa"))
        val b = config(ai = AiConfig(model = "m", apiKey = "sk-bbb"))
        assertTrue(ConfigMerge.describe(a, b).isEmpty())
    }

    @Test
    fun looseSettingsCollapseIntoOneReadableLine() {
        val a = config()
        val b = Whitelist(
            sources = emptyList(), blockedVideoIds = emptySet(),
            autoplayNext = false, suggestSimilar = false, showVideoAge = true
        )
        assertEquals(
            listOf("changes autoplay, suggestions and showing when a video came out"),
            texts(a, b)
        )
    }

    @Test
    fun twoSettingsReadAsAnAndNotAComma() {
        val a = config()
        val b = Whitelist(sources = emptyList(), blockedVideoIds = emptySet(), autoplayNext = false, sponsorSkip = false)
        assertEquals(listOf("changes sponsor skipping and autoplay"), texts(a, b))
    }

    @Test
    fun channelsComeBeforeKidsWhichComeBeforeSettings() {
        // Order is the whole readability argument: a parent scanning this
        // dialog wants the content changes first and the switches last.
        val a = config(sources = listOf(entry("UCaaa", "SciShow")), profiles = listOf(Profile(id = "k1", name = "Emma")))
        val b = Whitelist(
            sources = listOf(entry("UCaaa", "SciShow"), entry("UCbbb", "StoryBots")),
            blockedVideoIds = emptySet(),
            profiles = listOf(Profile(id = "k1", name = "Emily")),
            autoplayNext = false
        )
        assertEquals(listOf("src.add", "kid.name", "settings"), codes(a, b))
    }
}
