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
    /**
     * YOSEMITE_KIDS_ADMIN_TOKEN, or null when the compose file does not set
     * one. Deliberately the ENVIRONMENT value and not the resolved token: the
     * stored one is read through [tokens] on every call, so a password change
     * or a rotated recovery token takes effect without restarting the
     * container. Passing the resolved value here would freeze the credential
     * at boot and keep a rotated token alive for ever.
     */
    private val envAdminToken: String?,
    /** Shown on the status page so a parent can see which volume is live. */
    private val dataDir: String = System.getenv("YOSEMITE_KIDS_DATA") ?: "/data",
    /**
     * The search index this hub serves — and crawls, once it holds the
     * master slot. Beside config.json under the data dir, so the volume a
     * parent already backs up carries it.
     */
    private val index: ChannelIndex = ChannelIndex(File(dataDir, "search-index")),
    /** The election and the crawl, for the Devices page to report on. Null in tests that have neither. */
    private val master: HubMaster? = null,
    private val crawl: HubCrawl? = null,
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

    /**
     * The static half of the admin GUI: what makes it installable.
     *
     * Named here as literals and never built from a request, so no path a
     * caller sends can reach anything else under resources. Unauthenticated
     * for the same reason the page shell is — none of it carries family
     * data, and a browser fetches the manifest and the icons before any
     * session exists.
     */
    private val assets = mapOf(
        "/manifest.webmanifest" to "application/manifest+json",
        "/sw.js" to "text/javascript; charset=utf-8",
        "/icon-192.png" to "image/png",
        "/icon-512.png" to "image/png",
        "/icon-maskable-512.png" to "image/png",
        "/apple-touch-icon.png" to "image/png"
    )

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
        // Unauthenticated, and one key. A parent staring at a password box
        // for a password nobody ever set is the failure this prevents; it
        // tells a LAN peer only that this hub is unclaimed, which they still
        // cannot act on without the container log.
        s.createContext("/setup") { ex -> guarded(ex) { setup(ex) } }
        s.createContext("/password") { ex -> guarded(ex) { password(ex) } }
        s.createContext("/recovery") { ex -> guarded(ex) { recovery(ex) } }
        // The search index, for devices to pull. GET only: the hub builds
        // the index itself and takes nobody's copy — a device that could
        // POST here could truncate a source the hub had crawled further.
        // Same wire format as a device's /index-status and /index, so the
        // app's one importer reads both.
        s.createContext("/index-status") { ex -> guarded(ex) { indexStatus(ex) } }
        s.createContext("/index") { ex -> guarded(ex) { indexSource(ex) } }
        // Registered individually rather than under one prefix: a prefix
        // context would swallow every path beneath it, and "/" already
        // answers everything else with the page.
        assets.forEach { (path, type) ->
            s.createContext(path) { ex -> guarded(ex) { asset(ex, path, type) } }
        }

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
        val kind = adminGate(ex) ?: return
        // Once a password exists the recovery token signs in and changes the
        // password, but it does not enrol devices. So a leaked log line can
        // no longer quietly add a device to the family; it can only take the
        // hub over visibly, by changing the password, which the parent meets
        // at their next sign-in. That is what makes "recovery credential"
        // mean something other than "a second admin token".
        if (kind == HubTokens.Secret.RECOVERY && tokens.hasPassword()) {
            return respond(ex, 401, JSONObject().put("error", "password").toString())
        }
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
        adminGate(ex) ?: return
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

    /** One of [assets], straight from the jar. */
    private fun asset(ex: HttpExchange, path: String, type: String) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val bytes = javaClass.getResourceAsStream("/web${path}")?.readBytes()
            ?: return respond(ex, 404, "missing from this build")
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        // The worker is never cached: a browser holding yesterday's copy
        // would keep serving yesterday's shell after the container is
        // rebuilt, and the usual cure for that is uninstalling the app.
        // The icons and the manifest change about once a year.
        ex.responseHeaders.add(
            "Cache-Control",
            if (path == "/sw.js") "no-cache" else "max-age=86400"
        )
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
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
        val body = readBody(ex) ?: return respond(ex, 413, "too large")
        // "secret" is what a current page sends; "token" is what a page
        // cached by the service worker still sends. sw.js caches "/", so a
        // browser can be posting a stale shell's body shape at a rebuilt
        // container, and accepting both is cheaper than telling a parent to
        // reinstall the app.
        val json = runCatching { JSONObject(body) }.getOrNull()
        val given = json?.optString("secret")?.ifEmpty { null }
            ?: json?.optString("token")?.ifEmpty { null }
        adminGate(ex, given) ?: return
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
            "/api/state" -> respond(ex, 200, HubWeb.state(store, tokens, dataDir, now(), index, master, crawl))

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
     * The admin gate: throttle, then verify. **Every** presentation of the
     * admin secret goes through here, which is the point.
     *
     * Before this, /approve and /pending checked the header themselves with
     * no rate limit at all. Against 96 bits of hex that was harmless. Behind
     * a password it is an unmetered guessing oracle four threads wide, and
     * once a key derivation sits behind it, a processor exhaustion attack as
     * well. The throttle runs BEFORE any derivation, which is what bounds the
     * cost of guessing to a handful of derivations per window.
     *
     * Returns what the secret turned out to be, or null after answering the
     * caller. The body of a request is read before this on the routes that
     * carry the secret in one; that read is already bounded to a megabyte and
     * the expensive half is the derivation, which is behind the gate.
     */
    private fun adminGate(ex: HttpExchange, given: String?): HubTokens.Secret? {
        if (!sessions.mayAttempt()) {
            val wait = sessions.retryAfterSeconds()
            ex.responseHeaders.add("Retry-After", wait.toString())
            respond(ex, 429, JSONObject().put("retryAfter", wait).toString())
            return null
        }
        val kind = tokens.verifyAdminSecret(given, envAdminToken)
        if (kind == HubTokens.Secret.NO) {
            sessions.recordFailure()
            // Names the regime rather than the mistake, so a phone can say
            // "wrong password" or "wrong token" correctly, and so nothing
            // echoes back what was submitted.
            val regime = if (tokens.hasPassword()) "password" else "secret"
            respond(ex, 401, JSONObject().put("error", regime).toString())
            return null
        }
        return kind
    }

    /** The header form, for routes that carry no body of their own. */
    private fun adminGate(ex: HttpExchange): HubTokens.Secret? =
        adminGate(ex, ex.requestHeaders.getFirst("X-Admin-Token"))

    /** Is this hub claimed yet? One key, and a test pins that. */
    private fun setup(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        respond(ex, 200, JSONObject().put("password", tokens.hasPassword()).toString())
    }

    /**
     * Set the first password, or change it later. One route for both, which
     * is why it sits outside /api/ — on first run there is no session to have.
     *
     * `current` is required even inside a live session. That session may be a
     * browser on a kitchen counter, and the failure it prevents (someone
     * picks it up and locks the parent out of their own hub) is a household
     * failure rather than a theoretical one.
     */
    private fun password(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "no")
        if (!sameOrigin(ex)) return respond(ex, 403, "cross-site")
        val body = readBody(ex) ?: return respond(ex, 413, "too large")
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return respond(ex, 400, JSONObject().put("error", "bad request").toString())
        val current = json.optString("current").ifEmpty { null }
        val next = json.optString("next")
        adminGate(ex, current) ?: return
        tokens.setPassword(current, next, now(), envAdminToken).fold(
            onSuccess = { rotated ->
                // You change a password because you think someone else has
                // it. Every other session ends; the caller's survives.
                sessions.closeAll(except = sessionOf(ex))
                respond(
                    ex, 200,
                    JSONObject().put("ok", true).put("recovery", rotated ?: JSONObject.NULL).toString()
                )
            },
            onFailure = {
                val why = if (it is HubPassword.TooShort) "short" else "wrong"
                respond(ex, 400, JSONObject().put("error", why).toString())
            }
        )
    }

    /** A fresh recovery token on demand, behind the same gate and throttle. */
    private fun recovery(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "no")
        if (!sameOrigin(ex)) return respond(ex, 403, "cross-site")
        val body = readBody(ex) ?: return respond(ex, 413, "too large")
        val current = runCatching { JSONObject(body).optString("current") }.getOrNull()?.ifEmpty { null }
        adminGate(ex, current) ?: return
        respond(ex, 200, JSONObject().put("token", tokens.rotateRecoveryToken()).toString())
    }

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
