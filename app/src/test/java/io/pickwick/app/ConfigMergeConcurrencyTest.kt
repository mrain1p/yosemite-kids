package io.pickwick.app

import io.pickwick.app.data.ConfigMerge
import io.pickwick.app.data.ConfigStamp
import io.pickwick.app.data.ConfigStore
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.SyncMeta
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Two pushes landing at once.
 *
 * A merge is a read-modify-write, and pushes arrive on a pool of up to eight
 * LAN worker threads. If the read and the write are not one locked step, two
 * of them interleave and one side is lost — which is precisely the bug the
 * merge exists to fix, reintroduced one layer down and much harder to see.
 *
 * Run repeatedly and started from a latch, because a race that only shows up
 * one time in twenty is still a family losing a channel.
 */
class ConfigMergeConcurrencyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val T = 1_780_000_000_000L
    private val ROUNDS = 50

    private fun entry(id: String) = WhitelistEntry(
        id = id,
        url = "https://www.youtube.com/channel/$id",
        label = id,
        kind = SourceKind.CHANNEL
    )

    private fun doc(
        sources: List<WhitelistEntry>,
        at: Map<String, Long>,
        gone: Map<String, Long> = emptyMap()
    ): String = ConfigStore.toJson(
        Whitelist(
            sources = sources,
            blockedVideoIds = emptySet(),
            sync = SyncMeta(docAt = (at.values + gone.values + 0L).max(), at = at, gone = gone)
        )
    )

    /** Two threads, released together, each pushing a different channel. */
    private fun raceTwoPushes(round: Int): Set<String> {
        val store = ConfigStore(File(tmp.newFolder("r$round"), "config.json"))
        store.saveRaw(doc(listOf(entry("UCbase")), mapOf(ConfigStamp.src("UCbase") to T)))

        val dad = doc(
            listOf(entry("UCbase"), entry("UCdad")),
            mapOf(ConfigStamp.src("UCbase") to T, ConfigStamp.src("UCdad") to T + 1)
        )
        val mum = doc(
            listOf(entry("UCbase"), entry("UCmum")),
            mapOf(ConfigStamp.src("UCbase") to T, ConfigStamp.src("UCmum") to T + 2)
        )

        val go = CountDownLatch(1)
        val done = CountDownLatch(2)
        listOf(dad, mum).forEach { body ->
            Thread {
                go.await()
                store.mergeIncoming(body)
                done.countDown()
            }.start()
        }
        go.countDown()
        done.await()
        return store.load().sources.map { it.id }.toSet()
    }

    @Test
    fun twoThreadsLandingDisjointPushesLoseNothing() {
        repeat(ROUNDS) { round ->
            assertEquals(
                "round $round lost a co-parent's channel",
                setOf("UCbase", "UCdad", "UCmum"),
                raceTwoPushes(round)
            )
        }
    }

    @Test
    fun aTombstoneSurvivesAConcurrentPushThatStillListsTheChannel() {
        // The nastiest ordering: one push carries the deletion, the other is a
        // stale copy that still lists the channel. Whichever lands first, the
        // channel must be gone at the end — a parent removed it.
        repeat(ROUNDS) { round ->
            val store = ConfigStore(File(tmp.newFolder("t$round"), "config.json"))
            store.saveRaw(
                doc(listOf(entry("UCdoomed")), mapOf(ConfigStamp.src("UCdoomed") to T))
            )

            val deletion = doc(emptyList(), emptyMap(), mapOf(ConfigStamp.src("UCdoomed") to T + 5))
            val staleCopy = doc(listOf(entry("UCdoomed")), mapOf(ConfigStamp.src("UCdoomed") to T))

            val go = CountDownLatch(1)
            val done = CountDownLatch(2)
            listOf(deletion, staleCopy).forEach { body ->
                Thread {
                    go.await()
                    store.mergeIncoming(body)
                    done.countDown()
                }.start()
            }
            go.countDown()
            done.await()

            val out = store.load()
            assertTrue(
                "round $round resurrected a deleted channel",
                out.sources.none { it.id == "UCdoomed" }
            )
            assertEquals(T + 5, out.sync.gone[ConfigStamp.src("UCdoomed")])
        }
    }

    @Test
    fun aLockedUpdateRacingAPushLosesNeitherSide() {
        // `update {}` is the primitive the small writers use — the master
        // claim, adopting a kid's restyle. Feeding `save` a config read
        // minutes earlier is what this replaces: under the stamper a
        // merged-in channel then looks like a fresh add, which clears its
        // tombstone and brings back something a parent deleted.
        repeat(ROUNDS) { round ->
            val store = ConfigStore(File(tmp.newFolder("u$round"), "config.json"))
            store.saveRaw(doc(listOf(entry("UCbase")), mapOf(ConfigStamp.src("UCbase") to T)))

            val push = doc(
                listOf(entry("UCbase"), entry("UCpeer")),
                mapOf(ConfigStamp.src("UCbase") to T, ConfigStamp.src("UCpeer") to T + 1)
            )

            val go = CountDownLatch(1)
            val done = CountDownLatch(2)
            Thread { go.await(); store.mergeIncoming(push); done.countDown() }.start()
            Thread {
                go.await()
                store.update { it.copy(masterDeviceToken = "abcd") }
                done.countDown()
            }.start()
            go.countDown()
            done.await()

            val out = store.load()
            assertEquals("round $round lost the master claim", "abcd", out.masterDeviceToken)
            assertTrue(
                "round $round lost the peer's channel",
                out.sources.any { it.id == "UCpeer" }
            )
            assertFalse(
                "round $round tombstoned a channel nobody deleted",
                out.sync.gone.containsKey(ConfigStamp.src("UCpeer"))
            )
        }
    }

    @Test
    fun noConfigOnDiskEverContainsAnApiKey() {
        val store = ConfigStore(File(tmp.newFolder("k"), "config.json"))
        val withKey = ConfigStore.toJson(
            Whitelist(
                sources = listOf(entry("UCaaa")),
                blockedVideoIds = emptySet(),
                ai = io.pickwick.app.data.AiConfig(model = "m", apiKey = "sk-must-not-land"),
                sync = SyncMeta(at = mapOf(ConfigStamp.src("UCaaa") to T))
            )
        )
        store.mergeIncoming(withKey)

        val onDisk = File(tmp.root, "k/config.json").readText()
        assertFalse("a credential must never reach the file", onDisk.contains("sk-must-not-land"))
        assertTrue("but the config it arrived with must", onDisk.contains("UCaaa"))
    }

    @Test
    fun aPushOfWhatWeAlreadyHoldWritesNothing() {
        val store = ConfigStore(File(tmp.newFolder("n"), "config.json"))
        val body = doc(listOf(entry("UCaaa")), mapOf(ConfigStamp.src("UCaaa") to T))
        store.saveRaw(body)
        val stampBefore = File(tmp.root, "n/config.json").readText()

        val outcome = store.mergeIncoming(body)
        assertFalse("the reconcile re-pushes constantly; a no-op must stay a no-op", outcome!!.changed)
        assertEquals(stampBefore, File(tmp.root, "n/config.json").readText())
        assertTrue(ConfigMerge.merge(body, body).merged == null)
    }
}
