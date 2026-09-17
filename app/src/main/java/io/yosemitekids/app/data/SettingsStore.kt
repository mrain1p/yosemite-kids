package io.yosemitekids.app.data

import android.content.Context
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.json.JSONObject

/**
 * Parent-gated app settings: the PIN (salted, stretched, never plaintext).
 *
 * The derivation is `:core`'s [Pbkdf2], which is also what the hub's admin
 * password and a kid's web password use. It was not always: this file carried
 * its own PBKDF2 at 120,000 iterations in a record format of its own, three
 * lines below a KDoc in `HubPassword` asserting that "there is one KDF in the
 * product rather than a hub copy and a phone copy that drift apart in cost".
 * There were two, and they had already drifted — the phone's parent PIN, which
 * guards every setting in the house, was the weaker of them.
 *
 * Two older formats still verify, and a PIN that checks out against either is
 * re-stored through the shared path on the spot, so a family upgrades silently
 * on their next visit to settings rather than being locked out of their own
 * settings screen by a version bump.
 */
class SettingsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun hasPin(): Boolean = prefs.contains("pin")

    fun setPin(pin: String) {
        prefs.edit().putString("pin", stored(pin, System.currentTimeMillis())).apply()
    }

    /**
     * Costs ~200 ms of CPU by design — call it off the main thread.
     *
     * A PIN that verified against one of the older formats is re-stored at the
     * shared cost here, which is the only moment it can be: nothing can
     * re-derive a hash without the input that made it.
     */
    fun checkPin(pin: String): Boolean {
        val ok = verifyStored(prefs.getString("pin", null), pin)
        if (ok && needsUpgrade(prefs.getString("pin", null))) setPin(pin)
        return ok
    }

    companion object {
        /** The prefix of this file's own old format. Read, never written. */
        private const val LEGACY_VERSION = "v1"

        /** What a PIN is written as today: the shared record, as its JSON. */
        internal fun stored(pin: String, now: Long): String =
            Pbkdf2.record(pin, now).toJson().toString()

        /**
         * Whether [pin] matches [stored], in any format this app has ever
         * written. Pure, so the upgrade path can be tested without a device —
         * and it is the path worth testing, because its failure mode is a
         * family locked out of their own settings by an update.
         */
        internal fun verifyStored(stored: String?, pin: String): Boolean {
            val text = stored ?: return false
            // The shared record, which is what every new PIN is written as.
            if (text.startsWith("{")) {
                val record = runCatching { PasswordRecord.fromJson(JSONObject(text)) }.getOrNull()
                return Pbkdf2.verify(record, pin)
            }
            val parts = text.split(':')
            return when {
                // v1:<salt>:<iterations>:<hash> — this file's own PBKDF2, 120k.
                parts.size == 4 && parts[0] == LEGACY_VERSION -> {
                    val iterations = parts[2].toIntOrNull() ?: return false
                    val salt = runCatching { unhex(parts[1]) }.getOrNull() ?: return false
                    constantTimeEquals(legacyPbkdf2(salt, pin, iterations), parts[3])
                }
                // <salt>:<sha256>, a single unstretched round, older still.
                parts.size == 2 -> {
                    val salt = runCatching { unhex(parts[0]) }.getOrNull() ?: return false
                    constantTimeEquals(legacySha(salt, pin), parts[1])
                }
                else -> false
            }
        }

        /** True for anything not already written at the shared cost. */
        internal fun needsUpgrade(stored: String?): Boolean =
            stored != null && !stored.startsWith("{")

        private fun legacyPbkdf2(salt: ByteArray, pin: String, iterations: Int): String =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, 256))
                .encoded
                .toHex()

        private fun legacySha(salt: ByteArray, pin: String): String =
            MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray()).toHex()

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        private fun unhex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
