package io.yosemitekids.hub

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Which browsers may watch, and the short codes a parent hands out to let one
 * in.
 *
 * A child has no password and must never hold the admin secret — the whole
 * point of the second listener is that the credential a tablet carries can do
 * nothing but play video. `Profile.pin` is not a candidate either: four D-pad
 * characters, stored in plaintext in a config every device holds, whose stated
 * threat model is a six-year-old.
 *
 * So a browser is claimed the way a device is enrolled, and deliberately in
 * the same two halves ([HubTokens.startEnrolment] / [HubTokens.approve]):
 *
 * 1. **A parent mints**, inside their own session, and says which child it is
 *    for. The code is bound to that profile at that moment, so nothing the
 *    tablet sends afterwards can change whose rules it watches under.
 * 2. **The tablet redeems**, on the kid origin, and is handed a cookie.
 *
 * Neither half is enough on its own: the code is worthless without someone
 * standing at the tablet, and the tablet can mint nothing.
 *
 * The cookie is long-lived on purpose ([CLAIM_TTL_MS]). A child re-typing a
 * code every week is a feature nobody wants, and the way to cut a lost iPad
 * off is [revoke] — a control on the admin page — not an expiry short enough
 * to be a nuisance to a family that has lost nothing.
 *
 * On disk beside `config.json` and `devices.json`, because it is family setup:
 * a hub that forgot its claimed browsers on every restart would send every
 * child back to the parent for a fresh code after a `docker pull`.
 */
class HubBrowsers(dataDir: File) {

    private val file = File(dataDir, "browsers.json")
    private val lock = Any()
    private val rng = SecureRandom()

    /** A code a parent has minted, waiting for a tablet to type it. */
    data class Pending(val code: String, val kid: String, val createdAt: Long, val tries: Int)

    /**
     * A browser that redeemed one.
     *
     * [kid] is the profile it was bound to at mint time and is never taken
     * from a request: it is the whole reason this row exists rather than a
     * `kid=` parameter on the media URL, which a child could edit into a
     * sibling's budget.
     */
    data class Browser(
        val token: String,
        val kid: String,
        val claimedAt: Long,
        val lastSeenAt: Long = 0L
    ) {
        /** What the admin page names it by. Never the token: that is a bearer credential. */
        val ref: String get() = token.take(8)
    }

    companion object {
        /**
         * Six characters, not the eight a device gets.
         *
         * A device's code is read off a television across a room by an adult;
         * this one is typed by a child on a tablet, and every character is a
         * chance to give up. Six from [HubTokens.CODE_ALPHABET] is 887 million
         * codes, and the space only ever has to beat [MAX_TRIES] guesses
         * inside [CODE_TTL_MS] — a wrong code burns a try on every live one,
         * exactly as `HubTokens.approve` does, so guessing is not a thing you
         * get to do here either.
         */
        const val CODE_LENGTH = 6

        /** A code is for the minute you are standing at the tablet, not for later. */
        const val CODE_TTL_MS = 10 * 60 * 1000L

        /** Wrong guesses before every outstanding code is burned. */
        const val MAX_TRIES = 5

        /**
         * Codes outstanding at once. Lower than `HubTokens.MAX_PENDING`
         * because minting one costs an admin session — nothing unauthenticated
         * can grow this file, so the cap is a bound on a parent's own
         * mis-taps rather than on a stranger's flood.
         */
        const val MAX_PENDING = 5

        /**
         * Claimed browsers kept at once. A household has a handful of screens;
         * anything past this is a family who never revoked, and refusing is
         * honest where evicting the oldest would silently cut off the tablet
         * somebody is watching on.
         */
        const val MAX_BROWSERS = 20

        /**
         * How long a claim lasts without being used or renewed — six months.
         *
         * Long by design: this is the cookie a child's browser carries, and
         * the failure a short one produces is a parent being asked for a fresh
         * code every Saturday morning. A browser that is *actually* lost is
         * cut off with [revoke], which takes effect on the next request.
         */
        const val CLAIM_TTL_MS = 180L * 24 * 60 * 60 * 1000L

        /**
         * How often "last watched" is written down. Every media request would
         * otherwise rewrite this file for the length of every video.
         */
        const val SEEN_WRITE_INTERVAL_MS = 60 * 60 * 1000L
    }

    private fun read(): JSONObject = synchronized(lock) {
        runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }

