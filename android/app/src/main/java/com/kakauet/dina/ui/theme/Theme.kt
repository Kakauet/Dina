package com.kakauet.dina.ui.theme

import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kakauet.dina.R

/*
 * Dina's design tokens. Screens and components read colors, type, shapes, spacing and motion
 * from here (`Dina.colors`, `Dina.type`, …); nothing below ui/theme hardcodes a color.
 */

/**
 * Colors of the hand-drawn character. The same in light and dark (she is a sticker), except
 * [aura]: the ink of what is drawn around her on the page (sound lines, z's).
 */
@Immutable
data class CharacterColors(
    val ink: Color,
    val aura: Color,
    val body: Color,
    val bodyShade: Color,
    val bodyLight: Color,
    val leaf: Color,
    val leafShade: Color,
    val belly: Color,
    val cheek: Color,
    val mouth: Color,
    val tongue: Color,
    val shine: Color,
    val spark: Color,
    val sweat: Color,
    val bubble: Color,
)

val CharacterInk = CharacterColors(
    ink = Color(0xFF263D2E),
    aura = Color(0xFF263D2E),
    body = Color(0xFFBDE3C3),
    bodyShade = Color(0xFF4F8A64),
    bodyLight = Color(0xFFF3FBEE),
    leaf = Color(0xFF93CF7C),
    leafShade = Color(0xFF3F7D45),
    belly = Color(0xFFE7F5DE),
    cheek = Color(0xFFF79C9A),
    mouth = Color(0xFF3A2826),
    tongue = Color(0xFFEE8579),
    shine = Color(0xFFFFFFFF),
    spark = Color(0xFFF4C64A),
    sweat = Color(0xFF9FD7EA),
    bubble = Color(0xFFFFFCF2),
)

@Immutable
data class DinaPalette(
    val isDark: Boolean,
    /** Page background, and the mint wash at the top of the home screen. */
    val background: Color,
    val backgroundTint: Color,
    /** Cards; [surfaceAlt] for nested areas and inputs; [surfaceSunken] for tracks. */
    val surface: Color,
    val surfaceAlt: Color,
    val surfaceSunken: Color,
    /** Disk behind the character, so she reads on any background. */
    val halo: Color,
    val ink: Color,
    val inkMuted: Color,
    /** Hand-drawn outlines of cards and controls. */
    val line: Color,
    /** Off-register block under cards (risograph look). */
    val shadow: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    /** Peach: only for things that need attention (ringing timers, warnings). */
    val warm: Color,
    val warmSoft: Color,
    /** Text on [warmSoft]. */
    val onWarm: Color,
    /** Text on a solid [warm] fill. */
    val onWarmFill: Color,
    /** Soft yellow for small highlights. */
    val sun: Color,
    val danger: Color,
    val dangerSoft: Color,
    val dinaBubble: Color,
    val userBubble: Color,
    val character: CharacterColors = CharacterInk,
)

val LightMeadow = DinaPalette(
    isDark = false,
    background = Color(0xFFFBF7EE),
    backgroundTint = Color(0xFFE6F2E1),
    surface = Color(0xFFF2F8EE),
    surfaceAlt = Color(0xFFE3EFDD),
    surfaceSunken = Color(0xFFD5E6CF),
    halo = Color(0xFFDDEFD8),
    ink = Color(0xFF1D3628),
    inkMuted = Color(0xFF4A6152),
    line = Color(0xFF9BBDA2),
    shadow = Color(0xFFCFE2CA),
    accent = Color(0xFF2E6A48),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFCBE7CF),
    onAccentSoft = Color(0xFF1D3628),
    warm = Color(0xFFF0A27C),
    warmSoft = Color(0xFFFCE2D3),
    onWarm = Color(0xFF6A3112),
    onWarmFill = Color(0xFF3E1A06),
    sun = Color(0xFFF6D57A),
    danger = Color(0xFFA23D2C),
    dangerSoft = Color(0xFFF8DCD5),
    dinaBubble = Color(0xFFFFFDF8),
    userBubble = Color(0xFFD6EBD2),
)

val NightMeadow = DinaPalette(
    isDark = true,
    background = Color(0xFF111A15),
    backgroundTint = Color(0xFF17281E),
    surface = Color(0xFF1A2620),
    surfaceAlt = Color(0xFF22322A),
    surfaceSunken = Color(0xFF0D1511),
    halo = Color(0xFF557F63),
    ink = Color(0xFFE8F2E6),
    inkMuted = Color(0xFFA7BBAC),
    line = Color(0xFF3F5C49),
    shadow = Color(0xFF0A110D),
    accent = Color(0xFF9DD8AE),
    onAccent = Color(0xFF0F2519),
    accentSoft = Color(0xFF2B4736),
    onAccentSoft = Color(0xFFE8F2E6),
    warm = Color(0xFFF0A27C),
    warmSoft = Color(0xFF4A2D20),
    onWarm = Color(0xFFFFDCC8),
    onWarmFill = Color(0xFF3E1A06),
    sun = Color(0xFFF6D57A),
    danger = Color(0xFFF2A394),
    dangerSoft = Color(0xFF4A2521),
    dinaBubble = Color(0xFF1F2D25),
    userBubble = Color(0xFF2B4736),
    character = CharacterInk.copy(aura = Color(0xFFCFE6D2)),
)

