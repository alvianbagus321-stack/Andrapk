package com.example.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.model.ServerLogItem
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun JarvisDashboardScreen(
    viewModel: JarvisViewModel,
    onRequestMediaProjection: () -> Unit
) {
    val context = LocalContext.current
    val isAccessConnected by viewModel.isAccessibilityConnected.collectAsState()
    val isMediaProjActive by viewModel.isMediaProjectionActive.collectAsState()
    val isServerRunning by viewModel.isServerRunning.collectAsState()
    val port by viewModel.port.collectAsState()
    val token by viewModel.token.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val logs by viewModel.serverLogs.collectAsState()

    val isHotwordEnabled by viewModel.isHotwordEnabled.collectAsState()
    val isHotwordListening by viewModel.isHotwordListeningActive.collectAsState()
    val isOverlayVisible by viewModel.isOverlayVisible.collectAsState()
    val aiConfig by viewModel.aiConfig.collectAsState()

    var showAiConfigDialog by remember { mutableStateOf(false) }
    var selectedLog by remember { mutableStateOf<ServerLogItem?>(null) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
                                        if (granted) {
                                            viewModel.setHotwordEnabled(context, true)
                                            Toast.makeText(context, "Asisten suara aktif di latar belakang!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Izin mikrofon dibutuhkan untuk asisten suara", Toast.LENGTH_LONG).show()
                                        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp)
    ) {
        // ASISTEN SUARA & HUD CARD
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("background_voice_assistant_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = CardBorderCyanViolet)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(38.dp),
                                shape = CircleShape,
                                color = if (isHotwordEnabled) JarvisCyan.copy(alpha = 0.2f) else JarvisSurfaceVariant,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isHotwordEnabled) JarvisCyan else JarvisBorder
                                )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.RecordVoiceOver,
                                        contentDescription = "Voice Assistant",
                                        tint = if (isHotwordEnabled) JarvisCyan else JarvisTextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "ASISTEN SUARA",
                                        color = JarvisCyan,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 13.sp,
                                        letterSpacing = 1.sp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (isHotwordEnabled) JarvisEmerald.copy(alpha = 0.2f) else JarvisSurfaceHighlight
                                    ) {
                                        Text(
                                            text = if (isHotwordEnabled) "AKTIF" else "OFF",
                                            color = if (isHotwordEnabled) JarvisEmerald else JarvisTextSecondary,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "Asisten Suara Latar Belakang & HUD Melayang",
                                    color = JarvisTextSecondary,
                                    fontSize = 10.5.sp
                                )
                            }
                        }

                        Switch(
                            checked = isHotwordEnabled,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    val hasAudio = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!hasAudio) {
                                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    } else {
                                        viewModel.setHotwordEnabled(context, true)
                                        Toast.makeText(context, "Asisten suara aktif di latar belakang!", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    viewModel.setHotwordEnabled(context, false)
                                    Toast.makeText(context, "Asisten suara dinonaktifkan", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = JarvisCyan,
                                checkedTrackColor = JarvisCyan.copy(alpha = 0.3f),
                                uncheckedThumbColor = JarvisTextSecondary,
                                uncheckedTrackColor = JarvisSurfaceHighlight
                            )
                        )
                    }

                    HorizontalDivider(color = JarvisBorder.copy(alpha = 0.4f))

                    // Description
                    Text(
                        text = if (isHotwordEnabled)
                            "Asisten mendengarkan kata aktivasi 'Jarvis [perintah]' di latar belakang tanpa perlu membuka aplikasi. Setiap respon dan tool otomatis ditampilkan melalui HUD melayang."
                        else
                            "Aktifkan sakelar di atas agar aplikasi mendeteksi kata aktivasi saat berjalan di latar belakang, seperti asisten bawaan perangkat.",
                        color = JarvisTextPrimary.copy(alpha = 0.85f),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )

                    // Overlay Status Check
                    val canDrawOverlay = JarvisOverlayManager.canDrawOverlay(context)
                    if (!canDrawOverlay) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = JarvisAmber.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisAmber.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Izin Tampilkan di Atas Aplikasi Lain",
                                        color = JarvisAmber,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Diperlukan agar HUD asisten dapat muncul saat Anda membuka aplikasi lain.",
                                        color = JarvisTextSecondary,
                                        fontSize = 10.sp
                                    )
                                }
                                Button(
                                    onClick = {
                                        try {
                                            val intent = Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = JarvisAmber, contentColor = Color.Black),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("Beri Izin", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else if (isHotwordEnabled) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = JarvisEmerald.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisEmerald.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = JarvisEmerald,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "HUD melayang (overlay) siap & aktif di layar",
                                    color = JarvisEmerald,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // Test Action & Battery Optimization Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (!isHotwordEnabled) {
                                    viewModel.setHotwordEnabled(context, true)
                                }
                                viewModel.testTriggerHotword()
                                Toast.makeText(context, "Mendengarkan... Silakan katakan instruksi Anda!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisCyan),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisCyan.copy(alpha = 0.6f)),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Tes Suara Langsung", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Buka Pengaturan Baterai untuk mengizinkan aplikasi tetap berjalan", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = JarvisTextSecondary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisBorder),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Baterai Aktif", fontSize = 10.5.sp)
                        }
                    }
                }
            }
        }

        // MODEL & CUSTOM ENDPOINT CONFIGURATION CARD
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_endpoint_config_card"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = CardBorderTealViolet)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(36.dp),
                                shape = CircleShape,
                                color = JarvisTeal.copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, JarvisTeal)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = "AI Config",
                                        tint = JarvisTeal,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "MODEL & ENDPOINT",
                                    color = JarvisTeal,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "Provider: ${aiConfig.providerLabel}",
                                    color = JarvisTextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Button(
                            onClick = { showAiConfigDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisTeal.copy(alpha = 0.2f), contentColor = JarvisTeal),
                            border = androidx.compose.foundation.BorderStroke(1.dp, JarvisTeal.copy(alpha = 0.7f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Atur API", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = JarvisSurfaceVariant.copy(alpha = 0.6f),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, JarvisBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Model Aktif:", color = JarvisTextSecondary, fontSize = 10.5.sp)
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = JarvisCyan.copy(alpha = 0.15f),
                                    border = androidx.compose.foundation.BorderStroke(0.5.dp, JarvisCyan.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = aiConfig.modelName,
                                        color = JarvisCyan,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Endpoint:", color = JarvisTextSecondary, fontSize = 10.5.sp)
                                Text(
                                    text = if (aiConfig.baseUrl.length > 28) aiConfig.baseUrl.take(26) + "..." else aiConfig.baseUrl,
                                    color = JarvisTextPrimary,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
        // Status overview cards
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("status_overview_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "STATUS LAYANAN SISTEM",
                            color = JarvisCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isServerRunning) JarvisEmerald else JarvisAmber)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isServerRunning) "RUNNING" else "STANDBY",
                                color = if (isServerRunning) JarvisEmerald else JarvisAmber,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    HorizontalDivider(color = JarvisBorder.copy(alpha = 0.5f))

                    // Accessibility Service Status
                    ServiceRow(
                        title = "Accessibility Service",
                        subtitle = if (isAccessConnected) "Active • Window hierarchy & gesture engine" else "Disconnected • Tap to enable in Settings",
                        isActive = isAccessConnected,
                        actionLabel = if (isAccessConnected) "Ready" else "Enable",
                        onAction = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        }
                    )

                    // Local HTTP Server
                    ServiceRow(
                        title = "Local HTTP Loopback",
                        subtitle = "127.0.0.1:$port (Strictly localhost loopback)",
                        isActive = isServerRunning,
                        actionLabel = if (isServerRunning) "Stop" else "Start",
                        onAction = { viewModel.toggleServer() }
                    )

                    // Screen Capture MediaProjection
                    ServiceRow(
                        title = "Screen Share / Capture",
                        subtitle = if (isMediaProjActive) "Sesi screen share aktif • Siap untuk visi AI & tangkapan layar" else if (isAccessConnected) "Klik Aktifkan (atau otomatis fallback ke Aksesibilitas)" else "Izin tangkapan layar untuk inspeksi visual & AI",
                        isActive = isMediaProjActive,
                        actionLabel = if (isMediaProjActive) "Stop" else "Aktifkan",
                        onAction = {
                            if (isMediaProjActive) {
                                com.example.service.JarvisCompanionService.stopMediaProjection(context)
                                Toast.makeText(context, "Screen Share dinonaktifkan", Toast.LENGTH_SHORT).show()
                            } else {
                                onRequestMediaProjection()
                            }
                        }
                    )

                    // Storage & Files Access
                    val isStorageGranted = com.example.service.AdbShizukuManager.isManageStorageGranted()
                    ServiceRow(
                        title = "Storage & File Access",
                        subtitle = if (isStorageGranted) "All files access granted" else "Full filesystem access required for Termux & scripts",
                        isActive = isStorageGranted,
                        actionLabel = if (isStorageGranted) "Granted" else "Grant",
                        onAction = {
                            com.example.service.AdbShizukuManager.requestAllFilesAccess(context)
                        }
                    )

                    // Shizuku & ADB Connector
                    val isShizukuInstalled = com.example.service.AdbShizukuManager.isShizukuInstalled(context)
                    ServiceRow(
                        title = "Shizuku ADB Connector",
                        subtitle = if (isShizukuInstalled) "Shizuku manager detected • Elevated shell ready" else "Shizuku not installed (Tap to open/install)",
                        isActive = isShizukuInstalled,
                        actionLabel = if (isShizukuInstalled) "Installed" else "Install",
                        onAction = {
                            com.example.service.AdbShizukuManager.openShizukuManager(context)
                        }
                    )
                }
            }
        }

        // Security & Local Token Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("token_credentials_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = JarvisSurface),
                border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "KREDENSIAL LOKAL (X-Local-Token)",
                            color = JarvisTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        IconButton(
                            onClick = {
                                val newToken = viewModel.regenerateToken()
                                Toast.makeText(context, "New token generated: $newToken", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Regenerate Token", tint = JarvisCyan)
                        }
                    }

                    Text(
                        text = "Komunikasi Termux ↔ Android diamankan dengan token ini. API key HANYA disimpan di Termux (config.py/env var), tidak pernah di APK.",
                        color = JarvisTextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    // Token display & copy row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(JarvisSurfaceVariant)
                            .border(1.dp, JarvisBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("ACTIVE TOKEN", color = JarvisTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            Text(token, color = JarvisCyan, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                copyToClipboard(context, "X-Local-Token", token)
                                Toast.makeText(context, "Token copied!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan.copy(alpha = 0.2f), contentColor = JarvisCyan),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Token", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", fontSize = 12.sp)
                        }
                    }

                    // Endpoint display
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(JarvisSurfaceVariant)
                            .border(1.dp, JarvisBorder, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("LOCAL ENDPOINT", color = JarvisTextSecondary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            Text("http://127.0.0.1:$port", color = JarvisTextPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                        Button(
                            onClick = {
                                copyToClipboard(context, "Endpoint", "http://127.0.0.1:$port")
                                Toast.makeText(context, "Endpoint copied!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = JarvisBorder, contentColor = JarvisTextPrimary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Copy", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Telemetry Chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryChip(
                    icon = Icons.Default.BatteryChargingFull,
                    title = "Battery",
                    value = if (telemetry.batteryLevel >= 0) "${telemetry.batteryLevel}%" else "N/A",
                    detail = if (telemetry.isCharging) "Charging" else "Discharging",
                    modifier = Modifier.weight(1f)
                )
                TelemetryChip(
                    icon = Icons.Default.Wifi,
                    title = "Network",
                    value = if (telemetry.wifiConnected) "WiFi" else "Cellular",
                    detail = telemetry.wifiSsid.take(12),
                    modifier = Modifier.weight(1f)
                )
                TelemetryChip(
                    icon = Icons.Default.Apps,
                    title = "Active App",
                    value = telemetry.currentAppPackage.substringAfterLast(".").take(10),
                    detail = telemetry.currentAppPackage.take(14),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Live Request Logs Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "AKTIVITAS REQUEST LIVE",
                        color = JarvisTextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = JarvisCyan.copy(alpha = 0.2f),
                        shape = CircleShape
                    ) {
                        Text(
                            text = "${logs.size}",
                            color = JarvisCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                if (logs.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearLogs() }) {
                        Text("Clear", color = JarvisTextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }

        // Live Log Items
        if (logs.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = JarvisSurfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Sensors, contentDescription = null, tint = JarvisCyan.copy(alpha = 0.5f), modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Menunggu panggilan dari Termux Agent...", color = JarvisTextSecondary, fontSize = 13.sp)
                        Text("Requests to http://127.0.0.1:$port will appear here in real time.", color = JarvisTextSecondary.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
            }
        } else {
            items(logs, key = { it.id }) { logItem ->
                LogItemRow(logItem = logItem, onClick = { selectedLog = logItem })
            }
        }
    }

    // Details Dialog for Selected Log
    selectedLog?.let { log ->
        AlertDialog(
            onDismissRequest = { selectedLog = null },
            title = { Text("${log.method} ${log.path}", color = JarvisCyan, fontSize = 15.sp, fontFamily = FontFamily.Monospace) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Status: ${log.statusCode}", color = if (log.isError) JarvisRed else JarvisEmerald, fontWeight = FontWeight.Bold)
                    Text("Client: ${log.clientIp}", color = JarvisTextSecondary, fontSize = 12.sp)
                    Text("Summary: ${log.summary}", color = JarvisTextPrimary, fontSize = 13.sp)
                    if (log.payloadPreview.isNotEmpty()) {
                        Text("Payload / Response:", color = JarvisTeal, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Surface(
                            color = JarvisSurfaceVariant,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = log.payloadPreview,
                                color = JarvisTextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedLog = null }) {
                    Text("Close", color = JarvisCyan)
                }
            },
            containerColor = JarvisSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showAiConfigDialog) {
        AiConfigDialog(
            currentConfig = aiConfig,
            onSave = { apiKey, baseUrl, modelName, provider ->
                viewModel.updateAiConfig(apiKey, baseUrl, modelName, provider)
                showAiConfigDialog = false
            },
            onDismiss = { showAiConfigDialog = false }
        )
    }
}

@Composable
fun ServiceRow(
    title: String,
    subtitle: String,
    isActive: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isActive) JarvisEmerald else JarvisAmber)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(title, color = JarvisTextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(subtitle, color = JarvisTextSecondary, fontSize = 11.sp)
        }
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isActive) JarvisEmerald.copy(alpha = 0.15f) else JarvisCyan.copy(alpha = 0.2f),
                contentColor = if (isActive) JarvisEmerald else JarvisCyan
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 36.dp)
        ) {
            Text(actionLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TelemetryChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = JarvisSurface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder.copy(alpha = 0.85f)))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(JarvisCyan.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = JarvisCyan, modifier = Modifier.size(14.dp))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    title,
                    color = JarvisTextSecondary,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                value,
                color = JarvisTextPrimary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(detail, color = JarvisTextSecondary, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
fun LogItemRow(logItem: ServerLogItem, onClick: () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val timeStr = remember(logItem.timestamp) { timeFormat.format(Date(logItem.timestamp)) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = JarvisSurface),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(JarvisBorder.copy(alpha = 0.6f)))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Surface(
                    color = if (logItem.isError) JarvisRed.copy(alpha = 0.2f) else JarvisCyan.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = logItem.method,
                        color = if (logItem.isError) JarvisRed else JarvisCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(logItem.path, color = JarvisTextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    Text(logItem.summary, color = JarvisTextSecondary, fontSize = 11.sp, maxLines = 1)
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    color = if (logItem.statusCode == 200) JarvisEmerald.copy(alpha = 0.2f) else JarvisAmber.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "${logItem.statusCode}",
                        color = if (logItem.statusCode == 200) JarvisEmerald else JarvisAmber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(timeStr, color = JarvisTextSecondary, fontSize = 10.sp)
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
}
