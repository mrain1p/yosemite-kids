package io.yosemitekids.app

import io.yosemitekids.app.data.Pbkdf2
import io.yosemitekids.app.data.SettingsStore
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parent PIN survives the move onto the shared derivation.
 *
 * This file carried its own PBKDF2 at 120,000 iterations in a record format of
 * its own, beside `:core`'s at 210,000 that the hub's admin password and a
 * kid's web password both use — so the thing guarding every setting in the
 * house was the weaker of two implementations that a KDoc claimed was one.
 *
 * The failure mode of getting the move wrong is not subtle and not
 * recoverable: a family updates, their PIN stops verifying, and the settings
 * screen they would use to set a new one is behind it. So every format this
 * app has ever written is held here, including the shapes a damaged record
 * can take.
 */
class ParentPinUpgradeTest {

    private val pin = "4821"

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** The v1 record this file used to write: v1:<salt>:<iterations>:<hash>. */
    private fun v1(pin: String, salt: ByteArray, iterations: Int = 120_000): String {
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, 256))
            .encoded
        return "v1:${hex(salt)}:$iterations:${hex(key)}"
    }

    /** Older still: <salt>:<sha256>, one unstretched round. */
    private fun sha(pin: String, salt: ByteArray): String =
        hex(salt) + ":" + hex(MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray()))

    @Test
    fun `a PIN written today verifies, and a wrong one does not`() {
        val stored = SettingsStore.stored(pin, 1_780_000_000_000L)
        assertTrue(SettingsStore.verifyStored(stored, pin))
        assertFalse(SettingsStore.verifyStored(stored, "4822"))
        assertFalse("no PIN set is the same answer as wrong", SettingsStore.verifyStored(null, pin))
    }

    @Test
    fun `it is written at the shared cost, not this file's old one`() {
        val record = org.json.JSONObject(SettingsStore.stored(pin, 1L))
        assertEquals(Pbkdf2.ITERATIONS, record.getInt("iter"))
        assertEquals(Pbkdf2.KDF, record.getString("kdf"))
    }

    @Test
    fun `a PIN stored by an older build still opens the settings screen`() {
        val salt = ByteArray(16) { it.toByte() }
        val old = v1(pin, salt)
        assertTrue("the 120k format", SettingsStore.verifyStored(old, pin))
        assertFalse(SettingsStore.verifyStored(old, "0000"))
        assertTrue("and it is marked for re-storing", SettingsStore.needsUpgrade(old))

        val ancient = sha(pin, salt)
        assertTrue("the unstretched format", SettingsStore.verifyStored(ancient, pin))
        assertFalse(SettingsStore.verifyStored(ancient, "0000"))
        assertTrue(SettingsStore.needsUpgrade(ancient))
    }

    @Test
    fun `what is already shared is not re-stored on every check`() {
        assertFalse(SettingsStore.needsUpgrade(SettingsStore.stored(pin, 1L)))
    }

    @Test
    fun `a damaged record verifies nothing rather than everything`() {
        for (junk in listOf("", ":", "v1:zz:120000:aa", "{", """{"v":1}""", "one:two:three")) {
            assertFalse("`$junk` must not open the settings screen", SettingsStore.verifyStored(junk, pin))
        }
    }
}
