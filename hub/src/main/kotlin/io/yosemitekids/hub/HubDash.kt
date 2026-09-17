package io.yosemitekids.hub

import io.yosemitekids.app.data.YouTubeRepository

/**
 * The DASH manifest a browser plays HD through.
 *
 * The muxed progressive stream `GET /kid/media` serves caps at about 360p;
 * HD on YouTube is a video-only track and an audio-only track, merged at
 * playback. ExoPlayer does that merge on the phone and the television; a
 * plain `<video>` cannot, and Media Source Extensions can - given a manifest
 * that says where each track's segments are. YouTube's progressive DASH
 * streams carry exactly that in their init and index byte ranges, so this
 * writes the manifest by hand: one video adaptation set with every mp4
 * rendition up to the ceiling (dash.js picks by bandwidth), one audio set
 * with the best original-language track, and every BaseURL pointing back at
 * the hub's own proxy (`/kid/media?v=…&s=<itag>`), because the rules on a
 * kid's stream - the per-chunk gate, the block that takes effect mid-video -
 * live in that proxy and a redirect would step around them (see HubStream).
 *
 * mp4 only: Safari plays no WebM, and an iPad is the browser this exists
 * for. Pure, so `HubDashTest` can hold the shape without a video.
 */
object HubDash {

    /** What a browser is offered at most. 1080p on a tablet held close is plenty, and above it the bytes double for nothing. */
    const val MAX_HEIGHT = 1080

    /** The `s=` a manifest hands the proxy: the stream's itag, which is how YouTube names a rendition. */
    fun streamParam(itag: Int): String = itag.toString()

    fun mpd(videoId: String, set: YouTubeRepository.DashSet): String {
        val video = set.video.filter { it.height <= MAX_HEIGHT && it.mimeType == "video/mp4" }
            .ifEmpty { set.video.filter { it.mimeType == "video/mp4" } }
        val audio = set.audio.filter { it.mimeType == "audio/mp4" }.take(1)
        require(video.isNotEmpty() && audio.isNotEmpty()) { "no mp4 video and audio pair" }
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT1.5S" mediaPresentationDuration="PT${set.durationSeconds}S">""").append('\n')
        sb.append("  <Period>\n")
        sb.append("""    <AdaptationSet mimeType="video/mp4" subsegmentAlignment="true" startWithSAP="1">""").append('\n')
        video.forEach { s ->
            sb.append("""      <Representation id="${s.itag}" codecs="${escape(s.codec)}" bandwidth="${s.bitrate}" width="${s.width}" height="${s.height}"""")
            if (s.fps > 0) sb.append(""" frameRate="${s.fps}"""")
            sb.append(">\n")
            representationBody(sb, videoId, s)
            sb.append("      </Representation>\n")
        }
        sb.append("    </AdaptationSet>\n")
        sb.append("""    <AdaptationSet mimeType="audio/mp4" subsegmentAlignment="true" startWithSAP="1">""").append('\n')
        audio.forEach { s ->
            sb.append("""      <Representation id="${s.itag}" codecs="${escape(s.codec)}" bandwidth="${s.bitrate}" audioSamplingRate="44100">""").append('\n')
            representationBody(sb, videoId, s)
            sb.append("      </Representation>\n")
        }
        sb.append("    </AdaptationSet>\n")
        sb.append("  </Period>\n</MPD>\n")
        return sb.toString()
    }

    private fun representationBody(sb: StringBuilder, videoId: String, s: YouTubeRepository.DashStream) {
        // The proxy, never googlevideo: the rules live there. `&amp;` because this is XML.
        sb.append("        <BaseURL>${HubKidServer.KID_PATH}/media?v=${escape(videoId)}&amp;s=${streamParam(s.itag)}</BaseURL>\n")
        sb.append("""        <SegmentBase indexRange="${s.indexStart}-${s.indexEnd}"><Initialization range="${s.initStart}-${s.initEnd}"/></SegmentBase>""").append('\n')
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
