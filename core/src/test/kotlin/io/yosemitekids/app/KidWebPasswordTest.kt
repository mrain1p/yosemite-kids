package io.yosemitekids.app

import io.yosemitekids.app.data.ConfigJson
import io.yosemitekids.app.data.ConfigMerge
import io.yosemitekids.app.data.ConfigStamp
import io.yosemitekids.app.data.KidPassword
import io.yosemitekids.app.data.PasswordRecord
import io.yosemitekids.app.data.Pbkdf2
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.data.Whitelist
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * A kid's browser password: the record, and its life as a config field —
 * the four canonical tests from the sync skill, plus the one that matters
 * for a credential riding inside an object: a co-parent's stale copy must
 * not take it away.
 */
class KidWebPasswordTest {

    private val T = 1_780_000_000_000L
    private val rng = SecureRandom(byteArrayOf(7))

    private fun family(vararg kids: Profile) = Whitelist(emptyList(), emptySet(), profiles = kids.toList())

    // --- the record ------------------------------------------------------

    @Test
    fun `a record verifies its own password and nothing else`() {
        val r = KidPassword.record("otter", T, rng)
        assertTrue(KidPassword.verify(r, "otter"))
        assertFalse(KidPassword.verify(r, "Otter"))
        assertFalse(KidPassword.verify(r, "otter "))
        assertFalse(KidPassword.verify(null, "otter"))
        assertFalse("an empty guess never matches", KidPassword.verify(r, ""))
    }

    @Test
    fun `four characters is the floor and the record is never the password`() {
        assertTrue(runCatching { KidPassword.record("abc", T, rng) }.exceptionOrNull() is KidPassword.TooShort)
        val r = KidPassword.record("abcd", T, rng)
        assertFalse(r.toJson().toString().contains("abcd"))
        assertEquals(Pbkdf2.KDF, r.kdf)
        assertEquals(Pbkdf2.ITERATIONS, r.iter)
    }

    @Test
    fun `a record with an unknown kdf or a damaged field fails closed`() {
        val r = KidPassword.record("otter", T, rng)
        assertFalse(Pbkdf2.verify(r.copy(kdf = "MD5"), "otter"))
        assertFalse(Pbkdf2.verify(r.copy(key = "zz"), "otter"))
        assertNull(PasswordRecord.fromJson(JSONObject().put("kdf", Pbkdf2.KDF)))
        assertNull(PasswordRecord.fromJson(null))
    }

    // --- the config field: the four canonical tests --------------------------

    @Test
    fun `a web password survives a JSON round-trip and clears back to null`() {
        val r = KidPassword.record("otter", T, rng)
        val kid = Profile(id = "k1", name = "Leo", webPassword = r)
        val parsed = ConfigJson.fromJson(ConfigJson.toJson(family(kid)))
        assertEquals(r, parsed.profiles.single().webPassword)
        assertTrue(KidPassword.verify(parsed.profiles.single().webPassword, "otter"))

        val cleared = ConfigJson.fromJson(ConfigJson.toJson(family(kid.copy(webPassword = null))))
        assertNull(cleared.profiles.single().webPassword)
    }

    @Test
    fun `no web password is omitted from JSON, byte for byte as before the field`() {
        val json = ConfigJson.toJson(family(Profile(id = "k1", name = "Leo")))
        assertFalse(json.contains("\"web\""))
    }

    @Test
    fun `a kid without a web password keeps the pre-feature fingerprint`() {
        val plain = family(Profile(id = "k1", name = "Leo"))
        assertEquals(
            ConfigJson.fingerprint(plain),
            ConfigJson.fingerprint(family(Profile(id = "k1", name = "Leo", webPassword = null)))
        )
        assertFalse(ConfigJson.fingerprint(plain).contains("W:"))
    }

    @Test
    fun `setting a web password moves the fingerprint so the offline reconcile carries it`() {
        val plain = family(Profile(id = "k1", name = "Leo"))
        val set = family(Profile(id = "k1", name = "Leo", webPassword = KidPassword.record("otter", T, rng)))
        val reset = family(Profile(id = "k1", name = "Leo", webPassword = KidPassword.record("badger", T + 1, rng)))
        assertNotEquals(ConfigJson.fingerprint(plain), ConfigJson.fingerprint(set))
        assertNotEquals(ConfigJson.fingerprint(set), ConfigJson.fingerprint(reset))
    }

    // --- its own unit ----------------------------------------------------------

    @Test
    fun `the web password is its own unit, separate from the kid's name`() {
        val kid = Profile(id = "k1", name = "Leo")
        val base = family(kid)
        val next = family(kid.copy(webPassword = KidPassword.record("otter", T, rng)))
        val sync = ConfigStamp.stamped(base, base, next, T, "mum", "hub").config.sync
        assertEquals(setOf(ConfigStamp.kidWeb("k1")), sync.at.keys)
    }

    @Test
    fun `a stale phone fixing the kid's name does not take the password the hub set`() {
        val kid = Profile(id = "k1", name = "Leo")
        val original = ConfigStamp.stamped(family(), family(), family(kid), T, "mum", "phone").config

        // The hub sets a password at T+10.
        val withPassword = kid.copy(webPassword = KidPassword.record("otter", T + 10, rng))
        val hub = ConfigStamp.stamped(original, original, family(withPassword), T + 10, "hub", "hub").config

        // A phone that never saw it corrects the name at T+20.
        val phone = ConfigStamp.stamped(original, original, family(kid.copy(name = "Leon")), T + 20, "dad", "phone").config

        val merged = ConfigMerge.merge(ConfigJson.toJson(phone), ConfigJson.toJson(hub)).merged!!
        val out = ConfigJson.fromJson(merged).profiles.single()
        assertEquals("the newer name wins", "Leon", out.name)
        assertTrue("the password the phone never saw survives", KidPassword.verify(out.webPassword, "otter"))

        val other = ConfigMerge.merge(ConfigJson.toJson(hub), ConfigJson.toJson(phone)).merged!!
        assertEquals(
            "and the same from the other side",
            ConfigJson.fingerprint(ConfigJson.fromJson(merged)),
            ConfigJson.fingerprint(ConfigJson.fromJson(other))
        )
    }
}
