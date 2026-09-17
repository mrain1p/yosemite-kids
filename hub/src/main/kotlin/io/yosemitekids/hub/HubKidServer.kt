package io.yosemitekids.hub

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The kid's half of the hub: the page a child watches on, and the routes
 * under `/kid/` that feed it. One listener with the parents' console, one
 * port, one address a family has to know — and a wall between the two that
 * is built from **credentials**, not from ports.
 *
 * ### The wall, and what holds it up
 *
 * The web player used to live on a second port so that the two were two
 * browser origins. The danger that design answered is real and it is still
 * the danger here: a page a child opened, on the same origin as the console,
 * can `fetch("/api/config", {method: "POST"})`, and if the parent's session
 * were an ambient cookie the browser would attach it — `Path` is matched
 * against the request, `SameSite=Strict` is satisfied, `HttpOnly` stops
 * nothing from being *sent*. On a shared iPad that is a child's page holding
 * a rewrite of the family's whole configuration.
 *
 * So on one origin the rule is: **no credential is ambient across the
 * wall.**
 *
 * - The parent's session is a header (`X-Session`, [HubServer.SESSION_HEADER]),
 *   held by the console's own script in the tab that signed in and attached
 *   on purpose to each call. Nothing a browser does on its own carries it;
 *   the hub sets no cookie for it and reads none (guard 59).
 * - The kid's credential is a cookie, because a `<video>` and an `<img>` can
 *   send no header — and it is scoped to `Path=/kid/`, so the browser never
 *   attaches it to `/api/` or anything else on the console's side. The
 *   console never reads it either way (guard 59): a kid cookie on an admin
 *   route is nothing.
 * - Every `/kid/` route that says anything about the family resolves that
 *   cookie first and fails closed (guard 60); the child it answers for is
 *   the cookie's, bound when the credential was minted, never a parameter.
 * - Nothing here ever sends a CORS header (guard 58), and [HubServer.sameOrigin]
 *   still refuses a request that arrived with a foreign `Origin`.
 *
 * ### How a browser gets in
 *
 * Two ways, both through `POST /kid/claim`, and neither involves typing a
 * code any more. A parent's console mints a one-shot code and shows it as a
 * QR whose URL is `/kid?c=<code>`; the page redeems it on arrival. Or the
 * child taps their own avatar and types the password their parent set on
 * their profile (`Profile.webPassword`, [io.yosemitekids.app.data.KidPassword]),
 * which is a field of the family config and so is the same on the phone,
 * the hub and every device. The password is throttled per kid ([HubKidLock])
 * on top of the bucket in front of the whole route, and never touches the
 * parents' lockout.
 *
 * ### What it serves
 *
 * Exactly the paths listed in [register] and pinned by guard 57, all under
 * `/kid`. There is no catch-all page: `/kid` answers the kid page and every
 * other `/kid…` path is a JSON 404, where the console deliberately answers an
 * unclaimed path with itself. That asymmetry is the point — under `/kid` an
 * unknown path is never something a parent mistyped.
 *
 * ### Bounded, like everything else facing the LAN
 *
 * These routes face the whole house before any credential is checked, so they
 * read the way `LanServer` does: a request timeout, a body cap far below the
 * admin's (nothing here posts more than a password), and a media pool that is
 * separate again because a stream holds its thread for the length of a video.
 */
