package io.yosemitekids.hub

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.KidPassword
import io.yosemitekids.app.data.Profile
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The wall between a child's page and the parents' console, on ONE origin.
 *
 * Two listeners used to make the two different browser origins. Now they
 * share one, and what keeps a page a child opened from spending a parent's
 * session is that no credential is ambient across the wall: the parents'
 * session is a header the console attaches and never a cookie; the kid's
 * cookie is scoped to `/kid/` and the console never reads it. Every test
 * here asks the hub over a socket, in the shapes a browser produces:
 *
 * - the kid routes live under /kid, and nothing else answers there;
 * - /login hands back a session in the body and sets no cookie, and a
 *   session presented as a cookie is nothing;
 * - a kid cookie is nothing on an admin route, and a session header is
 *   nothing on a kid route;
 * - a browser gets in by a parent's QR code or by the kid's own password,
 *   the password is throttled per kid, and neither door reaches the parents'
 *   lockout;
 * - no reply carries a CORS header, and a cross-site POST is refused.
 */
class HubKidBoundaryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val T = 1_780_000_000_000L
    private val ADMIN = "test-admin-token"
    private val VIDEO = "dQw4w9WgXcQ"

    private lateinit var store: HubStore
    private lateinit var server: HubServer
    private var port = 0
    private var kidId = ""
    private var now = T

    @Before
    fun setUp() {
        val dir = tmp.newFolder("data")
        store = HubStore(dir)
        server = HubServer(
            store, HubTokens(dir), 0, ADMIN,
            index = ChannelIndex(File(dir, "search-index"))
        ) { now }
        port = server.start()
        kidId = seed()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // --- one origin, two halves ------------------------------------------

    @Test
    fun `the kid routes live under kid and the console's routes do not answer there`() {
        for (path in listOf("/kid/api/state", "/kid/login", "/kid/setup", "/kid/health", "/kid/index.html")) {
            val (code, body) = call(path)
            assertEquals("$path answered with something other than the kid 404", 404, code)
            assertFalse(body.contains("<html"))
        }
        val page = call("/kid")
        assertEquals(200, page.first)
        assertTrue(page.second.contains("id=\"whoGrid\""))
    }

    @Test
    fun `the console still answers a parent's typo with its page and the kid page is not it`() {
        // Including a typo that shares the kid prefix: the JDK server matches
        // contexts by string prefix, so "/kids" arrives at the kid context and
        // must be handed back to the console rather than 404ed.
        for (path in listOf("/whatever", "/kids", "/kidney")) {
            val (code, body) = call(path)
            assertEquals("$path", 200, code)
            assertTrue("$path", body.contains("<html"))
            assertFalse("$path is the kid page", body.contains("id=\"whoGrid\""))
        }
    }

    @Test
    fun `the stylesheet is served once, at the root, for both pages`() {
        assertEquals(200, call("/kid-tokens.css").first)
        assertEquals(404, call("/kid/kid-tokens.css").first)
    }

    // --- the parents' session is a header ------------------------------------

    @Test
    fun `login answers with a session in the body and sets no cookie`() {
        val c = post("/login", JSONObject().put("secret", ADMIN).toString())
        assertEquals(200, c.responseCode)
        assertNull("a cookie is a credential the browser attaches on its own", c.getHeaderField("Set-Cookie"))
        val body = JSONObject(c.body())
        assertTrue(body.optString("session").length >= 16)
        c.disconnect()
    }

    @Test
    fun `a session in the header opens the api and the same session as a cookie does not`() {
        val session = signIn()
        assertEquals(200, call("/api/state", headers = mapOf(HubServer.SESSION_HEADER to session)).first)
        assertEquals(401, call("/api/state", headers = mapOf("Cookie" to "yk_session=$session")).first)
        assertEquals(401, call("/api/state").first)
    }

    @Test
    fun `logout closes the session named in the header`() {
        val session = signIn()
        val c = post("/logout", "{}", mapOf(HubServer.SESSION_HEADER to session))
        assertEquals(200, c.responseCode)
        c.disconnect()
        assertEquals(401, call("/api/state", headers = mapOf(HubServer.SESSION_HEADER to session)).first)
    }

    // --- the kid's cookie ----------------------------------------------------

    @Test
    fun `a parent mints a code and the QR's URL redeems it for a cookie scoped to kid`() {
        val session = signIn()
        val minted = JSONObject(
            call("/api/browsers", "POST", JSONObject().put("claim", kidId).toString(), mapOf(HubServer.SESSION_HEADER to session)).second
        )
        assertTrue(minted.getBoolean("minted"))
        assertTrue("the QR carries the kid path on this origin", minted.getString("url").endsWith("/kid?c=" + minted.getString("code")))
        assertTrue(minted.getString("qr").startsWith("<svg"))

        val c = post("/kid/claim", JSONObject().put("code", minted.getString("code")).toString())
        assertEquals(200, c.responseCode)
        val set = c.getHeaderField("Set-Cookie")
        c.disconnect()
        assertNotNull(set)
        assertTrue(set.startsWith("yk_kid="))
        assertTrue("scoped to the kid routes and nothing else", set.contains("Path=/kid/"))
        assertTrue(set.contains("HttpOnly"))

        val cookie = set.substringBefore(";")
        val who = JSONObject(call("/kid/whoami", headers = mapOf("Cookie" to cookie)).second)
        assertEquals(kidId, who.getString("kid"))
    }

    @Test
    fun `a kid cookie is nothing on an admin route and a session is nothing on a kid route`() {
        val cookie = claimByCode()
        for (path in listOf("/api/state", "/api/config", "/api/browsers")) {
            assertEquals("$path let a kid cookie in", 401, call(path, headers = mapOf("Cookie" to cookie)).first)
        }
        val session = signIn()
        for (path in listOf("/kid/whoami", "/kid/home", "/kid/you", "/kid/channels")) {
            assertEquals("$path let a parent's session in", 401, call(path, headers = mapOf(HubServer.SESSION_HEADER to session)).first)
            assertEquals("$path let a parent's session in as a cookie", 401, call(path, headers = mapOf("Cookie" to "yk_session=$session")).first)
        }
    }

    @Test
    fun `a code is single use`() {
        val session = signIn()
        val code = JSONObject(
            call("/api/browsers", "POST", JSONObject().put("claim", kidId).toString(), mapOf(HubServer.SESSION_HEADER to session)).second
        ).getString("code")
        assertEquals(200, post("/kid/claim", JSONObject().put("code", code).toString()).responseCode)
        assertEquals(409, post("/kid/claim", JSONObject().put("code", code).toString()).responseCode)
    }

    // --- the kid's password -------------------------------------------------

    @Test
    fun `a kid with a password appears on the sign-in screen and one without does not`() {
        val before = JSONObject(call("/kid/kids").second).getJSONArray("kids")
        assertEquals(0, before.length())

        setPassword("otter")
        val after = JSONObject(call("/kid/kids").second).getJSONArray("kids")
        assertEquals(1, after.length())
        assertEquals("Leo", after.getJSONObject(0).getString("name"))
        assertFalse("nothing but a name and a face", after.getJSONObject(0).has("web"))
        assertFalse(after.getJSONObject(0).has("limits"))
    }

    @Test
    fun `the right password lets a browser in as that kid and the wrong one does not`() {
        setPassword("otter")
        val wrong = post("/kid/claim", JSONObject().put("kid", kidId).put("password", "badger").toString())
        assertEquals(409, wrong.responseCode)
        assertEquals("WRONG_PASSWORD", JSONObject(wrong.body()).getString("refused"))
        assertNull(wrong.getHeaderField("Set-Cookie"))

        val right = post("/kid/claim", JSONObject().put("kid", kidId).put("password", "otter").toString())
        assertEquals(200, right.responseCode)
        val cookie = right.getHeaderField("Set-Cookie").substringBefore(";")
        assertEquals(kidId, JSONObject(call("/kid/whoami", headers = mapOf("Cookie" to cookie)).second).getString("kid"))
    }

    @Test
    fun `a kid with no password cannot be signed in as with any password`() {
        val c = post("/kid/claim", JSONObject().put("kid", kidId).put("password", "").toString())
        assertEquals(409, c.responseCode)
        assertEquals(409, post("/kid/claim", JSONObject().put("kid", kidId).put("password", "anything").toString()).responseCode)
    }

    @Test
    fun `guessing one kid's password locks that kid for a while and nobody else`() {
        setPassword("otter")
        val sibling = seed("Mia", "k2")
        setPassword("badger", sibling)
        repeat(HubKidLock.MAX_FAILURES) {
            assertEquals(409, post("/kid/claim", JSONObject().put("kid", kidId).put("password", "nope").toString()).responseCode)
        }
        val locked = post("/kid/claim", JSONObject().put("kid", kidId).put("password", "otter").toString())
        assertEquals("the right answer is refused while the lock holds", 429, locked.responseCode)
        assertNotNull(locked.getHeaderField("Retry-After"))
        // The sibling is untouched, and so is the parent.
        assertEquals(200, post("/kid/claim", JSONObject().put("kid", sibling).put("password", "badger").toString()).responseCode)
        assertEquals(200, post("/login", JSONObject().put("secret", ADMIN).toString()).responseCode)
        // And the lock lifts.
        now += HubKidLock.FIRST_LOCK_MS + 1
        assertEquals(200, post("/kid/claim", JSONObject().put("kid", kidId).put("password", "otter").toString()).responseCode)
    }

    @Test
    fun `a child failing their password all afternoon does not lock the parent out`() {
        setPassword("otter")
        repeat(HubSessions.MAX_ATTEMPTS + 2) {
            post("/kid/claim", JSONObject().put("kid", kidId).put("password", "nope").toString()).disconnect()
        }
        assertEquals(200, post("/login", JSONObject().put("secret", ADMIN).toString()).responseCode)
    }

    @Test
    fun `the console sets and clears a kid's password and the phone can verify what it set`() {
        val session = signIn()
        val set = JSONObject(
            call("/api/kid-password", "POST", JSONObject().put("kid", kidId).put("password", "otter").toString(), mapOf(HubServer.SESSION_HEADER to session)).second
        )
        assertTrue(set.getBoolean("saved"))
        assertTrue(KidPassword.verify(store.load().profile(kidId)!!.webPassword, "otter"))

        val short = JSONObject(
            call("/api/kid-password", "POST", JSONObject().put("kid", kidId).put("password", "ab").toString(), mapOf(HubServer.SESSION_HEADER to session)).second
        )
        assertFalse(short.getBoolean("saved"))
        assertEquals("TOO_SHORT", short.getString("why"))

        val cleared = JSONObject(
            call("/api/kid-password", "POST", JSONObject().put("kid", kidId).put("password", "").toString(), mapOf(HubServer.SESSION_HEADER to session)).second
        )
        assertTrue(cleared.getBoolean("saved"))
        assertNull(store.load().profile(kidId)!!.webPassword)
        assertEquals(401, call("/api/kid-password", "POST", "{}").first)
    }

    // --- the browser's own policy ---------------------------------------------

    @Test
    fun `no reply carries a CORS header`() {
        for (path in listOf("/kid", "/kid/whoami", "/kid/kids", "/api/state", "/setup")) {
            assertNull("$path told a browser it may read it", cors(URL("http://127.0.0.1:$port$path")))
        }
    }

    @Test
    fun `a cross-site POST is refused on both halves`() {
        assertEquals(403, rawStatus("POST /kid/claim HTTP/1.1", "Origin: http://evil.example", "Content-Length: 2", "", "{}"))
        val session = signIn()
        assertEquals(403, rawStatus("GET /api/state HTTP/1.1", "Origin: http://evil.example", "${HubServer.SESSION_HEADER}: $session", ""))
        assertEquals(200, rawStatus("GET /api/state HTTP/1.1", "Origin: http://127.0.0.1:$port", "${HubServer.SESSION_HEADER}: $session", ""))
    }

    @Test
    fun `every kid reply carries the baseline security headers`() {
        for (path in listOf("/kid", "/kid/kids", "/kid/whoami", "/kid/manifest.webmanifest")) {
            val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
            c.responseCode
            assertEquals("$path", "nosniff", c.getHeaderField("X-Content-Type-Options"))
            assertEquals("$path", "DENY", c.getHeaderField("X-Frame-Options"))
            assertEquals("$path", "no-referrer", c.getHeaderField("Referrer-Policy"))
            c.disconnect()
        }
    }

    @Test
    fun `the kid manifest and its icons are rooted at kid`() {
        val m = JSONObject(call("/kid/manifest.webmanifest").second)
        assertEquals("/kid", m.getString("start_url"))
        assertEquals("/kid", m.getString("scope"))
        assertTrue(m.getJSONArray("icons").getJSONObject(0).getString("src").startsWith("/kid/icon?"))
        assertEquals(200, call("/kid/icon?s=192").first)
    }

    @Test
    fun `a body far over the cap is refused`() {
        val c = post("/kid/claim", "{\"code\": \"" + "A".repeat(8 * 1024) + "\"}")
        assertEquals(413, c.responseCode)
        c.disconnect()
    }

    @Test
    fun `a revoked browser stops watching at once`() {
        val cookie = claimByCode()
        assertEquals(200, call("/kid/whoami", headers = mapOf("Cookie" to cookie)).first)
        val session = signIn()
        val ref = JSONObject(call("/api/state", headers = mapOf(HubServer.SESSION_HEADER to session)).second)
            .getJSONArray("browsers").getJSONObject(0).getString("ref")
        call("/api/browsers", "POST", JSONObject().put("revoke", ref).toString(), mapOf(HubServer.SESSION_HEADER to session))
        assertEquals(401, call("/kid/whoami", headers = mapOf("Cookie" to cookie)).first)
    }

    // --- helpers --------------------------------------------------------------

    /** A kid, added the way the console adds one: a stamped edit, so a second kid merges cleanly. */
    private fun seed(name: String = "Leo", id: String = "k1"): String {
        store.edit("test", now) { current ->
            current.copy(
                sources = current.sources.ifEmpty {
                    listOf(
                        io.yosemitekids.app.data.WhitelistEntry(
                            "UCtest000000000000000000", "https://www.youtube.com/channel/UCtest000000000000000000",
                            "Test", io.yosemitekids.app.data.SourceKind.CHANNEL
                        )
                    )
                },
                profiles = current.profiles + Profile(id = id, name = name)
            )
        }
        assertNotNull(store.load().profile(id))
        return id
    }

    private fun setPassword(password: String, kid: String = kidId) {
        store.edit("test", now) { w ->
            w.copy(profiles = w.profiles.map { if (it.id == kid) it.copy(webPassword = KidPassword.record(password, now)) else it })
        }
    }

    private fun signIn(): String {
        val c = post("/login", JSONObject().put("secret", ADMIN).toString())
        assertEquals(200, c.responseCode)
        val session = JSONObject(c.body()).getString("session")
        c.disconnect()
        return session
    }

    private fun claimByCode(): String {
        val code = server.browsers().mint(kidId, now)!!
        val c = post("/kid/claim", JSONObject().put("code", code).toString())
        assertEquals(200, c.responseCode)
        val set = c.getHeaderField("Set-Cookie")
        c.disconnect()
        return set.substringBefore(";")
    }

    private fun HttpURLConnection.body(): String =
        (if (responseCode in 200..299) inputStream else errorStream)?.bufferedReader()?.readText().orEmpty()

    private fun post(path: String, body: String, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.setRequestProperty("Content-Type", "application/json")
        c.doOutput = true
        c.outputStream.use { it.write(body.toByteArray()) }
        c.responseCode
        return c
    }

    private fun call(
        path: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = c.body()
        c.disconnect()
        return code to text
    }

    /** Over a raw socket: HttpURLConnection silently drops Origin. */
    private fun rawStatus(vararg lines: String): Int {
        java.net.Socket("127.0.0.1", port).use { s ->
            val out = s.getOutputStream()
            val all = listOf(lines[0], "Host: 127.0.0.1:$port") + lines.drop(1)
            // A blank line separates headers from the body; anything after it is the body.
            val text = all.joinToString("\r\n") + (if (lines.last().isEmpty()) "\r\n" else "")
            out.write(text.toByteArray())
            out.flush()
            val status = s.getInputStream().bufferedReader().readLine()
            return status.split(" ")[1].toInt()
        }
    }

    private fun cors(url: URL): String? {
        val c = url.openConnection() as HttpURLConnection
        c.setRequestProperty("Origin", "http://example.invalid")
        c.responseCode
        val header = c.getHeaderField("Access-Control-Allow-Origin")
        c.disconnect()
        return header
    }
}
