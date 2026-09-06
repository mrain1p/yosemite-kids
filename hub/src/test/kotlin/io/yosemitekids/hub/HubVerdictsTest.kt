package io.yosemitekids.hub

import io.yosemitekids.app.data.AiConfig
import io.yosemitekids.app.data.AiScreener
import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.ScreeningStore
import io.yosemitekids.app.data.SyncMeta
import io.yosemitekids.app.data.Whitelist
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * `GET|POST /verdicts` on the hub.
 *
 * A verdict costs the family money — one AI call per video per rules version —
 * and until now the hub was the one paired peer that answered the sweep with a
 * page of HTML, which the phone's merger parsed to nothing. So these check the
 * two things that make the hub a real participant: it keeps what it is given,
 * and an exchange with it settles instead of ping-ponging.
 *
 * They also pin the authentication, which matters more here than on `/config`.
 * A verdict entry carries the title, channel and thumbnail of something a
 * child watched or was stopped from watching, so this is the first hub route
 * whose body is about the family's viewing rather than their settings.
 */
class HubVerdictsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val admin = "test-admin-token"
    private var clock = 1_780_000_000_000L

    private lateinit var dir: File
    private lateinit var store: HubStore
    private lateinit var tokens: HubTokens
    private lateinit var server: HubServer
    private var port = 0

    /** The rules the family is on. Anything under another version is not current. */
    private val rules = 7

    @Before
    fun setUp() {
        dir = tmp.newFolder("hub")
        store = HubStore(dir)
        tokens = HubTokens(dir)
        server = HubServer(store, tokens, 0, admin, dir.absolutePath) { clock }
        port = server.start()
        // A config to read the rules version out of. Pushed rather than
        // written, because that is the only way one ever gets here.
        store.merge(
            ConfigJson.toJson(
                Whitelist(emptyList(), emptySet(), ai = AiConfig(rulesVersion = rules), sync = SyncMeta.EMPTY)
            ),
            "a phone"
        )
    }

    @After
    fun tearDown() = server.stop()

    // --- the exchange ------------------------------------------------------

    @Test
    fun whatAPhonePushesIsWhatTheHubHandsBack() {
        val device = enrol("Mum's phone")
        assertEquals(200, post("/verdicts", verdictJson("abc12345", rules), device).first)

        val (code, body) = getAs("/verdicts", device)
        assertEquals(200, code)
        val held = JSONObject(body).getJSONObject("abc12345")
        assertEquals("REVIEW", held.getString("v"))
        // The whole reason the queue needs no video cache on this box: the
        // entry carries what a parent has to look at to rule on it.
        assertEquals("Loud unboxing", held.getString("title"))
        assertEquals("SomeChannel", held.getString("channel"))
        assertEquals("https://i.ytimg.com/vi/abc12345/hq.jpg", held.getString("thumb"))
        assertEquals("shouting the whole way through", held.getString("why"))
    }

    @Test
    fun theSameExchangeTwiceAdoptsNothingTheSecondTime() {
        // What a fifteen-minute worker actually does: the same document lands
        // here over and over. Import never overwrites a verdict already held,
        // so the count is the proof the second round is a no-op — anything
        // else and two peers would push the same entry at each other forever.
        val device = enrol("Mum's phone")
        val first = post("/verdicts", verdictJson("abc12345", rules), device)
        assertEquals(1, JSONObject(first.second).getInt("merged"))
        val again = post("/verdicts", verdictJson("abc12345", rules), device)
        assertEquals(0, JSONObject(again.second).getInt("merged"))
    }

    @Test
    fun aVerdictUnderOtherRulesIsNotCurrentAndIsNotKept() {
        // The rules version is the family's, read from the config this hub
        // already holds, so the two faces cannot disagree about which
        // verdicts are current. A device still on last week's rules must not
        // seed a queue a parent would then be asked to rule on.
        val device = enrol("An old TV")
        val (code, body) = post("/verdicts", verdictJson("abc12345", rules - 1), device)
        assertEquals(200, code)
        assertEquals(0, JSONObject(body).getInt("merged"))
        assertEquals("{}", getAs("/verdicts", device).second)
    }

    @Test
    fun somethingThatIsNotAVerdictMapIsRefused() {
        // A 400 a peer can log, not a "merged" it will trust. The device's own
        // POST /verdicts answers the same way, and the phone treats both alike.
        val device = enrol("Mum's phone")
        assertEquals(400, post("/verdicts", "not json at all", device).first)
    }

    @Test
    fun theVerdictsAreOnDiskBesideTheConfig() {
        // On the volume a parent already backs up, and under the file name a
        // device uses, so a copy taken off either box reads on the other.
        val device = enrol("Mum's phone")
        post("/verdicts", verdictJson("abc12345", rules), device)
        val file = File(dir, "screening.json")
        assertTrue("the hub kept no verdict file", file.exists())
        assertNotNull(ScreeningStore(file).get("abc12345"))
    }

    // --- who may ask -------------------------------------------------------

    @Test
    fun verdictsNeedsAnEnrolledDeviceAndIsNeverThePage() {
        // Both halves matter. The 401 is the gate; the "not HTML" is the bug
        // this route was born out of — "/" is registered last, so before the
        // hub answered /verdicts a phone's sweep got the admin page with a
        // 200 and merged it to nothing, silently, on every sweep for ever.
        listOf("GET", "POST").forEach { method ->
            val (code, body) = call("/verdicts", method, if (method == "POST") "{}" else null, null)
            assertEquals("$method /verdicts must not be open to the LAN", 401, code)
            assertFalse("$method /verdicts answered with the admin page", body.contains("<!doctype", true))
        }
        // And the refusal list must not still be claiming this route is one
        // the hub does not answer, or guard 22 is watching a lie.
        assertFalse("/verdicts" in HubServer.DEVICE_ONLY)
    }

    @Test
    fun aMethodTheRouteDoesNotSpeakIsRefusedRatherThanIgnored() {
        val device = enrol("Mum's phone")
        assertEquals(405, call("/verdicts", "DELETE", null, device).first)
    }

    // --- plumbing ---------------------------------------------------------

    private fun enrol(name: String): String =
        tokens.approve(tokens.startEnrolment(name, clock), clock, HubTokens.Kind.PARENT).getOrThrow()

    /** One entry in `ScreeningStore`'s own wire shape — what a device exports. */
    private fun verdictJson(videoId: String, rulesVersion: Int): String =
        JSONObject().put(
            videoId,
            JSONObject()
                .put("v", AiScreener.Verdict.REVIEW.name)
                .put("why", "shouting the whole way through")
                .put("title", "Loud unboxing")
                .put("channel", "SomeChannel")
                .put("thumb", "https://i.ytimg.com/vi/$videoId/hq.jpg")
                .put("rv", rulesVersion)
                .put("at", clock)
        ).toString()

    private fun getAs(path: String, token: String) = call(path, "GET", null, token)

    private fun post(path: String, body: String, token: String?) = call(path, "POST", body, token)

    private fun call(path: String, method: String, body: String?, token: String?): Pair<Int, String> {
        val c = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            token?.let { setRequestProperty("X-Token", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        body?.let { b -> c.outputStream.use { it.write(b.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        return code to text
    }
}
