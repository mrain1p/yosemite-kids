package io.yosemitekids.app.data

import java.time.Instant
import java.time.ZoneId

/**
 * One "Add time" tap: extra minutes for one day, for one kid or for everyone.
 *
 * Carried in the config rather than fired at devices over the LAN, so it
 * travels by every path a config does — the push, the hub, the fifteen-minute
 * worker, the merge — and a television that was asleep when the parent tapped
 * still finds the minutes when it wakes. The direct `POST /grant` remains as
 * the fast path for a device that is awake, and for builds that predate this
 * field; it carries the same [id], so a device counts a tap once however
 * many ways it hears about it.
 *
 * Each tap is its own entry with its own id, and the merge unit is
 * `grant|<id>` (absent when in doubt, like a channel). Two phones granting on
 * the same day are therefore two units that both land and simply add up,
 * where a single per-kid "bonus for today" value would keep whichever was
 * stamped later and lose the other without a word.
 */
data class Grant(
    /** 8 hex, minted at the tap — [Profile.newId]'s shape. Never reused. */
    val id: String,
    /** The kid the minutes are for; null means everyone (a family with no kids set up). */
    val kidId: String?,
    /**
     * The granting phone's local calendar day, `yyyy-MM-dd`. Text, compared
     * as text: a grant counts on the day it names and on no other, and a day
     * that has passed is tombstoned by the stamper on the next save. The
     * merge itself never reads a clock, so it never decides expiry.
     */
    val date: String,
    val minutes: Int,
    /** Wall clock at the tap, for display only; the merge orders by its stamp. */
    val at: Long
)

/** The pure half of grants — what counts today, and what is new. */
object Grants {

    /** The calendar day [epochMillis] falls on in [zone], in the form [Grant.date] uses. */
    fun dateOf(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

    /**
     * The grants that count for [profileId] on [date]: their own, plus any
     * made for everyone. A grant naming a kid this device has never heard of
     * counts for nobody here and harms nothing — it may precede the push that
     * introduces the kid, exactly as the LAN route allows.
     */
    fun forKid(grants: List<Grant>, profileId: String?, date: String): List<Grant> =
        grants.filter { it.date == date && (it.kidId == null || it.kidId == profileId) }

    /** Extra minutes [profileId] has on [date], every grant summed. */
    fun minutesFor(grants: List<Grant>, profileId: String?, date: String): Int =
        forKid(grants, profileId, date).sumOf { it.minutes }

    /**
     * Of [incoming], the grants [known] has not seen — by id, never by value.
     * This is what stops the LAN fast path and the config counting one tap
     * twice: whichever arrives second finds the id and adds nothing.
     */
    fun unseen(known: List<Grant>, incoming: List<Grant>): List<Grant> {
        val seen = known.map { it.id }.toSet()
        return incoming.filter { it.id !in seen }
    }

    /** Whether a grant dated [date] belongs to a day before [today]. */
    fun expired(date: String, today: String): Boolean = date < today

    /** Who a grant is for, in the family's words — for the change log and the diff. */
    fun whose(grant: Grant, profiles: List<Profile>): String =
        grant.kidId?.let { id -> profiles.firstOrNull { it.id == id }?.name ?: "a kid" } ?: "everyone"
}
