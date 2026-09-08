package io.yosemitekids.hub

import java.io.File

import io.yosemitekids.app.data.ChannelIndex

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.yosemitekids.app.data.BackupFile
import io.yosemitekids.app.data.ScreeningStore
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
    /**
     * The AI verdicts this hub holds. Not screened here — the hub reaches
     * YouTube and nothing else (guard 7) — but pushed here by every phone on
     * every sweep, which is what the review queue on the admin page is built
     * from. Same file name a device uses, so a copy taken off either box reads
     * on the other.
     *
     * Taken from [store]'s own directory rather than from [dataDir], which is
     * a display string the caller may pass anything for: `Main` never passes
     * it at all and the two agreed only because both read the same environment
     * variable. Verdicts that landed somewhere other than beside `config.json`
     * would be silently absent from every backup of the volume, and the queue
     * would empty itself on the next container move with nothing to say why.
     */
    private val screening: ScreeningStore = ScreeningStore(File(store.dataDir, "screening.json")),
    /** The election and the crawl, for the Devices page to report on. Null in tests that have neither. */
    private val master: HubMaster? = null,
    private val crawl: HubCrawl? = null,
    /**
     * The kid origin's port — a **second listener**, not a path under this one.
     *
     * Zero lets the OS choose, which is what every test wants and what makes
     * two servers in one JVM harmless. `Main` passes `YOSEMITE_KIDS_KID_PORT`.
     * See [HubKidServer] for why a port and not a path: on one origin a
     * child's page can spend a parent's session cookie against `/api/config`,
     * and every gate this project has agrees that it may.
     */
    private val kidPort: Int = 0,
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

    /**
     * The family's watch ledger. Built here, like [sessions], because it needs
     * [now] and [store] — and kept firmly apart from [store] itself: a counter
     * is not curation, and nothing about a minute of viewing may move the
     * config's fingerprint, its version ring or its change log. See [HubUsage].
     *
     * The zone is read fresh on every call rather than captured, so a parent
     * naming one takes effect without restarting the container; `runCatching`
     * because a config that will not parse must cost the family a windowed
     * ledger, never a 500 on a route that is only counting.
     */
    private val ledger = HubUsage(
        store.dataDir,
        homeZone = { runCatching { store.load().homeZone }.getOrNull() },
        now = now
    )

    /**
     * The one place this box decides whether a child may play something, and
     * the meter that counts a browser's minutes for it.
     *
     * Built here beside [ledger] because they need the same four things this
     * server already holds — and handed to [kid], which is where the caller
     * lives: `GET /media` on the kid origin asks [policy] before it resolves
     * anything and again on every chunk. One object, shared, rather than a
     * second HubPolicy over there: two answers to "may this child watch this"
     * is the failure guard 46 exists for, and it would not throw — it would
     * let a browser play what a television hides.
     */
    private val policy = HubPolicy(store, ledger, screening, index, now)
    private val meter = HubWatchMeter(ledger, now)

    /**
     * Which browsers may watch, and the codes a parent mints to let one in.
     *
     * Beside `config.json` on the same volume, because a claimed browser is
     * family setup: a hub that forgot them on restart would send every child
     * back for a fresh code after a `docker pull`.
     */
    private val browsers = HubBrowsers(store.dataDir)

    /**
     * The kid's own origin.
     *
     * Built here because it needs four things this server already holds —
     * the config, the verdict engine, the meter and the claim store — and
     * started and stopped with it, so a container has one listener's
     * lifecycle to think about and gets two. It shares **objects**, not
     * routes: the two servers answer disjoint path sets (guard 57), and
     * nothing about the admin session reaches it (guard 59).
     */
    private val kid = HubKidServer(kidPort, browsers, store, policy, meter, now)

    /** The verdict engine and the browser meter, for tests and a future route. */
    fun policy(): HubPolicy = policy
    fun watchMeter(): HubWatchMeter = meter

    /** The kid origin's bound port, for the boot line and for tests. */
    fun kidPort(): Int = kid.boundPort()

    /** The kid origin's stream counter, so a test can fill it over a socket. */
    internal fun mediaSlots(): HubMedia.Slots = kid.mediaSlots()

    /**
     * The claim store, so a test can mint a code without a config to hold a
     * kid in. Internal: the route a parent uses is `POST /api/browsers`, which
     * refuses a kid this hub has never heard of.
     */
    internal fun browsers(): HubBrowsers = browsers

    /**
     * How fast an unauthenticated caller may ask to join. See [enrol].
     *
     * Separate from [sessions]' counter deliberately: sharing it would let
     * anyone on the LAN lock a parent out of their own hub by hammering a
     * route that needs no credential.
     */
    private val enrolments = HubRate(MAX_ENROLMENTS_PER_WINDOW, ENROLMENT_WINDOW_MS)

    /**
     * When this process came up, for the health block on "This hub".
     *
     * Taken here rather than passed in for the same reason [sessions] is
     * built here: it needs [now], and a constructor parameter defaulting to
     * another parameter would have to sit after it, which is the
     * trailing-lambda trap described above.
     */
    private val startedAt = now()

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
        "/apple-touch-icon.png" to "image/png",
        // The kid palette and type scale, written into this jar at build time
        // by :hub:generateKidTokensCss from the same :core table the Android
        // app themes itself from. Carries no family data, so it sits with the
        // rest of the shell rather than behind the session.
        "/kid-tokens.css" to "text/css; charset=utf-8"
    )

    fun start(): Int {
        applyRequestTimeout()
        val s = HttpServer.create(InetSocketAddress(port), 0)
        // A small fixed pool, like the app's LAN server. Unbounded threads on a
        // NAS is how a device that reconnects in a loop takes the box down.
        s.executor = Executors.newFixedThreadPool(4)

        s.createContext("/status") { ex -> guarded(ex) { status(ex) } }
        s.createContext("/config") { ex -> guarded(ex) { config(ex) } }
        s.createContext("/enrol") { ex -> guarded(ex) { enrol(ex) } }
        s.createContext("/approve") { ex -> guarded(ex) { approve(ex) } }
        s.createContext("/pending") { ex -> guarded(ex) { pending(ex) } }
        s.createContext("/health") { ex -> guarded(ex) { health(ex) } }
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
        // Verdict sharing, both ways, exactly as a device speaks it. A video
        // is billed to the AI once per rules version by whichever device sees
        // it first, and until now the hub was the one peer that answered the
        // sweep with a page of HTML. Answering it properly is also what puts
        // a review queue on the admin page: the entry carries its own title,
        // channel, thumbnail and reason, so nothing else has to be held here.
        s.createContext("/verdicts") { ex -> guarded(ex) { verdicts(ex) } }
        // Watch minutes, both ways, and device-initiated in both directions:
        // a device pushes what only it knows and pulls what it needs, so this
        // box never holds a credential on anything and guard 7 is untouched.
        // A relay and a scoreboard; it stops nobody watching anything.
        s.createContext("/usage") { ex -> guarded(ex) { usage(ex) } }
        // Video bytes are NOT here. `/media` lives on the kid origin
        // ([HubKidServer]), because the thing that plays video is a child's
        // browser and a child's browser must not be on this origin at all —
        // see that class for what a page here can do with a parent's session
        // cookie. Guard 57 fails the build if it comes back.
        //
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
        // The kid's origin comes up with this one and goes down with it. It
        // is a separate listener on a separate port; it is not separate
        // plumbing for a parent to start, stop or forget.
        kid.start()
        return s.address.port
    }

    fun stop() {
        server?.stop(0)
        server = null
        kid.stop()
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
        // Whether this caller and this hub are going to be comparing a key.
        // Deliberately per-caller and not "does this box hold one": a peer
        // that will never be served the key must never be judged on a
        // fingerprint that includes it, or it reads as out of sync for ever
        // and takes the merge arm on every sweep — the exact bug the phone's
        // keyless-peer flag was introduced to fix. The predicate is the same
        // one [config] uses, so the two answers cannot drift apart.
        val shared = sharesKeyWith(ex)
        respond(
            ex, 200,
            JSONObject()
                // The keyless fingerprint, for ever. A phone from before this
                // feature has the hub recorded as keyless and compares its own
                // keyless form against this one; moving it would put every
                // such phone permanently out of sync with a hub it agrees with
                // completely.
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
                // Which build a paired device is talking to. `/health` says
                // the same thing to anything that can reach the port; this
                // says it inside the sweep a device already makes, so a phone
                // can one day refuse to push a field an old image would drop
                // rather than watching it disappear. Additive: a peer that
                // does not read it is unaffected.
                .put("hubVersion", HubBuild.VERSION)
                .put("holdsKey", shared)
                .apply { if (shared) put("hashWithKey", store.fingerprintWithKey()) }
                .toString()
        )
    }

    /**
     * Whether the caller is a parent's phone **and** this hub has a key to
     * give it. Both halves, in one place, because `/status` and `GET /config`
     * have to agree: a peer told `holdsKey` and then served a keyless document
     * would compare a hash it can never reproduce.
     *
     * Parents only, and the row says which. A kid device is handed its key by
     * a parent's phone (`ConfigSync` pushes `rawJson()`, secrets included) and
     * has no reason to be handed one by a box on the network as well — this is
     * the machine most likely to face the internet one day, and a television
     * is the peer least able to look after a credential.
     */
    private fun sharesKeyWith(ex: HttpExchange): Boolean =
        store.holdsKey() &&
            tokens.kindOf(ex.requestHeaders.getFirst("X-Token")) == HubTokens.Kind.PARENT

    private fun config(ex: HttpExchange) {
        if (!authorised(ex)) return
        when (ex.requestMethod) {
            "GET" -> {
                // The key goes back on only for a parent. See [sharesKeyWith].
                val raw = if (sharesKeyWith(ex)) store.forPeers() else store.raw()
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
    /**
     * This hub's verdicts for the rules the family is on now, and a peer's
     * merged in.
     *
     * The rules version comes from the config this hub already holds, so the
     * two faces cannot disagree about which verdicts are current: a device
     * pushing under an older version is ignored by [ScreeningStore.importJson]
     * rather than half-adopted.
     *
     * Import never overwrites a verdict already held, so a pull-then-push
     * exchange settles instead of ping-ponging — the one exception being a
     * peer's deep (pre-play) verdict over this box's title-only one, which is
     * one-way and therefore cannot loop either.
     */
    private fun verdicts(ex: HttpExchange) {
        if (!authorised(ex)) return
        // A hub with no config yet has screened nothing and can serve nothing;
        // rulesVersion 0 is what a family before any AI rules is on, which is
        // the honest answer rather than a 500.
        val rules = runCatching { store.load().ai.rulesVersion }.getOrDefault(0)
        when (ex.requestMethod) {
            "GET" -> respond(ex, 200, screening.exportJson(rules))
            "POST" -> {
                val body = readBody(ex) ?: return respond(ex, 413, "too large")
                val fresh = screening.importJson(body, rules)
                if (fresh < 0) respond(ex, 400, JSONObject().put("error", "bad verdicts").toString())
                else respond(ex, 200, JSONObject().put("merged", fresh).toString())
            }
            else -> respond(ex, 405, "no")
        }
    }

    /**
     * The family's watch ledger: serve the join, or take a device's own cells
     * into it.
     *
     * `POST` keeps only the cells the caller authored, identified by the
     * `X-Device-Id` it presented alongside the token [authorised] just
     * checked — the same id `Whitelist.deviceProfiles` is keyed by, and the
     * only identity this box can tie to a caller. A body with no author is
     * refused rather than half-trusted.
     *
     * There is no hub-side enforcement anywhere near this. The number is
     * carried so the device about to *start* can add it to its own live
     * counter; the decision stays where the child and the screen are.
     */
    private fun usage(ex: HttpExchange) {
        if (!authorised(ex)) return
        when (ex.requestMethod) {
            "GET" -> respond(ex, 200, ledger.exportJson())
            "POST" -> {
                val body = readBody(ex) ?: return respond(ex, 413, "too large")
                val stored = ledger.merge(body, ex.requestHeaders.getFirst("X-Device-Id")?.take(64))
                if (stored < 0) respond(ex, 400, JSONObject().put("error", "bad usage").toString())
                else respond(ex, 200, JSONObject().put("cells", stored).toString())
            }
            else -> respond(ex, 405, "no")
        }
    }

    /**
     * A device asking to join: the one route gated by neither a token nor a
     * session, because a device that has never been here holds nothing to
     * present.
     *
     * It grants nothing on its own — [approve] is where a human holding the
     * admin secret turns a code into a token — but it does *write*, and until
     * this throttle and [HubTokens.MAX_PENDING] existed that was the whole
     * problem: anything on the LAN could append rows to `devices.json` for as
     * long as it liked, rewriting the file on every call, and the only bound
     * was the ten-minute expiry sweep.
     *
     * Both numbers sit far above any real household on purpose, and the join
     * window has a second constraint on top of that: the phone renders **any**
     * non-200 from this route as "that is not a Yosemite Kids hub"
     * (`HubEnrolment.mint`), so a limit a parent could plausibly reach would
     * send them to check an address that was right all along — the same
     * failure the 429 case on `/approve` was added to fix. See
     * [MAX_ENROLMENTS_PER_WINDOW] for the two limits it has to sit between.
     */
    private fun enrol(ex: HttpExchange) {
        if (ex.requestMethod != "POST") return respond(ex, 405, "no")
        // A page on another site cannot be the thing joining: a device enrols
        // itself over HTTP with no browser in the loop, and the phone's
        // OkHttp calls carry no Origin at all. Same reasoning as
        // /pair-request in the app, which refuses any request carrying one.
        if (!sameOrigin(ex)) return respond(ex, 403, "cross-site")
        if (!enrolments.allow(now())) {
            val wait = enrolments.retryAfterSeconds(now())
            ex.responseHeaders.add("Retry-After", wait.toString())
            return respond(ex, 429, JSONObject().put("retryAfter", wait).toString())
        }
        val body = readBody(ex) ?: return respond(ex, 413, "too large")
        val name = runCatching { JSONObject(body).optString("name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "A device"
        // Null means the queue is full. 429 rather than 500: it is a "come
        // back in a moment", not a fault, and it is the code the phone
        // already knows how to wait on where it reads one.
        val code = tokens.startEnrolment(name.take(40), now())
            ?: return respond(
                ex, 429,
                JSONObject()
                    .put("error", "too many waiting")
                    .put("retryAfter", HubTokens.CODE_TTL_MS / 1000)
                    .toString()
            )
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
        // Same refusal /login, /password and /recovery already make, and for
        // the same reason: a page on another site must not be able to spend
        // an admin secret a browser happens to be holding. Safe for the real
        // callers — the admin GUI approves through `/api/devices`, and the
        // phone's OkHttp requests carry no Origin header at all, which
        // sameOrigin() reads as "not a browser" and allows.
        if (!sameOrigin(ex)) return respond(ex, 403, "cross-site")
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
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return respond(ex, 400, "no code")
        val code = json.optString("code")
        // What is being enrolled, said by the party holding the admin secret —
        // never by the thing joining. /enrol is unauthenticated by necessity,
        // so nothing it claims about itself is worth anything, and the only
        // door this opens is the API key. Absent means DEVICE, which is what
        // every older phone sends and what a code typed into the hub's own
        // page means.
        val enrolling = if (json.optString("kind") == "parent") HubTokens.Kind.PARENT
        else HubTokens.Kind.DEVICE

        tokens.approve(code, now(), enrolling).fold(
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
        if (!sameOrigin(ex)) return respond(ex, 403, "cross-site")
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
        // Before anything else: a device route this hub does not implement is
        // a 404, never the page. See [DEVICE_ONLY].
        if (ex.requestURI.path in DEVICE_ONLY) {
            return respond(ex, 404, JSONObject().put("error", "the hub does not answer this").toString())
        }
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val html = javaClass.getResourceAsStream("/web/index.html")?.readBytes()
            ?: return respond(ex, 500, "the GUI is missing from this build")
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        // The cost of these is nothing and the alternative is arguing about
        // it later. A LAN page is still a page in a browser — and now so is
        // every other reply this server makes. See [securityHeaders].
        securityHeaders(ex)
        ex.sendResponseHeaders(200, html.size.toLong())
        ex.responseBody.use { it.write(html) }
    }

    /** One of [assets], straight from the jar. */
    private fun asset(ex: HttpExchange, path: String, type: String) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        val bytes = javaClass.getResourceAsStream("/web${path}")?.readBytes()
            ?: return respond(ex, 404, "missing from this build")
        ex.responseHeaders.add("Content-Type", type)
        securityHeaders(ex)
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
            "/api/state" -> respond(
                ex, 200,
                HubWeb.state(
                    store, tokens, dataDir, now(), index, screening, master, crawl, startedAt,
                    browsers = browsers, kidPort = kid.boundPort()
                )
            )

            /**
             * A backup a parent can take off the box.
             *
             * The stored document verbatim, in the same envelope the phone's
             * own export writes, because the day this file is wanted is the
             * day the NAS is gone and a phone is what is left. Verbatim and
             * not rebuilt: a field a newer build added and this one does not
             * model rides through, which is the same property the merge is
             * defined at the JSON level for.
             *
             * Keyless by construction rather than by a strip here —
             * `HubStore.commit` takes the API key out on every write, so
             * these are the bytes on disk. `HubBackupTest` asserts that from
             * the outside, at every depth, because "the hub's disk holds no
             * credential" is a claim a route like this can quietly break.
             *
             * The file is named by the browser: a filename with a date in it
             * would need a calendar, which the container deliberately does
             * not read (guard 27).
             */
            "/api/backup" -> {
                if (ex.requestMethod != "GET") return respond(ex, 405, "no")
                val raw = store.raw()
                    ?: return respond(ex, 404, JSONObject().put("error", "no config yet").toString())
                respond(ex, 200, BackupFile.wrap(raw, now(), "hub"))
            }

            /**
             * And back again — as a stamped edit, never a byte copy. See
             * `HubVersions`' own KDoc for the four separate arguments with the
             * merge a byte restore loses, silently, on the next sync.
             */
            "/api/restore" -> mutate(ex) { body ->
                JSONObject().put(
                    "restored",
                    HubVersions.restoreFile(store, WHO, now(), body.toString())
                )
            }

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
                // [browsers] goes in so a deleted child takes their claimed
                // browsers with them: a credential naming a profile that no
                // longer exists would otherwise keep playing under the family
                // default, which is the loosest rule set in the house.
                if (HubWeb.applyPatch(store, WHO, now(), body, browsers)) JSONObject().put("saved", true)
                else null
            }

            // The one config field with a route of its own, because it is the
            // one a patch would get wrong: a patch replaces the array it
            // names, and an entry left out of `grants` is expiry to the
            // stamper. So the browser says who, how long and which day, and
            // the hub appends one entry with an id it minted itself.
            "/api/grant" -> mutate(ex) { body ->
                val outcome = HubWeb.grant(
                    store, WHO, now(),
                    body.optString("kid"),
                    body.optInt("minutes", 0),
                    body.optString("date")
                )
                // Named rather than merely refused, like an assignment: "that
                // date is nowhere near this box's" and "that is not a number
                // of minutes" send a parent to different places.
                JSONObject()
                    .put("granted", outcome == HubWeb.Granted.OK)
                    .put("why", outcome.name)
            }

            /**
             * The AI key: set it, replace it, or clear it with a blank.
             *
             * A route of its own rather than a field of `/api/config`, for the
             * reason `ai.apiKey` is scrubbed out of every patch: the key is
             * not in the config document on this box and must not be put
             * there. It lands in `HubSecrets`, and `HubStore.setApiKey` moves
             * the `ai` unit's stamp so the merge can tell this rotation from
             * the copy a sleeping television still holds.
             *
             * The reply carries the last four characters and nothing else. The
             * value never comes back out of this hub except to a parent's
             * phone through `GET /config`.
             */
            "/api/ai-key" -> mutate(ex) { body ->
                if (!body.has("key")) null
                else {
                    // Bounded like anything else off the wire. A provider key
                    // is a couple of hundred characters at most, and the file
                    // this lands in is read on every merge.
                    val key = body.getString("key").trim().take(512)
                    store.setApiKey(key, WHO, now())
                    JSONObject().put("saved", true).put("tail", store.keyTail())
                }
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
                        val outcome = HubWeb.assignDevice(
                            store, tokens, WHO, now(),
                            a.getString("ref"), a.optString("kid")
                        )
                        // Named, not just refused: "that device has never
                        // called here" and "there is no such device" send a
                        // parent to different places, and the failure this
                        // replaces was one that said nothing at all.
                        JSONObject()
                            .put("assigned", outcome == HubWeb.Assigned.OK)
                            .put("why", outcome.name)
                    }
                    else -> null
                }
            }

            /**
             * Browsers that watch on the kid origin: mint a code for one, or
             * cut one off.
             *
             * The mint is here rather than over there because **only a parent
             * may say a browser is allowed and which child it is for**. That
             * is the same split `/enrol` and `/approve` already make: the kid
             * origin can redeem a code and can mint nothing at all, so a
             * tablet on the LAN cannot introduce itself to the family.
             *
             * The kid id is bound at this moment and never re-read from a
             * request afterwards — see `HubKidServer.media`, where the child
             * whose rules apply comes from the credential and the `kid=`
             * parameter no longer exists.
             *
             * The code comes back once, in this reply, for a parent to read
             * onto the tablet. It is not stored anywhere the page can fetch
             * again: a code sitting on `/api/state` for ten minutes is a code
             * every browser signed in as the parent keeps re-fetching.
             */
            "/api/browsers" -> mutate(ex) { body ->
                when {
                    body.has("claim") -> {
                        val outcome = HubWeb.mintClaim(store, browsers, body.getString("claim"), now())
                        // Always 200 with a reason, like /api/grant: "there is
                        // no such child" and "five codes are already out" send
                        // a parent to different places, and a bare 400 says
                        // neither.
                        JSONObject()
                            .put("minted", outcome.why == HubWeb.Minted.OK)
                            .put("code", outcome.code.orEmpty())
                            .put("why", outcome.why.name)
                            .put("ttlSeconds", HubBrowsers.CODE_TTL_MS / 1000)
                    }
                    body.has("revoke") -> JSONObject()
                        .put("revoked", browsers.revoke(body.getString("revoke")))
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

    // --- plumbing -------------------------------------------------------

    /**
     * The admin gate. **Every** presentation of the admin secret goes through
     * here, which is the point.
     *
     * Before this, /approve and /pending checked the header themselves with
     * no rate limit at all. Against 96 bits of hex that was harmless. Behind
     * a password it is an unmetered guessing oracle four threads wide, and
     * once a key derivation sits behind it, a processor exhaustion attack as
     * well. The throttle still runs before any derivation — that is what
     * bounds the cost of guessing to a handful of derivations per window —
     * but it suppresses the password rather than the whole gate, so the
     * recovery token stays usable. See the body.
     *
     * Returns what the secret turned out to be, or null after answering the
     * caller. The body of a request is read before this on the routes that
     * carry the secret in one; that read is already bounded to a megabyte and
     * the expensive half is the derivation, which is behind the gate.
     */
    private fun adminGate(ex: HttpExchange, given: String?): HubTokens.Secret? {
        val locked = !sessions.mayAttempt()
        // The lockout suppresses the PASSWORD, not the gate.
        //
        // This used to answer 429 before the secret was looked at, which made
        // the recovery token's documented exemption
        // ([HubTokens.verifyAdminSecret]'s `allowPassword`) unreachable: the
        // one credential that is supposed to stay usable while the password
        // path is locked was never compared, so anyone who wanted a family
        // locked out of their own hub only had to fail ten times a window,
        // for ever, and the only way back was the container log.
        //
        // Passing `allowPassword = !locked` is the whole fix and it weakens
        // nothing. A wrong password under lockout is still 429, and so is a
        // right one — the recovery token is 96 bits of hex, which a rate
        // limit does not protect, and it is compared with one constant-time
        // byte compare that derives no key. The cost of a refused attempt is
        // therefore back to what the throttle was written to bound: no KDF,
        // and one small file read that every allowed attempt already does.
        val kind = tokens.verifyAdminSecret(given, envAdminToken, allowPassword = !locked)
        if (locked && kind != HubTokens.Secret.RECOVERY) {
            val wait = sessions.retryAfterSeconds()
            ex.responseHeaders.add("Retry-After", wait.toString())
            respond(ex, 429, JSONObject().put("retryAfter", wait).toString())
            return null
        }
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

    /**
     * Liveness, and which build is answering.
     *
     * Unauthenticated, like the probe it has always been, and it still says
     * nothing about the family — a hub that is up and holds no config answers
     * exactly this, which is what a restart policy wants to know.
     *
     * The version is here because this is the one route anything can reach
     * before it holds a credential: the phone's `HubEnrolment.probe` already
     * calls it to decide whether an address is a hub at all. See
     * `hub/build.gradle.kts` for why a hub advertising its version matters at
     * all — the short of it is that an old image silently drops config keys
     * it does not model, and the number is what turns that from a discovery
     * into a check.
     *
     * `ok` stays in the body for the container healthcheck's sake and because
     * the reply used to be that bare word; it is now a JSON object, which is
     * what the `Content-Type` this server has always sent already claimed.
     */
    private fun health(ex: HttpExchange) {
        if (ex.requestMethod != "GET") return respond(ex, 405, "no")
        respond(
            ex, 200,
            JSONObject().put("ok", true).put("version", HubBuild.VERSION).toString()
        )
    }

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

    internal companion object {
        const val SESSION_COOKIE = "yk_session"

        /** How a hub edit is attributed in the change feed a parent reads. */
        const val WHO = "The hub"

        /**
         * The device's own socket read timeout, in the unit the JDK's server
         * takes. Ten seconds, because that is what `LanServer` gives an
         * accepted socket and there is no reason for the two boxes to have
         * different patience. See [applyRequestTimeout].
         */
        internal const val REQUEST_TIMEOUT_SECONDS = 10
        internal const val REQUEST_TIMEOUT_PROPERTY = "sun.net.httpserver.maxReqTime"

        /**
         * How many unauthenticated join attempts this hub will take in a
         * window, and how long that window is. See [enrol].
         *
         * Fifteen is not a round number chosen for feel; it sits between two
         * limits it must not cross, and both directions have a real failure:
         *
         * - **Above [HubSessions.MAX_ATTEMPTS].** `HubEnrolment.mint` calls
         *   `/enrol` and *then* `/approve`, so a parent mistyping the admin
         *   secret spends one join slot per attempt. Ten here would close
         *   this window on exactly the attempt the sign-in lockout closes on
         *   — and the phone reads any non-200 from `/enrol` as "that is not a
         *   Yosemite Kids hub", so they would be sent to check an address
         *   that was right all along instead of being told to wait. That is
         *   the failure the 429 case on `/approve` was added to fix, arriving
         *   by a different door. `:app`'s HubIntegrationTest caught it.
         * - **Below [HubTokens.MAX_PENDING].** The cap is the long-run bound
         *   on `devices.json`; this is the bound on the rate of getting
         *   there. If the cap bit first this number would never be reached
         *   and the throttle would be decoration.
         *
         * `HubServerTest.theJoinWindowSitsBetweenTheLockoutAndTheCap` states
         * both, because neither is visible from either end.
         */
        internal const val MAX_ENROLMENTS_PER_WINDOW = 15
        internal const val ENROLMENT_WINDOW_MS = 60 * 1000L

        /**
         * Routes a device answers and this hub deliberately does not.
         *
         * `"/"` is registered last and hands the admin page to anything
         * unclaimed, which is right for a parent's typo and wrong for a
         * device. A phone sweeps `/watchstate`, `/verdicts` and `/stats`
         * across **every** paired peer, the hub included, and each came back
         * 200 with a page of HTML: the watch-state and verdict mergers parsed
         * it to nothing, and `StatsCache` wrote index.html into the phone's
         * `files/stats_cache/` on every sweep, for ever. A 404 is a shape the
         * app already handles — it is what a device on a build older than a
         * route replies.
         *
         * `/verdicts` has since left this list, which is the shape the list is
         * meant to have: a route belongs here until the hub actually answers
         * it, and then it belongs above. The other two are still refusals —
         * `/watchstate` and `/stats` are device state, not family policy.
         *
         * This is `LanServer.handle`'s route list minus the ones this file
         * registers, and a guard in `scripts/check.*` holds it there: a route
         * added to a device and not thought about here would go quietly back
         * to being answered with the page.
         */
        val DEVICE_ONLY = setOf(
            "/admin-leave", "/admin-revoke", "/admins", "/check-updates",
            "/grant", "/join-hub", "/leave-hub", "/looks",
            "/pair-approve", "/pair-deny", "/pair-pending", "/pair-request",
            "/pair-status", "/play", "/player", "/stats",
            "/sync-now", "/watchstate"
        )

        /**
         * Refuse anything a browser on another site initiated.
         *
         * SameSite=Strict already stops the cookie riding along, but a page
         * that checks only the cookie is trusting the browser to have enforced
         * that. The same reasoning as /pair-request in the app, which refuses
         * any request carrying an Origin at all.
         *
         * On the companion, and **shared with [HubKidServer]**, because the
         * two listeners exist precisely so that this predicate answers false
         * across them: a fetch from the kid page to `/api/config` carries the
         * kid origin's `Origin:` and is refused here. Two copies of a security
         * check are two things to harden, and the second one is the one
         * somebody forgets — so there is one.
         */
        internal fun sameOrigin(ex: HttpExchange): Boolean {
            val origin = ex.requestHeaders.getFirst("Origin") ?: return true
            val host = ex.requestHeaders.getFirst("Host") ?: return false
            return origin.substringAfter("://") == host
        }

        /**
         * Drop a caller that opens a connection and then does not finish
         * asking.
         *
         * The device's `LanServer` gives every accepted socket `soTimeout =
         * 10_000`; this server had no equivalent, so a client that sent half a
         * request line held one of four worker threads until it felt like
         * leaving. Four of those is the hub, silently, with no log line — and
         * the route to it needs no token, because the request never gets far
         * enough to present one.
         *
         * The JDK's server exposes no socket to set a timeout on, so the
         * discipline goes on through the property its own `ServerConfig`
         * reads. That is read once per JVM, at the first `HttpServer.create`,
         * which is why this runs before it rather than in a constructor — and
         * why it is on the companion: [HubKidServer] creates a server of its
         * own and needs the same patience without depending on which listener
         * happened to start first.
         *
         * Only the REQUEST side. `maxRspTime` is deliberately left alone: a
         * device pulling a whole crawled source out of `/index` over a tired
         * wifi link is legitimately slow, and a response timer would cut
         * exactly the transfer that most needs to finish — and on the kid
         * origin it would cut a video. An explicit `-D` on the JVM wins, so a
         * NAS running something unusual can still tune it.
         */
        internal fun applyRequestTimeout() {
            if (System.getProperty(REQUEST_TIMEOUT_PROPERTY) == null) {
                System.setProperty(REQUEST_TIMEOUT_PROPERTY, REQUEST_TIMEOUT_SECONDS.toString())
            }
        }
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
                // And who it is. The token above is one this hub minted at
                // enrolment; this is the one the device itself resolves
                // config.deviceProfiles by, and the only way the hub can
                // learn it. Bounded, because it comes off the wire.
                ex.requestHeaders.getFirst("X-Device-Id")?.take(64),
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
        securityHeaders(ex)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /**
     * The three headers every response carries, whatever it is.
     *
     * They used to be on the admin page alone, which is backwards: the page
     * is the one response that is plainly HTML and plainly ours, while a JSON
     * error a browser was steered into fetching is the one a sniffing content
     * type or a framing attack has something to work with. There are exactly
     * three response paths in this file — this one, the page and the assets —
     * and guard 41 in scripts/check.* fails the build if a fourth appears
     * without calling here.
     *
     * A hub also has no business being framed at all: the whole GUI is a
     * family's configuration behind a session cookie, on plain HTTP, on a
     * LAN. DENY rather than SAMEORIGIN because nothing on this origin frames
     * anything.
     */
    private fun securityHeaders(ex: HttpExchange) {
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.add("X-Frame-Options", "DENY")
        ex.responseHeaders.add("Referrer-Policy", "no-referrer")
    }
}
