package io.yosemitekids.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import io.yosemitekids.app.data.PROFILE_COLORS
import io.yosemitekids.app.data.Profile
import io.yosemitekids.app.ui.Argb
import io.yosemitekids.app.ui.KID_DARK
import io.yosemitekids.app.ui.KID_LIGHT
import io.yosemitekids.app.ui.KidType
import io.yosemitekids.app.ui.THEME_COLOR
import io.yosemitekids.app.ui.YosemiteDarkColors
import io.yosemitekids.app.ui.YosemiteLightColors
import io.yosemitekids.app.ui.YosemiteTypography
import io.yosemitekids.app.ui.kidColorScheme
import io.yosemitekids.app.ui.kidTinted
import io.yosemitekids.app.ui.kidTokenRoles
import io.yosemitekids.app.ui.kidTokensFor
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pin between the two colour implementations.
 *
 * The app derives its signal colours with Compose (`Color.luminance`, and a
 * `lerp` that blends in Oklab). The stylesheet the hub serves a browser is
 * written by a plain-JVM copy of that maths in `:core`, because a Gradle task
 * cannot call the Android graphics stack. Two implementations of anything is
 * a standing invitation to drift, so this is the thing that makes it not one:
 * it fails the moment either side moves.
 *
 * It asserts **exact equality** on the two grounds the generated stylesheet
 * actually covers, and one-step agreement on the eight tinted grounds a kid
 * can produce — those are not in the stylesheet (they are runtime values), and
 * the two rampers can round a channel differently on an arbitrary ground.
 * Anything worse than a rounding step is a real divergence and fails here.
 */
class KidTokensParityTest {

    private fun hex(argb: Int) = String.format("%08x", argb)

    // --- the maths ---------------------------------------------------------

    @Test
    fun `core's luminance is Compose's`() {
        for (argb in listOf(0xFFE0533D, 0xFF141218, 0xFFFAFAFA, 0xFF47B877, 0xFF000000, 0xFFFFFFFF)) {
            val mine = Argb.luminance(argb.toInt())
            val theirs = Color(argb).luminance().toDouble()
            assertTrue(
                "luminance of ${hex(argb.toInt())}: core $mine, Compose $theirs",
                abs(mine - theirs) < 1e-5
            )
        }
    }

    @Test
    fun `core blends in Oklab, exactly as Compose does`() {
        // Compose's lerp(Color, Color, Float) interpolates in Oklab. A blend
        // in sRGB or in linear light lands on a visibly different colour at
        // the same fraction, so this is the assertion that keeps the browser's
        // darkened coral the app's darkened coral.
        val ends = listOf(Argb.BLACK, Argb.WHITE)
        val starts = listOf(0xFFE0533D, 0xFFD8A13A, 0xFF47B877, 0xFF4A8B8D, 0xFF4DB6AC).map { it.toInt() }
        var offByOne = 0
        for (a in starts) for (b in ends) for (i in 1..20) {
            val t = i * 0.05f
            val mine = Argb.mix(a, b, t.toDouble())
            val theirs = lerp(Color(a), Color(b), t).toArgb()
            if (mine != theirs) {
                offByOne++
                assertTrue(
                    "mix(${hex(a)}, ${hex(b)}, $t): core ${hex(mine)}, Compose ${hex(theirs)}",
                    maxOf(
                        abs(Argb.red(mine) - Argb.red(theirs)),
                        abs(Argb.green(mine) - Argb.green(theirs)),
                        abs(Argb.blue(mine) - Argb.blue(theirs))
                    ) <= 1
                )
            }
        }
        // Every disagreement above is already held to one 8-bit step, so this
        // only catches a systematic bias — a blend that is *nearly* right at
        // every fraction rather than right at most of them. Compose does this
        // in Float and `:core` in Double, so a tenth or so of the ramp landing
        // a step apart is the expected cost of that and not a finding.
        assertTrue("$offByOne of 200 blends disagree — that is a different blend, not rounding",
            offByOne <= 40)
    }

    // --- the tokens the stylesheet ships ------------------------------------

