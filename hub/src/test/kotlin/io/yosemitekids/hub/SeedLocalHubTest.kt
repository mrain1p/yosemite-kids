package io.yosemitekids.hub

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.PROFILE_COLORS
import io.yosemitekids.app.data.Pin
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Video
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.junit.Test
import java.io.File

/**
 * **A fixture builder, not a test** — and the browser's half of the emulator
 * loop.
 *
 * `CLAUDE.md` requires anything kid-facing to be verified running rather than
 * in review, and gives the app `scripts/emu.ps1` to do it with. The browser had
 * no counterpart, so the first round of the web player was proved by hand with
 * a family typed into a scratch directory that was then deleted — which meant
 * the next round had to invent one again. This is that directory, written by
 * the real serializers so what the hub reads is what a hub reads.
 *
 *     gradlew :hub:test --tests '*SeedLocalHubTest'
 *     YOSEMITE_KIDS_DATA=$PWD/hub/build/seed-hub \
 *       YOSEMITE_KIDS_PORT=8865 YOSEMITE_KIDS_KID_PORT=8866 \
 *       ./hub/build/install/hub/bin/hub
 *
 * Then open `http://127.0.0.1:8866` and type the claim code it printed.
 *
 * It asserts nothing on purpose. What it is for is the class of failure a
 * passing test cannot see: a card that clips its own channel name, a square
 * crop of a 16:9 poster, a bar that is the wrong grey. All three of those were
 * found this way and none of them failed anything.
 */
class SeedLocalHubTest {

    @Test
    fun seed() {
        // Under the module build dir, so no property has to reach the test JVM
        // and nothing outside build/ is ever written.
        val dir = File("build/seed-hub").apply { deleteRecursively(); mkdirs() }
        val now = System.currentTimeMillis()

        val store = HubStore(dir)
        val ada = "ada00001"
        val sam = "sam00002"
        val nature = WhitelistEntry("UCnature", "https://youtube.com/channel/UCnature", "Wild Wonders", SourceKind.CHANNEL)
        val making = WhitelistEntry("UCmaking", "https://youtube.com/channel/UCmaking", "Making Things", SourceKind.CHANNEL)
        val older = WhitelistEntry(
            "UColder", "https://youtube.com/channel/UColder", "Big Kid Stuff", SourceKind.CHANNEL,
            profileIds = setOf(sam)
        )

        store.edit("seed", now) {
            Whitelist(
                sources = listOf(nature, making, older),
                blockedVideoIds = emptySet(),
                homeZone = "Pacific/Auckland",
                pins = listOf(
                    Pin(kidId = ada, sourceId = "UCnature", rank = 10),
                    Pin(kidId = ada, sourceId = "UCmaking", rank = 20)
                ),
                profiles = listOf(
                    Profile(
                        id = ada, name = "Ada",
                        colorArgb = PROFILE_COLORS[3],
                        avatar = "🦊",
                        limits = Limits(sessionMinutes = 30, weekdaySessions = 2, weekendSessions = 3)
                    ),
                    // A sibling, so the per-kid visibility filter has something
                    // to actually hide on every screen.
                    Profile(id = sam, name = "Sam", colorArgb = PROFILE_COLORS[1])
                )
            )
        }

        val index = ChannelIndex(File(dir, "search-index"))
        index.addVideos("UCnature", NATURE, complete = true)
        index.addVideos("UCmaking", MAKING, complete = true)
        index.addVideos("UColder", OLDER, complete = true)

        // One part-watched and one finished, so Keep watching, the feed's
        // drop-when-finished rule, the WATCHED tag, the dim and the History
        // shelf all have something to draw on the first paint.
        val watched = HubKidHistory(dir)
        watched.save(ada, Video.watchUrl("aqz-KE-bpKQ"), 90_000, 600_000)
        watched.save(ada, Video.watchUrl("dQw4w9WgXcQ"), 210_000, 212_000)

        val code = HubBrowsers(dir).mint(ada, now)
        println("=== SEEDED ${dir.absolutePath}")
        println("=== CLAIM CODE: $code")
    }

    private companion object {
        /**
         * Real video ids, so the posters the page proxies are real images.
         * The titles are written here rather than crawled: this fixture is for
         * looking at a layout, and a crawl would need the network and a channel
         * that still exists next year.
         */
        private fun row(id: String, title: String, channel: String, seconds: Long, source: String) =
            ChannelIndex.IndexedVideo(
                id, title, channel, "https://i.ytimg.com/vi/$id/hqdefault.jpg", seconds, source
            )

        val NATURE = listOf(
            row("aqz-KE-bpKQ", "The rabbit and the three bullies", "Wild Wonders", 600, "UCnature"),
            row("dQw4w9WgXcQ", "Why do volcanoes erupt?", "Wild Wonders", 212, "UCnature"),
            row("9bZkp7q19f0", "Deep sea creatures that glow", "Wild Wonders", 253, "UCnature"),
            row("kJQP7kiw5Fk", "How bees make honey", "Wild Wonders", 281, "UCnature"),
            row("JGwWNGJdvx8", "Every dinosaur, biggest to smallest", "Wild Wonders", 264, "UCnature")
        )

        val MAKING = listOf(
            row("RgKAFK5djSk", "Build a paper aeroplane that really flies", "Making Things", 230, "UCmaking"),
            row("OPf0YbXqDm0", "A volcano you can make in the kitchen", "Making Things", 270, "UCmaking"),
            row("fJ9rUzIMcZQ", "Painting with bubbles", "Making Things", 355, "UCmaking"),
            row("CevxZvSJLk8", "The tallest tower we could build", "Making Things", 231, "UCmaking")
        )

        val OLDER = listOf(
            row("hT_nvWreIhg", "Not for Ada", "Big Kid Stuff", 245, "UColder")
        )
    }
}
