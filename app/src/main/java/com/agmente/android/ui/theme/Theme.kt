package com.agmente.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Pine,
    secondary = Slate,
    tertiary = Clay,
    background = Mist,
    surface = Color.White,
    surfaceContainerHigh = Sand,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Ink,
    onSurface = Ink,
    onSurfaceVariant = Slate,
)

private val DarkColors = darkColorScheme(
    primary = Sand,
    secondary = Slate,
    tertiary = Clay,
)

@Composable
fun AgmenteAndroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
