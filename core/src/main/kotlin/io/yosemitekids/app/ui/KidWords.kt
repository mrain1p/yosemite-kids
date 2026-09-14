package io.yosemitekids.app.ui

/**
 * What a **child** reads when something will not play.
 *
 * ### Why this is a separate vocabulary
 *
 * `HubPolicy.Decision` already carries a `detail`, and its own KDoc says what
 * that is for: *"a sentence for a parent's log — never handed to a child as it
 * stands"*. It reads like one, too — "this kid has a bedtime or a daily budget
 * and nobody has set the family's home timezone". True, useful in a log, and
 * the wrong thing to put in front of a five-year-old who only wants to know
 * whether they can watch.
 *
 * The browser was showing exactly that text. This is the other half: one short
 * sentence per reason, written for the person reading it.
 *
 * ### In `:core`, keyed by the reason code
 *
 * So the television, the phone and the tablet say the same thing about the
 * same refusal. A child who is told *"That is enough for today"* on the TV and
 * something else on the iPad learns that the rules depend on the screen, which
 * is the opposite of what a limit is for.
 *
 * ### The tone, which is a decision and not a default
 *
 * No blame, no apology, and no bargaining. A limit is not the app being mean
 * and it is not the app being sorry — it is simply how things are, the way a
 * closed door is. Where there is a thing the child can do ("ask a grown-up"),
 * it is said; where there is not, nothing is offered, because an invitation
 * that leads nowhere is worse than a plain full stop.
 */
object KidWords {

    /**
     * The sentence for a refusal code, or a safe general one.
     *
     * The fallback matters as much as the cases: a build that meets a reason
     * code from a newer hub must say *something* a child can read, not a code
     * name and not a blank box.
     */
    fun refusal(reason: String): String = when (reason) {
        "paused" -> "A grown-up paused watching."
        "window" -> "It is not watching time right now."
        "out-of-time" -> "That is all the watching for today."
        "blocked" -> "A grown-up chose to hide this one."
        "not-for-this-kid" -> "This one is not on your list."
        "too-short" -> "That one is too short."
        "not-screened" -> "This one has not been checked yet."
        "unknown-video" -> "This one is not here any more."
        // Genuinely a fault rather than a rule, and the only case where a
        // child is pointed at a grown-up: nothing they do can fix it.
        "needs-home-zone", "no-config" -> "Something is not set up. Ask a grown-up."
        else -> "That will not play right now."
    }

    /**
     * The countdown, with the last minute counted honestly.
     *
     * Whole minutes are a lie in the last one: a child told "1 minute left"
     * watches it say that for sixty seconds and then stop mid-sentence. Under
     * a minute this counts seconds, which is what makes the ending something
     * they can see coming rather than something that happens to them.
     */
    fun timeLeft(seconds: Long): String = when {
        seconds <= 0 -> "No time left"
        seconds < 60 -> "$seconds seconds left"
        seconds < 120 -> "1 minute left"
        else -> "${seconds / 60} minutes left"
    }

    /** Under this, the countdown stops being information and becomes a warning. */
    const val LOW_SECONDS = 5 * 60L
}
