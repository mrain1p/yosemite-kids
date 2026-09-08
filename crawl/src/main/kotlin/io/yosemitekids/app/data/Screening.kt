package io.yosemitekids.app.data

/**
 * Everything the visibility decision reads, in one value.
 *
 * A record rather than four arguments because the decision has two callers on
 * two faces — the app's [Screener] and, shortly, the hub serving a kid's page
 * to a browser — and a fifth input added to one call site and not the other is
 * exactly the drift this move exists to prevent. Defaults are the pre-config
 * state: screening off, nothing overridden, no notes, no active kid.
 */
data class ScreeningRules(
    val config: AiConfig = AiConfig(),
    /** Parent allow-overrides already resolved for the active kid (global + per-kid). */
    val allowedOverrides: Set<String> = emptySet(),
    /** Channel name → the parents' channel-specific instructions (see WhitelistEntry.aiNote). */
    val channelNotes: Map<String, String> = emptyMap(),
    /** Whose verdicts gate visibility right now; null = pre-profile behavior. */
    val activeProfileId: String? = null
)

/**
 * "May this child see this video?" — the whole of it, in one place.
 *
 * It lives in `:crawl` beside [ScreeningStore] because the store already does:
 * a phone pushes its verdicts to every paired peer, the hub among them, and
 * the hub is about to serve the same catalogue to a browser. A second copy of
 * these rules would not throw. It would let a video the television hides
 * appear on a tablet — and the failure a parent reports is "the filter does
 * not work", with nothing in either log to say which of the two answered.
 *
 * Fail-closed by design: no verdict (yet) means not visible.
 */
object Screening {

    /**
     * Whether the kid may see this video right now. With screening off, always.
     * With it on: parent override wins, then a current-rules ALLOW verdict; anything
     * else (blocked, needs-review, not yet screened) stays hidden.
     */
    fun isVisible(store: ScreeningStore, rules: ScreeningRules, video: Video): Boolean {
        if (!rules.config.enabled) return true
        val id = video.videoId ?: return false
        if (id in rules.allowedOverrides) return true
        val e = store.get(id) ?: return false
        if (e.rulesVersion != rules.config.rulesVersion) return false
        if (e.verdictFor(rules.activeProfileId) != AiScreener.Verdict.ALLOW) return false
        // An ALLOW earned under a different channel note is unproven against
        // the current one — fail closed until the re-screen lands. Entries
        // whose strictest verdict is BLOCK are exempt (they never re-screen,
        // so a per-kid ALLOW inside one must not go permanently dark).
        return e.verdict == AiScreener.Verdict.BLOCK ||
            e.noteHash == AiScreener.noteHash(rules.channelNotes[video.channelName])
    }

    /**
     * Whether [isVisible]'s "hidden" would merely mean "no verdict yet" — i.e. a
     * screening call could still clear it, as opposed to an existing deny. Lets
     * search count "awaiting screening" separately from "held for review".
     */
    fun needsScreening(store: ScreeningStore, rules: ScreeningRules, video: Video): Boolean {
        val cfg = rules.config
        if (!cfg.enabled) return false
        val id = video.videoId ?: return false
        if (id in rules.allowedOverrides) return false
        val e = store.get(id) ?: return true
        if (e.rulesVersion != cfg.rulesVersion) return true
        // Blocked stays blocked across note edits — the parent's ask was
        // "filter more junk", not "re-litigate what's already out".
        if (e.verdict == AiScreener.Verdict.BLOCK) return false
        return e.noteHash != AiScreener.noteHash(rules.channelNotes[video.channelName])
    }
}
