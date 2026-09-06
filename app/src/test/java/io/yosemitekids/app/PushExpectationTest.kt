package io.yosemitekids.app

import io.yosemitekids.app.data.AiConfig
import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.PairedDevice
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import io.yosemitekids.app.ui.expectedAfterPush
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What the Push button judges a device's answer against: the bytes it just
 * sent, not the form the screen held when the button was drawn. The save
 * inside Push mints stamps and can carry a co-parent's unit in, so the
 * composition-time hashes are one save behind by the time the device answers.
 */
class PushExpectationTest {

    private fun device(secretless: Boolean) =
        PairedDevice(name = "TV", host = "10.0.0.2", port = 8765, token = "t", secretless = secretless)

    private val sent = Whitelist(
        sources = listOf(WhitelistEntry("UCa", "https://www.youtube.com/channel/UCa", "A", SourceKind.CHANNEL)),
        blockedVideoIds = emptySet(),
        ai = AiConfig(enabled = true, model = "some/model", apiKey = "sk-secret")
    )

    @Test
    fun aTvIsJudgedOnTheFullFingerprintOfWhatWasSent() {
        val json = ConfigJson.toJson(sent)
        assertEquals(ConfigJson.fingerprint(sent), expectedAfterPush(device(secretless = false), json))
    }

    @Test
    fun aHubIsJudgedOnTheSecretlessFingerprintOfWhatWasSent() {
        val json = ConfigJson.toJson(sent)
        assertEquals(ConfigJson.fingerprint(sent, includeSecrets = false), expectedAfterPush(device(secretless = true), json))
        // The distinction is real: the key moves the full hash.
        assertNotEquals(expectedAfterPush(device(true), json), expectedAfterPush(device(false), json))
    }

    @Test
    fun theExpectationFollowsTheBytesNotTheScreen() {
        // The screen held `sent`; the save carried a co-parent's channel in.
        val carried = sent.copy(sources = sent.sources + WhitelistEntry("UCb", "https://www.youtube.com/channel/UCb", "B", SourceKind.CHANNEL))
        val json = ConfigJson.toJson(carried)
        assertNotEquals(ConfigJson.fingerprint(sent), expectedAfterPush(device(false), json))
        assertEquals(ConfigJson.fingerprint(carried), expectedAfterPush(device(false), json))
    }
}
