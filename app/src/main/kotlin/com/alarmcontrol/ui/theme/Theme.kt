package com.alarmcontrol.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BrandLightColors =
    lightColorScheme(
        primary = Color(0xFF315DA8),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD9E2FF),
        onPrimaryContainer = Color(0xFF001A42),
        secondary = Color(0xFF006A6A),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFF9CF1EF),
        onSecondaryContainer = Color(0xFF002020),
        tertiary = Color(0xFF6B5778),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFF2DAFF),
        onTertiaryContainer = Color(0xFF251432),
        background = Color(0xFFF8F9FD),
        onBackground = Color(0xFF191C20),
        surface = Color(0xFFF8F9FD),
        onSurface = Color(0xFF191C20),
        surfaceVariant = Color(0xFFE1E2EC),
        onSurfaceVariant = Color(0xFF44474F),
        outline = Color(0xFF74777F),
        outlineVariant = Color(0xFFC4C6D0),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )

private val BrandDarkColors =
    darkColorScheme(
        primary = Color(0xFFADC6FF),
        onPrimary = Color(0xFF002E69),
        primaryContainer = Color(0xFF164584),
        onPrimaryContainer = Color(0xFFD9E2FF),
        secondary = Color(0xFF80D5D3),
        onSecondary = Color(0xFF003737),
        secondaryContainer = Color(0xFF004F4F),
        onSecondaryContainer = Color(0xFF9CF1EF),
        tertiary = Color(0xFFD7BEE5),
        onTertiary = Color(0xFF3B2947),
        tertiaryContainer = Color(0xFF523F5F),
        onTertiaryContainer = Color(0xFFF2DAFF),
        background = Color(0xFF111318),
        onBackground = Color(0xFFE2E2E9),
        surface = Color(0xFF111318),
        onSurface = Color(0xFFE2E2E9),
        surfaceVariant = Color(0xFF44474F),
        onSurfaceVariant = Color(0xFFC4C6D0),
        outline = Color(0xFF8E9099),
        outlineVariant = Color(0xFF44474F),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )

private val AlarmControlTypography =
    Typography(
        headlineMedium =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
            ),
        headlineSmall =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 30.sp,
            ),
        titleLarge =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        titleMedium =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 23.sp,
            ),
        titleSmall =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
        labelLarge =
            TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
    )

private val AlarmControlShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )

/** Calm Expressive app theme with a deterministic brand palette and opt-in Material You colors. */
@Composable
fun AlarmControlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> BrandDarkColors
            else -> BrandLightColors
        }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AlarmControlTypography,
        shapes = AlarmControlShapes,
        content = content,
    )
}
