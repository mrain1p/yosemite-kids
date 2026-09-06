package io.yosemitekids.hub

import org.json.JSONObject
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The hub's admin password: deriving it, storing it, checking it.
 *
 * Pure — no file, no clock of its own, no state. [HubTokens] owns where the
 * record lives; this owns what it means.
 *
 * **Why hash at all, when the recovery token sits in plaintext in the same
 * file?** Because the two protect different things. Anyone who can read
 * `devices.json` already holds every device token and the recovery token, and
 * can read `config.json` beside it: the hub is over, and no amount of key
 * stretching changes that. What the stretching protects is *the parent's
 * password everywhere else*. Families reuse one, that file sits on a NAS
 * volume people back up to cloud drives, and `HUB.md` documents a permissions
 * failure that leaves it world-readable. That is the threat these iterations
 * are aimed at, and it is written down here so a later session does not
 * "simplify" this back to a bare SHA-256.
 */
object HubPassword {

    /**
     * The only credible password KDF in the JDK alone. Argon2, scrypt and
     * bcrypt all mean a new dependency in an image a NAS pulls over a home
     * connection, to verify a credential a handful of times a day.
     */
    const val KDF = "PBKDF2WithHmacSHA256"

    /**
     * Below OWASP's 600 000 on purpose. This runs single-threaded on a
     * Synology Celeron or an ARM box with four worker threads, the cost of a
     * guess is already bounded by the sign-in lockout, and the threat here is
     * password reuse rather than a stolen ten-million-row dump. Measure the
     * first verify on the real NAS and put the number in docs/HUB.md.
     */
    const val ITERATIONS = 210_000

    const val SALT_BYTES = 16
    const val KEY_BYTES = 32
    const val VERSION = 1

    /**
     * Eight characters. Enforced here and not only in the browser, because
     * the browser is one client of three (the page, the phone, and curl) and
     * the store is the only place all three pass through.
     */
    const val MIN_LENGTH = 8

    class TooShort : IllegalArgumentException(
        "a hub password must be at least $MIN_LENGTH characters"
    )

    /**
     * Normalized to NFC and deliberately NOT trimmed.
     *
     * Normalizing in the hub lets both clients stay dumb: an accented
     * character typed on a phone keyboard and on a desktop can arrive as one
     * code point or as two, and a parent whose password stopped working after
     * switching device would have no way to tell why. Trimming is refused
     * because a password may legitimately end in a space, and silently
     * dropping it makes a password that works in one client and not another.
     */
    internal fun normalize(password: String): String =
        Normalizer.normalize(password, Normalizer.Form.NFC)

    internal fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    internal fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd-length hex" }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    internal fun derive(password: String, salt: ByteArray, iterations: Int, kdf: String): ByteArray {
        val spec = PBEKeySpec(normalize(password).toCharArray(), salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance(kdf).generateSecret(spec).encoded
        } finally {
            // The spec holds a copy of the characters; drop it rather than
            // leave the password sitting in the heap until a GC happens to
            // reach it. A heap dump of this container is a thing a NAS owner
            // can produce by accident.
            spec.clearPassword()
        }
    }

    /**
     * The JDK's own constant-time comparison rather than a hand-rolled one.
     * A first attempt here indexed the second array modulo its length to stay
     * length-independent, which walks off the end of an empty one — the exact
     * shape of bug that makes hand-written crypto helpers a bad trade when
     * the platform ships the primitive.
     */
    internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        a.isNotEmpty() && java.security.MessageDigest.isEqual(a, b)

    /** A fresh record for [password]. Throws [TooShort] rather than storing a weak one. */
    fun record(password: String, now: Long, rng: SecureRandom = SecureRandom()): JSONObject {
        if (normalize(password).length < MIN_LENGTH) throw TooShort()
        val salt = ByteArray(SALT_BYTES).also { rng.nextBytes(it) }
        return JSONObject()
            .put("v", VERSION)
            .put("kdf", KDF)
            .put("iter", ITERATIONS)
            .put("salt", hex(salt))
            .put("key", hex(derive(password, salt, ITERATIONS, KDF)))
            .put("setAt", now)
    }

    /**
     * Whether [password] matches [record]. False for a null or unreadable
     * record, so "no password set" and "wrong password" are one answer to a
     * caller and cannot be told apart from outside.
     *
     * The stored `kdf` and `iter` are read from the record and never assumed.
     * That is what lets the cost be raised later without invalidating a
     * password anyone has already set — and an unknown `kdf` fails CLOSED
     * rather than quietly verifying with whatever this build happens to use.
     */
    fun verify(record: JSONObject?, password: String): Boolean {
        val r = record ?: return false
        val kdf = r.optString("kdf")
        if (kdf != KDF) return false
        val iter = r.optInt("iter", 0)
        if (iter <= 0) return false
        val salt = runCatching { unhex(r.optString("salt")) }.getOrNull() ?: return false
        val stored = runCatching { unhex(r.optString("key")) }.getOrNull() ?: return false
        if (salt.isEmpty() || stored.isEmpty()) return false
        val derived = runCatching { derive(password, salt, iter, kdf) }.getOrNull() ?: return false
        return constantTimeEquals(derived, stored)
    }

    /**
     * True when a verified record was written by an older build or a lower
     * cost. The caller re-derives at the current parameters and rewrites —
     * AFTER the sign-in has already been decided, and never letting a failed
     * rewrite fail the sign-in.
     */
    fun needsUpgrade(record: JSONObject): Boolean =
        record.optInt("v", 0) < VERSION || record.optInt("iter", 0) < ITERATIONS
}