    @Test
    fun `the generated tokens are the tokens the app draws, on both fixed grounds`() {
        for ((look, ground) in listOf("dark" to KID_DARK.background, "light" to KID_LIGHT.background)) {
            val app = kidTokensFor(Color(ground))
            val generated = kidTokenRoles(ground).toMap()
            assertEquals("$look action", hex(app.action.toArgb()), hex(generated.getValue("action")))
            assertEquals("$look on-action", hex(app.onAction.toArgb()), hex(generated.getValue("on-action")))
            assertEquals("$look time-warning", hex(app.timeWarning.toArgb()), hex(generated.getValue("time-warning")))
            assertEquals("$look watched", hex(app.watched.toArgb()), hex(generated.getValue("watched")))
            assertEquals("$look offline", hex(app.offline.toArgb()), hex(generated.getValue("offline")))
            assertEquals("$look artwork-scrim", hex(app.artworkScrim.toArgb()), hex(generated.getValue("artwork-scrim")))
            assertEquals("$look on-artwork", hex(app.onArtwork.toArgb()), hex(generated.getValue("on-artwork")))
        }
    }

    @Test
    fun `on a kid's own ground the two agree to within a rounding step`() {
        // Not in the stylesheet — a tinted ground is a runtime value — but if
        // the hub ever computes one, it must be the app's answer. Anything
        // more than one 8-bit step apart is a divergence, not rounding.
        val worst = PROFILE_COLORS.flatMap { argb ->
            val scheme = kidColorScheme(Profile(id = "t", name = "T", colorArgb = argb), THEME_COLOR)
            val ground = scheme.background.toArgb()
            val app = kidTokensFor(scheme.background)
            val generated = kidTokenRoles(ground).toMap()
            listOf(
                "action" to app.action, "time-warning" to app.timeWarning,
                "watched" to app.watched, "offline" to app.offline
            ).map { (name, colour) ->
                val a = colour.toArgb()
                val b = generated.getValue(name)
                val d = maxOf(
                    abs(Argb.red(a) - Argb.red(b)),
                    abs(Argb.green(a) - Argb.green(b)),
                    abs(Argb.blue(a) - Argb.blue(b))
                )
                Triple("${hex(argb.toInt())}/$name", d, "${hex(a)} vs ${hex(b)}")
            }
        }.filter { it.second > 1 }
        assertTrue(
            "the two token derivations have diverged, not merely rounded —\n" +
                worst.joinToString("\n") { "${it.first}: ${it.third}" },
            worst.isEmpty()
        )
    }

    @Test
    fun `every role a kid's colour moves reaches the Compose scheme`() {
        // kidColorScheme now *calls* kidTinted and converts the answer, so the
        // blend itself cannot drift — but a role the conversion forgets to copy
        // would silently keep the untinted value, which is a grey card on a
        // pink page: it reads as a rendering bug rather than as a missing line.
        //
        // Dark only, because "My colour" tints the dark look whichever way the
        // kid's theme switch is set — kidColorScheme's own base branch cannot
        // reach Light when the theme is THEME_COLOR, and this test would pass
        // vacuously if it pretended otherwise.
        for (argb in PROFILE_COLORS) {
            val drawn = kidColorScheme(Profile(id = "t", name = "T", colorArgb = argb), THEME_COLOR)
            val expected = kidTinted(KID_DARK, argb.toInt())
            val roles = listOf<Triple<String, Int, Color>>(
                Triple("primary", expected.primary, drawn.primary),
                Triple("on-primary", expected.onPrimary, drawn.onPrimary),
                Triple("primary-container", expected.primaryContainer, drawn.primaryContainer),
                Triple("on-primary-container", expected.onPrimaryContainer, drawn.onPrimaryContainer),
                Triple("background", expected.background, drawn.background),
                Triple("surface", expected.surface, drawn.surface),
                Triple("surface-variant", expected.surfaceVariant, drawn.surfaceVariant),
                Triple("surface-container-lowest", expected.surfaceContainerLowest, drawn.surfaceContainerLowest),
                Triple("surface-container-low", expected.surfaceContainerLow, drawn.surfaceContainerLow),
                Triple("surface-container", expected.surfaceContainer, drawn.surfaceContainer),
                Triple("surface-container-high", expected.surfaceContainerHigh, drawn.surfaceContainerHigh),
                Triple("surface-container-highest", expected.surfaceContainerHighest, drawn.surfaceContainerHighest),
                Triple("outline", expected.outline, drawn.outline),
                Triple("outline-variant", expected.outlineVariant, drawn.outlineVariant),
                Triple("secondary-container", expected.secondaryContainer, drawn.secondaryContainer),
                Triple("on-secondary-container", expected.onSecondaryContainer, drawn.onSecondaryContainer),
                Triple("surface-tint", expected.surfaceTint, drawn.surfaceTint)
            )
            for ((name, core, compose) in roles) {
                assertEquals("${hex(argb.toInt())} $name", hex(core), hex(compose.toArgb()))
            }
        }
    }

