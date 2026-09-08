package io.yosemitekids.app.data

/**
 * Stranger-written prose, made safe to show a five-year-old.
 *
 * A YouTube channel description is written by the channel, for YouTube, and
 * the bottom two thirds of a typical one is a list of ways to leave: a shop,
 * a Discord, a Patreon, a second channel, an e-mail address for business
 * enquiries. This app's whole promise is that what a child can reach is what
 * a parent allowed, so a description reaches a kid-facing screen with every
 * route out of it removed — the links, the bare domains a child can read
 * aloud to someone with a browser, the @handles that name a place to go
 * looking, and the e-mail addresses.
 *
 * Over-stripping is the safe direction and is chosen deliberately: the
 * domain pattern is generic rather than a list of known suffixes, so a
 * top-level domain nobody thought of is still caught, at the price of the
 * occasional "Mon.Wed.Fri" losing its dots. A blurb with a word missing is a
 * blurb; a blurb with a working address in it is a door.
 *
 * Pure, and in `:core` rather than beside the extractor, because the hub
 * resolves the same channels for the same children and a second implementation
 * of "what may a child read" is exactly the kind of thing that drifts.
 */
object SafeText {

    /** Longest blurb kept. Past this nobody is reading; it is a link list. */
    internal const val MAX = 1_000

    // Order is load-bearing: e-mail before handle (so "hi@studio.com" does not
    // leave a bare domain behind) and before domain (so the local part goes
    // with it).
    private val URL = Regex("""(?:\b(?:https?|ftp)://|\bwww\.)\S+""", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")

    /**
     * A social handle: an @ that starts a word. Kept separate from e-mail so
     * "@ 5pm" and "read@home" are left alone — the first has no name after
     * the @, the second is caught by the e-mail rule only when it really is
     * an address.
     */
    private val HANDLE = Regex("""(?<![A-Za-z0-9._%+\-@])@[A-Za-z0-9](?:[A-Za-z0-9._-]{1,})""")

    /**
     * A bare domain: two or more dot-separated labels ending in something
     * that looks like a suffix, plus any path glued to it. Labels of one
     * character are excluded, which is what keeps "e.g.", "p.m." and "U.S."
     * out of it.
     */
    private val DOMAIN = Regex(
        """\b(?:[A-Za-z0-9][A-Za-z0-9-]+\.)+[A-Za-z]{2,6}\b(?:/\S*)?""",
        RegexOption.IGNORE_CASE
    )

    /** Whitespace that survived a removal, and lines left holding punctuation. */
    private val SPACES = Regex("""[^\S\n]+""")
    private val BLANK_RUN = Regex("""\n{3,}""")
    private val PUNCT_ONLY = Regex("""^[\p{Punct}\s–—•»«]*$""")
    private val TRAILING_PUNCT = Regex("""[\s:;,\-–—|/•]+$""")

    /**
     * The channel's own words, with every way out taken out. Null when the
     * input was blank, absent, or was nothing but links — a channel whose
     * description was a link list has no description, and an empty block is
     * better than a row of stray colons.
     */
    fun forKids(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val stripped = listOf(URL, EMAIL, HANDLE, DOMAIN)
            .fold(raw) { text, pattern -> pattern.replace(text, " ") }
        val lines = stripped.lineSequence()
            .map { SPACES.replace(it, " ").trim() }
            .map { if (PUNCT_ONLY.matches(it)) "" else TRAILING_PUNCT.replace(it, "") }
            .toList()
        val text = BLANK_RUN.replace(lines.joinToString("\n"), "\n\n").trim()
        if (text.isBlank()) return null
        return clamp(text)
    }

    /** Cut at a word boundary, so the "more" affordance never opens on half a word. */
    internal fun clamp(text: String): String {
        if (text.length <= MAX) return text
        val cut = text.take(MAX)
        val lastSpace = cut.lastIndexOfAny(charArrayOf(' ', '\n'))
        return (if (lastSpace > MAX / 2) cut.take(lastSpace) else cut).trimEnd() + "…"
    }
}
