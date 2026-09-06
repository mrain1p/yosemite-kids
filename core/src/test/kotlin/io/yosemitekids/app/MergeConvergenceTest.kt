package io.yosemitekids.app

import io.yosemitekids.app.data.AiConfig
import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.ConfigMerge
import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.Limits
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.SourceKind
import io.yosemitekids.app.data.SyncMeta
import io.yosemitekids.app.data.Whitelist
import io.yosemitekids.app.data.WhitelistEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Convergence of the merge under the loops the fleet actually runs.
 *
 * `ConfigMergeTest` proves each rule once. This file runs the rules *again
 * and again* with the inputs held still, because that is what a Push button
 * and a fifteen-minute worker do: the same phone document lands on the same
 * hub many times over. A merge that is a pure function of (hub copy, pushed
 * document) must settle after one round — the skill claims idempotence — and
 * a hub whose log shows the same push landing on alternating hashes is a hub
 * whose merge is not idempotent.
 *
 * Three shapes are exercised, each over several fixture pairs:
 *  - idempotence: merge(merge(a, b), b) is a no-op;
 *  - hash commutativity: merge(a, b) and merge(b, a) fingerprint alike;
 *  - the two-party round trip a phone and a hub actually run.
 *
 * The last test is the one built to reproduce a real log: six pushes from
 * one phone, the hub's fingerprint flipping between two values.
 */
class MergeConvergenceTest {

    private val T = 1_780_000_000_000L

    private fun entry(id: String, label: String? = null) = WhitelistEntry(
        id = id,
        url = "https://www.youtube.com/channel/$id",
        label = label,
        kind = SourceKind.CHANNEL
    )

    /** A config as bytes, with an explicit sync blob so stamps are exact. */
    private fun doc(
        sources: List<WhitelistEntry> = emptyList(),
        blocked: Set<String> = emptySet(),
        blockedFor: Map<String, Set<String>> = emptyMap(),
        profiles: List<Profile> = emptyList(),
        limits: Limits = Limits(),
        ai: AiConfig = AiConfig(),
        autoplay: Boolean = true,
        master: String? = null,
        at: Map<String, Long> = emptyMap(),
        gone: Map<String, Long> = emptyMap()
    ): String = pinUpdatedAt(
        ConfigJson.toJson(
            Whitelist(
                sources = sources,
                blockedVideoIds = blocked,
                blockedFor = blockedFor,
                limits = limits,
                ai = ai,
                profiles = profiles,
                autoplayNext = autoplay,
                masterDeviceToken = master,
                sync = if (at.isEmpty() && gone.isEmpty()) SyncMeta.EMPTY
                else SyncMeta(docAt = (at.values + gone.values + 0L).max(), at = at, gone = gone)
            )
        ),
        T
    )

    /**
     * `toJson` stamps updatedAt from the wall clock and the merge's sameDoc
     * compares it, so it is pinned — every document here is a function of its
     * arguments alone (the same reason ConfigMergeTest pins it).
     */
    private fun pinUpdatedAt(json: String, value: Long): String =
        JSONObject(json).put("updatedAt", value).toString(2)

    /** One merge, falling back to the local bytes when there was nothing to write. */
    private fun settle(local: String, incoming: String): String =
        ConfigMerge.merge(local, incoming).merged ?: local

    /**
     * What a device advertises on /status and what the hub prints in its log:
     * the content fingerprint and the bookkeeping fingerprint. The hub's
     * "merged a push → #hash" line is the first of these (HubStore.merge), and
     * it logs whenever either moves.
     */
    private fun hashes(json: String): String {
        val w = ConfigJson.fromJson(json)
        return "#${ConfigJson.fingerprint(w)}/${ConfigMerge.syncHash(w.sync)}"
    }

    /**
     * The phone never pushes its disk bytes. `ConfigStore.rawJson()` is
     * `toJson(loadForPeers())`, a full trip through the model, so model that
     * rather than hand the merge output straight back.
     */
    private fun asPushedByPhone(disk: String): String =
        pinUpdatedAt(ConfigJson.toJson(ConfigJson.fromJson(disk)), T)

