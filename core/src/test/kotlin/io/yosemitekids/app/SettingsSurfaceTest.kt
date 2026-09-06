package io.yosemitekids.app

import io.yosemitekids.app.data.AiConfig
import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.ControlKind
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.SettingsControl
import io.yosemitekids.app.data.SettingsSurface
import io.yosemitekids.app.data.Where
import io.yosemitekids.app.data.Whitelist
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings manifest, held to the two things a browser and a Compose screen
 * both bet on: that a control id means one control, and that the path a
 * control claims to write is a real place in the config.
 *
 * Guard 26 checks the same shape by reading the source, which is what lets CI
 * run it with no Gradle. This checks it against the actual classes: a rename
 * that a regex would still match — `sessionMinutes` to `sessionMins` in a file
 * the guard scans differently — fails here.
 */
class SettingsSurfaceTest {

    private val controls = SettingsSurface.controls

    @Test
    fun everyControlIdIsUnique() {
        // An id is how the phone asks for a control's words and how the guard
        // finds the hub's card for it. Two controls sharing one means
        // whichever is declared first answers for both, and the second is
        // rendered nowhere while every check reports it present.
        val duplicates = controls.map { it.id }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }
            .keys
        assertEquals("duplicate control ids", emptySet<String>(), duplicates)
    }

    @Test
    fun everyControlWritesARealProperty() {
        controls.filter { it.writes.isNotEmpty() }.forEach { c ->
            assertTrue(
                "${c.id} writes \"${c.writes}\", which is not a property of " +
                    "Whitelist/Limits/AiConfig",
                resolves(c.writes)
            )
        }
    }

    @Test
    fun everyExemptionNamesARealProperty() {
        // A stale exemption is worse than none: it silently absolves a field
        // that no longer exists while the field that replaced it goes
        // unclaimed.
        SettingsSurface.NOT_A_CONTROL.forEach { (path, why) ->
            assertTrue("NOT_A_CONTROL names \"$path\", which is not a property", resolves(path))
            assertTrue("NOT_A_CONTROL[\"$path\"] has no reason", why.isNotBlank())
        }
    }

    @Test
    fun everyConfigLeafIsClaimedExactlyOnce() {
        // The whole point of the manifest, stated as a value: a field with
        // nothing to set it is a field a parent cannot reach on either face,
        // and it fails here on the day it is added rather than in a support
        // message six months later.
        val claimed = controls.mapNotNull { it.writes.ifEmpty { null } }
        val twice = claimed.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals("two controls write the same field", emptySet<String>(), twice)

        val covered = claimed.toSet() + SettingsSurface.NOT_A_CONTROL.keys
        val missing = leaves().filterNot { it in covered }
        assertEquals(
            "these config fields have no control and no recorded exemption",
            emptyList<String>(), missing
        )
    }

    @Test
    fun aControlOnOneFaceOnlySaysWhy() {
        controls.filter { it.where != Where.BOTH }.forEach {
            assertTrue("${it.id} is ${it.where} with no why", it.why.isNotBlank())
        }
    }

    @Test
    fun aGenericControlsJsonPathLandsInThePropertyItClaims() {
        // The contract the hub's renderControl() rests on. It patches the
        // control's `json` path and nothing else; if that key does not parse
        // into the property named by `writes`, the browser writes a value the
        // config drops and the page re-renders with the old one — an edit that
        // silently does nothing, which is the exact failure this whole step
        // exists to stop.
        controls.filter { it.kind != ControlKind.CUSTOM && it.writes.isNotEmpty() }.forEach { c ->
            trialValues(c).forEach { value ->
                val parsed = ConfigJson.fromJson(docWith(c.json, value))
                assertEquals(
                    "${c.id}: patching \"${c.json}\" with $value did not reach ${c.writes}",
                    value, read(parsed, c.writes)
                )
            }
        }
    }

    @Test
    fun everyControlHasWordsToRenderWith() {
        controls.forEach {
            assertTrue("${it.id} has no label", it.label.isNotBlank())
        }
        // A chip control with no chips renders an empty row on the hub and a
        // crash-free nothing on the phone.
        controls.filter { it.kind == ControlKind.CHIPS }.forEach {
            assertTrue("${it.id} is CHIPS with no options", it.options.isNotEmpty())
        }
    }

    @Test
    fun theHubIsOfferedExactlyTheControlsItsGroupsClaim() {
        // hubControls() is what /api/state ships. A control in a group that is
        // not hubReady must not reach the browser, or the page would render an
        // edit for a group the manifest says is not there yet.
        val notReady = SettingsSurface.sections.filter { it.where == Where.PHONE || !it.hubReady }
            .flatMap { it.controls }.map { it.id }.toSet()
        val shipped = SettingsSurface.hubControls().map { it.id }.toSet()
        assertEquals(emptySet<String>(), shipped intersect notReady)
        assertTrue(
            "a hub-eligible control is missing from hubControls()",
            shipped.containsAll(
                SettingsSurface.forHub().flatMap { s -> s.controls.filter { it.where != Where.PHONE } }
                    .map { it.id }
            )
        )
    }

    // --- plumbing ---------------------------------------------------------

    /** Every declared property of the three classes a control may write, as a path. */
    private fun leaves(): List<String> =
        fieldsOf(Whitelist::class.java) +
            fieldsOf(Limits::class.java).map { "limits.$it" } +
            fieldsOf(AiConfig::class.java).map { "ai.$it" }

    private fun fieldsOf(type: Class<*>): List<String> =
        type.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }

    private fun resolves(path: String): Boolean {
        val parts = path.split(".")
        val owner = when (parts.size) {
            1 -> Whitelist::class.java
            2 -> when (parts[0]) {
                "limits" -> Limits::class.java
                "ai" -> AiConfig::class.java
                else -> return false
            }
            else -> return false
        }
        return parts.last() in fieldsOf(owner)
    }

    /** Read a property path off a parsed config, by the same names [resolves] checks. */
    private fun read(config: Whitelist, path: String): Any? {
        var current: Any? = config
        path.split(".").forEach { name ->
            val holder = current ?: return null
            val f = holder.javaClass.getDeclaredField(name)
            f.isAccessible = true
            current = f.get(holder)
        }
        return current
    }

    /** Values worth trying for one control: enough to prove the key is read at all. */
    private fun trialValues(c: SettingsControl): List<Any?> = when (c.kind) {
        // Both, because half of these are omitted from the JSON at their
        // default — a test that only set the default would pass on a key
        // nothing reads.
        ControlKind.TOGGLE -> listOf(true, false)
        ControlKind.NUMBER -> listOf(c.min ?: 1)
        ControlKind.TEXT, ControlKind.TEXTAREA -> listOf("a value")
        ControlKind.CHIPS -> c.options.map { it.value }
        ControlKind.CUSTOM -> emptyList()
    }

    /**
     * An otherwise-empty config with one leaf set. A null value is written as
     * an *absent* key, which is what null means on the wire everywhere in this
     * codebase — `limitsFromJson` asks `has()` before `getInt`, so a literal
     * JSON null would throw rather than mean "no rule".
     */
    private fun docWith(path: String, value: Any?): String {
        val root = JSONObject(ConfigJson.toJson(Whitelist(emptyList(), emptySet())))
        val parts = path.split(".")
        if (parts.size == 1) {
            if (value == null) root.remove(parts[0]) else root.put(parts[0], value)
        } else {
            val parent = root.optJSONObject(parts[0]) ?: JSONObject().also { root.put(parts[0], it) }
            if (value == null) parent.remove(parts[1]) else parent.put(parts[1], value)
        }
        return root.toString()
    }
}
