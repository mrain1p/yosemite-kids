package io.yosemitekids.hub

import com.sun.net.httpserver.HttpExchange

/**
 * The headers every reply on either of the hub's two faces carries.
 *
 * Written once rather than per listener. `HubServer` and `HubKidServer` each
 * kept a byte-identical copy of this, three lines below a KDoc explaining that
 * a security check must not exist twice — and the copies are exactly the kind
 * that drift, because a person hardening the console has no reason to open the
 * kid's file. Each server keeps its own one-line `securityHeaders(ex)` so the
 * gate's per-file count of headed replies (guards 41 and 60) still reads what
 * it has always read.
 */
object HubHttp {

    /**
     * The policy a page on this origin runs under.
     *
     * `'none'` by default and then only what the page actually uses, so a
     * script injected through any of the text a family types — a channel's
     * name, a kid's, a note — has nowhere to send what it reads and nothing
     * to load. `'unsafe-inline'` is unavoidable while the pages are single
     * files with their markup, style and script together; it is what makes
     * the `connect-src`, `form-action` and `base-uri` clauses the load-bearing
     * ones here, since those are what a successful injection would need.
     *
     * Two lists differ between the faces:
     *  - **images.** The console draws a screening verdict's poster straight
     *    from YouTube's own host; the kid page proxies every thumbnail through
     *    `/kid/thumb`, so its policy names no outside host at all.
     *  - **media.** Only the kid page plays video, and dash.js hands the
     *    `<video>` element a `blob:` URL of its own making.
     */
    fun contentSecurityPolicy(kidOrigin: Boolean): String = buildString {
        append("default-src 'none'; ")
        append("script-src 'self' 'unsafe-inline'; ")
        append("style-src 'self' 'unsafe-inline'; ")
        append(if (kidOrigin) "img-src 'self' data:; " else "img-src 'self' https://i.ytimg.com data:; ")
        if (kidOrigin) append("media-src 'self' blob:; ")
        append("connect-src 'self'; ")
        append("manifest-src 'self'; ")
        append("font-src 'self'; ")
        // The three that cost a page nothing and close the shapes an injected
        // script would otherwise reach for: a frame around us, a rewritten
        // base for every relative URL, and a form posting somewhere else.
        append("frame-ancestors 'none'; ")
        append("base-uri 'none'; ")
        append("form-action 'self'")
    }

    /** The baseline every reply carries, on both faces. */
    fun securityHeaders(ex: HttpExchange, kidOrigin: Boolean) {
        ex.responseHeaders.add("X-Content-Type-Options", "nosniff")
        ex.responseHeaders.add("X-Frame-Options", "DENY")
        ex.responseHeaders.add("Referrer-Policy", "no-referrer")
        ex.responseHeaders.add("Content-Security-Policy", contentSecurityPolicy(kidOrigin))
    }
}
