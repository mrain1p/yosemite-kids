package io.yosemitekids.hub

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The kid's own origin: a second listener, on a port of its own, serving the
 * page a child watches on and nothing else.
 *
 * ### Why a port and not a path
 *
 * This is the load-bearing decision in the whole web player and it must not be
 * softened back into path-scoping later, so the argument is written down here
 * rather than in a commit message.
 *
 * Put the kid's page at `/kid/` on the admin origin and a script on it can
 * `fetch("/api/config", {method: "POST", …})` — and that request passes every
 * gate this project has:
 *
 * - [HubServer.sameOrigin] compares the `Origin` host to the `Host` header,
 *   and on one origin they match;
 * - the parent's session cookie rides along, because cookie `Path` is matched
 *   against the **request URI**, not against the page that made the request;
 * - `SameSite=Strict` is satisfied, because it genuinely is the same site;
 * - `HttpOnly` is irrelevant — the page never reads the cookie, it only sends
 *   it.
 *
 * On a shared family iPad with a parent signed in, that is a child's page
 * holding a rewrite of the family's whole configuration: `HubWeb.PATCHABLE`
 * covers `blocked`, `blockedFor`, `allowedFor`, `limits` and `ai`, and
 * `/api/grant` mints bonus minutes.
 *
 * A second port makes the two genuinely different origins, and every one of
 * those gates starts working *for* us instead of against us. A fetch from the
 * kid page to the admin port carries a foreign `Origin:` header, so
 * `sameOrigin()` is false and the existing 403 fires — a defence that already
 * existed and was already tested. Reading a reply is separately impossible,
 * because **nothing here ever sends a CORS header** (guard 58): the browser's
 * own same-origin policy hides the response body even where a request is not
 * refused.
 *
 * ### What it serves
 *
 * Exactly five paths, listed in [start] and pinned by guard 57. There is no
 * catch-all page: `/` answers the kid page and every other path is a JSON 404,
 * where the admin listener deliberately answers an unclaimed path with the
 * console. That asymmetry is the point — on this origin an unknown path is
 * never something a parent mistyped, and answering `/login` or `/api/state`
 * with anything but a refusal would be the first crack in the wall this class
 * is.
 *
 * ### Bounded, like everything else facing the LAN
 *
 * This listener faces the whole house before any credential is checked, so it
 * reads the same way `LanServer` does: a fixed worker pool, a request timeout,
 * a body cap far below the admin's (nothing here posts more than a code), and
 * a media pool that is separate again because a stream holds its thread for
 * the length of a video.
 */
