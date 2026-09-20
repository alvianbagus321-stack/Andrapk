package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.model.ChatMessage
import com.example.model.ChatSender
import com.example.model.AiPermissionMode
import com.example.service.JarvisVoiceManager
import com.example.service.ToolManager
import com.example.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisChatScreen(viewModel: JarvisViewModel) {
    val context = LocalContext.current
    val messages by viewModel.chatMessages.collectAsState()
    val isThinking by viewModel.isChatAiThinking.collectAsState()
    val statusText by viewModel.chatStatusText.collectAsState()

    val apiKey by viewModel.chatApiKey.collectAsState()
    val baseUrl by viewModel.chatBaseUrl.collectAsState()
    val modelName by viewModel.chatModelName.collectAsState()
    val permissionMode by ToolManager.permissionMode.collectAsState()
    val customPerms by ToolManager.customPermissions.collectAsState()

    val isVoiceSpeaking by JarvisVoiceManager.isSpeaking.collectAsState()
    val isVoiceListening by JarvisVoiceManager.isListening.collectAsState()
    val autoReadEnabled by JarvisVoiceManager.autoReadEnabled.collectAsState()
    val isVoiceCallActive by JarvisVoiceManager.isVoiceCallActive.collectAsState()
    val isHotwordEnabled by viewModel.isHotwordEnabled.collectAsState()
    val liveThought by viewModel.liveThought.collectAsState()

    val sessions by viewModel.chatSessions.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var selectedAttachmentUri by remember { mutableStateOf<String?>(null) }
    var selectedAttachmentName by remember { mutableStateOf<String?>(null) }
    var selectedAttachmentMimeType by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedAttachmentUri = uri.toString()
            selectedAttachmentMimeType = context.contentResolver.getType(uri) ?: "*/*"
            var name = "Lampiran"
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) {
                            name = cursor.getString(nameIdx)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore query error
            }
            selectedAttachmentName = name
            Toast.makeText(context, "Terlampir: $name 📎", Toast.LENGTH_SHORT).show()
        }
    }

    var showConfigDialog by remember { mutableStateOf(false) }
    var showPermsDialog by remember { mutableStateOf(false) }
    var showCustomPermsDialog by remember { mutableStateOf(false) }
    var showVoiceCallDialog by remember { mutableStateOf(false) }
    var showSessionDialog by remember { mutableStateOf(false) }
    var showThinkingHistoryDialog by remember { mutableStateOf(false) }
    var htmlToPreview by remember { mutableStateOf<String?>(null) }

    var pendingAudioAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingAudioAction?.invoke()
        } else {
            Toast.makeText(context, "Izin mikrofon diperlukan untuk fitur Voice Mode", Toast.LENGTH_SHORT).show()
        }
        pendingAudioAction = null
    }

    fun checkAndRunAudioAction(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingAudioAction = action
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(messages.size, isThinking) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisBackground)
    ) {
        // Chat Header with Endpoint & Settings Indicator
        Surface(
            color = JarvisSurface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isThinking) JarvisAmber else JarvisEmerald)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Asisten Perangkat",
                            color = JarvisTextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (modelName.isNotBlank()) modelName else "Gemini 3.5 Flash",
                                color = JarvisCyan,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = when (permissionMode) {
                                    AiPermissionMode.SANDBOXED -> JarvisTeal.copy(alpha = 0.2f)
                                    AiPermissionMode.LOW_RISK -> JarvisAmber.copy(alpha = 0.2f)
                                    AiPermissionMode.FULL_ACCESS -> JarvisRed.copy(alpha = 0.2f)
                                    AiPermissionMode.CUSTOM -> JarvisCyan.copy(alpha = 0.2f)
                                },
                                modifier = Modifier.clickable { showPermsDialog = true }
                            ) {
                                Text(
                                    text = "🛡️ ${permissionMode.name}",
                                    color = when (permissionMode) {
                                        AiPermissionMode.SANDBOXED -> JarvisTeal
                                        AiPermissionMode.LOW_RISK -> JarvisAmber
                                        AiPermissionMode.FULL_ACCESS -> JarvisRed
                                        AiPermissionMode.CUSTOM -> JarvisCyan
                                    },
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Quick Always-On "Jarvis" Background Assistant Toggle Chip
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isHotwordEnabled) JarvisEmerald.copy(alpha = 0.2f) else JarvisSurfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isHotwordEnabled) JarvisEmerald.copy(alpha = 0.8f) else JarvisBorder
                        ),
                        modifier = Modifier
                            .clickable {
                                checkAndRunAudioAction {
                                    val newState = viewModel.toggleHotword(context)
                                    Toast.makeText(
                                        context,
                                        if (newState) "Asisten suara aktif di latar belakang!"
                                        else "Asisten suara dinonaktifkan",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            .testTag("chat_background_hotword_toggle")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                Icons.Default.RecordVoiceOver,
                                contentDescription = "Asisten suara latar belakang",
                                tint = if (isHotwordEnabled) JarvisEmerald else JarvisTextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (isHotwordEnabled) "SUARA: ON" else "SUARA: OFF",
                                color = if (isHotwordEnabled) JarvisEmerald else JarvisTextSecondary,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Voice Call / Live Voice Mode Launcher Button
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = JarvisCyan.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .clickable {
                                checkAndRunAudioAction {
                                    showVoiceCallDialog = true
                                    viewModel.startVoiceCall()
                                }
                            }
                            .testTag("chat_voice_call_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                Icons.Default.GraphicEq,
                                contentDescription = "Voice Mode",
                                tint = JarvisCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "VOICE",
                                color = JarvisCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Auto-TTS Read Aloud Toggle
                    IconButton(
                        onClick = {
                            viewModel.toggleAutoRead()
                            Toast.makeText(
                                context,
                                if (!autoReadEnabled) "Auto-Read Suara AI Aktif" else "Auto-Read Suara AI Nonaktif",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.size(34.dp).testTag("chat_auto_read_toggle")
                    ) {
                        Icon(
                            imageVector = if (autoReadEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = "Auto Read AI Responses",
                            tint = if (autoReadEnabled) JarvisCyan else JarvisTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { showConfigDialog = true },
                        modifier = Modifier.size(34.dp).testTag("chat_config_button")
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "API & Base URL Config",
                            tint = JarvisCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.clearChat() },
                        modifier = Modifier.size(34.dp).testTag("chat_clear_button")
                    ) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "Clear Chat",
                            tint = JarvisTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Sub-Header: Chat Session Selector, + Sesi, and Thinking History
        Surface(
            color = JarvisSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Session Dropdown / Selector Pill
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = JarvisSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clickable { showSessionDialog = true }
                        .testTag("chat_open_sessions_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Forum,
                            contentDescription = null,
                            tint = JarvisCyan,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = currentSession?.title ?: "Sesi Percakapan",
                            color = JarvisTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = JarvisCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // New Session Quick Button
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = JarvisCyan.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .clickable {
                                viewModel.createNewSession()
                                Toast.makeText(context, "Sesi baru dibuat", Toast.LENGTH_SHORT).show()
                            }
                            .testTag("chat_quick_new_session")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Sesi Baru",
                                tint = JarvisCyan,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "+ Sesi",
                                color = JarvisCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // AI Thinking History Button
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = JarvisAmber.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisAmber.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .clickable { showThinkingHistoryDialog = true }
                            .testTag("chat_thinking_history_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Psychology,
                                contentDescription = "Riwayat Pikir AI",
                                tint = JarvisAmber,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Riwayat Pikir",
                                color = JarvisAmber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Active Status Bar when AI is thinking or executing local actions
        AnimatedVisibility(visible = isThinking) {
            Surface(
                color = JarvisSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = JarvisCyan
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (statusText.isNotBlank()) statusText else "AI sedang memproses...",
                            color = JarvisCyan,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }

                    // STOP AI Button
                    Button(
                        onClick = { viewModel.stopAiExecution() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = JarvisRed,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Hentikan AI", modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("STOP AI", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Messages List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                ChatBubble(
                    message = msg,
                    onPreviewHtml = { htmlToPreview = it }
                )
            }
        }

        // Selected Attachment Preview Chip
        if (selectedAttachmentUri != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = JarvisSurfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.5f)),
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (selectedAttachmentMimeType?.startsWith("image/") == true) Icons.Default.Image else Icons.Default.InsertDriveFile,
                            contentDescription = "Lampiran",
                            tint = JarvisCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = selectedAttachmentName ?: "Lampiran dipilih",
                            color = JarvisTextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                    IconButton(
                        onClick = {
                            selectedAttachmentUri = null
                            selectedAttachmentName = null
                            selectedAttachmentMimeType = null
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Hapus Lampiran",
                            tint = JarvisRed,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Input Bar Container
        Surface(
            color = JarvisSurface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Prominent Stop Banner when AI is actively thinking/processing
                AnimatedVisibility(visible = isThinking) {
                    Surface(
                        color = JarvisRed.copy(alpha = 0.18f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisRed.copy(alpha = 0.7f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.stopAiExecution() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = JarvisRed
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "AI Sedang Berjalan: ${statusText.ifBlank { "Memproses task..." }}",
                                    color = JarvisRed,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = JarvisRed,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("BERHENTI (STOP)", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Real-Time Live AI Thought Card
                AnimatedVisibility(visible = isThinking && !liveThought.isNullOrBlank()) {
                    var isExpanded by remember { mutableStateOf(true) }
                    Surface(
                        color = JarvisCyan.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isExpanded = !isExpanded },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Psychology, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Proses Berpikir Realtime",
                                        color = JarvisCyan,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = JarvisCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (isExpanded) {
                                Spacer(modifier = Modifier.height(4.dp))
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(
                                        text = liveThought ?: "",
                                        color = JarvisTextPrimary.copy(alpha = 0.95f),
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 5,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Attach File / Photo Button
                    IconButton(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier
                            .size(42.dp)
                            .testTag("chat_attach_file_button")
                    ) {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = "Tambah Lampiran File atau Foto",
                            tint = JarvisCyan,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                if (isThinking) "AI sedang berjalan (Klik STOP untuk menghentikan)..." else "Tulis pesan atau instruksi...",
                                color = JarvisTextSecondary,
                                fontSize = 13.sp
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_input_textfield"),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = JarvisCyan,
                            unfocusedBorderColor = JarvisBorder,
                            focusedTextColor = JarvisTextPrimary,
                            unfocusedTextColor = JarvisTextPrimary,
                            cursorColor = JarvisCyan,
                            focusedContainerColor = JarvisSurfaceVariant,
                            unfocusedContainerColor = JarvisSurfaceVariant
                        ),
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if ((inputText.isNotBlank() || selectedAttachmentUri != null) && !isThinking) {
                                viewModel.sendChatMessage(
                                    userText = inputText,
                                    attachmentUri = selectedAttachmentUri,
                                    attachmentName = selectedAttachmentName,
                                    attachmentMimeType = selectedAttachmentMimeType
                                )
                                inputText = ""
                                selectedAttachmentUri = null
                                selectedAttachmentName = null
                                selectedAttachmentMimeType = null
                            }
                        })
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // Voice Input (STT) Button
                    val micButtonColor by animateColorAsState(
                        targetValue = if (isVoiceListening) JarvisRed else JarvisSurfaceVariant,
                        label = "mic_color"
                    )
                    IconButton(
                        onClick = {
                            if (isVoiceListening) {
                                JarvisVoiceManager.stopListening()
                            } else {
                                checkAndRunAudioAction {
                                    JarvisVoiceManager.startListening(
                                        context = context,
                                        onResult = { spoken ->
                                            inputText = if (inputText.isBlank()) spoken else "$inputText $spoken"
                                        },
                                        onError = { err ->
                                            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(micButtonColor)
                            .border(1.dp, if (isVoiceListening) JarvisRed else JarvisCyan.copy(alpha = 0.5f), CircleShape)
                            .testTag("chat_mic_button")
                    ) {
                        Icon(
                            imageVector = if (isVoiceListening) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Voice Input",
                            tint = if (isVoiceListening) Color.White else JarvisCyan,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Send or STOP Button
                    if (isThinking) {
                        FloatingActionButton(
                            onClick = { viewModel.stopAiExecution() },
                            modifier = Modifier
                                .size(46.dp)
                                .testTag("chat_stop_ai_button"),
                            containerColor = JarvisRed,
                            contentColor = Color.White,
                            shape = CircleShape
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Hentikan AI dan Semua Task",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else {
                        FloatingActionButton(
                            onClick = {
                                if (inputText.isNotBlank() || selectedAttachmentUri != null) {
                                    viewModel.sendChatMessage(
                                        userText = inputText,
                                        attachmentUri = selectedAttachmentUri,
                                        attachmentName = selectedAttachmentName,
                                        attachmentMimeType = selectedAttachmentMimeType
                                    )
                                    inputText = ""
                                    selectedAttachmentUri = null
                                    selectedAttachmentName = null
                                    selectedAttachmentMimeType = null
                                }
                            },
                            modifier = Modifier
                                .size(46.dp)
                                .testTag("chat_send_button"),
                            containerColor = if (inputText.isNotBlank() || selectedAttachmentUri != null) JarvisCyan else JarvisBorder,
                            contentColor = JarvisBackground,
                            shape = CircleShape
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send Message",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Hands-free Voice Call Dialog (Live Voice Mode)
    if (showVoiceCallDialog || isVoiceCallActive) {
        JarvisVoiceCallDialog(
            onDismiss = {
                showVoiceCallDialog = false
                viewModel.endVoiceCall()
            },
            onSendMessage = { spokenInput ->
                viewModel.sendChatMessage(spokenInput)
            }
        )
    }

    // Sensitive Action / Deletion Approval Dialog
    val pendingDeletionReq by ToolManager.pendingDeletionRequest.collectAsState()
    pendingDeletionReq?.let { req ->
        AlertDialog(
            onDismissRequest = { ToolManager.denyPendingDeletion() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = JarvisRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = req.title,
                        color = JarvisTextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Text(
                    text = req.details,
                    color = JarvisTextSecondary,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { ToolManager.confirmPendingDeletion() },
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisRed, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Izinkan Hapus", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { ToolManager.denyPendingDeletion() },
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
                ) {
                    Text("Tolak (Batalkan)", color = JarvisTextPrimary, fontSize = 12.sp)
                }
            },
            containerColor = JarvisSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Config Dialog for API Key, Base URL & Model (Custom Endpoint & Presets)
    if (showConfigDialog) {
        val currentAiConfig by viewModel.aiConfig.collectAsState()
        AiConfigDialog(
            currentConfig = currentAiConfig,
            onSave = { key, url, model, provider ->
                viewModel.updateAiConfig(key, url, model, provider)
                showConfigDialog = false
            },
            onDismiss = { showConfigDialog = false }
        )
    }

    // HTML Live Preview Dialog
    htmlToPreview?.let { htmlCode ->
        HtmlPreviewDialog(
            htmlCode = htmlCode,
            onDismiss = { htmlToPreview = null }
        )
    }

    // Permission Mode Selector Dialog
    if (showPermsDialog) {
        AlertDialog(
            onDismissRequest = { showPermsDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = JarvisCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pilih Mode Izin AI", color = JarvisTextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Tentukan batasan akses otonom AI terhadap smartphone:",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    PermissionModeCard(
                        currentMode = permissionMode,
                        onModeSelected = { mode ->
                            ToolManager.setPermissionMode(mode)
                            if (mode == AiPermissionMode.CUSTOM) {
                                showCustomPermsDialog = true
                            }
                            showPermsDialog = false
                        },
                        onOpenCustomSettings = {
                            showPermsDialog = false
                            showCustomPermsDialog = true
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPermsDialog = false }) {
                    Text("Tutup", color = JarvisCyan)
                }
            },
            containerColor = JarvisSurface
        )
    }

    // Custom Permissions Dialog
    if (showCustomPermsDialog) {
        CustomPermissionsDialog(
            settings = customPerms,
            onDismiss = { showCustomPermsDialog = false },
            onSave = { updated ->
                ToolManager.updateCustomPermissions(updated)
                showCustomPermsDialog = false
            }
        )
    }

    // Chat Sessions Manager Dialog
    if (showSessionDialog) {
        JarvisSessionManagerDialog(
            sessions = sessions,
            currentSessionId = currentSession?.id ?: "",
            onSelectSession = { id -> viewModel.switchSession(id) },
            onCreateNewSession = { title -> viewModel.createNewSession(title) },
            onDeleteSession = { id -> viewModel.deleteSession(id) },
            onRenameSession = { id, newTitle -> viewModel.renameSession(id, newTitle) },
            onClearCurrentSession = { viewModel.clearChat() },
            onDismiss = { showSessionDialog = false }
        )
    }

    // AI Thinking History Dialog
    if (showThinkingHistoryDialog) {
        JarvisThinkingHistoryDialog(
            messages = messages,
            onDismiss = { showThinkingHistoryDialog = false }
        )
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    onPreviewHtml: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val isUser = message.sender == ChatSender.USER
    val isSystem = message.sender == ChatSender.SYSTEM

    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeStr = remember(message.timestamp) { timeFormatter.format(Date(message.timestamp)) }
    val detectedHtml = remember(message.text) { HtmlCodeExtractor.extractHtml(message.text) }

    if (isSystem) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = JarvisSurfaceVariant.copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = message.text,
                    color = JarvisTextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Surface(
                modifier = Modifier.size(28.dp),
                shape = CircleShape,
                color = JarvisCyan.copy(alpha = 0.2f),
                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.SmartToy,
                        contentDescription = "AI",
                        tint = JarvisCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 310.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                color = if (isUser) JarvisCyan else JarvisSurface,
                border = if (isUser) null else androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // AI Thinking Trace Banner (Expandable/Collapsible)
                    if (!isUser && !message.thinkingProcess.isNullOrBlank()) {
                        ThinkingTraceCard(
                            thinking = message.thinkingProcess,
                            actionToolName = message.actionToolName
                        )
                    }

                    // Attached File / Photo Chip Display
                    if (!message.attachmentName.isNullOrBlank() || !message.attachmentUri.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isUser) JarvisBackground.copy(alpha = 0.25f) else JarvisSurfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isUser) JarvisBackground.copy(alpha = 0.5f) else JarvisCyan.copy(alpha = 0.5f)),
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (message.attachmentMimeType?.startsWith("image/") == true) Icons.Default.Image else Icons.Default.InsertDriveFile,
                                    contentDescription = "Lampiran",
                                    tint = if (isUser) JarvisBackground else JarvisCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = message.attachmentName ?: "Lampiran",
                                    color = if (isUser) JarvisBackground else JarvisTextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Text(
                        text = message.text,
                        color = if (isUser) JarvisBackground else JarvisTextPrimary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    // HTML Live Preview Button if code detected
                    if (detectedHtml != null) {
                        HtmlPreviewBanner(
                            htmlCode = detectedHtml,
                            onOpenPreview = { onPreviewHtml(detectedHtml) }
                        )
                    }

                    // If action was executed
                    if (message.actionResult != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = JarvisSurfaceVariant,
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisEmerald.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
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
                                    text = message.actionResult,
                                    color = JarvisEmerald,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Text(
                    text = timeStr,
                    color = JarvisTextSecondary,
                    fontSize = 9.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Salin Pesan",
                    tint = JarvisCyan.copy(alpha = 0.8f),
                    modifier = Modifier
                        .size(13.dp)
                        .clickable {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Pesan Asisten", message.text)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Pesan disalin ke clipboard 📋", Toast.LENGTH_SHORT).show()
                        }
                )
                if (!isUser) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Dengarkan Jawaban Suara",
                        tint = JarvisCyan.copy(alpha = 0.8f),
                        modifier = Modifier
                            .size(13.dp)
                            .clickable {
                                JarvisVoiceManager.speak(message.text)
                            }
                    )
                }
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
                modifier = Modifier.size(28.dp),
                shape = CircleShape,
                color = JarvisSurfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = "User",
                        tint = JarvisTextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Futuristic Collapsible Thinking Card displayed directly inside the AI response bubble
 */
@Composable
fun ThinkingTraceCard(
    thinking: String,
    actionToolName: String? = null
) {
    var isExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = JarvisSurfaceVariant.copy(alpha = 0.8f),
        border = androidx.compose.foundation.BorderStroke(1.dp, JarvisAmber.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { isExpanded = !isExpanded }
            .testTag("chat_thinking_trace_card")
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = JarvisAmber,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Proses Berpikir",
                        color = JarvisAmber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (!actionToolName.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = JarvisCyan.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, JarvisCyan.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = "Tool: $actionToolName",
                                color = JarvisCyan,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isExpanded) "Tutup" else "Lihat Detail",
                        color = JarvisCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = JarvisCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = JarvisBackground,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, JarvisBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = thinking,
                            color = JarvisTextPrimary.copy(alpha = 0.9f),
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "Salin Pemikiran",
                            color = JarvisCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Proses Berpikir", thinking))
                                    android.widget.Toast.makeText(context, "Proses berpikir disalin", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                .padding(4.dp)
                        )
                    }
                }
            }
        }
    }
}