    // --- fixtures -------------------------------------------------------

    private val kid = Profile(id = "k1", name = "Leo")

    /** Named pairs, so a failure says which shape broke rather than "pair 3". */
    private fun pairs(): List<Triple<String, String, String>> = listOf(
        Triple(
            "disjoint adds",
            doc(sources = listOf(entry("UCaaa")), at = mapOf(ConfigStamp.src("UCaaa") to T + 1)),
            doc(sources = listOf(entry("UCbbb")), at = mapOf(ConfigStamp.src("UCbbb") to T + 2))
        ),
        Triple(
            "same channel, equal stamps, different labels",
            doc(sources = listOf(entry("UCaaa", "Name A")), at = mapOf(ConfigStamp.src("UCaaa") to T)),
            doc(sources = listOf(entry("UCaaa", "Name B")), at = mapOf(ConfigStamp.src("UCaaa") to T))
        ),
        Triple(
            "a tombstone against a stale copy",
            doc(sources = listOf(entry("UCaaa")), at = mapOf(ConfigStamp.src("UCaaa") to T + 1)),
            doc(gone = mapOf(ConfigStamp.src("UCaaa") to T + 2))
        ),
        Triple(
            "a deliberate re-add against the tombstone holder",
            doc(
                sources = listOf(entry("UCaaa")),
                at = mapOf(ConfigStamp.src("UCaaa") to T + 3),
                gone = mapOf(ConfigStamp.src("UCaaa") to T + 2)
            ),
            doc(gone = mapOf(ConfigStamp.src("UCaaa") to T + 2))
        ),
        Triple(
            "a block against an unblock that never saw it",
            doc(blocked = setOf("v1"), at = mapOf(ConfigStamp.blk("v1") to T + 1)),
            doc(gone = mapOf(ConfigStamp.blk("v1") to T + 5))
        ),
        Triple(
            "a block against a deliberate unblock (proof kept, as the stamper writes it)",
            doc(blocked = setOf("v1"), at = mapOf(ConfigStamp.blk("v1") to T + 1)),
            doc(at = mapOf(ConfigStamp.blk("v1") to T + 1), gone = mapOf(ConfigStamp.blk("v1") to T + 5))
        ),
        Triple(
            "a per-kid block against a deliberate per-kid unblock",
            doc(
                profiles = listOf(kid), blockedFor = mapOf("v1" to setOf("k1")),
                at = mapOf(ConfigStamp.kid("k1") to T, ConfigStamp.forKid("v1", "k1") to T + 1)
            ),
            doc(
                profiles = listOf(kid),
                at = mapOf(ConfigStamp.kid("k1") to T, ConfigStamp.forKid("v1", "k1") to T + 1),
                gone = mapOf(ConfigStamp.forKid("v1", "k1") to T + 5)
            )
        ),
        Triple(
            "screening rules that differ on a stamp tie",
            doc(ai = AiConfig(enabled = true, model = "m", rules = "no scary", rulesVersion = 3), at = mapOf(ConfigStamp.AI to T)),
            doc(ai = AiConfig(enabled = true, model = "m", rules = "no loud", rulesVersion = 3), at = mapOf(ConfigStamp.AI to T))
        ),
        Triple(
            "screening rules that differ, local newer",
            doc(ai = AiConfig(enabled = true, model = "m", rules = "no scary", rulesVersion = 3), at = mapOf(ConfigStamp.AI to T + 2)),
            doc(ai = AiConfig(enabled = true, model = "m", rules = "no loud", rulesVersion = 3), at = mapOf(ConfigStamp.AI to T + 1))
        ),
        Triple(
            "family limits with no stamp on either side",
            doc(limits = Limits(sessionMinutes = 45), at = mapOf(ConfigStamp.SETTINGS to T)),
            doc(limits = Limits(sessionMinutes = 30), at = mapOf(ConfigStamp.SETTINGS to T))
        )
    )

    // --- idempotence ----------------------------------------------------

