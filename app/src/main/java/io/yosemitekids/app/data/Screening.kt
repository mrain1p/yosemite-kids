package io.yosemitekids.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Feed-side gate. Holds the active config, answers "may the kid see this video?"
 * synchronously from the verdict cache, and screens the unscreened in background
 * batches. Fail-closed by design: no verdict (yet) means not visible.
 */
/** [store] is public for the gates that share its verdicts (DownloadChecker). */
class Screener(val store: ScreeningStore) {

    @Volatile var config: AiConfig = AiConfig()
    /** Parent allow-overrides already resolved for the active kid (global + per-kid). */
    @Volatile var allowedOverrides: Set<String> = emptySet()
    /** Channel name → the parents' channel-specific instructions (see WhitelistEntry.aiNote). */
    @Volatile var channelNotes: Map<String, String> = emptyMap()
    /** The family's kids — screening judges all of them in one call. */
    @Volatile var profiles: List<Profile> = emptyList()
    /** Whose verdicts gate visibility right now; null = pre-profile behavior. */
    @Volatile var activeProfileId: String? = null

    private val inFlight = mutableSetOf<String>()

    companion object {
        /**
         * At most two AI calls in flight across all sources. A whole-catalog warm
         * kicks one screenAsync per source, and an unthrottled burst trips provider
         * rate limits — which used to strand every batch as "unscreened" (= hidden).
         */
        private val aiCalls = Semaphore(2)

        /** A failed batch retries after these delays before giving up until the next feed load. */
        private val RETRY_DELAYS_MS = longArrayOf(30_000, 120_000)
    }

    /**
     * Whether the kid may see this video right now. With screening off, always.
     * With it on: parent override wins, then a current-rules ALLOW verdict; anything
     * else (blocked, needs-review, not yet screened) stays hidden.
     */
    fun isVisible(video: Video): Boolean {
        if (!config.enabled) return true
        val id = video.videoId ?: return false
        if (id in allowedOverrides) return true
        val e = store.get(id) ?: return false
        if (e.rulesVersion != config.rulesVersion) return false
        if (e.verdictFor(activeProfileId) != AiScreener.Verdict.ALLOW) return false
        // An ALLOW earned under a different channel note is unproven against
        // the current one — fail closed until the re-screen lands. Entries
        // whose strictest verdict is BLOCK are exempt (they never re-screen,
        // so a per-kid ALLOW inside one must not go permanently dark).
        return e.verdict == AiScreener.Verdict.BLOCK ||
            e.noteHash == AiScreener.noteHash(channelNotes[video.channelName])
    }

    /**
     * Whether [isVisible]'s "hidden" would merely mean "no verdict yet" — i.e. a
     * screening call could still clear it, as opposed to an existing deny. Lets
     * search count "awaiting screening" separately from "held for review".
     */
    fun needsScreening(video: Video): Boolean {
        val cfg = config
        if (!cfg.enabled) return false
        val id = video.videoId ?: return false
        if (id in allowedOverrides) return false
        val e = store.get(id) ?: return true
        if (e.rulesVersion != cfg.rulesVersion) return true
        // Blocked stays blocked across note edits — the parent's ask was
        // "filter more junk", not "re-litigate what's already out".
        if (e.verdict == AiScreener.Verdict.BLOCK) return false
        return e.noteHash != AiScreener.noteHash(channelNotes[video.channelName])
    }

    /**
     * Screens whatever in [videos] has no current verdict, in batches, calling
     * [onUpdated] as each batch of verdicts lands so the UI can re-filter. A failed
     * batch retries with backoff ([RETRY_DELAYS_MS]) — transient provider errors
     * must not strand videos in the unscreened (hidden) state until the next feed
     * load, which on an idle device may be days away.
     */
    fun screenAsync(scope: CoroutineScope, videos: List<Video>, onUpdated: () -> Unit) {
        val cfg = config
        if (!cfg.enabled || cfg.model.isBlank()) return

        val todo = synchronized(inFlight) {
            videos
                .filter { v ->
                    val id = v.videoId ?: return@filter false
                    // needsScreening owns the staleness rules (rules version,
                    // channel-note hash, blocked-stays-blocked, overrides).
                    id !in inFlight && needsScreening(v)
                }
                .distinctBy { it.videoId }
                .also { list -> inFlight += list.mapNotNull { it.videoId } }
        }
        if (todo.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            todo.chunked(30).forEach { batch ->
                try {
                    screenBatch(cfg, batch, onUpdated)
                } finally {
                    // Also on cancellation — otherwise the ids stay "in flight"
                    // forever and are never retried while the app lives.
                    synchronized(inFlight) { inFlight -= batch.mapNotNull { it.videoId }.toSet() }
                }
            }
        }
    }

    private suspend fun screenBatch(cfg: AiConfig, batch: List<Video>, onUpdated: () -> Unit) {
        var attempt = 0
        while (true) {
            val results = try {
                aiCalls.withPermit {
                    AiScreener.screen(cfg, batch, profiles) { channelNotes[it.channelName] }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("YosemiteKids",
                    "AI screening batch failed (attempt ${attempt + 1})", e)
                if (attempt >= RETRY_DELAYS_MS.size) return
                delay(RETRY_DELAYS_MS[attempt])
                attempt++
                continue
            }
            val byId = batch.associateBy { it.videoId }
            // A play-press deep check can land for one of these ids while the
            // batch was in flight; its verdict judged the actual content and
            // must not be replaced by this title-only one. A deep verdict made
            // under an *outdated* channel note is the exception — overwriting
            // it un-hides the video now, and the play gate re-runs its own
            // deep pass (the entry loses the deep flag) at the next press.
            val fresh = results.filterNot { r ->
                val existing = store.get(r.videoId) ?: return@filterNot false
                val note = byId[r.videoId]?.channelName?.let { channelNotes[it] }
                existing.deep && existing.rulesVersion == cfg.rulesVersion &&
                    (existing.verdict == AiScreener.Verdict.BLOCK ||
                        existing.noteHash == AiScreener.noteHash(note))
            }
            store.putAll(fresh.associate { r ->
                val v = byId[r.videoId]
                r.videoId to ScreeningStore.Entry(
                    verdict = r.verdict,
                    reason = r.reason,
                    title = v?.title ?: "",
                    channel = v?.channelName ?: "",
                    thumb = v?.thumbnailUrl,
                    rulesVersion = cfg.rulesVersion,
                    at = System.currentTimeMillis(),
                    perProfile = r.perProfile,
                    noteHash = AiScreener.noteHash(v?.channelName?.let { channelNotes[it] })
                )
            })
            onUpdated()
            return
        }
    }
}
