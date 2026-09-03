package io.pickwick.hub

import io.pickwick.app.data.ConfigJson
import io.pickwick.app.data.ConfigStamp
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.SyncMeta
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The hub over real HTTP.
 *
 * Driven through a socket rather than by calling the handlers, because the
 * things most likely to be wrong are at that boundary: status codes, the
 * shape of a body, whether an unauthenticated caller is actually refused.
 */
class HubServerTest {

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
        server = HubServer(store, tokens, 0, ADMIN) { T }
        port = server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun entry(id: String) = WhitelistEntry(
        id = id,
        url = "https://www.youtube.com/channel/$id",
        label = id,
        kind = SourceKind.CHANNEL
    )

    private fun config(vararg ids: String, at: Long = T) = ConfigJson.toJson(
        Whitelist(
            sources = ids.map { entry(it) },
            blockedVideoIds = emptySet(),
            sync = SyncMeta(docAt = at, at = ids.associate { ConfigStamp.src(it) to at })
        )
    )

    private fun call(
        path: String,
        method: String = "GET",
        token: String? = null,
        body: String? = null
    ): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        token?.let { c.setRequestProperty("X-Token", it) }
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        return code to text
    }

    private fun callAdmin(path: String, body: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("X-Admin-Token", ADMIN)
        c.doOutput = true
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        return code to text
    }

    /** Enrol a device the way a real one would, and return its token. */
    private fun enrolled(name: String = "Living Room TV"): String {
        val (code, body) = call("/enrol", "POST", body = JSONObject().put("name", name).toString())
        assertEquals(200, code)
        val c = JSONObject(body).getString("code")
        val (ac, ab) = callAdmin("/approve", JSONObject().put("code", c).toString())
        assertEquals(200, ac)
        return JSONObject(ab).getString("token")
    }

    // --- the gate -------------------------------------------------------

    @Test
    fun anUnknownCallerReachesNothing() {
        // The hub holds a family's whole configuration. Everything except
        // asking to join is behind a token.
        listOf("/status", "/config").forEach { path ->
            assertEquals("$path must refuse an unknown caller", 401, call(path).first)
        }
        assertEquals(401, call("/config", "POST", body = config("UCaaa")).first)
    }

    @Test
    fun aRevokedDeviceStopsBeingAbleToRead() {
        val token = enrolled()
        assertEquals(200, call("/status", token = token).first)
        tokens.revoke(token)
        assertEquals(401, call("/status", token = token).first)
    }

    @Test
    fun healthNeedsNoTokenSoAContainerCanBeProbed() {
        assertEquals(200, call("/health").first)
    }

    // --- the wire contract ----------------------------------------------

    @Test
    fun statusCarriesExactlyTheKeysTheAppReads() {
        // These names are read by LanClient.fullStatus in :app. The hub cannot
        // depend on :app to check that, so the contract is pinned here by
        // name — if either side renames one, this fails rather than the app
        // quietly treating the hub as a pre-merge peer forever.
        val (code, body) = call("/status", token = enrolled())
        assertEquals(200, code)
        val json = JSONObject(body)
        listOf("hash", "updatedAt", "syncV", "syncHash").forEach {
            assertTrue("/status must carry '$it' — LanClient.fullStatus reads it", json.has(it))
        }
    }

    @Test
    fun advertisingSyncVIsWhatMakesTheHubAMergePeer() {
        // Without syncV the app classes a peer as pre-merge and push-only, so
        // the hub would receive config and never contribute any.
        val json = JSONObject(call("/status", token = enrolled()).second)
        assertEquals(SyncMeta.VERSION, json.getInt("syncV"))
    }

    // --- config ---------------------------------------------------------

    @Test
    fun aFreshHubHasNoConfigToServe() {
        assertEquals(404, call("/config", token = enrolled()).first)
    }

    @Test
    fun aPushedConfigComesBackOnGet() {
        val token = enrolled()
        assertEquals(200, call("/config", "POST", token, config("UCaaa")).first)

        val (code, body) = call("/config", token = token)
        assertEquals(200, code)
        assertEquals(listOf("UCaaa"), ConfigJson.fromJson(body).sources.map { it.id })
    }

    @Test
    fun twoDevicesPushingDifferentChannelsBothSurvive() {
        // The reason the hub exists in this shape at all: it merges rather
        // than storing whichever push landed last.
        val a = enrolled("Dad's phone")
        val b = enrolled("Mum's phone")
        call("/config", "POST", a, config("UCdad", at = T + 1))
        call("/config", "POST", b, config("UCmum", at = T + 2))

        val held = ConfigJson.fromJson(call("/config", token = a).second)
        assertEquals(setOf("UCdad", "UCmum"), held.sources.map { it.id }.toSet())
    }

    @Test
    fun aPushReportsWhetherAnythingWasLearned() {
        val token = enrolled()
        val first = JSONObject(call("/config", "POST", token, config("UCaaa")).second)
        assertTrue("the first push is new", first.getBoolean("changed"))

        val again = JSONObject(call("/config", "POST", token, config("UCaaa")).second)
        assertFalse("re-pushing the same config changes nothing", again.getBoolean("changed"))
    }

    @Test
    fun aPushTellsAPeerWhenItIsBehind() {
        val token = enrolled()
        call("/config", "POST", token, config("UCaaa", "UCbbb"))
        // A device that only knows one of them should be told to come back.
        val body = JSONObject(call("/config", "POST", token, config("UCaaa")).second)
        assertTrue("the peer is missing a channel the hub holds", body.getBoolean("peerBehind"))
    }

    @Test
    fun garbageIsRefusedAndChangesNothing() {
        val token = enrolled()
        call("/config", "POST", token, config("UCaaa"))
        assertEquals(400, call("/config", "POST", token, "{{{ not json").first)
        assertEquals(
            "a refused push must leave the config untouched",
            listOf("UCaaa"),
            ConfigJson.fromJson(call("/config", token = token).second).sources.map { it.id }
        )
    }

    @Test
    fun theHashInAStatusMatchesTheConfigItServes() {
        // If these ever disagree, every device decides it is out of sync with
        // the hub on every sweep and pushes forever.
        val token = enrolled()
        call("/config", "POST", token, config("UCaaa"))
        val status = JSONObject(call("/status", token = token).second)
        val served = ConfigJson.fromJson(call("/config", token = token).second)
        assertEquals(ConfigJson.fingerprint(served), status.getString("hash"))
    }

    // --- secrets --------------------------------------------------------

    @Test
    fun anApiKeyInAPushNeverReachesTheHubsDisk() {
        val token = enrolled()
        val withKey = ConfigJson.toJson(
            Whitelist(
                sources = listOf(entry("UCaaa")),
                blockedVideoIds = emptySet(),
                ai = io.pickwick.app.data.AiConfig(model = "m", apiKey = "sk-must-not-land"),
                sync = SyncMeta(at = mapOf(ConfigStamp.src("UCaaa") to T))
            )
        )
        call("/config", "POST", token, withKey)

        val onDisk = File(tmp.root, "data/config.json").readText()
        assertFalse("a credential must never reach the hub's volume", onDisk.contains("sk-must-not-land"))
        assertTrue("but the config it arrived with must", onDisk.contains("UCaaa"))
    }

    @Test
    fun anOversizedBodyIsRefusedRatherThanRead() {
        // The server answers 413 from the Content-Length alone and never reads
        // the megabytes — which is the point, since this faces the network. The
        // client then fails mid-write, so either outcome is the refusal
        // working; what must not happen is the body being swallowed and stored.
        val token = enrolled()
        call("/config", "POST", token, config("UCaaa"))
        val huge = "x".repeat(2 * 1024 * 1024)

        val refused = runCatching { call("/config", "POST", token, huge) }
            .fold({ it.first == 413 }, { true })
        assertTrue("an oversized body must be refused", refused)
        assertEquals(
            "and must not disturb what is stored",
            listOf("UCaaa"),
            ConfigJson.fromJson(call("/config", token = token).second).sources.map { it.id }
        )
    }

    // --- enrolment ------------------------------------------------------

    @Test
    fun enrolmentNeedsNoTokenButGrantsNothingOnItsOwn() {
        val (code, body) = call("/enrol", "POST", body = JSONObject().put("name", "New TV").toString())
        assertEquals(200, code)
        val issued = JSONObject(body).getString("code")
        assertEquals(HubTokens.CODE_LENGTH, issued.length)
        // Holding a code is not being paired. A human still has to approve it.
        assertEquals(401, call("/status").first)
        assertTrue(tokens.devices().isEmpty())
    }

    @Test
    fun twoEnrolmentsGetDifferentCodes() {
        val a = JSONObject(call("/enrol", "POST", body = "{}").second).getString("code")
        val b = JSONObject(call("/enrol", "POST", body = "{}").second).getString("code")
        assertNotEquals(a, b)
    }

    // --- the admin gate -------------------------------------------------

    @Test
    fun approvingNeedsTheAdminSecret() {
        // The device code proves someone is standing at the device. The admin
        // token proves they are entitled to add one. Neither alone is enough,
        // and this is the half an attacker on the network would not have.
        val body = JSONObject(call("/enrol", "POST", body = "{}").second)
        val code = body.getString("code")

        val c = URL("http://127.0.0.1:$port/approve").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("X-Admin-Token", "not-the-admin-token")
        c.doOutput = true
        c.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray()) }
        assertEquals(401, c.responseCode)
        c.disconnect()

        assertTrue("a refused approval must enrol nothing", tokens.devices().isEmpty())
    }

    @Test
    fun approvingWithNoAdminHeaderAtAllIsRefused() {
        val code = JSONObject(call("/enrol", "POST", body = "{}").second).getString("code")
        assertEquals(401, call("/approve", "POST", body = JSONObject().put("code", code).toString()).first)
    }

    @Test
    fun thePendingListIsAdminOnly() {
        // It names the devices asking to join and their live codes — handing
        // that to an unauthenticated caller would hand them enrolment itself.
        call("/enrol", "POST", body = JSONObject().put("name", "Kitchen tablet").toString())
        assertEquals(401, call("/pending").first)
    }

    @Test
    fun anAdminSeesWhatIsWaiting() {
        call("/enrol", "POST", body = JSONObject().put("name", "Kitchen tablet").toString())
        val c = URL("http://127.0.0.1:$port/pending").openConnection() as HttpURLConnection
        c.setRequestProperty("X-Admin-Token", ADMIN)
        val text = c.inputStream.bufferedReader().readText()
        c.disconnect()
        val arr = JSONObject(text).getJSONArray("pending")
        assertEquals(1, arr.length())
        assertEquals("Kitchen tablet", arr.getJSONObject(0).getString("name"))
    }

    @Test
    fun aWrongCodeFromAnAdminSaysWhy() {
        call("/enrol", "POST", body = "{}")
        val (code, body) = callAdmin("/approve", JSONObject().put("code", "WRONGCOD").toString())
        assertEquals(409, code)
        assertEquals("UNKNOWN_CODE", JSONObject(body).getString("refused"))
    }
}
