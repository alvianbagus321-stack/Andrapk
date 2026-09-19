package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AiConfigManager
import com.example.ui.theme.*

/**
 * Ultra-Futuristic Cybernetic Liquid Glass HUD Floating Overlay.
 * Designed with transparent glass center, frosted specular reflections,
 * holographic Arc Reactor core visualizer, live audio spectrum, active AI model indicators,
 * and seamless Close / Cancel support.
 */
@Composable
fun JarvisFloatingOverlayUI(
    onDismiss: () -> Unit,
    onCloseOverlay: () -> Unit,
    onCancel: () -> Unit,
    onOpenApp: () -> Unit,
    onMicTap: () -> Unit
) {
    val uiMode by JarvisOverlayManager.uiMode.collectAsState()
    val userCommand by JarvisOverlayManager.userCommand.collectAsState()
    val statusText by JarvisOverlayManager.statusText.collectAsState()
    val toolName by JarvisOverlayManager.toolName.collectAsState()
    val toolResult by JarvisOverlayManager.toolResult.collectAsState()
    val aiReply by JarvisOverlayManager.aiReply.collectAsState()
    val audioRms by JarvisOverlayManager.audioRms.collectAsState()
    val aiConfig by AiConfigManager.config.collectAsState()

    // Smooth continuous animations for Arc Reactor
    val infiniteTransition = rememberInfiniteTransition(label = "hud_cyber_loop")

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val counterRotationAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "counterRotation"
    )

    Box(
        modifier = Modifier
            .wrapContentSize()
            .padding(6.dp)
    ) {
        when (uiMode) {
            OverlayUiMode.MINI_PILL -> {
                LiquidGlassMiniPill(
                    pulseAlpha = pulseAlpha,
                    audioRms = audioRms,
                    modelName = aiConfig.modelName,
                    onTap = onMicTap,
                    onClose = onCloseOverlay
                )
            }

            OverlayUiMode.LISTENING -> {
                LiquidGlassListeningCard(
                    userCommand = userCommand,
                    aiReply = aiReply,
                    audioRms = audioRms,
                    pulseAlpha = pulseAlpha,
                    rotationAngle = rotationAngle,
                    counterRotationAngle = counterRotationAngle,
                    modelName = aiConfig.modelName,
                    onMinimize = onDismiss,
                    onClose = onCloseOverlay,
                    onCancel = onCancel
                )
            }

            OverlayUiMode.THINKING -> {
                LiquidGlassThinkingCard(
                    userCommand = userCommand,
                    statusText = statusText,
                    rotationAngle = rotationAngle,
                    modelName = aiConfig.modelName,
                    onMinimize = onDismiss,
                    onClose = onCloseOverlay,
                    onCancel = onCancel
                )
            }

            OverlayUiMode.EXECUTING -> {
                LiquidGlassExecutingCard(
                    userCommand = userCommand,
                    toolName = toolName ?: "Otomasi Perangkat",
                    statusText = statusText,
                    rotationAngle = rotationAngle,
                    onMinimize = onDismiss,
                    onClose = onCloseOverlay,
                    onCancel = onCancel
                )
            }

            OverlayUiMode.RESULT -> {
                LiquidGlassResultCard(
                    userCommand = userCommand,
                    toolName = toolName,
                    toolResult = toolResult,
                    aiReply = aiReply,
                    modelName = aiConfig.modelName,
                    onMinimize = onDismiss,
                    onClose = onCloseOverlay,
                    onOpenApp = onOpenApp,
                    onMicTap = onMicTap
                )
            }
        }
    }
}

/**
 * Transparent Liquid Glass Mini Pill Indicator
 */
