package io.yosemitekids.hub

import io.yosemitekids.app.data.ControlKind
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SettingsSurface
import io.yosemitekids.app.data.Whitelist
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The manifest's declared ranges, on the box rather than in the markup.
 *
 * `SettingsControl.min`/`max` carry the words "the range the phone enforces,
 * mirrored by the hub". The phone does enforce them. The hub put them on the
 * `<input>` as min/max attributes and nothing else — which stops a spinner
 * and stops nothing at all typed, pasted, sent by an older build, or sent by
 * anything that is not that page being used the way it was drawn. Whatever
 * arrived went onto the disk, and from there to every device in the house.
 *
 * A session of 100000 minutes is not a session. A break of zero is a break
 * that never ends. A child aged 0 is a screening prompt with a nonsense in
 * it, sent to a model that will answer it anyway.
 */
class HubRangeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_780_000_000_000L

    private fun store(): HubStore = HubStore(tmp.newFolder("hub"))

    private fun rangeOf(id: String): IntRange {
        val c = SettingsSurface.controls.single { it.id == id }
        return c.min!!..c.max!!
    }

    @Test
    fun theFamilysNumbersAreHeldToTheirDeclaredRange() {
        val store = store()
        val session = rangeOf("rules-session")
        store.edit("test", now) { Whitelist(emptyList(), emptySet()) }

        HubWeb.applyPatch(
            store, "test", now,
            JSONObject().put("limits", JSONObject().put("session", 100_000).put("breakMinutes", 0))
        )

        val limits = store.load().limits
        assertEquals(session.last, limits.sessionMinutes)
        assertEquals(rangeOf("rules-break").first, limits.breakMinutes)
    }

    @Test
    fun aKidsNumbersAreHeldToTheSameRange() {
        val store = store()
        store.edit("test", now) {
            Whitelist(emptyList(), emptySet(), profiles = listOf(Profile(id = "ada", name = "Ada")))
        }

        val profiles = JSONArray().put(
            JSONObject().put("id", "ada").put("name", "Ada")
                .put("limits", JSONObject().put("session", 0).put("weekdaySessions", 900))
        )
        HubWeb.applyPatch(store, "test", now, JSONObject().put("profiles", profiles))

        val limits = store.load().profiles.single().limits
        assertEquals(rangeOf("rules-session").first, limits.sessionMinutes)
        assertEquals(rangeOf("rules-weekday-sessions").last, limits.weekdaySessions)
    }

    @Test
    fun aNumberInsideItsRangeIsLeftAlone() {
        val store = store()
        store.edit("test", now) { Whitelist(emptyList(), emptySet()) }
        HubWeb.applyPatch(store, "test", now, JSONObject().put("limits", JSONObject().put("session", 25)))
        assertEquals(25, store.load().limits.sessionMinutes)
    }

    @Test
    fun everyNumberControlTheConsoleDrawsDeclaresARange() {
        // The clamp can only be as complete as the manifest. A NUMBER control
        // with no range is a field with no floor and no ceiling on either
        // face, and it would pass this whole file in silence.
        val loose = SettingsSurface.controls
            .filter { it.kind == ControlKind.NUMBER && (it.min == null || it.max == null) }
            .map { it.id }
        assertTrue(
            "these NUMBER controls declare no range, so nothing enforces one: $loose",
            loose.isEmpty()
        )
    }
}
