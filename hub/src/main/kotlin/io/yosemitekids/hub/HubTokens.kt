package io.yosemitekids.hub

import io.yosemitekids.app.data.MasterToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Who may talk to this hub, and the short codes that let a new device join.
 *
 * The enrolment code is the only credential between a stranger and a family's
 * config, so it is deliberately unforgiving: single use, short expiry, and a
 * lockout after a handful of wrong guesses. That matters on a LAN and matters
 * far more the day this is reachable from outside, which is the plan — so it
 * is built this way from the first commit rather than hardened later.
 */
class HubTokens(dataDir: File) {

    private val file = File(dataDir, "devices.json")
    private val lock = Any()
    private val rng = SecureRandom()

    /**
     * An enrolled device.
     *
     * [host] and [port] are learned rather than enrolled: the hub records
     * them from each authenticated call ([noteSeen]) so it can nudge the
     * device when its own copy changes. Null until the device has called at
     * least once from a build that announces itself, so an older device is
     * simply never nudged rather than nudged at a guess.
     */
    data class Device(
        val token: String,
        val name: String,
        val enrolledAt: Long,
        val host: String? = null,
        val port: Int = 0,
        val lastSeenAt: Long = 0L,
        /** When it last asked this hub for the search index. See [armed]. */
        val pulledAt: Long = 0L,
        /**
         * The device's **own** pairing token, as it announced it
         * (`X-Device-Id`), or null until it has called from a build that says.
         *
         * Emphatically not [token]. That one was minted here at enrolment and
         * no device has ever heard of it, while every device resolves
         * `config.deviceProfiles` by the token in this field. Writing an
         * assignment under the enrolment token — which is what the hub did —
         * files it where nothing will ever look.
         */
        val deviceId: String? = null,
        /** Two different identities have announced themselves under this enrolment. See [noteSeen]. */
        val idConflict: Boolean = false,
        /**
         * Whether this enrolment is a parent's phone or a child's screen.
         *
         * Written by the **approver** — whoever presented the admin secret to
         * `/approve` — and never claimed by the enrolling device, which is the
         * same argument the codebase already makes for `PairedDevice.isHub`:
         * `/enrol` is unauthenticated by necessity, so anything it could say
         * about itself is worthless.
         *
         * It decides one thing: whether `GET /config` puts the API key back.
         * A row written before this existed is a [Kind.DEVICE], because
         * failing closed here costs a parent one re-join and failing open
         * hands a credential to a television.
         */
        val kind: Kind = Kind.DEVICE
    ) {
        /** Where to reach it, or null when it has never said. */
        val address: String? get() = if (host != null && port in 1..65535) "$host:$port" else null
    }

    /**
     * What kind of thing an enrolment is, and therefore what it may be given.
     *
     * A parent's phone administers the family and already holds the API key —
     * it is where the key is typed. A kid device is handed its key by a
     * parent's phone and has no reason to be handed one by a box on the
     * network as well. Two names, one decision: see [Device.kind].
     */
    enum class Kind { PARENT, DEVICE }

    /** A code shown on a device, waiting for a human to approve it here. */
    data class Pending(val code: String, val name: String, val createdAt: Long, val tries: Int)

    companion object {
        /** Long enough that guessing is hopeless, short enough to read off a TV. */
        const val CODE_LENGTH = 8

        /** A code is for the minute you are standing there, not for later. */
        const val CODE_TTL_MS = 10 * 60 * 1000L

        /** Wrong guesses before a code is burned. Guessing is not a thing you get to do. */
        const val MAX_TRIES = 5

        /**
         * A device's pull counts for this long. The same day the master
         * stamp takes to go vacant (MasterElection.VACANT_AFTER_MS): a hub
         * that nobody has pulled from for a day is a hub whose crawl nobody
         * would see.
         */
        const val ARM_WINDOW_MS = 24 * 60 * 60 * 1000L

        /** How often a device's pull is written down. The question asked is "within a day", not "when". */
        const val PULL_WRITE_INTERVAL_MS = 60 * 60 * 1000L

        /**
         * No vowels and no look-alikes: someone is reading this off a TV across
         * a room and typing it on a phone. Removing O/0 and I/1/L costs four
         * characters of alphabet and saves every mistyped code.
         */
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    }

    private fun read(): JSONObject = synchronized(lock) {
        runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }

