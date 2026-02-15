// In file: app/src/main/java/com/example/kawatch/ui/theme/Theme.kt
package com.example.kawatch.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Define the color scheme for your app using colors from Color.kt
private val DarkColorScheme = darkColorScheme(
    primary = Navy,
    secondary = LightNavy,
    tertiary = Green,
    background = Navy,
    surface = LightNavy,
    onPrimary = White,
    onSecondary = LightSlate,
    onTertiary = Navy,
    onBackground = White,
    onSurface = LightSlate,
    error = Red,
    onError = White
)

@Composable
fun KawatchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Your app is designed for a dark theme, so we will force it.
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography, // This now correctly refers to the object in Type.kt
        content = content
    )
}
