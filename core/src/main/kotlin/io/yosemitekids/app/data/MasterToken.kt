package io.yosemitekids.app.data

/**
 * Who holds `config.masterDeviceToken`, and who wins when two documents
 * name different holders.
 *
 * A hub's self token starts with [HUB_PREFIX]: HubTokens mints ".hub" plus
 * 28 lowercase hex, 32 characters like a device token so nothing that takes
 * the first eight or assumes the length breaks. It is an identity and never
 * a credential — no route accepts it — which is what lets the pure merge
 * prefer a hub on a tie without looking anything up.
 */
object MasterToken {

    const val HUB_PREFIX = ".hub"

    fun isHub(token: String?): Boolean = token != null && token.startsWith(HUB_PREFIX)

    /**
     * The tie rule for the master unit when neither side's stamp is newer.
     * The hub if exactly one side is one — it is always on and the phone is
     * not — and otherwise the lexicographically smaller token, unchanged for
     * two phones. Symmetric in its arguments, so the merge stays commutative:
     * "keep the local one" would leave two co-parents who both claimed
     * running the rate-limit-expensive crawl forever.
     */
    fun preferred(mine: String?, theirs: String?): String? {
        if (mine == null) return theirs
        if (theirs == null) return mine
        val hubMine = isHub(mine)
        val hubTheirs = isHub(theirs)
        if (hubMine != hubTheirs) return if (hubMine) mine else theirs
        return minOf(mine, theirs)
    }
}