    private fun write(root: JSONObject) = synchronized(lock) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString(2))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun devices(): List<Device> {
        val arr = read().optJSONArray("devices") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Device(
                    it.optString("token"), it.optString("name"), it.optLong("enrolledAt"),
                    it.optString("host").ifBlank { null },
                    it.optInt("port"), it.optLong("lastSeenAt"),
                    it.optLong("pulledAt"),
                    it.optString("deviceId").ifBlank { null },
                    it.optBoolean("idConflict"),
                    // Fail closed. An unknown word from a newer build, or no
                    // word at all from an older one, is a device — never a
                    // parent, because the only thing this field opens is the
                    // API key.
                    runCatching { Kind.valueOf(it.optString("kind")) }.getOrDefault(Kind.DEVICE)
                )
            }
        }
    }

    /**
     * Record what an enrolled device just told the hub about itself: where it
     * can be called back, and which identity it is.
     *
     * The address is observed, not claimed — it is the socket's own peer
     * address. The port cannot be: an inbound connection's source port is
     * ephemeral and has nothing to do with where the device listens, so the
     * device states that and the hub takes its word. Believing it costs
     * nothing, because the only thing the hub ever sends there is a nudge
     * carrying no data — the worst a lie achieves is that the liar receives
     * "something changed" and the real device does not.
     *
     * [deviceId] is **first-writer-wins**, and that is a decision rather than
     * a convenience. A device's pairing token is minted once per install and
     * never changes; a reinstall wipes it, and with it the enrolment this row
     * holds, so the device has to enrol again and gets a new row. One
     * enrolment therefore maps to exactly one identity for its whole life,
     * and a second, different one is either a restored backup or a lie.
     * Overwriting would silently re-point an assignment a parent already made
     * ("this device is for Emma") at a different device — the quiet failure
     * this whole fix exists to end — so the first is kept and the disagreement
     * is recorded for the page to show.
     *
     * Each fact is recorded independently: a device whose own LAN server has
     * not bound sends no port, and must still be able to say who it is.
     *
     * Written only when something actually moved. Every sync would otherwise
     * rewrite devices.json on a fixed schedule for the life of the hub.
     */
    fun noteSeen(token: String?, host: String?, port: Int, deviceId: String?, now: Long) {
        if (token.isNullOrBlank()) return
        synchronized(lock) {
            val root = read()
            val arr = root.optJSONArray("devices") ?: return
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                if (d.optString("token") != token) continue
                var moved = false
                if (!host.isNullOrBlank() && port in 1..65535 &&
                    (d.optString("host") != host || d.optInt("port") != port)
                ) {
                    d.put("host", host).put("port", port).put("lastSeenAt", now)
                    moved = true
                }
                if (!deviceId.isNullOrBlank()) {
                    val known = d.optString("deviceId")
                    if (known.isBlank()) {
                        d.put("deviceId", deviceId)
                        moved = true
                    } else if (known != deviceId && !d.optBoolean("idConflict")) {
                        d.put("idConflict", true)
                        moved = true
                    }
                }
                if (moved) write(root)
                return
            }
        }
    }
    /**
     * This hub's own identity: ".hub" + 28 lowercase hex, 32 characters like
     * a device token so nothing that takes the first eight or assumes the
     * length breaks. Minted once and kept under `self`. An identity, never a
     * credential — no route accepts it — which is what lets it travel in
     * config.masterDeviceToken and lets the merge tell a hub from a phone by
     * its prefix (MasterToken).
     */
    fun selfToken(): String = synchronized(lock) {
        val root = read()
        root.optString("self").takeIf { MasterToken.isHub(it) && it.length == 32 }?.let { return it }
        val minted = MasterToken.HUB_PREFIX +
            (1..28).map { "0123456789abcdef"[rng.nextInt(16)] }.joinToString("")
        root.put("self", minted)
        write(root)
        minted
    }

    /**
     * A device asked for the index (GET /index-status with X-Index-Pull: 1).
     * Only an enrolled token counts. Written at most once an hour per device:
     * a fleet pulling every fifteen minutes would otherwise rewrite
     * devices.json on a schedule for the life of the hub.
     */
    fun notePull(token: String?, now: Long) {
        if (token.isNullOrBlank()) return
        synchronized(lock) {
            val root = read()
            val arr = root.optJSONArray("devices") ?: return
            for (i in 0 until arr.length()) {
                val d = arr.optJSONObject(i) ?: continue
                if (d.optString("token") != token) continue
                if (now - d.optLong("pulledAt") < PULL_WRITE_INTERVAL_MS) return
                d.put("pulledAt", now)
                write(root)
                return
            }
        }
    }

    /**
     * Whether any enrolled device has pulled the index within [ARM_WINDOW_MS].
     * The hub claims the master slot only while armed (MasterElection): a hub
     * nobody's devices can pull from must never take the crawl away from the
     * phone still doing the work, and one they stopped pulling from lets its
     * slot age out.
     */
    fun armed(now: Long): Boolean =
        devices().any { it.pulledAt > 0L && now - it.pulledAt <= ARM_WINDOW_MS }

    fun isEnrolled(token: String?): Boolean =
        token != null && token.isNotBlank() && devices().any { it.token == token }

    fun nameOf(token: String?): String? = devices().firstOrNull { it.token == token }?.name

    /**
     * What kind of enrolment a token is, or null when it is not one at all.
     *
     * The one caller that matters is `GET /config`: a parent is served the API
     * key and a kid device is not. An unknown token answers null and is
     * refused before it gets this far.
     */
    fun kindOf(token: String?): Kind? = devices().firstOrNull { it.token == token }?.kind

    /** Mint a code for a device that wants in. [now] is passed so tests need no clock. */
    fun startEnrolment(name: String, now: Long): String = synchronized(lock) {
        // Inside the lock, like every other mutator here. read() and write()
        // each take it, but the read-modify-write between them was not
        // atomic, so an enrolment landing beside a password write could drop
        // one of them. Nothing had raced it before; a password is the first
        // thing written to this file from a route a parent uses while a
        // device is enrolling.
        val code = (1..CODE_LENGTH)
            .map { ALPHABET[rng.nextInt(ALPHABET.length)] }
            .joinToString("")
        val root = read()
        val pending = root.optJSONArray("pending") ?: JSONArray()
        // Drop anything expired while we are here, so the file cannot grow
        // without bound from abandoned attempts.
        val kept = JSONArray()
        for (i in 0 until pending.length()) {
            val p = pending.optJSONObject(i) ?: continue
            if (now - p.optLong("createdAt") < CODE_TTL_MS) kept.put(p)
        }
        kept.put(
            JSONObject().put("code", code).put("name", name)
                .put("createdAt", now).put("tries", 0)
        )
        root.put("pending", kept)
        write(root)
        code
    }

    fun pending(now: Long): List<Pending> {
        val arr = read().optJSONArray("pending") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                Pending(it.optString("code"), it.optString("name"), it.optLong("createdAt"), it.optInt("tries"))
            }
        }.filter { now - it.createdAt < CODE_TTL_MS }
    }

    /** Why an approval failed, so the screen can say something true. */
    enum class Refusal { UNKNOWN_CODE, EXPIRED, TOO_MANY_TRIES }

    /**
     * Approve a code typed in by a human, returning the new device token.
     *
     * A wrong code counts against *every* live code, not just the one it
     * resembles — otherwise the try limit is per-code and an attacker gets
     * five guesses per outstanding enrolment.
     */
    fun approve(code: String, now: Long, kind: Kind = Kind.DEVICE): Result<String> = synchronized(lock) {
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
            // Burn a try on everything outstanding, then drop what is spent.
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
                EnrolmentRefused(if (burned) Refusal.TOO_MANY_TRIES else Refusal.UNKNOWN_CODE)
            )
        }

        val token = (1..32).map { "0123456789abcdef"[rng.nextInt(16)] }.joinToString("")
        val devices = root.optJSONArray("devices") ?: JSONArray()
        devices.put(
            JSONObject().put("token", token)
                .put("name", found.optString("name"))
                .put("enrolledAt", now)
                .put("kind", kind.name)
        )
        root.put("devices", devices)
        root.put("pending", kept)   // the approved one is consumed
        write(root)
        return Result.success(token)
    }

    /**
     * The admin secret, which is what lets a human approve a device code.
     *
     * Taken from `YOSEMITE_KIDS_ADMIN_TOKEN` when set. When it is not, one is
     * generated on first run and written here — and printed to the container
     * log, which on a NAS is the one place a parent can always reach without
     * already being authenticated. Generating beats defaulting: a hub with a
     * known default token is a hub anyone on the network administers.
     */
    fun adminToken(fromEnv: String?): String = synchronized(lock) {
        fromEnv?.takeIf { it.isNotBlank() }?.let { return it }
        val root = read()
        root.optString("admin").takeIf { it.isNotBlank() }?.let { return it }
        val minted = mintAdminToken()
        root.put("admin", minted)
        write(root)
        minted
    }

    // --- the admin password ------------------------------------------------
    //
    // The token above becomes the RECOVERY credential and the password becomes
    // the everyday one. Both are checked here, in one place, so a route cannot
    // accidentally accept one and not the other.

    /** What a presented secret turned out to be. */
    enum class Secret { PASSWORD, RECOVERY, NO }

    fun hasPassword(): Boolean = read().optJSONObject("password") != null

    /**
     * Check a presented admin secret.
     *
     * Order matters and is the whole reason this is one function. The
     * recovery token is compared first, because it is a cheap constant-time
     * byte compare; only on a miss is the password derived, which costs one
     * KDF. So a wrong guess costs at most one derivation, and a caller that
     * is locked out passes `allowPassword = false` and costs none at all.
     *
     * @param allowPassword false while the sign-in lockout is in force. The
     *   recovery token stays exempt: 96 bits gain nothing from a rate limit,
     *   and it must remain the way back in while the password path is locked,
     *   or an attacker who only wants the family locked out simply fails ten
     *   times every window.
     * @param envToken YOSEMITE_KIDS_ADMIN_TOKEN, which overrides the stored
     *   one exactly as it does today, so a parent with the compose file is
     *   never locked out.
     */
    fun verifyAdminSecret(given: String?, envToken: String?, allowPassword: Boolean = true): Secret {
        if (given.isNullOrEmpty()) return Secret.NO
        val recovery = envToken?.takeIf { it.isNotBlank() } ?: read().optString("admin")
        if (recovery.isNotBlank() &&
            MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), recovery.toByteArray(Charsets.UTF_8))
        ) return Secret.RECOVERY
        if (!allowPassword) return Secret.NO
        val record = read().optJSONObject("password") ?: return Secret.NO
        if (!HubPassword.verify(record, given)) return Secret.NO
        // Re-stretch a record an older build wrote, so raising the cost does
        // not have to invalidate anyone's password. After the answer is
        // decided, and a failure here must never fail the sign-in.
        if (HubPassword.needsUpgrade(record)) {
            runCatching {
                synchronized(lock) {
                    val root = read()
                    if (root.optJSONObject("password") != null) {
                        root.put("password", HubPassword.record(given, System.currentTimeMillis()))
                        write(root)
                    }
                }
            }
        }
        return Secret.PASSWORD
    }

    /**
     * Set or change the password. [current] must be the existing password or
     * the recovery token — always, even inside a live session, because that
     * session may be a browser on a kitchen counter and the failure this
     * prevents is a parent locked out of their own hub.
     *
     * Returns the new recovery token on the FIRST set only, to be shown once.
     * The old one is sitting in a container log that `docker logs` replays,
     * so leaving it live would make the password decoration. Later changes
     * rotate nothing: silently invalidating a token a parent wrote down is
     * itself a lockout.
     */
    fun setPassword(current: String?, next: String, now: Long, envToken: String?): Result<String?> {
        if (verifyAdminSecret(current, envToken) == Secret.NO) {
            return Result.failure(IllegalArgumentException("wrong"))
        }
        val record = runCatching { HubPassword.record(next, now) }
            .getOrElse { return Result.failure(it) }
        return synchronized(lock) {
            val root = read()
            val first = root.optJSONObject("password") == null
            root.put("password", record)
            val rotated = if (first) mintAdminToken().also { root.put("admin", it) } else null
            write(root)
            Result.success(rotated)
        }
    }

    /** A fresh recovery token, on demand. Same gate and throttle as a password change. */
    fun rotateRecoveryToken(): String = synchronized(lock) {
        val root = read()
        val minted = mintAdminToken()
        root.put("admin", minted)
        write(root)
        minted
    }

    private fun mintAdminToken(): String =
        (1..24).map { "0123456789abcdef"[rng.nextInt(16)] }.joinToString("")

    fun revoke(token: String) = synchronized(lock) {
        val root = read()
        val arr = root.optJSONArray("devices") ?: return
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val d = arr.optJSONObject(i) ?: continue
            if (d.optString("token") != token) kept.put(d)
        }
        root.put("devices", kept)
        write(root)
    }
}

class EnrolmentRefused(val reason: HubTokens.Refusal) : Exception(reason.name)