    private fun write(root: JSONObject) = synchronized(lock) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString(2))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    /**
     * Mint a code for [kid], or null when [MAX_PENDING] are already
     * outstanding.
     *
     * The cap is checked and the row appended under one lock, like
     * [HubTokens.startEnrolment], so a cap tested from outside is not a cap
     * two worker threads walk past together.
     */
    fun mint(kid: String, now: Long): String? = synchronized(lock) {
        val code = (1..CODE_LENGTH)
            .map { HubTokens.CODE_ALPHABET[rng.nextInt(HubTokens.CODE_ALPHABET.length)] }
            .joinToString("")
        val root = read()
        val kept = livePending(root, now)
        // After the sweep, not before it: a queue full of expired codes is an
        // empty queue, and refusing on the strength of them would leave a
        // parent unable to add a tablet for ten minutes after a run of
        // abandoned attempts.
        if (kept.length() >= MAX_PENDING) return null
        kept.put(JSONObject().put("code", code).put("kid", kid).put("createdAt", now).put("tries", 0))
        root.put("pending", kept)
        write(root)
        code
    }

    fun pending(now: Long): List<Pending> {
        val arr = read().optJSONArray("pending") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Pending(it.optString("code"), it.optString("kid"), it.optLong("createdAt"), it.optInt("tries"))
            }
        }.filter { now - it.createdAt < CODE_TTL_MS }
    }

    /** Why a claim failed, so the tablet can say something true. */
    enum class Refusal { UNKNOWN_CODE, TOO_MANY_TRIES, TOO_MANY_BROWSERS }

    /** What a claim produced: the cookie value, and the child it plays as. */
    data class Claimed(val token: String, val kid: String)

    /**
     * Redeem a code. Single use: the row is consumed whether or not the caller
     * ever comes back with the cookie.
     *
     * A wrong code burns a try on **every** live code rather than on the one
     * it resembles, for the reason `HubTokens.approve` gives: per-code tries
     * hand an attacker five guesses per outstanding code.
     */
    fun claim(code: String, now: Long): Result<Claimed> = synchronized(lock) {
        val root = read()
        val arr = root.optJSONArray("pending") ?: JSONArray()
        val wanted = code.trim().uppercase()

        var found: JSONObject? = null
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            if (now - p.optLong("createdAt") >= CODE_TTL_MS) continue
            if (p.optString("code").equals(wanted, ignoreCase = true)) found = p else kept.put(p)
        }

        if (found == null) {
            val survivors = JSONArray()
            var burned = false
            for (i in 0 until kept.length()) {
                val p = kept.optJSONObject(i) ?: continue
                val tries = p.optInt("tries") + 1
                if (tries < MAX_TRIES) survivors.put(p.put("tries", tries)) else burned = true
            }
            root.put("pending", survivors)
            write(root)
            return Result.failure(
                ClaimRefused(if (burned) Refusal.TOO_MANY_TRIES else Refusal.UNKNOWN_CODE)
            )
        }

        val live = liveBrowsers(root, now)
        if (live.length() >= MAX_BROWSERS) {
            // The code is spent either way — it was typed, and a code that
            // survived a refusal would be a code an attacker gets to keep
            // trying against a full list until somebody revokes something.
            root.put("pending", kept)
            root.put("browsers", live)
            write(root)
            return Result.failure(ClaimRefused(Refusal.TOO_MANY_BROWSERS))
        }

        val token = (1..32).map { "0123456789abcdef"[rng.nextInt(16)] }.joinToString("")
        val kid = found.optString("kid")
        live.put(
            JSONObject().put("token", token).put("kid", kid)
                .put("claimedAt", now).put("lastSeenAt", 0L)
        )
        root.put("browsers", live)
        root.put("pending", kept)   // the redeemed one is consumed
        write(root)
        return Result.success(Claimed(token, kid))
    }

    /**
     * The browser a cookie names, or null — which every kid route reads as
     * "show the code box".
     *
     * Compared with [MessageDigest.isEqual] rather than `==`: this is a bearer
     * credential arriving off the LAN, and the length-independent compare
     * costs nothing here. Expiry is checked on the way out and **not** written
     * back: a read path that rewrote the file would turn every chunk of every
     * video into a disk write.
     */
    fun resolve(token: String?, now: Long): Browser? {
        if (token.isNullOrBlank()) return null
        val given = token.toByteArray(Charsets.UTF_8)
        return browsers().firstOrNull {
            MessageDigest.isEqual(given, it.token.toByteArray(Charsets.UTF_8)) &&
                now - it.claimedAt < CLAIM_TTL_MS
        }
    }

    fun browsers(): List<Browser> {
        val arr = read().optJSONArray("browsers") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Browser(
                    it.optString("token"), it.optString("kid"),
                    it.optLong("claimedAt"), it.optLong("lastSeenAt")
                )
            }
        }
    }

    /**
     * "This browser was here." Written at most once an hour per browser, like
     * `HubTokens.notePull`, for the same reason: a tablet watching an
     * afternoon of video would otherwise rewrite this file every two
     * megabytes.
     */
    fun noteSeen(token: String?, now: Long) {
        if (token.isNullOrBlank()) return
        synchronized(lock) {
            val root = read()
            val arr = root.optJSONArray("browsers") ?: return
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                if (b.optString("token") != token) continue
                if (now - b.optLong("lastSeenAt") < SEEN_WRITE_INTERVAL_MS) return
                b.put("lastSeenAt", now)
                write(root)
                return
            }
        }
    }

    /** Cut a browser off, by the short reference the admin page holds. */
    fun revoke(ref: String): Boolean = synchronized(lock) {
        val root = read()
        val arr = root.optJSONArray("browsers") ?: return false
        val kept = JSONArray()
        var removed = false
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            if (b.optString("token").take(8) == ref) removed = true else kept.put(b)
        }
        if (!removed) return false
        root.put("browsers", kept)
        write(root)
        true
    }

    /** Every browser claimed for [kid] goes, when a parent deletes the child. */
    fun revokeFor(kid: String): Int = synchronized(lock) {
        val root = read()
        val arr = root.optJSONArray("browsers") ?: return 0
        val kept = JSONArray()
        var removed = 0
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            if (b.optString("kid") == kid) removed++ else kept.put(b)
        }
        if (removed == 0) return 0
        root.put("browsers", kept)
        write(root)
        removed
    }

    private fun livePending(root: JSONObject, now: Long): JSONArray {
        val arr = root.optJSONArray("pending") ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            if (now - p.optLong("createdAt") < CODE_TTL_MS) kept.put(p)
        }
        return kept
    }

    private fun liveBrowsers(root: JSONObject, now: Long): JSONArray {
        val arr = root.optJSONArray("browsers") ?: JSONArray()
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            if (now - b.optLong("claimedAt") < CLAIM_TTL_MS) kept.put(b)
        }
        return kept
    }
}

class ClaimRefused(val reason: HubBrowsers.Refusal) : Exception(reason.name)
