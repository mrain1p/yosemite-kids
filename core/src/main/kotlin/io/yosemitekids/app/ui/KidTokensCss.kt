package io.yosemitekids.app.ui

/**
 * The kid palette, written out as CSS custom properties.
 *
 * `:hub:generateKidTokensCss` runs [main] at build time and drops the result
 * into the hub's resources, where it is served at `/kid-tokens.css`. Nothing
 * else may produce that file: a checked-in copy would be a second palette
 * that agrees with `DesignTokens.kt` right up until somebody changes a colour
 * and only the app moves. Guard 48 enforces it.
 *
 * **Derived, not canonical, and here is the honest reason.** The four signal
 * hues are each stated once and then moved by [Argb.legibleOn] until they
 * carry text on the ground they land on: coral reads at 4.85:1 on the dark
 * ground and only 3.67:1 on paper, so the light look gets a darker coral. The
 * two ways to give a browser that are (a) ship the canonical hue and repeat
 * the derivation in JavaScript, or (b) ship the answer. This ships the answer,
 * for the two grounds the app has as constants, because (a) would be a third
 * implementation of a loop over an Oklab blend — and the second one, right
 * here in Kotlin, already needed a test in `:app` pinning it to Compose byte
 * for byte before it could be trusted. A JavaScript third would have no such
 * pin and would drift the moment either side was touched.
 *
 * What that costs, stated plainly: the per-kid tinted grounds are NOT in this
 * file. `kidColorScheme` moves the ground a few percent toward a colour the
 * kid picked at runtime, and no build-time table can enumerate that. When the
 * player learns about "My colour", the hub has to compute that kid's tokens
 * with [kidTokenRoles] and send them as inline custom properties overriding
 * these — same function, one implementation. The canonical hues are exported
 * alongside the derived ones so that path is available without another table.
 */
fun kidTokensCss(): String {
    val sb = StringBuilder()
    sb.append(
        """
        |/* GENERATED FILE - do not edit, and do not check a copy in.
        | *
        | * Written by :hub:generateKidTokensCss from
        | * core/src/main/kotlin/io/yosemitekids/app/ui/DesignTokens.kt, which is
        | * the same table Theme.kt and KidTokens.kt build the Android app from.
        | * Change a colour there and the browser changes with it; edit this file
        | * and the next build throws your edit away.
        | *
        | * Sizes are in px at the browser's default scale, which is what the
        | * app's sp values come to at a font scale of 1. A page that wants to
        | * respect the reader's own text size should scale from these, not
        | * replace them.
        | */
        |
        """.trimMargin()
    )

    sb.append("\n/* The dark look: the app's default, and this stylesheet's. */\n")
    sb.append(":root {\n")
    sb.append("  color-scheme: dark;\n")
    appendLook(sb, KID_DARK)
    sb.append("\n")
    sb.append("  /* Fixed in every look. The mark, and two borrowed conventions. */\n")
    sb.append("  --yk-brand: ${Argb.css(KidBrand.TEAL)};\n")
    sb.append("  --yk-on-brand: ${Argb.css(KidBrand.ON_TEAL)};\n")
    sb.append("  --yk-watched-progress: ${Argb.css(KidBrand.WATCHED_PROGRESS)};\n")
    sb.append("  --yk-sponsor-segment: ${Argb.css(KidBrand.SPONSOR_SEGMENT)};\n")
    sb.append("\n")
    sb.append("  /* The canonical signal hues, before any ground moves them. Here so\n")
    sb.append("     a server that knows a kid's own ground can re-derive with\n")
    sb.append("     kidTokenRoles() rather than inventing a second table. */\n")
    sb.append("  --yk-canonical-action: ${Argb.css(KidHues.ACTION)};\n")
    sb.append("  --yk-canonical-time-warning: ${Argb.css(KidHues.TIME_WARNING)};\n")
    sb.append("  --yk-canonical-watched: ${Argb.css(KidHues.WATCHED)};\n")
    sb.append("  --yk-canonical-offline: ${Argb.css(KidHues.OFFLINE)};\n")
    sb.append("\n")
    sb.append("  /* The type scale. */\n")
    for (t in KidType.all) {
        sb.append("  --yk-font-${t.css}-size: ${t.sizeSp}px;\n")
        sb.append("  --yk-font-${t.css}-line: ${t.lineHeightSp}px;\n")
        t.weight?.let { sb.append("  --yk-font-${t.css}-weight: $it;\n") }
    }
    sb.append("}\n")

    sb.append("\n/* The light look. A kid's pick, not the operating system's, so it is\n")
    sb.append("   an attribute on the root element rather than a media query. */\n")
    sb.append("[data-yk-theme=\"light\"] {\n")
    sb.append("  color-scheme: light;\n")
    appendLook(sb, KID_LIGHT)
    sb.append("}\n")
    return sb.toString()
}

private fun appendLook(sb: StringBuilder, scheme: KidScheme) {
    for ((name, argb) in scheme.roles()) {
        sb.append("  --yk-$name: ${Argb.css(argb)};\n")
    }
    for ((name, argb) in kidTokenRoles(scheme.background)) {
        sb.append("  --yk-$name: ${Argb.css(argb)};\n")
    }
}

/** `<out file>` — the one argument, so the Gradle task owns the path. */
fun main(args: Array<String>) {
    require(args.size == 1) { "usage: KidTokensCss <output .css path>" }
    val out = java.io.File(args[0])
    out.parentFile?.mkdirs()
    out.writeText(kidTokensCss())
}
