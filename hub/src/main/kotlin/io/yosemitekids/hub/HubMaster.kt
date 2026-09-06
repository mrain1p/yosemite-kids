package io.yosemitekids.hub

import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.MasterElection
import io.yosemitekids.app.data.MasterElection.Decision
import io.yosemitekids.app.data.Whitelist
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The hub's side of the master election: one tick on a schedule, the
 * decision itself in :core (MasterElection), and the clock read here and
 * only here on the hub, so the rules stay a pure function a test can drive.
 *
 * Probe before claim. A hub that cannot reach YouTube must never take the
 * slot: the phone that held it would stop crawling and nobody would start,
 * and the family would notice as search going stale weeks later. The probe
 * is HubCrawl's, through the same allow-listed client the crawl uses.
 */
class HubMaster(
    private val store: HubStore,
    private val tokens: HubTokens,
    private val probe: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        /**
         * The device worker's period. Kept in step so the hub's heartbeat is
         * never later than a phone's would have been, and a claim follows a
         * device's first pull within the quarter hour.
         */
        const val TICK_MS = 15 * 60 * 1000L
    }

    val me: String get() = tokens.selfToken()

    private fun config(): Whitelist =
        runCatching { store.load() }.getOrElse { Whitelist(emptyList(), emptySet()) }

    fun isMaster(): Boolean = config().masterDeviceToken == me

    /** What the last tick concluded, in words, for the GUI's Devices page. */
    @Volatile
    var last: String = "not yet run"
        private set

    /** One tick. Returns what the election said, after the probe had its say. */
    fun tick(): Decision {
        val t = now()
        val config = config()
        val armed = tokens.armed(t)
        val decision = MasterElection.decide(config, me, isHub = true, now = t, armed = armed)
        when (decision) {
            Decision.CLAIM -> {
                if (!probe()) {
                    last = "would build the search index, but YouTube is unreachable from this hub"
                    println(last)
                    return Decision.NOTHING
                }
                // The refresh is redundant with the token change but harmless,
                // and it makes the claim and the heartbeat the same write.
                store.edit("hub", t, refresh = setOf(ConfigStamp.MASTER)) { it.copy(masterDeviceToken = me) }
                last = "this hub builds the search index"
                println("claimed the search index: a device pulled from this hub within the last day")
            }
            Decision.HEARTBEAT -> {
                // No value changes; only the stamp moves. That is the whole
                // liveness protocol (MasterElection), and the one thing the
                // stamper does only when asked by name.
                store.edit("hub", t, refresh = setOf(ConfigStamp.MASTER)) { it }
                last = "this hub builds the search index"
            }
            Decision.NOTHING -> last = when {
                config.masterDeviceToken == me && !armed ->
                    "this hub built the index, but no device has pulled it for a day; letting a phone take over"
                config.masterDeviceToken == me -> "this hub builds the search index"
                !armed -> "idle: no device has pulled the index from this hub in the last day"
                else -> "idle: another peer builds the search index"
            }
        }
        return decision
    }

    private val ticker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "hub-master").apply { isDaemon = true }
    }

    fun start() {
        ticker.scheduleWithFixedDelay(
            {
                runCatching { tick() }.onFailure { System.err.println("master tick failed: ${it.message}") }
            },
            0, TICK_MS, TimeUnit.MILLISECONDS
        )
    }

    fun stop() = ticker.shutdownNow()
}
