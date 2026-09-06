package io.yosemitekids.hub

import io.yosemitekids.app.data.AiConfig
import io.yosemitekids.app.data.AiScreener
import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.ConfigMerge
import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.ScreeningStore
import io.yosemitekids.app.data.SyncMeta
import io.yosemitekids.app.data.Whitelist
import org.json.JSONArray
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

    // --- what a parent does with them --------------------------------------

    @Test
    fun theQueueIsBuiltFromTheVerdictsAndNothingElse() {
        // The claim this whole step rests on: an entry carries its own title,
        // channel, thumbnail and reason, so a queue can be drawn on a box that
        // holds no video cache, no feed and no crawl of its own.
        val device = enrol("Mum's phone")
        post("/verdicts", verdictJson("abc12345", rules), device)

        val queue = reviewState().getJSONArray("queue")
        assertEquals(1, queue.length())
        assertEquals("abc12345", queue.getJSONObject(0).getString("id"))
        assertEquals("Loud unboxing", queue.getJSONObject(0).getString("title"))
        assertEquals("SomeChannel", queue.getJSONObject(0).getString("channel"))
        assertEquals(
            "https://i.ytimg.com/vi/abc12345/hq.jpg",
            queue.getJSONObject(0).getString("thumb")
        )
        assertEquals("shouting the whole way through", queue.getJSONObject(0).getString("why"))
    }

    @Test
    fun aVideoAlreadyRuledOnDoesNotComeBackOnThisFace() {
        // A parent who answered this card on their phone must not be asked
        // again on the NAS. The filter is the same set the phone calls
        // `resolved`, read from the config both faces share.
        val device = enrol("Mum's phone")
        post("/verdicts", verdictJson("abc12345", rules), device)
        assertEquals(1, reviewState().getInt("queueTotal"))

        val session = signIn()!!
        assertEquals(
            200,
            post(
                "/api/config",
                JSONObject().put("aiAllowed", JSONArray().put("abc12345")).toString(),
                session = session
            ).first
        )
        assertEquals(0, reviewState().getInt("queueTotal"))
        assertTrue("abc12345" in store.load().aiAllowedVideoIds)
    }

    @Test
    fun aBlockTheHubIsHoldingCanBeLiftedHereAndStaysLifted() {
        // The fail-closed half of the merge, from the side that benefits from
        // it. `blk` is Safe.PRESENT: presence wins ties and lifting is the act
        // that needs proof, where the proof is that the copy doing the lifting
        // was holding the block. The hub merges the family config like any
        // other peer, so it holds it — which is the whole reason a ruling can
        // be made here at all.
        val phone = phoneThatBlocked("abc12345")
        store.merge(phone, "Mum's phone")
        assertTrue("abc12345" in store.load().blockedVideoIds)

        clock += 60_000
        val session = signIn()!!
        assertEquals(
            200,
            post(
                "/api/config",
                JSONObject()
                    .put("blocked", JSONArray())
                    .put("aiAllowed", JSONArray().put("abc12345"))
                    .toString(),
                session = session
            ).first
        )
        assertFalse("abc12345" in store.load().blockedVideoIds)

        // The phone that made the block and never saw the lift takes it.
        val back = ConfigMerge.merge(phone, store.raw()!!).merged
            ?: error("the lift did not reach the phone at all")
        assertFalse("abc12345" in ConfigJson.fromJson(back).blockedVideoIds)

        // And that stale copy arriving here again — a Push button, the
        // fifteen-minute worker — must not put the block back.
        repeat(3) { store.merge(phone, "Mum's phone") }
        assertFalse(
            "a stale push resurrected a lifted block",
            "abc12345" in store.load().blockedVideoIds
        )
    }

    @Test
    fun aTombstoneFromAPeerThatNeverHeldTheBlockDoesNotLiftIt() {
        // The half of "fail closed" that a test can actually see, and the
        // reason the lift above is worth anything. `blk` is Safe.PRESENT, so a
        // lift needs proof, and the proof is that the copy doing the lifting
        // was holding the block: `gone` above `at`, with an `at` of its own
        // below it. A peer carrying only the tombstone — a copy that learned of
        // the removal and never of the block — is not evidence, and the block
        // stays. Flip `safeState("blk")` to Safe.ABSENT and this is the
        // assertion that fails.
        store.merge(phoneThatBlocked("abc12345"), "Mum's phone")
        assertTrue("abc12345" in store.load().blockedVideoIds)

        val forged = JSONObject(phoneThatBlocked("abc12345"))
        val sync = forged.getJSONObject("sync")
        val heldAt = sync.getJSONObject("at").getLong("blk|abc12345")
        sync.getJSONObject("at").remove("blk|abc12345")
        sync.put("gone", JSONObject().put("blk|abc12345", heldAt + 60_000))
        forged.put("blocked", JSONArray())

        store.merge(forged.toString(), "an old TV")
        assertTrue(
            "a tombstone with nothing behind it lifted a block",
            "abc12345" in store.load().blockedVideoIds
        )
    }

    @Test
    fun rulingIsSessionGated() {
        // The verdicts arrive on a device token; the rulings are made by a
        // parent in front of the page, and nothing else may make one.
        assertEquals(
            401,
            post(
                "/api/config",
                JSONObject().put("aiAllowed", JSONArray().put("abc12345")).toString()
            ).first
        )
        assertTrue(store.load().aiAllowedVideoIds.isEmpty())
    }

    // --- plumbing ---------------------------------------------------------

    /** The `review` block of /api/state, as the page reads it. */
    private fun reviewState(): JSONObject {
        val (code, body) = get("/api/state", signIn()!!)
        assertEquals(200, code)
        return JSONObject(body).getJSONObject("review")
    }

    /**
     * A phone's document with one video blocked, stamped as that phone.
     *
     * Stamped rather than hand-built: a `blk|<id>` unit with no stamp carries
     * no causality at all, and the merge would then be proving nothing.
     */
    private fun phoneThatBlocked(videoId: String): String {
        val empty = Whitelist(
            emptyList(), emptySet(),
            ai = AiConfig(rulesVersion = rules), sync = SyncMeta.EMPTY
        )
        val stamped = ConfigStamp.stamped(
            empty, empty, empty.copy(blockedVideoIds = setOf(videoId)),
            clock, "Mum's phone", "mum12345"
        )
        return ConfigJson.toJson(stamped.config)
    }

    private fun signIn(): String? {
        val c = (URL("http://127.0.0.1:$port/login").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(JSONObject().put("secret", admin).toString().toByteArray()) }
        if (c.responseCode != 200) return null
        return c.getHeaderField("Set-Cookie")?.substringAfter("yk_session=")?.substringBefore(";")
    }

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

    private fun get(path: String, session: String) = call(path, "GET", null, null, session)

    private fun post(path: String, body: String, token: String? = null, session: String? = null) =
        call(path, "POST", body, token, session)

    private fun call(
        path: String,
        method: String,
        body: String?,
        token: String?,
        session: String? = null
    ): Pair<Int, String> {
        val c = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            token?.let { setRequestProperty("X-Token", it) }
            session?.let { setRequestProperty("Cookie", "yk_session=$it") }
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
