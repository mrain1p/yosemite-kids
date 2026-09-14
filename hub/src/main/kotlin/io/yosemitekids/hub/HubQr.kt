package io.yosemitekids.hub

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * A claim code as a QR a tablet's camera can read.
 *
 * ### Why this exists
 *
 * The code is six characters and a parent was expected to read them off a
 * laptop and type them on an iPad across the room. That is the same ask the
 * television already refuses to make: `SettingsDevices.QrImage` has drawn a
 * pairing QR since the beginning, because a code read off a screen and typed
 * on another device is a code that gets mistyped.
 *
 * So the console gets one too, and the two faces encode with **the same
 * library at the same version** — `com.google.zxing:core`, which is plain Java
 * and needs nothing from Android. What differs is only the surface it lands
 * on: the app rasterises to a `Bitmap` for Compose, this writes SVG for a
 * page. That difference is real and per-face rather than drift; the thing that
 * would have been drift — a second QR *encoder*, or a JavaScript one vendored
 * into the page — is what this avoids.
 *
 * ### SVG, and not a PNG
 *
 * The hand-rolled PNG writer in `scripts/make-hub-icons.js` exists because
 * this machine has no image library, and it is 200 lines of CRC tables to
 * produce one file at build time. A QR is squares; SVG draws squares in a
 * string, scales to any screen without a second size, and inlines into the
 * page with no route, no cache header and no second request. It is also
 * readable in a diff, which a base64 PNG is not.
 *
 * ### What it encodes, and the one thing to be careful about
 *
 * The kid page's URL with the code in it, so scanning goes straight to a
 * claimed browser and a child never types anything. The code is therefore in a
 * URL, which means it can land in the tablet's history — so [HubKidServer]'s
 * page strips it from the address bar the moment it is redeemed. That is worth
 * the trade: the code is single-use, expires in ten minutes, and burns every
 * outstanding code after five wrong guesses. A code that is *typed* is a code
 * a parent has just read aloud in a room, which is not more private.
 */
object HubQr {

    /**
     * The QR's module grid, rendered as an SVG string.
     *
     * No width or height attribute on purpose — only a `viewBox`, so the page
     * decides the size in CSS and one string serves a phone, a laptop and a
     * projector. `shape-rendering="crispEdges"` because a QR is not a
     * photograph: antialiasing its module edges is exactly the blur a camera
     * has to fight.
     */
    fun svg(content: String): String {
        // 0 quiet zone from zxing, and the margin added below instead. Asking
        // the writer for a size makes it scale modules to fit and hand back a
        // matrix whose cells are not one module each; taking its natural size
        // keeps one matrix cell to one SVG unit, which is what makes the path
        // below exact rather than approximately aligned.
        val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 0)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        val w = matrix.width
        val h = matrix.height
        val quiet = 2

        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
            .append(w + quiet * 2).append(' ').append(h + quiet * 2)
            .append("\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"Scan to watch\">")
        // The quiet zone is part of the symbol, not decoration: a QR flush to a
        // dark page edge is one a camera will not find. White, always, whatever
        // the console's theme is doing - a QR inverted for dark mode is not a
        // QR most scanners will read.
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>")
        sb.append("<path fill=\"#000000\" d=\"")
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!matrix[x, y]) { x++; continue }
                // Run-length: one path command per horizontal run of dark
                // modules rather than one rect each. A 41x41 symbol is ~1700
                // cells and this turns most rows into two or three commands,
                // which is the difference between a 40 KB string and a 4 KB one
                // on a payload the console rebuilds on every poll.
                var run = 0
                while (x + run < w && matrix[x + run, y]) run++
                sb.append('M').append(x + quiet).append(' ').append(y + quiet)
                    .append('h').append(run).append("v1h-").append(run).append('z')
                x += run
            }
        }
        sb.append("\"/></svg>")
        return sb.toString()
    }
}