class HubKidServer(
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
    /**
     * Where a browser got to in each video. The browser's device store — see
     * [HubKidHistory] for why the box keeps it and the phone does not.
     */
    private val history: HubKidHistory,
    /**
     * Favorites, Watch later and Up next for this browser — the same `:crawl`
     * stores the phone uses, never a hub-shaped copy. See [HubSavedLists].
     */
    private val lists: HubSavedLists,
    /** Where a browser's page errors go: the console's Devices page and the container log. See [HubReports]. */
    private val reports: HubReports,
    private val searches: HubKidSearches,
    /** Passed in so tests need no clock, like every other class here. */
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * What the page is shown. Shape only: every rule it draws on is the app's,
     * in `:core` or `:crawl`. See [HubKidHome].
     */
    private val browse = HubKidHome(policy, store, history, lists, searches, now)

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

    /**
     * The password throttle, per kid, behind [claims]. The bucket above
     * bounds how fast the LAN can knock; this bounds how far one child's
     * password can be guessed at all. Its own object for the reason
     * [claims] is: never the parents' lockout.
     */
    private val kidLock = HubKidLock(now)

    /**
     * Media runs on threads of its own, never on the pool the listener
     * answers everything else with. Same argument as the console's routes: a
     * proxied stream holds its thread for as long as the browser keeps
     * reading, so two children watching would leave nothing to answer
     * `/kid/claim` or `/kid/whoami` with.
     */
    private var mediaPool: ExecutorService? = null

    /** Poster fetches. Separate from [mediaPool] — see where it is built. */
    private var thumbPool: ExecutorService? = null
    private val slots = HubMedia.Slots(MAX_CONCURRENT_STREAMS)

    /**
     * The HD path's segment requests (`s=`) have slots and threads of their
     * own. A segment is a few megabytes and done in well under a second, so
     * the argument for the stream cap - a thread held for the length of a
     * video - does not apply; and dash.js opens the index of every rendition
     * at once when it starts, which on three stream slots was four 503s and
     * a second's retry before the first frame.
     */
    private val segmentSlots = HubMedia.Slots(MAX_CONCURRENT_SEGMENTS)
    private var segmentPool: ExecutorService? = null

    /**
     * The stream counter, so a test can fill it and ask over a socket what a
     * fourth child gets. Proving the 503 any other way means three real videos
     * and a network.
     */
    internal fun mediaSlots(): HubMedia.Slots = slots

    /** Small. Nothing a child's browser posts here is bigger than a password. */
    private val maxBody = 4 * 1024

    /** The console's catch-all, for a path under the "/kid" prefix that is not ours. See [register]. */
    private var console: (HttpExchange) -> Unit = { ex -> respond(ex, 404, JSONObject().put("error", "not here").toString()) }

    /**
     * Register every kid route on the hub's one listener. Called by
     * [HubServer.start] before it registers its own catch-all, and the paths
     * here are the whole of what a child's browser can reach: guard 57 pins
     * the list, and the JDK server's longest-prefix match sends anything else
     * under `/kid` to [page], which 404s it.
     */
    fun register(s: HttpServer, console: (HttpExchange) -> Unit) {
        // The JDK server matches a context by string prefix, not by path
        // segment: "/kid" would also catch "/kids" and "/kidney", which are a
        // parent's typos and belong to the console's catch-all. Everything
        // that is not /kid or /kid/... is handed back.
        this.console = console
        // Trade a code or a password for the cookie. Unauthenticated by
        // necessity — a browser that has never been here holds nothing to
        // present — and throttled in its own bucket for exactly that reason.
        s.createContext("/kid/claim") { ex -> guarded(ex) { claim(ex) } }
        // The children a browser may sign in as, for the "Who's watching?"
        // screen: only those with a password set, and only name and avatar.
        s.createContext("/kid/kids") { ex -> guarded(ex) { kids(ex) } }
        // Who this browser is watching as. The page's first call, and what it
        // renders the sign-in screen from when the answer is 401.
        s.createContext("/kid/whoami") { ex -> guarded(ex) { whoami(ex) } }
        // The whole home screen in one answer: shelves, hero, channels, the
        // countdown. One request rather than six, because six is six chances
        // to half-draw a five-year-old's page over house wifi.
        s.createContext("/kid/home") { ex -> guarded(ex) { home(ex) } }
        // The kid's own shelves: Favorites, Watch later, Up next, History. In
        // :core's order, with :core's words, and every shelf declared even when
        // it is empty - the page has one shape and an empty row says what would
        // fill it.
        s.createContext("/kid/you") { ex -> guarded(ex) { you(ex) } }
        // Put a video in one of the kid's own lists, or take it out. The only
        // kid route that writes anything a child chose — see [list] for the
        // four things it checks before it does.
        s.createContext("/kid/list") { ex -> guarded(ex) { list(ex) } }
        // Every channel, in the order the kid picked — orderChannels from
        // :crawl, so "A to Z" means the same thing here as on the television.
        s.createContext("/kid/channels") { ex -> guarded(ex) { channels(ex) } }
        // A seeded mix across every channel. The seed is the whole point: an
        // unseeded shuffle is the one ordering two faces cannot agree on.
        s.createContext("/kid/surprise") { ex -> guarded(ex) { surprise(ex) } }
        // One channel's videos.
        s.createContext("/kid/channel") { ex -> guarded(ex) { channel(ex) } }
        // A channel's playlists (the See-all page) and one playlist as a page:
        // both from the index the crawl filled (PlaylistCrawlRun), both showing
        // a kid exactly the rows the channel page would.
        s.createContext("/kid/playlists") { ex -> guarded(ex) { playlists(ex) } }
        s.createContext("/kid/playlist") { ex -> guarded(ex) { playlist(ex) } }
        // Search within what this kid may see, ranked by the shared SearchRank.
        s.createContext("/kid/search") { ex -> guarded(ex) { search(ex) } }
        // "Still watching, and this far in." The one route that writes: it
        // credits the watch meter, which is how a browser's minutes reach a
        // kid's daily budget at all, and remembers the resume position.
        s.createContext("/kid/progress") { ex -> guarded(ex) { progress(ex) } }
        // "Something on this page broke." A page error or a video the hub
        // could not carry, so a parent can see it on the console instead of
        // hearing about it at bedtime. Behind the cookie like everything
        // else, and it changes nothing.
        s.createContext("/kid/report") { ex -> guarded(ex) { report(ex) } }
        // What makes this installable on an iPad: an icon on the home screen,
        // full screen, no address bar. Unauthenticated on purpose — a browser
        // fetches these while installing, often without credentials, and they
        // say nothing about the family. See [manifest] for the service worker
        // this deliberately does not have.
        s.createContext("/kid/manifest.webmanifest") { ex -> guarded(ex) { manifest(ex) } }
        s.createContext("/kid/icon") { ex -> guarded(ex) { kidIcon(ex) } }
        // Video bytes. Handed to another pool, so the listener's thread goes
        // straight back to answering everything else. See [dispatchMedia].
        val media = Executors.newFixedThreadPool(MAX_CONCURRENT_STREAMS) { r ->
            Thread(r, "yosemite-kids-kid-media").apply { isDaemon = true }
        }
        mediaPool = media
        val segments = Executors.newFixedThreadPool(MAX_CONCURRENT_SEGMENTS) { r ->
            Thread(r, "yosemite-kids-kid-segment").apply { isDaemon = true }
        }
        segmentPool = segments
        // One route, two pools: a rendition request is a segment, a request
        // without `s=` is a stream held for the length of a video. See
        // [segmentSlots].
        s.createContext("/kid/media") { ex ->
            if (HubMedia.itagIn(ex.requestURI.rawQuery) != null) dispatchMedia(ex, segments, segmentSlots, MAX_CONCURRENT_SEGMENTS)
            else dispatchMedia(ex, media)
        }
        // The HD path. The manifest a browser's media source plays from is
        // built on the stream pool, because building it resolves the video
        // (HubDash, HubStream.dash); the library that reads it is served
        // from THIS origin and no CDN, because the kid page loads nothing
        // from the internet and a script fetched from elsewhere is a script
        // on a child's page this build never reviewed.
        s.createContext("/kid/dash") { ex -> dispatchMedia(ex, media, handler = ::dash) }
        s.createContext("/kid/dash.js") { ex -> guarded(ex) { dashJs(ex) } }
        // Thumbnails get a pool of their own, and not the media one: a grid of
        // forty posters would otherwise fill the three stream slots and a child
        // pressing play would be told the hub is busy by their own home screen.
        // Wider than the stream pool because these are small and quick, and
        // narrow enough that a page of them cannot become this box's whole
        // uplink. See [thumb].
        val thumbs = Executors.newFixedThreadPool(THUMB_THREADS) { r ->
            Thread(r, "yosemite-kids-kid-thumb").apply { isDaemon = true }
        }
        thumbPool = thumbs
        s.createContext("/kid/thumb") { ex ->
            try {
                thumbs.execute { guarded(ex) { thumb(ex) } }
            } catch (e: RejectedExecutionException) {
                guarded(ex) { respond(ex, 503, JSONObject().put("error", "busy").toString()) }
            }
        }
        // The page, at "/kid" and at nothing else. The shortest prefix here,
        // so it is what every unregistered "/kid…" path lands on — and it
        // answers those with a 404, emphatically not a catch-all: see [page].
        s.createContext(KID_PATH) { ex -> guarded(ex) { page(ex) } }
    }

    fun stop() {
        // Interrupted rather than drained, like the console's: a stream in
        // flight is a child's video, and waiting for one to finish would hold
        // the container's shutdown for the length of it.
        mediaPool?.shutdownNow()
        mediaPool = null
        segmentPool?.shutdownNow()
        segmentPool = null
        thumbPool?.shutdownNow()
        thumbPool = null
    }

    // --- the claim ------------------------------------------------------

    /**
     * `POST /kid/claim {code}` or `{kid, password}` — for the cookie this
     * browser then carries.
     *
     * Two doors, one credential. A code is a parent's one-shot, redeemed by
     * the page when the QR's URL arrives. A password is the child's own,
     * checked against the record on their profile — and only after the
     * per-kid lock says they may still try, so a sibling's guessing shuts one
     * door for a while and never the parents'.
     *
     * The cookie is scoped to `Path=/kid/`: the browser attaches it to the
     * kid routes and to nothing on the console's side, which is the half of
     * the wall this route is responsible for.
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
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return respond(ex, 400, JSONObject().put("error", "bad request").toString())
        val outcome: Result<HubBrowsers.Claimed> = when {
            json.has("code") -> browsers.claim(json.optString("code"), now())
            json.has("kid") -> {
                // Bounded off the wire, like everything else here.
                val kid = json.optString("kid").take(64)
                val password = json.optString("password").take(io.yosemitekids.app.data.KidPassword.MAX_LENGTH)
                val wait = kidLock.retryAfterSeconds(kid)
                if (wait > 0) {
                    ex.responseHeaders.add("Retry-After", wait.toString())
                    return respond(ex, 429, JSONObject().put("retryAfter", wait).toString())
                }
                val record = runCatching { store.load().profile(kid)?.webPassword }.getOrNull()
                // A kid with no password and a wrong password are one answer,
                // and both count as a wrong guess: "this child has no
                // password" is not something a browser on the LAN gets to
                // learn one name at a time.
                if (io.yosemitekids.app.data.KidPassword.verify(record, password)) {
                    kidLock.passed(kid)
                    browsers.admit(kid, now())
                } else {
                    kidLock.failed(kid)
                    Result.failure(ClaimRefused(HubBrowsers.Refusal.WRONG_PASSWORD))
                }
            }
            else -> return respond(ex, 400, JSONObject().put("error", "no code").toString())
        }
        outcome.fold(
            onSuccess = { claimed ->
                // No Secure flag, for the reason /login once gave: this is
                // plain HTTP on a home LAN, and Secure would stop the cookie
                // being sent at all. HttpOnly and SameSite are the two that
                // work here — and Path=/kid/ is the one that matters now that
                // the console shares this origin: the browser never sends this
                // cookie to /api, /login or the page at "/".
                ex.responseHeaders.add(
                    "Set-Cookie",
                    "$CLAIM_COOKIE=${claimed.token}; HttpOnly; SameSite=Strict; Path=$KID_PATH/; Max-Age=" +
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
     * `POST /kid/report {entries:[{at, level, msg}]}` — the page saying what
     * broke: an uncaught script error, a promise nobody handled, a video the
     * hub answered with a 5xx. Filed under the child this browser watches as,
     * from the cookie and never the body (guard 60), so a parent reading the
     * console knows which tablet. Bounded by [HubReports]; a page can post a
     * handful per load and then stops itself, so a loop of errors is a
     * handful of lines and not a flood.
     */
    private fun report(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "no")
        if (!sameOrigin(ex)) return respond(ex, 403, "cross-site")
        val browser = watching(ex) ?: return
        val body = readBody(ex) ?: return respond(ex, 413, "too large")
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return respond(ex, 400, JSONObject().put("error", "bad report").toString())
        val kept = reports.record(
            from = "browser",
            who = nameOf(browser.kid).ifEmpty { browser.kid },
            kind = "browser",
            version = json.optString("version").ifEmpty { null },
            entries = json.optJSONArray("entries")
        )
        respond(ex, 200, JSONObject().put("kept", kept).toString())
    }

    /**
     * `GET /kid/kids` — who a browser may sign in as: the children with a
     * password set, as name and avatar and nothing else.
     *
     * Unauthenticated, and that is a decision worth stating. A browser that
     * has not claimed learns from this route that the family has children
     * called Leo and Mia who watch here — the same thing anyone holding the
     * television's remote sees on its "Who's watching?" screen, and the
     * least a sign-in screen can show while still being one a five-year-old
     * can use. It learns no rule, no video and nothing about a kid without a
     * password; a family that wants the LAN to see no names sets no
     * passwords and signs in by QR alone, and this answers with an empty
     * list.
     */
    private fun kids(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val arr = org.json.JSONArray()
        runCatching { store.load().profiles }.getOrDefault(emptyList())
            .filter { it.webPassword != null }
            .forEach { p ->
                arr.put(
                    JSONObject().put("id", p.id).put("name", p.name)
                        .put("avatar", p.avatar).put("color", p.colorArgb)
                )
            }
        respond(ex, 200, JSONObject().put("kids", arr).toString())
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
                .toString()
        )
    }

    // --- browsing -------------------------------------------------------

    /**
     * `GET /home` — everything a child's home screen draws.
     *
     * Behind the credential like every other route here, and the kid it
     * answers for is the credential's, never a parameter: the same rule
     * `/media` follows and for the same reason (guard 60). A browser that has
     * not claimed gets the 401 that renders as the code box.
     */
    private fun home(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val browser = watching(ex) ?: return
        respond(ex, 200, browse.home(browser.kid, meter.ledgerId(browser.token)).toString())
    }

    /**
     * `POST /list {list, v, on}` — a child hearts something, or unhearts it.
     *
     * **The only route on this origin that stores something a child chose**,
     * so it is the one worth reading slowly. Four things are checked, in this
     * order, and none is optional:
     *
     * 1. **POST and same-origin.** A GET that writes is a link a sibling can
     *    send; a cross-site POST is the console's own page reaching in.
     * 2. **The credential, and the kid from it.** Never from the body — a
     *    child who could name the kid could fill their sibling's Up next.
     *    Same rule as `/media`, same reason, and guard 60 keeps it.
     * 3. **The video must be one this child may actually see.** Not merely a
     *    valid id: `HubPolicy.mayPlay`'s catalogue half, so a blocked video, a
     *    sibling's channel, or something never indexed cannot be *saved* even
     *    though it could never be played. Without this the store becomes a
     *    place to smuggle a row onto a shelf.
     * 4. **The answer is read back from the store.** The queue is capped and
     *    an add at the cap is refused, so a page that flipped its own heart on
     *    the tap would show a video as queued that is not.
     *
     * The video's title and poster come from the family's own index rather
     * than from the request, which is what keeps a saved row from being a
     * place to write arbitrary text a child then reads.
     */
    private fun list(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "no")
        if (!sameOrigin(ex)) return respond(ex, 403, "cross-site")
        val browser = watching(ex) ?: return
        val body = readBody(ex) ?: return respond(ex, 413, "too large")
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return respond(ex, 400, JSONObject().put("error", "bad body").toString())

        val which = HubSavedLists.Which.of(json.optString("list"))
            ?: return respond(ex, 400, JSONObject().put("error", "no such list").toString())
        val on = json.optBoolean("on", true)
        if (which == HubSavedLists.Which.CHANNELS) {
            // A heart on a CHANNEL: `c` names one of this kid's own channels, and
            // the row stored is the channel shaped like a video (its url, name
            // and picture), the same shape the phone stores - one store, one
            // convergence, and orderChannels floats it to the front on every face.
            val sourceId = json.optString("c").take(64)
            val row = browse.channelRow(browser.kid, sourceId)
                ?: return respond(ex, 403, JSONObject().put("error", "not-for-this-kid").toString())
            val nowOn = lists.set(browser.kid, which, row, on)
            return respond(
                ex, 200,
                JSONObject().put("list", which.wire).put("c", sourceId).put("on", nowOn)
                    .put("count", lists.urls(browser.kid, which).size).toString()
            )
        }
        val videoId = json.optString("v").takeIf { HubMedia.looksLikeVideoId(it) }
            ?: return respond(ex, 400, JSONObject().put("error", "bad video").toString())

        // The row as the family's own index holds it. Also the check that this
        // child may see it at all: browse.rowFor consults the same catalogue
        // HubPolicy.mayPlay does, so a blocked or not-for-this-kid video cannot
        // be saved any more than it could be played.
        val video = browse.rowFor(browser.kid, videoId)
            ?: return respond(ex, 403, JSONObject().put("error", "not-for-this-kid").toString())

        val nowOn = lists.set(browser.kid, which, video, on)
        respond(
            ex, 200,
            JSONObject()
                .put("list", which.wire)
                .put("v", videoId)
                // What the STORE says, not what was asked for. The queue can
                // refuse at its cap and the page must follow the store.
                .put("on", nowOn)
                .put("count", lists.urls(browser.kid, which).size)
                // What is at the head of the queue now.
                //
                // Here so the page never has to hunt for it. The end of a
                // video asks "take this one out, what is next?", and that is
                // one question with one answer — a page that fetched the whole
                // You payload and picked a shelf out of it would be deciding
                // something (guard 61), and would be three round trips deep at
                // the exact moment a child is waiting for the next story.
                .put("next", nextInQueue(browser.kid))
                .toString()
        )
    }

    /**
     * The head of this kid's queue as a playable row, or `null`.
     *
     * Filtered through the catalogue like every other shelf, so a video queued
     * last night and blocked by a parent this morning is not what plays next.
     */
    private fun nextInQueue(kid: String): Any =
        browse.nextInQueue(kid) ?: JSONObject.NULL

    /** `GET /you` — the kid's own shelves. Behind the credential like everything else. */
    private fun you(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val browser = watching(ex) ?: return
        respond(ex, 200, browse.you(browser.kid, meter.ledgerId(browser.token)).toString())
    }

    /** `GET /channels?sort=&seed=` — every channel this kid may see, ordered. */
    private fun channels(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val browser = watching(ex) ?: return
        respond(ex, 200, browse.channels(browser.kid, param(ex, "sort"), seedFrom(ex)).toString())
    }

    /** `GET /surprise?seed=` — a seeded mix across every channel. */
    private fun surprise(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val browser = watching(ex) ?: return
        respond(ex, 200, browse.surprise(browser.kid, seedFrom(ex)).toString())
    }

    /**
     * The seed a shuffle is drawn with, from the request or freshly minted.
     *
     * The page sends back the seed it was given, so a reload does not reshuffle
     * under a child's thumb — the same promise `orderChannels` makes on the
     * phone. A first visit has none and gets one, which is why this cannot
     * simply default to a constant: every family would get the same "random".
     */
    private fun seedFrom(ex: HttpExchange): Long =
        param(ex, "seed")?.toLongOrNull() ?: java.security.SecureRandom().nextLong()

    /** `GET /channel?id=<source>` — one channel's page, or a 404 if this kid may not see it. */
    private fun channel(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val browser = watching(ex) ?: return
        val id = param(ex, "id")
            ?: return respond(ex, 400, JSONObject().put("error", "no channel").toString())
        // Null is "not on this kid's list" as much as "no such channel", and
        // the answer is deliberately the same 404 for both: a child's browser
        // must not be able to tell a sibling's channel from a missing one.
        //
        // `from` is where the page's "Show more" continues from. The cap is
        // the parent's pageSize, applied HERE (guard 61 forbids the page
        // slicing), and `more` in the reply says whether to draw the button.
        // `watched=1` is the channel's finished videos, the phone's Watched
        // screen; the hub decides what counts as finished and in what order.
        val body = browse.channel(browser.kid, id, from = pageFrom(ex), onlyWatched = param(ex, "watched") == "1")
            ?: return respond(ex, 404, JSONObject().put("error", "not here").toString())
        respond(ex, 200, body.toString())
    }

    /** `GET /search?q=` — ranked with the same [io.yosemitekids.app.data.SearchRank] the app uses. */
    private fun playlists(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val browser = watching(ex) ?: return
        val id = param(ex, "id")
            ?: return respond(ex, 400, JSONObject().put("error", "no channel").toString())
        val body = browse.playlists(browser.kid, id)
            ?: return respond(ex, 404, JSONObject().put("error", "not here").toString())
        respond(ex, 200, body.toString())
    }

    private fun playlist(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val browser = watching(ex) ?: return
        val id = param(ex, "id")
            ?: return respond(ex, 400, JSONObject().put("error", "no playlist").toString())
        // A playlist of a channel this kid may not see and one that does not
        // exist are the same 404, like a channel (guard 60).
        val body = browse.playlist(browser.kid, id, from = pageFrom(ex))
            ?: return respond(ex, 404, JSONObject().put("error", "not here").toString())
        respond(ex, 200, body.toString())
    }

    private fun search(ex: HttpExchange) {
        val browser = watching(ex) ?: return
        if (ex.requestMethod == "POST") {
            // The × on a recent-search chip, or Clear all: the one thing a kid
            // may delete on their own. Same-origin like every other write here.
            if (!sameOrigin(ex)) return respond(ex, 403, "cross-site")
            val body = readBody(ex) ?: return respond(ex, 413, "too large")
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return respond(ex, 400, JSONObject().put("error", "bad body").toString())
            val left = when {
                json.optBoolean("clear") -> browse.clearSearches(browser.kid)
                json.optString("forget").isNotBlank() -> browse.forgetSearch(browser.kid, json.optString("forget").take(MAX_QUERY_CHARS))
                else -> return respond(ex, 400, JSONObject().put("error", "nothing to do").toString())
            }
            return respond(ex, 200, JSONObject().put("recent", JSONArray(left)).toString())
        }
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val q = param(ex, "q").orEmpty()
        if (q.length > MAX_QUERY_CHARS) {
            return respond(ex, 400, JSONObject().put("error", "too long").toString())
        }
        // `order` is the kid's chip (SearchOrder, validated in the hub);
        // `remember=1` says the child meant this search, so it joins their
        // recents - a search-as-you-type page must not remember every prefix.
        val reply = browse.search(
            browser.kid, q, from = pageFrom(ex),
            order = param(ex, "order").orEmpty().take(16),
            remember = param(ex, "remember") == "1"
        )
        respond(ex, 200, reply.toString())
    }

    /** `from=` on a paged route: a non-negative offset, bounded, or zero. */
    private fun pageFrom(ex: HttpExchange): Int =
        param(ex, "from")?.take(6)?.toIntOrNull()?.coerceIn(0, 100_000) ?: 0

    /**
     * `POST /progress {v, positionMs, durationMs}` — "still watching, and this
     * far in".
     *
     * **This is how a browser's minutes reach a budget at all.** A device
     * charges its own `SessionGuard`; a page cannot be trusted to charge
     * anything, so the hub counts the time *it* has observed passing between
     * beats ([HubWatchMeter.beat]) and files it under a cell derived from the
     * credential. The position is remembered alongside, which is what makes
     * Keep watching work on a tablet.
     *
     * The kid, again, is the cookie's. The body names a video and a position
     * and nothing else — a page that could name the kid is a page that could
     * spend a sibling's afternoon.
     *
     * Beating does not grant permission and never has: [media] re-asks
     * [HubPolicy] on every chunk, so a browser that keeps beating past a
     * bedtime is a browser whose next chunk stops.
     */
    private fun progress(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "no")
        if (!sameOrigin(ex)) return respond(ex, 403, "cross-site")
        val browser = watching(ex) ?: return
        val body = readBody(ex) ?: return respond(ex, 413, "too large")
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return respond(ex, 400, JSONObject().put("error", "bad body").toString())
        val videoId = json.optString("v").takeIf { HubMedia.looksLikeVideoId(it) }
            ?: return respond(ex, 400, JSONObject().put("error", "bad video").toString())

        val minutes = meter.beat(browser.token, browser.kid)
        history.save(
            kid = browser.kid,
            videoUrl = io.yosemitekids.app.data.Video.watchUrl(videoId),
            positionMs = json.optLong("positionMs").coerceAtLeast(0L),
            durationMs = json.optLong("durationMs").coerceAtLeast(0L)
        )
        // The countdown comes back on every beat, so a page that has been open
        // since breakfast is never showing this morning's number — and so the
        // sentence a child reads when their time runs out is the hub's.
        val time = policy.timeFor(browser.kid, meter.ledgerId(browser.token))
        respond(
            ex, 200,
            JSONObject()
                .put("minutes", minutes)
                .put("allowed", time.allowed)
                .put("reason", time.reason)
                .apply {
                    time.spentMinutes?.let { put("spentMinutes", it) }
                    time.budgetMinutes?.let { put("budgetMinutes", it) }
                    // The same three fields /home sends, so the pill reads
                    // identically whether it was painted on load or on a beat.
                    // Two shapes for one pill is how a countdown comes to say
                    // different things on the same screen a minute apart.
                    val budget = time.budgetMinutes
                    if (budget != null) {
                        val left = (budget - (time.spentMinutes ?: 0)).coerceAtLeast(0)
                        put("leftMinutes", left)
                        put("say", io.yosemitekids.app.ui.KidWords.timeLeft(left * 60L))
                        put("low", left * 60L <= io.yosemitekids.app.ui.KidWords.LOW_SECONDS)
                    }
                }
                .toString()
        )
    }

    /**
     * `GET /thumb?u=<poster url>` — one video poster, fetched by the hub.
     *
     * **The page loads no image from Google.** It could: a thumbnail URL is
     * public and a browser would fetch it happily. What that would cost is
     * every child's tablet announcing itself to Google's CDN — its IP, its
     * user agent, and the timing of every poster on the shelf — from a product
     * whose whole proposition is that a family's watching is nobody else's
     * business. The hub is already talking to those hosts on the family's
     * behalf; this keeps the number of things that do at one.
     *
     * The URL is checked against the same allow-list the crawler is armed with
     * ([Http.HUB_HOSTS], guard 7) before anything is opened, so this cannot be
     * turned into a fetcher for arbitrary addresses on the house network — the
     * classic server-side request forgery, which a route that takes a URL from
     * a request is exactly the shape of.
     */
    private fun thumb(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        // Behind the credential like everything else: an unclaimed browser must
        // not be able to make this box fetch anything at all.
        watching(ex) ?: return
        val raw = param(ex, "u")
            ?: return respond(ex, 400, JSONObject().put("error", "no url").toString())
        val url = runCatching { java.net.URI(raw) }.getOrNull()
            ?: return respond(ex, 400, JSONObject().put("error", "bad url").toString())
        val host = url.host
        if (url.scheme != "https" || host == null ||
            !io.yosemitekids.app.data.Http.hostAllowed(host, io.yosemitekids.app.data.Http.HUB_HOSTS)
        ) {
            return respond(ex, 403, JSONObject().put("error", "not allowed").toString())
        }
        val request = okhttp3.Request.Builder().url(raw).build()
        io.yosemitekids.app.data.Http.client.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) {
                return respond(ex, 502, JSONObject().put("error", "no image").toString())
            }
            val bytes = body.byteStream().readNBytes(MAX_THUMB_BYTES + 1)
            if (bytes.size > MAX_THUMB_BYTES) {
                return respond(ex, 502, JSONObject().put("error", "too big").toString())
            }
            // Only what an <img> can be, and never what the upstream claims: a
            // Content-Type echoed from another server is a header this origin
            // did not choose. Anything that is not an image is refused rather
            // than relabelled.
            val type = response.header("Content-Type").orEmpty().substringBefore(';').trim()
            if (!type.startsWith("image/")) {
                return respond(ex, 502, JSONObject().put("error", "not an image").toString())
            }
            ex.responseHeaders.add("Content-Type", type)
            // Posters do not change, and a shelf redraws constantly. This is
            // the one thing on this origin worth caching in a child's browser;
            // it names no video and says nothing about who watched what.
            ex.responseHeaders.add("Cache-Control", "private, max-age=86400")
            securityHeaders(ex)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    /** One query parameter, decoded. Null when absent or empty. */
    private fun param(ex: HttpExchange, name: String): String? =
        ex.requestURI.rawQuery
            ?.split('&')
            ?.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

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
    private fun dispatchMedia(
        ex: HttpExchange,
        pool: ExecutorService,
        slots: HubMedia.Slots = this.slots,
        cap: Int = MAX_CONCURRENT_STREAMS,
        handler: (HttpExchange) -> Unit = ::media
    ) {
        if (!slots.take()) return guarded(ex) { busy(ex, cap) }
        try {
            pool.execute {
                try {
                    guarded(ex) { handler(ex) }
                } finally {
                    slots.release()
                }
            }
        } catch (e: RejectedExecutionException) {
            slots.release()
            guarded(ex) { busy(ex, cap) }
        }
    }

    private fun busy(ex: HttpExchange, cap: Int = MAX_CONCURRENT_STREAMS) {
        ex.responseHeaders.add("Retry-After", MEDIA_RETRY_AFTER_SECONDS.toString())
        respond(
            ex, 503,
            JSONObject()
                .put("error", "busy")
                .put("streams", cap)
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
        // One rendition of the HD path, or the muxed stream. Which is a
        // property of the request, and the gate below is the same for both.
        val itag = HubMedia.itagIn(query)
        // Never taken from the page. The cell a browser's minutes are filed
        // under is derived from the token this hub minted at claim time, so a
        // tablet cannot claim a fresh viewer every morning to reset a budget.
        val viewer = meter.ledgerId(browser.token)

        val verdict = policy.mayPlay(kidId, videoId, viewer)
        if (!verdict.allowed) return refused(ex, verdict)

        val resolved = try {
            if (itag != null) streams.stream(videoId, itag) else streams.resolve(videoId)
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

        val answer = HubMedia.rangeFor(
            ex.requestHeaders.getFirst("Range"), resolved.total,
            if (itag != null) HubMedia.MAX_SEGMENT_BYTES else HubMedia.MAX_RESPONSE_BYTES
        )
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
        // A rendition reply (`s=`) is the exception the other way: a whole
        // segment, up to [HubMedia.MAX_SEGMENT_BYTES], because a media-source
        // player appends what it gets AS the segment it asked for. The gate
        // still runs between the chunks the pump fetches, so a block lands
        // mid-segment - with the untidy end described above, and then a 403
        // on the next segment, which is what the page shows.
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
            // What a CHILD reads. `detail` is written for a parent's log and
            // says so in its own KDoc; the page was putting it in front of a
            // five-year-old. One vocabulary in :core, so the television and the
            // tablet refuse in the same words.
            .put("say", io.yosemitekids.app.ui.KidWords.refusal(verdict.reason))
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
        val path = ex.requestURI.path
        // "/kids", "/kidney": the prefix match brought a parent's typo here.
        // Not ours; the console answers it with its page like any other.
        if (path != KID_PATH && !path.startsWith("$KID_PATH/")) return console(ex)
        if (path != KID_PATH) {
            return respond(ex, 404, JSONObject().put("error", "not here").toString())
        }
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val html = javaClass.getResourceAsStream("/web/kid.html")?.readBytes()
            ?: return respond(ex, 500, "the kid page is missing from this build")
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        // Same argument as the stylesheet's: a held page is a child running
        // last month's markup against this month's payload.
        ex.responseHeaders.add("Cache-Control", "no-cache")
        securityHeaders(ex)
        ex.sendResponseHeaders(200, html.size.toLong())
        ex.responseBody.use { it.write(html) }
    }

    /** The generated stylesheet, straight from the jar. */
    /**
     * `GET /kid-manifest.webmanifest` — what makes this installable.
     *
     * ### Why a kid manifest at all
     *
     * On an iPad this page IS the app. Added to the home screen with a
     * manifest it opens full-screen with its own icon and no address bar;
     * without one it is a Safari tab with a URL a child can edit. The console
     * has had a manifest since it was written, for a parent; this is the same
     * argument for the person who actually uses the product.
     *
     * ### Built here rather than generated into a file
     *
     * `kid-tokens.css` is generated at build time because it is a hundred
     * derived colours and nobody should be able to hand-edit it. This is
     * fifteen lines of JSON whose only two colours come from [KID_DARK], so
     * building it in Kotlin keeps `:core` the single source without a Gradle
     * task, a generated resource and a guard to police the pair. The values are
     * still read from the one table — which is the property that mattered.
     *
     * ### Deliberately NO service worker
     *
     * A PWA usually comes with one, and this one must not. `Cache Storage`
     * outlives the claim cookie, so a cached shell keeps a **revoked or blocked
     * child looking at a working app** — precisely the failure `/media`'s
     * per-chunk `mayPlay` gate and its `no-store` exist to prevent. An
     * installed icon that opens a page which then asks the hub for everything
     * is the whole win here; offline is not a feature this product wants.
     */
    private fun manifest(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val ground = io.yosemitekids.app.ui.Argb.css(io.yosemitekids.app.ui.KID_DARK.background)
        val body = JSONObject()
            .put("name", "Yosemite Kids")
            // What fits under a home-screen icon. The long name is the one a
            // child never reads.
            .put("short_name", "Yosemite")
            // Its own scope beside the console's: a tap on the installed icon
            // lands on the kid page, and the app stays within /kid.
            .put("id", KID_PATH)
            .put("start_url", KID_PATH)
            .put("scope", KID_PATH)
            .put("display", "standalone")
            .put("orientation", "any")
            .put("background_color", ground)
            .put("theme_color", ground)
            .put(
                "icons",
                org.json.JSONArray()
                    .put(icon("192", "192x192", "any"))
                    .put(icon("512", "512x512", "any"))
                    .put(icon("maskable", "512x512", "maskable"))
            )
            .toString()
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/manifest+json; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        securityHeaders(ex)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun icon(size: String, sizes: String, purpose: String) = JSONObject()
        .put("src", "$KID_PATH/icon?s=$size")
        .put("sizes", sizes)
        .put("type", "image/png")
        .put("purpose", purpose)

    /**
     * `GET /kid-icon?s=192|512|maskable|apple` — the home-screen icon.
     *
     * One route with a parameter rather than four paths, because guard 57 pins
     * this origin's route list and four near-identical entries on it is four
     * things to read past when asking the question that list exists for: *what
     * can a child's browser reach?*
     *
     * **The same artwork the console uses**, from the same files in the jar.
     * It is one product and one mark; a second icon set would be a second
     * thing to redraw when the mark changes, which is the drift this project
     * keeps finding in colours and lengths. The *paths* differ so the two
     * origins still share no route but `/` and the stylesheet (guard 57).
     *
     * Unauthenticated, and it has to be: a browser fetches a manifest's icons
     * while installing, often without credentials, and an icon that 401s is an
     * app that installs with a grey square. It says nothing about the family —
     * it is the same picture for every household running this build.
     */
    private fun kidIcon(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val file = when (param(ex, "s")) {
            "512" -> "icon-512.png"
            "maskable" -> "icon-maskable-512.png"
            "apple" -> "apple-touch-icon.png"
            // 192 and anything unrecognised. A manifest that asked for a size
            // this build does not have should still install.
            else -> "icon-192.png"
        }
        val bytes = javaClass.getResourceAsStream("/web/$file")?.readBytes()
            ?: return respond(ex, 404, "missing from this build")
        ex.responseHeaders.add("Content-Type", "image/png")
        // A mark that changes about once a year, against a home-screen icon
        // that is refetched on every install. Unlike the stylesheet there is no
        // per-release drift to worry about here.
        ex.responseHeaders.add("Cache-Control", "public, max-age=604800")
        securityHeaders(ex)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /**
     * `GET /kid/dash?v=<id>` - the manifest a browser plays HD through.
     *
     * The same gate as `/media`, asked before anything is resolved, for the
     * same reason: a refused child must not be able to make this box ask
     * YouTube. What comes back is [HubDash]'s MPD, every BaseURL of which is
     * `/kid/media` with the rendition's `s=`, so the per-chunk gate holds on
     * HD exactly as it does at 360p and a redirect never hands the browser
     * a googlevideo URL. `no-store`: the renditions inside expire with the
     * URLs they name, and a cached manifest is a video that keeps playing
     * after a block. A video with no mp4 pair is `502 no-dash-streams`, and
     * the page plays the muxed stream instead - smaller, never nothing.
     */
    private fun dash(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        if (!sameOrigin(ex)) return respond(ex, 403, "cross-site")
        val browser = watching(ex) ?: return
        val videoId = HubMedia.videoIdIn(ex.requestURI.rawQuery)
            ?: return respond(ex, 400, JSONObject().put("error", "bad video").toString())
        val verdict = policy.mayPlay(browser.kid, videoId, meter.ledgerId(browser.token))
        if (!verdict.allowed) return refused(ex, verdict)
        val mpd = try {
            streams.dash(videoId)
        } catch (e: HubStream.Unplayable) {
            return respond(
                ex, 502,
                JSONObject().put("error", e.reason).put("detail", e.message.orEmpty()).toString()
            )
        }
        val bytes = mpd.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/dash+xml; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        securityHeaders(ex)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** dash.js, read from the jar once: three quarters of a megabyte a page asks for on every load it cannot cache. */
    private val dashJsBytes: ByteArray? by lazy { javaClass.getResourceAsStream("/web/dash.all.min.js")?.readBytes() }

    /**
     * `GET /kid/dash.js` - the player library, vendored (see the LICENSE
     * beside it in the jar). Unauthenticated like the icon: it says nothing
     * about the family. Cached a week; the page names the library's version
     * in its query, so an upgrade is a new URL and never a stale copy.
     */
    private fun dashJs(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val bytes = dashJsBytes ?: return respond(ex, 404, "missing from this build")
        ex.responseHeaders.add("Content-Type", "text/javascript; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "public, max-age=604800")
        securityHeaders(ex)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    // --- plumbing -------------------------------------------------------

    /**
     * The kid gate. Every route that says anything about the family goes
     * through here, and it **fails closed to the sign-in screen**: null, after
     * answering 401, is what the page renders as "Who's watching?".
     *
     * There is no fallback to the admin session and there cannot be — this
     * class never reads [HubServer.SESSION_HEADER] and holds no reference to
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
     * Refuse anything a browser on another site initiated.
     *
     * [HubServer.sameOrigin] itself, not a copy of it: two implementations of
     * a security check are two things to harden, of which the second is the
     * one somebody forgets. On one origin this no longer separates the kid
     * page from the console — the credentials do that — but it still stops
     * a page on some other site on the LAN posting here.
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
         * The kid's cookie: the one cookie this hub sets. Scoped to
         * `Path=/kid/` when set (see [claim]), so the browser never presents
         * it on the console's side, and never read by [HubServer] (guard 59).
         * A different name from the parents' [HubServer.SESSION_HEADER] so
         * "which credential is this" is never a question of routing.
         */
        const val CLAIM_COOKIE = "yk_kid"

        /**
         * Where the kid app lives on the hub's one origin: the page at this
         * path, every route beneath it. One address for a family to know —
         * `http://<hub>:8765/kid` — and the prefix the kid cookie is scoped to.
         */
        const val KID_PATH = "/kid"

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

        /**
         * Segment requests in flight at once, on the HD path. dash.js holds
         * two per viewer (a video segment and an audio one) and opens every
         * rendition's index at once at the start - seven for a 1080p video -
         * so three viewers fit with room for a start-up burst. Past it the
         * same 503, which dash.js retries after a second.
         */
        const val MAX_CONCURRENT_SEGMENTS = 8
        const val MEDIA_RETRY_AFTER_SECONDS = 5

        /**
         * How often the page says "still watching".
         *
         * Twenty seconds is a compromise between two failures. Longer, and a
         * child who closes the lid mid-video has up to that much time credited
         * that they did not watch — [HubWatchMeter.MAX_GAP_MS] bounds the
         * damage, but the last beat is still counted. Shorter, and a household
         * of three tablets is writing this box's disk every few seconds for
         * nothing; [HubKidHistory.WRITE_INTERVAL_MS] is what keeps the history
         * file out of that loop.
         */
        const val BEAT_SECONDS = 20

        /** Poster fetches at once. See where the pool is built. */
        const val THUMB_THREADS = 6

        /**
         * A poster this big is not a poster. YouTube's largest thumbnail is
         * well under this; the cap is here because the route hands a remote
         * body to a child's browser and an unbounded read is an unbounded read
         * whoever is on the other end.
         */
        const val MAX_THUMB_BYTES = 512 * 1024

        /**
         * A search a child typed. Long enough for anything a six-year-old
         * hunts for, short enough that the ranking loop cannot be handed a
         * paragraph to tokenize per candidate video.
         */
        const val MAX_QUERY_CHARS = 100
    }
}
