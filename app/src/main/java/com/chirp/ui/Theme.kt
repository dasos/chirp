package com.chirp.ui

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Typography

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF0B5FFF),
    secondary = Color(0xFF00B3A4),
    tertiary = Color(0xFF1D1B20),
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    secondary = Color(0xFF69D8C3),
    tertiary = Color(0xFFE6E1E5),
)

@Composable
fun ChirpTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (!darkTheme) {
                dynamicLightColorScheme(context)
            } else {
                dynamicDarkColorScheme(context)
            }
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content,
    )
}