@Composable
private fun LiquidGlassMiniPill(
    pulseAlpha: Float,
    audioRms: Float,
    modelName: String,
    onTap: () -> Unit,
    onClose: () -> Unit
) {
    val glassBrush = Brush.linearGradient(
        colors = listOf(
            Color(0x3538BDF8),
            Color(0x18071026),
            Color(0x221E293B)
        )
    )

    Surface(
        modifier = Modifier
            .shadow(16.dp, RoundedCornerShape(26.dp), ambientColor = JarvisCyan, spotColor = JarvisCyan)
            .clip(RoundedCornerShape(26.dp))
            .testTag("jarvis_overlay_mini_pill"),
        shape = RoundedCornerShape(26.dp),
        color = Color(0x1A050B18), // Ultra transparent liquid glass body
        border = BorderStroke(
            1.2.dp,
            Brush.horizontalGradient(
                listOf(
                    JarvisCyan.copy(alpha = 0.9f),
                    JarvisTeal.copy(alpha = 0.4f),
                    JarvisEmerald.copy(alpha = 0.8f)
                )
            )
        )
    ) {
        Box(
            modifier = Modifier
                .background(glassBrush)
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Futuristic Arc Reactor Core with Luminous Ripple
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(JarvisCyan.copy(alpha = 0.2f * pulseAlpha))
                        .border(1.2.dp, JarvisCyan.copy(alpha = pulseAlpha), CircleShape)
                        .clickable { onTap() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .shadow(4.dp, CircleShape, spotColor = JarvisCyan)
                    )
                }

                // Status & Active Model
                Column(
                    modifier = Modifier.clickable { onTap() }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "JARVIS",
                            color = JarvisCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(JarvisEmerald)
                        )
                    }
                    Text(
                        text = if (modelName.length > 13) modelName.take(11) + ".." else modelName,
                        color = JarvisTextSecondary.copy(alpha = 0.85f),
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Dynamic Audio Spectrum Frequency Bars
                Box(modifier = Modifier.clickable { onTap() }) {
                    LiquidAudioSpectrum(audioRms = audioRms)
                }

                // Mini Close Button
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Tutup Overlay",
                        tint = JarvisTextSecondary.copy(alpha = 0.7f),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }
    }
}

/**
 * Animated Sound Frequency Equalizer with Liquid Glow
 */
@Composable
private fun LiquidAudioSpectrum(audioRms: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp)
    ) {
        val h1 = (5 + audioRms * 16).coerceIn(4f, 20f).dp
        val h2 = (9 + audioRms * 22).coerceIn(6f, 24f).dp
        val h3 = (12 + audioRms * 18).coerceIn(7f, 22f).dp
        val h4 = (7 + audioRms * 14).coerceIn(5f, 18f).dp
        val h5 = (4 + audioRms * 10).coerceIn(3f, 14f).dp

        Box(modifier = Modifier.width(2.5.dp).height(h1).clip(RoundedCornerShape(1.dp)).background(JarvisCyan))
        Box(modifier = Modifier.width(2.5.dp).height(h2).clip(RoundedCornerShape(1.dp)).background(JarvisTeal))
        Box(modifier = Modifier.width(2.5.dp).height(h3).clip(RoundedCornerShape(1.dp)).background(Color.White))
        Box(modifier = Modifier.width(2.5.dp).height(h4).clip(RoundedCornerShape(1.dp)).background(JarvisCyan))
        Box(modifier = Modifier.width(2.5.dp).height(h5).clip(RoundedCornerShape(1.dp)).background(JarvisEmerald))
    }
}

/**
 * Sci-Fi Liquid Glass Container with transparent center, specular rim highlights, and corner crosshairs
 */
@Composable
private fun LiquidGlassCardContainer(
    modifier: Modifier = Modifier,
    borderColor: Color = JarvisCyan,
    content: @Composable ColumnScope.() -> Unit
) {
    val glassGradient = Brush.verticalGradient(
        colors = listOf(
            Color(0x301E293B), // Translucent top specular highlight
            Color(0x12071026), // Transparent liquid glass center
            Color(0x240A1428)  // Subtle deep bottom tone
        )
    )

    Surface(
        modifier = modifier
            .shadow(24.dp, RoundedCornerShape(22.dp), ambientColor = borderColor, spotColor = borderColor)
            .clip(RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        color = Color(0x1A050B18), // Ultra transparent liquid glass base
        border = BorderStroke(
            1.2.dp,
            Brush.sweepGradient(
                listOf(
                    borderColor.copy(alpha = 0.9f),
                    JarvisTeal.copy(alpha = 0.3f),
                    borderColor.copy(alpha = 0.7f),
                    Color(0x40FFFFFF),
                    borderColor.copy(alpha = 0.9f)
                )
            )
        )
    ) {
        Box(
            modifier = Modifier
                .background(glassGradient)
                .fillMaxWidth()
        ) {
            // Cybernetic corner markers overlay
            CyberneticCornerCrosshairs(tint = borderColor.copy(alpha = 0.5f))

            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                content()
            }
        }
    }
}

/**
 * Subtle holographic corner markers (+ and corner angles) for authentic sci-fi HUD look
 */