class HubKidServer(
    private val port: Int,
    private val browsers: HubBrowsers,
    private val store: HubStore,
    /**
     * The one answer to "may this child play this video", shared with the box
     * that holds the family's config — never a second copy of the rules. See
     * [HubPolicy]'s own KDoc and guard 46.
     */
    private val policy: HubPolicy,
    /** Minutes for a viewer with no `SessionGuard`. See [HubWatchMeter]. */
    private val meter: HubWatchMeter,
    /** Passed in so tests need no clock, like every other class here. */
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Resolving a video to one playable URL, and carrying its bytes. Built
     * here rather than shared with [HubServer]: this is the only listener that
     * plays anything, and the cache inside it is a cache of things a child
     * asked for.
     */
    private val streams = HubStream(now = now)

    /**
     * The claim throttle, and **the reason it is here and not in
     * [HubSessions]**.
     *
     * With one shared counter a child mistyping a code ten times locks their
     * parent out of the admin console for fifteen minutes, then thirty, then
     * an hour — a throttle that causes exactly the failure it exists to
     * prevent, triggered by a six-year-old with a keyboard. So the kid origin
     * counts its own attempts, in its own bucket, and nothing here can reach
     * `HubSessions.recordFailure` at all (guard 59).
     *
     * [HubRate] rather than [HubSessions]' shape because the two questions are
     * different: the admin gate escalates, because behind it is a password a
     * parent chose and a grind is worth answering with a longer sentence. This
     * one is protecting a code that expires in ten minutes and burns after
     * five wrong guesses, so a plain sliding window is the whole of it — and a
     * window that forgets after a minute is what keeps a mistyping child from
     * being told to come back after tea.
     */
    private val claims = HubRate(MAX_CLAIMS_PER_WINDOW, CLAIM_WINDOW_MS)

    private var server: HttpServer? = null

    /**
     * Media runs on threads of its own, never on the pair this listener
     * answers everything else with. Same argument as the admin server's, one
     * origin along: a proxied stream holds its thread for as long as the
     * browser keeps reading, so two children watching would leave nothing to
     * answer `/claim` or `/whoami` with.
     */
    private var mediaPool: ExecutorService? = null
    private val slots = HubMedia.Slots(MAX_CONCURRENT_STREAMS)

    /**
     * The stream counter, so a test can fill it and ask over a socket what a
     * fourth child gets. Proving the 503 any other way means three real videos
     * and a network.
     */
    internal fun mediaSlots(): HubMedia.Slots = slots

    /** Small. Nothing a child's browser posts here is bigger than a code. */
    private val maxBody = 4 * 1024

    fun start(): Int {
        HubServer.applyRequestTimeout()
        val s = HttpServer.create(InetSocketAddress(port), 0)
        // Two, not four: this origin has three control-plane routes and the
        // expensive one has a pool of its own.
        s.executor = Executors.newFixedThreadPool(2)

        // Trade a code for a cookie. Unauthenticated by necessity — a browser
        // that has never been here holds nothing to present — and throttled in
        // its own bucket for exactly that reason.
        s.createContext("/claim") { ex -> guarded(ex) { claim(ex) } }
        // Who this browser is watching as. The page's first call, and what it
        // renders the code box from when the answer is 401.
        s.createContext("/whoami") { ex -> guarded(ex) { whoami(ex) } }
        // The kid palette and type scale, generated at build time from :core's
        // one table (guard 48). Also served by the admin listener; it is the
        // single path both origins answer, because it carries no family data
        // and a stylesheet is not a route about anybody.
        s.createContext("/kid-tokens.css") { ex -> guarded(ex) { asset(ex) } }
        // Video bytes. Handed to another pool, so this thread goes straight
        // back to answering the rest of the origin. See [dispatchMedia].
        val media = Executors.newFixedThreadPool(MAX_CONCURRENT_STREAMS) { r ->
            Thread(r, "yosemite-kids-kid-media").apply { isDaemon = true }
        }
        mediaPool = media
        s.createContext("/media") { ex -> dispatchMedia(ex, media) }
        // The page, at "/" and at nothing else. Registered last like the admin
        // server's, but emphatically not a catch-all: see [page].
        s.createContext("/") { ex -> guarded(ex) { page(ex) } }

        s.start()
        server = s
        return s.address.port
    }

    fun stop() {
        server?.stop(0)
        server = null
        // Interrupted rather than drained, like the admin server's: a stream
        // in flight is a child's video, and waiting for one to finish would
        // hold the container's shutdown for the length of it.
        mediaPool?.shutdownNow()
        mediaPool = null
    }

    /** The bound port — for tests, which ask for 0 and let the OS choose. */
    fun boundPort(): Int = server?.address?.port ?: port

    // --- the claim ------------------------------------------------------

    /**
     * `POST /claim {code}` — a code a parent minted, for the cookie this
     * browser then carries.
     *
     * The cookie is set on **this** origin, which is the whole point: the
     * admin origin never sees it, and this one never sees the admin session.
     */
    private fun claim(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "no")
        if (!sameOrigin(ex)) return respond(ex, 403, "cross-site")
        if (!claims.allow(now())) {
            val wait = claims.retryAfterSeconds(now())
            ex.responseHeaders.add("Retry-After", wait.toString())
            return respond(ex, 429, JSONObject().put("retryAfter", wait).toString())
        }
        val body = readBody(ex) ?: return respond(ex, 413, "too large")
        val code = runCatching { JSONObject(body).optString("code") }.getOrNull()
            ?: return respond(ex, 400, JSONObject().put("error", "no code").toString())
        browsers.claim(code, now()).fold(
            onSuccess = { claimed ->
                // No Secure flag, for the reason /login gives: this is plain
                // HTTP on a home LAN, and Secure would stop the cookie being
                // sent at all. HttpOnly and SameSite are the two that work
                // here — and Path=/ is harmless precisely because this origin
                // serves nothing but the kid's five routes.
                ex.responseHeaders.add(
                    "Set-Cookie",
                    "$CLAIM_COOKIE=${claimed.token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=" +
                        (HubBrowsers.CLAIM_TTL_MS / 1000)
                )
                respond(ex, 200, JSONObject().put("ok", true).put("kid", nameOf(claimed.kid)).toString())
            },
            onFailure = {
                // Named, because "that code is wrong" and "this hub is already
                // watching on twenty screens" send a parent to different
                // places. Nothing echoes back what was typed.
                val reason = (it as? ClaimRefused)?.reason ?: HubBrowsers.Refusal.UNKNOWN_CODE
                respond(ex, 409, JSONObject().put("refused", reason.name).toString())
            }
        )
    }

    /**
     * `GET /whoami` — the child this browser watches as, or a 401 the page
     * renders as the code box.
     *
     * Fails closed, and says nothing at all to a browser that has not claimed:
     * a stranger on the LAN learns the family's children's names from no route
     * on this origin.
     */
    private fun whoami(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val browser = watching(ex) ?: return
        respond(
            ex, 200,
            JSONObject()
                .put("kid", browser.kid)
                .put("name", nameOf(browser.kid))
                // The placeholder page has one video and no way to find
                // another. Step 4 replaces this whole answer with a home
                // screen; until then it is what proves the path end to end.
                .put("video", PLACEHOLDER_VIDEO)
                .toString()
        )
    }

    // --- media ----------------------------------------------------------

    /**
     * Take a slot, or say no, and get off the shared pool either way.
     *
     * The count is taken **before** the hand-off and released in the worker's
     * own `finally`, so a stream can never be admitted twice or leak a slot
     * when the executor refuses the task. Over the cap this answers 503 with a
     * `Retry-After` rather than queueing: a queued video does not start, shows
     * nothing, and gives a child nothing to read — where a 503 is a sentence
     * the page can put on screen.
     */
    private fun dispatchMedia(ex: HttpExchange, pool: ExecutorService) {
        if (!slots.take()) return guarded(ex) { busy(ex) }
        try {
            pool.execute {
                try {
                    guarded(ex) { media(ex) }
                } finally {
                    slots.release()
                }
            }
        } catch (e: RejectedExecutionException) {
            slots.release()
            guarded(ex) { busy(ex) }
        }
    }

    private fun busy(ex: HttpExchange) {
        ex.responseHeaders.add("Retry-After", MEDIA_RETRY_AFTER_SECONDS.toString())
        respond(
            ex, 503,
            JSONObject()
                .put("error", "busy")
                .put("streams", MAX_CONCURRENT_STREAMS)
                .toString()
        )
    }

    /**
     * `GET /media?v=<video>` — video bytes for a browser.
     *
     * The order of the first three lines is the whole design and is not
     * negotiable:
     *
     * 1. **The credential, first.** This is the only route on this box that
     *    costs real bandwidth on someone else's network, so an unclaimed
     *    caller must not be able to make the hub fetch anything at all.
     * 2. **[HubPolicy.mayPlay], second — before a single byte is resolved.**
     *    Not "resolve, then check": resolving is a round trip to YouTube on a
     *    named video, and a refused child must not be able to cause one. The
     *    reason code goes back verbatim, because a page that has to say
     *    *bedtime* and a page that has to say *a parent blocked this* are
     *    different sentences.
     * 3. **The muxed stream, third**, and only then the bytes.
     *
     * **Whose rules apply is decided by the cookie, never by the query.** The
     * `kid=` parameter this route used to read is gone, and guard 60 keeps it
     * gone: a child who could name the kid is a child who could name their
     * sibling, and spend a budget, a bedtime and a block list that were not
     * theirs. The child is a property of the credential, bound when the parent
     * minted the code.
     *
     * The rules are asked again on every chunk inside [HubStream.pump]. That,
     * and not speed, is the reason this route proxies instead of redirecting:
     * a `302` hands a child a URL Google will keep serving for hours, and a
     * parent who blocks a video mid-play would be talking to nobody.
     *
     * Everything about the answer is RFC-7233 plain — `206`, `Content-Range`,
     * `Content-Length`, `Accept-Ranges: bytes` — because a browser with any of
     * those wrong cannot seek and shows no duration, and it fails by looking
     * broken rather than by erroring.
     *
     * A 206 carries at most [HubMedia.MAX_RESPONSE_BYTES]. That is not a
     * throttle; see the constant for the measured JDK behaviour it exists to
     * stay clear of.
     */
    private fun media(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        if (!sameOrigin(ex)) return respond(ex, 403, "cross-site")
        val browser = watching(ex) ?: return
        val query = ex.requestURI.rawQuery
        val videoId = HubMedia.videoIdIn(query)
            ?: return respond(ex, 400, JSONObject().put("error", "bad video").toString())
        val kidId = browser.kid
        // Never taken from the page. The cell a browser's minutes are filed
        // under is derived from the token this hub minted at claim time, so a
        // tablet cannot claim a fresh viewer every morning to reset a budget.
        val viewer = meter.ledgerId(browser.token)

        val verdict = policy.mayPlay(kidId, videoId, viewer)
        if (!verdict.allowed) return refused(ex, verdict)

        val resolved = try {
            streams.resolve(videoId)
        } catch (e: HubStream.Unplayable) {
            // Distinguishable on purpose. "This hub could not find a stream a
            // browser can play" and "YouTube would not answer" send a parent
            // to different places, and serving something unplayable instead
            // would send them to neither.
            return respond(
                ex, 502,
                JSONObject().put("error", e.reason).put("detail", e.message.orEmpty()).toString()
            )
        }

        val answer = HubMedia.rangeFor(ex.requestHeaders.getFirst("Range"), resolved.total)
        if (answer == null) {
            ex.responseHeaders.add("Content-Range", HubMedia.unsatisfiable(resolved.total))
            return respond(ex, 416, JSONObject().put("error", "bad range").toString())
        }
        ex.responseHeaders.add("Content-Type", HubMedia.contentTypeFor(resolved.url))
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        // Never stored. The URL behind this is signed and expires, and a
        // cached copy in a child's browser is a video that keeps playing after
        // a parent blocks it — which is the one thing this whole route exists
        // to prevent.
        ex.responseHeaders.add("Cache-Control", "no-store")
        if (answer.partial) {
            ex.responseHeaders.add("Content-Range", HubMedia.contentRange(answer, resolved.total))
        }
        securityHeaders(ex)
        ex.sendResponseHeaders(if (answer.partial) 206 else 200, answer.length)
        // A 206 is capped at one chunk and therefore always finishes; the
        // gate below decides the NEXT request rather than interrupting this
        // one. The 200 arm — no `Range:` header at all — is the exception,
        // and the honest note is that a gate closing there stops the bytes
        // but leaves the caller waiting on its own timeout, because the JDK
        // will not close an under-written fixed-length reply (see
        // [HubMedia.MAX_RESPONSE_BYTES]). No browser reaches it: `<video>`
        // always sends a Range. Anything hand-rolled that does not is still
        // *stopped* — it just gets an untidy end rather than a clean one.
        try {
            ex.responseBody.use { out ->
                // Named, not trailing: `fetch` sits after `gate`, so a
                // trailing lambda here would silently become the fetcher.
                streams.pump(
                    out, resolved.url, answer.start, answer.endInclusive,
                    gate = { policy.mayPlay(kidId, videoId, viewer).allowed }
                )
            }
        } catch (e: java.io.IOException) {
            // A browser that has buffered enough closes the connection mid
            // transfer, and a seek does the same. Both are ordinary and land
            // here as a broken pipe; the headers are long since sent, so
            // there is nothing to answer with and nothing has gone wrong.
            System.err.println("media $videoId ended early: ${e.message}")
        }
    }

    /** A refusal a page can read — the code, and the sentence behind it. */
    private fun refused(ex: HttpExchange, verdict: HubPolicy.Decision) = respond(
        ex, 403,
        JSONObject()
            .put("error", verdict.reason)
            .put("detail", verdict.detail)
            .apply {
                verdict.spentMinutes?.let { put("spentMinutes", it) }
                verdict.budgetMinutes?.let { put("budgetMinutes", it) }
            }
            .toString()
    )

    // --- the page -------------------------------------------------------

    /**
     * The kid page, at `/` — and a JSON 404 at every other path.
     *
     * Deliberately **not** the admin server's catch-all. There, an unknown
     * path is a parent's typo and the console is the kindest answer. Here it
     * is either a mistake or somebody probing for the console, and a 200
     * carrying a page would be the answer that says "keep looking". It also
     * makes the boundary checkable from outside with one curl per admin route,
     * which is how this listener is proved rather than merely asserted.
     *
     * Served to anyone who asks, because it contains nothing: no name, no
     * video, no configuration. Everything the child sees arrives afterwards
     * from `/whoami`, behind the cookie.
     */
    private fun page(ex: HttpExchange) {
        if (ex.requestURI.path != "/") {
            return respond(ex, 404, JSONObject().put("error", "not here").toString())
        }
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val html = javaClass.getResourceAsStream("/web/kid.html")?.readBytes()
            ?: return respond(ex, 500, "the kid page is missing from this build")
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        securityHeaders(ex)
        ex.sendResponseHeaders(200, html.size.toLong())
        ex.responseBody.use { it.write(html) }
    }

    /** The generated stylesheet, straight from the jar. */
    private fun asset(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val bytes = javaClass.getResourceAsStream("/web/kid-tokens.css")?.readBytes()
            ?: return respond(ex, 404, "missing from this build")
        ex.responseHeaders.add("Content-Type", "text/css; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "max-age=86400")
        securityHeaders(ex)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    // --- plumbing -------------------------------------------------------

    /**
     * The kid gate. Every route that says anything about the family goes
     * through here, and it **fails closed to the code prompt**: null, after
     * answering 401, is what the page renders as "type your code".
     *
     * There is no fallback to the admin session and there cannot be — this
     * class never reads [HubServer.SESSION_COOKIE] and holds no reference to
     * [HubSessions] (guard 59). A parent signed in on the console is a
     * stranger here, which is the same statement in the other direction as a
     * kid cookie being nothing at all on `/api/`.
     */
    private fun watching(ex: HttpExchange): HubBrowsers.Browser? {
        val browser = browsers.resolve(cookie(ex), now())
        if (browser == null) {
            respond(ex, 401, JSONObject().put("error", "claim").toString())
            return null
        }
        browsers.noteSeen(browser.token, now())
        return browser
    }

    private fun cookie(ex: HttpExchange): String? =
        ex.requestHeaders.getFirst("Cookie")
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$CLAIM_COOKIE=") }
            ?.substringAfter("=")

    /** The child's name, or a blank one — never a 500 because a config will not parse. */
    private fun nameOf(kid: String): String =
        runCatching { store.load().profile(kid)?.name }.getOrNull().orEmpty()

    /**
     * Refuse anything a browser on another site initiated — including, and
     * especially, the console one port along.
     *
     * [HubServer.sameOrigin] itself, not a copy of it: the whole point of two
     * listeners is that this predicate answers false between them, and two
     * implementations of a security check are two things to harden, of which
     * the second is the one somebody forgets.
     */
    private fun sameOrigin(ex: HttpExchange): Boolean = HubServer.sameOrigin(ex)

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
        securityHeaders(ex)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /**
     * The three headers every reply carries — and **no fourth one naming
     * CORS**.
     *
     * `Access-Control-Allow-Origin` on this origin would undo the entire
     * reason it exists: the two origins are meant to be unable to read each
     * other, and one permissive header here is the wall coming down without
     * anything failing. Guard 58 greps the whole module for the string.
     */
    private fun securityHeaders(ex: HttpExchange) {
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.add("X-Frame-Options", "DENY")
        ex.responseHeaders.add("Referrer-Policy", "no-referrer")
    }

    /**
     * Every route runs inside this. An exception escaping a handler leaves the
     * JDK server holding an open exchange with no response, so the caller
     * waits for its timeout and reads it as "the hub is down" rather than as a
     * bug.
     */
    private fun guarded(ex: HttpExchange, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            System.err.println("kid route ${ex.requestURI} failed: ${e.message}")
            runCatching { respond(ex, 500, "error") }
        } finally {
            ex.close()
        }
    }

    internal companion object {
        /**
         * The kid's cookie. A different name from [HubServer.SESSION_COOKIE]
         * on purpose as well as by origin: the two are never both valid
         * anywhere, and a shared name would make that an accident of routing
         * rather than a fact.
         */
        const val CLAIM_COOKIE = "yk_kid"

        /**
         * The port this listens on when nobody says otherwise — one past the
         * admin's 8765, so a family reading their compose file sees the pair.
         * `YOSEMITE_KIDS_KID_PORT` moves it, and `hub/docker-compose.yml`
         * publishes it through the same variable so the two cannot drift.
         */
        const val DEFAULT_PORT = 8766

        /**
         * Claim attempts allowed in a window, and how long that window is.
         *
         * Ten a minute is far more than a child typing six characters needs
         * and far less than guessing one in 887 million is worth — and,
         * unlike the admin lockout, it forgets. A child who mistypes their way
         * to a 429 waits a minute, not a quarter of an hour, and their parent
         * is never locked out of anything at all.
         */
        const val MAX_CLAIMS_PER_WINDOW = 10
        const val CLAIM_WINDOW_MS = 60 * 1000L

        /**
         * Videos this box carries at once, and how long a refused caller is
         * asked to wait.
         *
         * Three, because that is a household: two children watching and one
         * seek in flight. The number is a bound on *this NAS's uplink and
         * CPU*, not a licence — every stream is a full-rate fetch from
         * googlevideo re-sent to a browser, and a fourth would not make a
         * fourth child's video play, it would make all four stutter. Past it
         * the route answers 503 rather than queueing: a queued video never
         * starts and says nothing, where a 503 is something a page can put on
         * screen.
         *
         * The media pool is sized to exactly this, so the refusal is decided
         * by [slots] on the control pool and never by a full queue.
         */
        const val MAX_CONCURRENT_STREAMS = 3
        const val MEDIA_RETRY_AFTER_SECONDS = 5

        /**
         * The one video the placeholder page plays.
         *
         * Step 4 builds the real page — shelves, a home screen, everything a
         * child actually picks from — and this goes with it. It exists so the
         * origin and the claim can be proved end to end in a browser rather
         * than only in a test, and it is a plain id because `HubPolicy` will
         * refuse it like any other unless the family's own catalogue holds it.
         */
        const val PLACEHOLDER_VIDEO = "dQw4w9WgXcQ"
    }
}
