package io.yosemitekids.app.data

import org.json.JSONObject
import java.security.SecureRandom

/**
 * One kid in the family config. Everything here syncs device-to-device inside
 * config.json — an empty profiles list means the pre-profile behavior (the
 * device is the kid), so existing installs upgrade with zero visible change.
 *
 * The PIN is a 4-step D-pad sequence ("UDLR" characters), entered blind on the
 * TV so a sibling watching the screen learns nothing. Stored plaintext in the
 * config like the AI API key: the file lives in app-private storage on
 * parent-controlled devices, and the threat model is a 6-year-old, not root.
 */
data class Profile(
    /** Stable random id — names are display-only and freely editable. */
    val id: String,
    val name: String,
    /** Tile background, one of [PROFILE_COLORS] (any ARGB survives sync). */
    val colorArgb: Long = PROFILE_COLORS.first(),
    /** Emoji from [PROFILE_AVATARS], or "fluent:<res>" for a bundled image. */
    val avatar: String = PROFILE_AVATARS.first(),
    /** Feeds AI screening ("Dave is 4") — null screens against the family rules alone. */
    val age: Int? = null,
    val limits: Limits = Limits(),
    /** 4 chars of U/D/L/R; null = anyone can pick this profile. */
    val pin: String? = null,
    /**
     * When the avatar or colour was last chosen, wall-clock ms; 0 = never
     * changed since the profile was made. A kid can restyle themselves on any
     * device, and the parent can too, so the newest choice wins wherever the
     * two meet ([ProfileLooks]). Not a "modified" stamp for anything else.
     */
    val lookAt: Long = 0L
) {
    companion object {
        fun newId(): String = ByteArray(4)
            .also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
    }
}

/** Picker-tile palette — distinct at ten feet, friendly at arm's length. */
val PROFILE_COLORS = listOf(
    0xFFE53935L, // red
    0xFFF57C00L, // orange
    0xFFFBC02DL, // amber
    0xFF43A047L, // green
    0xFF00897BL, // teal
    0xFF1E88E5L, // blue
    0xFF8E24AAL, // purple
    0xFFD81B60L  // pink
)

/**
 * Curated avatar set (animals, transport, faces) for kids who can't read yet.
 * Each maps to a bundled Fluent Emoji image when the APK carries one (see
 * ui/ProfileAvatar.kt); otherwise the emoji itself renders large.
 */
val PROFILE_AVATARS = listOf(
    "🦊", "🐼", "🦁", "🐸", "🐰", "🦄", "🐙", "🦖",
    "🚗", "🚀", "🚂", "🚜", "🚁", "⛵",
    "🤖", "👻", "🌟", "🌈", "🍉", "⚽", "🎸", "🧁"
)

/** True when [pin] is a valid stored PIN: exactly four D-pad steps
 *  (the four directions plus C for the center/OK button). */
fun isValidDirectionPin(pin: String): Boolean =
    pin.length == 4 && pin.all { it in "UDLRC" }

/**
 * Whether a change to the kids list invalidates existing AI verdicts (which
 * forces every device to re-screen its whole catalog — real API money, so
 * only judgment-relevant changes may trigger it):
 *  - a NEW kid needs verdicts nobody has computed yet → re-screen;
 *  - a changed AGE changes what's appropriate → re-screen;
 *  - a rename changes nothing about the videos (verdicts key on profile id),
 *    and a removal leaves the remaining kids' verdicts exactly as valid.
 */
fun screeningJudgmentChanged(old: List<Profile>, new: List<Profile>): Boolean {
    val oldAges = old.associate { it.id to it.age }
    return new.any { p -> p.id !in oldAges || oldAges[p.id] != p.age }
}
