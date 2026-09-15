package io.yosemitekids.hub

import io.yosemitekids.app.data.PasswordRecord
import io.yosemitekids.app.data.Pbkdf2
import org.json.JSONObject
import java.security.SecureRandom

/**
 * The hub's admin password: deriving it, storing it, checking it.
 *
 * Pure — no file, no clock of its own, no state. [HubTokens] owns where the
 * record lives; this owns what it means. The derivation itself is `:core`'s
 * [Pbkdf2], shared with a kid's browser password, so there is one KDF in the
 * product rather than a hub copy and a phone copy that drift apart in cost.
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

    const val KDF = Pbkdf2.KDF
    const val ITERATIONS = Pbkdf2.ITERATIONS
    const val VERSION = Pbkdf2.VERSION

    /**
     * Eight characters. Enforced here and not only in the browser, because
     * the browser is one client of three (the page, the phone, and curl) and
     * the store is the only place all three pass through.
     */
    const val MIN_LENGTH = 8

    class TooShort : IllegalArgumentException(
        "a hub password must be at least $MIN_LENGTH characters"
    )

    const val SALT_BYTES = Pbkdf2.SALT_BYTES
    const val KEY_BYTES = Pbkdf2.KEY_BYTES

    // The primitives, by their old names, for the tests that build a record
    // by hand to prove an older or damaged one is handled. All of them are
    // :core's.
    internal fun normalize(password: String): String = Pbkdf2.normalize(password)
    internal fun hex(bytes: ByteArray): String = Pbkdf2.hex(bytes)
    internal fun unhex(s: String): ByteArray = Pbkdf2.unhex(s)
    internal fun derive(password: String, salt: ByteArray, iterations: Int, kdf: String): ByteArray =
        Pbkdf2.derive(password, salt, iterations, kdf)

    /** A fresh record for [password]. Throws [TooShort] rather than storing a weak one. */
    fun record(password: String, now: Long, rng: SecureRandom = SecureRandom()): JSONObject {
        if (normalize(password).length < MIN_LENGTH) throw TooShort()
        return Pbkdf2.record(password, now, rng).toJson()
    }

    /**
     * Whether [password] matches [record]. False for a null or unreadable
     * record, so "no password set" and "wrong password" are one answer to a
     * caller and cannot be told apart from outside. An unknown `kdf` fails
     * closed — see [Pbkdf2.verify].
     */
    fun verify(record: JSONObject?, password: String): Boolean =
        Pbkdf2.verify(PasswordRecord.fromJson(record), password)

    /**
     * True when a verified record was written by an older build or a lower
     * cost. The caller re-derives at the current parameters and rewrites —
     * AFTER the sign-in has already been decided, and never letting a failed
     * rewrite fail the sign-in.
     */
    fun needsUpgrade(record: JSONObject): Boolean =
        PasswordRecord.fromJson(record)?.let { Pbkdf2.needsUpgrade(it) } ?: true
}
