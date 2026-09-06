package io.yosemitekids.hub

import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.ConfigMerge
import io.yosemitekids.app.data.Grants
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SyncMeta
import io.yosemitekids.app.data.Whitelist
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneOffset

/**
 * "Add time" on the hub: what the browser may decide, what the hub decides for
 * it, and what a tap made here survives.
 *
 * Three of these exist because a grant is the one config field where the
 * obvious implementation is wrong. `grants` is deliberately outside
 * `HubWeb.PATCHABLE`, so extra minutes go through `POST /api/grant`: a patch
 * replaces the array it names, and an entry missing from a save is *expiry* to
 * the stamper — a browser holding a copy from a minute ago would tombstone a
 * co-parent's tap for the whole fleet without either parent doing anything.
 *
 * The date is the other half. The hub reads no calendar (guard 27) because a
 * container runs UTC and the family does not, so the day comes from the
 * parent's browser and the hub only asks whether it is anywhere near its own.
 */
class HubGrantTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val admin = "test-admin-token"

    /** A fixed instant, so "the hub's own day" is a value this file can name. */
    private var clock = 1_780_000_000_000L

    /** The day that clock falls on in UTC — what the container itself would say. */
    private val hubDay = Grants.dateOf(clock, ZoneOffset.UTC)

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

    // --- what the hub mints -----------------------------------------------

    @Test
    fun theHubMintsTheGrantsIdItself() {
        // An id is merge-key material: `grant|<id>`, and a delimiter in the
        // fingerprint. The browser never sends one, so two faces of one
        // household cannot mint the same id and merge two taps into one.
        val session = signIn()!!
        assertGranted(grant(session, kid = "", minutes = 15, date = hubDay))

        val stored = store.load().grants.single()
        assertTrue(
            "a grant id must be Profile.newId()'s shape: eight lowercase hex, got ${stored.id}",
            Regex("^[0-9a-f]{8}$").matches(stored.id)
        )
        assertEquals(15, stored.minutes)
        assertEquals(hubDay, stored.date)
        // Blank is the family-wide case, exactly as it is on the phone for a
        // household with no profiles yet.
        assertEquals(null, stored.kidId)
        // And it is stamped, or the first peer to sync would discard it.
        assertTrue(store.load().sync.at.keys.any { it == "grant|${stored.id}" })
    }

    @Test
    fun aGrantForOneKidNamesThatKid() {
        val session = signIn()!!
        val kid = Profile.newId()
        assertGranted(grant(session, kid = kid, minutes = 30, date = hubDay))
        assertEquals(kid, store.load().grants.single().kidId)
    }

    @Test
    fun twoTapsAreTwoGrants() {
        // Not one value that the later tap overwrites: two parents adding time
        // in the same minute both meant it, and the merge keeps both.
        val session = signIn()!!
        assertGranted(grant(session, kid = "", minutes = 15, date = hubDay))
        assertGranted(grant(session, kid = "", minutes = 5, date = hubDay))

        val grants = store.load().grants
        assertEquals(2, grants.size)
        assertEquals("two taps must have two ids", 2, grants.map { it.id }.toSet().size)
        assertEquals(20, Grants.minutesFor(grants, null, hubDay))
    }

    // --- what the hub refuses ---------------------------------------------

    @Test
    fun aDateNowhereNearThisContainersDayIsRefused() {
        // A grant expires by the TEXT of its date, so one dated next year
        // never expires: it would hand out its minutes again every day until
        // somebody read the config and found it.
        val session = signIn()!!
        val next = Grants.dateOf(clock + 400L * 24 * 60 * 60 * 1000, ZoneOffset.UTC)
        val (code, body) = grant(session, kid = "", minutes = 15, date = next)
        assertEquals(200, code)
        assertFalse("a distant date must not be granted", JSONObject(body).getBoolean("granted"))
        assertEquals("BAD_DATE", JSONObject(body).getString("why"))
        assertTrue(store.load().grants.isEmpty())
    }

    @Test
    fun theDayEitherSideOfTheContainersIsAccepted() {
        // The parent's browser sends its own day, and a family in Auckland is
        // a day ahead of a UTC container while one in Honolulu is a day
        // behind. Refusing either would mean the hub works everywhere except
        // in the evening, or except in the morning.
        val session = signIn()!!
        val day = 24L * 60 * 60 * 1000
        assertGranted(grant(session, "", 15, Grants.dateOf(clock - day, ZoneOffset.UTC)))
        assertGranted(grant(session, "", 15, Grants.dateOf(clock + day, ZoneOffset.UTC)))
        assertEquals(2, store.load().grants.size)
    }

    @Test
    fun somethingThatIsNotADayIsRefused() {
        val session = signIn()!!
        listOf("", "today", "2026-13-40", "2026-9-6", "2026-09-06T10:00").forEach { bad ->
            val (_, body) = grant(session, kid = "", minutes = 15, date = bad)
            assertFalse("\"$bad\" was accepted as a day", JSONObject(body).getBoolean("granted"))
        }
        assertTrue(store.load().grants.isEmpty())
    }

    @Test
    fun aKidIdThatIsNotOneIsRefused() {
        // The kid id sits between commas in the fingerprint's grant tail, so a
        // stray delimiter there would let two different documents hash alike
        // and read as in sync for ever.
        val session = signIn()!!
        listOf("leo", "1a2b3c4d5e", "1a2b,3c4", "ABCDEF12").forEach { bad ->
            val (_, body) = grant(session, kid = bad, minutes = 15, date = hubDay)
            assertEquals("BAD_KID", JSONObject(body).getString("why"))
        }
        assertTrue(store.load().grants.isEmpty())
    }

    @Test
    fun minutesOutsideWhatADeviceWouldTakeAreRefused() {
        // The bound LanServer's own POST /grant enforces. A face that granted
        // what the other refuses is a parent watching a tap do nothing.
        val session = signIn()!!
        listOf(0, -30, 241, 100_000).forEach { bad ->
            val (_, body) = grant(session, kid = "", minutes = bad, date = hubDay)
            assertEquals("BAD_MINUTES", JSONObject(body).getString("why"))
        }
        assertTrue(store.load().grants.isEmpty())
    }

    @Test
    fun grantsCannotBeSetThroughTheOrdinaryPatchRoute() {
        // The whole reason this route exists. A patch replaces the key it
        // names, so a browser that could set `grants` could leave one out —
        // and a grant missing from a save is expiry to the stamper, which
        // tombstones it for the household.
        val session = signIn()!!
        assertGranted(grant(session, kid = "", minutes = 15, date = hubDay))
        val mine = store.load().grants.single().id

        val forged = JSONArray().put(
            JSONObject().put("id", "deadbeef").put("date", hubDay).put("minutes", 240).put("at", clock)
        )
        // Nothing settable in the patch, so it is refused outright rather than
        // partly applied.
        val (code, _) = post("/api/config", JSONObject().put("grants", forged).toString(), session)
        assertEquals(400, code)

        val after = store.load().grants
        assertEquals("the browser must not be able to replace the grant list", 1, after.size)
        assertEquals(mine, after.single().id)
    }

    @Test
    fun grantingNeedsASession() {
        val (code, _) = post("/api/grant", JSONObject().put("minutes", 15).put("date", hubDay).toString())
        assertEquals(401, code)
        assertTrue(store.load().grants.isEmpty())
    }

    // --- what a tap survives ----------------------------------------------

    @Test
    fun twoGrantsOnOneDayBothSurviveTheSamePushArrivingAgainAndAgain() {
        // What a Push button and a fifteen-minute worker actually do: the same
        // phone document lands on the hub over and over. A merge that is a
        // pure function of (hub copy, pushed document) settles after one
        // round; anything that did not would drop a tap on the second sweep
        // and nobody would ever see which one.
        val session = signIn()!!
        assertGranted(grant(session, kid = "", minutes = 15, date = hubDay))
        assertGranted(grant(session, kid = "", minutes = 30, date = hubDay))
        val ids = store.load().grants.map { it.id }.toSet()

        // A phone that has never seen either tap, pushing its own copy.
        val phone = ConfigJson.toJson(
            Whitelist(emptyList(), emptySet(), sponsorSkip = false, sync = SyncMeta.EMPTY)
        )

        val seen = ArrayList<String>()
        repeat(6) {
            store.merge(phone, "phone")
            val after = store.load()
            // Proof the push is not a no-op the grants are surviving by
            // accident: the phone's own settings blob really does land here.
            assertFalse("the pushed document did not merge at all", after.sponsorSkip)
            assertEquals(
                "a grant was lost when the same push arrived again",
                ids, after.grants.map { it.id }.toSet()
            )
            assertEquals(45, Grants.minutesFor(after.grants, null, hubDay))
            seen += ConfigJson.fingerprint(after)
        }
        assertEquals("the hub's copy kept moving under an unchanged push: $seen", 1, seen.distinct().size)
    }

    @Test
    fun aTapMadeHereReachesAPhoneThatNeverSawIt() {
        // The hub cannot fire POST /grant at anything — it holds no credential
        // on any device — so the config IS the delivery. If the merge did not
        // carry it, the button would be a lie.
        val session = signIn()!!
        assertGranted(grant(session, kid = "", minutes = 20, date = hubDay))

        val phone = ConfigJson.toJson(Whitelist(emptyList(), emptySet(), sync = SyncMeta.EMPTY))
        val merged = ConfigMerge.merge(phone, store.raw()!!).merged
            ?: error("the hub's grant did not reach the phone at all")
        assertEquals(20, Grants.minutesFor(ConfigJson.fromJson(merged).grants, null, hubDay))
    }

    // --- plumbing ---------------------------------------------------------

    private fun assertGranted(result: Pair<Int, String>) {
        assertEquals(200, result.first)
        assertTrue("refused: ${result.second}", JSONObject(result.second).getBoolean("granted"))
    }

    private fun grant(session: String, kid: String, minutes: Int, date: String) =
        post(
            "/api/grant",
            JSONObject().put("kid", kid).put("minutes", minutes).put("date", date).toString(),
            session
        )

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

    private fun post(path: String, body: String, cookie: String? = null): Pair<Int, String> {
        val c = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            cookie?.let { setRequestProperty("Cookie", "yk_session=$it") }
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        return code to text
    }
}
