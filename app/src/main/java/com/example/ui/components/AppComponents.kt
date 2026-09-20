package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AuroraViolet
import com.example.ui.theme.GlassBorderBrush
import com.example.ui.theme.JarvisBorder
import com.example.ui.theme.JarvisCyan
import com.example.ui.theme.JarvisEmerald
import com.example.ui.theme.JarvisSurface
import com.example.ui.theme.JarvisSurfaceVariant
import com.example.ui.theme.JarvisTextPrimary
import com.example.ui.theme.JarvisTextSecondary

/**
 * Aurora Design System — komponen bersama agar seluruh layar punya bahasa visual konsisten.
 */

/**
 * Latar belakang "deep space": gradasi vertikal gelap dengan pendaran aurora
 * lembut di sudut atas dan bawah. Dipakai sebagai backdrop konten utama.
 */
@Composable
fun AuroraBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF05060E), Color(0xFF0A0E20), Color(0xFF070A16))
                )
            )
    ) {
        // Pendaran aurora atas (cyan-indigo)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            JarvisCyan.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        center = androidx.compose.ui.geometry.Offset(0.12f, 0.02f),
                        radius = 900f
                    )
                )
        )
        // Pendaran aurora bawah (violet-indigo)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AuroraViolet.copy(alpha = 0.09f),
                            Color.Transparent
                        ),
                        center = androidx.compose.ui.geometry.Offset(0.92f, 0.98f),
                        radius = 1000f
                    )
                )
        )
        content()
    }
}

/**
 * Kartu "kaca" gelap dengan border gradasi aurora halus — wadah utama konten.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    containerColor: Color = JarvisSurface.copy(alpha = 0.92f),
    cornerRadius: Dp = 18.dp,
    accentBrush: Brush? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.border(
            1.dp,
            accentBrush ?: GlassBorderBrush,
            RoundedCornerShape(cornerRadius)
        ),
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        content()
    }
}

/**
 * Header section: garis aksen vertikal + judul kapital + subjudul opsional.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accent: Color = JarvisCyan,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(if (subtitle != null) 30.dp else 18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.25f)))
                )
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = JarvisTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = JarvisTextSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Pill status kecil (AKTIF / NONAKTIF / dsb).
 */
@Composable
fun StatusPill(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = JarvisEmerald
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = if (active) activeColor.copy(alpha = 0.16f) else JarvisSurfaceVariant,
        border = BorderStroke(
            0.8.dp,
            if (active) activeColor.copy(alpha = 0.55f) else JarvisBorder
        )
    ) {
        Text(
            text = text,
            color = if (active) activeColor else JarvisTextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

/**
 * Titik status bercahaya (indikator running/standby).
 */
@Composable
fun StatusDot(
    active: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = JarvisEmerald,
    inactiveColor: Color = Color(0xFFFBBF24),
    size: Dp = 9.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) activeColor else inactiveColor)
            .background(
                Brush.radialGradient(
                    listOf(
                        (if (active) activeColor else inactiveColor).copy(alpha = 0.45f),
                        Color.Transparent
                    ),
                    radius = size.value * 2f
                )
            )
    )
}

/**
 * Logo mark aplikasi: lingkaran gelap dengan ikon petir bergradasi aurora.
 */
@Composable
fun AppLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    iconSize: Dp = 20.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF0D1226))
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(JarvisCyan.copy(alpha = 0.8f), AuroraViolet.copy(alpha = 0.8f))
                ),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Bolt,
            contentDescription = "Logo",
            modifier = Modifier.size(iconSize),
            tint = JarvisCyan
        )
    }
}

/**
 * Tile ringkas untuk telemetri (baterai, jaringan, aplikasi aktif).
 */
@Composable
fun MetricTile(
    icon: ImageVector,
    label: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
    accent: Color = JarvisCyan
) {
    GlassCard(
        modifier = modifier,
        cornerRadius = 14.dp,
        accentBrush = androidx.compose.ui.graphics.SolidColor(JarvisBorder.copy(alpha = 0.8f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    label,
                    color = JarvisTextSecondary,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp
                )
            }
            Spacer(modifier = Modifier.height(7.dp))
            Text(
                value,
                color = JarvisTextPrimary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(detail, color = JarvisTextSecondary, fontSize = 9.5.sp, maxLines = 1)
        }
    }
}