    @Test
    fun mergingTheSamePeerAgainIsANoOp() {
        pairs().forEach { (name, a, b) ->
            val once = settle(a, b)
            val twice = settle(once, b)
            assertEquals("[$name] a second merge of the same peer moved the hashes", hashes(once), hashes(twice))
            assertNull("[$name] a second merge of the same peer must write nothing", ConfigMerge.merge(once, b).merged)
        }
    }

    @Test
    fun aFixedPushSettlesAfterOneRound() {
        // Six rounds, the incoming document held still: what a Push button
        // pressed six times looks like to a hub.
        pairs().forEach { (name, a, b) ->
            var cur = a
            val seen = ArrayList<String>()
            repeat(6) {
                cur = settle(cur, b)
                seen += hashes(cur)
            }
            assertEquals(
                "[$name] the local copy kept moving under an unchanged push: $seen",
                1, seen.distinct().size
            )
        }
    }

    // --- commutativity of the hashes ------------------------------------

    @Test
    fun bothDirectionsHashAlike() {
        pairs().forEach { (name, a, b) ->
            assertEquals("[$name] merge(a, b) and merge(b, a) disagree", hashes(settle(a, b)), hashes(settle(b, a)))
        }
    }

    // --- the two-party round trip ---------------------------------------

    /**
     * Hub H and phone P. The phone pushes; the hub merges (H1). The sweep
     * then fetches H1, the phone merges it in the way `mergeIncoming` does —
     * `commit(merged)`, no stamping — and pushes `rawJson()` back (P1). The
     * hub merges again (H2). From here nothing may move.
     */
    @Test
    fun aPhoneAndAHubSettleWithinTwoRounds() {
        pairs().forEach { (name, hub, phone) ->
            val h1 = settle(hub, phone)
            val p1 = asPushedByPhone(settle(phone, h1))
            val h2 = settle(h1, p1)
            val p2 = asPushedByPhone(settle(p1, h2))
            val h3 = settle(h2, p2)
            assertEquals("[$name] the hub moved on the phone's second push", hashes(h1), hashes(h2))
            assertEquals("[$name] the hub moved on the phone's third push", hashes(h2), hashes(h3))
            assertEquals("[$name] the phone and the hub never agreed", hashes(h3), hashes(p2))
        }
    }

    // --- the log this file was written for ------------------------------

    /**
     * The hub log, from a real phone: six pushes of one unchanged document,
     * the merged fingerprint alternating between two values.
     *
     * The shape that produces it: the hub lifted a block and holds the proof
     * — the add stamp beside the tombstone, exactly what `ConfigStamp.remove`
     * writes for a fail-closed unit. The phone (a restored backup) still holds
     * the block and never saw the unblock. Only the phone pushes; nobody pulls.
     */
    @Test
    fun aRestoredBlockPushedAtAnUnblockDoesNotFlicker() {
        val hub = doc(at = mapOf(ConfigStamp.blk("v1") to T + 1), gone = mapOf(ConfigStamp.blk("v1") to T + 5))
        val phone = doc(blocked = setOf("v1"), at = mapOf(ConfigStamp.blk("v1") to T + 1))

        var cur = hub
        val log = ArrayList<String>()
        repeat(6) {
            cur = settle(cur, phone)
            log += "merged a push → ${hashes(cur)} blocked=${ConfigJson.fromJson(cur).blockedVideoIds}"
        }
        assertEquals(
            "the hub's copy must settle after the first push; instead:\n" + log.joinToString("\n"),
            1, log.distinct().size
        )
    }

    @Test
    fun aRestoredPerKidBlockPushedAtAnUnblockDoesNotFlicker() {
        val hub = doc(
            profiles = listOf(kid),
            at = mapOf(ConfigStamp.kid("k1") to T, ConfigStamp.forKid("v1", "k1") to T + 1),
            gone = mapOf(ConfigStamp.forKid("v1", "k1") to T + 5)
        )
        val phone = doc(
            profiles = listOf(kid), blockedFor = mapOf("v1" to setOf("k1")),
            at = mapOf(ConfigStamp.kid("k1") to T, ConfigStamp.forKid("v1", "k1") to T + 1)
        )

        var cur = hub
        val log = ArrayList<String>()
        repeat(6) {
            cur = settle(cur, phone)
            log += "merged a push → ${hashes(cur)} blockedFor=${ConfigJson.fromJson(cur).blockedFor}"
        }
        assertEquals(
            "the hub's copy must settle after the first push; instead:\n" + log.joinToString("\n"),
            1, log.distinct().size
        )
    }

