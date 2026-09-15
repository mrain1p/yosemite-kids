package io.yosemitekids.app

import io.yosemitekids.app.data.Diag
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The diagnostic ring's pure half: the cap, the order, and what a report carries. */
class DiagRingTest {

    @Test
    fun `the ring keeps the newest MAX entries and cuts a long message`() {
        var raw = "[]"
        repeat(Diag.MAX + 5) { i -> raw = Diag.Ring.append(raw, "warn", "line $i", 1000L + i) }
        val entries = Diag.Ring.parse(raw)
        assertEquals(Diag.MAX, entries.size)
        assertEquals("line 5", entries.first().message)
        assertEquals("line ${Diag.MAX + 4}", entries.last().message)

        val cut = Diag.Ring.parse(Diag.Ring.append("[]", "error", "x".repeat(5000), 1L)).single()
        assertEquals(Diag.MAX_MESSAGE, cut.message.length)
    }

    @Test
    fun `a report carries only what the hub has not been told, capped, with the sender's version and kind`() {
        var raw = "[]"
        repeat(10) { i -> raw = Diag.Ring.append(raw, "warn", "line $i", 1000L + i) }
        assertNull("nothing new is no report", Diag.Ring.report(raw, sentUpTo = 1009L, kind = "tv", version = "1.8.0"))

        val (body, newest) = Diag.Ring.report(raw, sentUpTo = 1004L, kind = "tv", version = "1.8.0")!!
        assertEquals(1009L, newest)
        val json = JSONObject(body)
        assertEquals("1.8.0", json.getString("version"))
        assertEquals("tv", json.getString("kind"))
        assertEquals(5, json.getJSONArray("entries").length())
        assertEquals("line 5", json.getJSONArray("entries").getJSONObject(0).getString("msg"))

        var big = "[]"
        repeat(Diag.MAX) { i -> big = Diag.Ring.append(big, "warn", "line $i", 1000L + i) }
        val capped = JSONObject(Diag.Ring.report(big, 0L, null, "1.8.0")!!.first)
        assertEquals(Diag.MAX_PER_REPORT, capped.getJSONArray("entries").length())
        assertTrue("the newest survive the cap", capped.getJSONArray("entries").getJSONObject(Diag.MAX_PER_REPORT - 1).getString("msg") == "line ${Diag.MAX - 1}")
        assertTrue(!capped.has("kind"))
    }

    @Test
    fun `an unreadable ring parses as empty rather than throwing`() {
        assertEquals(emptyList<Diag.Entry>(), Diag.Ring.parse("not json"))
        assertEquals(1, Diag.Ring.parse(Diag.Ring.append("not json", "warn", "fresh", 1L)).size)
    }
}
