package com.tryptz.neuron.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// OLED-optimized dark palette — true black backgrounds for zero pixel power
private val NeuronDarkColors = darkColorScheme(
    primary = Color(0xFF82B1FF),
    onPrimary = Color(0xFF001E3C),
    primaryContainer = Color(0xFF002F5F),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F8),
    tertiary = Color(0xFFD5BDE2),
    onTertiary = Color(0xFF392946),
    surface = Color.Black,
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF0D0D0D),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerHigh = Color(0xFF141414),
    surfaceContainerHighest = Color(0xFF1E1E1E),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474F),
    error = Color(0xFFFFB4AB),
    background = Color.Black,
    onBackground = Color(0xFFE3E2E6),
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF1B1B1F)
)

private val NeuronLightColors = lightColorScheme(
    primary = Color(0xFF0061A4),
    surface = Color(0xFFFDFCFF),
    background = Color(0xFFFDFCFF)
)

@Composable
fun NeuronTheme(
    darkTheme: Boolean = true, // default dark for OLED battery savings
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> NeuronDarkColors
        else -> NeuronLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NeuronTypography,
        content = content
    )
}