    /**
     * The same pair when the phone also pulls — the background sweep's shape.
     * Kept separate so the report can say whether the sweep would rescue the
     * pair, or whether it too keeps moving.
     */
    @Test
    fun aRestoredBlockAndAnUnblockConvergeWhenBothSidesMerge() {
        var hub = doc(at = mapOf(ConfigStamp.blk("v1") to T + 1), gone = mapOf(ConfigStamp.blk("v1") to T + 5))
        var phone = doc(blocked = setOf("v1"), at = mapOf(ConfigStamp.blk("v1") to T + 1))

        val log = ArrayList<String>()
        repeat(4) {
            hub = settle(hub, phone)                       // the phone pushed
            phone = asPushedByPhone(settle(phone, hub))    // the sweep pulled and re-serialized
            log += "hub ${hashes(hub)} phone ${hashes(phone)}"
        }
        assertEquals("both sides must hold the same document after a sweep:\n" + log.joinToString("\n"), hashes(hub), hashes(phone))
        assertEquals("and must stop moving:\n" + log.joinToString("\n"), log[2], log[3])
    }

    // --- the master slot ------------------------------------------------

    private val hubToken = ".hub0123456789abcdef0123456789ab"
    private val phoneToken = "0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a"

    /** Both orders, then each again: the shape every merge here has to have. */
    private fun assertSettles(name: String, a: String, b: String, holder: String?, stamp: Long) {
        val ab = settle(a, b)
        val ba = settle(b, a)
        assertEquals("$name: order", hashes(ab), hashes(ba))
        assertEquals("$name: settled", hashes(ab), hashes(settle(ab, b)))
        assertEquals("$name: settled the other way", hashes(ba), hashes(settle(ba, a)))
        val w = ConfigJson.fromJson(ab)
        assertEquals("$name: holder", holder, w.masterDeviceToken)
        assertEquals("$name: stamp", stamp, w.sync.at[ConfigStamp.MASTER])
    }

    @Test
    fun aHubReclaimsTheSlotFromAPhoneAndTheFleetConverges() {
        // The phone claimed at T; the hub, armed a day later, claimed after it.
        val phone = doc(master = phoneToken, at = mapOf(ConfigStamp.MASTER to T))
        val hub = doc(master = hubToken, at = mapOf(ConfigStamp.MASTER to T + 2))
        assertSettles("newer hub claim", phone, hub, hubToken, T + 2)
        // On an exact tie the hub still wins, in both orders (MasterToken.preferred).
        val phoneTie = doc(master = phoneToken, at = mapOf(ConfigStamp.MASTER to T))
        val hubTie = doc(master = hubToken, at = mapOf(ConfigStamp.MASTER to T))
        assertSettles("tie", phoneTie, hubTie, hubToken, T)
        // Two phones keep the old rule.
        val other = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"
        assertSettles("two phones", doc(master = other, at = mapOf(ConfigStamp.MASTER to T)), phoneTie, phoneToken, T)
    }

    @Test
    fun aHeartbeatMovesTheStampAndNothingElse() {
        val before = doc(master = hubToken, at = mapOf(ConfigStamp.MASTER to T))
        val beat = doc(master = hubToken, at = mapOf(ConfigStamp.MASTER to T + 6))
        assertSettles("heartbeat", before, beat, hubToken, T + 6)
        // A stale copy pushed back after the heartbeat cannot age the slot again.
        val merged = settle(before, beat)
        assertEquals(hashes(merged), hashes(settle(merged, before)))
        assertEquals(T + 6, ConfigJson.fromJson(settle(merged, before)).sync.at[ConfigStamp.MASTER])
    }
}
