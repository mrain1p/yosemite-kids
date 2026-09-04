package io.pickwick.app

import io.pickwick.app.data.AiConfig
import io.pickwick.app.data.ConfigJson
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The keyless fingerprint, and the three-way agreement it rests on.
 *
 * A peer that deliberately holds no API key — the hub — strips the key before
 * writing and has no SecretStore to put it back, so the fingerprint it
 * advertises is computed over a config whose key is blank. Judged against a
 * phone's full fingerprint it could never match, and the hub read as
 * permanently out of sync: the Devices tile said so, Push and Pull were
 * offered forever, and the background reconcile took the merge arm on every
 * sweep instead of doing nothing.
 *
 * These live in :core rather than :app because the hub runs this exact code.
 * A copy in :app would prove the fingerprint agrees with itself on Android and
 * prove nothing at all about the peer it has to agree with.
 */
class ConfigSecretlessTest {

    private fun entry(id: String) = WhitelistEntry(
        id = id,
        url = "https://www.youtube.com/channel/$id",
        label = id,
        kind = SourceKind.CHANNEL
    )

    private fun config(
        key: String = "sk-live-abcdef",
        model: String = "gpt-4o-mini",
        rules: String = "no scary stuff"
    ) = Whitelist(
        sources = listOf(entry("UCaaa")),
        blockedVideoIds = setOf("vid1"),
        ai = AiConfig(enabled = true, model = model, apiKey = key, rules = rules)
    )

    @Test
    fun theKeylessFingerprintIsWhatAPeerHoldingNoKeyComputes() {
        // THE assertion. Hub/phone agreement currently depends on three
        // independent facts lining up: stripSecrets removes the apiKey member
        // entirely, fromJson turns an absent apiKey into "", and
        // includeSecrets=false substitutes exactly "". Change any one of them
        // and the hub is silently out of sync forever again — which is
        // precisely the bug this test exists for. This pins all three at once,
        // by following the same path the hub does.
        val local = config()
        val asTheHubStoresIt = ConfigJson.fromJson(
            ConfigJson.stripSecrets(ConfigJson.toJson(local))
        )

        assertEquals(
            ConfigJson.fingerprint(local, includeSecrets = false),
            ConfigJson.fingerprint(asTheHubStoresIt)
        )
    }

    @Test
    fun aKeyStillMovesTheOrdinaryFingerprint() {
        // The default must stay true. The settings form autosaves on a
        // fingerprint change and nothing else, so a key edit that stopped
        // moving it would never be saved at all — the key would be lost on the
        // phone itself, not merely unpropagated.
        assertNotEquals(
            ConfigJson.fingerprint(config(key = "sk-old")),
            ConfigJson.fingerprint(config(key = "sk-new"))
        )
    }

    @Test
    fun theKeylessFingerprintIgnoresOnlyTheKey() {
        val keyless = { w: Whitelist -> ConfigJson.fingerprint(w, includeSecrets = false) }

        assertEquals(
            "rotating the key must not move the keyless form",
            keyless(config(key = "sk-old")),
            keyless(config(key = "sk-new"))
        )
        assertNotEquals(
            "the model is policy, not a secret — it must still be compared",
            keyless(config(model = "gpt-4o")),
            keyless(config(model = "gpt-3.5"))
        )
        assertNotEquals(
            "the screening rules must still be compared",
            keyless(config(rules = "no scary stuff")),
            keyless(config(rules = "anything goes"))
        )
    }

    @Test
    fun aDelimiterInsideAnAiFieldCannotForgeAgreement() {
        // The AI fields are parent-typed free text joined on "|" and were never
        // scrubbed of it. Blanking the key leaves an empty slot a stray "|" can
        // migrate across, so without escaping these two configs — different
        // model AND different rules — canonicalize identically and a hub
        // holding either would read as in sync.
        val a = config(model = "gpt-4|", rules = "r")
        val b = config(model = "gpt-4", rules = "|r")

        assertNotEquals(
            ConfigJson.fingerprint(a, includeSecrets = false),
            ConfigJson.fingerprint(b, includeSecrets = false)
        )
        assertNotEquals(ConfigJson.fingerprint(a), ConfigJson.fingerprint(b))
    }

    @Test
    fun aConfigWithNoKeyHashesTheSameBothWays() {
        // The hub's own case, from the other direction: blanking a key that
        // is already blank must be a no-op. If the two forms ever diverged
        // here, a hub would disagree with itself the moment a family turned
        // AI off, which is the same failure wearing a different hat.
        val noKey = config(key = "")
        assertEquals(
            ConfigJson.fingerprint(noKey),
            ConfigJson.fingerprint(noKey, includeSecrets = false)
        )
    }
}
