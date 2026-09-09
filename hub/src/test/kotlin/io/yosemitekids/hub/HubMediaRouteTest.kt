package io.yosemitekids.hub

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.SyncMeta
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * `GET /media` over a real socket — the door, not the arithmetic.
 *
 * On the **kid origin** now, and gated on the claim cookie rather than on a
 * parent's session: the thing that plays video is a child's browser, and a
 * child's browser must not be on the origin that holds the console (see
 * `HubKidServer`).
 *
 * The order of the checks inside that route is the design, and this is what
 * holds it. Two of these tests would pass just as happily if the route
 * resolved a stream first and asked the rules afterwards; the third would not,
 * and it is the one that matters. This JVM has never called `Extractor.init()`
 * and has no network, so **any** resolve in here fails — which means a 403
 * carrying a policy reason code is proof that nothing was resolved. A route
 * that fetched first would answer 502.
 */
class HubMediaRouteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val T = 1_780_000_000_000L
    private val ADMIN = "test-admin-token"
    private val VIDEO = "dQw4w9WgXcQ"
    private val KID = "abc12345"

    private lateinit var store: HubStore
    private lateinit var server: HubServer
    private var port = 0
    private var kidPort = 0

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
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // --- the door -------------------------------------------------------

    @Test
    fun `media is refused without a claim`() {
        // This is the one route that costs a family's uplink. An unclaimed
        // caller must not be able to make the box fetch anything at all.
        val (code, body) = get("/media?v=$VIDEO")
        assertEquals(401, code)
        assertEquals("claim", JSONObject(body).getString("error"))
    }

    @Test
    fun `only GET carries media`() {
        assertEquals(405, get("/media?v=$VIDEO", method = "POST", cookie = claim()).first)
    }

    @Test
    fun `a video id that is not a video id is refused before anything else`() {
        val cookie = claim()
        assertEquals(400, get("/media?v=nope", cookie = cookie).first)
        assertEquals(400, get("/media", cookie = cookie).first)
    }

    // --- the rules, first -----------------------------------------------

    @Test
    fun `the gate answers before anything is resolved`() {
        // A parent has blocked this video. The route says so with the policy's
        // own reason code — and it says so without a single byte being
        // fetched. If it resolved first this would be a 502, because there is
        // no extractor and no network in this JVM.
        seed(blocked = setOf(VIDEO))
        val (code, body) = get("/media?v=$VIDEO", cookie = claim())
        assertEquals(403, code)
        assertEquals(HubPolicy.BLOCKED, JSONObject(body).getString("error"))
        assertTrue(JSONObject(body).getString("detail").isNotEmpty())
    }

    @Test
    fun `a video this hub never indexed is refused by a different name`() {
        // "not yours" and "never heard of it" are two answers a parent has to
        // be able to tell apart, and the route passes the distinction through
        // rather than flattening every no into one.
        seed()
        val (code, body) = get("/media?v=$VIDEO", cookie = claim())
        assertEquals(403, code)
        assertEquals(HubPolicy.UNKNOWN_VIDEO, JSONObject(body).getString("error"))
    }

    @Test
    fun `a hub with no config at all still answers with the rules, never a fetch`() {
        val (code, body) = get("/media?v=$VIDEO", cookie = claim())
        assertEquals(403, code)
        // Whichever no it is, it is HubPolicy's and not the network's.
        assertTrue(
            JSONObject(body).getString("error") in
                setOf(HubPolicy.NO_CONFIG, HubPolicy.UNKNOWN_VIDEO)
        )
    }

    // --- how many at once -----------------------------------------------

    @Test
    fun `past the cap a stream is refused, not queued`() {
        val cookie = claim()
        val slots = server.mediaSlots()
        repeat(HubKidServer.MAX_CONCURRENT_STREAMS) { assertTrue(slots.take()) }
        val c = raw("/media?v=$VIDEO", cookie)
        assertEquals(503, c.responseCode)
        assertEquals("5", c.getHeaderField("Retry-After"))
        val body = c.errorStream.readBytes().decodeToString()
        assertEquals("busy", JSONObject(body).getString("error"))
        assertEquals(HubKidServer.MAX_CONCURRENT_STREAMS, JSONObject(body).getInt("streams"))

        // And both control planes are untouched while every media slot is
        // held — the reason media has an executor of its own, on an origin of
        // its own.
        assertEquals(200, get("/whoami", cookie = cookie).first)
        assertEquals(200, admin("/health").first)

        slots.release()
        // A freed slot lets the next child in; the answer is now the rules'
        // and no longer the cap's.
        assertEquals(403, get("/media?v=$VIDEO", cookie = cookie).first)
    }

    // --- plumbing -------------------------------------------------------

    private fun seed(blocked: Set<String> = emptySet()) {
        val json = ConfigJson.toJson(
            Whitelist(
                sources = listOf(
                    WhitelistEntry("UC1", "https://www.youtube.com/channel/UC1", "UC1", SourceKind.CHANNEL)
                ),
                blockedVideoIds = blocked,
                homeZone = "Pacific/Auckland",
                sync = SyncMeta(docAt = T, at = mapOf(ConfigStamp.src("UC1") to T))
            )
        )
        assertNotNull(store.merge(json, "test"))
    }

    /**
     * Mint a code the way a parent's console does, and redeem it the way a
     * tablet does. The mint is on the hub's own store rather than through
     * `POST /api/browsers` so these tests can run against a hub holding no
     * config at all — that route refuses a kid this hub has never heard of,
     * which `HubKidOriginTest` covers.
     */
    private fun claim(): String {
        val code = server.browsers().mint(KID, T)!!
        val c = URL("http://127.0.0.1:$kidPort/claim").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray()) }
        assertEquals(200, c.responseCode)
        val set = c.getHeaderField("Set-Cookie")
        c.disconnect()
        return set.substringBefore(";")
    }

    private fun raw(path: String, cookie: String? = null, method: String = "GET"): HttpURLConnection {
        val c = URL("http://127.0.0.1:$kidPort$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        cookie?.let { c.setRequestProperty("Cookie", it) }
        if (method == "POST") {
            c.doOutput = true
            c.outputStream.use { it.write(ByteArray(0)) }
        }
        c.responseCode
        return c
    }

    private fun get(
        path: String,
        cookie: String? = null,
        method: String = "GET"
    ): Pair<Int, String> {
        val c = raw(path, cookie, method)
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.readBytes()?.decodeToString().orEmpty()
        c.disconnect()
        return code to body
    }

    /** The admin origin, for the one assertion that is about it. */
    private fun admin(path: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.readBytes()?.decodeToString().orEmpty()
        c.disconnect()
        return code to body
    }
}
