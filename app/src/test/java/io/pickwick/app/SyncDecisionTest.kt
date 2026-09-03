package io.pickwick.app

import io.pickwick.app.data.SyncAction
import io.pickwick.app.data.SyncMeta
import io.pickwick.app.data.syncAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the sweep decides about one paired device.
 *
 * The arm that matters most is the one that used to be dead. "Differs but its
 * copy is newer — leaving for Push/Pull" meant a co-parent's edit sat on the
 * TV until somebody noticed and pressed a button, which is exactly the
 * complaint the whole sync design started from.
 */
class SyncDecisionTest {

    private val T = 1_780_000_000_000L

    private fun decide(
        localHash: String = "aaaa",
        localSyncHash: String = "1111",
        localAt: Long = T,
        remoteHash: String = "aaaa",
        remoteSyncHash: String? = "1111",
        remoteSyncV: Int? = SyncMeta.VERSION,
        remoteAt: Long = T
    ) = syncAction(
        localHash, localSyncHash, localAt,
        remoteHash, remoteSyncHash, remoteSyncV, remoteAt
    )

    @Test
    fun equalHashesAndSyncHashesDoNothing() {
        assertEquals(SyncAction.Nothing, decide())
    }

    @Test
    fun differentContentMerges() {
        assertEquals(SyncAction.Merge, decide(remoteHash = "bbbb"))
    }

    @Test
    fun sameContentButADifferentHistoryStillMerges() {
        // The peer holds a tombstone we have never seen. Content matches, so
        // the config hash alone would say "in sync" — and the deletion would
        // never travel, because the sweep never fetches a body in that case.
        assertEquals(SyncAction.Merge, decide(remoteSyncHash = "9999"))
    }

    @Test
    fun aPeerWithoutSyncVIsNeverAMergeSource() {
        // An old build restamps updatedAt at serialization time, so its
        // document always claims to be brand new. Treating it as a source
        // would let a phone out of a drawer speak with authority.
        listOf(0L, T - 1, T, T + 999_999).forEach { theirClock ->
            val action = decide(
                remoteHash = "bbbb", remoteSyncV = null, remoteSyncHash = null, remoteAt = theirClock
            )
            assertEquals(
                "a pre-merge peer must never be merged from (clock $theirClock)",
                true, action is SyncAction.PushWhole || action is SyncAction.LeaveForParent
            )
        }
    }

    @Test
    fun aPeerWithoutSyncVStillGetsAWholeConfigPushWhenOlder() {
        assertEquals(
            SyncAction.PushWhole,
            decide(remoteHash = "bbbb", remoteSyncV = null, remoteSyncHash = null, remoteAt = T - 1)
        )
    }

    @Test
    fun aPeerWithoutSyncVWhoseCopyIsNewerIsLeftToTheParent() {
        assertEquals(
            SyncAction.LeaveForParent,
            decide(remoteHash = "bbbb", remoteSyncV = null, remoteSyncHash = null, remoteAt = T + 1)
        )
    }

    @Test
    fun aLegacyPeerHoldingTheSameConfigNeedsNothing() {
        // Today's behaviour, unchanged: a household with only pre-merge peers
        // must behave exactly as it did before any of this.
        assertEquals(
            SyncAction.Nothing,
            decide(remoteSyncV = null, remoteSyncHash = null, remoteAt = T + 5)
        )
    }

    @Test
    fun aHalfAdvertisedPeerIsTreatedAsLegacy() {
        // syncV without syncHash is not a shape we emit, but a proxy or a
        // partial upgrade could produce it, and guessing would be worse than
        // falling back to the path that cannot lose anything.
        val action = decide(remoteHash = "bbbb", remoteSyncHash = null, remoteAt = T - 1)
        assertEquals(SyncAction.PushWhole, action)
    }

    @Test
    fun contentEqualButBlobsDifferMergesOnceAndThenSettles() {
        // The merge is idempotent, so the second sweep sees equal hashes and
        // stops. Without that this pair would push at each other forever.
        assertEquals(SyncAction.Merge, decide(remoteSyncHash = "2222"))
        assertEquals(SyncAction.Nothing, decide(remoteSyncHash = "1111"))
    }
}
