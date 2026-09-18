package io.yosemitekids.app

import io.yosemitekids.app.data.ConfigJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One row this build cannot read costs that row, not the family.
 *
 * Every "bad config" test in this repo used whole-file garbage — `"{{{"`,
 * `""`, `"not json at all"` — and the reader is right to refuse those. The
 * case nobody held was the one that actually happens: valid JSON with one
 * element in it that this build cannot make sense of, because a newer build
 * wrote a field differently or an editor's hand slipped. That threw out of the
 * whole parse, and a document that will not parse reads as a family with no
 * children and no channels on every face that reads it.
 *
 * The pins, the grants and the home rows have always been lenient this way.
 * The channels and the kids — the two that matter most — were not.
 */
class OneBadRowTest {

    private fun config(entries: String, profiles: String = "[]"): String =
        """{"entries":$entries,"blocked":[],"profiles":$profiles}"""

    private val good = """{"id":"UC1","url":"https://www.youtube.com/channel/UC1","label":"Nature","kind":"CHANNEL"}"""
    private val goodKid = """{"id":"ada00001","name":"Ada"}"""

    @Test
    fun `a channel with no id is dropped and the rest of the family survives`() {
        val w = ConfigJson.fromJson(
            config("""[$good, {"url":"https://www.youtube.com/channel/UC2"}, {"id":"UC3","url":"https://www.youtube.com/channel/UC3"}]""")
        )
        assertEquals("the unreadable one, and only it", 2, w.sources.size)
        assertEquals(listOf("UC1", "UC3"), w.sources.map { it.id })
    }

    @Test
    fun `a channel that is not an object at all is dropped`() {
        val w = ConfigJson.fromJson(config("""[$good, "a string where a channel should be", 7]"""))
        assertEquals(1, w.sources.size)
        assertEquals("UC1", w.sources.first().id)
    }

    @Test
    fun `a kid with no id is dropped and their siblings stay`() {
        val w = ConfigJson.fromJson(
            config("[$good]", """[$goodKid, {"name":"Nobody"}, {"id":"sam00001","name":"Sam"}]""")
        )
        assertEquals(2, w.profiles.size)
        assertEquals(listOf("ada00001", "sam00001"), w.profiles.map { it.id })
        assertNotNull("and the family is still a family", w.profile("ada00001"))
        assertNull(w.profile("nobody"))
    }

    @Test
    fun `whole-file garbage is still refused rather than read as empty`() {
        for (junk in listOf("{{{", "", "not json at all", "[]")) {
            val failed = runCatching { ConfigJson.fromJson(junk) }.isFailure
            assert(failed) { "`$junk` must not parse as a family with nobody in it" }
        }
    }

    @Test
    fun `a good document still round-trips unchanged`() {
        val w = ConfigJson.fromJson(config("[$good]", "[$goodKid]"))
        val again = ConfigJson.fromJson(ConfigJson.toJson(w))
        assertEquals(w.sources.map { it.id }, again.sources.map { it.id })
        assertEquals(w.profiles.map { it.id }, again.profiles.map { it.id })
        assertEquals(
            "and nothing about the dropping changed the fingerprint rule",
            ConfigJson.fingerprint(w), ConfigJson.fingerprint(again)
        )
    }
}
