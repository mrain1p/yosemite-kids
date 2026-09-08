package io.yosemitekids.hub

import io.yosemitekids.app.data.ChannelIndex
import io.yosemitekids.app.ui.KID_DARK
import io.yosemitekids.app.ui.Argb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The generated stylesheet, from the browser's side of the wire.
 *
 * The Gradle task that writes it and the route that serves it are two
 * separate things, and the one failure mode that matters is them not meeting:
 * "/" answers anything without a route of its own with the admin page's HTML
 * and a 200, so a missing `/kid-tokens.css` would arrive as a stylesheet full
 * of `<!doctype html>` and every colour on the kid's page would silently be
 * the browser default. That is guard 20's lesson, applied to a file that is
 * not in the repository at all.
 */
class HubTokensCssTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: HubServer
    private var port = 0

    @Before
    fun setUp() {
        val dir = tmp.newFolder("data")
        server = HubServer(
            HubStore(dir), HubTokens(dir), 0, "test-admin-token",
            index = ChannelIndex(File(dir, "search-index"))
        ) { 1_780_000_000_000L }
        port = server.start()
    }

    @After
    fun tearDown() = server.stop()

    @Test
    fun `the hub serves the generated tokens as CSS`() {
        val c = URL("http://127.0.0.1:$port/kid-tokens.css").openConnection() as HttpURLConnection
        val code = c.responseCode
        val type = c.getHeaderField("Content-Type")
        val body = c.inputStream.bufferedReader().readText()
        c.disconnect()

        assertEquals(200, code)
        assertEquals("text/css; charset=utf-8", type)
        assertTrue(
            "the body is the admin page, not the stylesheet — the asset is missing from this build",
            body.trimStart().startsWith("/*")
        )
        // The values, not merely the shape: this is the whole point of the file.
        assertTrue(
            "--yk-background is not the app's ground",
            "--yk-background: ${Argb.css(KID_DARK.background)};" in body
        )
        assertTrue("no light look in the stylesheet", "[data-yk-theme=\"light\"]" in body)
    }

    @Test
    fun `it is unauthenticated, like the rest of the shell`() {
        // It carries no family data. A stylesheet behind a session cookie
        // would mean the kid's page renders unstyled until they sign in,
        // which on a television is indistinguishable from broken.
        val c = URL("http://127.0.0.1:$port/kid-tokens.css").openConnection() as HttpURLConnection
        assertEquals(200, c.responseCode)
        c.disconnect()
    }
}
