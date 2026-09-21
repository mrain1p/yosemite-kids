package io.yosemitekids.app

import io.yosemitekids.app.data.OkHttpDownloader
import io.yosemitekids.app.data.WhitelistParser
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Probe one problematic channel end-to-end: parse → info → tabs → videos.
 *
 * **A hand tool, and it runs nowhere on purpose.** Both gates and CI name it
 * beside [ExtractorSmokeTest] in their live-YouTube exclusion, for the same
 * reason: it calls `ChannelInfo.getInfo` with no `runCatching` and no
 * `Assume`, so a bot wall would fail a gate for something the change being
 * checked did not do. Unlike the smoke test it is also not a canary — the
 * channel id below is whichever one someone was debugging, so a red run here
 * says something about that channel and not about the extractor.
 *
 * It sits in the test source set because that is where the extractor, the
 * downloader and the parser are already wired up. Run it by hand, against
 * whatever channel is misbehaving:
 *
 *     gradlew :app:testDebugUnitTest --tests "*SingleChannelProbeTest" -i
 *
 * The `-i` is not optional: the point is the log, not the assertion.
 */
class SingleChannelProbeTest {

    @Test
    fun probe() = runBlocking<Unit> {
        val url = "https://www.youtube.com/channel/UCkd-BQZOkCVYk10rM_IUEcw"

        val parsed = WhitelistParser.parse(url)
        println("PARSED: ${parsed.sources}")
        check(parsed.sources.isNotEmpty()) { "Parser rejected the URL" }

        io.yosemitekids.app.data.Extractor.init()
        val yt = ServiceList.YouTube
        val info = ChannelInfo.getInfo(yt, parsed.sources[0].url)
        println("CHANNEL: ${info.name}")
        println("TABS: " + info.tabs.joinToString { it.contentFilters.toString() })

        info.tabs.forEach { tab ->
            runCatching {
                val t = ChannelTabInfo.getInfo(yt, tab)
                val streams = t.relatedItems.filterIsInstance<StreamInfoItem>()
                println("TAB ${tab.contentFilters}: ${t.relatedItems.size} items (${streams.size} streams)")
                streams.take(3).forEach { println("   - ${it.name}") }
            }.onFailure { println("TAB ${tab.contentFilters} FAILED: ${it.javaClass.simpleName}: ${it.message}") }
        }

        // Fallback probe: the auto-generated uploads playlist (UC → UU).
        val uploadsPlaylist = "UU" + "UCkd-BQZOkCVYk10rM_IUEcw".removePrefix("UC")
        runCatching {
            val pl = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(
                yt, "https://www.youtube.com/playlist?list=$uploadsPlaylist"
            )
            val streams = pl.relatedItems.filterIsInstance<StreamInfoItem>()
            println("UPLOADS-PLAYLIST $uploadsPlaylist: ${streams.size} streams")
            streams.take(3).forEach { println("   - ${it.name}") }
        }.onFailure { println("UPLOADS-PLAYLIST FAILED: ${it.javaClass.simpleName}: ${it.message}") }
    }
}
