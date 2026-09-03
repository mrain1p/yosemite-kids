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
    /** The secret that may approve a device code. See HubTokens.adminToken. */
    private val adminToken: String,
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
        s.createContext("/approve") { ex -> guarded(ex) { approve(ex) } }
        s.createContext("/pending") { ex -> guarded(ex) { pending(ex) } }
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

    /**
     * A human, holding the admin secret, approving a code a device is showing.
     *
     * This is the step that makes enrolment safe: the code proves someone is
     * standing at the device, and the admin token proves they are entitled to
     * add one. Neither alone is enough.
     */
    private fun approve(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "no")
        if (!admin(ex)) return
        val body = readBody(ex) ?: return respond(ex, 413, "too large")
        val code = runCatching { JSONObject(body).optString("code") }.getOrNull()
            ?: return respond(ex, 400, "no code")

        tokens.approve(code, now()).fold(
            onSuccess = { respond(ex, 200, JSONObject().put("token", it).toString()) },
            onFailure = {
                val reason = (it as? EnrolmentRefused)?.reason
                // Named, because "that code is wrong" and "that code expired"
                // send a parent to different places.
                respond(ex, 409, JSONObject().put("refused", reason?.name ?: "UNKNOWN_CODE").toString())
            }
        )
    }

    /** What is waiting to be approved — the list a console would render. */
    private fun pending(ex: HttpExchange) {
        if (!admin(ex)) return
        val arr = org.json.JSONArray()
        tokens.pending(now()).forEach {
            arr.put(JSONObject().put("code", it.code).put("name", it.name).put("createdAt", it.createdAt))
        }
        respond(ex, 200, JSONObject().put("pending", arr).toString())
    }

    // --- plumbing -------------------------------------------------------

    /**
     * The admin gate. Compared in constant time: a token checked with ordinary
     * string equality leaks its prefix to anyone willing to time the answer,
     * and this is the secret that can add devices.
     */
    private fun admin(ex: HttpExchange): Boolean {
        val given = ex.requestHeaders.getFirst("X-Admin-Token").orEmpty()
        val expected = adminToken
        val ok = given.length == expected.length &&
            given.indices.fold(0) { acc, i -> acc or (given[i].code xor expected[i].code) } == 0
        if (!ok) respond(ex, 401, "not admin")
        return ok
    }

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