    @Test
    fun `a tinted chip's label still clears 4 and a half to one`() {
        // The reason legibleGround exists. Amber as a chip fill reached 3.39:1
        // before the walk was doing this, and a kid may pick any colour at all.
        for (argb in PROFILE_COLORS) {
            val tinted = kidTinted(KID_DARK, argb.toInt())
            val r = Argb.ratio(tinted.onSecondaryContainer, tinted.secondaryContainer)
            assertTrue(
                "chip label on ${hex(argb.toInt())} reaches only ${"%.2f".format(r)}:1",
                r >= 4.5
            )
        }
    }

    // --- the tables ---------------------------------------------------------

    @Test
    fun `the Compose schemes are the core tables, role for role`() {
        // The move must not have changed a single colour. Named one by one
        // rather than by reflection, because a role dropped from the binding
        // would silently fall back to Material's baseline — which looks fine
        // in review and is a grey the theme never chose.
        assertEquals(KID_DARK.primary, YosemiteDarkColors.primary.toArgb())
        assertEquals(KID_DARK.onPrimary, YosemiteDarkColors.onPrimary.toArgb())
        assertEquals(KID_DARK.primaryContainer, YosemiteDarkColors.primaryContainer.toArgb())
        assertEquals(KID_DARK.onPrimaryContainer, YosemiteDarkColors.onPrimaryContainer.toArgb())
        assertEquals(KID_DARK.secondary, YosemiteDarkColors.secondary.toArgb())
        assertEquals(KID_DARK.onSecondary, YosemiteDarkColors.onSecondary.toArgb())
        assertEquals(KID_DARK.secondaryContainer, YosemiteDarkColors.secondaryContainer.toArgb())
        assertEquals(KID_DARK.onSecondaryContainer, YosemiteDarkColors.onSecondaryContainer.toArgb())
        assertEquals(KID_DARK.tertiary!!, YosemiteDarkColors.tertiary.toArgb())
        assertEquals(KID_DARK.background, YosemiteDarkColors.background.toArgb())
        assertEquals(KID_DARK.onBackground, YosemiteDarkColors.onBackground.toArgb())
        assertEquals(KID_DARK.surface, YosemiteDarkColors.surface.toArgb())
        assertEquals(KID_DARK.onSurface, YosemiteDarkColors.onSurface.toArgb())
        assertEquals(KID_DARK.surfaceContainerLowest, YosemiteDarkColors.surfaceContainerLowest.toArgb())
        assertEquals(KID_DARK.surfaceContainerLow, YosemiteDarkColors.surfaceContainerLow.toArgb())
        assertEquals(KID_DARK.surfaceContainer, YosemiteDarkColors.surfaceContainer.toArgb())
        assertEquals(KID_DARK.surfaceContainerHigh, YosemiteDarkColors.surfaceContainerHigh.toArgb())
        assertEquals(KID_DARK.surfaceContainerHighest, YosemiteDarkColors.surfaceContainerHighest.toArgb())
        assertEquals(KID_DARK.surfaceVariant, YosemiteDarkColors.surfaceVariant.toArgb())
        assertEquals(KID_DARK.onSurfaceVariant, YosemiteDarkColors.onSurfaceVariant.toArgb())
        assertEquals(KID_DARK.outline, YosemiteDarkColors.outline.toArgb())
        assertEquals(KID_DARK.outlineVariant, YosemiteDarkColors.outlineVariant.toArgb())
        assertEquals(KID_DARK.surfaceTint, YosemiteDarkColors.surfaceTint.toArgb())

        assertEquals(KID_LIGHT.primary, YosemiteLightColors.primary.toArgb())
        assertEquals(KID_LIGHT.onPrimary, YosemiteLightColors.onPrimary.toArgb())
        assertEquals(KID_LIGHT.primaryContainer, YosemiteLightColors.primaryContainer.toArgb())
        assertEquals(KID_LIGHT.onPrimaryContainer, YosemiteLightColors.onPrimaryContainer.toArgb())
        assertEquals(KID_LIGHT.secondary, YosemiteLightColors.secondary.toArgb())
        assertEquals(KID_LIGHT.onSecondary, YosemiteLightColors.onSecondary.toArgb())
        assertEquals(KID_LIGHT.secondaryContainer, YosemiteLightColors.secondaryContainer.toArgb())
        assertEquals(KID_LIGHT.onSecondaryContainer, YosemiteLightColors.onSecondaryContainer.toArgb())
        assertEquals(KID_LIGHT.background, YosemiteLightColors.background.toArgb())
        assertEquals(KID_LIGHT.onBackground, YosemiteLightColors.onBackground.toArgb())
        assertEquals(KID_LIGHT.surface, YosemiteLightColors.surface.toArgb())
        assertEquals(KID_LIGHT.onSurface, YosemiteLightColors.onSurface.toArgb())
        assertEquals(KID_LIGHT.surfaceContainerLowest, YosemiteLightColors.surfaceContainerLowest.toArgb())
        assertEquals(KID_LIGHT.surfaceContainerLow, YosemiteLightColors.surfaceContainerLow.toArgb())
        assertEquals(KID_LIGHT.surfaceContainer, YosemiteLightColors.surfaceContainer.toArgb())
        assertEquals(KID_LIGHT.surfaceContainerHigh, YosemiteLightColors.surfaceContainerHigh.toArgb())
        assertEquals(KID_LIGHT.surfaceContainerHighest, YosemiteLightColors.surfaceContainerHighest.toArgb())
        assertEquals(KID_LIGHT.surfaceVariant, YosemiteLightColors.surfaceVariant.toArgb())
        assertEquals(KID_LIGHT.onSurfaceVariant, YosemiteLightColors.onSurfaceVariant.toArgb())
        assertEquals(KID_LIGHT.outline, YosemiteLightColors.outline.toArgb())
        assertEquals(KID_LIGHT.outlineVariant, YosemiteLightColors.outlineVariant.toArgb())
        assertEquals(KID_LIGHT.surfaceTint, YosemiteLightColors.surfaceTint.toArgb())
    }

    @Test
    fun `the Compose type scale is the core ladder, step for step`() {
        val bound = listOf(
            KidType.headlineSmall to YosemiteTypography.headlineSmall,
            KidType.titleLarge to YosemiteTypography.titleLarge,
            KidType.titleMedium to YosemiteTypography.titleMedium,
            KidType.titleSmall to YosemiteTypography.titleSmall,
            KidType.bodyLarge to YosemiteTypography.bodyLarge,
            KidType.bodyMedium to YosemiteTypography.bodyMedium,
            KidType.bodySmall to YosemiteTypography.bodySmall,
            KidType.labelLarge to YosemiteTypography.labelLarge,
            KidType.labelMedium to YosemiteTypography.labelMedium,
            KidType.labelSmall to YosemiteTypography.labelSmall
        )
        assertEquals("every style in the ladder is bound", KidType.all.size, bound.size)
        for ((step, style) in bound) {
            assertEquals("${step.css} size", step.sizeSp.toFloat(), style.fontSize.value, 0f)
            assertEquals("${step.css} line height", step.lineHeightSp.toFloat(), style.lineHeight.value, 0f)
            assertEquals("${step.css} weight", step.weight, style.fontWeight?.weight)
        }
    }
}
