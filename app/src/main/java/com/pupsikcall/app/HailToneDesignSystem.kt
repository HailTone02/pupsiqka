package com.pupsikcall.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class AppearanceMode(val preferenceValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromPreference(value: String?): AppearanceMode = entries.firstOrNull { it.preferenceValue == value } ?: SYSTEM
    }
}

internal data class HailTonePalette(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val field: Color,
    val outline: Color,
    val bronze: Color,
    val caramel: Color,
    val glow: Color,
    val text: Color,
    val muted: Color,
    val subtle: Color,
    val online: Color,
    val danger: Color,
    val callGlass: Color,
)

internal object HailTonePalettes {
    val Light = HailTonePalette(
        background = Color(0xFFF7F3EB),
        surface = Color(0xFFEDE5D9),
        surfaceRaised = Color(0xFFFFFCF7),
        field = Color(0xFFFCFAF5),
        outline = Color(0xFFD9CCBA),
        bronze = Color(0xFF946A42),
        caramel = Color(0xFFBD9160),
        glow = Color(0xFFE6C18F),
        text = Color(0xFF342B22),
        muted = Color(0xFF786E63),
        subtle = Color(0xFFA3988B),
        online = Color(0xFF5E8164),
        danger = Color(0xFFB44A42),
        callGlass = Color(0xA8FFF9F0),
    )

    val Dark = HailTonePalette(
        background = Color(0xFF080B09),
        surface = Color(0xFF111612),
        surfaceRaised = Color(0xFF171D18),
        field = Color(0xFF111612),
        outline = Color(0xFF2B342D),
        bronze = Color(0xFF456B54),
        caramel = Color(0xFF78927F),
        glow = Color(0xFF1F382B),
        text = Color(0xFFF3F3EF),
        muted = Color(0xFFB4B8B2),
        subtle = Color(0xFF7C857E),
        online = Color(0xFF43C979),
        danger = Color(0xFFE45D5D),
        callGlass = Color(0xFF111612),
    )
}

internal object HailToneSpacing {
    val xSmall = 4.dp
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
    val xLarge = 32.dp
    val section = 40.dp
}

internal object HailToneShapes {
    val control = RoundedCornerShape(14.dp)
    val panel = RoundedCornerShape(18.dp)
    val capsule = RoundedCornerShape(50)
}

internal val LocalHailTonePalette = staticCompositionLocalOf { HailTonePalettes.Dark }

@Composable
internal fun HailToneTheme(content: @Composable () -> Unit) {
    val palette = HailTonePalettes.Dark
    val scheme = darkColorScheme(
        primary = palette.bronze,
        secondary = palette.caramel,
        tertiary = palette.online,
        background = palette.background,
        surface = palette.surface,
        surfaceVariant = palette.surfaceRaised,
        outline = palette.outline,
        onPrimary = palette.text,
        onSecondary = palette.text,
        onBackground = palette.text,
        onSurface = palette.text,
        onSurfaceVariant = palette.muted,
        error = palette.danger,
    )
    val baseTypography = Typography(
        displayMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 36.sp, lineHeight = 42.sp),
        headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 36.sp),
        headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 25.sp, lineHeight = 31.sp),
        titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(26.dp),
        extraLarge = RoundedCornerShape(34.dp),
    )
    CompositionLocalProvider(LocalHailTonePalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = baseTypography, shapes = shapes, content = content)
    }
}
