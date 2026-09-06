package io.yosemitekids.hub

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

/**
 * The admin password's arithmetic.
 *
 * Written for what it must REFUSE, in HubTokensTest's voice: this is the
 * credential between a stranger on the network and a family's whole
 * configuration, and the interesting cases are all failures.
 */
class HubPasswordTest {

    private val T = 1_780_000_000_000L

    @Test
    fun `a password verifies against its own record and nothing else`() {
        val r = HubPassword.record("correct horse battery", T)
        assertTrue(HubPassword.verify(r, "correct horse battery"))
        assertFalse(HubPassword.verify(r, "correct horse batter"))
        assertFalse(HubPassword.verify(r, "Correct horse battery"))
        assertFalse(HubPassword.verify(r, ""))
        // No record is the same answer as a wrong one, so an outsider cannot
        // tell "not set up yet" from "you guessed wrong".
        assertFalse(HubPassword.verify(null, "correct horse battery"))
    }

    @Test
    fun `two hubs with the same password store different bytes`() {
        val a = HubPassword.record("the same password", T)
        val b = HubPassword.record("the same password", T)
        assertNotEquals("the salt must be per install", a.getString("salt"), b.getString("salt"))
        assertNotEquals(a.getString("key"), b.getString("key"))
        assertTrue(HubPassword.verify(a, "the same password"))
        assertTrue(HubPassword.verify(b, "the same password"))
    }

    @Test
    fun `the password itself appears nowhere in the record`() {
        // The strongest test here: it catches "just store it", and it catches
        // a debugging line left behind in the record builder.
        val secret = "unmistakable-plaintext-42"
        val text = HubPassword.record(secret, T).toString()
        assertFalse(text, text.contains(secret))
        assertFalse(text, text.contains("unmistakable"))
    }

    @Test
    fun `a short password is refused by the store, not only by the browser`() {
        // The page is one client of three; the phone and curl are the others.
        val e = runCatching { HubPassword.record("short", T) }.exceptionOrNull()
        assertTrue(e.toString(), e is HubPassword.TooShort)
        assertTrue(runCatching { HubPassword.record("12345678", T) }.isSuccess)
    }

    @Test
    fun `the same password typed two ways both verify`() {
        // é as one code point, and as e followed by a combining accent. A
        // parent whose password stopped working after changing keyboard
        // would have no way to find out why.
        val nfc = Normalizer.normalize("café secret", Normalizer.Form.NFC)
        val nfd = Normalizer.normalize("café secret", Normalizer.Form.NFD)
        assertNotEquals("the fixture must actually differ", nfc, nfd)
        val r = HubPassword.record(nfd, T)
        assertTrue(HubPassword.verify(r, nfc))
        assertTrue(HubPassword.verify(r, nfd))
    }

    @Test
    fun `a trailing space is part of the password`() {
        // Trimming would make a password that works in one client and not
        // another, which is unfalsifiable from the parent's side.
        val r = HubPassword.record("with a space ", T)
        assertTrue(HubPassword.verify(r, "with a space "))
        assertFalse(HubPassword.verify(r, "with a space"))
    }

    @Test
    fun `the stored iteration count is honoured rather than assumed`() {
        // Hand-written at a lower cost, the way a record from an older build
        // would look. It must still verify, or raising the cost would lock
        // out every family that had already set a password.
        val salt = ByteArray(HubPassword.SALT_BYTES) { it.toByte() }
        val cheap = JSONObject()
            .put("v", 1)
            .put("kdf", HubPassword.KDF)
            .put("iter", 1_000)
            .put("salt", HubPassword.hex(salt))
            .put("key", HubPassword.hex(HubPassword.derive("older build", salt, 1_000, HubPassword.KDF)))
            .put("setAt", T)
        assertTrue(HubPassword.verify(cheap, "older build"))
        assertTrue("and it must be flagged for rewriting", HubPassword.needsUpgrade(cheap))
        assertFalse(HubPassword.needsUpgrade(HubPassword.record("a fresh one", T)))
    }

    @Test
    fun `a record naming an unknown algorithm fails closed`() {
        // Not "verify it with whatever this build uses": that would silently
        // downgrade a record written by a future build with a better KDF.
        val r = HubPassword.record("a good password", T).put("kdf", "PBKDF2WithHmacSHA1")
        assertFalse(HubPassword.verify(r, "a good password"))
    }

    @Test
    fun `a corrupt record is refused rather than throwing`() {
        // devices.json is edited by hand during recovery. A half-edited
        // record must lock the page, not crash the container on every call.
        val good = HubPassword.record("a good password", T)
        val broken = listOf(
            JSONObject(good.toString()).put("salt", "not hex"),
            JSONObject(good.toString()).put("key", "abc"),
            JSONObject(good.toString()).put("iter", 0),
            JSONObject(good.toString()).put("salt", ""),
            JSONObject()
        )
        broken.forEach { assertFalse(it.toString(), HubPassword.verify(it, "a good password")) }
    }

    @Test
    fun `hex survives a round trip, including high bytes`() {
        val bytes = byteArrayOf(0, 1, 15, 16, -1, -128, 127)
        assertEquals("00010f10ff807f", HubPassword.hex(bytes))
        assertTrue(bytes.contentEquals(HubPassword.unhex(HubPassword.hex(bytes))))
    }
}
