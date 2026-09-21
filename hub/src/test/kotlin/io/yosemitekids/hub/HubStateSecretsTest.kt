package io.yosemitekids.hub

import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.Pbkdf2
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.Whitelist
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the console is handed, and what it is allowed to send back.
 *
 * `/api/state` renders the parent console, and the easiest way to render a
 * page from a document is to ship the document. So it did - including a
 * child's four-direction code, the PBKDF2 record behind their browser
 * password, the pairing token of the phone that builds the index, and a map
 * keyed by every device's token. The page reads exactly one bit of all of
 * that (`!!k.hasWeb`, to choose between "Set" and "Change") and the file
 * that draws it says, in its own words, that the hub "holds no credential"
 * on a device.
 *
 * The first test is a **sweep, not a list**: it walks the whole reply to any
 * depth and fails if a stored secret appears anywhere in it. A list of four
 * removals would pass for ever while the fifth field rode out unnoticed,
 * which is how these four got there.
 *
 * The second is the other half, and the reason the first is not a one-line
 * change. The console edits a kid by copying the profile object it was
 * given and changing one field, so the moment `/api/state` stops sending
 * `pin`, a rename sends a profile that has none - and a patch is a
 * replacement. Read as written it would wipe a child's code, and the
 * stamper would push that removal to every device in the house before
 * anyone noticed the television stopped asking.
 */
class HubStateSecretsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pin = "UDLR"
    private val masterToken = "master-token-0123456789abcdef"
    private val deviceToken = "device-token-fedcba9876543210"
    private val now = 1_780_000_000_000L

    private fun storeWithSecrets(): Pair<HubStore, Whitelist> {
        val dir = tmp.newFolder("hub")
        val store = HubStore(dir)
        val record = Pbkdf2.record("otter", now)
        val config = Whitelist(
            emptyList(), emptySet(),
            profiles = listOf(
                Profile(id = "ada", name = "Ada", pin = pin, webPassword = record)
            ),
            deviceProfiles = mapOf(deviceToken to "ada"),
            masterDeviceToken = masterToken
        )
        store.edit("test", now) { config }
        return store to config
    }

    /** Every string in a JSON tree, whatever nests it. */
    private fun strings(node: Any?, out: MutableList<String> = mutableListOf()): List<String> {
        when (node) {
            is String -> out.add(node)
            is JSONObject -> {
                node.keys().forEach { k ->
                    out.add(k)
                    strings(node.get(k), out)
                }
            }
            is JSONArray -> (0 until node.length()).forEach { strings(node.get(it), out) }
        }
        return out
    }

    @Test
    fun theConsoleIsHandedNoCredentialAtAnyDepth() {
        val (store, config) = storeWithSecrets()
        val tokens = HubTokens(tmp.newFolder("tokens"))
        val reply = JSONObject(HubWeb.state(store, tokens, "/data", now))
        val seen = strings(reply).toSet()

        val record = config.profiles.first().webPassword!!
        listOf(
            pin to "a child's code for the television",
            record.key to "the hash behind a kid's browser password",
            record.salt to "the salt behind a kid's browser password",
            masterToken to "the pairing token of the phone that builds the index",
            deviceToken to "a device's pairing token, as a deviceProfiles key"
        ).forEach { (secret, what) ->
            assertFalse(
                "/api/state hands the browser $what",
                seen.any { it.contains(secret) }
            )
        }
    }

    @Test
    fun theConsoleIsToldWhetherAKidHasAPasswordAndNotWhatItIs() {
        val (store, _) = storeWithSecrets()
        val tokens = HubTokens(tmp.newFolder("tokens"))
        val kid = JSONObject(HubWeb.state(store, tokens, "/data", now))
            .getJSONObject("config").getJSONArray("profiles").getJSONObject(0)

        assertTrue("the page draws Set or Change from this", kid.optBoolean("hasWeb"))
        assertNull("and from nothing else", kid.opt("web"))
        assertNull(kid.opt("pin"))
        assertEquals("Ada", kid.getString("name"))
    }

    @Test
    fun renamingAKidDoesNotClearTheirCodeOrTheirPassword() {
        val (store, _) = storeWithSecrets()
        val tokens = HubTokens(tmp.newFolder("tokens"))

        // Exactly what the console sends: the profile it was given, with one
        // field changed. It was never given `pin` or `web`.
        val shown = JSONObject(HubWeb.state(store, tokens, "/data", now))
            .getJSONObject("config").getJSONArray("profiles")
        shown.getJSONObject(0).put("name", "Ada B")

        assertTrue(HubWeb.applyPatch(store, "test", now + 1000, JSONObject().put("profiles", shown)))

        val after = store.load().profiles.single()
        assertEquals("Ada B", after.name)
        assertEquals("the rename must not have wiped the television code", pin, after.pin)
        assertNotNull("nor the browser password", after.webPassword)
        assertEquals(masterToken, store.load().masterDeviceToken)
        assertEquals(mapOf(deviceToken to "ada"), store.load().deviceProfiles)
    }

    @Test
    fun aPatchCannotSetACredentialItWasNeverShown() {
        val (store, _) = storeWithSecrets()

        // A tab with a pin of its own invention, which is what a page would
        // have to be doing to send one at all: there is no control for it.
        val invented = JSONArray().put(
            JSONObject(ConfigJson.toJson(store.load())).getJSONArray("profiles").getJSONObject(0)
                .put("pin", "RRRR")
        )
        HubWeb.applyPatch(store, "test", now + 1000, JSONObject().put("profiles", invented))

        assertEquals("a code is set on the phone, never through /api/config", pin, store.load().profiles.single().pin)
    }
}
