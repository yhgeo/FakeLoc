package com.mo.fakeloc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkScheme = darkColorScheme(
    primary = Cyan,
    onPrimary = BgDeep,
    primaryContainer = CyanDark,
    onPrimaryContainer = TextPrimary,
    secondary = Pink,
    onSecondary = BgDeep,
    background = BgDeep,
    onBackground = TextPrimary,
    surface = BgSurface,
    onSurface = TextPrimary,
    surfaceVariant = BgSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    error = ErrRed,
    outline = TextSecondary
)

private val LightScheme = lightColorScheme(
    primary = CyanDark,
    secondary = Pink,
    background = androidx.compose.ui.graphics.Color(0xFFF6F8FC),
    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF)
)

@Composable
fun FakeLocTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = FakeLocTypography,
        content = content
    )
}
