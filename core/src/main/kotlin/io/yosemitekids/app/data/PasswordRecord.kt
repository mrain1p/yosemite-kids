package io.yosemitekids.app.data

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A stored password: never the password, only what verifies one.
 *
 * One shape for every password this product keeps — the hub's admin
 * password (`HubPassword`) and a kid's web password ([KidPassword]) — so
 * there is one derivation to get right and one record a phone, the hub and
 * a test all read the same way. `kdf` and `iter` travel inside the record
 * and are never assumed on verify, which is what lets the cost be raised
 * later without invalidating anything already set; an unknown `kdf` fails
 * closed.
 *
 * A data class rather than a bare `JSONObject` because it rides inside
 * [Profile], and the stamper decides whether a kid changed by comparing
 * profiles for equality: a field that never compares equal is a unit that
 * is touched on every save.
 */
data class PasswordRecord(
    val v: Int,
    val kdf: String,
    val iter: Int,
    val salt: String,
    val key: String,
    /** Wall-clock ms when set; display and fingerprint only. */
    val setAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", v).put("kdf", kdf).put("iter", iter)
        .put("salt", salt).put("key", key).put("setAt", setAt)

    companion object {
        /** Null for anything that is not a complete record, so a damaged one verifies nothing. */
        fun fromJson(o: JSONObject?): PasswordRecord? {
            o ?: return null
            val kdf = o.optString("kdf").ifEmpty { return null }
            val salt = o.optString("salt").ifEmpty { return null }
            val key = o.optString("key").ifEmpty { return null }
            val iter = o.optInt("iter", 0).takeIf { it > 0 } ?: return null
            return PasswordRecord(o.optInt("v", 0), kdf, iter, salt, key, o.optLong("setAt", 0L))
        }
    }
}

/**
 * The one key derivation, shared by every password the product stores.
 *
 * PBKDF2-HMAC-SHA256 because it is the only credible password KDF in the
 * JDK and on Android alone: Argon2, scrypt and bcrypt all mean a dependency
 * in an image a NAS pulls over a home connection, to verify a credential a
 * handful of times a day. Iterations below OWASP's figure on purpose — this
 * runs on a Synology Celeron and on a phone, the cost of a guess is bounded
 * by a throttle in front of every door, and the threat is a sibling or a
 * reused password rather than a stolen dump.
 */
object Pbkdf2 {
    const val KDF = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 210_000
    const val SALT_BYTES = 16
    const val KEY_BYTES = 32
    const val VERSION = 1

    /**
     * NFC-normalized and deliberately NOT trimmed. An accented character can
     * arrive as one code point from one keyboard and two from another, and a
     * password that works on the phone and not on the NAS is one nobody can
     * explain; a trailing space is a legitimate character, and dropping it
     * silently makes the same failure the other way round.
     */
    fun normalize(password: String): String = Normalizer.normalize(password, Normalizer.Form.NFC)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd-length hex" }
        return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun derive(password: String, salt: ByteArray, iterations: Int, kdf: String): ByteArray {
        val spec = PBEKeySpec(normalize(password).toCharArray(), salt, iterations, KEY_BYTES * 8)
        return try {
            SecretKeyFactory.getInstance(kdf).generateSecret(spec).encoded
        } finally {
            // The spec holds a copy of the characters; drop it rather than
            // leave the password in the heap until a GC happens to reach it.
            spec.clearPassword()
        }
    }

    /** The platform's constant-time compare, not a hand-rolled one; empty never matches. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        a.isNotEmpty() && MessageDigest.isEqual(a, b)

    /** A fresh record for [password] at the current cost. Length is the caller's rule. */
    fun record(password: String, now: Long, rng: SecureRandom = SecureRandom()): PasswordRecord {
        val salt = ByteArray(SALT_BYTES).also { rng.nextBytes(it) }
        return PasswordRecord(
            VERSION, KDF, ITERATIONS, hex(salt), hex(derive(password, salt, ITERATIONS, KDF)), now
        )
    }

    /**
     * Whether [password] matches [record]. False for a null or unreadable
     * record, so "none set" and "wrong" are one answer from outside.
     */
    fun verify(record: PasswordRecord?, password: String): Boolean {
        val r = record ?: return false
        if (r.kdf != KDF) return false
        if (r.iter <= 0) return false
        val salt = runCatching { unhex(r.salt) }.getOrNull() ?: return false
        val stored = runCatching { unhex(r.key) }.getOrNull() ?: return false
        if (salt.isEmpty() || stored.isEmpty()) return false
        val derived = runCatching { derive(password, salt, r.iter, r.kdf) }.getOrNull() ?: return false
        return constantTimeEquals(derived, stored)
    }

    fun needsUpgrade(record: PasswordRecord): Boolean =
        record.v < VERSION || record.iter < ITERATIONS
}

/**
 * A kid's own password for watching in a browser.
 *
 * Set by a parent — on the phone or on the hub's console, it is one field of
 * the kid's profile and syncs like the rest — and typed by the child on a
 * tablet's "Who's watching?" screen. It is distinct from the parent's PIN
 * and from the hub password on purpose: it can do nothing but let a browser
 * watch as that one child, under that child's rules.
 *
 * Four characters, not eight. The threat is a sibling, the door it opens
 * is a child's own shelves, and the hub throttles guesses per kid; a
 * password a five-year-old cannot type is a password a parent will set to
 * something they can, so the floor is honest rather than aspirational.
 */
object KidPassword {
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 64

    class TooShort : IllegalArgumentException("a kid's password must be at least $MIN_LENGTH characters")

    fun record(password: String, now: Long, rng: SecureRandom = SecureRandom()): PasswordRecord {
        if (Pbkdf2.normalize(password).length < MIN_LENGTH) throw TooShort()
        return Pbkdf2.record(password.take(MAX_LENGTH), now, rng)
    }

    fun verify(record: PasswordRecord?, password: String): Boolean =
        Pbkdf2.verify(record, password.take(MAX_LENGTH))
}
