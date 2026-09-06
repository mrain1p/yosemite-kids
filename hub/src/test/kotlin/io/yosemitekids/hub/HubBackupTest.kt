package io.yosemitekids.hub

import io.yosemitekids.app.data.AiConfig
import io.yosemitekids.app.data.BackupFile
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

/**
 * Taking the family off the box, and putting it back.
 *
 * Two properties, and neither is obvious from reading the route.
 *
 * The first is that a backup carries no credential. The hub is meant never to
 * hold one, and it does not — `HubStore.commit` strips the API key on every
 * write — but a route that serves a document is exactly where that stops
 * being true, quietly, on the day someone builds the file from the in-memory
 * config instead of the bytes on disk. So this checks the served file at
 * every depth rather than trusting the layer below.
 *
 * The second is that a restore is a **stamped edit and never a byte copy**.
 * `HubVersions`' own KDoc lists the four arguments with the merge a byte
 * restore loses; the visible one is that the snapshot carries the OLD stamps,
 * so the first peer that has edited since wins its units straight back and
 * the parent watches the rollback undo itself with nothing reporting it.
 * `aRestoreStandsUpToThePeerThatEditedSinceTheBackup` is that failure written
 * down: it pushes the co-parent's unchanged document back afterwards, which
 * is what a Push button and a fifteen-minute worker actually do.
 */
class HubBackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val admin = "test-admin-token"

    /** A fixed instant, so every stamp in this file is a value it can name. */
    private var clock = 1_780_000_000_000L

    private lateinit var store: HubStore
    private lateinit var tokens: HubTokens
    private lateinit var server: HubServer
    private var port = 0

    private val scishow = WhitelistEntry("UC1", "https://youtube.com/channel/UC1", "SciShow Kids", SourceKind.CHANNEL)
    private val numberblocks = WhitelistEntry("UC2", "https://youtube.com/channel/UC2", "Numberblocks", SourceKind.CHANNEL)
    private val storybots = WhitelistEntry("UC3", "https://youtube.com/channel/UC3", "StoryBots", SourceKind.CHANNEL)

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

    // --- what a backup may carry ------------------------------------------

    @Test
    fun aDownloadedBackupCarriesNoApiKeyAtAnyDepth() {
        // The key reaches this hub on every sweep: ConfigSync pushes
        // `rawJson()`, secrets included, to every peer including this one.
        // What must never happen is it leaving again through a route that
        // reads like a convenience.
        val secret = "sk-or-v1-this-must-never-leave-the-hub"
        store.merge(
            ConfigJson.toJson(
                Whitelist(listOf(scishow), emptySet(), ai = AiConfig(enabled = true, apiKey = secret)),
                includeSecrets = true
            ),
            "a phone"
        )

        val (code, body) = get("/api/backup", signIn()!!)
        assertEquals(200, code)
        assertFalse(
            "the served backup contains the API key itself",
            body.contains(secret)
        )
        assertTrue(
            "the backup names an apiKey field somewhere: $body",
            keysNamed(JSONObject(body), "apiKey").isEmpty()
        )
        // And the file really is the family's settings, or the assertion above
        // would pass on an empty document.
        val inside = BackupFile.configIn(body) ?: error("the file is not a backup at all")
        assertEquals(listOf("UC1"), ConfigJson.fromJson(inside).sources.map { it.id })
    }

    @Test
    fun theBackupIsAFileTheOtherFaceWouldTake() {
        // The day this file is wanted is the day the NAS is gone, and a phone
        // is what is left. Same envelope, same schema.
        store.edit("The hub", clock) { it.copy(sources = listOf(scishow)) }
        val (_, body) = get("/api/backup", signIn()!!)
        val root = JSONObject(body)
        assertEquals(BackupFile.KIND, root.getString("kind"))
        assertEquals(BackupFile.SCHEMA, root.getInt("schema"))
        assertTrue("a backup must carry the sync block, or a restore cannot tell a deletion from an absence",
            root.getJSONObject("config").has("sync"))
    }

    @Test
    fun thereIsNothingToBackUpBeforeThereIsAConfig() {
        // Deliberately a 404 rather than an empty document: an empty config is
        // a perfectly valid one meaning "no channels, no kids, no rules", so
        // handing one out would be handing a parent a file that wipes their
        // family when they restore it.
        assertEquals(404, get("/api/backup", signIn()!!).first)
    }

    // --- what a restore is ------------------------------------------------

    @Test
    fun aRestoreStandsUpToThePeerThatEditedSinceTheBackup() {
        val session = signIn()!!
        store.edit("The hub", clock) { it.copy(sources = listOf(scishow, numberblocks)) }
        val backup = get("/api/backup", session).second

        // A co-parent's phone, editing the document it last synced: it drops
        // Numberblocks and adds StoryBots, an hour after the backup.
        clock += 60 * 60 * 1000
        val coParent = pushFromCoParent { it.copy(sources = listOf(scishow, storybots)) }
        assertEquals(
            listOf("UC1", "UC3"),
            store.load().sources.map { it.id }
        )

        clock += 60 * 60 * 1000
        assertRestored(post("/api/restore", backup, session))
        assertEquals(
            "the restore did not put the backup's channels back",
            listOf("UC1", "UC2"), store.load().sources.map { it.id }
        )

        // The whole point. The co-parent's phone has not synced since, so it
        // pushes the same document again — and again, which is what a Push
        // button and the fifteen-minute worker actually do. A byte restore
        // carries the snapshot's OLD stamps, so this is where the rollback
        // would silently undo itself.
        repeat(3) { store.merge(coParent, "Dad's phone") }
        assertEquals(
            "a peer that edited before the restore won its unit straight back — the restore was not stamped above it",
            listOf("UC1", "UC2"), store.load().sources.map { it.id }
        )
    }

    @Test
    fun aRestoreKeepsTheBookkeepingTheHubLearnedAfterTheBackup() {
        val session = signIn()!!
        store.edit("The hub", clock) { it.copy(sources = listOf(scishow, numberblocks)) }
        val backup = get("/api/backup", session).second

        clock += 60 * 60 * 1000
        pushFromCoParent { it.copy(sources = listOf(scishow)) }
        val tombstoned = store.load().sync.gone.keys.filter { it.startsWith("src|UC2") }
        assertTrue("the co-parent's removal left no tombstone; the test proves nothing", tombstoned.isNotEmpty())

        clock += 60 * 60 * 1000
        assertRestored(post("/api/restore", backup, session))

        val after = store.load()
        // The bytes of the backup carry the sync block from BEFORE the
        // removal. Writing them would have discarded every tombstone this hub
        // has learned since — on a parental-controls app, "press Restore and
        // every channel anyone ever removed comes back" is the worst outcome
        // available.
        assertTrue(
            "the restore discarded a tombstone the hub had learned since the backup",
            after.sync.gone.keys.containsAll(tombstoned)
        )
        // And the co-parent's line is still in the feed a parent reads, for
        // the same reason: bookkeeping comes from the live document.
        assertTrue(
            "the change feed was replaced by the backup's own: ${after.sync.log.map { it.who + " " + it.text }}",
            after.sync.log.any { it.who == "Dad's phone" }
        )
    }

    @Test
    fun aFileThatIsNotABackupChangesNothing() {
        val session = signIn()!!
        store.edit("The hub", clock) { it.copy(sources = listOf(scishow)) }
        val before = ConfigJson.fingerprint(store.load())

        listOf(
            JSONObject().toString(),
            JSONObject().put("kind", "some-other-app").put("config", JSONObject()).toString(),
            JSONObject().put("hello", "world").toString(),
            JSONObject().put("kind", BackupFile.KIND).put("schema", 99)
                .put("config", JSONObject().put("entries", JSONArray()).put("limits", JSONObject()))
                .toString()
        ).forEach { junk ->
            val (code, body) = post("/api/restore", junk, session)
            assertEquals(200, code)
            assertFalse("\"${junk.take(40)}\" was restored", JSONObject(body).getBoolean("restored"))
        }
        assertEquals(
            "something that is not a backup moved the family's settings",
            before, ConfigJson.fingerprint(store.load())
        )
    }

    @Test
    fun neitherRouteAnswersWithoutASession() {
        store.edit("The hub", clock) { it.copy(sources = listOf(scishow)) }
        assertEquals(401, get("/api/backup", null).first)
        assertEquals(401, post("/api/restore", "{}", null).first)
        assertEquals(listOf("UC1"), store.load().sources.map { it.id })
    }

    // --- plumbing ---------------------------------------------------------

    /**
     * A co-parent's phone editing what it last synced and pushing it. Returns
     * the pushed document, so a test can send the *same* one again — a phone
     * that has not re-synced holds exactly this.
     */
    private fun pushFromCoParent(edit: (Whitelist) -> Whitelist): String {
        val base = store.load()
        val stamped = ConfigStamp.stamped(base, base, edit(base), clock, "Dad's phone", "dad12345")
        val document = ConfigJson.toJson(stamped.config)
        store.merge(document, "Dad's phone")
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

    private fun assertRestored(result: Pair<Int, String>) {
        assertEquals(200, result.first)
        assertTrue("refused: ${result.second}", JSONObject(result.second).getBoolean("restored"))
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

    private fun post(path: String, body: String, cookie: String?): Pair<Int, String> {
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
