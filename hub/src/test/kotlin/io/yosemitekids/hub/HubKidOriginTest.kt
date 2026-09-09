package io.yosemitekids.hub

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.SyncMeta
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The two origins, over two real sockets.
 *
 * This is the file that has to be believed, because the whole web player rests
 * on one claim: **a page a child opens cannot reach the family's
 * configuration.** A unit test of a helper cannot state that. What states it
 * is asking the two listeners, from outside, in the shapes a browser would
 * actually produce:
 *
 * - the kid origin does not answer `/api/state`, `/login` or the console;
 * - the admin origin does not answer `/claim`, `/whoami` or `/media`;
 * - a kid cookie is nothing on an admin route, and an admin session is nothing
 *   on a kid route;
 * - a cross-origin POST carrying the kid origin's `Origin:` is refused by the
 *   admin origin, which is the exact request a compromised kid page would
 *   make;
 * - a child failing their code all afternoon does not lock their parent out.
 */
class HubKidOriginTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val T = 1_780_000_000_000L
    private val ADMIN = "test-admin-token"
    private val VIDEO = "dQw4w9WgXcQ"

    private lateinit var store: HubStore
    private lateinit var server: HubServer
    private var port = 0
    private var kidPort = 0
    private var kidId = ""

    @Before
    fun setUp() {
        val dir = tmp.newFolder("data")
        store = HubStore(dir)
        server = HubServer(
            store, HubTokens(dir), 0, ADMIN,
            index = ChannelIndex(File(dir, "search-index"))
        ) { T }
        port = server.start()
        kidPort = server.kidPort()
        kidId = seed()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // --- two origins, not two paths -------------------------------------

    @Test
    fun `they are actually two listeners`() {
        assertNotEquals(0, kidPort)
        assertNotEquals(port, kidPort)
    }

    @Test
    fun `the kid origin answers nothing the console does`() {
        // Every one of these is a 200 on the admin origin. Here they are 404s
        // — not the kid page with a 200, which is what a catch-all would give
        // and what would make this boundary a matter of opinion.
        for (path in listOf("/api/state", "/api/config", "/login", "/logout", "/setup", "/health", "/status")) {
            assertEquals("kid origin answered $path", 404, kid(path).first)
        }
        // And the console's own front door is not here either.
        val page = kid("/index.html")
        assertEquals(404, page.first)
        assertFalse(page.second.contains("<html"))
    }

    @Test
    fun `the admin origin answers nothing the kid page does`() {
        // "/" on the admin origin is the console, so an unknown path there is
        // the page by design. What must not happen is the kid ROUTES working:
        // /claim minting a cookie on the parents' origin, or /media serving a
        // video to whatever is holding a session there.
        val claim = admin("/claim", method = "POST", body = JSONObject().put("code", "AAAAAA").toString())
        assertNull("the admin origin minted a claim cookie", claim.third)
        // It answers these three with its own page — 200 and HTML, the
        // catch-all a parent's typo gets — and never with a kid answer. What
        // matters is what is NOT in it: no cookie above, and no JSON body
        // naming a child or carrying a video below.
        for (path in listOf("/whoami", "/media?v=$VIDEO")) {
            val (code, body) = admin(path)
            assertEquals(200, code)
            assertTrue("the admin origin answered $path with something other than its page", body.contains("<html"))
        }
    }

    @Test
    fun `the stylesheet is the one thing both origins serve`() {
        // Deliberate, and the only overlap: it carries no family data, guard
        // 48 requires the console to serve it, and the kid page cannot be
        // styled from an origin it may not read.
        assertEquals(200, kid("/kid-tokens.css").first)
        assertEquals(200, admin("/kid-tokens.css").first)
    }

    // --- the claim ------------------------------------------------------

    @Test
    fun `a parent mints a code and a browser trades it for a cookie`() {
        val session = signIn()
        val minted = JSONObject(
            admin("/api/browsers", method = "POST", body = JSONObject().put("claim", kidId).toString(), cookie = session).second
        )
        assertTrue(minted.getBoolean("minted"))
        val code = minted.getString("code")
        assertEquals(HubBrowsers.CODE_LENGTH, code.length)

        val redeemed = kidRaw("/claim", method = "POST", body = JSONObject().put("code", code).toString())
        assertEquals(200, redeemed.responseCode)
        val setCookie = redeemed.getHeaderField("Set-Cookie")
        assertTrue(setCookie.startsWith("${HubKidServer.CLAIM_COOKIE}="))
        assertTrue(setCookie.contains("HttpOnly"))
        assertTrue(setCookie.contains("SameSite=Strict"))

        // And the cookie names the child the PARENT chose, not one the browser
        // asked for.
        val cookie = setCookie.substringBefore(";")
        val who = JSONObject(kid("/whoami", cookie = cookie).second)
        assertEquals(kidId, who.getString("kid"))
        assertEquals("Ada", who.getString("name"))
    }

    @Test
    fun `a code is single use`() {
        val code = server.browsers().mint(kidId, T)!!
        assertEquals(200, kidRaw("/claim", method = "POST", body = JSONObject().put("code", code).toString()).responseCode)
        assertEquals(409, kidRaw("/claim", method = "POST", body = JSONObject().put("code", code).toString()).responseCode)
    }

    @Test
    fun `a browser cannot be claimed for a kid this hub has never heard of`() {
        // The credential names a child, and a child nobody has heard of would
        // watch under the family default — the loosest rules in the house.
        val session = signIn()
        val body = JSONObject(
            admin("/api/browsers", method = "POST", body = JSONObject().put("claim", "ffffffff").toString(), cookie = session).second
        )
        assertFalse(body.getBoolean("minted"))
        assertEquals("BAD_KID", body.getString("why"))
        assertEquals("", body.getString("code"))
    }

    @Test
    fun `minting needs the parent's session`() {
        // Without it, anything on the LAN could mint itself a credential.
        assertEquals(
            401,
            admin("/api/browsers", method = "POST", body = JSONObject().put("claim", kidId).toString()).first
        )
    }

    // --- neither credential works on the other origin --------------------

    @Test
    fun `a kid cookie is nothing on an admin route`() {
        val cookie = claimCookie()
        // Presented as a session cookie, which is the closest thing to an
        // attack this shape allows: same value, admin's cookie name.
        val asSession = "${HubServer.SESSION_COOKIE}=" + cookie.substringAfter("=")
        assertEquals(401, admin("/api/state", cookie = asSession).first)
        assertEquals(401, admin("/api/state", cookie = cookie).first)
        assertEquals(
            401,
            admin(
                "/api/config", method = "POST",
                body = JSONObject().put("blocked", org.json.JSONArray()).toString(),
                cookie = asSession
            ).first
        )
    }

    @Test
    fun `an admin session is nothing on a kid route`() {
        val session = signIn()
        assertEquals(200, admin("/api/state", cookie = session).first)
        val asClaim = "${HubKidServer.CLAIM_COOKIE}=" + session.substringAfter("=")
        assertEquals(401, kid("/whoami", cookie = session).first)
        assertEquals(401, kid("/whoami", cookie = asClaim).first)
        assertEquals(401, kid("/media?v=$VIDEO", cookie = asClaim).first)
    }

    @Test
    fun `a cross-origin POST from the kid page is refused by the console`() {
        // The whole argument for a second port, as a request: exactly what a
        // script on the kid page would send at /api/config, with the parent's
        // session cookie riding along because the browser attaches it. On ONE
        // origin this is a 200 and the family's block list has been rewritten
        // by a page a child opened.
        //
        // Over a raw socket, because HttpURLConnection silently drops Origin —
        // it is on the JDK's restricted-header list — and a cross-site test
        // written through it passes while proving nothing.
        val session = signIn()
        val patch = JSONObject().put("blocked", org.json.JSONArray()).toString()
        assertEquals(
            403,
            raw(
                port, "POST /api/config HTTP/1.1",
                listOf(
                    "Host: 127.0.0.1:$port",
                    "Origin: http://127.0.0.1:$kidPort",
                    "Cookie: $session"
                ),
                patch
            )
        )
        // And the same request from the console's own origin is allowed, so
        // the refusal above is about the Origin and not about the raw socket.
        assertEquals(
            200,
            raw(
                port, "POST /api/config HTTP/1.1",
                listOf(
                    "Host: 127.0.0.1:$port",
                    "Origin: http://127.0.0.1:$port",
                    "Cookie: $session"
                ),
                patch
            )
        )
    }

    @Test
    fun `the kid origin refuses a cross-origin claim too`() {
        // Both ways. A page on the console's origin must not be able to spend
        // a code either — the boundary is not one-directional.
        val code = server.browsers().mint(kidId, T)!!
        assertEquals(
            403,
            raw(
                kidPort, "POST /claim HTTP/1.1",
                listOf("Host: 127.0.0.1:$kidPort", "Origin: http://127.0.0.1:$port"),
                JSONObject().put("code", code).toString()
            )
        )
        // The code is untouched: a refusal before the throttle and before the
        // store, so a page next door cannot burn a child's code either.
        assertEquals(
            200,
            raw(
                kidPort, "POST /claim HTTP/1.1",
                listOf("Host: 127.0.0.1:$kidPort", "Origin: http://127.0.0.1:$kidPort"),
                JSONObject().put("code", code).toString()
            )
        )
    }

    @Test
    fun `no reply from either origin lets the other read it`() {
        // The second half of the same argument: even where a request is not
        // refused, the browser must not hand the body back. No CORS header
        // anywhere is what makes that true, and guard 58 keeps it true.
        for (url in listOf("http://127.0.0.1:$kidPort/", "http://127.0.0.1:$kidPort/whoami",
            "http://127.0.0.1:$kidPort/kid-tokens.css", "http://127.0.0.1:$port/health",
            "http://127.0.0.1:$port/")) {
            assertNull("$url allows a foreign origin to read it", cors(URL(url)))
        }
    }

    // --- the throttle a child cannot lock a parent out with ---------------

    @Test
    fun `a child failing their code all afternoon does not lock the parent out`() {
        // The reason this listener counts its own attempts. With one shared
        // counter, ten wrong codes from a six-year-old would be a parent
        // locked out of their own hub for fifteen minutes, then thirty, then
        // an hour.
        repeat(HubKidServer.MAX_CLAIMS_PER_WINDOW) {
            val r = kidRaw("/claim", method = "POST", body = JSONObject().put("code", "ZZZZZZ").toString())
            assertEquals(409, r.responseCode)
        }
        // Past the window the kid origin refuses — and says how long for.
        val over = kidRaw("/claim", method = "POST", body = JSONObject().put("code", "ZZZZZZ").toString())
        assertEquals(429, over.responseCode)
        assertNotNull(over.getHeaderField("Retry-After"))

        // And the parent signs in, on the other origin, unaffected.
        assertNotNull(signIn())
    }

    @Test
    fun `the two throttles are not the same object`() {
        // Stated from the other direction too: the admin lockout must not
        // close the kid's door either, or a parent mistyping their password
        // would stop the children watching.
        repeat(HubSessions.MAX_ATTEMPTS + 1) {
            admin("/login", method = "POST", body = JSONObject().put("secret", "wrong").toString())
        }
        val code = server.browsers().mint(kidId, T)!!
        assertEquals(
            200,
            kidRaw("/claim", method = "POST", body = JSONObject().put("code", code).toString()).responseCode
        )
    }

    // --- the page -------------------------------------------------------

    @Test
    fun `the kid page is served at the root and says nothing about the family`() {
        val (code, body) = kid("/")
        assertEquals(200, code)
        assertTrue(body.contains("Type your code"))
        // No name, no video, no configuration: everything the child sees
        // arrives afterwards from /whoami, behind the cookie.
        assertFalse(body.contains("Ada"))
        assertFalse(body.contains(kidId))
    }

    @Test
    fun `every kid reply carries the baseline security headers`() {
        for (path in listOf("/", "/kid-tokens.css", "/whoami", "/nope")) {
            val c = kidRaw(path)
            assertEquals("nosniff", c.getHeaderField("X-Content-Type-Options"))
            assertEquals("DENY", c.getHeaderField("X-Frame-Options"))
            assertEquals("no-referrer", c.getHeaderField("Referrer-Policy"))
            c.disconnect()
        }
    }

    @Test
    fun `a body far over the cap is refused rather than read`() {
        // This listener faces the whole house before any credential is
        // checked, so it reads the way LanServer does. The server answers 413
        // from the Content-Length alone and never reads the kilobytes; the
        // client then fails mid-write, so either outcome is the refusal
        // working.
        val huge = "x".repeat(64 * 1024)
        val refused = runCatching {
            kid("/claim", method = "POST", body = JSONObject().put("code", huge).toString()).first
        }.fold({ it == 413 }, { true })
        assertTrue("an oversized claim body must be refused", refused)
    }

    // --- revoke ---------------------------------------------------------

    @Test
    fun `a revoked browser stops watching at once`() {
        val cookie = claimCookie()
        assertEquals(200, kid("/whoami", cookie = cookie).first)

        val session = signIn()
        val ref = JSONObject(admin("/api/state", cookie = session).second)
            .getJSONArray("browsers").getJSONObject(0).getString("ref")
        val revoked = JSONObject(
            admin("/api/browsers", method = "POST", body = JSONObject().put("revoke", ref).toString(), cookie = session).second
        )
        assertTrue(revoked.getBoolean("revoked"))

        assertEquals(401, kid("/whoami", cookie = cookie).first)
        assertEquals(401, kid("/media?v=$VIDEO", cookie = cookie).first)
    }

    @Test
    fun `deleting a kid takes their browsers with them`() {
        // A credential names a profile, and limitsFor an unknown kid is the
        // FAMILY DEFAULT — the loosest rules in the house. A deleted child's
        // tablet carrying on under them is the failure this closes, and it is
        // one nothing would have thrown over.
        val cookie = claimCookie()
        assertEquals(200, kid("/whoami", cookie = cookie).first)

        val session = signIn()
        assertEquals(
            200,
            admin(
                "/api/config", method = "POST",
                body = JSONObject().put("profiles", org.json.JSONArray()).toString(),
                cookie = session
            ).first
        )
        assertEquals(401, kid("/whoami", cookie = cookie).first)
    }

    // --- plumbing -------------------------------------------------------

    /** One kid, one channel, and a zone, so the policy has something to say. */
    private fun seed(): String {
        val id = "abc12345"
        val json = ConfigJson.toJson(
            Whitelist(
                sources = listOf(
                    WhitelistEntry("UC1", "https://www.youtube.com/channel/UC1", "UC1", SourceKind.CHANNEL)
                ),
                blockedVideoIds = emptySet(),
                profiles = listOf(Profile(id = id, name = "Ada")),
                homeZone = "Pacific/Auckland",
                sync = SyncMeta(docAt = T, at = mapOf(ConfigStamp.src("UC1") to T))
            )
        )
        assertNotNull(store.merge(json, "test"))
        return id
    }

    private fun claimCookie(): String {
        val code = server.browsers().mint(kidId, T)!!
        val c = kidRaw("/claim", method = "POST", body = JSONObject().put("code", code).toString())
        assertEquals(200, c.responseCode)
        val set = c.getHeaderField("Set-Cookie")
        c.disconnect()
        return set.substringBefore(";")
    }

    private fun signIn(): String {
        val c = URL("http://127.0.0.1:$port/login").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.outputStream.use { it.write(JSONObject().put("secret", ADMIN).toString().toByteArray()) }
        assertEquals(200, c.responseCode)
        val set = c.getHeaderField("Set-Cookie")
        c.disconnect()
        return set.substringBefore(";")
    }

    private fun kidRaw(
        path: String,
        method: String = "GET",
        body: String? = null,
        cookie: String? = null
    ): HttpURLConnection = call(URL("http://127.0.0.1:$kidPort$path"), method, body, cookie)

    private fun call(
        url: URL,
        method: String,
        body: String?,
        cookie: String?
    ): HttpURLConnection {
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = method
        cookie?.let { c.setRequestProperty("Cookie", it) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        c.responseCode
        return c
    }

    /** Status, body, and the `Set-Cookie` — the third is what a claim proves. */
    private fun read(c: HttpURLConnection): Triple<Int, String, String?> {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.readBytes()?.decodeToString().orEmpty()
        val set = c.getHeaderField("Set-Cookie")
        c.disconnect()
        return Triple(code, text, set)
    }

    private fun kid(
        path: String,
        method: String = "GET",
        body: String? = null,
        cookie: String? = null
    ): Triple<Int, String, String?> = read(kidRaw(path, method, body, cookie))

    private fun admin(
        path: String,
        method: String = "GET",
        body: String? = null,
        cookie: String? = null
    ): Triple<Int, String, String?> =
        read(call(URL("http://127.0.0.1:$port$path"), method, body, cookie))

    /**
     * A request over a raw socket, to whichever origin.
     *
     * `HttpURLConnection` silently drops `Origin` — it is on the JDK's
     * restricted-header list — so a cross-site test written through that
     * connection passes while proving nothing, which is worse than not having
     * it. `HubServerTest` and `HubWebTest` both learned this the same way.
     */
    private fun raw(
        onPort: Int,
        requestLine: String,
        headers: List<String>,
        body: String? = null
    ): Int = java.net.Socket("127.0.0.1", onPort).use { socket ->
        val all = headers.toMutableList()
        if (body != null) {
            all += "Content-Type: application/json"
            all += "Content-Length: ${body.toByteArray().size}"
        }
        all += "Connection: close"
        val request = (listOf(requestLine) + all + listOf("", body.orEmpty())).joinToString("\r\n")
        socket.getOutputStream().apply { write(request.toByteArray()); flush() }
        socket.getInputStream().bufferedReader().readLine().orEmpty()
            .split(" ").getOrNull(1)?.toIntOrNull() ?: -1
    }

    /** Any CORS header at all, which there must never be. */
    private fun cors(url: URL): String? {
        val c = url.openConnection() as HttpURLConnection
        c.setRequestProperty("Origin", "http://example.invalid")
        c.responseCode
        val header = c.getHeaderField("Access-Control-Allow-Origin")
        c.disconnect()
        return header
    }
}
