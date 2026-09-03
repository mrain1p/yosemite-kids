package io.pickwick.app.data

/**
 * What the reconcile should do about one paired device.
 *
 * Pure, so the four-way choice is a unit test rather than something a family
 * discovers. The decision used to be three-way with a dead arm — "differs but
 * its copy is newer, leaving for Push/Pull" — which meant a co-parent's edit
 * sat on the TV until somebody noticed and pressed a button. That arm is now
 * the feature.
 */
sealed interface SyncAction {

    /** Both sides hold the same config and know the same history. */
    data object Nothing : SyncAction

    /**
     * Fetch their copy, merge it into ours, and push the result back if we
     * learned something or they are missing something we hold.
     */
    data object Merge : SyncAction

    /** A pre-merge peer, older than us: push the whole config, exactly as before. */
    data object PushWhole : SyncAction

    /**
     * A pre-merge peer whose copy claims to be newer. Left alone, because an
     * old build restamps `updatedAt` at serialization time, so "newer" from
     * one is not evidence of anything — the parent decides with Push or Pull,
     * where they can now see the diff first.
     */
    data object LeaveForParent : SyncAction
}

/**
 * [remoteSyncV] null means the peer predates the merge format. Such a peer is
 * a **push-only destination and never a merge source**: its document carries
 * no bookkeeping, and its `updatedAt` is the moment it serialized rather than
 * the moment anyone edited, so treating it as a source would let a phone out
 * of a drawer speak with authority about a config it has not seen in weeks.
 */
fun syncAction(
    localHash: String,
    localSyncHash: String,
    localAt: Long,
    remoteHash: String,
    remoteSyncHash: String?,
    remoteSyncV: Int?,
    remoteAt: Long
): SyncAction {
    val canMerge = remoteSyncV != null && remoteSyncHash != null
    if (canMerge) {
        // Both hashes, not just the config one. A peer holding a tombstone we
        // have never seen matches on content and not on history, and if that
        // read as "in sync" the deletion would never travel — the sweep never
        // fetches a body when it thinks the two sides agree.
        return if (remoteHash == localHash && remoteSyncHash == localSyncHash) {
            SyncAction.Nothing
        } else SyncAction.Merge
    }
    return when {
        remoteHash == localHash -> SyncAction.Nothing
        remoteAt < localAt -> SyncAction.PushWhole
        else -> SyncAction.LeaveForParent
    }
}
