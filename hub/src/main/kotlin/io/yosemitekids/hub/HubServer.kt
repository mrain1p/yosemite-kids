package io.yosemitekids.hub

import java.io.File

import io.yosemitekids.app.data.ChannelIndex

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.yosemitekids.app.data.SyncMeta
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
    /** Shown on the status page so a parent can see which volume is live. */
    private val dataDir: String = System.getenv("YOSEMITE_KIDS_DATA") ?: "/data",
    /**
     * The search index this hub serves — and crawls, once it holds the
     * master slot. Beside config.json under the data dir, so the volume a
     * parent already backs up carries it.
     */
    private val index: ChannelIndex = ChannelIndex(File(dataDir, "search-index")),
    /**
     * Passed in so tests need no clock and the merge stays clock-free.
     *
     * Last on purpose. Every existing call site passes it as a trailing
     * lambda, so a parameter added after it does not read as a compile error
     * — it silently rebinds those lambdas to the new parameter instead.
     * Anything new goes above this line.
     */
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Built here rather than injected: it needs [now], and a constructor
     * parameter defaulting to another parameter would have to sit after it,
     * which is the trailing-lambda trap described above.
     */
    private val sessions = HubSessions(now)

    private var server: HttpServer? = null

    /** Bodies are bounded: this faces the LAN, and a config is small. */
    private val maxBody = 1024 * 1024

    /** Bounded and anchored: the id names a file under the data dir. Same alphabet and length as a device's /index. */
    private val sourceId = Regex("(?:^|&)source=([A-Za-z0-9_-]{1,64})(?:&|$)")

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
        // The search index, for devices to pull. GET only: the hub builds
        // the index itself and takes nobody's copy — a device that could
        // POST here could truncate a source the hub had crawled further.
        // Same wire format as a device's /index-status and /index, so the
        // app's one importer reads both.
        s.createContext("/index-status") { ex -> guarded(ex) { indexStatus(ex) } }
        s.createContext("/index") { ex -> guarded(ex) { indexSource(ex) } }

        // The admin GUI. "/" is registered last and matches everything not
        // claimed above, so an unknown path lands on the page rather than on
        // a 404 a parent has to interpret.
        s.createContext("/login") { ex -> guarded(ex) { login(ex) } }
        s.createContext("/logout") { ex -> guarded(ex) { logout(ex) } }
        s.createContext("/api/") { ex -> guarded(ex) { api(ex) } }
        s.createContext("/") { ex -> guarded(ex) { web(ex) } }

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
                .put("name", "Yosemite Kids hub")
                // Identity, so a phone backfills PairedDevice.id with it and
                // can recognise the hub in config.masterDeviceToken; never a
                // credential. `kind` is what the settings screens badge by.
                .put("token", tokens.selfToken())
                .put("kind", "hub")
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
    private fun indexStatus(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        if (!authorised(ex)) return
        // The pull marker is what arms this hub to claim the master slot
        // (HubTokens.armed): the caller says it takes its index from here.
        if (ex.requestHeaders.getFirst("X-Index-Pull") == "1") {
            tokens.notePull(ex.requestHeaders.getFirst("X-Token"), now())
        }
        respond(ex, 200, index.statusJson())
    }

    private fun indexSource(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        if (!authorised(ex)) return
        val id = sourceId.find(ex.requestURI.rawQuery.orEmpty())?.groupValues?.get(1)
            ?: return respond(ex, 400, "bad source")
        val json = index.exportSourceWithState(id) ?: return respond(ex, 404, "unknown source")
        respond(ex, 200, json)
    }

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

    // --- the admin GUI --------------------------------------------------

    /**
     * The page itself. Served to anyone who asks, because it contains nothing:
     * every byte of family data arrives later, over /api, behind a session.
     */
    private fun web(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val html = javaClass.getResourceAsStream("/web/index.html")?.readBytes()
            ?: return respond(ex, 500, "the GUI is missing from this build")
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        // The cost of these is nothing and the alternative is arguing about
        // it later. A LAN page is still a page in a browser.
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.add("X-Frame-Options", "DENY")
        ex.responseHeaders.add("Referrer-Policy", "no-referrer")
        ex.sendResponseHeaders(200, html.size.toLong())
        ex.responseBody.use { it.write(html) }
    }

    /**
     * Trade the admin token for a session cookie.
     *
     * This route is what makes the admin token guessable: before the GUI it
     * could only be presented programmatically, one call at a time. Hence the
     * throttle, which refuses rather than slows — a parent mistyping it twice
     * is unaffected, and anything trying thousands is stopped rather than
     * merely inconvenienced.
     */
    private fun login(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "no")
        if (!sameOrigin(ex)) return respond(ex, 403, "cross-site")
        if (!sessions.mayAttempt()) {
            val wait = sessions.retryAfterSeconds()
            ex.responseHeaders.add("Retry-After", wait.toString())
            return respond(ex, 429, JSONObject().put("retryAfter", wait).toString())
        }
        val body = readBody(ex) ?: return respond(ex, 413, "too large")
        val given = runCatching { JSONObject(body).optString("token") }.getOrNull().orEmpty()
        if (!constantTimeEquals(given, adminToken)) {
            sessions.recordFailure()
            return respond(ex, 401, JSONObject().put("error", "wrong token").toString())
        }
        val id = sessions.open()
        // No Secure flag: this is plain HTTP on a home LAN, and marking the
        // cookie Secure would stop it being sent at all. HttpOnly and
        // SameSite are the two that do work here.
        ex.responseHeaders.add(
            "Set-Cookie",
            "$SESSION_COOKIE=$id; HttpOnly; SameSite=Strict; Path=/; Max-Age=" +
                (HubSessions.SESSION_TTL_MS / 1000)
        )
        respond(ex, 200, JSONObject().put("ok", true).toString())
    }

    private fun logout(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "no")
        sessions.close(sessionOf(ex))
        ex.responseHeaders.add(
            "Set-Cookie",
            "$SESSION_COOKIE=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0"
        )
        respond(ex, 200, JSONObject().put("ok", true).toString())
    }

    private fun api(ex: HttpExchange) {
        if (!sameOrigin(ex)) return respond(ex, 403, "cross-site")
        if (!sessions.valid(sessionOf(ex))) {
            return respond(ex, 401, JSONObject().put("error", "sign in").toString())
        }

        when (ex.requestURI.path) {
            "/api/state" -> respond(ex, 200, HubWeb.state(store, tokens, dataDir, now()))

            "/api/channels" -> mutate(ex) { body ->
                when {
                    body.has("add") -> JSONObject()
                        .put("added", HubWeb.addChannels(store, WHO, now(), body.getString("add")))
                    body.has("remove") -> JSONObject()
                        .put("removed", HubWeb.removeChannel(store, WHO, now(), body.getString("remove")))
                    body.has("unblock") -> JSONObject()
                        .put("unblocked", HubWeb.unblock(store, WHO, now(), body.getString("unblock")))
                    body.has("edit") -> {
                        val e = body.getJSONObject("edit")
                        JSONObject().put(
                            "edited",
                            HubWeb.editChannel(
                                store, WHO, now(), e.getString("id"),
                                if (e.has("multiplier")) e.getInt("multiplier") else null,
                                if (e.has("note")) e.getString("note") else null,
                                if (e.has("kids")) {
                                    e.getJSONArray("kids").let { a ->
                                        (0 until a.length()).map { a.getString(it) }.toSet()
                                    }
                                } else null
                            )
                        )
                    }
                    else -> null
                }
            }

            // One patch route for every plain config field, rather than a
            // route per control. A route per control is a route per control
            // to forget when the phone grows one.
            "/api/config" -> mutate(ex) { body ->
                if (HubWeb.applyPatch(store, WHO, now(), body)) JSONObject().put("saved", true)
                else null
            }

            "/api/versions" -> mutate(ex) { body ->
                if (!body.has("restore")) null
                else JSONObject().put(
                    "restored",
                    HubVersions.restore(store, WHO, now(), body.getString("restore"))
                )
            }

            "/api/devices" -> mutate(ex) { body ->
                when {
                    body.has("approve") -> tokens.approve(body.getString("approve"), now()).fold(
                        // The token goes to the device showing the code, never
                        // to this page: the page is administering, not joining.
                        onSuccess = { JSONObject().put("approved", true) },
                        onFailure = {
                            JSONObject().put("approved", false).put(
                                "refused",
                                (it as? EnrolmentRefused)?.reason?.name ?: "UNKNOWN_CODE"
                            )
                        }
                    )
                    body.has("revoke") -> JSONObject()
                        .put("revoked", HubWeb.revokeDevice(tokens, body.getString("revoke")))
                    body.has("assign") -> {
                        val a = body.getJSONObject("assign")
                        JSONObject().put(
                            "assigned",
                            HubWeb.assignDevice(
                                store, tokens, WHO, now(),
                                a.getString("ref"), a.optString("kid")
                            )
                        )
                    }
                    else -> null
                }
            }

            else -> respond(ex, 404, JSONObject().put("error", "no such route").toString())
        }
    }

    /** POST-only and body-bounded. A null result from [block] is a 400. */
    private fun mutate(ex: HttpExchange, block: (JSONObject) -> JSONObject?) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "no")
        val body = readBody(ex) ?: return respond(ex, 413, "too large")
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return respond(ex, 400, JSONObject().put("error", "bad request").toString())
        val result = block(json)
            ?: return respond(ex, 400, JSONObject().put("error", "nothing to do").toString())
        respond(ex, 200, result.toString())
    }

    private fun sessionOf(ex: HttpExchange): String? =
        ex.requestHeaders.getFirst("Cookie")
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$SESSION_COOKIE=") }
            ?.substringAfter("=")

    /**
     * Refuse anything a browser on another site initiated.
     *
     * SameSite=Strict already stops the cookie riding along, but a page that
     * checks only the cookie is trusting the browser to have enforced that.
     * The same reasoning as /pair-request in the app, which refuses any
     * request carrying an Origin at all.
     */
    private fun sameOrigin(ex: HttpExchange): Boolean {
        val origin = ex.requestHeaders.getFirst("Origin") ?: return true
        val host = ex.requestHeaders.getFirst("Host") ?: return false
        return origin.substringAfter("://") == host
    }

    // --- plumbing -------------------------------------------------------

    /**
     * The admin gate. Compared in constant time: a token checked with ordinary
     * string equality leaks its prefix to anyone willing to time the answer,
     * and this is the secret that can add devices.
     */
    private fun admin(ex: HttpExchange): Boolean {
        val ok = constantTimeEquals(
            ex.requestHeaders.getFirst("X-Admin-Token").orEmpty(), adminToken
        )
        if (!ok) respond(ex, 401, "not admin")
        return ok
    }

    /** Shared with the login route, which compares the same secret. */
    private fun constantTimeEquals(given: String, expected: String): Boolean =
        given.length == expected.length &&
            given.indices.fold(0) { acc, i -> acc or (given[i].code xor expected[i].code) } == 0

    private companion object {
        const val SESSION_COOKIE = "yk_session"

        /** How a hub edit is attributed in the change feed a parent reads. */
        const val WHO = "The hub"
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
        if (tokens.isEnrolled(token)) {
            // Learn where to call this device back. Here because it is the one
            // gate every device call passes, and only for calls that proved a
            // token — an unauthenticated caller must not be able to move an
            // enrolled device's address and collect its nudges.
            tokens.noteSeen(
                token,
                ex.remoteAddress?.address?.hostAddress,
                ex.requestHeaders.getFirst("X-Device-Port")?.toIntOrNull() ?: 0,
                now()
            )
            return true
        }
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
