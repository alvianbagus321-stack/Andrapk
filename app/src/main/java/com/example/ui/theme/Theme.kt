package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp

private val GraphiteColorScheme = darkColorScheme(
    primary = JarvisCyan,
    onPrimary = JarvisOnPrimary,
    primaryContainer = Color(0xFF17263C),
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = JarvisTeal,
    onSecondary = Color(0xFF0E131A),
    secondaryContainer = JarvisSurfaceHighlight,
    onSecondaryContainer = Color(0xFFDCE2EC),
    tertiary = JarvisEmerald,
    onTertiary = Color(0xFF051B10),
    tertiaryContainer = Color(0xFF112C20),
    onTertiaryContainer = Color(0xFFC5F3DA),
    background = JarvisBackground,
    onBackground = JarvisTextPrimary,
    surface = JarvisSurface,
    onSurface = JarvisTextPrimary,
    surfaceVariant = JarvisSurfaceVariant,
    onSurfaceVariant = JarvisTextSecondary,
    surfaceContainerLowest = Color(0xFF080A0D),
    surfaceContainerLow = Color(0xFF101318),
    surfaceContainer = JarvisSurface,
    surfaceContainerHigh = JarvisSurfaceVariant,
    surfaceContainerHighest = JarvisSurfaceHighlight,
    outline = JarvisBorderStrong,
    outlineVariant = JarvisBorder,
    error = JarvisRed,
    onError = Color(0xFF22070A),
    errorContainer = Color(0xFF331418),
    onErrorContainer = Color(0xFFFFD9DC)
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp)
)

/** Aksen primer halus untuk identitas merek. */
val AuroraGradient: Brush = Brush.linearGradient(
    listOf(JarvisCyan, JarvisCyanDark)
)

/** Permukaan kartu utama dengan pencahayaan vertikal sangat lembut. */
val HeroCardGradient: Brush = Brush.verticalGradient(
    listOf(JarvisSurfaceElevated, JarvisSurface)
)

/** Garis tepi struktural netral untuk seluruh kartu (bebas efek neon). */
val GlassBorderBrush: Brush = SolidColor(JarvisBorder)

/** Garis tepi kartu utama — halus dengan highlight atas tipis (pra-alokasi). */
val CardBorderCyanViolet: Brush = Brush.verticalGradient(
    listOf(JarvisBorderStrong, JarvisBorder)
)

/** Garis tepi kartu sekunder — netral konsisten (pra-alokasi). */
val CardBorderTealViolet: Brush = SolidColor(JarvisBorder)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = GraphiteColorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
