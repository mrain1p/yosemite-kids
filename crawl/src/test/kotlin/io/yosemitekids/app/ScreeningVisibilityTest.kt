package io.yosemitekids.app

import io.yosemitekids.app.data.AiConfig
import io.yosemitekids.app.data.AiScreener
import io.yosemitekids.app.data.Screening
import io.yosemitekids.app.data.ScreeningRules
import io.yosemitekids.app.data.ScreeningStore
import io.yosemitekids.app.data.Video
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The one visibility predicate, tested where it now has to hold.
 *
 * In `:app` this proved the filter works *on Android*, and proved nothing
 * about the hub — which holds the same verdicts and is about to answer the
 * same question for a browser. Here it covers both, which is the whole point
 * of the predicate having moved.
 */
class ScreeningVisibilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val video = Video(
        url = "https://www.youtube.com/watch?v=abcdefghijk",
        title = "Nerf battle 3000",
        channelName = "Nerf Central",
        thumbnailUrl = null,
        durationSeconds = 300
    )

    private val on = AiConfig(enabled = true, model = "m", rulesVersion = 1)

    private fun entry(
        verdict: AiScreener.Verdict,
        noteHash: Int = 0,
        rulesVersion: Int = 1,
        perProfile: Map<String, AiScreener.Verdict> = emptyMap()
    ) = ScreeningStore.Entry(
        verdict = verdict, reason = "r", title = "t", channel = "Nerf Central",
        thumb = null, rulesVersion = rulesVersion, at = 1L,
        perProfile = perProfile, noteHash = noteHash
    )

    private fun store(e: ScreeningStore.Entry?): ScreeningStore {
        val s = ScreeningStore(File(tmp.root, "screening-${counter++}.json"))
        if (e != null) s.putAll(mapOf("abcdefghijk" to e))
        return s
    }

    private var counter = 0

    private fun visible(e: ScreeningStore.Entry?, rules: ScreeningRules) =
        Screening.isVisible(store(e), rules, video)

    private fun needs(e: ScreeningStore.Entry?, rules: ScreeningRules) =
        Screening.needsScreening(store(e), rules, video)

    @Test
    fun `screening off shows everything and asks for nothing`() {
        val off = ScreeningRules(config = AiConfig())
        assertTrue(visible(null, off))
        assertFalse(needs(null, off))
    }

    @Test
    fun `no verdict yet is hidden, not allowed`() {
        val rules = ScreeningRules(config = on)
        assertFalse("an unscreened video must fail closed", visible(null, rules))
        assertTrue("and must be queued for screening", needs(null, rules))
    }

    @Test
    fun `a parent override beats the absence of a verdict`() {
        val rules = ScreeningRules(config = on, allowedOverrides = setOf("abcdefghijk"))
        assertTrue(visible(null, rules))
        assertFalse("an overridden video is never billed to the AI", needs(null, rules))
    }

    @Test
    fun `a verdict from an older rules version does not count`() {
        val rules = ScreeningRules(config = on)
        val stale = entry(AiScreener.Verdict.ALLOW, rulesVersion = 0)
        assertFalse(visible(stale, rules))
        assertTrue(needs(stale, rules))
    }

    @Test
    fun `the active kid's own verdict decides`() {
        val allowedForAmy = entry(
            AiScreener.Verdict.BLOCK,
            perProfile = mapOf("amy" to AiScreener.Verdict.ALLOW)
        )
        // A per-kid ALLOW inside a BLOCK entry is exempt from the note check —
        // BLOCK entries never re-screen, so it would otherwise go dark for good.
        assertTrue(visible(allowedForAmy, ScreeningRules(config = on, activeProfileId = "amy")))
        assertFalse(visible(allowedForAmy, ScreeningRules(config = on, activeProfileId = "ben")))
        assertFalse("no active kid falls back to the strictest verdict",
            visible(allowedForAmy, ScreeningRules(config = on)))
    }

    @Test
    fun `editing a note re-screens allows and reviews but never blocks`() {
        val note = "toy guns are fine"
        val withNote = ScreeningRules(
            config = on, channelNotes = mapOf("Nerf Central" to note)
        )
        // ALLOW under the old (absent) note: hidden and re-screened.
        assertFalse(visible(entry(AiScreener.Verdict.ALLOW), withNote))
        assertTrue(needs(entry(AiScreener.Verdict.ALLOW), withNote))
        // ALLOW under the current note: visible, nothing to do.
        val current = entry(AiScreener.Verdict.ALLOW, AiScreener.noteHash(note))
        assertTrue(visible(current, withNote))
        assertFalse(needs(current, withNote))
        // BLOCK under the old note: stays blocked, never re-screened.
        assertFalse(visible(entry(AiScreener.Verdict.BLOCK), withNote))
        assertFalse(needs(entry(AiScreener.Verdict.BLOCK), withNote))
        // REVIEW under the old note: still hidden, but the new note may resolve it.
        assertFalse(visible(entry(AiScreener.Verdict.REVIEW), withNote))
        assertTrue(needs(entry(AiScreener.Verdict.REVIEW), withNote))
        // Clearing the note works the same way: an ALLOW under it goes stale.
        assertTrue(needs(current, ScreeningRules(config = on)))
    }

    @Test
    fun `a url with no video id is never visible`() {
        val noId = video.copy(url = "https://www.youtube.com/watch?list=PL1")
        assertFalse(Screening.isVisible(store(null), ScreeningRules(config = on), noId))
        assertFalse(
            "and it cannot be screened either, so it must not be queued",
            Screening.needsScreening(store(null), ScreeningRules(config = on), noId)
        )
    }
}
