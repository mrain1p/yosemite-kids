package io.pickwick.hub

import io.pickwick.app.data.ConfigJson
import io.pickwick.app.data.ConfigMerge
import io.pickwick.app.data.Page
import io.pickwick.app.data.SettingsSurface
import io.pickwick.app.data.Where
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
import java.net.HttpURLConnection
import java.net.URL

/**
 * The admin GUI: its session gate, its throttle, and what an edit made in a
 * browser actually becomes on disk.
 *
 * The last of those is the one worth having. A hub edit that is not stamped
 * carries no causality and loses to the first peer that syncs, so a parent
 * changing something on the NAS would watch it silently revert — a failure
 * that looks like the hub being broken and is invisible in any test that only
 * checks the file afterwards.
 */
class HubWebTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val admin = "test-admin-token"
    private var clock = 1_780_000_000_000L

    private lateinit var store: HubStore
    private lateinit var tokens: HubTokens
    private lateinit var server: HubServer
    private var port = 0

    @Before
    fun setUp() {
        val dir = tmp.newFolder("hub")
        store = HubStore(dir)
        tokens = HubTokens(dir)
        server = HubServer(store, tokens, 0, admin, "/data") { clock }
        port = server.start()
    }

    @After
    fun tearDown() = server.stop()

    // --- the session gate -----------------------------------------------

    @Test
    fun theApiSaysNothingWithoutASession() {
        val (code, _) = call("/api/state")
        assertEquals("family data must not be readable without signing in", 401, code)
    }

    @Test
    fun thePageItselfIsServedToAnyone() {
        // It contains no family data — every byte of that arrives later over
        // /api, behind the session. Gating the shell would only mean a parent
        // gets a 401 instead of a sign-in box.
        val (code, body) = call("/")
        assertEquals(200, code)
        assertTrue(body.contains("Pickwick hub"))
    }

    @Test
    fun signingInWithTheAdminTokenOpensASession() {
        val session = signIn()
        assertNotNull(session)
        assertEquals(200, call("/api/state", cookie = session).first)
    }

    @Test
    fun aWrongTokenOpensNothing() {
        val (code, _) = post("/login", JSONObject().put("token", "not-it").toString())
        assertEquals(401, code)
    }

    @Test
    fun signingOutClosesTheSession() {
        val session = signIn()!!
        assertEquals(200, post("/logout", "{}", cookie = session).first)
        assertEquals(401, call("/api/state", cookie = session).first)
    }

    @Test
    fun aSessionDoesNotOutliveItsTtl() {
        val session = signIn()!!
        clock += HubSessions.SESSION_TTL_MS + 1
        assertEquals(401, call("/api/state", cookie = session).first)
    }

    @Test
    fun guessingTheAdminTokenIsThrottled() {
        // The login form is what makes the admin token guessable at all: before
        // the GUI it could only be presented programmatically. Refusing beats
        // slowing — a parent mistyping twice is unaffected, and something trying
        // thousands is stopped rather than merely inconvenienced.
        repeat(HubSessions.MAX_ATTEMPTS) {
            assertEquals(401, post("/login", JSONObject().put("token", "wrong$it").toString()).first)
        }
        assertEquals(429, post("/login", JSONObject().put("token", "wrong").toString()).first)

        // And the real token is refused too while locked out, or the throttle
        // would be trivially bypassed by guessing until you get it right.
        assertEquals(429, post("/login", JSONObject().put("token", admin).toString()).first)

        clock += HubSessions.LOCKOUT_WINDOW_MS + 1
        assertEquals(200, post("/login", JSONObject().put("token", admin).toString()).first)
    }

    @Test
    fun aCrossSiteRequestIsRefusedEvenWithAValidSession() {
        // SameSite=Strict already stops the cookie riding along, but checking
        // only the cookie trusts the browser to have enforced that. Same
        // reasoning as /pair-request in the app.
        //
        // Over a raw socket because HttpURLConnection silently drops Origin —
        // it is on the JDK list of restricted headers. Sent through that, this
        // test passed while proving nothing, which is worse than not having it.
        val session = signIn()!!
        val status = rawRequest(
            "GET /api/state HTTP/1.1",
            "Host: 127.0.0.1:$port",
            "Origin: http://evil.example",
            "Cookie: pw_session=$session"
        )
        assertEquals(403, status)
    }

    @Test
    fun aSameOriginRequestOverTheSameSocketIsAllowed() {
        // The control. Without it the test above would also pass if the server
        // simply refused everything that arrived over a raw socket.
        val session = signIn()!!
        val status = rawRequest(
            "GET /api/state HTTP/1.1",
            "Host: 127.0.0.1:$port",
            "Origin: http://127.0.0.1:$port",
            "Cookie: pw_session=$session"
        )
        assertEquals(200, status)
    }

    // --- editing ---------------------------------------------------------

    @Test
    fun aChannelAddedInTheBrowserIsStamped() {
        // THE property. An unstamped edit has no causality, so the next merge
        // with any peer discards it and the parent watches their change revert.
        val session = signIn()!!
        assertEquals(200, post("/api/channels", JSONObject().put("add", "@Numberphile").toString(), session).first)

        val config = store.load()
        assertEquals(listOf("@Numberphile"), config.sources.map { it.id })
        assertTrue(
            "the new channel must carry a stamp",
            config.sync.at.keys.any { it.contains("@Numberphile") }
        )
    }

    @Test
    fun aHubEditSurvivesAMergeWithAPeerThatNeverSawIt() {
        // The same property, proved the way it actually matters: against the
        // merge, not against the file.
        val session = signIn()!!
        post("/api/channels", JSONObject().put("add", "@Numberphile").toString(), session)
        val afterEdit = store.raw()!!

        // A peer whose document predates the edit entirely.
        val stalePeer = ConfigJson.toJson(store.load().copy(sources = emptyList(), sync = io.pickwick.app.data.SyncMeta.EMPTY))
        val merged = ConfigMerge.merge(afterEdit, stalePeer).merged ?: afterEdit

        assertEquals(
            "the hub's own edit must not lose to a peer that never had it",
            listOf("@Numberphile"),
            ConfigJson.fromJson(merged).sources.map { it.id }
        )
    }

    @Test
    fun removingAChannelInTheBrowserRemovesIt() {
        val session = signIn()!!
        post("/api/channels", JSONObject().put("add", "@One").toString(), session)
        post("/api/channels", JSONObject().put("add", "@Two").toString(), session)
        assertEquals(200, post("/api/channels", JSONObject().put("remove", "@One").toString(), session).first)

        assertEquals(listOf("@Two"), store.load().sources.map { it.id })
    }

    @Test
    fun textThatIsNotAChannelIsReportedRatherThanSilentlyAdded() {
        val session = signIn()!!
        val (code, body) = post("/api/channels", JSONObject().put("add", "hello there").toString(), session)
        assertEquals(200, code)
        assertEquals(0, JSONObject(body).getInt("added"))
        assertTrue(store.load().sources.isEmpty())
    }

    // --- devices ---------------------------------------------------------

    @Test
    fun theBrowserNeverSeesADeviceToken() {
        // A page has no use for a bearer credential it can only hand back, and
        // rendering one puts it in a screenshot, a scroll-back and a support
        // email. It gets a short reference and revokes by that.
        val session = signIn()!!
        val code = tokens.startEnrolment("A phone", clock)
        val token = tokens.approve(code, clock).getOrThrow()

        val body = call("/api/state", cookie = session).second
        assertFalse("a device token must never reach the page", body.contains(token))
        assertTrue(body.contains(HubWeb.deviceRef(token)))
    }

    @Test
    fun aDeviceCanBeApprovedAndThenRevokedFromTheBrowser() {
        val session = signIn()!!
        val code = tokens.startEnrolment("A phone", clock)

        assertEquals(200, post("/api/devices", JSONObject().put("approve", code).toString(), session).first)
        assertEquals(1, tokens.devices().size)

        val ref = HubWeb.deviceRef(tokens.devices().single().token)
        assertEquals(200, post("/api/devices", JSONObject().put("revoke", ref).toString(), session).first)
        assertTrue(tokens.devices().isEmpty())
    }

    // --- parity ----------------------------------------------------------

    @Test
    fun theHubServesExactlyThePagesThePhoneNavigates() {
        // Also a build guard, deliberately. The guard catches it before a
        // commit; this catches it in a build where the guard was not run, and
        // states the rule where a reader of :hub will meet it.
        val phonePages = Page.values().map { HubWeb.pageIdOf(it) }.toSet()
        assertEquals(phonePages, HubWeb.pages.map { it.id }.toSet())
    }

    @Test
    fun everyGroupMarkedReadyOnTheHubBelongsToAPageTheHubServes() {
        val served = HubWeb.pages.map { it.id }.toSet()
        SettingsSurface.sections
            .filter { it.where != Where.PHONE && it.hubReady }
            .forEach {
                assertTrue(
                    "${it.id} is ready on the hub but its page is not served",
                    HubWeb.pageIdOf(it.page) in served
                )
            }
    }

    @Test
    fun whatIsStillMissingIsNamedForTheParentRatherThanCounted() {
        // The page tells a parent which settings they still need a phone for,
        // on the page where they went looking. "Six outstanding" would not
        // help anyone decide anything.
        val session = signIn()!!
        val hub = JSONObject(call("/api/state", cookie = session).second).getJSONObject("hub")
        val outstanding = hub.getJSONArray("outstanding")
        assertEquals(SettingsSurface.outstandingOnHub().size, outstanding.length())
        if (outstanding.length() > 0) {
            val first = outstanding.getJSONObject(0)
            assertTrue(first.getString("title").isNotBlank())
            assertTrue("and says which page it lives on", first.getString("page").isNotBlank())
        }
    }

    // --- editing plain config ---------------------------------------------

    @Test
    fun aTogglePatchedFromTheBrowserIsStampedAndSurvivesAMerge() {
        val session = signIn()!!
        assertEquals(200, post("/api/config", JSONObject().put("sponsorSkip", false).toString(), session).first)

        val after = store.load()
        assertFalse(after.sponsorSkip)
        // Against the merge, not against the file: an unstamped edit looks
        // fine on disk and loses on the next sync.
        val stale = ConfigJson.toJson(after.copy(sponsorSkip = true, sync = io.pickwick.app.data.SyncMeta.EMPTY))
        val merged = ConfigMerge.merge(store.raw(), stale).merged ?: store.raw()!!
        assertFalse(ConfigJson.fromJson(merged).sponsorSkip)
    }

    @Test
    fun aBrowserCannotSetTheApiKeyHoweverItAsks() {
        // The hub strips secrets on the way to disk, so a key set here would
        // appear to work, ride out to every device in the next push, and then
        // be gone after a restart.
        val session = signIn()!!
        val ai = JSONObject().put("enabled", true).put("model", "m").put("apiKey", "sk-nope")
        post("/api/config", JSONObject().put("ai", ai).toString(), session)

        assertEquals("m", store.load().ai.model)
        assertFalse(java.io.File(tmp.root, "hub/config.json").readText().contains("sk-nope"))
    }

    @Test
    fun aPatchNamingNothingSettableIsRefused() {
        val session = signIn()!!
        // sync is the merge's own bookkeeping and only the stamper may write it.
        val (code, _) = post("/api/config", JSONObject().put("sync", JSONObject()).toString(), session)
        assertEquals(400, code)
    }

    @Test
    fun aChannelsPerKidVisibilityCanBeSetFromTheBrowser() {
        val session = signIn()!!
        post("/api/channels", JSONObject().put("add", "@One").toString(), session)
        val edit = JSONObject().put("id", "@One").put("multiplier", 50)
            .put("kids", org.json.JSONArray().put("k1"))
        assertEquals(200, post("/api/channels", JSONObject().put("edit", edit).toString(), session).first)

        val e = store.load().sources.single()
        assertEquals(50, e.timeMultiplierPercent)
        assertEquals(setOf("k1"), e.profileIds)
    }

    // --- versions ----------------------------------------------------------

    @Test
    fun aRestoreIsAStampedEditThatBeatsAPeerRatherThanAFileCopy() {
        // THE property of the whole feature. A byte-restore carries the old
        // stamps, so every peer that edited since wins its unit straight back
        // and the parent watches the rollback undo itself.
        val session = signIn()!!
        post("/api/channels", JSONObject().put("add", "@Keep").toString(), session)
        val before = store.load()

        // A version exists once the settings change again, past the window.
        clock += HubVersions.COALESCE_MS + 1
        post("/api/channels", JSONObject().put("add", "@Mistake").toString(), session)
        assertEquals(setOf("@Keep", "@Mistake"), store.load().sources.map { it.id }.toSet())

        val versions = JSONObject(call("/api/state", cookie = session).second).getJSONArray("versions")
        assertTrue("a version should have been kept", versions.length() > 0)
        val id = versions.getJSONObject(0).getString("id")

        clock += HubVersions.COALESCE_MS + 1
        assertEquals(200, post("/api/versions", JSONObject().put("restore", id).toString(), session).first)
        assertEquals(setOf("@Keep"), store.load().sources.map { it.id }.toSet())

        // And it holds against a peer that still has the mistake.
        val peerStillHasIt = ConfigJson.toJson(before.copy(sources = before.sources))
        val merged = ConfigMerge.merge(store.raw(), peerStillHasIt).merged ?: store.raw()!!
        assertFalse(
            "the restore must not be undone by a peer",
            ConfigJson.fromJson(merged).sources.any { it.id == "@Mistake" }
        )
    }

    @Test
    fun versionsAreKeptOnlyWhenTheSettingsActuallyChange() {
        // toJson re-stamps updatedAt on every serialization, so byte-difference
        // is always true and would burn the ring on sync noise alone.
        val session = signIn()!!
        post("/api/channels", JSONObject().put("add", "@One").toString(), session)
        val first = JSONObject(call("/api/state", cookie = session).second).getJSONArray("versions").length()

        clock += HubVersions.COALESCE_MS + 1
        // Re-adding the same channel changes nothing.
        post("/api/channels", JSONObject().put("add", "@One").toString(), session)
        val second = JSONObject(call("/api/state", cookie = session).second).getJSONArray("versions").length()
        assertEquals(first, second)
    }

    @Test
    fun aRestoreOfSomethingThatIsNotAVersionIsRefused() {
        val session = signIn()!!
        val (code, body) = post("/api/versions", JSONObject().put("restore", "../config.json").toString(), session)
        assertEquals(200, code)
        assertFalse(JSONObject(body).getBoolean("restored"))
    }
    // --- plumbing --------------------------------------------------------

    /**
     * A request built by hand, for headers the JDK client refuses to send.
     * Returns the status code.
     */
    private fun rawRequest(requestLine: String, vararg headers: String): Int =
        java.net.Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            val request = (listOf(requestLine) + headers + listOf("Connection: close", "", ""))
                .joinToString("\r\n")
            out.write(request.toByteArray())
            out.flush()
            val first = socket.getInputStream().bufferedReader().readLine().orEmpty()
            first.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
        }

    /** Returns the session cookie value, or null if the login was refused. */
    private fun signIn(): String? {
        val url = URL("http://127.0.0.1:$port/login")
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(JSONObject().put("token", admin).toString().toByteArray()) }
        if (c.responseCode != 200) return null
        return c.getHeaderField("Set-Cookie")?.substringAfter("pw_session=")?.substringBefore(";")
    }

    private fun call(path: String, cookie: String? = null) =
        request(path, "GET", null, cookie)

    private fun post(path: String, body: String, cookie: String? = null) =
        request(path, "POST", body, cookie)

    private fun request(
        path: String,
        method: String,
        body: String?,
        cookie: String?
    ): Pair<Int, String> {
        val c = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            cookie?.let { setRequestProperty("Cookie", "pw_session=$it") }
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
