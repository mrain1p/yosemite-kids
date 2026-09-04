package io.pickwick.hub

import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The hub telling its devices that its copy moved.
 *
 * Until this existed the hub had no outbound anything: every one of its routes
 * was inbound, and an edit made in its own admin GUI was written to a file and
 * announced to nobody. A phone's edit reaches a TV in about a second
 * (`Settings.pushAll`, plus two retries); the same edit made on the hub waited
 * up to five minutes on an awake device and until next launch on a sleeping
 * one. That gap stopped being cosmetic when the hub grew all six settings
 * pages, and it is disqualifying for a parent whose only admin surface is the
 * hub — an iPhone, which cannot sideload the app.
 *
 * **It sends a nudge, not the config.** That is a deliberate limit, not a
 * shortcut. Pushing config would require a credential the device accepts, so a
 * device would have to mint an admin token for the hub — and the hub is the
 * component most exposed by design, the one on a NAS, the one intended to be
 * reachable from outside one day. It must not be able to command devices. So
 * it may only say "come and look", and the device then pulls, merges and
 * authenticates exactly as it does on its own timer. The direction of trust is
 * unchanged: devices authenticate to the hub, never the reverse.
 *
 * The nudge is therefore *pure latency*. Losing one costs nothing — the
 * device's own reconcile still catches the change on its next tick.
 */
class HubNudge(
    private val tokens: HubTokens,
    /** Swapped in tests; real sends go over HTTP. */
    private val send: (String, Int) -> Boolean = ::post
) {

    // One thread. Nudging is a background courtesy and must never compete with
    // serving, which runs on a fixed pool of four.
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hub-nudge").apply { isDaemon = true }
    }

    // Coalesces a burst. Removing three channels in the GUI is three commits;
    // they should cost one round of nudges, not three. A request arriving while
    // a round is in flight sets this, and the running round repeats once.
    private val pending = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    /** Something changed. Tell everyone we know how to reach, soon, off-thread. */
    fun changed() {
        pending.set(true)
        if (!running.compareAndSet(false, true)) return
        worker.execute {
            try {
                // `while` rather than one pass: an edit landing between the
                // drain and the sends would otherwise be swallowed by the
                // coalescing it was supposed to benefit from.
                while (pending.compareAndSet(true, false)) round()
            } finally {
                running.set(false)
                // A change that arrived while we were standing down still has
                // to wake someone, or it waits for the next edit.
                if (pending.get() && running.compareAndSet(false, true)) {
                    worker.execute {
                        try { while (pending.compareAndSet(true, false)) round() }
                        finally { running.set(false) }
                    }
                }
            }
        }
    }

    private fun round() {
        tokens.devices().forEach { device ->
            val host = device.host ?: return@forEach
            if (device.port !in 1..65535) return@forEach
            runCatching { send(host, device.port) }
        }
    }

    fun stop() = worker.shutdownNow()

    companion object {
        /** Short: a device on this LAN answers in milliseconds or is asleep. */
        internal const val TIMEOUT_MS = 1500

        private fun post(host: String, port: Int): Boolean = runCatching {
            // No library: the hub has zero dependencies beyond the JDK and org.json,
            // and one POST with no body is not worth changing that for.
            val c = URI("http://$host:$port/sync-now").toURL()
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.doOutput = true
            // The device refuses anything that is not JSON, so that a page in a
            // browser cannot post here without a preflight. An empty object is
            // the smallest thing that satisfies it — the route reads no body.
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write("{}".toByteArray()) }
            val ok = c.responseCode in 200..299
            c.disconnect()
            ok
        }.getOrDefault(false)
    }
}
