package io.pickwick.hub

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.pickwick.app.data.SyncMeta
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * The hub's HTTP face.
 *
 * Speaks the routes a kid device already speaks — `/status`, `GET /config`,
 * `POST /config` — so from the app's side the hub is just another paired
 * device that happens never to sleep. Nothing in the app had to learn a new
 * protocol for this, which is the whole point of the design.
 *
 * The JDK's own HTTP server, with no framework: a handful of routes on a home
 * network does not justify a dependency, and every megabyte here is a
 * megabyte in an image someone pulls onto a NAS.
 */
class HubServer(
    private val store: HubStore,
    private val tokens: HubTokens,
    private val port: Int,
    /** Passed in so tests need no clock and the merge stays clock-free. */
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private var server: HttpServer? = null

    /** Bodies are bounded: this faces the LAN, and a config is small. */
    private val maxBody = 1024 * 1024

    fun start(): Int {
        val s = HttpServer.create(InetSocketAddress(port), 0)
        // A small fixed pool, like the app's LAN server. Unbounded threads on a
        // NAS is how a device that reconnects in a loop takes the box down.
        s.executor = Executors.newFixedThreadPool(4)

        s.createContext("/status") { ex -> guarded(ex) { status(ex) } }
        s.createContext("/config") { ex -> guarded(ex) { config(ex) } }
        s.createContext("/enrol") { ex -> guarded(ex) { enrol(ex) } }
        s.createContext("/health") { ex -> respond(ex, 200, "ok") }

        s.start()
        server = s
        return s.address.port
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    /** The bound port — for tests, which ask for 0 and let the OS choose. */
    fun boundPort(): Int = server?.address?.port ?: port

    // --- routes ---------------------------------------------------------

    /**
     * What this hub holds, in the shape the app's reconcile expects.
     *
     * `syncV` and `syncHash` are what mark a peer as merge-capable. Without
     * them the app treats a peer as pre-merge and push-only, so getting these
     * right is what makes the hub a participant rather than a destination.
     */
    private fun status(ex: HttpExchange) {
        if (!authorised(ex)) return
        respond(
            ex, 200,
            JSONObject()
                .put("hash", store.fingerprint())
                .put("updatedAt", store.updatedAt())
                .put("syncV", SyncMeta.VERSION)
                .put("syncHash", store.syncHash())
                .put("name", "Pickwick hub")
                .toString()
        )
    }

    private fun config(ex: HttpExchange) {
        if (!authorised(ex)) return
        when (ex.requestMethod) {
            "GET" -> {
                val raw = store.raw()
                if (raw == null) respond(ex, 404, "no config yet")
                else respond(ex, 200, raw)
            }
            "POST" -> {
                val body = readBody(ex) ?: return respond(ex, 413, "too large")
                val who = tokens.nameOf(ex.requestHeaders.getFirst("X-Token"))
                val outcome = store.merge(body, who)
                if (outcome == null) respond(ex, 400, "bad config")
                else respond(
                    ex, 200,
                    JSONObject()
                        .put("changed", outcome.changed)
                        .put("peerBehind", outcome.peerBehind)
                        .put("hash", outcome.hash)
                        .put("syncHash", outcome.syncHash)
                        .toString()
                )
            }
            else -> respond(ex, 405, "no")
        }
    }

    /**
     * A device asking to join. Unauthenticated by necessity — it has no token
     * yet — so it may do exactly one thing: mint a code for a human to approve
     * here. It reveals nothing about the family and grants nothing on its own.
     */
    private fun enrol(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "no")
        val body = readBody(ex) ?: return respond(ex, 413, "too large")
        val name = runCatching { JSONObject(body).optString("name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "A device"
        val code = tokens.startEnrolment(name.take(40), now())
        respond(ex, 200, JSONObject().put("code", code).toString())
    }

    // --- plumbing -------------------------------------------------------

    /**
     * Every route runs inside this. An exception escaping a handler leaves the
     * JDK server holding an open exchange with no response, so the caller waits
     * for its timeout and reads it as "the hub is down" rather than as a bug.
     */
    private fun guarded(ex: HttpExchange, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            System.err.println("route ${ex.requestURI} failed: ${e.message}")
            runCatching { respond(ex, 500, "error") }
        } finally {
            ex.close()
        }
    }

    private fun authorised(ex: HttpExchange): Boolean {
        val token = ex.requestHeaders.getFirst("X-Token")
        if (tokens.isEnrolled(token)) return true
        respond(ex, 401, "not paired")
        return false
    }

    /** Null when the body is over the cap — refused rather than read. */
    private fun readBody(ex: HttpExchange): String? {
        val declared = ex.requestHeaders.getFirst("Content-Length")?.toIntOrNull()
        if (declared != null && declared > maxBody) return null
        val bytes = ex.requestBody.readNBytes(maxBody + 1)
        if (bytes.size > maxBody) return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
