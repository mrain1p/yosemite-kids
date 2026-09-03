package io.pickwick.app

import io.pickwick.app.data.ConfigJson
import io.pickwick.app.data.HubEnrolment
import io.pickwick.app.data.PairedDevice
import io.pickwick.app.data.SourceKind
import io.pickwick.app.data.SyncMeta
import io.pickwick.app.data.ConfigStamp
import io.pickwick.app.data.Whitelist
import io.pickwick.app.data.WhitelistEntry
import io.pickwick.hub.HubServer
import io.pickwick.hub.HubStore
import io.pickwick.hub.HubTokens
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The app's real hub client against the real hub server, in one JVM.
 *
 * Everything else about the hub is tested on one side or the other. This is
 * the only place the two meet, and the failures it exists to catch are the
 * ones neither side can see alone: a header name that does not match, a status
 * code the client reads differently from how the server means it, a route that
 * exists on one side under another name.
 *
 * The `:hub` dependency is test-only and deliberately one-directional. Nothing
 * in the app's main source set may know the hub exists — a household that never
 * runs one must be unaffected — and a guard in `scripts/check.ps1` enforces
 * the reverse: that `:hub` never depends on `:app`.
 */
class HubIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val T = 1_780_000_000_000L
    private val admin = "integration-admin-token"

    private lateinit var store: HubStore
    private lateinit var tokens: HubTokens
    private lateinit var server: HubServer
    private var port = 0

    @Before
    fun setUp() {
        val dir = tmp.newFolder("hub")
        store = HubStore(dir)
        tokens = HubTokens(dir)
        server = HubServer(store, tokens, 0, admin) { T }
        port = server.start()
    }

    @After
    fun tearDown() = server.stop()

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

    @Test
    fun aPhoneCanFindAHubBeforeTryingToJoinIt() {
        assertTrue(runBlocking { HubEnrolment.probe("127.0.0.1", port) }.isSuccess)
    }

    @Test
    fun probingSomethingThatIsNotThereFailsFastAndSaysSo() {
        // Port 1 is reserved and nothing listens there. A parent who mistypes
        // an address must get "nothing answered", not a hang.
        val e = runBlocking { HubEnrolment.probe("127.0.0.1", 1) }.exceptionOrNull()
        assertEquals(HubEnrolment.Failure.Unreachable, (e as HubEnrolment.HubError).failure)
    }

    @Test
    fun joiningYieldsAPairedDeviceTheOrdinarySyncCanUse() {
        // The whole design rests on this: what comes back is a plain
        // PairedDevice, so the existing reconcile treats the hub as a peer and
        // no sync code had to learn anything about hubs.
        val device = runBlocking {
            HubEnrolment.join("127.0.0.1:$port", 8765, admin, "Dad's phone")
        }.getOrThrow()

        assertEquals("Pickwick hub", device.name)
        assertEquals("127.0.0.1", device.host)
        assertEquals(port, device.port)
        assertTrue(device.token.isNotBlank())
        assertTrue("the hub must now know this device", tokens.isEnrolled(device.token))
        assertEquals("Dad's phone", tokens.nameOf(device.token))
    }

    @Test
    fun aWrongAdminTokenIsRefusedAndEnrolsNothing() {
        val e = runBlocking {
            HubEnrolment.join("127.0.0.1:$port", 8765, "not-the-admin-token", "Dad's phone")
        }.exceptionOrNull()

        assertEquals(HubEnrolment.Failure.BadAdminToken, (e as HubEnrolment.HubError).failure)
        assertTrue("a refused join must leave the hub with no devices", tokens.devices().isEmpty())
    }

    @Test
    fun anAddressWithNoHubAnsweringIsReportedAsUnreachable() {
        val e = runBlocking {
            HubEnrolment.join("127.0.0.1:1", 8765, admin, "Dad's phone")
        }.exceptionOrNull()
        assertEquals(HubEnrolment.Failure.Unreachable, (e as HubEnrolment.HubError).failure)
    }

    @Test
    fun theAddressIsAcceptedInTheFormsAParentActuallyTypes() {
        // Bare host with a port, and with a scheme. Someone typing this off a
        // NAS admin page will paste whichever they were shown.
        listOf("127.0.0.1:$port", "http://127.0.0.1:$port").forEach { typed ->
            assertTrue(
                "'$typed' should be understood",
                runBlocking { HubEnrolment.join(typed, 8765, admin, "Phone") }.isSuccess
            )
        }
    }

    @Test
    fun aJoinedHubAcceptsAPushAndServesItBack() {
        // The end of the loop: enrol, then use the token the way LanClient
        // would, and confirm the hub took it.
        val device = runBlocking {
            HubEnrolment.join("127.0.0.1:$port", 8765, admin, "Dad's phone")
        }.getOrThrow()

        val (code, _) = post(device, config("UCaaa"))
        assertEquals(200, code)

        val (getCode, body) = get(device)
        assertEquals(200, getCode)
        assertEquals(listOf("UCaaa"), ConfigJson.fromJson(body).sources.map { it.id })
    }

    @Test
    fun twoPhonesPushingDifferentChannelsBothSurviveOnTheHub() {
        // The reason an always-on peer is worth running at all.
        val dad = runBlocking { HubEnrolment.join("127.0.0.1:$port", 8765, admin, "Dad") }.getOrThrow()
        val mum = runBlocking { HubEnrolment.join("127.0.0.1:$port", 8765, admin, "Mum") }.getOrThrow()

        post(dad, config("UCdad", at = T + 1))
        post(mum, config("UCmum", at = T + 2))

        val held = ConfigJson.fromJson(get(dad).second)
        assertEquals(setOf("UCdad", "UCmum"), held.sources.map { it.id }.toSet())
    }

    @Test
    fun theStatusTheHubServesIsOneTheAppsOwnParserAccepts() {
        // LanClient.fullStatus reads these names. If the hub renamed one, the
        // app would silently class it as a pre-merge peer and only ever push
        // to it — which looks like working sync until a deletion goes missing.
        val device = runBlocking {
            HubEnrolment.join("127.0.0.1:$port", 8765, admin, "Phone")
        }.getOrThrow()

        val json = org.json.JSONObject(status(device).second)
        assertTrue(json.has("hash"))
        assertTrue(json.has("updatedAt"))
        assertEquals(SyncMeta.VERSION, json.getInt("syncV"))
        assertTrue(json.getString("syncHash").isNotBlank())
    }

    @Test
    fun anApiKeyPushedToTheHubNeverReachesItsDisk() {
        val device = runBlocking {
            HubEnrolment.join("127.0.0.1:$port", 8765, admin, "Phone")
        }.getOrThrow()
        val withKey = ConfigJson.toJson(
            Whitelist(
                sources = listOf(entry("UCaaa")),
                blockedVideoIds = emptySet(),
                ai = io.pickwick.app.data.AiConfig(model = "m", apiKey = "sk-never"),
                sync = SyncMeta(at = mapOf(ConfigStamp.src("UCaaa") to T))
            )
        )
        post(device, withKey)

        val onDisk = java.io.File(tmp.root, "hub/config.json").readText()
        assertFalse(onDisk.contains("sk-never"))
        assertTrue(onDisk.contains("UCaaa"))
    }

    // --- the same calls LanClient makes, without pulling in Android --------

    private fun client() = okhttp3.OkHttpClient.Builder().build()

    private fun status(d: PairedDevice) = call(d, "GET", "/status", null)
    private fun get(d: PairedDevice) = call(d, "GET", "/config", null)
    private fun post(d: PairedDevice, body: String) = call(d, "POST", "/config", body)

    private fun call(d: PairedDevice, method: String, path: String, body: String?): Pair<Int, String> {
        val req = okhttp3.Request.Builder()
            .url("http://${d.host}:${d.port}$path")
            .header("X-Token", d.token)
            .method(
                method,
                body?.toRequestBody("application/json".toMediaType())
            )
            .build()
        client().newCall(req).execute().use { resp ->
            return resp.code to resp.body?.string().orEmpty()
        }
    }
}
