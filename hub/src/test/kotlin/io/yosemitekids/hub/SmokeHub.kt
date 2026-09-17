package io.yosemitekids.hub

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.KidPassword
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.SyncMeta
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import java.io.File

/**
 * A hub with a family in it, started in this JVM for the browser smoke test.
 *
 * ### Why not the real entry point
 *
 * `Main` wires the master election and the crawl, and both reach YouTube. A
 * check that runs on every push must not, so this builds the same [HubServer]
 * with those two left null — which is what the constructor's own defaults are
 * for — and seeds a family through the real serializers. A page driven against
 * it is driven against the hub the family runs, minus the two things that talk
 * to the internet.
 *
 * It is in the TEST source set deliberately: nothing here ships in the image,
 * and no environment variable exists in production that could start a hub with
 * its rules half-wired.
 *
 * ### What it seeds, and why each piece
 *
 * Two children, because a catalogue with nothing hidden from anybody proves no
 * filter. One channel is Sam's alone, so a request for its video as Ada is the
 * smoke test's proof that the play gate runs **before** anything is resolved:
 * the answer must be a 403 with a policy reason, and a 502 would mean the hub
 * went looking for a stream. Ada has a web password, so the sign-in the test
 * drives is the one a family uses. A home timezone, because without one the
 * hub fails closed for any kid with a time rule.
 *
 * Prints one line the runner parses:
 *
 *     SMOKE port=<n> kid=<id> password=<word> hidden=<videoId>
 */
object SmokeHub {

    private const val ADMIN = "smoke-admin-token"
    private const val KID_ADA = "ada00001"
    private const val KID_SAM = "sam00001"
    private const val ADA_PASSWORD = "otter"
    private const val HIDDEN_VIDEO = "sam0000000a"

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.getOrNull(0) ?: "build/smoke-hub").apply {
            deleteRecursively()
            mkdirs()
        }
        val port = args.getOrNull(1)?.toIntOrNull() ?: 0
        // Fixed, so a run at 23:59 is the same run as one at noon.
        val now = 1_780_000_000_000L

        val store = HubStore(dir)
        val index = ChannelIndex(File(dir, "search-index"))
        seedFamily(store, now)
        seedIndex(index)

        // A real clock, not the seeded `now`: the countdown is the one thing
        // here that only means something if time passes. The seeded document
        // keeps its fixed stamps, so what a page DRAWS stays deterministic.
        val server = HubServer(store, HubTokens(dir), port, ADMIN, index = index)
        val bound = server.start()
        println("SMOKE port=$bound kid=$KID_ADA password=$ADA_PASSWORD hidden=$HIDDEN_VIDEO")
        System.out.flush()
        // The runner kills this process when the browser is done with it.
        Thread.currentThread().join()
    }

    private fun seedFamily(store: HubStore, now: Long) {
        val shared = WhitelistEntry(
            "UCshared", "https://www.youtube.com/channel/UCshared", "Nature", SourceKind.CHANNEL
        )
        val samOnly = WhitelistEntry(
            "UCsam", "https://www.youtube.com/channel/UCsam", "Older", SourceKind.CHANNEL,
            profileIds = setOf(KID_SAM)
        )
        val config = Whitelist(
            sources = listOf(shared, samOnly),
            blockedVideoIds = emptySet(),
            profiles = listOf(
                Profile(
                    id = KID_ADA, name = "Ada", avatar = "🦦",
                    webPassword = KidPassword.record(ADA_PASSWORD, now),
                    // A budget, so the countdown is something the smoke test can
                    // see: a pill that never renders is a pill never checked.
                    limits = Limits(sessionMinutes = 15, weekdaySessions = 2, weekendSessions = 2)
                ),
                Profile(id = KID_SAM, name = "Sam", avatar = "🦊")
            ),
            // Every kid route that touches the clock needs this; without it the
            // hub refuses to play anything for a kid with a time rule.
            homeZone = "Pacific/Auckland",
            sync = SyncMeta(docAt = now)
        )
        checkNotNull(store.merge(ConfigJson.toJson(config), "smoke")) {
            "the seeded family did not survive the hub's own merge"
        }
    }

    private fun seedIndex(index: ChannelIndex) {
        fun video(source: String, id: String, title: String, seconds: Long) =
            ChannelIndex.IndexedVideo(
                videoId = id, title = title, channelName = source,
                thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                durationSeconds = seconds, sourceId = source,
                publishedAt = 1_779_000_000_000L
            )
        index.addVideos(
            "UCshared",
            listOf(
                video("UCshared", "share00000a", "Otters at the river", 320),
                video("UCshared", "share00000b", "How a nest is built", 540),
                video("UCshared", "share00000c", "Counting to ten", 180)
            ),
            complete = true
        )
        index.setArt("UCshared", avatarUrl = null, bannerUrl = null)
        index.addVideos(
            "UCsam",
            listOf(video("UCsam", HIDDEN_VIDEO, "Only Sam may watch this", 400)),
            complete = true
        )
    }
}
