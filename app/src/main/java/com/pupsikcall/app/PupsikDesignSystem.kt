package com.pupsikcall.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

internal data class PupsikPalette(
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

internal object PupsikPalettes {
    val Light = PupsikPalette(
        background = Color(0xFFF5F0E7),
        surface = Color(0xFFECE4D7),
        surfaceRaised = Color(0xFFFFFCF6),
        field = Color(0xFFF9F5ED),
        outline = Color(0xFFD7C9B3),
        bronze = Color(0xFF986B3F),
        caramel = Color(0xFFC38A50),
        glow = Color(0xFFE8B777),
        text = Color(0xFF33291F),
        muted = Color(0xFF786D60),
        subtle = Color(0xFFA39889),
        online = Color(0xFF5E8164),
        danger = Color(0xFFB44A42),
        callGlass = Color(0xA8FFF9F0),
    )

    val Dark = PupsikPalette(
        background = Color(0xFF211D1A),
        surface = Color(0xFF2B2521),
        surfaceRaised = Color(0xFF342C26),
        field = Color(0xFF302923),
        outline = Color(0xFF534438),
        bronze = Color(0xFFC18B54),
        caramel = Color(0xFFE0AC70),
        glow = Color(0xFFF0BF7C),
        text = Color(0xFFF5EBDD),
        muted = Color(0xFFB5A797),
        subtle = Color(0xFF84776A),
        online = Color(0xFF91B58C),
        danger = Color(0xFFE06A5F),
        callGlass = Color(0x9C352B23),
    )
}

internal object PupsikSpacing {
    val xSmall = 4.dp
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
    val xLarge = 32.dp
    val section = 40.dp
}

internal object PupsikShapes {
    val control = RoundedCornerShape(14.dp)
    val panel = RoundedCornerShape(22.dp)
    val capsule = RoundedCornerShape(50)
}

internal val LocalPupsikPalette = staticCompositionLocalOf { PupsikPalettes.Dark }

@Composable
internal fun PupsikTheme(mode: AppearanceMode, content: @Composable () -> Unit) {
    val useDark = when (mode) {
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
    }
    val palette = if (useDark) PupsikPalettes.Dark else PupsikPalettes.Light
    val scheme = if (useDark) {
        darkColorScheme(
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
    } else {
        lightColorScheme(
            primary = palette.bronze,
            secondary = palette.caramel,
            tertiary = palette.online,
            background = palette.background,
            surface = palette.surfaceRaised,
            surfaceVariant = palette.surface,
            outline = palette.outline,
            onPrimary = Color.White,
            onSecondary = palette.text,
            onBackground = palette.text,
            onSurface = palette.text,
            onSurfaceVariant = palette.muted,
            error = palette.danger,
        )
    }
    val typography = Typography(
        displayMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 38.sp, lineHeight = 44.sp),
        headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 38.sp),
        headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp),
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
    CompositionLocalProvider(LocalPupsikPalette provides palette) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}
