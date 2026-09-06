package io.yosemitekids.app.data

/**
 * Whether this peer should claim the search-index slot, keep it, or leave
 * it alone. Pure: the clock is passed in (ConfigSync.sweep reads it on a
 * device, HubMaster on the hub), and the only state is the config itself.
 *
 * Liveness is the master stamp. The holder re-touches `sync.at["master"]`
 * every [HEARTBEAT_MS] without changing the token, so a slot whose stamp is
 * older than [VACANT_AFTER_MS] belongs to nobody: the holder has been off
 * for a day, or was a phone that was factory-reset with the slot in its
 * pocket. No new field, no per-peer last-seen store; the merge stays pure
 * and reads stamps only.
 *
 * The stamp was minted on the holder's clock and is aged on this one, so
 * a peer whose clock is a day out would see every slot as vacant. Kid
 * devices, the ones with the bad RTCs, never call this; parents' phones
 * and the NAS keep network time.
 */
object MasterElection {

    /** How often the holder re-touches its stamp. */
    const val HEARTBEAT_MS = 6 * 60 * 60 * 1000L

    /** A stamp older than this is nobody's. Four heartbeats missed, not one. */
    const val VACANT_AFTER_MS = 24 * 60 * 60 * 1000L

    enum class Decision { CLAIM, HEARTBEAT, NOTHING }

    /** When the holder last said it was alive, or null for a slot that has never been stamped. */
    fun stampedAt(config: Whitelist): Long? = config.sync.at[ConfigStamp.MASTER]

    /** True when nobody live holds the slot. A never-stamped slot is vacant: a legacy claim's holder heartbeats first and fixes that. */
    fun vacant(config: Whitelist, now: Long): Boolean {
        if (config.masterDeviceToken == null) return true
        val at = stampedAt(config) ?: return true
        return now - at > VACANT_AFTER_MS
    }

    /**
     * @param me this peer's token: a device token, or the hub's self token.
     * @param isHub whether [me] is a hub. Passed rather than derived from
     *   the prefix so a test can say what it means.
     * @param armed hub only: a device has asked this hub for the index
     *   within a day (`GET /index-status` with `X-Index-Pull: 1`). A hub
     *   nobody pulls from neither claims nor keeps the slot, so a fleet
     *   that stopped pulling — every device back on an older build — gets
     *   its phone back as master after the stamp ages out. Ignored for a
     *   phone.
     */
    fun decide(
        config: Whitelist,
        me: String,
        isHub: Boolean,
        now: Long,
        armed: Boolean = true
    ): Decision {
        val holder = config.masterDeviceToken
        if (holder == me) {
            if (isHub && !armed) return Decision.NOTHING
            val at = stampedAt(config)
            return if (at == null || now - at >= HEARTBEAT_MS) Decision.HEARTBEAT else Decision.NOTHING
        }
        val vacant = vacant(config, now)
        if (isHub) {
            if (!armed) return Decision.NOTHING
            // A vacant slot, or one a phone holds: the hub is always on and
            // the phone is not. Never one another live hub holds; two hubs
            // settle by the tie rule and the loser sees that here next tick.
            return if (vacant || !MasterToken.isHub(holder)) Decision.CLAIM else Decision.NOTHING
        }
        // A phone takes only what nobody holds — never a live hub's slot,
        // and never a live co-parent's, which is what made two claims race.
        return if (vacant) Decision.CLAIM else Decision.NOTHING
    }
}
