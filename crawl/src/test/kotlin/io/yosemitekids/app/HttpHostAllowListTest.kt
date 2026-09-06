package io.yosemitekids.app

import io.yosemitekids.app.data.Http
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * The hub's outbound allow-list, which guard 7 relies on: once armed, the
 * shared client refuses any host that is not YouTube's, before DNS and
 * before a socket. The hub arms it at startup; the app never does.
 */
class HttpHostAllowListTest {

    @After
    fun disarm() = Http.restrictTo(null)

    @Test
    fun anArmedClientRefusesAHostOffTheList() {
        Http.restrictTo(Http.HUB_HOSTS)
        try {
            Http.client.newCall(Request.Builder().url("http://example.invalid/").build()).execute().close()
            fail("a host off the allow-list must be refused")
        } catch (e: IOException) {
            assertTrue(e.message, e.message.orEmpty().contains("allow-list"))
        }
    }

    @Test
    fun theListNamesYouTubeAndItsCdnsAndNothingElse() {
        val allowed = Http.HUB_HOSTS
        assertTrue(allowed.contains("youtube.com"))
        assertTrue(allowed.contains("googlevideo.com"))
        assertTrue(allowed.none { it.contains("/") || it.startsWith("http") })
        assertTrue("every entry is a bare registrable domain", allowed.all { Regex("[a-z0-9.-]+[.][a-z]+").matches(it) })
    }

    @Test
    fun subdomainsOfAnAllowedHostAreAllowedAndLookalikesAreNot() {
        assertTrue(Http.hostAllowed("www.youtube.com", Http.HUB_HOSTS))
        assertTrue(Http.hostAllowed("rr3---sn-abc.googlevideo.com", Http.HUB_HOSTS))
        assertTrue(!Http.hostAllowed("youtube.com.evil.example", Http.HUB_HOSTS))
        assertTrue(!Http.hostAllowed("notyoutube.com", Http.HUB_HOSTS))
    }
}
