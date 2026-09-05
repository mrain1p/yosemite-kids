package io.pickwick.app

import io.pickwick.app.data.AiConfig
import io.pickwick.app.data.ConfigJson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A blank AI provider must stay blank across a parse.
 *
 * The parser used to turn `"baseUrl": ""` into the OpenRouter default. The
 * settings form then saw a fingerprint that differed from the file, and its
 * autosave wrote OpenRouter back to disk — a provider the parent never chose,
 * persisted by opening a page. Found on the emulator by watching config.json.
 */
class AiBaseUrlRoundTripTest {

    @Test
    fun anExplicitlyBlankProviderStaysBlank() {
        val w = ConfigJson.fromJson("""{"ai":{"enabled":false,"baseUrl":"","model":""}}""")
        assertEquals("", w.ai.baseUrl)
    }

    @Test
    fun aConfigFromBeforeTheAiBlockStillGetsTheDefault() {
        // Old installs have no ai block at all. They should keep getting a
        // provider to start from, exactly as before.
        val w = ConfigJson.fromJson("""{}""")
        assertEquals(AiConfig().baseUrl, w.ai.baseUrl)
    }

    @Test
    fun aChosenProviderIsPreservedVerbatim() {
        val w = ConfigJson.fromJson("""{"ai":{"baseUrl":"https://api.openai.com/v1"}}""")
        assertEquals("https://api.openai.com/v1", w.ai.baseUrl)
    }
}
