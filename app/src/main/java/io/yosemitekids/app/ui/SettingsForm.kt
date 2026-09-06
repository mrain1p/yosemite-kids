package io.yosemitekids.app.ui

import io.yosemitekids.app.data.AiConfig
import io.yosemitekids.app.data.ConfigStore
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import io.yosemitekids.app.data.screeningJudgmentChanged

/**
 * The settings form's state as one plain value.
 *
 * `AdminScreen` holds each of these in its own Compose state so a page can
 * edit one without recomposing the rest. This is the same twenty fields with
 * no Compose in them, so the shaping a save does — and the save itself,
 * [saveForm] — runs in a JVM test with no Context. The properties are named
 * exactly as the [Whitelist] fields they feed: guard 1 reads the constructor
 * call in `buildCurrentConfig` to check every field the form writes is
 * claimed by a settings group, and `SettingsFormSaveTest` pins the naming
 * that check now rests on.
 */
internal data class SettingsForm(
    val sources: List<WhitelistEntry>,
    val blockedVideoIds: Set<String>,
    val limits: Limits,
    val ai: AiConfig,
    val aiAllowedVideoIds: Set<String>,
    val profiles: List<Profile>,
    val blockedFor: Map<String, Set<String>>,
    val allowedFor: Map<String, Set<String>>,
    val deviceProfiles: Map<String, String>,
    val masterDeviceToken: String?,
    val sponsorSkip: Boolean,
    val autoplayNext: Boolean,
    val suggestSimilar: Boolean,
    val channelLayout: String,
    val channelOrder: String,
    val listenPercent: Int?,
    val qualityTv: Int?,
    val qualityPhone: Int?,
    val pageSize: Int?,
    val showVideoAge: Boolean
) {
    /**
     * The form as a config, shaped for saving. [baseline] is what disk holds
     * as far as the form knows — the open-time snapshot until the first save,
     * then whatever was last written — so a second judging change after a
     * save gets its own version bump, and building twice between saves yields
     * the same version, not two.
     */
    fun toConfig(baseline: Whitelist): Whitelist {
        // Changed rules/age/model mean old verdicts no longer apply — bumping
        // the version makes every device re-screen its catalog against the
        // new rules.
        val judgingChanged = ai.rules != baseline.ai.rules ||
            ai.childAge != baseline.ai.childAge ||
            ai.model != baseline.ai.model ||
            ai.baseUrl != baseline.ai.baseUrl ||
            screeningJudgmentChanged(baseline.profiles, profiles)
        val finalAi = if (judgingChanged) ai.copy(rulesVersion = baseline.ai.rulesVersion + 1) else ai
        // A removed kid must not linger: entries owned only by them fall back
        // to everyone, their per-video rulings and device assignment are dropped.
        val validIds = profiles.map { it.id }.toSet()
        fun scrub(overlay: Map<String, Set<String>>) = overlay
            .mapValues { (_, pids) -> pids.intersect(validIds) }
            .filterValues { it.isNotEmpty() }
        // baseline.copy, never a fresh Whitelist(...). A positional constructor
        // silently defaults out any field this form does not name, and the form
        // does not name the sync blob — so every autosave, every close and
        // every Push would have shipped a config with its bookkeeping erased,
        // and the merge would never have run on the primary path. Copying
        // inherits whatever the model grows next, too.
        return baseline.copy(
            sources = sources.map { e ->
                if (e.profileIds.isEmpty()) e
                else e.copy(profileIds = e.profileIds.intersect(validIds))
            },
            blockedVideoIds = blockedVideoIds,
            limits = limits,
            ai = finalAi,
            aiAllowedVideoIds = aiAllowedVideoIds,
            profiles = profiles,
            blockedFor = scrub(blockedFor),
            allowedFor = scrub(allowedFor),
            deviceProfiles = deviceProfiles.filterValues { it in validIds },
            masterDeviceToken = masterDeviceToken,
            sponsorSkip = sponsorSkip,
            autoplayNext = autoplayNext,
            suggestSimilar = suggestSimilar,
            channelLayout = channelLayout,
            channelOrder = channelOrder,
            listenPercent = listenPercent,
            qualityTv = qualityTv,
            qualityPhone = qualityPhone,
            pageSize = pageSize,
            showVideoAge = showVideoAge
        )
    }

    companion object {
        /** The form a config opens as — and what it becomes after adopting a save. */
        fun of(c: Whitelist) = SettingsForm(
            sources = c.sources,
            blockedVideoIds = c.blockedVideoIds,
            limits = c.limits,
            ai = c.ai,
            aiAllowedVideoIds = c.aiAllowedVideoIds,
            profiles = c.profiles,
            blockedFor = c.blockedFor,
            allowedFor = c.allowedFor,
            deviceProfiles = c.deviceProfiles,
            masterDeviceToken = c.masterDeviceToken,
            sponsorSkip = c.sponsorSkip,
            autoplayNext = c.autoplayNext,
            suggestSimilar = c.suggestSimilar,
            channelLayout = c.channelLayout,
            channelOrder = c.channelOrder,
            listenPercent = c.listenPercent,
            qualityTv = c.qualityTv,
            qualityPhone = c.qualityPhone,
            pageSize = c.pageSize,
            showVideoAge = c.showVideoAge
        )
    }
}

/**
 * What one save hands back to the form. [form] and [baseline] are the same
 * stamped result seen two ways, and the form must take both: see [saveForm].
 * [json] is the wire copy the save wrote — null when the write failed, in
 * which case there is nothing worth pushing.
 */
internal data class FormSave(val form: SettingsForm, val baseline: Whitelist, val json: String?)

/**
 * One save from the settings form. The auto-save, Back and Push all run this,
 * and so does `SettingsFormSaveTest`, three times over on an unchanged form.
 *
 * [current] is `form.toConfig(baseline)`, built by the caller because the
 * composition already holds it. The result is the stamped document, not
 * [current]: stamping carries forward anything a co-parent's push landed
 * under the open form and keeps the disk's copy of any section the editor
 * left alone. Adopting the form's own value as the baseline would read the
 * carried units as fresh adds on the next save, clearing their tombstones;
 * adopting the stamped result as the baseline but not into the form reads
 * them as deletions on the next save and tombstones them. Both halves of the
 * result come from the same document so neither can happen.
 */
internal fun saveForm(
    store: ConfigStore,
    current: Whitelist,
    baseline: Whitelist,
    who: String,
    by: String
): FormSave {
    val saved = store.save(current, base = baseline, who = who, by = by)
    val adopted = saved?.config ?: current
    return FormSave(SettingsForm.of(adopted), adopted, saved?.json)
}
