package io.yosemitekids.hub

import java.io.File
import kotlin.system.exitProcess

/**
 * The container's entry point.
 *
 * Everything it needs comes from the environment, because a compose file is
 * the only configuration surface a NAS user should have to touch:
 *
 *   YOSEMITE_KIDS_DATA  where config.json and devices.json live (default /data)
 *   YOSEMITE_KIDS_PORT  the port to listen on            (default 8765)
 *
 * 8765 matches the range the app's own LAN server uses, so the number a parent
 * sees here is the number they already half-recognise.
 */
fun main() {
    val dataDir = File(System.getenv("YOSEMITE_KIDS_DATA") ?: "/data")
    val port = System.getenv("YOSEMITE_KIDS_PORT")?.toIntOrNull() ?: 8765

    // Before anything opens a file. An unwritable volume is the single most
    // likely way this container fails on a new machine, and discovering it
    // through the first write means the log says FileOutputStream.open0
    // instead of what is wrong — repeated forever by the restart policy,
    // never once printing the admin token underneath.
    dataDirProblem(dataDir)?.let {
        System.err.println(it)
        exitProcess(1)
    }

    // Before any fetch: this container reaches YouTube and the devices'
    // /sync-now, nothing else, and the shared client enforces the first half
    // (HubNudge is the second). Guard 7 checks this line is here.
    io.yosemitekids.app.data.Http.restrictTo(io.yosemitekids.app.data.Http.HUB_HOSTS)
    io.yosemitekids.app.data.Extractor.init()

    val tokens = HubTokens(dataDir)
    // The one thing this hub ever initiates: "my copy moved, come and look."
    // Not the config itself — see HubNudge for why that limit is deliberate.
    val nudge = HubNudge(tokens)
    val store = HubStore(dataDir, onChanged = nudge::changed)
    val admin = tokens.adminToken(System.getenv("YOSEMITE_KIDS_ADMIN_TOKEN"))
    // Beside config.json, on the volume a parent already backs up. Devices
    // pull it from here; HubCrawl fills it once this hub holds the slot.
    val index = io.yosemitekids.app.data.ChannelIndex(File(dataDir, "search-index"))
    val server = HubServer(store, tokens, port, admin, index = index)

    val bound = server.start()
    println("Yosemite Kids hub listening on $bound, data in ${dataDir.absolutePath}")
    println("Devices enrolled: ${tokens.devices().size}")
    // Printed because the container log is the one place a parent can reach
    // without already holding a credential. Set YOSEMITE_KIDS_ADMIN_TOKEN in the
    // compose file to pin it instead of reading it from here each time.
    println("Admin token: $admin")
    if (tokens.devices().isEmpty()) {
        println("Nothing paired yet. Approve a device code to pair the first one.")
    }

    // Compose sends SIGTERM on `down`. Without this the container is killed
    // outright nine seconds later, and a write in flight is a truncated
    // config.json — the file every device would then sync *from*.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            println("stopping")
            server.stop()
            // Nothing in flight matters — a nudge is pure latency and the
            // devices' own reconcile covers whatever it would have said.
            nudge.stop()
        }
    )

    // The HTTP server runs on its own threads; park the main one.
    Thread.currentThread().join()
}
