package io.yosemitekids.hub

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.data.KidPassword
import io.yosemitekids.app.data.Profile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A device's diagnostic ring reaching the hub, and a browser's page errors:
 * both land in the container log and on the console's Devices page, both
 * are bounded, and neither is taken from anything but a credential.
 */
class HubReportsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val T = 1_780_000_000_000L
    private val ADMIN = "test-admin-token"

    private lateinit var store: HubStore
    private lateinit var tokens: HubTokens
    private lateinit var server: HubServer
    private var port = 0

    @Before
    fun setUp() {
        val dir = tmp.newFolder("data")
        store = HubStore(dir)
        tokens = HubTokens(dir)
        server = HubServer(store, tokens, 0, ADMIN, index = ChannelIndex(File(dir, "search-index"))) { T }
        port = server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `the store keeps the newest, caps a post, cuts long text and prints each line`() {
        val out = ByteArrayOutputStream()
        val was = System.out
        System.setOut(PrintStream(out, true))
        try {
            val reports = HubReports { T }
            val many = JSONArray().apply {
                repeat(HubReports.MAX_PER_POST + 10) { i -> put(JSONObject().put("at", T + i).put("level", "warn").put("msg", "line $i")) }
            }
            assertEquals(HubReports.MAX_PER_POST, reports.record("device", "Living room TV", "tv", "1.8.0", many))
            assertEquals("newest first", "line ${HubReports.MAX_PER_POST - 1}", reports.recent().getJSONObject(0).getString("msg"))
            val long = JSONArray().put(JSONObject().put("msg", "x".repeat(2000)).put("level", "error"))
            reports.record("browser", "Leo", "browser", null, long)
            assertEquals(HubReports.MAX_TEXT, reports.recent().getJSONObject(0).getString("msg").length)
            val empty = JSONArray().put(JSONObject().put("msg", "   ")).put("not an object")
            assertEquals("blank and malformed rows are skipped, not refused", 0, reports.record("device", "x", null, null, empty))
            repeat(HubReports.MAX) { reports.record("device", "x", null, null, JSONArray().put(JSONObject().put("msg", "fill"))) }
            assertEquals(HubReports.MAX, reports.size())
        } finally {
            System.setOut(was)
        }
        val printed = out.toString()
        assertTrue(printed.contains("report device Living room TV (tv 1.8.0) [warn] line 0"))
        assertTrue(printed.contains("report browser Leo (browser) [error] xxxx"))
    }

    @Test
    fun `a device drains its ring at the hub and the console sees it under the enrolment's name`() {
        val token = enrol("Kitchen TV")
        val body = JSONObject()
            .put("version", "1.8.0").put("kind", "tv")
            .put("entries", JSONArray()
                .put(JSONObject().put("at", T - 5000).put("level", "warn").put("msg", "warm UCabc failed — SocketTimeout"))
                .put(JSONObject().put("at", T - 1000).put("level", "crash").put("msg", "NullPointerException at PlayerActivity.playIndex:1823")))
            .toString()
        val (code, reply) = call("/report", "POST", body, mapOf("X-Token" to token))
        assertEquals(200, code)
        assertEquals(2, JSONObject(reply).getInt("kept"))

        val session = signIn()
        val state = JSONObject(call("/api/state", headers = mapOf(HubServer.SESSION_HEADER to session)).second)
        val reports = state.getJSONArray("reports")
        assertEquals(2, reports.length())
        val newest = reports.getJSONObject(0)
        assertEquals("crash", newest.getString("level"))
        assertEquals("Kitchen TV", newest.getString("who"))
        assertEquals("tv", newest.getString("kind"))
        assertEquals("1.8.0", newest.getString("version"))
        assertEquals(T, newest.getLong("heardAt"))
    }

    @Test
    fun `a report needs a device token, a POST, and a body under the cap`() {
        assertEquals(401, call("/report", "POST", "{}").first)
        val token = enrol("Kitchen TV")
        assertEquals(405, call("/report", "GET", null, mapOf("X-Token" to token)).first)
        assertEquals(400, call("/report", "POST", "not json", mapOf("X-Token" to token)).first)
        // Refused from the Content-Length alone, never read — so the client
        // may fail mid-write before it sees the 413. Either is the refusal.
        val refused = runCatching {
            call("/report", "POST", "{\"entries\":[{\"msg\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}]}", mapOf("X-Token" to token)).first
        }.fold({ it == 413 }, { true })
        assertTrue(refused)
        // The hub's own name for the device, never the body's.
        val forged = JSONObject().put("who", "Mum's phone").put("entries", JSONArray().put(JSONObject().put("msg", "hello"))).toString()
        assertEquals(200, call("/report", "POST", forged, mapOf("X-Token" to token)).first)
        val session = signIn()
        val state = JSONObject(call("/api/state", headers = mapOf(HubServer.SESSION_HEADER to session)).second)
        assertEquals("Kitchen TV", state.getJSONArray("reports").getJSONObject(0).getString("who"))
    }

    @Test
    fun `a browser's page error is filed under the child it watches as, and needs the kid cookie`() {
        val kid = seedKid()
        assertEquals(401, call("/kid/report", "POST", "{\"entries\":[]}").first)
        val cookie = signInKid(kid, "otter")
        val body = JSONObject().put("entries", JSONArray().put(JSONObject().put("at", T).put("level", "error").put("msg", "video abc did not play: 502 resolve-failed"))).toString()
        val (code, reply) = call("/kid/report", "POST", body, mapOf("Cookie" to cookie))
        assertEquals(200, code)
        assertEquals(1, JSONObject(reply).getInt("kept"))
        val session = signIn()
        val newest = JSONObject(call("/api/state", headers = mapOf(HubServer.SESSION_HEADER to session)).second)
            .getJSONArray("reports").getJSONObject(0)
        assertEquals("browser", newest.getString("from"))
        assertEquals("Leo", newest.getString("who"))
        assertEquals("browser", newest.getString("kind"))
    }

    // --- helpers --------------------------------------------------------------

    private fun enrol(name: String): String =
        tokens.approve(tokens.startEnrolment(name, T)!!, T, HubTokens.Kind.DEVICE).getOrThrow()

    private fun seedKid(): String {
        store.edit("test", T) { w ->
            w.copy(
                sources = listOf(
                    io.yosemitekids.app.data.WhitelistEntry(
                        "UCtest000000000000000000", "https://www.youtube.com/channel/UCtest000000000000000000",
                        "Test", io.yosemitekids.app.data.SourceKind.CHANNEL
                    )
                ),
                profiles = listOf(Profile(id = "k1", name = "Leo", webPassword = KidPassword.record("otter", T)))
            )
        }
        return "k1"
    }

    private fun signInKid(kid: String, password: String): String {
        val c = URL("http://127.0.0.1:$port/kid/claim").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("kid", kid).put("password", password).toString().toByteArray()) }
        assertEquals(200, c.responseCode)
        val set = c.getHeaderField("Set-Cookie")
        c.disconnect()
        return set.substringBefore(";")
    }

    private fun signIn(): String {
        val (code, body) = call("/login", "POST", JSONObject().put("secret", ADMIN).toString())
        assertEquals(200, code)
        return JSONObject(body).getString("session")
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
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        return code to text
    }
}
