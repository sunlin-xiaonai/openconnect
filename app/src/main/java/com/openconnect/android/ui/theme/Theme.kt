package com.openconnect.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = TelegramBlue,
    secondary = TelegramSky,
    tertiary = TelegramIndigo,
    background = Cloud,
    surface = Snow,
    surfaceVariant = Pearl,
    surfaceContainerLowest = Snow,
    surfaceContainerLow = Snow,
    surfaceContainer = Pearl,
    surfaceContainerHigh = Frost,
    surfaceContainerHighest = HairlineSoft,
    primaryContainer = Color(0xFFE2F4FC),
    secondaryContainer = Color(0xFFE5F7FF),
    tertiaryContainer = Color(0xFFEBEEFF),
    error = TelegramRed,
    errorContainer = Color(0xFFFFE6E9),
    outline = Hairline,
    outlineVariant = HairlineSoft,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = Slate,
    onPrimaryContainer = Color(0xFF0F405D),
    onSecondaryContainer = Color(0xFF093C55),
    onTertiaryContainer = Color(0xFF273B75),
    onError = Color.White,
    onErrorContainer = Color(0xFF5D1720),
)

private val DarkColors = darkColorScheme(
    primary = TelegramBlue,
    secondary = TelegramSky,
    tertiary = TelegramIndigo,
    background = Midnight,
    surface = Graphite,
    surfaceVariant = Steel,
    surfaceContainerLowest = Graphite,
    surfaceContainerLow = Graphite,
    surfaceContainer = Steel,
    surfaceContainerHigh = DeepSea,
    surfaceContainerHighest = Color(0xFF202635),
    primaryContainer = Color(0xFF123A53),
    secondaryContainer = Color(0xFF14465E),
    tertiaryContainer = Color(0xFF2E396A),
    error = TelegramRed,
    errorContainer = Color(0xFF62212A),
    outline = ShadowLine,
    outlineVariant = Steel,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Ice,
    onSurface = Ice,
    onSurfaceVariant = Fog,
    onPrimaryContainer = Color(0xFFD7F1FF),
    onSecondaryContainer = Color(0xFFD5F3FF),
    onTertiaryContainer = Color(0xFFE4E9FF),
    onError = Color.White,
    onErrorContainer = Color(0xFFFFDADD),
)

private val OpenConnectTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

private val OpenConnectShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(34.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(40.dp),
)

@Composable
fun OpenConnectTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = OpenConnectTypography,
        shapes = OpenConnectShapes,
        content = content,
    )
}
