package io.yosemitekids.hub

import io.yosemitekids.app.data.ConfigJson
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

/**
 * Who the hub thinks a device is, and whether "this device is for Emma"
 * actually reaches that device.
 *
 * The bug this pins was invisible from either end. The hub wrote
 * `deviceProfiles` under the enrolment token **it** had minted; every device
 * resolves that map by its own pairing token (`ConfigSync.kidHere`, `Stats`,
 * `SettingsDevices`). Two different keys, no error, no log line: the setting
 * saved, synced and was ignored by everything, and a map lookup that misses
 * is indistinguishable from a device nobody assigned.
 *
 * So the assertion that matters here is not "the hub stored something". It is
 * that the hub stored it under **the key a device reads**, which is why the
 * lookups below are written the long way rather than through a helper.
 */
class HubDeviceIdTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val T = 1_780_000_000_000L
    private val ADMIN = "test-admin-token"

    /** What a real device sends: its own pairing token, 32 hex. */
    private val TV_ID = "aa11bb22cc33dd44ee55ff6600778899"
    private val TABLET_ID = "99887766ff55ee44dd33cc22bb11aa00"

    private lateinit var store: HubStore
    private lateinit var tokens: HubTokens
    private lateinit var server: HubServer
    private var port = 0

    @Before
    fun setUp() {
        val dir = tmp.newFolder("data")
        store = HubStore(dir)
        tokens = HubTokens(dir)
        server = HubServer(store, tokens, 0, ADMIN, "/data") { T }
        port = server.start()
    }

    @After
    fun tearDown() = server.stop()

    // --- the property -----------------------------------------------------

    @Test
    fun theIdTheHubStoresIsTheIdTheDeviceReadsBack() {
        val enrolment = enrol("Living Room TV")
        sync(enrolment, TV_ID)

        assertEquals(HubWeb.Assigned.OK, assign(enrolment, "leo"))

        // Exactly the lookup ConfigSync.kidHere performs on the device:
        // config.deviceProfiles[PairingStore.deviceToken()].
        val arrived = ConfigJson.fromJson(store.raw()!!).deviceProfiles
        assertEquals("the TV must find itself in the config it syncs down", "leo", arrived[TV_ID])
        assertNull(
            "and nothing may be filed under the hub's own enrolment token — no device has heard of it",
            arrived[enrolment]
        )
    }

    @Test
    fun aDeviceThatHasNeverCalledIsUnavailableRatherThanSilentlyWrong() {
        val enrolment = enrol("A TV that has never opened the app")

        assertEquals(HubWeb.Assigned.NEVER_CALLED, assign(enrolment, "leo"))
        assertTrue("nothing may be written under a key nobody would read", deviceProfiles().isEmpty())

        // And the page is told, so it can offer no chips instead of chips
        // that appear to work.
        val dev = devicesInState().single()
        assertFalse(dev.getBoolean("known"))
        assertEquals("", dev.getString("kid"))
    }

    @Test
    fun assigningAnUnknownReferenceChangesNothing() {
        assertEquals(
            HubWeb.Assigned.NO_SUCH_DEVICE,
            HubWeb.assignDevice(store, tokens, "The hub", T, "nosuchdv", "leo")
        )
        assertTrue(deviceProfiles().isEmpty())
    }

    @Test
    fun handingADeviceBackToThePickerClearsItsEntry() {
        val enrolment = enrol("Living Room TV")
        sync(enrolment, TV_ID)
        assign(enrolment, "leo")
        assertEquals("leo", deviceProfiles()[TV_ID])

        assertEquals(HubWeb.Assigned.OK, assign(enrolment, ""))
        assertNull(deviceProfiles()[TV_ID])
    }

    @Test
    fun twoDevicesGetTwoEntries() {
        // The failure a single shared key would produce is one kid's device
        // silently governing the other's, so this is worth stating.
        val tv = enrol("Living Room TV")
        val tablet = enrol("Noa's tablet")
        sync(tv, TV_ID)
        sync(tablet, TABLET_ID)

        assign(tv, "leo")
        assign(tablet, "noa")

        assertEquals(mapOf(TV_ID to "leo", TABLET_ID to "noa"), deviceProfiles())
    }

    @Test
    fun theHubClearsTheEntryItUsedToWriteUnderTheEnrolmentToken() {
        // Households that ran the broken build carry a deviceProfiles entry
        // keyed by an enrolment token. It names no device, so it is inert —
        // but it is a `dev|<token>` unit in the sync blob, propagated to the
        // whole fleet for ever. The fix takes its own litter with it.
        val enrolment = enrol("Living Room TV")
        store.edit("The hub", T) { it.copy(deviceProfiles = mapOf(enrolment to "leo")) }
        assertEquals("leo", deviceProfiles()[enrolment])

        sync(enrolment, TV_ID)
        assign(enrolment, "leo")

        assertEquals(mapOf(TV_ID to "leo"), deviceProfiles())
    }

    // --- identity, first-writer-wins -------------------------------------

    @Test
    fun theFirstIdentityUnderAnEnrolmentIsTheOneThatSticks() {
        // A pairing token is minted once per install and dies with it, and a
        // reinstall loses this enrolment too — so one enrolment maps to one
        // identity for its whole life. A second is a restored backup or a
        // lie, and overwriting would re-point an assignment a parent already
        // made at a different device.
        val enrolment = enrol("Living Room TV")
        sync(enrolment, TV_ID)
        assign(enrolment, "leo")

        sync(enrolment, TABLET_ID)

        val device = tokens.devices().single()
        assertEquals(TV_ID, device.deviceId)
        assertTrue("and the disagreement is recorded rather than swallowed", device.idConflict)
        assertEquals("leo", deviceProfiles()[TV_ID])
        assertNull(deviceProfiles()[TABLET_ID])
        assertTrue(devicesInState().single().getBoolean("idConflict"))
    }

    @Test
    fun anOlderDeviceThatAnnouncesNoIdentityLeavesTheOneWeHave() {
        val enrolment = enrol("Living Room TV")
        sync(enrolment, TV_ID)
        sync(enrolment, null)
        assertEquals(TV_ID, tokens.devices().single().deviceId)
        assertFalse(tokens.devices().single().idConflict)
    }

    @Test
    fun aDeviceWhoseServerHasNotBoundCanStillSayWhoItIs() {
        // X-Device-Port is omitted until the device's own LAN server binds,
        // and the two facts are recorded independently: the identity must not
        // ride on the port, or a device that never bound could never be
        // assigned.
        val enrolment = enrol("Living Room TV")
        call("/status", enrolment, mapOf("X-Device-Id" to TV_ID))
        val device = tokens.devices().single()
        assertEquals(TV_ID, device.deviceId)
        assertNull("and it is still not nudgeable, which is the existing rule", device.address)
    }

    @Test
    fun anUnauthenticatedCallerCannotClaimAnIdentity() {
        val enrolment = enrol("Living Room TV")
        assertEquals(401, call("/status", "not-a-token", mapOf("X-Device-Id" to TV_ID)).first)
        assertNull(tokens.devices().single { it.token == enrolment }.deviceId)
    }

    // --- plumbing ---------------------------------------------------------

    private fun deviceProfiles(): Map<String, String> =
        store.raw()?.let { ConfigJson.fromJson(it).deviceProfiles } ?: emptyMap()

    private fun devicesInState() =
        JSONObject(HubWeb.state(store, tokens, "/data", T)).getJSONArray("devices").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }

    private fun assign(enrolmentToken: String, kid: String) =
        HubWeb.assignDevice(store, tokens, "The hub", T, HubWeb.deviceRef(enrolmentToken), kid)

    /** Enrol the way a real device does, and return the hub-minted token. */
    private fun enrol(name: String): String {
        val (code, body) = call("/enrol", null, emptyMap(), "POST", JSONObject().put("name", name).toString())
        assertEquals(200, code)
        val c = JSONObject(body).getString("code")
        val (ac, ab) = call(
            "/approve", null, mapOf("X-Admin-Token" to ADMIN), "POST",
            JSONObject().put("code", c).toString()
        )
        assertEquals(200, ac)
        return JSONObject(ab).getString("token")
    }

    /** One ordinary authenticated call, exactly as `LanClient.raw` stamps it. */
    private fun sync(enrolmentToken: String, deviceId: String?) {
        val headers = mutableMapOf("X-Device-Port" to "8765")
        deviceId?.let { headers["X-Device-Id"] = it }
        assertEquals(200, call("/status", enrolmentToken, headers).first)
    }

    private fun call(
        path: String,
        token: String?,
        headers: Map<String, String>,
        method: String = "GET",
        body: String? = null
    ): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        token?.let { c.setRequestProperty("X-Token", it) }
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
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
}
