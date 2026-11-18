package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = GuerrillaPink,
    secondary = AcidGreen,
    tertiary = LaserCyan,
    background = BlackCarbon,
    surface = DarkGreyCarbon,
    onBackground = WhiteText,
    onSurface = WhiteText,
    onPrimary = Color.Black,
    onSecondary = Color.Black
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force dark theme by default
    dynamicColor: Boolean = false, // Disable system dynamic pastel coloring to preserve styled tactical vibe
    content: @Composable () -> Unit,
) {
    val colorScheme = DarkColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
