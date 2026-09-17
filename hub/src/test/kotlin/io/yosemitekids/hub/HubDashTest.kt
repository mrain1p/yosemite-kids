package io.yosemitekids.hub

import io.yosemitekids.app.data.YouTubeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest a browser plays HD through, held to its shape: every BaseURL
 * is the hub's own proxy (never googlevideo, where the rules could not reach),
 * mp4 only (Safari plays no WebM), nothing above the ceiling, one audio
 * track, and the byte ranges the media source needs on every rendition.
 */
class HubDashTest {

    private fun v(itag: Int, height: Int, mime: String = "video/mp4", codec: String = "avc1.4d401f") =
        YouTubeRepository.DashStream(itag, "https://rr1.googlevideo.com/videoplayback?itag=$itag", mime, codec, height * 2000, height * 16 / 9, height, 30, 0, 740, 741, 1200, 5_000_000)

    private fun a(itag: Int, mime: String = "audio/mp4") =
        YouTubeRepository.DashStream(itag, "https://rr1.googlevideo.com/videoplayback?itag=$itag", mime, "mp4a.40.2", 128_000, 0, 0, 0, 0, 640, 641, 900, 1_000_000)

    @Test
    fun theManifestPointsEveryRenditionAtTheProxyAndKeepsTheRanges() {
        val set = YouTubeRepository.DashSet("A video", 125, listOf(v(137, 1080), v(136, 720), v(134, 360)), listOf(a(140)))
        val mpd = HubDash.mpd("aaaaaaaaaaa", set)
        assertTrue(mpd.contains("""mediaPresentationDuration="PT125S""""))
        assertEquals("three video renditions and one audio", 4, Regex("<Representation ").findAll(mpd).count())
        assertFalse("never googlevideo: the rules live in the proxy", mpd.contains("googlevideo"))
        assertTrue(mpd.contains("<BaseURL>/kid/media?v=aaaaaaaaaaa&amp;s=137</BaseURL>"))
        assertTrue(mpd.contains("""<SegmentBase indexRange="741-1200"><Initialization range="0-740"/></SegmentBase>"""))
        assertTrue(mpd.contains("""<BaseURL>/kid/media?v=aaaaaaaaaaa&amp;s=140</BaseURL>"""))
    }

    @Test
    fun webmAndAnythingAboveTheCeilingStayOut() {
        val set = YouTubeRepository.DashSet(
            "A video", 10,
            listOf(v(313, 2160), v(248, 1080, mime = "video/webm", codec = "vp9"), v(137, 1080), v(136, 720)),
            listOf(a(251, mime = "audio/webm"), a(140), a(139))
        )
        val mpd = HubDash.mpd("aaaaaaaaaaa", set)
        assertFalse("2160p is above the ceiling", mpd.contains("""id="313""""))
        assertFalse("WebM plays on no iPad", mpd.contains("vp9"))
        assertTrue(mpd.contains("""id="137""""))
        assertTrue(mpd.contains("""id="136""""))
        assertEquals("one audio track, the best mp4 one", 1, Regex("""<AdaptationSet mimeType="audio/mp4"""").findAll(mpd).count())
        assertTrue(mpd.contains("""id="140""""))
        assertFalse(mpd.contains("""id="139""""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun noMp4PairIsRefusedRatherThanServed() {
        HubDash.mpd("aaaaaaaaaaa", YouTubeRepository.DashSet("x", 10, listOf(v(248, 1080, mime = "video/webm")), listOf(a(140))))
    }
}
