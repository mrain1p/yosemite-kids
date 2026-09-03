package io.pickwick.hub

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    data class Device(val token: String, val name: String, val enrolledAt: Long)

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
                Device(it.optString("token"), it.optString("name"), it.optLong("enrolledAt"))
            }
        }
    }

    fun isEnrolled(token: String?): Boolean =
        token != null && token.isNotBlank() && devices().any { it.token == token }

    fun nameOf(token: String?): String? = devices().firstOrNull { it.token == token }?.name

    /** Mint a code for a device that wants in. [now] is passed so tests need no clock. */
    fun startEnrolment(name: String, now: Long): String {
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
        return code
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
    fun approve(code: String, now: Long): Result<String> = synchronized(lock) {
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
        )
        root.put("devices", devices)
        root.put("pending", kept)   // the approved one is consumed
        write(root)
        return Result.success(token)
    }

    /**
     * The admin secret, which is what lets a human approve a device code.
     *
     * Taken from `PICKWICK_ADMIN_TOKEN` when set. When it is not, one is
     * generated on first run and written here — and printed to the container
     * log, which on a NAS is the one place a parent can always reach without
     * already being authenticated. Generating beats defaulting: a hub with a
     * known default token is a hub anyone on the network administers.
     */
    fun adminToken(fromEnv: String?): String = synchronized(lock) {
        fromEnv?.takeIf { it.isNotBlank() }?.let { return it }
        val root = read()
        root.optString("admin").takeIf { it.isNotBlank() }?.let { return it }
        val minted = (1..24).map { "0123456789abcdef"[rng.nextInt(16)] }.joinToString("")
        root.put("admin", minted)
        write(root)
        minted
    }

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
