package io.yosemitekids.app.ui

/**
 * The quiet line under a video's title, in words every face agrees on.
 *
 * Both halves lived in `:app` until 1.9.0, which was fine while only the
 * phone and the television could say when a video came out. The day the hub's
 * index started keeping the date (roadmap §2M), the browser could say it too —
 * and the hub composes the line server-side so the page draws it verbatim
 * (guard 61: the page draws, it does not decide). Three faces, one "3 days
 * ago"; a second spelling here would be a card that reads differently on the
 * tablet than on the TV beside it.
 */

/**
 * "today", "3 days ago", "2 weeks ago" — the age of an upload, the way every
 * video app says it. Null when there is no date (a cache or index row that
 * predates the date column): nothing is shown rather than a guess.
 */
fun relativeAge(publishedAt: Long?, now: Long = System.currentTimeMillis()): String? {
    publishedAt ?: return null
    val days = ((now - publishedAt) / 86_400_000L).toInt()
    return when {
        days < 0 -> null
        days == 0 -> "today"
        days == 1 -> "yesterday"
        days < 7 -> "$days days ago"
        days < 30 -> "${days / 7} week${if (days / 7 == 1) "" else "s"} ago"
        days < 365 -> "${days / 30} month${if (days / 30 == 1) "" else "s"} ago"
        else -> "${days / 365} year${if (days / 365 == 1) "" else "s"} ago"
    }
}

/**
 * "Channel · 3 days ago" — the line itself.
 *
 * It degrades to whichever halves it has. The design puts a release date on
 * nearly every card, but `showVideoAge` is a parent switch that defaults to
 * off, so the common case is the channel alone — and the separator has to go
 * with the part it separates. A blank channel name (a cache row from an older
 * build) drops out the same way, rather than leaving the line opening on a
 * dangling "·".
 *
 * `MetaLineTest` holds this: no leading, trailing or doubled separator, for
 * any combination of empty, blank and null.
 */
fun metaLine(channel: String, age: String?): String =
    listOf(channel, age)
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .joinToString(" · ")
