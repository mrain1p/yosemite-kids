package io.yosemitekids.app

import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.AiConfig
import io.yosemitekids.app.data.ConfigStore
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AI API key travels to paired devices but must never reach `config.json`,
 * which is listed for cloud backup.
 */
class ConfigSecretsTest {

    private fun config(key: String) = Whitelist(
        sources = listOf(
            WhitelistEntry("UCa", "https://www.youtube.com/channel/UCa", "A", SourceKind.CHANNEL)
        ),
        blockedVideoIds = emptySet(),
        ai = AiConfig(enabled = true, model = "some/model", apiKey = key, rules = "be kind")
    )

    @Test
    fun `a push carries the key so the device can screen`() {
        val json = ConfigJson.toJson(config("sk-secret"))
        assertTrue(json.contains("sk-secret"))
        assertEquals("sk-secret", ConfigJson.fromJson(json).ai.apiKey)
    }

    @Test
    fun `the on-disk copy leaves the key out`() {
        val json = ConfigJson.toJson(config("sk-secret"), includeSecrets = false)
        assertFalse(json.contains("sk-secret"))
        assertFalse(json.contains("apiKey"))
        // Everything else still round-trips.
        val parsed = ConfigJson.fromJson(json)
        assertEquals("some/model", parsed.ai.model)
        assertEquals("be kind", parsed.ai.rules)
        assertEquals(1, parsed.sources.size)
    }

    @Test
    fun `stripping a pushed payload keeps fields this build does not know`() {
        val pushed = JSONObject(ConfigJson.toJson(config("sk-secret")))
            .put("somethingNewerPhonesSend", "keep me")
            .toString()

        val stored = ConfigJson.stripSecrets(pushed)

        assertFalse(stored.contains("sk-secret"))
        val root = JSONObject(stored)
        assertEquals("keep me", root.getString("somethingNewerPhonesSend"))
        assertFalse(root.getJSONObject("ai").has("apiKey"))
        // updatedAt is preserved verbatim, not restamped — the phone's edit time
        // is what both ends compare.
        assertEquals(
            JSONObject(pushed).getLong("updatedAt"),
            root.getLong("updatedAt")
        )
    }

    @Test
    fun `stripping is a no-op on payloads without a key`() {
        val json = ConfigJson.toJson(config("sk-secret"), includeSecrets = false)
        assertEquals(json, ConfigJson.stripSecrets(json))
        assertEquals("not json", ConfigJson.stripSecrets("not json"))
    }

    @Test
    fun `the key still changes the fingerprint so a rotated key reaches devices`() {
        // The canonical string never leaves the device — only its 4-byte digest
        // does — so hashing the key here is safe, and necessary: sync re-pushes
        // only on a fingerprint mismatch.
        assertFalse(
            ConfigJson.fingerprint(config("sk-one")) == ConfigJson.fingerprint(config("sk-two"))
        )
    }
}