/** Fredoka (rounded, friendly) for titles; Nunito for text and tabular numbers. Both OFL, bundled. */
val Fredoka = FontFamily(
    Font(R.font.fredoka_medium, FontWeight.Medium),
    Font(R.font.fredoka_semibold, FontWeight.SemiBold),
)
val Nunito = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
)

@Immutable
data class DinaType(
    /** Countdowns and clocks: Nunito digits are all the same width, so they do not wobble. */
    val number: TextStyle = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 46.sp, lineHeight = 50.sp),
    val numberSmall: TextStyle = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 36.sp),
    val display: TextStyle = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 38.sp),
    val title: TextStyle = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.SemiBold, fontSize = 27.sp, lineHeight = 32.sp),
    val heading: TextStyle = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 25.sp),
    val state: TextStyle = TextStyle(fontFamily = Fredoka, fontWeight = FontWeight.Medium, fontSize = 23.sp, lineHeight = 28.sp),
    val bodyLarge: TextStyle = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    val body: TextStyle = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    val bodyStrong: TextStyle = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 23.sp),
    val label: TextStyle = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, lineHeight = 20.sp),
    val caption: TextStyle = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, lineHeight = 19.sp),
    val overline: TextStyle = TextStyle(fontFamily = Nunito, fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = 1.1.sp),
    val mono: TextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp),
)

@Immutable
data class DinaSpacing(
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 18.dp,
    val xl: Dp = 26.dp,
    val xxl: Dp = 36.dp,
    /** Side margin of every screen. */
    val gutter: Dp = 20.dp,
    /** Minimum touch target. */
    val touch: Dp = 48.dp,
)

@Immutable
data class DinaShapes(
    val card: RoundedCornerShape = RoundedCornerShape(26.dp),
    val control: RoundedCornerShape = RoundedCornerShape(18.dp),
    val bubble: RoundedCornerShape = RoundedCornerShape(22.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(50),
    /** Corner radius used by hand-drawn outlines (they draw their own path). */
    val cardRadius: Dp = 26.dp,
)

/** Durations in ms. [reduced] follows the system "remove animations" setting. */
@Immutable
data class DinaMotion(
    val reduced: Boolean = false,
    val quick: Int = 140,
    val standard: Int = 260,
    val gentle: Int = 420,
) {
    /** 0 when motion is reduced, so animations jump to their end. */
    fun ms(duration: Int) = if (reduced) 0 else duration
}

private val LocalPalette = staticCompositionLocalOf { LightMeadow }
private val LocalSpacing = staticCompositionLocalOf { DinaSpacing() }
private val LocalType = staticCompositionLocalOf { DinaType() }
private val LocalShapes = staticCompositionLocalOf { DinaShapes() }
private val LocalMotion = staticCompositionLocalOf { DinaMotion() }

/** Access as `Dina.colors`, `Dina.type`, `Dina.spacing`, `Dina.shapes`, `Dina.motion` inside composables. */
object Dina {
    val colors: DinaPalette @Composable get() = LocalPalette.current
    val spacing: DinaSpacing @Composable get() = LocalSpacing.current
    val type: DinaType @Composable get() = LocalType.current
    val shapes: DinaShapes @Composable get() = LocalShapes.current
    val motion: DinaMotion @Composable get() = LocalMotion.current
}

/** True when the system "remove animations" accessibility setting is on. */
@Composable
fun rememberSystemReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

@Composable
fun DinaTheme(palette: DinaPalette = LightMeadow, reducedMotion: Boolean = false, content: @Composable () -> Unit) {
    val base = if (palette.isDark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = palette.accent,
        onPrimary = palette.onAccent,
        primaryContainer = palette.accentSoft,
        onPrimaryContainer = palette.onAccentSoft,
        secondary = palette.accent,
        onSecondary = palette.onAccent,
        background = palette.background,
        onBackground = palette.ink,
        surface = palette.surface,
        onSurface = palette.ink,
        surfaceVariant = palette.surfaceAlt,
        onSurfaceVariant = palette.inkMuted,
        surfaceContainerLowest = palette.background,
        surfaceContainerLow = palette.surface,
        surfaceContainer = palette.surface,
        surfaceContainerHigh = palette.dinaBubble,
        surfaceContainerHighest = palette.surfaceAlt,
        outline = palette.line,
        outlineVariant = palette.line,
        error = palette.danger,
    )
    val type = DinaType()
    val material = Typography(
        bodyLarge = type.body,
        bodyMedium = type.body,
        bodySmall = type.caption,
        labelLarge = type.label,
        labelMedium = type.caption,
        titleLarge = type.heading,
        titleMedium = type.bodyStrong,
        headlineSmall = type.heading,
    )
    val shapes = DinaShapes()
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalType provides type,
        LocalShapes provides shapes,
        LocalMotion provides DinaMotion(reduced = reducedMotion),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = material,
            shapes = Shapes(small = shapes.control, medium = shapes.card, large = shapes.card, extraLarge = shapes.card),
            content = content,
        )
    }
}
