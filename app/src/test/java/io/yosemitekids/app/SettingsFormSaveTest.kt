package io.yosemitekids.app

import io.yosemitekids.app.data.AiConfig
import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.ConfigStore
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import io.yosemitekids.app.ui.FormSave
import io.yosemitekids.app.ui.SettingsForm
import io.yosemitekids.app.ui.saveForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Modifier

/**
 * The settings form's save path, driven the way the screen drives it:
 * build the config from the form, save it against the baseline, adopt the
 * stamped result as the new baseline AND the new form. Three saves of an
 * unchanged form must be one save.
 *
 * What went wrong before this existed: the screen adopted the stamped result
 * as its baseline and kept its own lists. Anything the stamper had carried in
 * from disk — a co-parent's channel that landed under the open form — was in
 * the baseline and not in the form, so the next tap showed the stamper a
 * unit in `base` and not in `next`: a deletion, tombstoned and propagated.
 * And `ai` took the disk's copy into the baseline while the form kept its
 * own, so every tap re-minted the AI unit and the feed read "changed
 * screening" with nobody touching screening.
 */
class SettingsFormSaveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val MUM = "99887766"
    private val DAD = "a1b2c3d4"

    private fun entry(id: String, label: String? = null) = WhitelistEntry(
        id = id,
        url = "https://www.youtube.com/channel/$id",
        label = label,
        kind = SourceKind.CHANNEL
    )

    private fun seed() = Whitelist(
        sources = listOf(entry("UCaaa", "SciShow Kids")),
        blockedVideoIds = setOf("v1"),
        profiles = listOf(Profile(id = "k1", name = "Leo", age = 6, pin = "1234", limits = Limits(sessionMinutes = 45))),
        blockedFor = mapOf("v2" to setOf("k1")),
        deviceProfiles = mapOf("tv-token" to "k1"),
        ai = AiConfig(enabled = true, model = "some/model", rules = "no pranks", childAge = 6)
    )

    /** A store over a fresh file holding [seed], as the family's phone would open it. */
    private fun opened(config: Whitelist = seed()): Triple<ConfigStore, SettingsForm, Whitelist> {
        val store = ConfigStore(File(tmp.root, "config.json"))
        store.save(config)
        val onDisk = store.load()
        return Triple(store, SettingsForm.of(onDisk), onDisk)
    }

    /** One tap's worth of the screen's save path. */
    private fun save(store: ConfigStore, form: SettingsForm, baseline: Whitelist): FormSave =
        saveForm(store, form.toConfig(baseline), baseline, who = "Mum's phone", by = MUM)

    @Test
    fun threeSavesOfAnUnchangedFormAreOneSave() {
        val (store, opened, baseline0) = opened()
        // One real edit first, so there is a stamp of this phone's to hold still.
        val first = save(store, opened.copy(sponsorSkip = false), baseline0)
        assertTrue(first.baseline.sync.at.containsKey(ConfigStamp.SETTINGS))

        var form = first.form
        var baseline = first.baseline
        repeat(3) { n ->
            val again = save(store, form, baseline)
            assertEquals("save ${n + 2}: no stamp may move", first.baseline.sync.at, again.baseline.sync.at)
            assertEquals("save ${n + 2}: no tombstone may appear", first.baseline.sync.gone, again.baseline.sync.gone)
            assertEquals("save ${n + 2}: no log line may be written", first.baseline.sync.log, again.baseline.sync.log)
            assertEquals("save ${n + 2}: the document is the same document", first.baseline, again.baseline)
            assertEquals("and the form is still the form", first.form, again.form)
            form = again.form
            baseline = again.baseline
        }
        // What is on disk agrees with what the form holds.
        assertEquals(first.baseline.sync.at, store.load().sync.at)
    }

    @Test
    fun aChannelThatLandsUnderTheOpenFormSurvivesEveryLaterTap() {
        val (store, opened, baseline0) = opened()

        // Dad's push lands while Mum's form is open: a channel she never saw.
        store.save(store.load().let { it.copy(sources = it.sources + entry("UCbbb", "Dad's add")) }, who = "Dad's phone", by = DAD)
        val dadsStamp = store.load().sync.at.getValue(ConfigStamp.src("UCbbb"))

        // Tap 1: something unrelated. The stamper carries Dad's channel and
        // the form adopts it.
        val tap1 = save(store, opened.copy(sponsorSkip = false), baseline0)
        assertTrue("the form now holds the carried channel", tap1.form.sources.any { it.id == "UCbbb" })
        assertFalse(tap1.baseline.sync.gone.containsKey(ConfigStamp.src("UCbbb")))

        // Tap 2: this is the tap that used to delete it.
        val tap2 = save(store, tap1.form.copy(showVideoAge = true), tap1.baseline)
        assertTrue("Dad's channel is still there", tap2.baseline.sources.any { it.id == "UCbbb" })
        assertFalse(
            "a channel nobody removed must not be tombstoned",
            tap2.baseline.sync.gone.containsKey(ConfigStamp.src("UCbbb"))
        )
        assertEquals(
            "adopting it is not an add either — Dad's stamp stands",
            dadsStamp, tap2.baseline.sync.at.getValue(ConfigStamp.src("UCbbb"))
        )

        // Tap 3: nothing changed. Still there, still his.
        val tap3 = save(store, tap2.form, tap2.baseline)
        assertEquals(tap2.baseline, tap3.baseline)
        assertTrue(store.load().sources.any { it.id == "UCbbb" })
    }

    @Test
    fun aRulesChangeBumpsTheVersionOnceAndNoLaterTapReMintsScreening() {
        val (store, opened, baseline0) = opened()
        val v0 = baseline0.ai.rulesVersion
        // The seed save minted its own "changed screening" (ai from default to
        // enabled, by nobody); count from there, not from zero.
        val screeningLines = { w: io.yosemitekids.app.data.Whitelist -> w.sync.log.count { it.text == "changed screening" } }
        val before = screeningLines(baseline0)

        val edited = save(store, opened.copy(ai = opened.ai.copy(rules = "no pranks, no slime")), baseline0)
        assertEquals("a judging change bumps the version", v0 + 1, edited.baseline.ai.rulesVersion)
        assertEquals("and the form adopts the bumped version", v0 + 1, edited.form.ai.rulesVersion)
        val aiStamp = edited.baseline.sync.at.getValue(ConfigStamp.AI)
        assertEquals(before + 1, screeningLines(edited.baseline))

        // An unrelated tap. The old form kept rulesVersion v0, so this save
        // read `ai` as edited again: re-minted, "changed screening" again,
        // and the version written BACK to v0.
        val tap = save(store, edited.form.copy(sponsorSkip = false), edited.baseline)
        assertEquals("the version does not regress", v0 + 1, tap.baseline.ai.rulesVersion)
        assertEquals("the AI stamp does not move", aiStamp, tap.baseline.sync.at.getValue(ConfigStamp.AI))
        assertEquals("no second 'changed screening'", before + 1, screeningLines(tap.baseline))

        val idle = save(store, tap.form, tap.baseline)
        assertEquals(tap.baseline, idle.baseline)
    }

    @Test
    fun aCoParentsScreeningEditIsAdoptedNotOverwritten() {
        val (store, opened, baseline0) = opened()

        // Dad changed the rules while Mum's form sat open.
        store.save(store.load().let { it.copy(ai = it.ai.copy(rules = "Dad's rules", rulesVersion = 7)) }, who = "Dad's phone", by = DAD)
        val dadsAiStamp = store.load().sync.at.getValue(ConfigStamp.AI)

        val tap = save(store, opened.copy(sponsorSkip = false), baseline0)
        assertEquals("the form takes Dad's rules rather than writing its stale copy back", "Dad's rules", tap.form.ai.rules)
        assertEquals(7, tap.form.ai.rulesVersion)
        assertEquals("the AI unit is still Dad's edit", dadsAiStamp, tap.baseline.sync.at.getValue(ConfigStamp.AI))
        assertFalse("nothing here touched screening", tap.baseline.sync.log.any { it.by == MUM && it.text == "changed screening" })

        val again = save(store, tap.form, tap.baseline)
        assertEquals(tap.baseline, again.baseline)
    }

    @Test
    fun aKeyTypedBeforeTheModelIsPickedSurvivesAnUnrelatedSave() {
        // AI not in use yet: no model, not enabled. The store never overlays
        // a key onto such a config, so the disk copy the stamper compares
        // against reads keyless — and used to hand the form a keyless `ai`
        // to adopt, which the save that then picked the model stored over
        // the real key.
        val (store, opened, baseline0) = opened(seed().copy(ai = AiConfig()))

        val typed = save(store, opened.copy(ai = opened.ai.copy(apiKey = "sk-typed")), baseline0)
        assertEquals("sk-typed", typed.form.ai.apiKey)

        val unrelated = save(store, typed.form.copy(sponsorSkip = false), typed.baseline)
        assertEquals("the key the form typed is still in the form", "sk-typed", unrelated.form.ai.apiKey)
        assertEquals(
            "and the unrelated save did not re-mint screening",
            typed.baseline.sync.at[ConfigStamp.AI], unrelated.baseline.sync.at[ConfigStamp.AI]
        )

        val picked = save(store, unrelated.form.copy(ai = unrelated.form.ai.copy(model = "some/model", enabled = true)), unrelated.baseline)
        assertEquals("picking the model ships the key, not a blank", "sk-typed", picked.form.ai.apiKey)
        assertTrue(picked.json!!.contains("sk-typed"))
    }

    @Test
    fun adoptingASaveIsAFixedPointOfTheForm() {
        // Whatever the stamper returns, opening it as a form and building the
        // config back must reproduce it exactly — that equality is what makes
        // the screen's auto-save see nothing to do after a save.
        val (store, _, baseline0) = opened()
        val again = SettingsForm.of(baseline0).toConfig(baseline0)
        assertEquals(baseline0, again)
        assertEquals(store.load(), again)
    }

    @Test
    fun theFormNamesItsFieldsAsTheConfigDoes() {
        // Guard 1 reads `sources = entries` and friends out of
        // buildCurrentConfig and checks each name against SettingsSurface,
        // whose names are config fields. That only means something while
        // the form's fields are named as the config's are.
        val configFields = Whitelist::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }.toSet()
        val formFields = SettingsForm::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
        assertTrue(formFields.isNotEmpty())
        formFields.forEach { f ->
            assertTrue("SettingsForm.$f is not a Whitelist field — rename it, or guard 1 checks a name that does not exist", f in configFields)
        }
    }
}
