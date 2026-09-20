package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AuroraColorScheme = darkColorScheme(
    primary = JarvisCyan,
    onPrimary = Color(0xFF04222B),
    primaryContainer = Color(0xFF0E3A47),
    onPrimaryContainer = Color(0xFFB9F2FF),
    secondary = JarvisTeal,
    onSecondary = Color(0xFF101433),
    secondaryContainer = Color(0xFF232C5E),
    onSecondaryContainer = Color(0xFFDDE2FF),
    tertiary = AuroraViolet,
    onTertiary = Color(0xFF231433),
    tertiaryContainer = Color(0xFF3A2A57),
    onTertiaryContainer = Color(0xFFEADFFF),
    background = JarvisBackground,
    onBackground = JarvisTextPrimary,
    surface = JarvisSurface,
    onSurface = JarvisTextPrimary,
    surfaceVariant = JarvisSurfaceVariant,
    onSurfaceVariant = JarvisTextSecondary,
    surfaceContainer = JarvisSurface,
    surfaceContainerHigh = JarvisSurfaceVariant,
    outline = JarvisBorder,
    outlineVariant = JarvisBorder.copy(alpha = 0.6f),
    error = JarvisRed,
    onError = Color.White
)

/** Gradient aurora utama — dipakai untuk elemen hero & branding. */
val AuroraGradient = androidx.compose.ui.graphics.Brush.linearGradient(
    listOf(JarvisCyan, AuroraIndigo, AuroraViolet)
)

/** Gradient lembut untuk kartu hero (kiri → kanan). */
val HeroCardGradient = androidx.compose.ui.graphics.Brush.linearGradient(
    listOf(
        JarvisCyan.copy(alpha = 0.14f),
        AuroraIndigo.copy(alpha = 0.10f),
        AuroraViolet.copy(alpha = 0.14f)
    )
)

/** Border gradasi halus untuk kartu kaca (glass). */
val GlassBorderBrush = androidx.compose.ui.graphics.Brush.linearGradient(
    listOf(
        JarvisCyan.copy(alpha = 0.35f),
        AuroraIndigo.copy(alpha = 0.25f),
        AuroraViolet.copy(alpha = 0.35f)
    )
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = AuroraColorScheme,
        typography = Typography,
        content = content
    )
}
