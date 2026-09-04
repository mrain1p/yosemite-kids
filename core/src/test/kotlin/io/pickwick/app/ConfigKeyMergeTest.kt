package io.pickwick.app

import io.pickwick.app.data.AiConfig
import io.pickwick.app.data.ConfigJson
import io.pickwick.app.data.ConfigMerge
import io.pickwick.app.data.ConfigStamp
import io.pickwick.app.data.SyncMeta
import io.pickwick.app.data.Whitelist
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * How the AI API key survives a merge.
 *
 * It was the one field in the document resolved by "whatever the last document
 * I read said" rather than by its stamp, and the local side was always handed
 * the disk copy — which never holds a key — so the incoming key won every
 * time. A parent who rotated the key while a TV was off got the old one back:
 * the TV woke, served its stale key, the phone adopted it, pushed it on, and
 * both ends settled on the revoked credential reporting "in sync".
 *
 * In :core because the hub runs this merge too, and because the key must
 * behave the same way on a peer that holds none.
 */
class ConfigKeyMergeTest {

    private val T = 1_780_000_000_000L

    private fun doc(key: String?, aiAt: Long, model: String = "m"): String {
        val json = ConfigJson.toJson(
            Whitelist(
                sources = emptyList(),
                blockedVideoIds = emptySet(),
                ai = AiConfig(enabled = true, model = model, apiKey = key.orEmpty()),
                sync = SyncMeta(docAt = aiAt, at = mapOf(ConfigStamp.AI to aiAt))
            )
        )
        // null means "this peer carries no apiKey member at all" — a hub, or
        // any device's own disk copy. Absent and empty are different claims.
        // Pinned for the same reason ConfigMergeTest pins it: toJson stamps
        // updatedAt from the clock and the merge compares that field, so an
        // unpinned fixture is a coin flip on a millisecond boundary.
        val pinned = JSONObject(json).put("updatedAt", aiAt).toString(2)
        return if (key == null) ConfigJson.stripSecrets(pinned) else pinned
    }

    private fun keyAfterMerge(local: String, incoming: String, held: String) =
        ConfigMerge.merge(local, incoming, localApiKey = held).apiKey

    @Test
    fun aStalePeerCannotUndoARotation() {
        // THE bug. The phone rotated at T+10; the TV slept through it and still
        // serves the old key stamped at T.
        val onThePhone = doc(key = null, aiAt = T + 10)   // disk copy: no key in it
        val fromTheTv = doc(key = "sk-old", aiAt = T)

        assertEquals("sk-new", keyAfterMerge(onThePhone, fromTheTv, held = "sk-new"))
    }

    @Test
    fun aGenuinelyNewerKeyFromAPeerStillWins() {
        // The other co-parent rotated it. Their edit is newer, so it must
        // arrive — otherwise the fix would simply freeze the key forever.
        val onThePhone = doc(key = null, aiAt = T)
        val fromTheirPhone = doc(key = "sk-theirs", aiAt = T + 10)

        assertEquals("sk-theirs", keyAfterMerge(onThePhone, fromTheirPhone, held = "sk-mine"))
    }

    @Test
    fun aPeerHoldingNoKeyNeverClearsOne() {
        // The commonest peer in the fleet: a hub, which strips the key before
        // writing and can never send one back. Even with a much newer stamp it
        // must not be read as "there is no key".
        val onThePhone = doc(key = null, aiAt = T)
        val fromTheHub = doc(key = null, aiAt = T + 1000)

        assertEquals("sk-mine", keyAfterMerge(onThePhone, fromTheHub, held = "sk-mine"))
    }

    @Test
    fun aFirstKeyArrivesWhenThisDeviceHasNone() {
        val onThePhone = doc(key = null, aiAt = T)
        val fromTheirPhone = doc(key = "sk-first", aiAt = T + 1)

        assertEquals("sk-first", keyAfterMerge(onThePhone, fromTheirPhone, held = ""))
    }

    @Test
    fun anUnreadableLocalFileDoesNotCostUsTheKey() {
        // Local unreadable: the peer's document is adopted wholesale. A key it
        // never carried is still not theirs to erase.
        assertEquals(
            "sk-mine",
            keyAfterMerge("{ not json", doc(key = null, aiAt = T), held = "sk-mine")
        )
    }

    @Test
    fun aTieResolvesTheSameWayFromBothSides() {
        // Equal stamps: neither side edited more recently, so there is nothing
        // to prefer — but both must still land on the same answer or they push
        // at each other forever.
        assertEquals(
            ConfigMerge.pickKey(null, "sk-aaa", "sk-bbb"),
            ConfigMerge.pickKey(null, "sk-bbb", "sk-aaa")
        )
    }

    @Test
    fun theResolvedKeyNeverReachesTheMergedDocument() {
        // The whole reason it travels out of band. A credential in the merged
        // JSON would land on disk, in a collision record, and in every backup.
        val result = ConfigMerge.merge(
            doc(key = null, aiAt = T),
            doc(key = "sk-theirs", aiAt = T + 10),
            localApiKey = "sk-mine"
        )
        assertFalse(result.merged.orEmpty().contains("sk-theirs"))
        assertFalse(result.merged.orEmpty().contains("sk-mine"))
    }

    @Test
    fun theKeyFollowsWhicheverSideWonTheAiUnit() {
        // Not a separate rule: the key rides the decision the ai unit already
        // made, so the model and the key it was entered beside cannot end up
        // coming from different devices.
        assertEquals("mine", ConfigMerge.pickKey(true, "mine", "theirs"))
        assertEquals("theirs", ConfigMerge.pickKey(false, "mine", "theirs"))
    }
}