@Composable
private fun CyberneticCornerCrosshairs(tint: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeW = 1.2.dp.toPx()
        val armLen = 8.dp.toPx()
        val pad = 6.dp.toPx()

        // Top-Left corner
        drawLine(tint, Offset(pad, pad), Offset(pad + armLen, pad), strokeW)
        drawLine(tint, Offset(pad, pad), Offset(pad, pad + armLen), strokeW)

        // Top-Right corner
        drawLine(tint, Offset(size.width - pad, pad), Offset(size.width - pad - armLen, pad), strokeW)
        drawLine(tint, Offset(size.width - pad, pad), Offset(size.width - pad, pad + armLen), strokeW)

        // Bottom-Left corner
        drawLine(tint, Offset(pad, size.height - pad), Offset(pad + armLen, size.height - pad), strokeW)
        drawLine(tint, Offset(pad, size.height - pad), Offset(pad, size.height - pad - armLen), strokeW)

        // Bottom-Right corner
        drawLine(tint, Offset(size.width - pad, size.height - pad), Offset(size.width - pad - armLen, size.height - pad), strokeW)
        drawLine(tint, Offset(size.width - pad, size.height - pad), Offset(size.width - pad, size.height - pad - armLen), strokeW)
    }
}

/**
 * Expanded Liquid Glass Card: Listening Mode
 */
