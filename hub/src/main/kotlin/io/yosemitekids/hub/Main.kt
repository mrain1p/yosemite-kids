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
    // The environment value, kept separate from the resolved one. The server
    // is handed THIS, so it reads the stored token through HubTokens on every
    // call and a rotated one stops working at once; handing it the resolved
    // value would freeze the credential at boot and keep a rotated token
    // alive until someone restarted the container.
    val envAdmin = System.getenv("YOSEMITE_KIDS_ADMIN_TOKEN")?.takeIf { it.isNotBlank() }
    val admin = tokens.adminToken(envAdmin)
    // Beside config.json, on the volume a parent already backs up. Devices
    // pull it from here; HubCrawl fills it once this hub holds the slot.
    val index = io.yosemitekids.app.data.ChannelIndex(File(dataDir, "search-index"))
    // The crawl and the election. The hub takes the search index over from
    // the phone only once a device has pulled from here (HubTokens.armed)
    // and only if YouTube answers from this box (the probe); until then it
    // serves whatever it has and leaves the crawl to the phone.
    val crawl = HubCrawl.real(store, index, tokens.selfToken())
    val master = HubMaster(store, tokens, probe = HubCrawl::probeYouTube)
    val server = HubServer(store, tokens, port, envAdmin, index = index, master = master, crawl = crawl)

    val bound = server.start()
    println("Yosemite Kids hub listening on $bound, data in ${dataDir.absolutePath}")
    master.start()
    crawl.start()
    println("Devices enrolled: ${tokens.devices().size}")
    // The log is the one place a parent can reach without already holding a
    // credential, which is why this is printed at all. But a container log is
    // a broadcast with a long tail: docker logs replays it from the
    // beginning, Container Manager shows it in a web UI, and a log driver
    // ships it to a file whose permissions have nothing to do with /data.
    // A full-power credential printed on every boot is not a recovery
    // credential, it is a second front door held open — and while the hex is
    // still on screen after every restart it stays the thing people use, and
    // the password never becomes real.
    //
    // So: print it until a password exists, then stop, and name the lever.
    if (!tokens.hasPassword()) {
        println("Admin token: $admin")
        println("No password set yet. Open this hub in a browser and set one — the token above is what claims it.")
    } else if (System.getenv("YOSEMITE_KIDS_PRINT_ADMIN_TOKEN") == "1") {
        println("Admin token: $admin  (printed because YOSEMITE_KIDS_PRINT_ADMIN_TOKEN=1)")
    } else {
        println(
            "Admin token: not shown, because a password is set. " +
                "YOSEMITE_KIDS_PRINT_ADMIN_TOKEN=1 prints it for one boot — see docs/HUB.md."
        )
    }
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
            // A crawl cut mid-page loses at most that page: cursors are
            // written per page and the next run resumes from the last one.
            crawl.stop()
            master.stop()
            // Nothing in flight matters — a nudge is pure latency and the
            // devices' own reconcile covers whatever it would have said.
            nudge.stop()
        }
    )

    // The HTTP server runs on its own threads; park the main one.
    Thread.currentThread().join()
}
