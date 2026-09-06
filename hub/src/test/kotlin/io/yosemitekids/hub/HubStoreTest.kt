package io.yosemitekids.hub

import io.yosemitekids.app.data.AiConfig
import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.json.JSONArray
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
 * The hub's store, and the one credential it is now allowed to hold.
 *
 * "The hub's disk never holds a credential" was true for a year because
 * `HubStore.commit` happened to call `stripSecrets` and nothing else wrote
 * config.json. It was never asserted from the outside, so the day the hub was
 * given a key of its own there was nothing to notice if it leaked into the
 * shared document instead of into its own file. That is what the first half of
 * this file is: the four places a key would surface — the document on disk,
 * the five snapshots in `versions/`, the admin page's `/api/state`, and the
 * backup a parent takes off the box — checked at every depth rather than
 * trusted from the layer below.
 *
 * The second half is the failure D19 of `docs/PLAN-hub-parity.md` calls the
 * quietest one available. A key is resolved by the `ai` unit's stamp, and the
 * hub's disk copy is always keyless — so without `localApiKey` the hub's side
 * of that comparison is permanently blank, the incoming key always wins, and a
 * television that slept through a rotation wakes up and hands the dead key
 * back to the whole household. Screening keeps working the entire time. The
 * bill is the only symptom, weeks later.
 *
 * `aStalePeerCannotUnRotateTheKey` therefore pushes the stale document again
 * and again with the inputs held still, which is what a Push button and a
 * fifteen-minute worker actually do — the harness `MergeConvergenceTest` uses
 * and for the same reason: a rule that holds for one merge and fails on the
 * fourth is not a rule yet.
 */
class HubStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val admin = "test-admin-token"

    /** A fixed instant, so every stamp in this file is a value it can name. */
    private var clock = 1_780_000_000_000L

    private lateinit var dir: File
    private lateinit var secrets: HubSecrets
    private lateinit var store: HubStore
    private lateinit var tokens: HubTokens
    private lateinit var server: HubServer
    private var port = 0

    private val scishow = WhitelistEntry("UC1", "https://youtube.com/channel/UC1", "SciShow Kids", SourceKind.CHANNEL)
    private val numberblocks = WhitelistEntry("UC2", "https://youtube.com/channel/UC2", "Numberblocks", SourceKind.CHANNEL)
    private val storybots = WhitelistEntry("UC3", "https://youtube.com/channel/UC3", "StoryBots", SourceKind.CHANNEL)

    private val oldKey = "sk-or-v1-the-key-that-was-rotated-away"
    private val newKey = "sk-or-v1-the-key-the-parent-typed-today"

    @Before
    fun setUp() {
        dir = tmp.newFolder("hub")
        secrets = HubSecrets(dir)
        store = HubStore(dir, secrets = secrets)
        tokens = HubTokens(dir)
        server = HubServer(store, tokens, 0, admin, "/data") { clock }
        port = server.start()
    }

    @After
    fun tearDown() = server.stop()

    // --- the document on disk holds no credential -------------------------

    @Test
    fun aPushCarryingAKeyLeavesTheStoredDocumentKeyless() {
        pushFromPhone(oldKey) { it.copy(sources = listOf(scishow)) }

        val onDisk = File(dir, "config.json").readText()
        assertFalse("config.json contains the API key itself", onDisk.contains(oldKey))
        assertEquals(
            "config.json names an apiKey field: $onDisk",
            emptyList<String>(), keysNamed(JSONObject(onDisk), "apiKey")
        )
        // And the push really landed, or every assertion above passes on an
        // empty document.
        assertEquals(listOf("UC1"), store.load().sources.map { it.id })
        // The key did arrive — it simply went somewhere else.
        assertEquals(oldKey, secrets.apiKey())
    }

    @Test
    fun theRestoreRingHoldsNoCredentialEither() {
        // Two fingerprint-moving pushes, far enough apart that the ring
        // actually opens a slot (HubVersions.COALESCE_MS), so there is a
        // displaced document to check rather than a directory of nothing.
        pushFromPhone(oldKey) { it.copy(sources = listOf(scishow)) }
        clock += HubVersions.COALESCE_MS * 2
        pushFromPhone(oldKey) { it.copy(sources = listOf(scishow, numberblocks)) }

        val snapshots = File(dir, "versions").listFiles()?.filter { it.name.startsWith("v-") }.orEmpty()
        assertTrue("no snapshot was taken, so this test proves nothing", snapshots.isNotEmpty())
        snapshots.forEach { f ->
            val text = f.readText()
            assertFalse("${f.name} contains the API key itself", text.contains(oldKey))
            assertEquals(
                "${f.name} names an apiKey field",
                emptyList<String>(), keysNamed(JSONObject(text), "apiKey")
            )
        }
    }

    @Test
    fun theAdminPageIsNeverShownTheKey() {
        pushFromPhone(oldKey) { it.copy(sources = listOf(scishow)) }
        val (code, body) = get("/api/state", signIn()!!)
        assertEquals(200, code)
        assertFalse("/api/state contains the API key itself", body.contains(oldKey))
        assertEquals(
            "/api/state names an apiKey field: $body",
            emptyList<String>(), keysNamed(JSONObject(body), "apiKey")
        )
    }

    @Test
    fun aBackupTakenOffTheBoxIsKeylessEvenWhenTheHubHoldsOne() {
        // HubBackupTest already asserts this for a hub that holds nothing.
        // The interesting case is the one that arrived with this feature: the
        // hub really does have the key now, and the file still must not.
        pushFromPhone(oldKey) { it.copy(sources = listOf(scishow)) }
        assertTrue("the hub is not holding a key, so this test proves nothing", store.holdsKey())

        val (code, body) = get("/api/backup", signIn()!!)
        assertEquals(200, code)
        assertFalse("the backup contains the API key itself", body.contains(oldKey))
        assertEquals(
            "the backup names an apiKey field: $body",
            emptyList<String>(), keysNamed(JSONObject(body), "apiKey")
        )
    }

    @Test
    fun theKeyIsPutBackOnlyForAPeerThatIsServedIt() {
        pushFromPhone(oldKey) { it.copy(sources = listOf(scishow)) }

        // Two views of one document. The stored bytes are what everything on
        // this box reads; forPeers() is the one place the key is overlaid.
        assertFalse(store.raw()!!.contains(oldKey))
        assertTrue(store.forPeers()!!.contains(oldKey))
        // And the overlay is exactly that — an overlay. The channel a parent
        // added is still there, so nothing was rebuilt from a parsed model.
        assertEquals(
            listOf("UC1"),
            ConfigJson.fromJson(store.forPeers()!!).sources.map { it.id }
        )
    }

    @Test
    fun theTwoFingerprintsAreTheSameUntilTheHubHoldsAKey() {
        store.edit("The hub", clock) { it.copy(sources = listOf(scishow)) }
        assertEquals(
            "a hub with no key must advertise one answer, or an older phone reads it as out of sync",
            store.fingerprint(), store.fingerprintWithKey()
        )

        pushFromPhone(oldKey) { it.copy(sources = listOf(scishow, numberblocks)) }
        assertNotEquals(
            "once the hub holds a key the keyed fingerprint has to move, or a phone holding a DIFFERENT key reads as in sync",
            store.fingerprint(), store.fingerprintWithKey()
        )
        // `hash` on /status is the keyless form for ever: a phone from before
        // this feature has the hub recorded as keyless and compares its own
        // keyless fingerprint against it.
        assertEquals(
            ConfigJson.fingerprint(store.load(), includeSecrets = false),
            store.fingerprint()
        )
    }

    // --- the rotation that must not be undone ------------------------------

    @Test
    fun aStalePeerCannotUnRotateTheKey() {
        // Day one: the family's key reaches the hub the way it always does —
        // ConfigSync pushes rawJson(), secrets included, to every peer.
        pushFromPhone(oldKey) { it.copy(sources = listOf(scishow)) }
        // The television syncs, and then goes to sleep holding this document.
        val staleTv = ConfigJson.toJson(
            ConfigJson.fromJson(store.raw()!!).copy(ai = AiConfig(enabled = true, apiKey = oldKey)),
            includeSecrets = true
        )
        assertEquals(oldKey, secrets.apiKey())

        // Day two: the parent rotates the key on their phone. That is an edit
        // to the `ai` unit, so it carries a newer stamp than the one the
        // sleeping television holds.
        clock += 24 * 60 * 60 * 1000L
        pushFromPhone(newKey) { it.copy(ai = it.ai.copy(enabled = true, apiKey = newKey)) }
        assertEquals("the rotation never reached the hub at all", newKey, secrets.apiKey())

        // Day three: the television wakes up. It has not re-synced, so it
        // pushes exactly what it went to sleep with — again, and again, which
        // is what a Push button and the fifteen-minute worker actually do.
        clock += 24 * 60 * 60 * 1000L
        repeat(5) {
            store.merge(staleTv, "Living Room TV")
            assertEquals(
                "a peer that slept through the rotation handed the old key back",
                newKey, secrets.apiKey()
            )
        }

        // And what the hub then serves a parent is the live key, not the one
        // it was told about last.
        assertTrue(store.forPeers()!!.contains(newKey))
        assertFalse(store.forPeers()!!.contains(oldKey))
    }

    @Test
    fun aKeylessPeerNeverClearsTheKey() {
        // The commonest peer in the fleet: every device's disk copy is keyless
        // and so is this hub's. "Blank" cannot be told apart from "I hold
        // none", so treating it as an instruction would let the first sync
        // after a restart wipe the family's key.
        pushFromPhone(newKey) { it.copy(sources = listOf(scishow)) }
        val keyless = ConfigJson.toJson(ConfigJson.fromJson(store.raw()!!), includeSecrets = false)

        repeat(3) { store.merge(keyless, "a kid device") }
        assertEquals(newKey, secrets.apiKey())
    }

    @Test
    fun settingTheKeyOnTheHubOutstampsThePeerHoldingTheOldOne() {
        // A parent typing the key into the admin page instead of into a phone.
        // The value is not in the document, so nothing in it moves — which
        // means the `ai` unit's stamp has to be moved deliberately, or this
        // rotation and the stale peer's copy are a TIE, and a tie is broken
        // lexicographically. Half of all rotations would lose.
        pushFromPhone(oldKey) { it.copy(sources = listOf(scishow)) }
        val staleTv = ConfigJson.toJson(
            ConfigJson.fromJson(store.raw()!!).copy(ai = AiConfig(enabled = true, apiKey = oldKey)),
            includeSecrets = true
        )

        clock += 60 * 60 * 1000L
        store.setApiKey(newKey, "The hub", clock)
        assertEquals(newKey, secrets.apiKey())

        clock += 60 * 60 * 1000L
        repeat(5) { store.merge(staleTv, "Living Room TV") }
        assertEquals(
            "the hub's own rotation lost to a peer that never heard about it",
            newKey, secrets.apiKey()
        )
        // Still nowhere near the document.
        assertFalse(File(dir, "config.json").readText().contains(newKey))
    }

    @Test
    fun clearingTheKeyOnTheHubReallyClearsIt() {
        pushFromPhone(oldKey) { it.copy(sources = listOf(scishow)) }
        store.setApiKey("", "The hub", clock + 1000)

        assertFalse(store.holdsKey())
        assertEquals("", secrets.apiKey())
        assertFalse(File(dir, "secrets.json").exists())
        // A cleared key means this hub is keyless again, and must advertise
        // the one fingerprint it did before it ever held one.
        assertEquals(store.fingerprint(), store.fingerprintWithKey())
    }

    @Test
    fun nothingAboutTheKeyReachesTheChangeFeed() {
        // Prohibition 9 in the sync skill: nothing derived from the `ai`
        // object may enter sync.log, because config.json is in all three
        // backup include lists and `stripSecrets` removes exactly one field
        // and can never be taught to walk free text.
        pushFromPhone(oldKey) { it.copy(sources = listOf(scishow)) }
        store.setApiKey(newKey, "The hub", clock + 1000)
        pushFromPhone(newKey) { it.copy(sources = listOf(scishow, storybots)) }

        val feed = store.load().sync.log.joinToString("\n") { "${it.who} ${it.text}" }
        assertFalse(feed.contains(oldKey))
        assertFalse(feed.contains(newKey))
        assertFalse("the feed names an API key at all: $feed", feed.contains("sk-or"))
    }

    // --- plumbing ---------------------------------------------------------

    /**
     * A parent's phone editing what it last synced and pushing it, carrying
     * the key — which is what `ConfigSync.sweep` really does: it pushes
     * `rawJson()`, secrets included, to every peer including this hub.
     */
    private fun pushFromPhone(key: String, edit: (Whitelist) -> Whitelist): String {
        val base = store.load().let { it.copy(ai = it.ai.copy(apiKey = secrets.apiKey())) }
        val stamped = ConfigStamp.stamped(base, base, edit(base), clock, "Mum's phone", "mum12345")
        val document = ConfigJson.toJson(
            stamped.config.copy(ai = stamped.config.ai.copy(apiKey = key)),
            includeSecrets = true
        )
        store.merge(document, "Mum's phone")
        return document
    }

    /** Every path at which a key of this name appears, however deeply nested. */
    private fun keysNamed(node: Any?, name: String, path: String = "$"): List<String> = when (node) {
        is JSONObject -> node.keys().asSequence().toList().flatMap { k ->
            (if (k == name) listOf("$path.$k") else emptyList()) + keysNamed(node.get(k), name, "$path.$k")
        }
        is JSONArray -> (0 until node.length()).flatMap { keysNamed(node.get(it), name, "$path[$it]") }
        else -> emptyList()
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

    private fun get(path: String, cookie: String?): Pair<Int, String> {
        val c = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            cookie?.let { setRequestProperty("Cookie", "yk_session=$it") }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        return code to text
    }
}
