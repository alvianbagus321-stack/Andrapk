package com.example.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.service.JarvisVoiceManager
import com.example.service.VoiceState
import com.example.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun JarvisVoiceCallDialog(
    onDismiss: () -> Unit,
    onSendMessage: (String) -> Unit
) {
    val voiceState by JarvisVoiceManager.voiceCallState.collectAsState()
    val voiceStatus by JarvisVoiceManager.voiceStatus.collectAsState()
    val audioRms by JarvisVoiceManager.audioRms.collectAsState()
    val isSpeaking by JarvisVoiceManager.isSpeaking.collectAsState()
    val isListening by JarvisVoiceManager.isListening.collectAsState()
    val lastUserSpeech by JarvisVoiceManager.lastUserSpeech.collectAsState()
    val lastAiSpeech by JarvisVoiceManager.lastAiSpeech.collectAsState()

    Dialog(
        onDismissRequest = {
            JarvisVoiceManager.endVoiceCall()
            onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF030712),
                            Color(0xFF081226),
                            Color(0xFF050B14)
                        )
                    )
                )
                .systemBarsPadding()
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Header
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isListening || isSpeaking) JarvisEmerald else JarvisCyan)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "JARVIS NEURAL VOICE LINK",
                            color = JarvisCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = when (voiceState) {
                            VoiceState.LISTENING -> "● MENDENGARKAN SUARA ANDA..."
                            VoiceState.THINKING -> "○ JARVIS SEDANG MEMPROSES..."
                            VoiceState.SPEAKING -> "▶ JARVIS SEDANG BERBICARA..."
                            VoiceState.IDLE -> voiceStatus.uppercase()
                        },
                        color = when (voiceState) {
                            VoiceState.LISTENING -> JarvisEmerald
                            VoiceState.THINKING -> JarvisAmber
                            VoiceState.SPEAKING -> JarvisCyan
                            VoiceState.IDLE -> JarvisTextSecondary
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Middle: Sci-Fi Animated Arc Reactor Visualizer
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    JarvisOrbVisualizer(
                        voiceState = voiceState,
                        audioRms = audioRms,
                        isSpeaking = isSpeaking
                    )

                    // Center Icon
                    Icon(
                        imageVector = when {
                            isSpeaking -> Icons.Default.VolumeUp
                            isListening -> Icons.Default.Mic
                            voiceState == VoiceState.THINKING -> Icons.Default.AutoAwesome
                            else -> Icons.Default.GraphicEq
                        },
                        contentDescription = "Voice State",
                        tint = when (voiceState) {
                            VoiceState.LISTENING -> JarvisEmerald
                            VoiceState.THINKING -> JarvisAmber
                            VoiceState.SPEAKING -> JarvisCyan
                            else -> JarvisCyan.copy(alpha = 0.8f)
                        },
                        modifier = Modifier.size(48.dp)
                    )
                }

                // Live Captions & Subtitles
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (lastUserSpeech.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = JarvisSurfaceVariant.copy(alpha = 0.8f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "ANDA",
                                    color = JarvisTextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "\"$lastUserSpeech\"",
                                    color = JarvisTextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    if (lastAiSpeech.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = JarvisCyan.copy(alpha = 0.08f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.SmartToy,
                                        contentDescription = null,
                                        tint = JarvisCyan,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "JARVIS",
                                        color = JarvisCyan,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = lastAiSpeech.take(240) + if (lastAiSpeech.length > 240) "..." else "",
                                    color = JarvisTextPrimary,
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }

                // Bottom Action Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Stop Speech Button
                    IconButton(
                        onClick = { JarvisVoiceManager.stopSpeaking() },
                        enabled = isSpeaking,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (isSpeaking) JarvisSurface else JarvisSurface.copy(alpha = 0.4f))
                            .border(1.dp, if (isSpeaking) JarvisAmber else JarvisBorder, CircleShape)
                            .testTag("voice_stop_speech")
                    ) {
                        Icon(
                            Icons.Default.VolumeOff,
                            contentDescription = "Hentikan Suara",
                            tint = if (isSpeaking) JarvisAmber else JarvisTextSecondary.copy(alpha = 0.5f)
                        )
                    }

                    // End Call Button (Main Action - Red)
                    IconButton(
                        onClick = {
                            JarvisVoiceManager.endVoiceCall()
                            onDismiss()
                        },
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(JarvisRed)
                            .shadow(12.dp, CircleShape, spotColor = JarvisRed)
                            .testTag("voice_end_call")
                    ) {
                        Icon(
                            Icons.Default.CallEnd,
                            contentDescription = "Akhiri Mode Suara",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    // Mic Mute / Restart Listening Button
                    IconButton(
                        onClick = {
                            if (isListening) {
                                JarvisVoiceManager.stopListening()
                            } else {
                                JarvisVoiceManager.stopSpeaking()
                                JarvisVoiceManager.startListening(
                                    context = com.example.JarvisApp.instance,
                                    onResult = { txt ->
                                        onSendMessage(txt)
                                    }
                                )
                            }
                        },
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(if (isListening) JarvisEmerald.copy(alpha = 0.2f) else JarvisSurface)
                            .border(
                                1.dp,
                                if (isListening) JarvisEmerald else JarvisCyan.copy(alpha = 0.5f),
                                CircleShape
                            )
                            .testTag("voice_toggle_mic")
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = "Toggle Mic",
                            tint = if (isListening) JarvisEmerald else JarvisTextSecondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * Animated Arc Reactor / Glowing Pulse Orb Visualizer for JARVIS Voice
 */
@Composable
fun JarvisOrbVisualizer(
    voiceState: VoiceState,
    audioRms: Float,
    isSpeaking: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_rotation")

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val activeColor = when (voiceState) {
        VoiceState.LISTENING -> JarvisEmerald
        VoiceState.THINKING -> JarvisAmber
        VoiceState.SPEAKING -> JarvisCyan
        VoiceState.IDLE -> JarvisCyan.copy(alpha = 0.4f)
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = (size.minDimension / 2f) * 0.75f

        // Dynamic radius reacting to voice amplitude or thinking pulse
        val dynamicR = when (voiceState) {
            VoiceState.LISTENING -> baseRadius * (0.85f + audioRms * 0.4f)
            VoiceState.SPEAKING -> baseRadius * pulseScale
            VoiceState.THINKING -> baseRadius * pulseScale
            VoiceState.IDLE -> baseRadius * 0.85f
        }

        // Outer glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    activeColor.copy(alpha = 0.35f),
                    activeColor.copy(alpha = 0.1f),
                    Color.Transparent
                ),
                center = center,
                radius = dynamicR * 1.35f
            ),
            radius = dynamicR * 1.35f,
            center = center
        )

        // Middle dashed ring (Rotating)
        drawCircle(
            color = activeColor.copy(alpha = 0.6f),
            radius = dynamicR,
            center = center,
            style = Stroke(width = 3.dp.toPx())
        )

        // Outer futuristic tick marks
        val tickCount = 24
        for (i in 0 until tickCount) {
            val angle = Math.toRadians((i * (360.0 / tickCount) + rotationAngle).toDouble())
            val innerR = dynamicR * 1.06f
            val outerR = dynamicR * 1.14f
            val start = Offset(
                x = center.x + (innerR * cos(angle)).toFloat(),
                y = center.y + (innerR * sin(angle)).toFloat()
            )
            val end = Offset(
                x = center.x + (outerR * cos(angle)).toFloat(),
                y = center.y + (outerR * sin(angle)).toFloat()
            )
            drawLine(
                color = activeColor.copy(alpha = if (i % 4 == 0) 0.9f else 0.4f),
                start = start,
                end = end,
                strokeWidth = if (i % 4 == 0) 3.dp.toPx() else 1.5.dp.toPx()
            )
        }

        // Inner glowing core
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    activeColor.copy(alpha = 0.5f),
                    activeColor.copy(alpha = 0.15f),
                    Color.Transparent
                ),
                center = center,
                radius = dynamicR * 0.55f
            ),
            radius = dynamicR * 0.55f,
            center = center
        )
    }
}
