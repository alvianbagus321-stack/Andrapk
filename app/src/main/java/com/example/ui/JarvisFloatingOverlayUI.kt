package com.example.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AiConfigManager
import com.example.ui.theme.*

private val OverlaySurfaceColor = Color(0xF513161B)
private val OverlayInsetColor = Color(0xFF1B1F26)

/**
 * Floating Assistant Overlay — high-contrast matte graphite surfaces designed for
 * clear legibility over any foreground application, with strict wrap-content bounds.
 */
@Composable
fun JarvisFloatingOverlayUI(
    allowedModes: Set<OverlayUiMode> = OverlayUiMode.entries.toSet(),
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

    val infiniteTransition = rememberInfiniteTransition(label = "overlay_pulse")
    val isListening = uiMode == OverlayUiMode.LISTENING
    val pulseState = infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val pulseAlpha = if (isListening) pulseState.value else 1f

    var isExpandedView by remember { mutableStateOf(false) }

    val isTaskMode = uiMode == OverlayUiMode.THINKING ||
        uiMode == OverlayUiMode.EXECUTING ||
        uiMode == OverlayUiMode.RESULT
    val renderHere = uiMode in allowedModes

    val rootModifier = if (isTaskMode && renderHere) {
        Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 12.dp, vertical = 20.dp)
    } else {
        Modifier
            .wrapContentSize()
            .padding(6.dp)
    }

    Box(
        modifier = rootModifier,
        contentAlignment = Alignment.TopCenter
    ) {
        if (!renderHere) {
            // Rendered by the companion overlay window
        } else when (uiMode) {
            OverlayUiMode.MINI_PILL -> {
                OverlayMiniPill(
                    audioRms = audioRms,
                    onTap = onMicTap,
                    onClose = onCloseOverlay
                )
            }

            OverlayUiMode.LISTENING -> {
                OverlayListeningCard(
                    userCommand = userCommand,
                    aiReply = aiReply,
                    audioRms = audioRms,
                    pulseAlpha = pulseAlpha,
                    modelName = aiConfig.modelName,
                    onMinimize = onDismiss,
                    onClose = onCloseOverlay,
                    onCancel = onCancel
                )
            }

            OverlayUiMode.THINKING, OverlayUiMode.EXECUTING -> {
                if (!isExpandedView) {
                    CompactTaskOverlayCard(
                        isExecuting = uiMode == OverlayUiMode.EXECUTING,
                        userCommand = userCommand,
                        statusText = statusText,
                        toolName = toolName,
                        onCancel = onCancel,
                        onExpand = { isExpandedView = true },
                        onMinimize = onDismiss
                    )
                } else if (uiMode == OverlayUiMode.THINKING) {
                    OverlayThinkingCard(
                        userCommand = userCommand,
                        statusText = statusText,
                        modelName = aiConfig.modelName,
                        onMinimize = { isExpandedView = false },
                        onClose = onCloseOverlay,
                        onCancel = onCancel
                    )
                } else {
                    OverlayExecutingCard(
                        userCommand = userCommand,
                        toolName = toolName ?: "Otomasi perangkat",
                        statusText = statusText,
                        onMinimize = { isExpandedView = false },
                        onClose = onCloseOverlay,
                        onCancel = onCancel
                    )
                }
            }

            OverlayUiMode.RESULT -> {
                OverlayResultCard(
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

@Composable
private fun OverlayMiniPill(
    audioRms: Float,
    onTap: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier
            .shadow(10.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .testTag("jarvis_overlay_mini_pill"),
        shape = RoundedCornerShape(22.dp),
        color = OverlaySurfaceColor,
        border = BorderStroke(1.dp, JarvisBorderStrong)
    ) {
        Row(
            modifier = Modifier.padding(start = 11.dp, end = 5.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.clickable(onClick = onTap)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(JarvisEmerald)
                )
                Text(
                    text = "Andra",
                    color = JarvisTextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                VoiceLevelBars(audioRms = audioRms)
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.size(22.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Tutup panel",
                    tint = JarvisTextSecondary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
private fun VoiceLevelBars(audioRms: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(16.dp)
    ) {
        val h1 = (4 + audioRms * 10).coerceIn(3f, 14f).dp
        val h2 = (7 + audioRms * 13).coerceIn(5f, 16f).dp
        val h3 = (9 + audioRms * 11).coerceIn(6f, 15f).dp
        val h4 = (5 + audioRms * 9).coerceIn(3f, 13f).dp
        val barColor = if (audioRms > 0.08f) JarvisCyan else JarvisTextTertiary

        Box(Modifier.width(2.dp).height(h1).clip(RoundedCornerShape(1.dp)).background(barColor))
        Box(Modifier.width(2.dp).height(h2).clip(RoundedCornerShape(1.dp)).background(barColor))
        Box(Modifier.width(2.dp).height(h3).clip(RoundedCornerShape(1.dp)).background(barColor))
        Box(Modifier.width(2.dp).height(h4).clip(RoundedCornerShape(1.dp)).background(barColor))
    }
}

/**
 * Shared compact floating card container — strictly wraps content height (bounded to 78% screen)
 * with zero full-screen child canvases so the window never stretches over the full screen.
 */
@Composable
private fun OverlayCardShell(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
    Surface(
        modifier = modifier
            .wrapContentHeight()
            .heightIn(max = maxHeight)
            .shadow(14.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = OverlaySurfaceColor,
        border = BorderStroke(1.dp, JarvisBorderStrong)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(14.dp),
            content = content
        )
    }
}

@Composable
private fun OverlayCardHeader(
    title: String,
    subtitle: String? = null,
    dotColor: Color = JarvisCyan,
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = JarvisTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = JarvisTextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            IconButton(onClick = onMinimize, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = "Kecilkan",
                    tint = JarvisTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Tutup",
                    tint = JarvisTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun OverlayListeningCard(
    userCommand: String,
    aiReply: String,
    audioRms: Float,
    pulseAlpha: Float,
    modelName: String,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onCancel: () -> Unit
) {
    OverlayCardShell(
        modifier = Modifier
            .width(308.dp)
            .testTag("jarvis_overlay_listening_card")
    ) {
        OverlayCardHeader(
            title = "Mendengarkan",
            subtitle = modelName,
            dotColor = JarvisEmerald,
            onMinimize = onMinimize,
            onClose = onClose
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Clean voice mic indicator with subtle audio-reactive ring
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            contentAlignment = Alignment.Center
        ) {
            val ringSize = (46f + audioRms * 18f).coerceIn(46f, 62f).dp
            Box(
                modifier = Modifier
                    .size(ringSize)
                    .clip(CircleShape)
                    .background(JarvisCyan.copy(alpha = 0.12f * pulseAlpha))
                    .border(1.dp, JarvisCyan.copy(alpha = 0.45f * pulseAlpha), CircleShape)
            )
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = OverlayInsetColor,
                border = BorderStroke(1.dp, JarvisBorderStrong)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = JarvisCyan,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = OverlayInsetColor,
            border = BorderStroke(1.dp, JarvisBorder)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (aiReply.isNotBlank()) {
                    Text(
                        text = aiReply,
                        color = JarvisTextSecondary,
                        fontSize = 11.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = if (userCommand.isNotBlank()) userCommand else "Ucapkan instruksi Anda...",
                    color = if (userCommand.isNotBlank()) JarvisTextPrimary else JarvisTextSecondary,
                    fontSize = 12.5.sp,
                    fontWeight = if (userCommand.isNotBlank()) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .testTag("cancel_button"),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextSecondary),
            border = BorderStroke(1.dp, JarvisBorderStrong),
            contentPadding = PaddingValues(vertical = 0.dp)
        ) {
            Text("Batalkan", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun OverlayThinkingCard(
    userCommand: String,
    statusText: String,
    modelName: String,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onCancel: () -> Unit
) {
    OverlayCardShell(
        modifier = Modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .testTag("jarvis_overlay_thinking_card")
    ) {
        OverlayCardHeader(
            title = "Memproses permintaan",
            subtitle = modelName,
            dotColor = JarvisCyan,
            onMinimize = onMinimize,
            onClose = onClose
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (userCommand.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = OverlayInsetColor,
                border = BorderStroke(1.dp, JarvisBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = userCommand,
                    color = JarvisTextPrimary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 2.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = JarvisCyan,
                strokeWidth = 1.8.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusText.ifBlank { "Menyusun langkah eksekusi..." },
                color = JarvisTextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .testTag("cancel_button"),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisRed),
            border = BorderStroke(1.dp, JarvisRed.copy(alpha = 0.45f)),
            contentPadding = PaddingValues(vertical = 0.dp)
        ) {
            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text("Hentikan", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun OverlayExecutingCard(
    userCommand: String,
    toolName: String,
    statusText: String,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    onCancel: () -> Unit
) {
    OverlayCardShell(
        modifier = Modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .testTag("jarvis_overlay_executing_card")
    ) {
        OverlayCardHeader(
            title = "Menjalankan aksi",
            subtitle = if (userCommand.isNotBlank()) userCommand else null,
            dotColor = JarvisAmber,
            onMinimize = onMinimize,
            onClose = onClose
        )

        Spacer(modifier = Modifier.height(10.dp))

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = OverlayInsetColor,
            border = BorderStroke(1.dp, JarvisBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = JarvisAmber.copy(alpha = 0.14f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = JarvisAmber,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = toolName,
                        color = JarvisTextPrimary,
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (statusText.isNotBlank()) {
                        Text(
                            text = statusText,
                            color = JarvisTextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .testTag("cancel_button"),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisRed),
            border = BorderStroke(1.dp, JarvisRed.copy(alpha = 0.45f)),
            contentPadding = PaddingValues(vertical = 0.dp)
        ) {
            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(5.dp))
            Text("Hentikan Aksi", fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun OverlayResultCard(
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
    OverlayCardShell(
        modifier = Modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .testTag("jarvis_overlay_result_card")
    ) {
        OverlayCardHeader(
            title = "Selesai",
            subtitle = modelName,
            dotColor = JarvisEmerald,
            onMinimize = onMinimize,
            onClose = onClose
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (!toolName.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = JarvisEmerald.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, JarvisEmerald.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = JarvisEmerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (!toolResult.isNullOrBlank()) "$toolName · selesai" else toolName,
                        color = JarvisEmerald,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        val scrollState = rememberScrollState()
        val bodyText = when {
            aiReply.isNotBlank() -> aiReply
            userCommand.isNotBlank() -> "Permintaan \"$userCommand\" telah selesai diproses."
            else -> "Tugas selesai diproses."
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(OverlayInsetColor)
                .border(1.dp, JarvisBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .verticalScroll(scrollState)
        ) {
            Text(
                text = bodyText,
                color = JarvisTextPrimary,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onMicTap,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = JarvisCyan,
                    contentColor = JarvisOnPrimary
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Bicara lagi", modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text("Bicara Lagi", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = onOpenApp,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextPrimary),
                border = BorderStroke(1.dp, JarvisBorderStrong),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Buka aplikasi", modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(5.dp))
                Text("Buka App", fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun CompactTaskOverlayCard(
    isExecuting: Boolean,
    userCommand: String,
    statusText: String,
    toolName: String?,
    onCancel: () -> Unit,
    onExpand: () -> Unit,
    onMinimize: () -> Unit
) {
    val accentColor = if (isExecuting) JarvisAmber else JarvisCyan
    val titleText = if (isExecuting) "Menjalankan aksi" else "Memproses"
    val subtitleText = when {
        !toolName.isNullOrBlank() -> toolName
        statusText.isNotBlank() -> statusText
        userCommand.isNotBlank() -> userCommand
        else -> "Mohon tunggu..."
    }

    Surface(
        modifier = Modifier
            .widthIn(min = 260.dp, max = 340.dp)
            .shadow(12.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .testTag("jarvis_overlay_compact_task"),
        shape = RoundedCornerShape(14.dp),
        color = OverlaySurfaceColor,
        border = BorderStroke(1.dp, JarvisBorderStrong)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 11.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onExpand)
            ) {
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = accentColor.copy(alpha = 0.14f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isExecuting) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(15.dp)
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                color = accentColor,
                                strokeWidth = 1.8.dp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(9.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = titleText,
                            color = JarvisTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(
                            imageVector = Icons.Default.UnfoldMore,
                            contentDescription = "Perluas",
                            tint = JarvisTextTertiary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                    Text(
                        text = subtitleText,
                        color = JarvisTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = onMinimize,
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(OverlayInsetColor)
                    .testTag("minimize_task_button")
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Kecilkan",
                    tint = JarvisTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(
                    containerColor = JarvisRed,
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .height(28.dp)
                    .testTag("stop_task_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Hentikan",
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "Stop",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
