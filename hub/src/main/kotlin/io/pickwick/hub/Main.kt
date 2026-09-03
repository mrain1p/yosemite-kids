package io.pickwick.hub

import java.io.File

/**
 * The container's entry point.
 *
 * Everything it needs comes from the environment, because a compose file is
 * the only configuration surface a NAS user should have to touch:
 *
 *   PICKWICK_DATA  where config.json and devices.json live (default /data)
 *   PICKWICK_PORT  the port to listen on            (default 8765)
 *
 * 8765 matches the range the app's own LAN server uses, so the number a parent
 * sees here is the number they already half-recognise.
 */
fun main() {
    val dataDir = File(System.getenv("PICKWICK_DATA") ?: "/data")
    val port = System.getenv("PICKWICK_PORT")?.toIntOrNull() ?: 8765

    val store = HubStore(dataDir)
    val tokens = HubTokens(dataDir)
    val server = HubServer(store, tokens, port)

    val bound = server.start()
    println("Pickwick hub listening on $bound, data in ${dataDir.absolutePath}")
    println("Devices enrolled: ${tokens.devices().size}")
    if (tokens.devices().isEmpty()) {
        println("Nothing paired yet — open the hub and approve a device's code to start.")
    }

    // Compose sends SIGTERM on `down`. Without this the container is killed
    // outright nine seconds later, and a write in flight is a truncated
    // config.json — the file every device would then sync *from*.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            println("stopping")
            server.stop()
        }
    )

    // The HTTP server runs on its own threads; park the main one.
    Thread.currentThread().join()
}