@Composable
private fun LiquidGlassListeningCard(
    userCommand: String,
    aiReply: String,
    audioRms: Float,
    pulseAlpha: Float,
    rotationAngle: Float,
    counterRotationAngle: Float,
    modelName: String,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onCancel: () -> Unit
) {
    LiquidGlassCardContainer(
        modifier = Modifier
            .width(320.dp)
            .testTag("jarvis_overlay_listening_card"),
        borderColor = JarvisCyan
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = JarvisCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "JARVIS // ONLINE",
                        color = JarvisCyan,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "SYS.CORE // $modelName",
                        color = JarvisTextSecondary.copy(alpha = 0.7f),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Window Controls: Minimize [-] and Close [X]
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onMinimize, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Remove, contentDescription = "Minimize", tint = JarvisTextSecondary, modifier = Modifier.size(15.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup Overlay", tint = JarvisTextSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Center Holographic Arc Reactor Visualizer
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp),
            contentAlignment = Alignment.Center
        ) {
            // Outer counter-rotating telemetry ring
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .rotate(counterRotationAngle)
                    .border(1.dp, Brush.sweepGradient(listOf(JarvisTeal.copy(alpha = 0.6f), Color.Transparent, JarvisCyan.copy(alpha = 0.8f))), CircleShape)
            )

            // Inner clockwise rotating ring
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .rotate(rotationAngle)
                    .border(1.5.dp, Brush.sweepGradient(listOf(JarvisCyan, Color.Transparent, JarvisEmerald, JarvisCyan)), CircleShape)
            )

            // Audio-reactive expanding shockwave
            val shockwaveScale = (1f + audioRms * 0.7f).coerceIn(1f, 1.8f)
            Box(
                modifier = Modifier
                    .size((42 * shockwaveScale).dp)
                    .clip(CircleShape)
                    .background(JarvisCyan.copy(alpha = 0.18f * pulseAlpha))
                    .border(1.2.dp, JarvisCyan.copy(alpha = pulseAlpha), CircleShape)
            )

            // Radiant Core Mic
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = Color(0x35080E1C),
                border = BorderStroke(1.2.dp, JarvisCyan)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Real-Time Transcription & Greeting Subtitle Box (Translucent Glass)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = Color(0x250A1528), // Translucent glass
            border = BorderStroke(1.dp, Brush.horizontalGradient(listOf(JarvisBorder, JarvisCyan.copy(alpha = 0.4f))))
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (aiReply.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "JARVIS:",
                            color = JarvisCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "\"$aiReply\"",
                            color = JarvisTextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Text(
                    text = if (userCommand.isNotBlank()) "🎤 \"$userCommand\"" else "Mendengarkan instruksi Anda...",
                    color = if (userCommand.isNotBlank()) JarvisEmerald else JarvisTextSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bottom Action Bar with Cancel / Close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("cancel_button"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0x20EF4444),
                    contentColor = JarvisRed
                ),
                border = BorderStroke(1.dp, JarvisRed.copy(alpha = 0.7f)),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Batal / Cancel", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Expanded Liquid Glass Card: Thinking / Reasoning Mode
 */
@Composable
private fun LiquidGlassThinkingCard(
    userCommand: String,
    statusText: String,
    rotationAngle: Float,
    modelName: String,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onCancel: () -> Unit
) {
    LiquidGlassCardContainer(
        modifier = Modifier
            .width(320.dp)
            .testTag("jarvis_overlay_thinking_card"),
        borderColor = JarvisTeal
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = JarvisTeal,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(rotationAngle)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "JARVIS • REASONING",
                        color = JarvisTeal,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "AGENT.LOOP // $modelName",
                        color = JarvisTextSecondary.copy(alpha = 0.7f),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onMinimize, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Remove, contentDescription = "Minimize", tint = JarvisTextSecondary, modifier = Modifier.size(15.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup Overlay", tint = JarvisTextSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (userCommand.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0x250A162B),
                border = BorderStroke(0.8.dp, JarvisTeal.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Instruksi: \"$userCommand\"",
                    color = JarvisCyan,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Live Status Spinner
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = JarvisTeal,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(9.dp))
            Text(
                text = statusText.ifBlank { "Menganalisa & merencanakan aksi agent loop..." },
                color = JarvisTextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cancel_button"),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color(0x20EF4444),
                contentColor = JarvisRed
            ),
            border = BorderStroke(1.dp, JarvisRed.copy(alpha = 0.7f)),
            contentPadding = PaddingValues(vertical = 5.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Batalkan Eksekusi", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Expanded Liquid Glass Card: Executing Autonomous System Tool
 */
@Composable
private fun LiquidGlassExecutingCard(
    userCommand: String,
    toolName: String,
    statusText: String,
    rotationAngle: Float,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onCancel: () -> Unit
) {
    LiquidGlassCardContainer(
        modifier = Modifier
            .width(320.dp)
            .testTag("jarvis_overlay_executing_card"),
        borderColor = JarvisAmber
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = JarvisAmber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "JARVIS • EKSEKUSI OTOMASI",
                    color = JarvisAmber,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onMinimize, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Remove, contentDescription = "Minimize", tint = JarvisTextSecondary, modifier = Modifier.size(15.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup Overlay", tint = JarvisTextSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0x301C1508),
            border = BorderStroke(1.dp, JarvisAmber.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = CircleShape,
                    color = JarvisAmber.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, JarvisAmber)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = null,
                            tint = JarvisAmber,
                            modifier = Modifier.size(18.dp).rotate(rotationAngle)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Eksekusi Tool Android / Termux",
                        color = JarvisAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = toolName,
                        color = JarvisTextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("cancel_button"),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color(0x20EF4444),
                contentColor = JarvisRed
            ),
            border = BorderStroke(1.dp, JarvisRed.copy(alpha = 0.7f)),
            contentPadding = PaddingValues(vertical = 5.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Batalkan Aksi", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Expanded Liquid Glass Card: Result / Confirmation Mode
 */
@Composable
private fun LiquidGlassResultCard(
    userCommand: String,
    toolName: String?,
    toolResult: String?,
    aiReply: String,
    modelName: String,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    onMicTap: () -> Unit
) {
    LiquidGlassCardContainer(
        modifier = Modifier
            .width(325.dp)
            .testTag("jarvis_overlay_result_card"),
        borderColor = JarvisCyan
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(22.dp),
                    shape = CircleShape,
                    color = JarvisEmerald.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, JarvisEmerald)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = JarvisEmerald, modifier = Modifier.size(14.dp))
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "JARVIS RESPON",
                        color = JarvisCyan,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "AGENT // $modelName",
                        color = JarvisTextSecondary.copy(alpha = 0.7f),
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = onMinimize, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Remove, contentDescription = "Minimize", tint = JarvisTextSecondary, modifier = Modifier.size(15.dp))
                }
                IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup Overlay", tint = JarvisTextSecondary, modifier = Modifier.size(16.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Executed Action Pill (if any)
        if (!toolName.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0x2506281E),
                border = BorderStroke(0.8.dp, JarvisEmerald.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = JarvisEmerald, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$toolName • OK",
                        color = JarvisEmerald,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Translucent Glass Response Box
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 140.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x200A152A))
                .border(0.8.dp, Brush.horizontalGradient(listOf(JarvisBorder, JarvisCyan.copy(alpha = 0.3f))), RoundedCornerShape(10.dp))
                .padding(10.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = aiReply.ifBlank { "Tugas telah selesai diproses oleh JARVIS." },
                color = JarvisTextPrimary,
                fontSize = 12.sp,
                lineHeight = 16.5.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action Buttons with Liquid Glass Aesthetic
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onMicTap,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan.copy(alpha = 0.25f), contentColor = JarvisCyan),
                border = BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.8f)),
                contentPadding = PaddingValues(vertical = 5.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Bicara Lagi", modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Bicara Lagi", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = onOpenApp,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x301E293B), contentColor = JarvisTextPrimary),
                border = BorderStroke(1.dp, JarvisBorder),
                contentPadding = PaddingValues(vertical = 5.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Buka App", modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Buka App", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
