package io.yosemitekids.app

import io.yosemitekids.app.data.KidPassword
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.ui.pickerGate
import org.junit.Assert.assertEquals
import org.junit.Test

/** The PIN locks the phone; the browser password may stand in for it, and only for it. */
class PickerGateTest {

    private val password = KidPassword.record("otter", now = 1_788_771_600_000L)

    @Test
    fun theFourCases() {
        val open = pickerGate(Profile(id = "k1", name = "Leo"))
        assertEquals("no PIN, no password: a tap opens it", false, open.askPin)
        assertEquals(false, open.passwordOffered)

        val pinOnly = pickerGate(Profile(id = "k1", name = "Leo", pin = "UDLR"))
        assertEquals(true, pinOnly.askPin)
        assertEquals("nothing to offer: the button would lead to a screen that cannot unlock", false, pinOnly.passwordOffered)

        val passwordOnly = pickerGate(Profile(id = "k1", name = "Leo", webPassword = password))
        assertEquals("a browser password alone does not lock the phone - the PIN is the lock", false, passwordOnly.askPin)
        assertEquals(false, passwordOnly.passwordOffered)

        val both = pickerGate(Profile(id = "k1", name = "Leo", pin = "UDLR", webPassword = password))
        assertEquals(true, both.askPin)
        assertEquals("beside the PIN, the typed way in", true, both.passwordOffered)
    }
}
